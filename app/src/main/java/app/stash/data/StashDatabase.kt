package app.stash.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 1 was the prototype (`note`, `vocab_word`, `correction`); its schema is frozen in
 * app/schemas/app.stash.data.StashDatabase/1.json. Version 2 is the two-layer model. Version 3 adds `attachment`
 * (card 16a). Version 4 adds `transcription` and two capture columns (card 26c). Version 5 adds `answer_trace`
 * (card 08). There is no destructive fallback anywhere: every change ships a hand-written migration and a test for it.
 */
@Database(
    entities = [
        CaptureEntity::class, ExtractionEntity::class, RecordEntity::class, TaskEntity::class,
        ReadingEntity::class, MetricEntity::class, WhereaboutsEntity::class, SubjectEntity::class,
        PlaceLocationEntity::class, NudgeLogEntity::class, RevisionEntity::class, TombstoneEntity::class,
        MissLogEntity::class, AppEventEntity::class, WordEntity::class, CorrectionEntity::class,
        SearchDocEntity::class, AttachmentEntity::class, TranscriptionEntity::class, AnswerTraceEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class StashDatabase : RoomDatabase() {
    abstract fun captures(): CaptureDao
    abstract fun extractions(): ExtractionDao
    abstract fun records(): RecordDao
    abstract fun tasks(): TaskDao
    abstract fun whereabouts(): WhereaboutsDao
    abstract fun readings(): ReadingDao
    abstract fun metrics(): MetricDao
    abstract fun subjects(): SubjectDao
    abstract fun placeLocations(): PlaceLocationDao
    abstract fun nudgeLog(): NudgeLogDao
    abstract fun missLog(): MissLogDao
    abstract fun appEvents(): AppEventDao
    abstract fun revisions(): RevisionDao
    abstract fun tombstones(): TombstoneDao
    abstract fun search(): SearchDao
    abstract fun vocab(): VocabDao
    abstract fun corrections(): CorrectionDao
    abstract fun attachments(): AttachmentDao
    abstract fun transcriptions(): TranscriptionDao
    abstract fun answerTraces(): AnswerTraceDao
}
