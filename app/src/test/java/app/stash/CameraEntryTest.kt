package app.stash

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.stash.capture.CaptureEntry
import app.stash.capture.DirectSink
import app.stash.capture.InboundSink
import app.stash.data.Capabilities
import app.stash.data.CaptureSource
import app.stash.data.PermissionCheck
import app.stash.data.SwitchStore
import app.stash.domain.Capability
import app.stash.share.shareSave
import app.stash.ui.CameraNoteButton
import app.stash.ui.CapabilityRow
import app.stash.ui.cameraVisible
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class Switches(private val map: MutableMap<Capability, Boolean> = mutableMapOf()) : SwitchStore {
    override fun isSwitchOn(c: Capability) = map[c] ?: c.defaultOn
    override fun setSwitch(c: Capability, on: Boolean) { map[c] = on }
}

/** Card 16e: the entry points. The main screen cannot be launched in a test (card 16d notes why), so its pieces are tested. */
@RunWith(RobolectricTestRunner::class)
class CameraEntryTest {
    @get:Rule val compose = createComposeRule()

    private fun rowsFor(caps: Capabilities) = Capability.entries.map {
        CapabilityRow(it, caps.switchOn(it), caps.permissionGranted(it), caps.isOn(it))
    }

    @Test fun withTheCameraSwitchedOffTheMainScreenHasNoCameraButton() {
        val switches = Switches().also { it.setSwitch(Capability.CAMERA, false) }
        val caps = Capabilities(switches, PermissionCheck { true })
        assertFalse(cameraVisible(rowsFor(caps), hasCamera = true))
        compose.setContent { CameraNoteButton(visible = cameraVisible(rowsFor(caps), true)) {} }
        assertTrue(compose.onAllNodesWithTag("takePicture").fetchSemanticsNodes().isEmpty())
    }

    @Test fun withTheCameraOnTheMainScreenHasTheButtonAndItWorksEvenBeforeThePermissionIsGiven() {
        val caps = Capabilities(Switches(), PermissionCheck { false }) // not allowed yet: asked on first use
        assertTrue(cameraVisible(rowsFor(caps), hasCamera = true))
        var taps = 0
        compose.setContent { CameraNoteButton(visible = cameraVisible(rowsFor(caps), true)) { taps++ } }
        compose.onNodeWithTag("takePicture").performClick()
        assertEquals(1, taps)
    }

    @Test fun noButtonWhenPicturesAreOffOrThePhoneHasNoCamera() {
        val picturesOff = Capabilities(Switches().also { it.setSwitch(Capability.PICTURES, false) }, PermissionCheck { true })
        assertFalse(cameraVisible(rowsFor(picturesOff), hasCamera = true))
        assertFalse(cameraVisible(rowsFor(Capabilities(Switches(), PermissionCheck { true })), hasCamera = false))
    }

    @Test fun theCameraSwitchSitsInTheCapabilityListWithItsPermission() {
        val rows = rowsFor(Capabilities(Switches(), PermissionCheck { false }))
        val r = rows.single { it.capability == Capability.CAMERA }
        assertTrue(r.switchOn)
        assertFalse(r.permissionGranted)
        assertFalse(r.on)
    }

    @Test fun aNewNoteFromTheCameraIsSavedAsAPhotoNote() {
        val h = ShareHarness()
        try {
            val entry = CaptureEntry({ it }, DirectSink(h.repo.captures, { emptyList() }, attachments = h.repo.attachments), { null }, "test", h.clock)
            val result = runBlocking { shareSave(entry, CaptureSource.PHOTO)("test bottle", false, false, emptyList()) }
            assertTrue(result is InboundSink.Result.Saved)
            val c = runBlocking { h.database.db.captures().live() }.single().capture
            assertEquals(CaptureSource.PHOTO, c.source)
            // And Share to Stash keeps marking its notes SHARED.
            runBlocking { shareSave(entry, CaptureSource.SHARED)("x", false, false, emptyList()) }
            assertEquals(setOf(CaptureSource.PHOTO, CaptureSource.SHARED), runBlocking { h.database.db.captures().live() }.map { it.capture.source }.toSet())
        } finally {
            h.database.db.close()
        }
    }
}
