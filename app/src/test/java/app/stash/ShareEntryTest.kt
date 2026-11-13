package app.stash

import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import app.stash.share.ShareActivity
import app.stash.share.ShareIntentReader
import app.stash.share.ShareShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Card 16c: reading the share intent, the manifest's doors, and the share-sheet shortcut. */
@RunWith(RobolectricTestRunner::class)
class ShareEntryTest {
    private val ctx get() = TestSupport.context

    @Test fun oneImageAndALinkAreBothTaken() {
        val i = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://a/1")).putExtra(Intent.EXTRA_TEXT, "https://x.test")
        val c = ShareIntentReader.read(i)
        assertEquals(listOf(Uri.parse("content://a/1")), c.uris)
        assertEquals("https://x.test", c.text)
    }

    @Test fun manyImagesKeepTheirOrderAndDropRepeats() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(
            Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://a/3"), Uri.parse("content://a/1"), Uri.parse("content://a/3"), Uri.parse("content://a/2")),
        )
        assertEquals(listOf("content://a/3", "content://a/1", "content://a/2"), ShareIntentReader.read(i).uris.map { it.toString() })
    }

    @Test fun aStreamWithTextTypeIsNotAPictureAndBlankTextIsNothing() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://a/1")).putExtra(Intent.EXTRA_TEXT, "  ")
        val c = ShareIntentReader.read(i)
        assertTrue(c.uris.isEmpty())
        assertNull(c.text)
    }

    @Test fun otherActionsGiveNothing() {
        assertTrue(ShareIntentReader.read(Intent(Intent.ACTION_VIEW)).uris.isEmpty())
        assertTrue(ShareIntentReader.read(null).uris.isEmpty())
    }

    private fun opensShare(action: String, type: String): Boolean {
        val i = Intent(action).setType(type)
        return ctx.packageManager.queryIntentActivities(i, 0).any { it.activityInfo.name == ShareActivity::class.java.name }
    }

    @Test fun theManifestAcceptsImagesAndTextAndNothingElse() {
        assertTrue(opensShare(Intent.ACTION_SEND, "image/png"))
        assertTrue(opensShare(Intent.ACTION_SEND, "image/jpeg"))
        assertTrue(opensShare(Intent.ACTION_SEND_MULTIPLE, "image/png"))
        assertTrue(opensShare(Intent.ACTION_SEND, "text/plain"))
        assertFalse(opensShare(Intent.ACTION_SEND, "video/mp4"))
        assertFalse(opensShare(Intent.ACTION_SEND, "application/pdf"))
        assertFalse(opensShare(Intent.ACTION_SEND_MULTIPLE, "text/plain"))
    }

    @Test fun theShareTargetFileNamesTheActivityAndTheShortcutCategory() {
        val xml = java.io.File("src/main/res/xml/shortcuts.xml").readText()
        assertTrue(xml.contains("app.stash.share.ShareActivity"))
        assertTrue(xml.contains(ShareShortcut.CATEGORY))
        assertTrue(java.io.File("src/main/AndroidManifest.xml").readText().contains("android.app.shortcuts"))
    }

    @Test fun theShortcutIsPublishedWithOnlyALabel() {
        assertTrue(ShareShortcut.publish(ctx))
        val s = ShortcutManagerCompat.getDynamicShortcuts(ctx).single { it.id == ShareShortcut.ID }
        assertEquals("Stash", s.shortLabel.toString())
        assertEquals(setOf(ShareShortcut.CATEGORY), s.categories)
    }
}
