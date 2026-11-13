package app.stash.capture

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.domain.CameraException
import app.stash.domain.CameraFailure
import app.stash.domain.CameraFlash
import app.stash.domain.CameraNext
import app.stash.domain.CameraRules
import app.stash.domain.CameraShot
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.JpegOrientation
import app.stash.pictures.Prepared
import app.stash.ui.pictures.PictureAdder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the camera screen is doing (docs/cards/16e). [captureId] is the only thing it is told about a note: null for a
 * new note, an id for "add to that note". It never reads the note. It survives a rotation, so the camera is not asked
 * twice and a picture being made ready is not lost.
 */
class CameraViewModel(private val deps: CameraDeps, val captureId: String?) : ViewModel() {
    enum class Screen {
        /** The live preview and the shutter. */
        CAMERA,

        /** A new note: the "Anything to add about this?" screen holding the pictures taken so far. */
        ANNOTATE,

        /** An existing note: "Take another" and "Done". */
        ADDED,
    }

    var screen by mutableStateOf(Screen.CAMERA)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var flash by mutableStateOf(CameraFlash.OFF)
        private set

    /** Pictures put on the existing note during this visit. */
    var added by mutableStateOf(0)
        private set

    /** The system's permission question has been shown during this visit (it is asked once when the camera first opens). */
    var askedPermission = false

    private var engine: CameraEngine? = null
    private val adder by lazy { PictureAdder(deps.intake, deps.sink) }

    fun engine(context: Context): CameraEngine =
        engine ?: deps.engine(context).also { engine = it; it.setFlash(flash) }

    fun toggleFlash() {
        flash = CameraRules.nextFlash(flash)
        engine?.setFlash(flash)
    }

    /**
     * The shutter. The picture is taken in memory, turned upright exactly once, made into the app's own small copy, and
     * then either handed to [onNewNote] (a new note: the annotate step) or put on the existing note through the sink.
     * Once the shutter has fired the rest finishes even if the screen goes away, so a file is saved or deleted, never left over.
     */
    fun shoot(context: Context, onNewNote: (Prepared) -> Unit) {
        if (busy) return
        busy = true
        message = null
        val started = deps.nowMs()
        viewModelScope.launch {
            try {
                val shot = engine(context).capture()
                withContext(NonCancellable) { afterShot(shot, started, onNewNote) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CameraException) {
                failed(e.failure)
            } catch (e: Exception) {
                failed(CameraFailure.CAPTURE)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun afterShot(shot: CameraShot, started: Long, onNewNote: (Prepared) -> Unit) {
        val step = CameraNext.after(shot, captureId)
        // The rotation is applied once: the tag inside the bytes is made equal to the camera's own rotation.
        val upright = JpegOrientation.withOrientation(shot.jpeg, shot.rotationDegrees)
        val prepared = try {
            deps.photos.prepare(upright)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(CameraFailure.PREPARE, CameraRules.COULD_NOT_KEEP)
            return
        }
        when (step) {
            CameraNext.Step.ANNOTATE -> {
                Dbg.event(DbgComponent.CAMERA, DbgCode.TAKEN, n1 = prepared.attachment.byteSize, ms = deps.nowMs() - started)
                onNewNote(prepared)
                screen = Screen.ANNOTATE
            }
            CameraNext.Step.ATTACH -> {
                val result = try {
                    deps.sink.accept(Inbound.AddAttachments(captureId!!, listOf(prepared.attachment)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InboundSink.Result.Failed(e.javaClass.simpleName)
                }
                if (result is InboundSink.Result.Failed) {
                    deps.photos.discard(prepared)
                    failed(CameraFailure.SAVE, CameraRules.COULD_NOT_KEEP)
                } else {
                    Dbg.event(DbgComponent.CAMERA, DbgCode.TAKEN, n1 = prepared.attachment.byteSize, ms = deps.nowMs() - started)
                    prepared.thumbnail.recycle()
                    added++
                    deps.cues.saved()
                    screen = Screen.ADDED
                }
            }
        }
    }

    /** "Take another" on the existing-note screen. */
    fun takeAnother() {
        message = null
        screen = Screen.CAMERA
    }

    /**
     * Existing note, camera not usable: pictures picked from the gallery go on the note (the same path as card 16d).
     * Nothing is read from the note.
     */
    fun addPicked(uris: List<Uri>) {
        val id = captureId ?: return
        if (uris.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            try {
                val r = adder.add(id, uris)
                if (r.added > 0) {
                    added += r.added
                    deps.cues.saved()
                    screen = Screen.ADDED
                } else {
                    message = CameraRules.GALLERY_PICK_FAILED
                }
            } finally {
                busy = false
            }
        }
    }

    private fun failed(reason: CameraFailure, text: String = CameraRules.FAILED) {
        Dbg.event(DbgComponent.CAMERA, DbgCode.FAILED, n1 = reason.code)
        message = text
        deps.cues.failed()
    }

    override fun onCleared() {
        deps.cues.release()
    }
}
