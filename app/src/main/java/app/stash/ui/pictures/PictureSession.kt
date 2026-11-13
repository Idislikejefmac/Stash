package app.stash.ui.pictures

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The viewer is open on one picture of one note. Ids only: no bitmap and no bytes are ever kept here (card 16d notes). */
data class ViewerRequest(val captureId: String, val pictureId: String)

/**
 * What the Notes screen remembers about pictures while the app is in the foreground: which Private notes the owner
 * tapped "Show" on, and the viewer. All of it goes when the app leaves the foreground or locks (card 16d item 4).
 * Card 02 later replaces "Show" with its second unlock and this class keeps only the viewer.
 */
class PictureSession(private val loader: PictureLoader) {
    private val shown = MutableStateFlow<Set<String>>(emptySet())
    private val viewing = MutableStateFlow<ViewerRequest?>(null)

    /** Notes whose Private pictures may be drawn now. */
    val shownPrivate: StateFlow<Set<String>> get() = shown
    val viewer: StateFlow<ViewerRequest?> get() = viewing

    fun showPrivate(captureId: String) { shown.value = shown.value + captureId }
    fun open(captureId: String, pictureId: String) { viewing.value = ViewerRequest(captureId, pictureId) }
    fun closeViewer() { viewing.value = null }

    /** The activity stopped: hide everything private, close the viewer, forget every decoded picture. */
    fun onStop() {
        shown.value = emptySet()
        viewing.value = null
        loader.clear()
    }

    /** The app locked (card 02): the same. */
    fun onLock() = onStop()
}
