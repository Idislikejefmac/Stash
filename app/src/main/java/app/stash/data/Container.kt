package app.stash.data

import android.app.Application
import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.room.withTransaction
import app.stash.ask.AskEngine
import app.stash.ask.Lookups
import app.stash.domain.Corrector
import app.stash.domain.CorrectionLearner
import app.stash.domain.NoteKind
import app.stash.domain.NoteView
import app.stash.domain.RecordKind
import app.stash.domain.Structured
import app.stash.domain.itemKey
import app.stash.domain.toRecordKind
import app.stash.capture.CaptureEntry
import app.stash.capture.DirectSink
import app.stash.capture.InboundSink
import app.stash.capture.RecordingSaver
import app.stash.domain.Capability
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.OutboundGate
import app.stash.model.hosted.GatedPoster
import app.stash.model.hosted.HttpPoster
import app.stash.model.hosted.UrlConnectionPoster
import app.stash.model.rules.RulesExtractor
import app.stash.pipeline.AfterProjection
import app.stash.pipeline.ExtractionPipeline
import app.stash.pipeline.ExtractionScheduler
import app.stash.pipeline.ForceList
import app.stash.pipeline.ReadingHook
import app.stash.pipeline.TaskHook
import app.stash.pipeline.WorkManagerScheduler
import app.stash.speech.ModelImporter
import app.stash.speech.ModelLocator
import app.stash.speech.ModelPacks
import app.stash.speech.ChunkedTranscriber
import app.stash.speech.EdgeTrimTranscriber
import app.stash.speech.NoTranscriber
import app.stash.speech.Transcriber
import app.stash.speech.sherpa.SherpaEngine
import app.stash.reminders.Notifier
import app.stash.reminders.ReminderEngine
import app.stash.reminders.ReminderPrefs
import app.stash.reminders.ReminderScheduler
import app.stash.reminders.ReminderService
import app.stash.reminders.ReminderSettingsAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.stash.audio.AudioDecoder
import app.stash.audio.Compactor
import app.stash.audio.PlatformEncoders
import app.stash.pipeline.ForegroundTracker
import app.stash.pipeline.PrefsRunMarkerStore
import app.stash.pipeline.TranscriptionPipeline
import app.stash.pipeline.TranscriptionScheduler
import app.stash.pipeline.TranscriptionStatusSummary
import app.stash.pipeline.TranscriptionWork
import app.stash.pipeline.WorkManagerTranscriptionWork
import java.util.concurrent.CopyOnWriteArrayList
import app.stash.model.rules.RulesStructurer
import app.stash.security.AttachmentStore
import app.stash.security.SecretStore
import kotlinx.coroutines.flow.Flow
import app.stash.pictures.AndroidPictureDecoder
import app.stash.pictures.AndroidPictureEncoder
import app.stash.pictures.MlKitPictureText
import app.stash.pictures.PictureCatchUp
import app.stash.pictures.PictureTextChoice
import app.stash.pictures.PictureTextReader
import app.stash.pictures.PicturePreparer
import org.json.JSONObject
import java.security.MessageDigest

class Settings(context: Context, private val secrets: SecretStore) : SwitchStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var hostedEnabled: Boolean
        get() = prefs.getBoolean("hosted_enabled", true)
        set(v) = prefs.edit().putBoolean("hosted_enabled", v).apply()

    var notificationEnabled: Boolean
        get() = prefs.getBoolean("notification_enabled", true)
        set(v) = prefs.edit().putBoolean("notification_enabled", v).apply()

    var seeded: Boolean
        get() = prefs.getBoolean("seeded", false)
        set(v) = prefs.edit().putBoolean("seeded", v).apply()

    var apiKey: String?
        get() = secrets.get("anthropic_key")
        set(v) = secrets.put("anthropic_key", v?.trim())

    /** The owner's own words that make a note Private whenever he says them (docs/cards/13 A3). */
    var alwaysPrivateWords: List<String>
        get() = prefs.getString("always_private_words", "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("always_private_words", v.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()

    /** "Picture size" (docs/cards/16b): applies to pictures added after the change. */
    var pictureSize: app.stash.domain.PictureSize
        get() = app.stash.domain.PictureSize.parse(prefs.getString("picture_size", null))
        set(v) = prefs.edit().putString("picture_size", v.name).apply()

    /** "End a voice note after a pause of" (docs/cards/26a item 5). Read by the capture screen from card 26g. */
    var endPause: app.stash.domain.EndPause
        get() = app.stash.domain.EndPause.parse(prefs.getString("end_pause", null))
        set(v) = prefs.edit().putString("end_pause", v.name).apply()

    /** "Recording quality" (docs/cards/26b item 1): Small or Clearer, for notes recorded after the change. Read from card 26g. */
    var recordingQuality: app.stash.domain.RecordingQuality
        get() = app.stash.domain.RecordingQuality.parse(prefs.getString("recording_quality", null))
        set(v) = prefs.edit().putString("recording_quality", v.name).apply()

    /**
     * Card 26g item 4: our recorder was blocked, or would not start, on the locked phone. Locked notes are quick notes
     * (no recording) until "Try recording on the lock screen again" in Settings clears this.
     */
    var lockedMicBlocked: Boolean
        get() = prefs.getBoolean("locked_mic_blocked", false)
        set(v) = prefs.edit().putBoolean("locked_mic_blocked", v).apply()

    /** "When the phone is locked" (card 26g item 4): matters only while [lockedMicBlocked] is true. */
    var lockedChoice: app.stash.capture.LockedChoice
        get() = app.stash.capture.LockedChoice.parse(prefs.getString("locked_choice", null))
        set(v) = prefs.edit().putString("locked_choice", v.name).apply()

    /** Card 26g item 14: the one-time line after the first recorded note has been shown. */
    var recordingNoticeShown: Boolean
        get() = prefs.getBoolean("recording_notice_shown", false)
        set(v) = prefs.edit().putBoolean("recording_notice_shown", v).apply()

    /** "When I say I did something" (docs/cards/05 B7). */
    var closeSetting: app.stash.domain.CloseSetting
        get() = app.stash.domain.CloseSetting.parse(prefs.getString("close_setting", null))
        set(v) = prefs.edit().putString("close_setting", v.name).apply()

    /** How long a task stays on the list with no word from him (docs/cards/05 A2). */
    var lifetimes: app.stash.domain.TaskLifetimes
        get() {
            val d = app.stash.domain.TaskLifetimes()
            return app.stash.domain.TaskLifetimes(
                undatedDays = prefs.getInt("life_undated", d.undatedDays).coerceAtLeast(1),
                atPlaceDays = prefs.getInt("life_place", d.atPlaceDays).coerceAtLeast(1),
                perishableDays = prefs.getInt("life_perishable", d.perishableDays).coerceAtLeast(1),
                datedDays = prefs.getInt("life_dated", d.datedDays).coerceAtLeast(1),
            )
        }
        set(v) = prefs.edit().putInt("life_undated", v.undatedDays).putInt("life_place", v.atPlaceDays)
            .putInt("life_perishable", v.perishableDays).putInt("life_dated", v.datedDays).apply()

    /** The reminder settings (docs/cards/06 item 15). Times are kept as minutes after midnight. */
    var nudge: app.stash.domain.NudgeSettings
        get() {
            val d = app.stash.domain.NudgeSettings()
            fun time(key: String, def: java.time.LocalTime) = java.time.LocalTime.ofSecondOfDay(prefs.getInt(key, def.toSecondOfDay() / 60).coerceIn(0, 1439) * 60L)
            return app.stash.domain.NudgeSettings(
                dailyOn = prefs.getBoolean("nudge_daily_on", d.dailyOn),
                dailyAutoPaused = prefs.getBoolean("nudge_daily_paused", d.dailyAutoPaused),
                dailyResumedAt = prefs.getLong("nudge_daily_resumed_at", d.dailyResumedAt),
                dailyTime = time("nudge_daily_time", d.dailyTime),
                afternoonTime = time("nudge_afternoon_time", d.afternoonTime),
                weeklyOn = prefs.getBoolean("nudge_weekly_on", d.weeklyOn),
                weeklyDay = java.time.DayOfWeek.of(prefs.getInt("nudge_weekly_day", d.weeklyDay.value).coerceIn(1, 7)),
                weeklyTime = time("nudge_weekly_time", d.weeklyTime),
                quietStart = time("nudge_quiet_start", d.quietStart),
                quietEnd = time("nudge_quiet_end", d.quietEnd),
                lockScreenFirstTask = prefs.getBoolean("nudge_lock_first", d.lockScreenFirstTask),
            )
        }
        set(v) {
            fun min(t: java.time.LocalTime) = t.toSecondOfDay() / 60
            prefs.edit().putBoolean("nudge_daily_on", v.dailyOn).putBoolean("nudge_daily_paused", v.dailyAutoPaused)
                .putLong("nudge_daily_resumed_at", v.dailyResumedAt)
                .putInt("nudge_daily_time", min(v.dailyTime)).putInt("nudge_afternoon_time", min(v.afternoonTime))
                .putBoolean("nudge_weekly_on", v.weeklyOn).putInt("nudge_weekly_day", v.weeklyDay.value).putInt("nudge_weekly_time", min(v.weeklyTime))
                .putInt("nudge_quiet_start", min(v.quietStart)).putInt("nudge_quiet_end", min(v.quietEnd))
                .putBoolean("nudge_lock_first", v.lockScreenFirstTask).apply()
        }

    /** The weekly review says once that the digest stopped itself (docs/cards/06 B9). */
    var pausedNoticePending: Boolean
        get() = prefs.getBoolean("nudge_paused_notice", false)
        set(v) = prefs.edit().putBoolean("nudge_paused_notice", v).apply()

    /** The digest stops itself: no more daily digests until the owner turns it back on. */
    fun autoPauseDaily() {
        nudge = nudge.copy(dailyAutoPaused = true)
        pausedNoticePending = true
    }

    /** Turning the daily digest on again forgets the ignored ones, so it does not stop itself at once. */
    fun setDailyOn(on: Boolean, now: Long) {
        nudge = if (on) nudge.copy(dailyOn = true, dailyAutoPaused = false, dailyResumedAt = now) else nudge.copy(dailyOn = false)
        if (on) pausedNoticePending = false
    }

    /** The key to use right now, or null if the hosted model is off or there is no key. */
    fun activeKey(): String? = if (hostedEnabled) apiKey?.takeIf { it.isNotBlank() } else null

    // The two switches that existed before capabilities keep their old preference names.
    override fun isSwitchOn(c: Capability): Boolean = when (c) {
        Capability.HOSTED_MODEL -> hostedEnabled
        Capability.CAPTURE_NOTIFICATION -> notificationEnabled
        else -> prefs.getBoolean("cap_" + c.name, c.defaultOn)
    }

    override fun setSwitch(c: Capability, on: Boolean) {
        when (c) {
            Capability.HOSTED_MODEL -> hostedEnabled = on
            Capability.CAPTURE_NOTIFICATION -> notificationEnabled = on
            else -> prefs.edit().putBoolean("cap_" + c.name, on).apply()
        }
    }
}

/** What the screens, the capture activity and (later) workers use. They never touch a DAO. */
class Repository(
    private val context: Context?,
    val db: StashDatabase,
    val settings: Settings?,
    private val clock: Clock = Clock.System,
    private val appVersion: String? = null,
    /** Reminders plan again when a task changes (card 06). */
    private val onTasksChanged: () -> Unit = {},
    /** The encrypted files of notes (card 16a): `files/att/`. Tests pass their own folder. */
    private val attachmentStore: AttachmentStore? = context?.let { AttachmentStore(java.io.File(it.filesDir, AttachmentStore.DIR)) },
    /** Card 26c item 13: notes the owner asked to have written out again. The app keeps it in preferences. */
    askedAgain: CaptureIdSet = MemoryCaptureIdSet(),
) {
    val index = SearchIndex(db)
    val captures = CaptureRepository(db, index, clock, attachmentStore)
    val records = RecordRepository(db, index, clock)
    val consistency = Consistency(db, index, attachmentStore)

    /** A note's files (card 16a). Only exists where there is a store (always, in the app). */
    val attachments: AttachmentRepository by lazy {
        AttachmentRepository(db, attachmentStore ?: error("no attachment store"), clock)
    }

    /** Card 26c: every writing-out of a recording, corrected with the owner's words and checked for Private. */
    val transcriptions = TranscriptionRepository(
        db, index, clock, correct = { correct(it) }, alwaysPrivateWords = { settings?.alwaysPrivateWords ?: emptyList() },
        askedAgain = askedAgain,
    )

    /** Card 26c: a note's recording (info, totals, remove it alone, replace its file). */
    val recordings = RecordingRepository(db) { attachments }
    val tasks = TaskRepository(db, clock, { settings?.lifetimes ?: app.stash.domain.TaskLifetimes() }, changed = onTasksChanged)

    /** Card 07: read-only queries over readings. Card 08's tools call these. */
    val readings = ReadingRepository(db)

    /** Card 08: what the read-only lookups read, and the answer traces with the miss log. */
    val ask = AskRepository(db, index, readings)
    val traces = AnswerTraceRepository(db, clock)

    // --- the owner's word list and corrections (unchanged from the prototype) ---

    fun observeVocab(): Flow<List<String>> = db.vocab().observe()
    fun observeCorrections(): Flow<List<CorrectionEntity>> = db.corrections().observe()
    suspend fun vocabWords(): List<String> = db.vocab().all()
    suspend fun addWord(w: String) = db.vocab().add(WordEntity(w))
    suspend fun removeWord(w: String) = db.vocab().remove(w)
    suspend fun putCorrection(heard: String, meant: String) = db.corrections().put(CorrectionEntity(heard, meant))
    suspend fun removeCorrection(heard: String) = db.corrections().remove(heard)

    suspend fun seedIfNeeded() {
        val s = settings ?: return
        if (s.seeded) return
        listOf("Syd", "Costco", "frunk", "Frank", "Tesla").forEach { addWord(it) }
        putCorrection("sid", "Syd")
        s.seeded = true
    }

    suspend fun corrector(): Corrector =
        Corrector(db.vocab().all(), db.corrections().all().associate { it.heard to it.meant })

    suspend fun correct(raw: String): String = corrector().correct(raw)

    // --- notes ---

    fun observeCaptures(): Flow<List<CaptureWithRecords>> = captures.observeLive()

    /**
     * The prototype's way of saving, kept for the foundations tests and tools: it files the note with the
     * prototype's offline rules, inline. The app itself saves through `InboundSink` and reads in the background
     * (`ExtractionPipeline`); nothing in the app calls this and it never calls a hosted model.
     */
    suspend fun saveNote(raw: String, location: Location?, capturedLocked: Boolean? = null): CaptureEntity {
        val text = correct(raw).trim()
        val capture = captures.insertCapture(
            rawText = raw, text = text, source = CaptureSource.VOICE, capturedLocked = capturedLocked,
            lat = location?.latitude, lon = location?.longitude,
            accuracyM = location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(), appVersion = appVersion,
        )
        try {
            storeStructured(capture, RulesStructurer().parse(text), false)
        } catch (e: Exception) {
            // The words are saved; understanding can be redone.
        }
        return capture
    }

    /** One extraction and one record (plus whereabouts or task) for a capture, in the prototype's shape. */
    suspend fun storeStructured(capture: CaptureEntity, s: Structured, model: Boolean) {
        val now = clock.now()
        val supersedes = findSuperseded(s)
        val extractionId = Ids.new()
        val recordId = Ids.new()
        val extraction = ExtractionEntity(
            id = extractionId, captureId = capture.id, at = now,
            extractor = if (model) "prototype-model" else "prototype-rules", schemaVersion = 0,
            textHash = sha256(capture.text), outputJson = outputJson(s), status = ExtractionStatus.CURRENT,
        )
        val kind = s.kind.toRecordKind()
        val record = RecordEntity(
            id = recordId, captureId = capture.id, extractionId = extractionId, seq = 0, kind = kind,
            title = capture.text.take(80), quote = capture.text, supersedesRecordId = supersedes,
            createdAt = now, updatedAt = now,
        )
        val whereabouts = if (kind == RecordKind.PLACED || kind == RecordKind.LOAN) {
            WhereaboutsEntity(
                recordId = recordId, itemText = s.item ?: capture.text, whereText = s.place, personText = s.person,
                direction = if (kind == RecordKind.LOAN) Direction.LENT else Direction.PLACED,
                saidDateText = s.eventDate, createdAt = now, updatedAt = now,
            )
        } else null
        val task = if (kind == RecordKind.TASK) {
            TaskEntity(recordId = recordId, atPlaceText = s.place, lastActiveAt = now, createdAt = now, updatedAt = now)
        } else null
        records.replaceRecordsFor(capture.id, extraction, listOf(RecordBundle(record, whereabouts, task)))
    }

    /** The newest still-current note about the same item (stored or loaned) is replaced by this one. */
    private suspend fun findSuperseded(s: Structured): String? {
        if (s.kind != NoteKind.STORED_ITEM && s.kind != NoteKind.LOAN) return null
        val key = itemKey(s.item) ?: return null
        val views = noteViews()
        val already = views.mapNotNull { it.supersedesId }.toSet()
        return views.firstOrNull {
            it.id !in already && itemKey(it.item) == key && (it.kind == NoteKind.STORED_ITEM || it.kind == NoteKind.LOAN)
        }?.id
    }

    suspend fun noteViews(): List<NoteView> = views(captures.live())

    /** Flat views of the given captures for search and answers (newest capture first). */
    suspend fun views(list: List<CaptureWithRecords>): List<NoteView> {
        val hidden = db.records().deletedLinks().associate { it.id to it.supersedesRecordId }
        return NoteViews.from(list, hidden)
    }

    /** The owner deleted a note: it leaves every list and search at once, and is really removed after 30 days. */
    suspend fun deleteCapture(captureId: String) = captures.softDelete(captureId)

    /** Daily housekeeping: really delete notes in the bin for [days] days, then sweep orphan files (card 16a). */
    suspend fun purgeDeleted(days: Int = 30): Int {
        val n = captures.purgeDeletedOlderThan(days)
        captures.dismissStaleOffers() // card 16g
        if (attachmentStore != null) attachments.sweep()
        return n
    }

    /** The user fixed a word: rewrite the note and remember the rule so it never recurs. */
    suspend fun correctWord(captureId: String, heard: String, meant: String) {
        val m = meant.trim()
        if (m.isEmpty() || heard == m) return
        db.withTransaction {
            val c = captures.get(captureId) ?: return@withTransaction
            captures.updateText(captureId, CorrectionLearner.replaceWord(c.text, heard, m))
            records.rewriteTexts(captureId) { CorrectionLearner.replaceWord(it, heard, m) }
        }
        // A one-off fix is not a rule when it would change an everyday word or make a number: "for" heard for "4"
        // once must not turn every "for" into 4 (owner, phone check 2026-11-13).
        val oneOff = m.any { it.isDigit() } || heard.trim().lowercase() in EVERYDAY_WORDS
        if (!heard.equals(m, ignoreCase = true) && !oneOff) putCorrection(heard.lowercase(), m)
        if (!m.contains(' ') && !oneOff) addWord(m)
    }

    fun exportText(list: List<CaptureWithRecords>): String = buildString {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        list.sortedBy { it.capture.createdAt }.forEach { c ->
            val live = c.records.filter { it.record.deletedAt == null }.sortedBy { it.record.seq }
            append(fmt.format(java.util.Date(c.capture.createdAt))).append("  [")
            append(live.joinToString(",") { it.record.kind.toNoteKind().name }.ifEmpty { NoteKind.OTHER.name }).append("]  ")
            append(c.capture.text)
            for (r in live) {
                val v = NoteViews.view(c.capture, r, emptyMap())
                if (v.item != null) append("\n    item: ").append(v.item)
                if (v.place != null) append("\n    place: ").append(v.place)
                if (v.person != null) append("\n    person: ").append(v.person)
            }
            if (c.capture.lat != null) append("\n    location: ").append(c.capture.lat).append(", ").append(c.capture.lon)
            append("\n\n")
        }
    }

    companion object {
        /** Words too common to ever become a remembered correction. */
        val EVERYDAY_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "had", "has", "have", "he", "her", "his",
            "i", "if", "in", "is", "it", "me", "my", "no", "not", "of", "on", "one", "or", "our", "she", "so", "that", "the",
            "their", "then", "there", "they", "this", "to", "too", "two", "was", "we", "were", "what", "when", "with", "you", "your",
        )

        fun outputJson(s: Structured): String = JSONObject()
            .put("kind", s.kind.name)
            .put("item", s.item ?: JSONObject.NULL)
            .put("place", s.place ?: JSONObject.NULL)
            .put("person", s.person ?: JSONObject.NULL)
            .put("eventDate", s.eventDate ?: JSONObject.NULL)
            .toString()

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** A [CaptureIdSet] that survives the process: one small preferences file of ids (card 26c item 13). */
class PrefsCaptureIdSet(context: Context, name: String) : CaptureIdSet {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Synchronized override fun add(captureId: String) {
        prefs.edit().putStringSet("ids", ids() + captureId).apply()
    }

    @Synchronized override fun remove(captureId: String): Boolean {
        val ids = ids()
        if (captureId !in ids) return false
        prefs.edit().putStringSet("ids", ids - captureId).apply()
        return true
    }

    @Synchronized override fun contains(captureId: String): Boolean = captureId in ids()

    private fun ids(): Set<String> = prefs.getStringSet("ids", emptySet()).orEmpty().toSet()
}

/** The database, or "not now". Background code asks [Container.store] and reschedules on [NotAvailable] (docs/STORAGE.md). */
sealed interface Store {
    class Ready(val db: StashDatabase) : Store
    data object NotAvailable : Store
}

/**
 * Hand-rolled service locator. [store] is the one place the database is opened (docs/STORAGE.md rule 1), so a later
 * change to how it is opened (encryption, the sealed inbox) is a change here and nowhere else. Before Room first
 * opens the file, a version-1 database is copied aside (see [SafetyCopy]).
 */
class Container(private val app: Application) {
    val settings = Settings(app, SecretStore(app))
    val capabilities = Capabilities(settings, AndroidPermissionCheck(app))
    val outboundCounter: OutboundCounter = PrefsOutboundCounter(app)
    val outboundGate: OutboundGate = CapabilityGate(capabilities, outboundCounter)

    /** The only HTTP poster the app uses: it names a kind, and refuses it when its capability is off. */
    val poster: HttpPoster = GatedPoster(outboundGate, UrlConnectionPoster())

    /** Card 04 replaces this with the real hosted extractor. */
    var hostedExtractors: HostedExtractorProvider = NoHostedExtractor

    /** Cards 05, 07 and 17 add their work here. */
    val afterProjection = CopyOnWriteArrayList<AfterProjection>()

    init {
        // Card 05: fold repeated tasks and close the task that something he said finished.
        afterProjection.add(
            TaskHook(::store, { settings.closeSetting }, lifetimes = { settings.lifetimes }, changed = { reminders.replanSoon() }),
        )
        // Card 07: a reading far from the usual, or in another unit than before, is flagged (never changed).
        afterProjection.add(ReadingHook(::store))
        // Card 06: a new task has a first nudge; plan the reminders again.
        afterProjection.add(
            object : AfterProjection {
                override suspend fun afterProjection(captureId: String, recordIds: List<String>) { reminders.replanSoon() }
            },
        )
    }

    private var db: StashDatabase? = null

    @Synchronized
    fun store(): Store = Store.Ready(db ?: open().also { db = it })

    /** For screens and tools that cannot do anything without the database. Background work uses [store]. */
    fun database(): StashDatabase = (store() as Store.Ready).db

    private fun open(): StashDatabase {
        SafetyCopy.ensureV1Backup(app)
        return Room.databaseBuilder(app, StashDatabase::class.java, "stash.db")
            .addMigrations(*Migrations.ALL)
            .build()
    }

    private val version: String? by lazy { runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() }

    /** The one folder of encrypted note files (card 16a). */
    val attachmentStore: AttachmentStore by lazy { AttachmentStore(java.io.File(app.filesDir, AttachmentStore.DIR)) }

    private val mlKitText: MlKitPictureText by lazy { MlKitPictureText() }

    /**
     * Card 16f: reads the words in a picture. ML Kit while the PICTURE_TEXT switch is on, otherwise nothing: with the
     * switch off no ML Kit class is touched. Asked afresh for every picture, so the switch takes effect at once.
     */
    val pictureText: PictureTextReader
        get() = PictureTextChoice.reader(capabilities.isOn(Capability.PICTURE_TEXT), { mlKitText })

    /** Card 16f item 12: reads pictures stored before the reader existed, or while its switch was off. */
    val pictureCatchUp: PictureCatchUp by lazy {
        PictureCatchUp(
            attachments = repo.attachments,
            enabled = { capabilities.isOn(Capability.PICTURE_TEXT) },
            reader = { pictureText },
            decode = { android.graphics.BitmapFactory.decodeStream(it) },
            afterRead = { id ->
                // A note that is only a picture takes its title from the picture's words: file it again.
                val c = repo.captures.get(id)
                if (c != null && c.deletedAt == null && c.text.isBlank()) {
                    repo.captures.requestReread(id)
                    scheduler.enqueueNow()
                }
            },
        )
    }

    /** Card 16b: the one way a picture becomes the app's own small, encrypted copy. */
    val picturePreparer: PicturePreparer by lazy {
        PicturePreparer(
            decoder = AndroidPictureDecoder(app.contentResolver),
            encoder = AndroidPictureEncoder(),
            store = attachmentStore,
            reader = { pictureText },
            size = { settings.pictureSize },
            screen = { app.resources.displayMetrics.let { it.widthPixels to it.heightPixels } },
        )
    }

    val repo: Repository by lazy {
        Repository(
            app, database(), settings, appVersion = version, onTasksChanged = { reminders.replanSoon() }, attachmentStore = attachmentStore,
            askedAgain = PrefsCaptureIdSet(app, "write_again"),
        )
    }

    /**
     * Card 08: questions. Pass 1 has no tool loop (`loop = null`; pass 2 plugs card 04's client in here), so a question
     * is answered by code or by plain search, with nothing sent anywhere.
     */
    val askEngine: AskEngine by lazy {
        AskEngine(
            lookups = Lookups(repo.ask, { System.currentTimeMillis() }, { java.time.ZoneId.systemDefault() }),
            traces = repo.traces,
            now = { System.currentTimeMillis() },
            zone = { java.time.ZoneId.systemDefault() },
            appVersion = version ?: "unknown",
            loop = null,
        )
    }

    private val reminderPrefs by lazy { ReminderPrefs(app) }

    /** Card 06: the reminders. */
    val reminders: ReminderService by lazy {
        val engine = ReminderEngine(
            store = ::store, plans = reminderPrefs, ledger = reminderPrefs, poster = Notifier(app),
            settings = ReminderSettingsAccess(
                get = { settings.nudge }, autoPause = { settings.autoPauseDaily() },
                noticePending = { settings.pausedNoticePending }, noticeShown = { settings.pausedNoticePending = false },
            ),
            enabled = { capabilities.isOn(Capability.REMINDERS) },
            tasks = { db -> TaskRepository(db, lifetimes = { settings.lifetimes }, changed = { reminders.replanSoon() }) },
        )
        ReminderService(app, engine, ReminderScheduler(app), { settings.nudge }, { capabilities.isOn(Capability.REMINDERS) })
    }

    val scheduler: ExtractionScheduler = WorkManagerScheduler(app)

    private val forceList: ForceList = object : ForceList {
        // The owner's "Read it again" must survive the process, so it lives in a small preferences file.
        private val prefs = app.getSharedPreferences("reread", Context.MODE_PRIVATE)

        @Synchronized override fun add(captureId: String) {
            prefs.edit().putStringSet("ids", (prefs.getStringSet("ids", emptySet()).orEmpty() + captureId)).apply()
        }

        @Synchronized override fun take(captureId: String): Boolean {
            val ids = prefs.getStringSet("ids", emptySet()).orEmpty()
            if (captureId !in ids) return false
            prefs.edit().putStringSet("ids", ids - captureId).apply()
            return true
        }
    }

    val pipeline: ExtractionPipeline by lazy {
        ExtractionPipeline(
            store = ::store,
            rules = RulesExtractor(),
            hostedExtractor = { hostedExtractors.extractor() },
            hostedOn = { capabilities.isOn(Capability.HOSTED_MODEL) },
            hasKey = { settings.activeKey() != null },
            networkAvailable = { hasNetwork() },
            hooks = { afterProjection.toList() },
            force = forceList,
        )
    }

    /** Location, or nothing at all when the capability is off. Chosen at the moment of capture. */
    fun locationSource(): LocationSource =
        if (capabilities.isOn(Capability.LOCATION_AT_CAPTURE)) AndroidLocationSource(app) else NoLocation

    // --- card 26d: the speech engine and its model packs ---

    /** Where speech model packs live: `files/models/<pack id>/`. Excluded from backup and device transfer. */
    val modelPacks: ModelPacks by lazy {
        ModelPacks(java.io.File(app.filesDir, ModelLocator.DIR))
    }

    /** Finds an installed pack: the app's files first, then (if a build ever bundles one) the APK's assets. */
    val modelLocator: ModelLocator by lazy {
        ModelLocator(modelPacks.root, { path -> runCatching { app.assets.list(path)?.toList() }.getOrNull() })
    }

    /** Imports a pack zip (Settings' file picker, or the debug receiver). Never from the network. */
    val modelImporter: ModelImporter by lazy {
        // Card 26f: notes that waited for a model are written out once one is installed.
        ModelImporter(modelPacks.root, onInstalled = { transcriptionScheduler.requestNow() })
    }

    private fun availableMemory(): Long? = runCatching {
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return@runCatching null
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }.getOrNull()

    /**
     * The engine in this build, or NoTranscriber. Nothing native is loaded until the first writing-out. Card 26e: the
     * engine is wrapped so that a long note is written out in pieces cut at real pauses (short notes go through whole).
     */
    private val engineTranscriber: Transcriber by lazy {
        SherpaEngine.transcriber(modelLocator, app.assets, ::availableMemory)?.let { ChunkedTranscriber(EdgeTrimTranscriber(it)) } ?: NoTranscriber
    }

    /** True when this build contains the speech engine (the AAR was given to the build). */
    val speechEngineBuilt: Boolean get() = SherpaEngine.BUILT

    @Volatile private var chosenTranscriber: Transcriber? = null

    /**
     * Card 26d item 1: what writes a recording out. The real engine when this build has it (it says NO_MODEL until a
     * pack is added), otherwise NoTranscriber. Tests and debug tools may set a fake here.
     */
    var transcriber: Transcriber
        get() = chosenTranscriber ?: engineTranscriber
        set(v) { chosenTranscriber = v }

    // --- card 26f: writing recordings out ---

    /** How many of the app's activities are started; StashApp's lifecycle callbacks keep it. */
    val foreground = ForegroundTracker()

    /** The application's own scope for the foreground pass: it outlives every screen. */
    private val transcriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val transcriptionWork: TranscriptionWork = WorkManagerTranscriptionWork(app)

    /** Card 26f section A: one pass over the notes waiting for their words. */
    val transcriptionPipeline: TranscriptionPipeline by lazy {
        TranscriptionPipeline(
            store = ::store,
            parts = { TranscriptionPipeline.Parts(repo.transcriptions, repo.attachments, repo.recordings) },
            transcriber = { transcriber },
            decoder = AudioDecoder(),
            compact = { Compactor(PlatformEncoders()).compact(it) },
            askExtraction = { scheduler.enqueueNow() },
            markers = PrefsRunMarkerStore(app),
        )
    }

    /** Card 26f section B: start a pass now (foreground while a screen is up) and always enqueue the job. */
    val transcriptionScheduler: TranscriptionScheduler by lazy {
        TranscriptionScheduler(foreground, { block -> transcriptionScope.launch { block() } }, transcriptionWork, { transcriptionPipeline })
    }

    /** "Write it out again" or "Try again" on a recorded note (26c item 13; the buttons are 26h's). */
    suspend fun writeOutAgain(captureId: String): TranscriptionRepository.AskAgain {
        val r = repo.transcriptions.askAgain(captureId)
        if (r != TranscriptionRepository.AskAgain.NOTHING) transcriptionScheduler.requestNow()
        return r
    }

    /** Card 26f item 12: what Settings shows (26j draws it). */
    suspend fun transcriptionSummary(): TranscriptionStatusSummary {
        val d = database().transcriptions()
        return TranscriptionStatusSummary(
            waiting = d.countInState(TranscribeState.PENDING.name) + d.countInState(TranscribeState.RETRY.name),
            gaveUp = d.countInState(TranscribeState.GAVE_UP.name),
            lastDelayMs = d.lastFirstDelayMs(),
        )
    }

    /** Card 26c's hook, wired by 26f: a saved recording is written out at once (or by the job). */
    @Volatile var afterRecordingSave: (String) -> Unit = { transcriptionScheduler.requestNow() }

    val sink: InboundSink by lazy {
        DirectSink(
            repo.captures, { settings.alwaysPrivateWords }, afterSave = { scheduler.enqueueNow() }, attachments = repo.attachments,
            afterRecordingSave = { id -> afterRecordingSave(id) },
        )
    }

    /**
     * Card 26g: the scope a recording is stopped and saved in. It outlives the capture screen, because leaving the
     * screen (Home, the back gesture, the screen turning off, a call) saves the note instead of losing it.
     */
    val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Card 26c: the one function the capture screen will call with a finished recording (26g). */
    val recordingSaver: RecordingSaver by lazy {
        RecordingSaver(attachmentStore, sink, { locationSource().lastKnown() }, version)
    }

    val entry: CaptureEntry by lazy {
        CaptureEntry({ repo.correct(it) }, sink, { locationSource().lastKnown() }, version)
    }

    /** "Read it again": back to PENDING, even with pinned records, and a pass now. */
    suspend fun readAgain(captureId: String) {
        forceList.add(captureId)
        repo.captures.requestReread(captureId)
        Dbg.event(DbgComponent.EXTRACT, DbgCode.REREAD_ASKED, refId = captureId)
        scheduler.enqueueNow()
    }

    private fun hasNetwork(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Start recording events (docs/cards/13 D). */
    fun startEventLog(scope: CoroutineScope) {
        Dbg.sink = DbEventSink(::store, scope)
    }
}
