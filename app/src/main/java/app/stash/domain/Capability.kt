package app.stash.domain

/**
 * Every optional ability of the app, each behind one switch (docs/cards/13 section C). Feature code asks
 * `Capabilities.isOn(...)`; it never checks a permission or a setting itself. A row is added when its feature is
 * built, not before. Pure Kotlin: permissions are named by their Android string.
 */
enum class Capability(
    val plainName: String,
    /** Android permissions of which any one is enough; empty if none is needed. The first is the one asked for. */
    val permissions: List<String>,
    /** What still works when this is off, in the words shown beside the switch. */
    val withoutIt: String,
    val defaultOn: Boolean = true,
) {
    VOICE_CAPTURE(
        "Add notes by voice", listOf("android.permission.RECORD_AUDIO"),
        "You can still type notes.",
    ),
    CAPTURE_NOTIFICATION(
        "Add a note from the lock screen", listOf("android.permission.POST_NOTIFICATIONS"),
        "You can still use the Quick Settings tile and the button in the app.",
    ),
    LOCATION_AT_CAPTURE(
        "Remember where a note was made",
        listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"),
        "Notes have no place. Trips will use the place names you say.",
    ),
    HOSTED_MODEL(
        "Use a hosted model to sort notes", emptyList(),
        "A simple set of rules on the phone sorts your notes, and search shows a list of matching notes.",
    ),
    REMINDERS(
        "Reminders", listOf("android.permission.POST_NOTIFICATIONS"),
        "Things to do are only shown on the \"To handle\" screen.",
    ),
    /** Card 16c: pictures on notes. Needs no permission (share and the photo picker hand over only what he chose). */
    PICTURES(
        "Pictures on notes", emptyList(),
        "Notes are words only.",
    ),

    /** Card 16e: the in-app camera. The picture goes straight into private storage, never the gallery. */
    CAMERA(
        "Take pictures inside Stash", listOf("android.permission.CAMERA"),
        "You can still share pictures in and pick them from your gallery.",
    ),

    /** Card 16f: the phone reads the printed words in a picture. Needs no permission; the reader is on the phone. */
    PICTURE_TEXT(
        "Read the words in pictures", emptyList(),
        "Pictures are found by what you said, the place and the date, not by the words printed on them.",
    ),

    /**
     * Card 26g: the one switch for recorded voice notes (D77, D82). On: the note is recorded, saved, and written out
     * by the speech model inside Stash. Off: the quick note through the phone's own recogniser, nothing recorded.
     * Until a speech model pack is added the quick note is used whatever this says.
     */
    VOICE_RECORDING(
        "Keep a recording of each voice note", listOf("android.permission.RECORD_AUDIO"),
        "Voice notes still work. The phone writes down your words as you speak and no sound is kept. " +
            "You can't listen again or have a note written out again.",
    ),
}

/** The rule for one capability: the owner's switch, and the permission, and the build. */
object CapabilityRules {
    fun isOn(switchOn: Boolean, permissionGranted: Boolean, buildAllows: Boolean): Boolean =
        switchOn && permissionGranted && buildAllows
}
