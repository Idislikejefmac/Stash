package app.stash.ui.pictures

import app.stash.data.AttachmentInfo
import app.stash.data.Repository
import app.stash.domain.PictureRemoval
import app.stash.domain.RemoveKind

enum class RemoveOutcome { PICTURE_REMOVED, NOTE_REMOVED, NOTHING }

/**
 * Everything the picture composables need from the rest of the app, and nothing else: the pictures of a note
 * (information only: no key, no file name), the loader, and the one destructive action. Screens never see a DAO.
 */
interface PictureHost {
    val loader: PictureLoader

    /** The note's pictures in `position` order; audio is never here. */
    suspend fun pictures(captureId: String): List<AttachmentInfo>

    /** Which question removing a picture of this note asks. */
    suspend fun removeKind(captureId: String): RemoveKind

    /**
     * After the owner confirmed [expected]: removes the picture at once and for good (kind PICTURE), or sends the whole
     * note to the 30-day bin with its picture (kind NOTE). If the note changed meanwhile so that [expected] is no
     * longer the right question, nothing is removed and the answer is [RemoveOutcome.NOTHING].
     */
    suspend fun remove(captureId: String, attachmentId: String, expected: RemoveKind): RemoveOutcome
}

class RepositoryPictureHost(private val repo: Repository, override val loader: PictureLoader) : PictureHost {
    override suspend fun pictures(captureId: String) = repo.attachments.pictures(captureId)

    override suspend fun removeKind(captureId: String): RemoveKind {
        val note = repo.captures.get(captureId) ?: return RemoveKind.PICTURE
        return PictureRemoval.kind(note.text, pictures(captureId).size)
    }

    override suspend fun remove(captureId: String, attachmentId: String, expected: RemoveKind): RemoveOutcome {
        val note = repo.captures.get(captureId) ?: return RemoveOutcome.NOTHING
        val pics = pictures(captureId)
        if (pics.none { it.id == attachmentId }) return RemoveOutcome.NOTHING
        if (PictureRemoval.kind(note.text, pics.size) != expected) return RemoveOutcome.NOTHING
        return when (expected) {
            RemoveKind.NOTE -> { repo.deleteCapture(captureId); RemoveOutcome.NOTE_REMOVED }
            RemoveKind.PICTURE -> if (repo.attachments.remove(attachmentId)) RemoveOutcome.PICTURE_REMOVED else RemoveOutcome.NOTHING
        }
    }
}
