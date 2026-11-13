package app.stash.data

/** Enums stored by name (Room does this for enum columns). Values are the ones in docs/cards/00-READ-FIRST.md. */
enum class CaptureSource { VOICE, TYPED, SHARED, PHOTO }
enum class ExtractState { PENDING, DONE, RETRY, GAVE_UP }
enum class PhotoHint { NONE, PENDING, DISMISSED }
enum class ExtractionStatus { CURRENT, REPLACED, FAILED }
enum class Confidence { HIGH, MEDIUM, LOW }
enum class TaskState { OPEN, SNOOZED, DONE, EXPIRED, DROPPED }
enum class DueKind { NONE, SOON, ON, BY, WINDOW }
enum class ClosedHow { TAP, SPOKEN, EXPIRED, DROPPED }
enum class Direction { PLACED, LENT, BORROWED, RETURNED }
enum class SubjectType { ITEM, PERSON, PLACE, THING }
enum class Volatility { DAILY, NORMAL, STORED }
enum class NudgeType { DAILY, WEEKLY, DUE, ARRIVAL, LEAVING, WATCH }
enum class NudgeOutcome { SHOWN, OPENED, ACTED, DISMISSED, IGNORED }
enum class MissReason { NO_MATCH, MARKED_WRONG }

/** Everything the owner does that cannot be rebuilt from the words (docs/STORAGE.md rule 3). */
enum class RevisionKind {
    TEXT_CORRECTED, KIND_CHANGED, TASK_DONE, TASK_REOPENED, SNOOZED, DROPPED, MERGED, PINNED, EDITED,
    MADE_PRIVATE, MADE_PUBLIC, DELETED, RESTORED,
    /** Card 16a: a file was added to an existing note (`new` = attachment id) or removed from one (`old` = attachment id). */
    PICTURE_ADDED, PICTURE_REMOVED,
    /**
     * Card 26c: a note's recording was removed and its words kept (`old` = attachment id); the words the app uses were
     * replaced by another writing-out of the recording (`old` and `new` = the text before and after).
     */
    AUDIO_REMOVED, TEXT_REWRITTEN,
}

/**
 * What an attachment holds (docs/cards/16a). `AUDIO` is a voice note's recording (D77, card 26c): at most one per
 * capture; everything that lists pictures leaves it out. `SCAN` is added by card 21.
 */
enum class AttachmentKind { PHOTO, SCREENSHOT, AUDIO }

/** How an attachment arrived. `RECORDER` is the app's own recorder (card 26c). */
enum class AttachmentSource { SHARE, CAMERA, PICKER, RECENT, RECORDER }

/**
 * Card 26c: has the note's recording been written out? Null for every note that has no recording (typed, shared,
 * dictated through the phone's recogniser, and every note made before version 4). Separate from [ExtractState],
 * which keeps meaning "have the words been read into records".
 */
enum class TranscribeState { PENDING, DONE, RETRY, GAVE_UP }

/**
 * Card 26c: one writing-out of a recording. `CURRENT` is the one `capture.text` came from; `OFFERED` waits for the
 * owner ("write it out again"); `REPLACED` is an earlier or declined version; `FAILED` holds a reason code and no words.
 */
enum class TranscriptionStatus { CURRENT, OFFERED, REPLACED, FAILED }

enum class DocType { RECORD, CAPTURE }

/** Card 08 E2: which step gave the answer. `NONE`: nothing did (no match, or the question failed). */
enum class TraceRoute { ROUTER, LOOP, SEARCH, NONE }

/** Card 08 E2: who wrote the answer's words. */
enum class WrittenBy { CODE, MODEL }

enum class TraceOutcome { ANSWERED, GAVE_UP, NO_MATCH }
