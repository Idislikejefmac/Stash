package app.stash

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.stash.capture.CameraDeps
import app.stash.capture.CameraEngine
import app.stash.capture.CameraPhotos
import app.stash.capture.DirectSink
import app.stash.capture.Inbound
import app.stash.capture.InboundAttachment
import app.stash.capture.InboundSink
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.Ids
import app.stash.domain.CameraException
import app.stash.domain.CameraFlash
import app.stash.domain.CameraShot
import app.stash.pictures.PictureException
import app.stash.pictures.PictureFailure
import app.stash.pictures.Prepared
import app.stash.security.AttachmentStore
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** The smallest byte string [app.stash.domain.JpegOrientation] reads as a JPEG: SOI, a scan marker, EOI. */
fun tinyJpeg(vararg extraSegments: ByteArray): ByteArray {
    var out = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    for (s in extraSegments) out += s
    return out + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
}

/** A camera that needs no camera: a plain box for the preview, and shots it is told to give. */
class FakeCameraEngine : CameraEngine {
    var captures = 0
    var rotation = 0
    var jpeg: ByteArray = tinyJpeg()
    var failWith: Exception? = null
    var currentFlash = CameraFlash.OFF
    val flashes = CopyOnWriteArrayList<CameraFlash>()

    @Composable
    override fun Preview(modifier: Modifier) {
        Box(modifier.testTag("preview"))
    }

    override suspend fun capture(): CameraShot {
        captures++
        failWith?.let { throw it }
        return CameraShot(jpeg.copyOf(), rotation)
    }

    override fun setFlash(flash: CameraFlash) {
        currentFlash = flash
        flashes += flash
    }
}

/** A preparer that writes a real tiny encrypted file through a real store, and keeps the bytes it was given. */
class FakeCameraPhotos(private val store: AttachmentStore) : CameraPhotos {
    val received = CopyOnWriteArrayList<ByteArray>()
    val made = CopyOnWriteArrayList<Prepared>()
    val discarded = CopyOnWriteArrayList<String>()
    var failWith: PictureFailure? = null

    override suspend fun prepare(jpeg: ByteArray): Prepared {
        failWith?.let { throw PictureException(it) }
        received += jpeg
        val id = Ids.new()
        val key = store.newKey()
        val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream(byteArrayOf(received.size.toByte())))
        val a = InboundAttachment(id, AttachmentKind.PHOTO, AttachmentSource.CAMERA, "image/webp", name, key, w.byteSize, w.sha256, 10, 10)
        return Prepared(a, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)).also { made += it }
    }

    override fun discard(prepared: Prepared) {
        discarded += prepared.attachment.id
        store.delete(prepared.attachment.fileName)
    }
}

/** Passes everything to the real sink and remembers it. [failNext] makes the next attachment save fail. */
class RecordingSink(private val real: InboundSink) : InboundSink {
    val items = CopyOnWriteArrayList<Inbound>()
    var failNext = false

    override suspend fun accept(item: Inbound): InboundSink.Result {
        items += item
        if (failNext && item is Inbound.AddAttachments) {
            failNext = false
            return InboundSink.Result.Failed("test")
        }
        return real.accept(item)
    }

    fun additions() = items.filterIsInstance<Inbound.AddAttachments>()
}

/** Everything a camera test needs, on top of the share harness (real save path, real store, real database). */
class CameraHarness(val h: ShareHarness = ShareHarness()) {
    val engine = FakeCameraEngine()
    val photos = FakeCameraPhotos(h.store)
    val sink = RecordingSink(DirectSink(h.repo.captures, { emptyList() }, attachments = h.repo.attachments))
    val cues = CountingCues()
    var cameraOn = true
    var granted = true
    var hasCamera = true
    private var clock = 1_000L

    val deps = CameraDeps(
        engine = { engine },
        photos = photos,
        intake = h.intake,
        sink = sink,
        cues = cues,
        cameraSwitchOn = { cameraOn },
        picturesOn = { h.picturesOn },
        hasCamera = { hasCamera },
        permissionGranted = { granted },
        isLocked = { h.locked },
        nowMs = { clock += 250; clock },
    )
}

@Suppress("unused")
private val keepImports = CameraException::class
