package app.stash.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.stash.domain.CameraFlash
import app.stash.domain.CameraShot

/**
 * The camera, behind one interface so no test needs a camera (docs/cards/16e). The real one is [CameraXEngine]; a test
 * gives a fake that draws a plain box and returns bytes it made up.
 */
interface CameraEngine {
    /** The live preview: back camera, tap to focus, pinch to zoom. Binding the camera belongs to this composable being on screen. */
    @Composable
    fun Preview(modifier: Modifier)

    /**
     * Takes one picture **in memory**: the JPEG bytes and the rotation the camera reports. Never writes a file and
     * never reaches the gallery. Throws [app.stash.domain.CameraException] with a code only.
     */
    suspend fun capture(): CameraShot

    fun setFlash(flash: CameraFlash)
}
