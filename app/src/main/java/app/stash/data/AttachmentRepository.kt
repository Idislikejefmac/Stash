package app.stash.data

import androidx.room.withTransaction
import app.stash.capture.InboundAttachment
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureTextRules
import app.stash.security.AttachmentStore
import app.stash.security.AttachmentUnreadable
import java.io.FilterInputStream
import java.io.InputStream

/**
 * The files of a note (docs/cards/16a section D). The only code that reads `file_key`. Screens get [AttachmentInfo].
 *
 * Crash-safe order: adding writes the **file first, then the row** (the file is written before it reaches here);
 * deleting removes the **row first, then the file**. A crash in between leaves a file with no row, never a row with
 * no file, and [sweep] removes it.
 */
class AttachmentRepository(
    private val db: StashDatabase,
    private val store: AttachmentStore,
    private val clock: Clock = Clock.System,
) {
    private val revisions = RevisionWriter(db, clock)
    private val index = SearchIndex(db)

    /** Card 16f item 8: the note's search document holds its pictures' words, so it is built again when they change. Call inside a transaction. */
    private suspend fun reindex(captureId: String) {
        val c = db.captures().get(captureId) ?: return
        if (c.deletedAt == null) index.indexCapture(c)
    }

    /** Every file of the note, pictures and (later) audio, in order. */
    suspend fun forCapture(captureId: String): List<AttachmentInfo> = db.attachments().infoForCapture(captureId)

    /** Pictures only, in order; the first is the note's cover. Audio is never here. */
    suspend fun pictures(captureId: String): List<AttachmentInfo> = db.attachments().pictureInfoForCapture(captureId)

    /** Card 16d item 13: the number of pictures and their total size, for Settings. */
    suspend fun pictureStats(): PictureStats = db.attachments().pictureStats()

    suspend fun cover(captureId: String): AttachmentInfo? = pictures(captureId).firstOrNull()

    /**
     * Files added to a note that already exists. One transaction: rows appended after the note's other files, one
     * `PICTURE_ADDED` revision per new file, the capture's `updated_at` stamped. Returns the updated capture, or null
     * (nothing inserted) if the capture does not exist or is in the bin. A file whose bytes the note already has adds
     * nothing. If the transaction fails it throws, and the files are left on disk for a retry or the sweep.
     */
    suspend fun addTo(captureId: String, attachments: List<InboundAttachment>): CaptureEntity? {
        val result = db.withTransaction {
            val c = db.captures().get(captureId) ?: return@withTransaction null
            if (c.deletedAt != null) return@withTransaction null
            val added = AttachmentRows.append(db, captureId, attachments, clock.now())
            for (a in added) revisions.write(RevisionKind.PICTURE_ADDED, captureId = captureId, old = "", new = a.id)
            // Card 16g: a picture added by any route closes the offer, so it cannot come back if the picture is removed.
            val closes = c.photoHint == PhotoHint.PENDING && added.any { it.kind != AttachmentKind.AUDIO }
            val base = if (closes) c.copy(photoHint = PhotoHint.DISMISSED) else c
            val updated = if (added.isEmpty()) c else base.stamped(clock).also { db.captures().update(it); index.indexCapture(it) }
            if (closes) Dbg.event(DbgComponent.OFFER, DbgCode.DISMISSED, n1 = 3, refId = captureId)
            updated to added
        } ?: return null
        AttachmentRows.announce(result.second)
        return result.first
    }

    /**
     * Remove one file, at once and for good (there is no bin for a single picture; card 16d asks first). In one
     * transaction: the row goes, a tombstone and a revision are written (`PICTURE_REMOVED`, or `AUDIO_REMOVED` for a
     * recording: card 26c), the capture is stamped. Then the file is deleted; if that fails the sweep removes it later.
     * Returns false if there was no such row.
     */
    suspend fun remove(attachmentId: String): Boolean = removeIf(attachmentId) { _, _ -> true } == Removal.REMOVED

    enum class Removal { REMOVED, NO_ROW, REFUSED }

    /**
     * [remove], but only if [allowed] says yes, asked inside the same transaction that deletes the row (card 26c uses
     * it to refuse removing the recording of a note that has no words).
     */
    internal suspend fun removeIf(attachmentId: String, allowed: (AttachmentEntity, CaptureEntity?) -> Boolean): Removal {
        var kind: AttachmentKind? = null
        val outcome = db.withTransaction {
            val a = db.attachments().get(attachmentId) ?: return@withTransaction Removal.NO_ROW to null
            val c = db.captures().get(a.captureId)
            if (!allowed(a, c)) return@withTransaction Removal.REFUSED to null
            kind = a.kind
            db.attachments().delete(listOf(a.id))
            db.tombstones().insert(TombstoneEntity(Ids.new(), TABLE, a.id, clock.now()))
            val audio = a.kind == AttachmentKind.AUDIO
            revisions.write(if (audio) RevisionKind.AUDIO_REMOVED else RevisionKind.PICTURE_REMOVED, captureId = a.captureId, old = a.id, new = "")
            if (c != null) {
                // Card 16g: a note that loses its picture is never offered one afterwards (the offer is made once).
                db.captures().update((if (!audio && c.photoHint == PhotoHint.NONE) c.copy(photoHint = PhotoHint.DISMISSED) else c).stamped(clock))
            }
            reindex(a.captureId)
            Removal.REMOVED to a.fileName
        }
        val gone = outcome.second ?: return outcome.first
        runCatching { store.delete(gone) }
        Dbg.event(if (kind == AttachmentKind.AUDIO) DbgComponent.AUDIO else DbgComponent.PICTURE, DbgCode.REMOVED, refId = attachmentId)
        return Removal.REMOVED
    }

    /**
     * A picture's plain bytes, decrypted as they are read. Throws [AttachmentUnreadable] when the row or the file is
     * gone or damaged, and (`WRONG_KIND`) for a recording: recordings are opened only by [openRecording] (card 26c).
     */
    suspend fun openBytes(attachmentId: String): InputStream = open(attachmentId, audio = false)

    /**
     * A recording's plain bytes (card 26c). Called only from `app.stash.audio`, `app.stash.pipeline` and (card 10)
     * `app.stash.backup`; a source-scan test keeps it so, which is the code form of "audio never leaves the phone".
     */
    suspend fun openRecording(attachmentId: String): InputStream = open(attachmentId, audio = true)

    private suspend fun open(attachmentId: String, audio: Boolean): InputStream {
        val a = db.attachments().get(attachmentId) ?: throw unreadable(AttachmentUnreadable(AttachmentUnreadable.Reason.NO_ROW), attachmentId)
        if ((a.kind == AttachmentKind.AUDIO) != audio) throw unreadable(AttachmentUnreadable(AttachmentUnreadable.Reason.WRONG_KIND), attachmentId)
        val stream = try {
            store.open(a.fileName, a.fileKey, a.id)
        } catch (e: AttachmentUnreadable) {
            throw unreadable(e, attachmentId)
        }
        return object : FilterInputStream(stream) {
            override fun read(): Int = logged { super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = logged { `in`.read(b, off, len) }

            private inline fun <T> logged(f: () -> T): T = try {
                f()
            } catch (e: AttachmentUnreadable) {
                throw unreadable(e, attachmentId)
            }
        }
    }

    /**
     * Card 26c item 16: give a row new bytes (26b's compaction of a WAV fallback). The new file is written first, under
     * a new name and a new key (the attachment id, and so the file's associated data, stay the same); one transaction
     * then points the row at it; then the old file is deleted (if that fails, [sweep] removes it). A crash leaves an
     * orphan file for [sweep], never a row without a file. Returns false (and deletes the new file) if the row is gone.
     */
    internal suspend fun replaceFile(attachmentId: String, plain: ByteArray, mime: String): Boolean {
        val a = db.attachments().get(attachmentId) ?: return false
        val key = store.newKey()
        val name = store.newFileName()
        val w = store.write(name, key, a.id, java.io.ByteArrayInputStream(plain))
        // If this throws, the new file is left for the sweep rather than deleted here: the row must never be able to
        // point at a file that is gone.
        val old = db.withTransaction {
            val cur = db.attachments().get(attachmentId) ?: return@withTransaction null
            db.attachments().replaceFile(cur.id, name, key, mime, w.byteSize, w.sha256, clock.now())
            cur.fileName
        }
        if (old == null) {
            runCatching { store.delete(name) }
            return false
        }
        runCatching { store.delete(old) }
        return true
    }

    /** The words read from a picture (card 16f). Not an owner action, so no revision. */
    suspend fun setOcr(attachmentId: String, text: String?, engine: String?) = db.withTransaction {
        db.attachments().updateOcr(attachmentId, text, engine, clock.now())
        db.attachments().get(attachmentId)?.let { reindex(it.captureId) }
    }

    /** Card 16f item 12: pictures no reader has looked at yet, at most [limit]. */
    suspend fun unread(limit: Int = PictureTextRules.CATCH_UP_BATCH): List<AttachmentInfo> = db.attachments().unreadPictures(limit)

    /** A reader looked and could not read: the picture goes to the back of the line for the next catch-up. No words, no re-index. */
    suspend fun touchUnread(attachmentId: String) = db.attachments().updateOcr(attachmentId, null, null, clock.now())

    /**
     * Housekeeping, run with the daily purge: delete every file that has no row and is older than 24 hours (a younger
     * one may belong to a note still being made), and every `.tmp` older than 24 hours. A row with no file is never
     * deleted here (its row still holds the words read from the picture); the consistency check reports it.
     * Returns how many files were deleted.
     */
    suspend fun sweep(): Int {
        val now = clock.now()
        // Read the rows after listing the files, so a file whose row is added in between is still seen as owned.
        val files = store.list()
        val temps = store.temps()
        val owned = db.attachments().fileNames().toSet()
        var n = 0
        for (f in files) if (f !in owned && store.ageMs(f, now) > SWEEP_AFTER_MS && runCatching { store.delete(f) }.getOrDefault(false)) n++
        for (t in temps) if (store.ageMs(t, now) > SWEEP_AFTER_MS && runCatching { store.deleteTemp(t) }.getOrDefault(false)) n++
        Dbg.event(DbgComponent.PICTURE, DbgCode.SWEPT, n1 = n)
        return n
    }

    private fun unreadable(e: AttachmentUnreadable, attachmentId: String): AttachmentUnreadable {
        Dbg.event(DbgComponent.PICTURE, DbgCode.UNREADABLE, n1 = e.reason.code, refId = attachmentId)
        return e
    }

    companion object {
        const val TABLE = "attachment"
        const val SWEEP_AFTER_MS = 24L * 3600_000L
    }
}

/** Writing attachment rows, shared by [CaptureRepository.insertCapture] and [AttachmentRepository.addTo]. Call inside a transaction. */
internal object AttachmentRows {
    /**
     * Append [list] to the capture's files: positions continue after the highest one (gaps are never filled). A file
     * whose `sha256` the capture already has adds nothing. Returns the rows actually inserted.
     */
    suspend fun append(db: StashDatabase, captureId: String, list: List<InboundAttachment>, now: Long): List<AttachmentEntity> {
        if (list.isEmpty()) return emptyList()
        val existing = db.attachments().forCapture(captureId)
        val known = existing.map { it.sha256 }.toMutableSet()
        var recordings = existing.count { it.kind == AttachmentKind.AUDIO }
        var next = (db.attachments().maxPosition(captureId) ?: -1) + 1
        val out = mutableListOf<AttachmentEntity>()
        for (a in list) {
            if (!known.add(a.sha256)) continue
            // Card 26c: a note has at most one recording. Thrown inside the caller's transaction, so nothing is kept.
            if (a.kind == AttachmentKind.AUDIO && ++recordings > 1) throw SecondRecording()
            val row = AttachmentEntity(
                id = a.id, captureId = captureId, kind = a.kind, position = next++, mime = a.mime, fileName = a.fileName,
                fileKey = a.key, formatVersion = AttachmentStore.FORMAT_VERSION, byteSize = a.byteSize, sha256 = a.sha256,
                width = a.width, height = a.height, durationMs = a.durationMs, ocrText = a.ocrText, ocrEngine = a.ocrEngine,
                source = a.source, createdAt = now, updatedAt = now,
            )
            db.attachments().insert(row)
            out += row
        }
        return out
    }

    /** After the transaction committed: one event per stored file (numbers and ids only; a recording also gives its length). */
    fun announce(rows: List<AttachmentEntity>) {
        for (r in rows) {
            if (r.kind == AttachmentKind.AUDIO) Dbg.event(DbgComponent.AUDIO, DbgCode.STORED, n1 = r.byteSize, n2 = r.durationMs, refId = r.id)
            else Dbg.event(DbgComponent.PICTURE, DbgCode.STORED, n1 = r.byteSize, refId = r.id)
        }
    }
}

/** Card 26c: a second recording for one note. The message carries nothing of the note. */
class SecondRecording : IllegalStateException("a note has at most one recording")
