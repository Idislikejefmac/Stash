package app.stash.data

import androidx.room.withTransaction
import app.stash.capture.InboundAttachment
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureOffer
import app.stash.domain.Titles
import app.stash.security.AttachmentStore
import kotlinx.coroutines.flow.Flow

/** Writes the record of one thing the owner did (docs/STORAGE.md rule 3). Call it inside the same transaction as the change. */
class RevisionWriter(private val db: StashDatabase, private val clock: Clock) {
    suspend fun write(
        what: RevisionKind,
        captureId: String? = null,
        recordId: String? = null,
        subjectId: String? = null,
        old: String = "",
        new: String = "",
    ) {
        db.revisions().insert(
            RevisionEntity(Ids.new(), clock.now(), captureId, recordId, subjectId, what, old, new),
        )
    }
}

/** What he said, and the ways the owner can change or remove it. */
class CaptureRepository(
    private val db: StashDatabase,
    private val index: SearchIndex,
    private val clock: Clock = Clock.System,
    /** Where [purge] deletes a note's files after its rows (card 16a). Null (the pipeline, which never purges): files are left for the sweep. */
    private val attachments: AttachmentStore? = null,
) {
    private val revisions = RevisionWriter(db, clock)

    fun observeLive(): Flow<List<CaptureWithRecords>> = db.captures().observeLive()

    suspend fun live(): List<CaptureWithRecords> = db.captures().live()

    suspend fun get(id: String): CaptureEntity? = db.captures().get(id)

    /**
     * Save first: this is the one write that must never wait on understanding. With [attachments] (files the store has
     * already written) their rows go in the same transaction, positions 0, 1, 2 … in list order. Empty [text] is
     * allowed only with at least one attachment.
     */
    suspend fun insertCapture(
        rawText: String,
        text: String,
        source: CaptureSource = CaptureSource.VOICE,
        capturedLocked: Boolean? = null,
        sensitive: Boolean = false,
        lat: Double? = null,
        lon: Double? = null,
        accuracyM: Double? = null,
        appVersion: String? = null,
        zone: String? = null,
        sensitiveReason: String? = null,
        /** Fixed at the moment of capture by the inbound sink; null means "now" and a new id. */
        id: String? = null,
        createdAt: Long? = null,
        attachments: List<InboundAttachment> = emptyList(),
        /** Card 26c: a recording with no words yet is saved as `DONE` here (nothing to read) and `PENDING` below. */
        extractState: ExtractState = ExtractState.PENDING,
        transcribeState: TranscribeState? = null,
    ): CaptureEntity {
        require(text.isNotEmpty() || attachments.isNotEmpty()) { "a note with no words needs a file" }
        val (c, added) = db.withTransaction {
            val now = clock.now()
            val c = CaptureEntity(
                id = id ?: Ids.new(), createdAt = createdAt ?: now, updatedAt = now, zone = zone, rawText = rawText, text = text,
                source = source, capturedLocked = capturedLocked, sensitive = sensitive, sensitiveReason = sensitiveReason,
                lat = lat, lon = lon, accuracyM = accuracyM, appVersion = appVersion,
                extractState = extractState, transcribeState = transcribeState, transcribeAttempts = 0,
            )
            db.captures().insert(c)
            val added = AttachmentRows.append(db, c.id, attachments, now)
            index.indexCapture(c)
            c to added
        }
        AttachmentRows.announce(added)
        return c
    }

    /** The owner corrected the words. Records a revision and asks for the capture to be read again. */
    suspend fun updateText(captureId: String, newText: String) = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction
        if (c.text == newText) return@withTransaction
        val updated = c.copy(text = newText, extractState = ExtractState.PENDING).stamped(clock)
        db.captures().update(updated)
        revisions.write(RevisionKind.TEXT_CORRECTED, captureId = captureId, old = c.text, new = newText)
        index.indexCapture(updated)
    }

    /** Captures that are waiting to be read: PENDING or RETRY, oldest first. */
    suspend fun toRead(): List<CaptureEntity> = db.captures().toRead()

    /** Does any record of this capture belong to the owner (pinned)? */
    suspend fun hasPinnedRecord(captureId: String): Boolean = db.records().forCapture(captureId).any { it.pinned }

    /** Text hash of the extraction that is current for this capture, if any. */
    suspend fun currentTextHash(captureId: String): String? =
        db.extractions().forCapture(captureId).lastOrNull { it.status == ExtractionStatus.CURRENT }?.textHash

    /** Nothing new to learn from reading it again: the state goes to DONE and nothing else changes. */
    suspend fun markDone(captureId: String) = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction
        if (c.extractState != ExtractState.DONE) db.captures().update(c.copy(extractState = ExtractState.DONE).stamped(clock))
    }

    /** "Read it again": the capture goes back to PENDING. Pinned records stay (see [RecordRepository.replaceRecordsFor]). */
    suspend fun requestReread(captureId: String) = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction
        if (c.deletedAt != null) return@withTransaction
        db.captures().update(c.copy(extractState = ExtractState.PENDING, extractAttempts = 0).stamped(clock))
    }

    /**
     * Card 16g: the picture offer's own state. No revision: it is not something he needs to be able to undo. Returns
     * true if the value changed; false for a capture that does not exist or already has that value.
     */
    suspend fun setPhotoHint(captureId: String, value: PhotoHint): Boolean = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction false
        if (c.photoHint == value) return@withTransaction false
        db.captures().update(c.copy(photoHint = value).stamped(clock))
        true
    }

    /** Card 16g: is the offer showing for this note right now? One row read and one count, by id. */
    suspend fun pictureOfferShown(captureId: String): Boolean {
        val c = db.captures().get(captureId) ?: return false
        if (c.deletedAt != null) return false
        return PictureOffer.show(c.photoHint, db.attachments().picturesForCapture(captureId).size, c.sensitive, clock.now() - c.createdAt)
    }

    /** Card 16g: the daily upkeep closes every offer older than 24 hours (by this repository's clock). Returns how many. */
    suspend fun dismissStaleOffers(): Int {
        val now = clock.now()
        val n = db.captures().dismissStaleOffers(PictureOffer.staleBefore(now), now)
        if (n > 0) Dbg.event(DbgComponent.OFFER, DbgCode.DISMISSED, n1 = 2, n2 = n.toLong())
        return n
    }

    suspend fun setSensitive(captureId: String, sensitive: Boolean, reason: String? = null) = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction
        if (c.sensitive == sensitive) return@withTransaction
        db.captures().update(c.copy(sensitive = sensitive, sensitiveReason = if (sensitive) reason else null).stamped(clock))
        revisions.write(
            if (sensitive) RevisionKind.MADE_PRIVATE else RevisionKind.MADE_PUBLIC,
            captureId = captureId, old = c.sensitive.toString(), new = sensitive.toString(),
        )
    }

    /**
     * Hide a capture and its records and take them out of search. It can be restored until [purgeDeletedOlderThan]
     * removes it. Its attachments are not touched: they are hidden because every listing goes through live captures.
     */
    suspend fun softDelete(captureId: String) = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction
        if (c.deletedAt != null) return@withTransaction
        val now = clock.now()
        db.captures().update(c.copy(deletedAt = now).stamped(clock))
        db.records().setDeletedForCapture(captureId, now, now)
        index.remove(listOf(captureId) + db.records().forCapture(captureId).map { it.id })
        revisions.write(RevisionKind.DELETED, captureId = captureId)
    }

    suspend fun restore(captureId: String) = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction
        if (c.deletedAt == null) return@withTransaction
        val restored = c.copy(deletedAt = null).stamped(clock)
        db.captures().update(restored)
        db.records().setDeletedForCapture(captureId, null, clock.now())
        index.indexCapture(restored)
        db.records().forCapture(captureId).forEach { index.indexRecord(it) }
        revisions.write(RevisionKind.RESTORED, captureId = captureId)
    }

    /**
     * The one real delete (docs/STORAGE.md rule 7). In one transaction: the capture's extractions (they hold the
     * model's copy of the words), its writing-outs (`transcription`, card 26c), records and their side rows,
     * revisions, search documents, attachment rows, pictures and recording alike (one
     * tombstone each); the words of any answer trace that used it (card 08); then one content-free tombstone for the capture; then any subject left with no records. After
     * the transaction commits, the attachments' files are deleted (row first, then file: card 16a); a file that cannot
     * be deleted is left for [AttachmentRepository.sweep].
     */
    suspend fun purge(captureId: String): Boolean {
        val files = mutableListOf<String>()
        val done = db.withTransaction {
            files.clear()
            val c = db.captures().get(captureId) ?: return@withTransaction false
            val records = db.records().forCapture(captureId)
            val recordIds = records.map { it.id }
            val now = clock.now()
            // Records that superseded one of these now supersede whatever it superseded, so history chains stay whole.
            for (r in records) db.records().relink(r.id, r.supersedesRecordId, now)
            if (recordIds.isNotEmpty()) {
                db.readings().deleteForRecords(recordIds)
                db.tasks().deleteForRecords(recordIds)
                db.whereabouts().deleteForRecords(recordIds)
                db.revisions().deleteForRecords(recordIds)
                db.records().delete(recordIds)
            }
            db.revisions().deleteForCapture(captureId)
            db.extractions().deleteForCapture(captureId)
            // Card 26c: every writing-out of the recording holds the words too.
            db.transcriptions().deleteForCapture(captureId)
            index.remove(listOf(captureId) + recordIds)
            val atts = db.attachments().forCapture(captureId)
            if (atts.isNotEmpty()) {
                db.attachments().deleteForCapture(captureId)
                for (a in atts) db.tombstones().insert(TombstoneEntity(Ids.new(), AttachmentRepository.TABLE, a.id, now))
                files += atts.map { it.fileName }
            }
            // Card 08 E2: an answer that used this note keeps its steps' ids and counts and loses its words.
            AnswerTraceRepository.blankFor(db, (listOf(captureId) + recordIds).toSet(), now)
            db.captures().delete(c.id)
            db.tombstones().insert(TombstoneEntity(Ids.new(), "capture", captureId, now))
            removeOrphanSubjects(records)
            true
        }
        // Merge the index so deleted words do not linger in its internal tables.
        if (done) db.search().optimize()
        if (done) attachments?.let { store -> for (f in files) runCatching { store.delete(f) } }
        return done
    }

    /** Really delete every capture that has been in the bin for [days] days. Returns how many. */
    suspend fun purgeDeletedOlderThan(days: Int): Int {
        val cutoff = clock.now() - days * 24L * 3600_000L
        val ids = db.captures().deletedBefore(cutoff)
        return ids.count { purge(it) }
    }

    private suspend fun removeOrphanSubjects(removed: List<RecordEntity>) {
        val candidates = removed.flatMap { listOfNotNull(it.subjectId, it.placeSubjectId) }.toSet()
        for (id in candidates) {
            val used = db.records().countForSubject(id) + db.subjects().whereaboutsUses(id) +
                db.subjects().taskUses(id) + db.readings().countForSubject(id)
            if (used == 0) {
                db.metrics().deleteUnusedForSubject(id)
                db.placeLocations().deleteForSubject(id)
                db.subjects().delete(id)
            }
        }
    }
}

/** A record with the extra rows that belong to it. */
data class RecordBundle(
    val record: RecordEntity,
    val whereabouts: WhereaboutsEntity? = null,
    val task: TaskEntity? = null,
    val readings: List<ReadingEntity> = emptyList(),
)

/** What the app worked out from a capture, and the owner's actions on it. */
class RecordRepository(
    private val db: StashDatabase,
    private val index: SearchIndex,
    private val clock: Clock = Clock.System,
) {
    private val revisions = RevisionWriter(db, clock)

    /**
     * Store a new extraction and its records, all in one transaction: the previous CURRENT extraction becomes
     * REPLACED; the capture's unpinned records and their side rows are deleted; the new ones are inserted; the
     * search index follows; the capture becomes DONE. Pinned records are left exactly as they are. If anything
     * fails, nothing changes.
     */
    suspend fun replaceRecordsFor(
        captureId: String,
        extraction: ExtractionEntity,
        bundles: List<RecordBundle>,
        /** The state the capture ends in: DONE, or RETRY when a model could not be reached (card 13). */
        finalState: ExtractState = ExtractState.DONE,
        /** New value for the attempt counter, or null to leave it. */
        attempts: Int? = null,
    ): List<String> =
        db.withTransaction {
            val capture = db.captures().get(captureId) ?: error("No such capture")
            val now = clock.now()

            db.extractions().markReplaced(captureId)
            db.extractions().insert(extraction.copy(captureId = captureId, status = ExtractionStatus.CURRENT))

            val existing = db.records().forCapture(captureId)
            val doomed = existing.filter { !it.pinned }
            val doomedIds = doomed.map { it.id }
            for (r in doomed) db.records().relink(r.id, r.supersedesRecordId, now)
            if (doomedIds.isNotEmpty()) {
                db.readings().deleteForRecords(doomedIds)
                db.tasks().deleteForRecords(doomedIds)
                db.whereabouts().deleteForRecords(doomedIds)
                db.records().delete(doomedIds)
                index.remove(doomedIds)
            }

            // A new record that says what a pinned one already says (same kind, same title once tidied) is dropped.
            val pinnedKeys = existing.filter { it.pinned }.map { it.kind to Titles.norm(it.title) }.toSet()
            val kept = bundles.filter { (it.record.kind to Titles.norm(it.record.title)) !in pinnedKeys }

            val ids = mutableListOf<String>()
            for (b in kept) {
                val rec = b.record.copy(captureId = captureId, extractionId = extraction.id, deletedAt = capture.deletedAt).stamped(clock)
                db.records().insert(rec)
                ids += rec.id
                b.whereabouts?.let { db.whereabouts().insert(it.copy(recordId = rec.id).stamped(clock)) }
                b.task?.let { db.tasks().insert(it.copy(recordId = rec.id).stamped(clock)) }
                b.readings.forEach { db.readings().insert(it.copy(recordId = rec.id).stamped(clock)) }
                if (capture.deletedAt == null) index.indexRecord(rec)
            }
            db.captures().update(
                capture.copy(extractState = finalState, extractAttempts = attempts ?: capture.extractAttempts).stamped(clock),
            )
            ids
        }

    /**
     * Set the reason a record is on the attention list (card 05 sets `DID_THIS_FINISH:`, `SAME_AS_TASK:`). The app, not the
     * owner, does this: no pin, no revision. A reason a check already set is kept unless [replace] is true.
     */
    suspend fun setAttention(recordId: String, reason: String, replace: Boolean = false) = db.withTransaction {
        val r = db.records().get(recordId) ?: return@withTransaction
        val current = r.attention
        val weak = current == null || current.startsWith(app.stash.domain.Attention.SAME_AS_PREFIX)
        if (!replace && !weak) return@withTransaction
        if (current == reason) return@withTransaction
        db.records().update(r.copy(attention = reason).stamped(clock))
    }

    /** The owner marked this record as theirs: re-reading the capture will not replace it. */
    suspend fun pin(recordId: String) = db.withTransaction {
        val r = db.records().get(recordId) ?: return@withTransaction
        if (r.pinned) return@withTransaction
        db.records().update(r.copy(pinned = true).stamped(clock))
        revisions.write(RevisionKind.PINNED, captureId = r.captureId, recordId = recordId)
    }

    /**
     * The owner fixed a word: apply [fix] to every text the capture's records show (title, quote, the item, place and
     * person words). Each changed record is pinned and gets an EDITED revision with its old and new title.
     */
    suspend fun rewriteTexts(captureId: String, fix: (String) -> String) = db.withTransaction {
        for (r in db.records().forCapture(captureId)) {
            val newRecord = r.copy(
                title = fix(r.title), quote = fix(r.quote), placeText = r.placeText?.let(fix), spotText = r.spotText?.let(fix),
                pinned = true,
            )
            val w = db.whereabouts().get(r.id)
            val newW = w?.copy(
                itemText = fix(w.itemText), whereText = w.whereText?.let(fix), personText = w.personText?.let(fix),
                saidDateText = w.saidDateText?.let(fix),
            )
            val t = db.tasks().get(r.id)
            val newT = t?.copy(atPlaceText = t.atPlaceText?.let(fix), dueText = t.dueText?.let(fix))
            val changed = newRecord.copy(pinned = r.pinned) != r || newW != w || newT != t
            if (!changed) continue
            db.records().update(newRecord.stamped(clock))
            if (newW != null) db.whereabouts().update(newW.stamped(clock))
            if (newT != null) db.tasks().update(newT.stamped(clock))
            revisions.write(RevisionKind.EDITED, captureId = captureId, recordId = r.id, old = r.title, new = newRecord.title)
            index.indexRecord(newRecord)
        }
    }
}
