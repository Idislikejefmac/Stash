package app.stash.capture

import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stash.domain.CameraException
import app.stash.domain.CameraFailure
import app.stash.domain.CameraFlash
import app.stash.domain.CameraShot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX (docs/cards/16e): a `LifecycleCameraController` on the back camera with tap to focus and pinch to zoom, and
 * `takePicture` with the **in-memory** callback only. The picture is read out of the image, the image is closed, and
 * the bytes are all that leaves this class. No file and no gallery entry is ever made.
 */
class CameraXEngine(private val context: Context) : CameraEngine {
    private val controller = LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        isTapToFocusEnabled = true
        isPinchToZoomEnabled = true
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            controller.bindToLifecycle(owner)
            onDispose { controller.unbind() }
        }
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = this@CameraXEngine.controller
                    addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> if (b > t && r > l) viewAspect = (r - l).toFloat() / (b - t) }
                }
            },
        )
    }

    override suspend fun capture(): CameraShot = suspendCancellableCoroutine { cont ->
        controller.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val shot = try {
                        if (image.format != ImageFormat.JPEG) throw CameraException(CameraFailure.NOT_JPEG)
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        CameraShot(cropToPreview(bytes, image.imageInfo.rotationDegrees), image.imageInfo.rotationDegrees)
                    } catch (e: CameraException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(CameraException(CameraFailure.CAPTURE))
                        return
                    } finally {
                        image.close()
                    }
                    if (cont.isActive) cont.resume(shot)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(CameraException(CameraFailure.CAPTURE))
                }
            },
        )
    }

    /** The shape (width / height) of what the owner sees; set when the preview is laid out. */
    @Volatile private var viewAspect = 0f

    /**
     * The preview fills the screen with a tall slice of the camera's frame, but the JPEG CameraX hands over is the
     * whole frame (phone check 2026-11-13: the picture had extra wall at the top, bottom and sides). Cut it to the
     * preview's own shape, centred, in the upright orientation. Any failure keeps the whole frame: a wider picture
     * beats a lost one.
     */
    private fun cropToPreview(jpeg: ByteArray, rotation: Int): ByteArray {
        val aspect = viewAspect
        if (aspect <= 0f) return jpeg
        return try {
            val decoder = android.graphics.BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size) ?: return jpeg
            val w = decoder.width
            val h = decoder.height
            val sideways = rotation == 90 || rotation == 270
            val upW = if (sideways) h else w
            val upH = if (sideways) w else h
            // Largest centred rectangle of the view's shape inside the upright picture.
            var cw = upW.toFloat()
            var ch = cw / aspect
            if (ch > upH) { ch = upH.toFloat(); cw = ch * aspect }
            if (cw >= upW - 2 && ch >= upH - 2) { decoder.recycle(); return jpeg }
            val ux = ((upW - cw) / 2f).toInt()
            val uy = ((upH - ch) / 2f).toInt()
            val upright = android.graphics.Rect(ux, uy, ux + cw.toInt(), uy + ch.toInt())
            // Back to the unrotated pixels the JPEG holds.
            val raw = when (rotation) {
                90 -> android.graphics.Rect(upright.top, h - upright.right, upright.bottom, h - upright.left)
                180 -> android.graphics.Rect(w - upright.right, h - upright.bottom, w - upright.left, h - upright.top)
                270 -> android.graphics.Rect(w - upright.bottom, upright.left, w - upright.top, upright.right)
                else -> upright
            }
            val region = decoder.decodeRegion(raw, null)
            decoder.recycle()
            val out = java.io.ByteArrayOutputStream()
            region.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            region.recycle()
            out.toByteArray()
        } catch (e: Throwable) {
            jpeg
        }
    }

    override fun setFlash(flash: CameraFlash) {
        controller.imageCaptureFlashMode = when (flash) { CameraFlash.AUTO -> ImageCapture.FLASH_MODE_AUTO; CameraFlash.ON -> ImageCapture.FLASH_MODE_ON; CameraFlash.OFF -> ImageCapture.FLASH_MODE_OFF }
    }
}
