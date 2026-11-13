package app.stash.share

import android.content.Intent
import android.net.Uri

/** What another app handed to Stash: pictures (in the order shared) and any words or link. */
class SharedContent(val uris: List<Uri>, val text: String?) {
    companion object {
        val EMPTY = SharedContent(emptyList(), null)
    }
}

/**
 * Reads the share intent at once (docs/cards/16c item 2): permission to read a shared picture lasts only as long as the
 * receiving activity, so the Uris are taken in `onCreate`. Only pictures are accepted from the stream: the filters
 * name only image types, and a stream that comes with `text/plain` is not a picture.
 */
object ShareIntentReader {
    fun read(intent: Intent?): SharedContent {
        if (intent == null) return SharedContent.EMPTY
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return SharedContent.EMPTY
        val type = intent.type
        val pictures = type == null || type.startsWith("image/")
        val uris = LinkedHashSet<Uri>()
        if (pictures) {
            if (action == Intent.ACTION_SEND) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it.filterNotNull() }
            }
            if (uris.isEmpty()) {
                val clip = intent.clipData
                if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
            }
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        return SharedContent(uris.toList(), text)
    }
}
