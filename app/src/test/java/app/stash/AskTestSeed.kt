package app.stash

import app.stash.data.AttachmentEntity
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.data.Direction
import app.stash.data.ExtractionEntity
import app.stash.data.ExtractionRunner
import app.stash.data.ExtractionStatus
import app.stash.data.Ids
import app.stash.data.RecordBundle
import app.stash.data.RecordEntity
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.data.SubjectEntity
import app.stash.data.SubjectType
import app.stash.data.TaskEntity
import app.stash.data.Volatility
import app.stash.data.WhereaboutsEntity
import app.stash.domain.RecordKind
import app.stash.model.rules.RulesExtractor
import java.time.ZoneOffset

/** Seeds notes for card 08's tests straight into a real (in-memory) database, the way the pipeline leaves them. */
class AskTestSeed(private val db: StashDatabase, private val repo: Repository, private val clock: FakeClock) {
    val now = clock.now()
    private val DAY = 86_400_000L

    data class Made(val captureId: String, val recordId: String)

    fun subject(name: String, type: SubjectType = SubjectType.ITEM, volatility: Volatility? = null, sensitive: Boolean = false, aliases: String = "[]"): SubjectEntity {
        val s = SubjectEntity(Ids.new(), type, name, name.lowercase(), aliases, volatility, sensitive, null, now, now)
        kotlinx.coroutines.runBlocking { db.subjects().insert(s) }
        return s
    }

    suspend fun note(
        text: String,
        kind: RecordKind,
        daysAgo: Double = 0.0,
        title: String = text,
        sensitive: Boolean = false,
        subject: SubjectEntity? = null,
        placeText: String? = null,
        item: String? = null,
        itemSubject: SubjectEntity? = null,
        where: String? = null,
        person: String? = null,
        direction: Direction? = null,
        saidDate: String? = null,
        returnedAt: Long? = null,
        supersedes: String? = null,
        happenedOn: String? = null,
        atPlace: String? = null,
        pictures: Int = 0,
    ): Made {
        val c = repo.captures.insertCapture(text, text, CaptureSource.VOICE, sensitive = sensitive, createdAt = now - (daysAgo * DAY).toLong())
        val x = ExtractionEntity(Ids.new(), c.id, now, "test", 1, "h", "{}", ExtractionStatus.CURRENT)
        val rid = Ids.new()
        val rec = RecordEntity(
            rid, c.id, x.id, 0, kind, title, text, subjectId = subject?.id, placeText = placeText, supersedesRecordId = supersedes,
            happenedOn = happenedOn, createdAt = now, updatedAt = now,
        )
        val w = if (direction != null) WhereaboutsEntity(
            rid, itemSubject?.id, item ?: text, where, null, person, direction, null, saidDate, null, returnedAt, now, now,
        ) else null
        val t = if (kind == RecordKind.TASK) TaskEntity(rid, atPlaceText = atPlace, lastActiveAt = c.createdAt, createdAt = now, updatedAt = now) else null
        repo.records.replaceRecordsFor(c.id, x, listOf(RecordBundle(rec, w, t)))
        repeat(pictures) { i ->
            db.attachments().insert(
                AttachmentEntity(
                    Ids.new(), c.id, AttachmentKind.PHOTO, i, "image/webp", Ids.new(), ByteArray(32), 1, 100, "00", 10, 10, null, null, null,
                    AttachmentSource.CAMERA, now, now,
                ),
            )
        }
        return Made(c.id, rid)
    }

    /** A note read by the offline rules (readings come out as rows, as on the phone). */
    suspend fun said(text: String, daysAgo: Int, sensitive: Boolean = false): String {
        val c = repo.captures.insertCapture(text, text, CaptureSource.VOICE, sensitive = sensitive, createdAt = now - daysAgo * DAY)
        ExtractionRunner(db, repo.records, clock) { ZoneOffset.UTC }.runLocal(c.id, RulesExtractor())
        return c.id
    }
}
