package app.stash.pipeline

import app.stash.data.Clock
import app.stash.data.RecordRepository
import app.stash.data.SearchIndex
import app.stash.data.Store
import app.stash.data.stamped
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.ReadingRules
import app.stash.domain.RecordKind

/**
 * Card 07's work after a capture is read: flag a reading far from the usual (`ODD_VALUE`) and one in another unit
 * than before (`UNIT_CHANGED`), and let the first unit heard for a metric become the metric's unit. Registered as an
 * [AfterProjection] hook. It never changes a value or a unit of a reading: the owner's words stay as said.
 *
 * A note that is not Private is compared only with notes that are not Private, so a reason on it never holds a
 * number from a Private one. A Private note is compared with all of them (it is never shown to anyone else).
 */
class ReadingHook(
    private val store: () -> Store,
    private val clock: Clock = Clock.System,
) : AfterProjection {
    override suspend fun afterProjection(captureId: String, recordIds: List<String>) {
        val db = (store() as? Store.Ready)?.db ?: return
        val records = RecordRepository(db, SearchIndex(db), clock)
        val capture = db.captures().get(captureId) ?: return
        val include = if (capture.sensitive) 1 else 0
        for (recordId in recordIds) {
            val record = db.records().get(recordId) ?: continue
            if (record.kind != RecordKind.READING || record.deletedAt != null) continue
            var reason: String? = null
            var code = 0
            for (reading in db.readings().forRecord(recordId)) {
                val metric = db.metrics().get(reading.metricId) ?: continue
                val earlier = db.readings().forMetric(metric.id, include)
                    .filter { it.recordId != recordId && it.measuredAt <= reading.measuredAt }
                    .mapNotNull { it.valueNum }
                val odd = ReadingRules.outlierMedian(reading.valueNum, earlier)
                if (odd != null && reason == null) {
                    reason = ReadingRules.oddValueReason(metric.label, odd)
                    code = 1
                }
                if (ReadingRules.unitDrifted(metric.unit, reading.unit) && reason == null) {
                    reason = app.stash.domain.Attention.UNIT_CHANGED
                    code = 2
                }
                ReadingRules.unitToStore(metric.unit, reading.unit)?.let { db.metrics().update(metric.copy(unit = it).stamped(clock)) }
            }
            if (reason != null) {
                records.setAttention(recordId, reason)
                Dbg.event(DbgComponent.READING, DbgCode.FLAGGED, n1 = code, refId = recordId)
            }
        }
    }
}
