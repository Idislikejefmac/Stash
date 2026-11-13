package app.stash.domain

/** One picture from the camera: the JPEG bytes as the camera made them, and the rotation the camera says makes it upright. */
class CameraShot(val jpeg: ByteArray, val rotationDegrees: Int)

/** The flash choices on the camera screen (docs/cards/16e item 4): nothing more. */
enum class CameraFlash { OFF, AUTO, ON }

/** Why the camera did not work. Only this number goes into `Dbg.event`; nothing from the picture does. */
enum class CameraFailure(val code: Int) { NO_PERMISSION(1), BIND(2), CAPTURE(3), NOT_JPEG(4), PREPARE(5), SAVE(6) }

/** The camera failed. Carries a code and nothing else. */
class CameraException(val failure: CameraFailure) : Exception("camera failed: ${failure.name}")

/** What happens to a shot (docs/cards/16e item 7). */
object CameraNext {
    enum class Step {
        /** A new note: the picture joins the "Anything to add about this?" screen. */
        ANNOTATE,

        /** An existing note: the picture goes straight onto it. The screen never reads the note. */
        ATTACH,
    }

    fun after(shot: CameraShot, captureId: String?): Step = if (captureId == null) Step.ANNOTATE else Step.ATTACH
}

/** What the camera screen can be asked to do right now. */
enum class CameraGate {
    OPEN, ASK_PERMISSION, ALLOW_IN_STASH, SWITCHED_OFF, PICTURES_OFF, NO_CAMERA,
}

/**
 * The words and the small decisions of the camera (docs/cards/16e). Pure Kotlin, pinned by JVM tests.
 */
object CameraRules {
    /**
     * Item 9 and its fallback. The card says to build the camera so it opens directly over the lock screen, run the
     * phone check (RISKS R33), and fall back to "Unlock to take a picture" if the phone asks to unlock or the preview
     * is black. The real behaviour is not known until that check is run, so the safe fallback is the default here.
     * To run the check itself, set this to false: the button then opens the camera directly while locked. Whichever
     * the phone does becomes the documented behaviour, and this value is set to match.
     */
    const val UNLOCK_FIRST_WHEN_LOCKED = true

    const val TAKE_A_PICTURE = "Take a picture"
    const val UNLOCK_TO_TAKE = "Unlock to take a picture"
    const val CLOSE = "Close"
    const val TAKE_ANOTHER = "Take another"
    const val DONE = "Done"
    const val ADD_ANOTHER_CAMERA = "Camera"
    const val SHUTTER = "Take the picture"
    const val CHOOSE_FROM_GALLERY = "Choose from your gallery"
    const val ALLOW_THE_CAMERA = "Allow the camera"

    const val NEEDS_PERMISSION = "Stash needs the camera to take pictures. Allow it, or choose pictures from your gallery."
    const val LOCKED_NEEDS_PERMISSION = "Open Stash once and allow the camera."
    const val SWITCHED_OFF = "The camera is switched off in Stash. Turn it on in Settings. You can still share pictures in and pick them from your gallery."
    const val PICTURES_OFF = "Pictures are switched off in Stash. Turn them on in Settings."
    const val NO_CAMERA = "This phone has no camera Stash can use. You can still share pictures in and pick them from your gallery."
    const val FAILED = "The camera did not work. Try again."
    const val COULD_NOT_KEEP = "I couldn't keep that picture. Try again."
    const val GALLERY_PICK_FAILED = "I couldn't add that picture."

    fun flashLabel(flash: CameraFlash) = when (flash) {
        CameraFlash.OFF -> "Flash: off"
        CameraFlash.AUTO -> "Flash: auto"
        CameraFlash.ON -> "Flash: on"
    }

    fun nextFlash(flash: CameraFlash) = when (flash) { CameraFlash.OFF -> CameraFlash.AUTO; CameraFlash.AUTO -> CameraFlash.ON; CameraFlash.ON -> CameraFlash.OFF }

    /** "Added 1 picture" / "Added 3 pictures": the count in this visit, on the existing-note screen. */
    fun added(count: Int) = if (count == 1) "Added 1 picture" else "Added $count pictures"

    /** A camera button is drawn only when the camera switch is on, pictures are on, and the phone has a camera. */
    fun buttonVisible(cameraSwitchOn: Boolean, picturesOn: Boolean, hasCamera: Boolean) = cameraSwitchOn && picturesOn && hasCamera

    /** The label of the "Take a picture" button on the capture screen's ready phase. */
    fun takeLabel(locked: Boolean) = if (needsUnlockFirst(locked)) UNLOCK_TO_TAKE else TAKE_A_PICTURE

    fun needsUnlockFirst(locked: Boolean) = locked && UNLOCK_FIRST_WHEN_LOCKED

    /**
     * What the camera screen does when it opens. Nothing works without the permission: the screen says so. The
     * permission is asked for only while the phone is unlocked (a locked phone cannot show the system's question).
     */
    fun gate(cameraSwitchOn: Boolean, picturesOn: Boolean, hasCamera: Boolean, permissionGranted: Boolean, locked: Boolean): CameraGate = when {
        !cameraSwitchOn -> CameraGate.SWITCHED_OFF
        !picturesOn -> CameraGate.PICTURES_OFF
        !hasCamera -> CameraGate.NO_CAMERA
        permissionGranted -> CameraGate.OPEN
        locked -> CameraGate.ALLOW_IN_STASH
        else -> CameraGate.ASK_PERMISSION
    }

    /** The line shown for a gate that is not [CameraGate.OPEN]. */
    fun message(gate: CameraGate): String? = when (gate) {
        CameraGate.OPEN, CameraGate.ASK_PERMISSION -> null
        CameraGate.ALLOW_IN_STASH -> LOCKED_NEEDS_PERMISSION
        CameraGate.SWITCHED_OFF -> SWITCHED_OFF
        CameraGate.PICTURES_OFF -> PICTURES_OFF
        CameraGate.NO_CAMERA -> NO_CAMERA
    }
}
