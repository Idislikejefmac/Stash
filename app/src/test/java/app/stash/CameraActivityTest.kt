package app.stash

import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ActivityScenario
import app.stash.capture.CameraActivity
import app.stash.capture.CameraDepsProvider
import app.stash.capture.Inbound
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.domain.CameraException
import app.stash.domain.CameraFailure
import app.stash.domain.CameraFlash
import app.stash.domain.CameraRules
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EventSink
import app.stash.domain.JpegOrientation
import app.stash.pictures.PictureFailure
import app.stash.share.ShareDepsProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/** Card 16e: the camera screen, driven through the real activity with a fake camera and a fake preparer. */
@RunWith(RobolectricTestRunner::class)
class CameraActivityTest {
    @get:Rule val compose: ComposeTestRule = createEmptyComposeRule()

    private lateinit var c: CameraHarness
    private var scenario: ActivityScenario<CameraActivity>? = null
    private lateinit var activity: CameraActivity
    private val events = CopyOnWriteArrayList<Triple<DbgComponent, DbgCode, Pair<Long?, Long?>>>()
    private val msOf = CopyOnWriteArrayList<Long?>()

    @Before fun setUp() {
        c = CameraHarness()
        CameraDepsProvider.factory = { c.deps }
        ShareDepsProvider.factory = { c.h.deps }
        Dbg.sink = EventSink { component, code, n1, n2, _, ms ->
            events += Triple(component, code, n1 to n2)
            msOf += ms
        }
    }

    @After fun tearDown() {
        scenario?.close()
        CameraDepsProvider.factory = null
        ShareDepsProvider.factory = null
        Dbg.sink = null
        c.h.database.db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun launch(captureId: String? = null) {
        val s = ActivityScenario.launch<CameraActivity>(CameraActivity.intent(TestSupport.context, captureId))
        s.onActivity { activity = it }
        scenario = s
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (cond()) return
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun thumbCount() = compose.onAllNodesWithTag("remove").fetchSemanticsNodes().size
    private fun shutter() = compose.onNodeWithTag("shutter").performClick()
    private fun onCamera() = awaitUntil("camera screen") { has("shutter") }
    private fun shootAndWait(afterTag: String) {
        onCamera()
        shutter()
        awaitUntil("after the shot: $afterTag") { has(afterTag) }
    }

    private fun seedNote(): String = runBlocking {
        c.h.repo.captures.insertCapture(rawText = "my note", text = "my note", source = CaptureSource.VOICE).id
    }

    private fun captures() = runBlocking { c.h.database.db.captures().live() }.map { it.capture }
    private fun attachmentsOf(id: String) = runBlocking { c.h.database.db.attachments().forCapture(id) }

    // ---------------------------------------------------------------- an existing note

    @Test fun aShotForAnExistingNoteIsAddedOnceWithThatIdAndTheAnnotateScreenIsNeverShown() {
        val id = seedNote()
        launch(id)
        shootAndWait("added")
        val adds = c.sink.additions()
        assertEquals(1, adds.size)
        assertEquals(id, adds[0].captureId)
        assertEquals(1, adds[0].attachments.size)
        assertEquals(AttachmentSource.CAMERA, adds[0].attachments[0].source)
        assertFalse(has("words"))
        assertFalse(has("save"))
        compose.onNodeWithTag("added").assertTextEquals("Added 1 picture")
        assertEquals(1, c.cues.saved)
        assertEquals(listOf(id), captures().map { it.id }) // no second note was made
        assertEquals(1, attachmentsOf(id).size)
    }

    @Test fun takeAnotherGoesBackToTheCameraAndTheNextPicturesJoinTheNoteInOrder() {
        val id = seedNote()
        launch(id)
        shootAndWait("added")
        compose.onNodeWithTag("another").performClick()
        shootAndWait("added")
        compose.onNodeWithTag("added").assertTextEquals("Added 2 pictures")
        val ids = c.sink.additions().flatMap { it.attachments }.map { it.id }
        assertEquals(c.photos.made.map { it.attachment.id }, ids)
        assertEquals(ids, attachmentsOf(id).sortedBy { it.position }.map { it.id })
        assertEquals(listOf(0, 1), attachmentsOf(id).map { it.position }.sorted())
        compose.onNodeWithTag("done").performClick()
        awaitUntil("done closes") { activity.isFinishing }
        assertEquals(2, attachmentsOf(id).size)
    }

    @Test fun aFailedSaveOnAnExistingNoteDeletesThePictureAndSaysSo() {
        val id = seedNote()
        c.sink.failNext = true
        launch(id)
        onCamera()
        shutter()
        awaitUntil("message") { has("message") }
        compose.onNodeWithTag("message").assertTextEquals(CameraRules.COULD_NOT_KEEP)
        assertEquals(c.photos.made.map { it.attachment.id }, c.photos.discarded.toList())
        assertEquals(0, attachmentsOf(id).size)
        assertEquals(1, c.cues.failed)
        assertTrue(has("shutter")) // can try again
    }

    // ---------------------------------------------------------------- a new note

    @Test fun aShotForANewNoteOpensTheAnnotateScreenWithOnePictureAndSavesNothingYet() {
        launch()
        shootAndWait("words")
        awaitUntil("one picture") { thumbCount() == 1 }
        assertTrue(c.sink.items.isEmpty())
        assertEquals(0, captures().size)
        assertEquals(0, c.h.saveCalls.size)
        assertFalse(has("shutter"))
    }

    @Test fun savingANewNoteMakesOneNoteWithItsPictureAndClosesTheCamera() {
        launch()
        shootAndWait("words")
        awaitUntil("one picture") { thumbCount() == 1 }
        compose.onNodeWithTag("words").performTextReplacement("test bottle")
        compose.onNodeWithTag("save").performScrollTo().performClick()
        awaitUntil("closed") { activity.isFinishing }
        assertEquals(1, c.h.saveCalls.size)
        val a = c.h.saveCalls[0].single()
        assertEquals(AttachmentSource.CAMERA, a.source)
        assertEquals("test bottle", captures().single().text)
        assertEquals(1, attachmentsOf(captures().single().id).size)
        // A camera picture is not in the gallery, so the "still in your gallery" line is never shown.
        assertFalse(has("galleryNote"))
    }

    @Test fun cameraInAddAnotherComesBackHereAndOneNoteCollectsThreePicturesInTheOrderTaken() {
        launch()
        shootAndWait("words")
        awaitUntil("one picture") { thumbCount() == 1 }
        compose.onNodeWithTag("camera").performScrollTo().performClick()
        shootAndWait("words")
        compose.onNodeWithTag("camera").performScrollTo().performClick()
        shootAndWait("words")
        awaitUntil("three pictures") { thumbCount() == 3 }
        compose.onNodeWithTag("save").performScrollTo().performClick()
        awaitUntil("closed") { activity.isFinishing }
        val note = captures().single()
        val saved = attachmentsOf(note.id).sortedBy { it.position }
        assertEquals(3, saved.size)
        assertEquals(c.photos.made.map { it.attachment.id }, saved.map { it.id })
        assertEquals(3, c.engine.captures)
    }

    @Test fun backFromTheCameraWithPicturesAlreadyTakenReturnsToThemAndWithNoneLeaves() {
        launch()
        onCamera()
        activity.onBackPressedDispatcher.onBackPressed()
        awaitUntil("left") { activity.isFinishing }

        scenario!!.close()
        launch()
        shootAndWait("words")
        compose.onNodeWithTag("camera").performScrollTo().performClick()
        onCamera()
        activity.onBackPressedDispatcher.onBackPressed()
        awaitUntil("back on the pictures") { has("words") }
        assertFalse(activity.isFinishing)
        assertEquals(1, thumbCount())
    }

    @Test fun cancellingTheAnnotateScreenDeletesTheTakenPictureAndSavesNothing() {
        launch()
        shootAndWait("words")
        awaitUntil("one picture") { thumbCount() == 1 }
        compose.onNodeWithTag("cancel").performScrollTo().performClick()
        awaitUntil("closed") { activity.isFinishing }
        assertEquals(0, captures().size)
        assertEquals(c.h.intake.discarded.size + c.photos.discarded.size, 1)
    }

    @Test fun whileLockedTheAnnotateScreenOffersTheCameraButNotTheGallery() {
        c.h.locked = true
        launch()
        shootAndWait("words")
        awaitUntil("one picture") { thumbCount() == 1 }
        assertTrue(has("camera"))
        assertTrue(compose.onAllNodesWithText("Gallery").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("save").performScrollTo().performClick()
        awaitUntil("closed") { activity.isFinishing }
        assertEquals(true, c.h.lastLocked)
    }

    // ---------------------------------------------------------------- rotation (item 6)

    @Test fun theCamerasRotationBecomesTheOnlyOrientationTagInTheBytesHandedToThePreparer() {
        for ((deg, tag) in listOf(0 to 1, 90 to 6, 180 to 3, 270 to 8)) {
            c.engine.rotation = deg
            val id = seedNote()
            launch(id)
            shootAndWait("added")
            assertEquals("rotation $deg", tag, JpegOrientation.read(c.photos.received.last()))
            scenario!!.close()
        }
    }

    @Test fun aJpegThatAlreadyCarriesItsOwnTagIsNotTurnedASecondTime() {
        // The picture says "turn 90" inside, the camera says it is already upright: the tag handed on must say upright.
        c.engine.jpeg = JpegOrientation.withOrientation(tinyJpeg(), 90)
        assertEquals(6, JpegOrientation.read(c.engine.jpeg))
        c.engine.rotation = 0
        launch(seedNote())
        shootAndWait("added")
        assertEquals(1, JpegOrientation.read(c.photos.received.single()))
    }

    // ---------------------------------------------------------------- permission, switch, lock

    private fun requestedPermissions(): List<String> =
        shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList() ?: emptyList()

    @Test fun withoutThePermissionAnUnlockedPhoneIsAskedOnceAndTheScreenSaysSoAndOffersThePicker() {
        c.granted = false
        launch()
        awaitUntil("notice") { has("notice") }
        assertEquals(listOf("android.permission.CAMERA"), requestedPermissions())
        compose.onNodeWithTag("notice").assertTextEquals(CameraRules.NEEDS_PERMISSION)
        assertTrue(has("allow"))
        assertTrue(has("gallery"))
        assertTrue(has("close"))
        assertFalse(has("shutter"))
        assertEquals(0, c.engine.captures)
    }

    @Test fun withoutThePermissionALockedPhoneIsNotAskedAndSaysToOpenStashOnce() {
        c.granted = false
        c.h.locked = true
        launch()
        awaitUntil("notice") { has("notice") }
        assertTrue(requestedPermissions().isEmpty())
        compose.onNodeWithTag("notice").assertTextEquals("Open Stash once and allow the camera.")
        assertFalse(has("allow"))
        assertFalse(has("gallery"))
        assertFalse(has("shutter"))
    }

    @Test fun aSwitchedOffCameraSaysSoAndShowsNoShutter() {
        c.cameraOn = false
        launch()
        awaitUntil("notice") { has("notice") }
        compose.onNodeWithTag("notice").assertTextEquals(CameraRules.SWITCHED_OFF)
        assertFalse(has("shutter"))
        assertTrue(has("gallery"))
    }

    @Test fun picturesSwitchedOffSaysSoAndOffersNoPicker() {
        c.h.picturesOn = false
        launch()
        awaitUntil("notice") { has("notice") }
        compose.onNodeWithTag("notice").assertTextEquals(CameraRules.PICTURES_OFF)
        assertFalse(has("gallery"))
        assertFalse(has("shutter"))
    }

    // ---------------------------------------------------------------- window and manifest

    @Test fun theScreenBlocksScreenshotsAndTheManifestEntryIsAnAddingActivity() {
        launch()
        onCamera()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val block = manifest.substringAfter("android:name=\".capture.CameraActivity\"").substringBefore("/>")
        assertTrue(block.contains("android:exported=\"false\""))
        assertTrue(block.contains("android:showWhenLocked=\"true\""))
        assertTrue(block.contains("android:turnScreenOn=\"true\""))
        assertTrue(block.contains("android:excludeFromRecents=\"true\""))
        assertTrue(block.contains("android:taskAffinity=\"\""))
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.CAMERA\" />"))
        assertTrue(manifest.contains("<uses-feature android:name=\"android.hardware.camera.any\" android:required=\"false\" />"))
    }

    // ---------------------------------------------------------------- failures and events

    @Test fun aCameraFailureSaysSoPlaysTheFailureCueSavesNothingAndLetsTheOwnerTryAgain() {
        launch()
        c.engine.failWith = CameraException(CameraFailure.CAPTURE)
        onCamera()
        shutter()
        awaitUntil("message") { has("message") }
        compose.onNodeWithTag("message").assertTextEquals(CameraRules.FAILED)
        assertEquals(1, c.cues.failed)
        assertTrue(c.sink.items.isEmpty())
        assertTrue(c.photos.made.isEmpty())
        assertTrue(has("shutter"))
        assertTrue(events.any { it.first == DbgComponent.CAMERA && it.second == DbgCode.FAILED && it.third.first == CameraFailure.CAPTURE.code.toLong() })
        // And a second try works.
        c.engine.failWith = null
        shutter()
        awaitUntil("words") { has("words") }
    }

    @Test fun aPictureThatCannotBePreparedSaysSoAndLeavesNothingBehind() {
        c.photos.failWith = PictureFailure.UNDECODABLE
        launch(seedNote())
        onCamera()
        shutter()
        awaitUntil("message") { has("message") }
        compose.onNodeWithTag("message").assertTextEquals(CameraRules.COULD_NOT_KEEP)
        assertTrue(c.sink.items.filterIsInstance<Inbound.AddAttachments>().isEmpty())
    }

    @Test fun eventsAreOpenedWithTheLockFlagAndTakenWithTheTime() {
        c.h.locked = true
        launch()
        onCamera()
        assertTrue(events.any { it.first == DbgComponent.CAMERA && it.second == DbgCode.OPENED && it.third.first == 1L })
        shutter()
        awaitUntil("words") { has("words") }
        val i = events.indexOfFirst { it.first == DbgComponent.CAMERA && it.second == DbgCode.TAKEN }
        assertTrue(i >= 0)
        assertNotNull(msOf[i])
        assertTrue(msOf[i]!! > 0)
    }

    @Test fun theFlashButtonCyclesOffAutoOn() {
        launch()
        onCamera()
        compose.onNodeWithTag("flash").assertTextEquals("Flash: off")
        compose.onNodeWithTag("flash").performClick()
        compose.onNodeWithTag("flash").assertTextEquals("Flash: auto")
        assertEquals(CameraFlash.AUTO, c.engine.currentFlash)
        compose.onNodeWithTag("flash").performClick()
        assertEquals(CameraFlash.ON, c.engine.currentFlash)
        compose.onNodeWithTag("flash").performClick()
        assertEquals(CameraFlash.OFF, c.engine.currentFlash)
    }

    @Test fun theIntentCarriesOnlyAnIdAndNeverNoteContent() {
        val none = CameraActivity.intent(TestSupport.context)
        assertFalse(none.hasExtra(CameraActivity.EXTRA_CAPTURE_ID))
        val one: Intent = CameraActivity.intent(TestSupport.context, "abc")
        assertEquals("abc", one.getStringExtra(CameraActivity.EXTRA_CAPTURE_ID))
        assertEquals(setOf("capture_id"), one.extras!!.keySet())
    }
}
