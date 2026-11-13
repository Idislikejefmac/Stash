package app.stash.domain

import app.stash.data.CaptureEntity
import app.stash.data.Direction
import app.stash.data.DueKind
import app.stash.data.Ids
import app.stash.data.MetricEntity
import app.stash.data.ReadingEntity
import app.stash.data.RecordBundle
import app.stash.data.RecordEntity
import app.stash.data.SubjectEntity
import app.stash.data.SubjectType
import app.stash.data.TaskEntity
import app.stash.data.WhereaboutsEntity
import java.time.LocalDate
import java.time.ZoneId

/** A current (not superseded, not deleted) placement or loan, as the projector needs to see it. */
data class ItemRecordRef(
    val recordId: String,
    val captureId: String,
    val itemSubjectId: String,
    val kind: RecordKind,
    val direction: Direction,
    val copyLabel: String?,
    val returnedAt: Long?,
    /** When its capture was made. */
    val capturedAt: Long,
)

/** Everything the projector reads besides the capture's own records. The caller loads it; nothing here writes. */
data class ProjectionContext(
    val existingSubjects: List<SubjectEntity> = emptyList(),
    val existingMetrics: List<MetricEntity> = emptyList(),
    val currentItemRecords: List<ItemRecordRef> = emptyList(),
    /** Context label (`T1`) to the open task's record id. */
    val openTasks: Map<String, String> = emptyMap(),
    val today: LocalDate,
    val zone: ZoneId,
    val now: Long,
    val newId: () -> String = Ids::new,
)

data class ReturnedMark(val loanRecordId: String, val returnedAt: Long)

/** The rows to write for one extraction. The projector only builds them; a repository writes them in one transaction. */
data class Projection(
    val newSubjects: List<SubjectEntity>,
    val newMetrics: List<MetricEntity>,
    val bundles: List<RecordBundle>,
    /** Older placements or loans that a new record replaced: they become pinned. */
    val olderToPin: List<String>,
    /** Open loans that a "gave it back" record closed. */
    val returned: List<ReturnedMark>,
    /** Some record describes a physical thing a picture would help find again. */
    val photoHint: Boolean,
)

/** Turns checked extractor output into rows (docs/cards/03 section E). Pure: returns rows, writes nothing. */
object Projector {
    fun project(
        capture: CaptureEntity,
        extractionId: String,
        checked: List<CheckedRecord>,
        ctx: ProjectionContext,
    ): Projection {
        val subjects = SubjectResolver(ctx.existingSubjects, ctx.now, ctx.newId)
        val metrics = MetricResolver(ctx.existingMetrics, ctx.now, ctx.newId)
        val bundles = mutableListOf<RecordBundle>()
        val toPin = linkedSetOf<String>()
        val returned = mutableListOf<ReturnedMark>()
        val latestNewByItem = mutableMapOf<String, String>()
        var photoHint = false

        for ((seq, c) in checked.withIndex()) {
            val r = c.record
            val recordId = ctx.newId()
            var sameAs: String? = null

            fun link(name: String?, type: SubjectType): SubjectEntity? {
                if (name.isNullOrBlank()) return null
                val res = subjects.resolve(name, type, capture.sensitive) ?: return null
                if (res.sameAsId != null && sameAs == null) sameAs = res.sameAsId
                return res.subject
            }

            val itemSubject = link(r.item, SubjectType.ITEM)
            val subject = link(r.subject, r.subjectType ?: SubjectType.THING) ?: itemSubject
            val placeSubject = link(r.place, SubjectType.PLACE)
            val atPlaceSubject = link(r.atPlace, SubjectType.PLACE)
            val personSubject = link(r.person, SubjectType.PERSON)

            val happened = DateResolver.resolveHappened(r.whenExpr, ctx.today)
            var supersedes: String? = null
            if ((r.kind == RecordKind.PLACED || r.kind == RecordKind.LOAN) && itemSubject != null) {
                if (r.kind == RecordKind.LOAN && r.direction == Direction.RETURNED) {
                    val open = ctx.currentItemRecords
                        .filter {
                            it.itemSubjectId == itemSubject.id && it.kind == RecordKind.LOAN && it.captureId != capture.id &&
                                (it.direction == Direction.LENT || it.direction == Direction.BORROWED) && it.returnedAt == null
                        }
                        .maxByOrNull { it.capturedAt }
                    if (open != null) {
                        returned += ReturnedMark(open.recordId, capture.createdAt)
                        toPin += open.recordId
                    }
                } else {
                    val prior = latestNewByItem[itemSubject.id]
                    if (prior != null) {
                        supersedes = prior
                    } else {
                        val older = ctx.currentItemRecords
                            .filter { it.itemSubjectId == itemSubject.id && it.captureId != capture.id && it.capturedAt <= capture.createdAt }
                            .maxByOrNull { it.capturedAt }
                        if (older != null) {
                            supersedes = older.recordId
                            toPin += older.recordId
                        }
                    }
                    latestNewByItem[itemSubject.id] = recordId
                }
            }

            val attention = c.attention ?: sameAs?.let { Attention.SAME_AS_PREFIX + it }
            val record = RecordEntity(
                id = recordId, captureId = capture.id, extractionId = extractionId, seq = seq, kind = r.kind,
                title = r.title.trim().ifEmpty { r.quote }.take(80), quote = r.quote,
                subjectId = subject?.id, placeSubjectId = placeSubject?.id, placeText = r.place, spotText = r.spot,
                happenedOn = happened?.toString(), happenedText = r.whenExpr?.said,
                confidence = r.sure, attention = attention, pinned = false,
                supersedesRecordId = supersedes,
                claimsToCloseRecordId = r.closes?.let { ctx.openTasks[it] },
                aliases = SubjectResolver.aliasesJson(r.aliases), tags = SubjectResolver.aliasesJson(r.tags),
                createdAt = ctx.now, updatedAt = ctx.now,
            )

            val whereabouts = if (r.kind == RecordKind.PLACED || r.kind == RecordKind.LOAN) {
                WhereaboutsEntity(
                    recordId = recordId, itemSubjectId = itemSubject?.id, itemText = r.item ?: r.title,
                    whereText = r.where, personSubjectId = personSubject?.id, personText = r.person,
                    direction = if (r.kind == RecordKind.PLACED) Direction.PLACED else (r.direction ?: Direction.LENT),
                    saidDateText = r.whenExpr?.said, createdAt = ctx.now, updatedAt = ctx.now,
                )
            } else null

            val task = if (r.kind == RecordKind.TASK) taskRow(recordId, r, atPlaceSubject, capture, ctx) else null

            val measuredAt = happened?.atTime(12, 0)?.atZone(ctx.zone)?.toInstant()?.toEpochMilli() ?: capture.createdAt
            val readings = if (r.kind == RecordKind.READING) {
                r.readings.map { x ->
                    val metric = metrics.resolve(subject?.id, x.metric, x.unit)
                    ReadingEntity(
                        id = ctx.newId(), recordId = recordId, subjectId = subject?.id, metricId = metric.id,
                        valueText = x.value, valueNum = x.number, unit = x.unit, measuredAt = measuredAt,
                        createdAt = ctx.now, updatedAt = ctx.now,
                    )
                }
            } else emptyList()

            if (r.photoHint == true) photoHint = true
            bundles += RecordBundle(record, whereabouts, task, readings)
        }
        return Projection(subjects.newSubjects, metrics.newMetrics, bundles, toPin.toList(), returned, photoHint)
    }

    private fun taskRow(
        recordId: String,
        r: ExtractedRecord,
        atPlace: SubjectEntity?,
        capture: CaptureEntity,
        ctx: ProjectionContext,
    ): TaskEntity {
        val due = DateResolver.resolveDue(r.due, ctx.today)
        val (kind, from, to) = when (due) {
            Due.None -> Triple(DueKind.NONE, null, null)
            Due.Soon -> Triple(DueKind.SOON, null, null)
            is Due.On -> Triple(DueKind.ON, due.date.toString(), null)
            is Due.By -> Triple(DueKind.BY, null, due.date.toString())
            is Due.Window -> Triple(DueKind.WINDOW, due.from.toString(), due.to.toString())
        }
        val task = TaskEntity(
            recordId = recordId, dueKind = kind, dueFrom = from, dueTo = to, dueText = r.due?.said,
            atPlaceSubjectId = atPlace?.id, atPlaceText = r.atPlace, perishable = r.perishable == true,
            lastActiveAt = capture.createdAt, createdAt = ctx.now, updatedAt = ctx.now,
        )
        // Card 06 A1: when time first nudges this task (never, for some).
        return task.copy(nextNudgeOn = NudgePlan.firstNudgeOn(task, ctx.today)?.toString())
    }
}
