package app.stash.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.stash.R

/**
 * The one sharing shortcut ("Stash") that lets the app appear in the share sheet's top row (docs/cards/16c item 12).
 * It matches the `share-target` in `res/xml/shortcuts.xml` by category. It carries a label and an icon, nothing else.
 */
object ShareShortcut {
    const val ID = "stash_share"
    const val CATEGORY = "app.stash.category.SHARE_TARGET"

    fun publish(context: Context): Boolean = try {
        val info = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel("Stash")
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_note))
            .setIntent(Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, info)
        true
    } catch (e: Exception) {
        false // the ordinary share list still has Stash
    }
}
