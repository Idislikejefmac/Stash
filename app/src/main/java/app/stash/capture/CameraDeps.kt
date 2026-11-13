package app.stash.capture

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import app.stash.StashApp
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.domain.Capability
import app.stash.pictures.PictureInput
import app.stash.pictures.PicturePreparer
import app.stash.pictures.Prepared
import app.stash.share.PictureIntake
import app.stash.share.PreparerIntake
import app.stash.share.ShareCues

/** How a camera picture becomes the app's own stored copy. The real one is [PreparerPhotos]; tests give a fake. */
interface CameraPhotos {
    /** Makes the small encrypted copy from the camera's JPEG bytes. Throws when it cannot. The bytes are not kept. */
    suspend fun prepare(jpeg: ByteArray): Prepared

    /** Deletes the stored copy again (cancelled, or the save failed). */
    fun discard(prepared: Prepared)
}

/** Card 16b's [PicturePreparer] with `kindHint = PHOTO` and `source = CAMERA` (docs/cards/16e item 5). */
class PreparerPhotos(private val preparer: PicturePreparer) : CameraPhotos {
    override suspend fun prepare(jpeg: ByteArray): Prepared =
        preparer.prepare(PictureInput.FromBytes(jpeg), kindHint = AttachmentKind.PHOTO, source = AttachmentSource.CAMERA)

    override fun discard(prepared: Prepared) = preparer.discard(prepared)
}

/**
 * Everything the camera screen needs from the rest of the app. Like the share screen it is an *adding* screen: it has
 * a sink to hand pictures to and no way to read a stored note.
 */
class CameraDeps(
    val engine: (Context) -> CameraEngine,
    val photos: CameraPhotos,
    /** For pictures chosen from the gallery on the existing-note path (the picker offered when the camera cannot be used). */
    val intake: PictureIntake,
    val sink: InboundSink,
    val cues: ShareCues,
    /** The CAMERA switch (and the build), ignoring the permission. */
    val cameraSwitchOn: () -> Boolean,
    val picturesOn: () -> Boolean,
    val hasCamera: () -> Boolean,
    val permissionGranted: () -> Boolean,
    val isLocked: () -> Boolean,
    /** A monotonic clock in milliseconds, for "shutter to prepared". */
    val nowMs: () -> Long,
)

/** Where the activity gets its [CameraDeps]. A test installs its own [factory]. */
object CameraDepsProvider {
    @Volatile
    var factory: ((Context) -> CameraDeps)? = null

    fun get(context: Context): CameraDeps = (factory ?: ::fromContainer)(context.applicationContext)

    private fun fromContainer(context: Context): CameraDeps {
        val c = (context as StashApp).container
        // Made only if a cue is really played, so the activity own copy of these deps costs nothing.
        val lazyCues = lazy { Cues(context) }
        return CameraDeps(
            engine = { CameraXEngine(it) },
            photos = PreparerPhotos(c.picturePreparer),
            intake = PreparerIntake(c.picturePreparer, c.attachmentStore, context.contentResolver),
            sink = c.sink,
            cues = object : ShareCues {
                override fun start() = lazyCues.value.start()
                override fun saved() = lazyCues.value.saved()
                override fun failed() = lazyCues.value.failed()
                override fun release() { if (lazyCues.isInitialized()) lazyCues.value.release() }
            },
            cameraSwitchOn = { c.capabilities.enabled(Capability.CAMERA) },
            picturesOn = { c.capabilities.isOn(Capability.PICTURES) },
            hasCamera = { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
            permissionGranted = { c.capabilities.permissionGranted(Capability.CAMERA) },
            isLocked = { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true },
            nowMs = { android.os.SystemClock.elapsedRealtime() },
        )
    }
}
