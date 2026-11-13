package app.stash.share

import android.app.KeyguardManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import app.stash.StashApp
import app.stash.capture.Cues
import app.stash.capture.InboundAttachment
import app.stash.capture.InboundSink
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.domain.Capability
import app.stash.pictures.PictureInput
import app.stash.pictures.PicturePreparer
import app.stash.pictures.Prepared
import app.stash.security.AttachmentStore
import app.stash.speech.AndroidSpeechEngine
import app.stash.speech.SpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How the annotate screen takes a picture in. The real one is [PreparerIntake]; tests give a fake (card 16c). */
interface PictureIntake {
    /** Makes the app's own small, encrypted copy. Throws when the picture cannot be added. */
    suspend fun prepare(uri: Uri, source: AttachmentSource): Prepared

    /** Deletes the stored copy again (cancelled, or removed before saving). */
    fun discard(prepared: Prepared)

    /** A larger picture for "tap a thumbnail", made in memory only. Null means "show the thumbnail". */
    suspend fun preview(prepared: Prepared): Bitmap? = null
}

/** The beeps, behind an interface so a JVM test can count them. */
interface ShareCues {
    fun start()
    fun saved()
    fun failed()
    fun release()
}

/**
 * Everything the share screen needs from the rest of the app, and nothing else. Note what is **not** here: no
 * repository and no way to read a stored note. The screen is an *adding* screen (00-READ-FIRST rule 5): it can only
 * hand one capture to [save].
 */
class ShareDeps(
    val intake: PictureIntake,
    /** One capture through the inbound sink, with source SHARED and the attachments linked. */
    val save: suspend (raw: String, capturedLocked: Boolean?, markPrivate: Boolean, attachments: List<InboundAttachment>) -> InboundSink.Result,
    /** The PICTURES capability. */
    val picturesOn: () -> Boolean,
    /** VOICE_CAPTURE is on and the microphone permission is granted. */
    val voiceOn: () -> Boolean,
    val speech: () -> SpeechEngine,
    /** The owner's word list, as hints to the recogniser. */
    val vocabulary: suspend () -> List<String>,
    val cues: ShareCues,
    val isLocked: () -> Boolean,
    val galleryNoteSeen: () -> Boolean,
    val markGalleryNoteSeen: () -> Unit,
    val savedShownMs: Long = app.stash.domain.ShareRules.SAVED_SHOWN_MS,
)

/** The save of one capture marked with [source] (SHARED here; PHOTO from the camera, card 16e), on the IO dispatcher. */
fun shareSave(
    entry: app.stash.capture.CaptureEntry,
    source: CaptureSource,
): suspend (String, Boolean?, Boolean, List<InboundAttachment>) -> InboundSink.Result = { raw, locked, priv, atts ->
    withContext(Dispatchers.IO) { entry.save(raw, source, locked, priv, atts) }
}

/** Where the activity gets its [ShareDeps]. A test installs its own [factory]. */
object ShareDepsProvider {
    @Volatile
    var factory: ((Context) -> ShareDeps)? = null

    /** [source] is what the saved capture is marked as: SHARED for Share to Stash, PHOTO when the camera (card 16e) saves it. A test factory ignores it. */
    fun get(context: Context, source: CaptureSource = CaptureSource.SHARED): ShareDeps =
        factory?.invoke(context.applicationContext) ?: fromContainer(context.applicationContext, source)

    private fun fromContainer(context: Context, source: CaptureSource): ShareDeps {
        val app = context as StashApp
        val c = app.container
        val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)
        val cues = Cues(context)
        return ShareDeps(
            intake = PreparerIntake(c.picturePreparer, c.attachmentStore, context.contentResolver),
            save = shareSave(c.entry, source),
            picturesOn = { c.capabilities.isOn(Capability.PICTURES) },
            voiceOn = { c.capabilities.isOn(Capability.VOICE_CAPTURE) },
            speech = { AndroidSpeechEngine(context) },
            vocabulary = { withContext(Dispatchers.IO) { c.repo.vocabWords() } },
            cues = object : ShareCues {
                override fun start() = cues.start()
                override fun saved() = cues.saved()
                override fun failed() = cues.failed()
                override fun release() = cues.release()
            },
            isLocked = { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true },
            galleryNoteSeen = { prefs.getBoolean("gallery_note_shown", false) },
            markGalleryNoteSeen = { prefs.edit().putBoolean("gallery_note_shown", true).apply() },
        )
    }
}

/** The real intake: card 16b's [PicturePreparer] with `kindHint = null`. */
class PreparerIntake(
    private val preparer: PicturePreparer,
    private val store: AttachmentStore,
    private val resolver: ContentResolver,
) : PictureIntake {
    override suspend fun prepare(uri: Uri, source: AttachmentSource): Prepared {
        // The display name ("Screenshot_...") helps the preparer tell a screenshot from a photo. Never logged.
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        return preparer.prepare(PictureInput.FromUri(uri, name), kindHint = null, source = source)
    }

    override fun discard(prepared: Prepared) = preparer.discard(prepared)

    /** The stored copy, decoded in memory (never written anywhere decrypted). */
    override suspend fun preview(prepared: Prepared): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val a = prepared.attachment
            store.open(a.fileName, a.key, a.id).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
