package app.stash.capture

import app.stash.data.AttachmentKind
import app.stash.data.AttachmentRepository
import app.stash.data.AttachmentSource
import app.stash.data.CaptureEntity
import app.stash.data.CaptureRepository
import app.stash.data.CaptureSource
import app.stash.data.Clock
import app.stash.data.ExtractState
import app.stash.data.Ids
import app.stash.data.LocationFix
import app.stash.data.TranscribeState
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.domain.Sensitivity
import app.stash.domain.SensitiveReason
import app.stash.security.AttachmentStore
import java.io.ByteArrayInputStream

/**
 * A file that [app.stash.security.AttachmentStore.write] has **already written** (docs/cards/16a item 9): the sink
 * only adds its row. [key] is the file's own key; it goes into the row and nowhere else. [id] is the id the file was
 * encrypted with (it is in the file's associated data), so it must become the row's id.
 */
class InboundAttachment(
    val id: String,
    val kind: AttachmentKind,
    val source: AttachmentSource,
    val mime: String,
    val fileName: String,
    val key: ByteArray,
    val byteSize: Long,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val ocrText: String? = null,
    val ocrEngine: String? = null,
)

/** Everything fixed at the moment something arrives while the phone may be locked. */
sealed interface Inbound {
    data class Capture(
        /** Made here, at the door, so a later sealed inbox can hold the item before the database can be opened. */
        val id: String,
        val createdAt: Long,
        val zone: String?,
        val rawText: String,
        /** The words after the owner's corrections. */
        val text: String,
        val source: CaptureSource,
        val capturedLocked: Boolean?,
        val location: LocationFix?,
        val appVersion: String?,
        /** The Private button on the capture screen was pressed before the note was saved. */
        val markPrivate: Boolean = false,
        /** Files already written to the store; their rows go in with the capture, in this order (card 16a). */
        val attachments: List<InboundAttachment> = emptyList(),
    ) : Inbound {
        companion object {
            fun now(
                rawText: String,
                text: String,
                source: CaptureSource,
                capturedLocked: Boolean?,
                location: LocationFix?,
                appVersion: String?,
                clock: Clock = Clock.System,
                zone: String? = java.time.ZoneId.systemDefault().id,
                markPrivate: Boolean = false,
                attachments: List<InboundAttachment> = emptyList(),
            ) = Capture(Ids.new(), clock.now(), zone, rawText, text, source, capturedLocked, location, appVersion, markPrivate, attachments)
        }
    }

    /** Files added to a note that already exists (card 16a item 9). They go after the note's other files. */
    class AddAttachments(val captureId: String, val attachments: List<InboundAttachment>) : Inbound
}

/**
 * The one way in (docs/cards/13 A). Capture code calls [accept] and only afterwards plays the tone and shows
 * "Saved". A later sealed inbox replaces the implementation without touching capture code.
 */
interface InboundSink {
    sealed interface Result {
        data class Saved(val capture: CaptureEntity) : Result

        /** Nothing was saved. Carries no words. */
        data class Failed(val reason: String) : Result
    }

    suspend fun accept(item: Inbound): Result
}

/**
 * Checks the words for a Private note (setting `sensitive` and its reason), strips the "private note" marker from
 * the stored `text` (never from `raw_text`), writes the capture with its attachment rows, and asks for it to be read
 * ([afterSave]). On a failure it returns [InboundSink.Result.Failed] and leaves any files alone: the caller may try
 * again with the same files, and the sweep removes them if it never does (card 16a).
 */
class DirectSink(
    private val captures: CaptureRepository,
    private val alwaysPrivateWords: suspend () -> List<String>,
    private val afterSave: () -> Unit = {},
    /** Needed only for [Inbound.AddAttachments]; without it that item fails. */
    private val attachments: AttachmentRepository? = null,
    /** Card 26c: called with the capture id after a recording with no words yet is saved, instead of [afterSave] (26f supplies it). */
    private val afterRecordingSave: (String) -> Unit = {},
) : InboundSink {
    override suspend fun accept(item: Inbound): InboundSink.Result = when (item) {
        is Inbound.Capture -> if (item.isRecording()) acceptRecording(item) else try {
            val words = alwaysPrivateWords()
            val reason = Sensitivity.check(item.text, words) ?: Sensitivity.check(item.rawText, words)
                ?: if (item.markPrivate) SensitiveReason.SAID else null
            val stripped = Sensitivity.stripMarker(item.text)
            val text = if (reason != null && stripped.isNotBlank()) stripped else item.text
            val saved = captures.insertCapture(
                rawText = item.rawText, text = text, source = item.source, capturedLocked = item.capturedLocked,
                sensitive = reason != null, sensitiveReason = reason?.code,
                lat = item.location?.lat, lon = item.location?.lon, accuracyM = item.location?.accuracyM,
                appVersion = item.appVersion, zone = item.zone, id = item.id, createdAt = item.createdAt,
                attachments = item.attachments,
            )
            Dbg.event(DbgComponent.CAPTURE, DbgCode.SAVED, n1 = saved.text.length, n2 = if (saved.sensitive) 1 else 0, refId = saved.id)
            // The words are safe. If scheduling fails, the next app start or boot reads them.
            runCatching { afterSave() }
            InboundSink.Result.Saved(saved)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            InboundSink.Result.Failed(e.javaClass.simpleName)
        }
        is Inbound.AddAttachments -> try {
            val repo = attachments ?: error("no attachment repository")
            val updated = repo.addTo(item.captureId, item.attachments)
            if (updated == null) InboundSink.Result.Failed("NoLiveCapture") else InboundSink.Result.Saved(updated)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            InboundSink.Result.Failed(e.javaClass.simpleName)
        }
    }

    /** Card 26c item 7: a recording with no words yet. */
    private fun Inbound.Capture.isRecording() = text.isBlank() && attachments.any { it.kind == AttachmentKind.AUDIO }

    /**
     * Saved with `transcribe_state = PENDING` and `extract_state = DONE` (there is nothing to read yet, so the
     * extraction pipeline never sees it); Private only from the button, because there are no words to check. A second
     * recording in the same item is refused (the repository refuses it inside the transaction).
     */
    private suspend fun acceptRecording(item: Inbound.Capture): InboundSink.Result = try {
        val saved = captures.insertCapture(
            rawText = "", text = "", source = item.source, capturedLocked = item.capturedLocked,
            sensitive = item.markPrivate, sensitiveReason = if (item.markPrivate) SensitiveReason.SAID.code else null,
            lat = item.location?.lat, lon = item.location?.lon, accuracyM = item.location?.accuracyM,
            appVersion = item.appVersion, zone = item.zone, id = item.id, createdAt = item.createdAt,
            attachments = item.attachments,
            extractState = ExtractState.DONE, transcribeState = TranscribeState.PENDING,
        )
        Dbg.event(DbgComponent.CAPTURE, DbgCode.SAVED, n1 = 0, n2 = if (saved.sensitive) 1 else 0, refId = saved.id)
        // The recording is safe. If starting the writing-out fails, 26f's catch-up finds it PENDING.
        runCatching { afterRecordingSave(saved.id) }
        InboundSink.Result.Saved(saved)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        InboundSink.Result.Failed(e.javaClass.simpleName)
    }
}

/**
 * Card 26c item 6: the one function the capture screen calls with a finished recording. File first (the encrypted
 * copy in card 16a's store), then the capture and its one `AUDIO` attachment through the sink, in one transaction.
 * On `Failed` the file is kept (16a's rule): the caller still holds the bytes and may call again; the sweep removes a
 * file that never got its row. Nothing about writing the recording out is needed for, or can delay, this save.
 */
class RecordingSaver(
    private val store: AttachmentStore,
    private val sink: InboundSink,
    private val location: () -> LocationFix?,
    private val appVersion: String?,
    private val clock: Clock = Clock.System,
) {
    suspend fun save(encoded: EncodedAudio, capturedLocked: Boolean?, markPrivate: Boolean): InboundSink.Result {
        val id = Ids.new()
        val key = store.newKey()
        val name = store.newFileName()
        val written = try {
            store.write(name, key, id, ByteArrayInputStream(encoded.bytes))
        } catch (e: Exception) {
            Dbg.event(DbgComponent.AUDIO, DbgCode.FAILED, n1 = WRITE_FAILED)
            return InboundSink.Result.Failed(e.javaClass.simpleName)
        }
        val attachment = InboundAttachment(
            id = id, kind = AttachmentKind.AUDIO, source = AttachmentSource.RECORDER, mime = encoded.mime, fileName = name,
            key = key, byteSize = written.byteSize, sha256 = written.sha256, width = null, height = null,
            durationMs = encoded.durationMs,
        )
        val fix = runCatching { location() }.getOrNull()
        return sink.accept(
            Inbound.Capture.now(
                rawText = "", text = "", source = CaptureSource.VOICE, capturedLocked = capturedLocked, location = fix,
                appVersion = appVersion, clock = clock, markPrivate = markPrivate, attachments = listOf(attachment),
            ),
        )
    }

    companion object {
        /** `Dbg.event(AUDIO, FAILED, n1)` when the encrypted file could not be written (26a and 26b use other codes). */
        const val WRITE_FAILED = 300
    }
}

/** What the capture screen and the typed-note box call: correct the words, make the item, hand it to the sink. */
class CaptureEntry(
    private val correct: suspend (String) -> String,
    private val sink: InboundSink,
    private val location: () -> LocationFix?,
    private val appVersion: String?,
    private val clock: Clock = Clock.System,
) {
    suspend fun save(raw: String, source: CaptureSource, capturedLocked: Boolean?, markPrivate: Boolean = false,
        /** Files already written to the store (card 16a/16b); they become this capture's attachments, in order. */
        attachments: List<InboundAttachment> = emptyList(),
    ): InboundSink.Result {
        val text = correct(raw).trim()
        return sink.accept(Inbound.Capture.now(raw, text, source, capturedLocked, location(), appVersion, clock, markPrivate = markPrivate, attachments = attachments))
    }
}
