package app.stash

import android.net.Uri
import android.os.Looper
import app.stash.data.AttachmentSource
import app.stash.share.ShareViewModel
import app.stash.share.SharedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Card 16c: the holder behind the screen, without the screen. The gallery picker's route is here. */
@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest {
    private fun settle(vm: ShareViewModel) {
        val end = System.currentTimeMillis() + 10_000
        while (vm.pending > 0 || vm.saveRequested && !vm.saved && !vm.finishRequested) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) throw AssertionError("timed out")
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun u(s: String) = Uri.parse(s)

    @Test fun picturesFromTheGalleryJoinTheStripAfterTheSharedOnesWithSourcePicker() {
        val h = ShareHarness()
        val vm = ShareViewModel(h.deps)
        vm.begin(SharedContent(listOf(u("content://a/1")), null))
        settle(vm)
        vm.addPicked(listOf(u("content://g/2"), u("content://g/2"), u("content://g/3")))
        settle(vm)
        assertEquals(listOf("content://a/1", "content://g/2", "content://g/3"), h.intake.prepared.toList())
        assertEquals(listOf(AttachmentSource.SHARE, AttachmentSource.PICKER, AttachmentSource.PICKER), h.intake.sources.toList())
        assertEquals(3, vm.pictures.size)
        vm.onSave()
        settle(vm)
        val c = h.repo.let { kotlinx.coroutines.runBlocking { h.database.db.captures().live() } }.single().capture
        val rows = kotlinx.coroutines.runBlocking { h.database.db.attachments().forCapture(c.id) }
        assertEquals(listOf(0, 1, 2), rows.map { it.position })
        assertEquals(listOf(AttachmentSource.SHARE, AttachmentSource.PICKER, AttachmentSource.PICKER), rows.map { it.source })
        h.database.db.close()
    }

    @Test fun aPickedPictureIsIgnoredWhenPicturesAreOff() {
        val h = ShareHarness()
        h.picturesOn = false
        val vm = ShareViewModel(h.deps)
        vm.begin(SharedContent(emptyList(), "words"))
        vm.addPicked(listOf(u("content://g/1")))
        settle(vm)
        assertTrue(h.intake.prepared.isEmpty())
        h.database.db.close()
    }

    @Test fun theProgressNumbersCountTheBatch() {
        val h = ShareHarness()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.intake.gate = gate
        val vm = ShareViewModel(h.deps)
        vm.begin(SharedContent(listOf(u("content://a/1"), u("content://a/2"), u("content://a/3")), null))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, vm.pending); assertEquals(3, vm.batchTotal); assertEquals(0, vm.batchDone)
        gate.complete(Unit)
        settle(vm)
        assertEquals(0, vm.pending); assertEquals(3, vm.batchDone)
        h.database.db.close()
    }
}
