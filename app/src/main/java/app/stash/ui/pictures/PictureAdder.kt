package app.stash.ui.pictures

import android.net.Uri
import app.stash.capture.Inbound
import app.stash.capture.InboundSink
import app.stash.data.AttachmentSource
import app.stash.pictures.Prepared
import app.stash.share.PictureIntake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What adding pictures to a note came to. [failed] means the save did not happen and the prepared files were thrown away. */
data class AddOutcome(val added: Int, val couldNotRead: Int, val failed: Boolean)

/**
 * Adding pictures to a note that already exists (docs/cards/16d section B): each picked picture is prepared (source
 * PICKER) one after another, and the list goes in as one [Inbound.AddAttachments]. Never touches the note's words,
 * records or Private mark: it only hands files to the sink.
 */
class PictureAdder(private val intake: PictureIntake, private val sink: InboundSink) {
    /** [progress] is called with "k of n" before the k-th picture is prepared. */
    suspend fun add(captureId: String, uris: List<Uri>, progress: (current: Int, total: Int) -> Unit = { _, _ -> }): AddOutcome =
        // Finish what was started even if the screen goes away: the files must be saved or deleted, not left over.
        withContext(NonCancellable) {
            val prepared = mutableListOf<Prepared>()
            var unreadable = 0
            val list = uris.distinct()
            list.forEachIndexed { i, uri ->
                progress(i + 1, list.size)
                try {
                    prepared += intake.prepare(uri, AttachmentSource.PICKER)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    unreadable++
                }
            }
            if (prepared.isEmpty()) return@withContext AddOutcome(0, unreadable, failed = unreadable > 0)
            val result = try {
                sink.accept(Inbound.AddAttachments(captureId, prepared.map { it.attachment }))
            } catch (e: Exception) {
                InboundSink.Result.Failed(e.javaClass.simpleName)
            }
            if (result is InboundSink.Result.Failed) {
                prepared.forEach { intake.discard(it) }
                AddOutcome(0, unreadable, failed = true)
            } else {
                AddOutcome(prepared.size, unreadable, failed = false)
            }
        }
}
