package app.stash

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ActivityScenario
import app.stash.capture.InboundSink
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.share.ShareActivity
import app.stash.share.ShareDepsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Card 16c: the share screen, driven through the real activity with a fake preparer and a fake speech engine. */
@RunWith(RobolectricTestRunner::class)
class ShareActivityTest {
    @get:Rule val compose: ComposeTestRule = createEmptyComposeRule()

    private lateinit var h: ShareHarness
    private var scenario: ActivityScenario<ShareActivity>? = null
    private lateinit var activity: ShareActivity

    @Before fun setUp() {
        h = ShareHarness()
        ShareDepsProvider.factory = { h.deps }
    }

    @After fun tearDown() {
        scenario?.close()
        ShareDepsProvider.factory = null
        h.database.db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun send(vararg uris: String, text: String? = null): Intent {
        val i = when (uris.size) {
            0 -> Intent(Intent.ACTION_SEND).setType("text/plain")
            1 -> Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, Uri.parse(uris[0]))
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.map { Uri.parse(it) }))
        }
        if (text != null) i.putExtra(Intent.EXTRA_TEXT, text)
        return i.setClass(TestSupport.context, ShareActivity::class.java)
    }

    private fun launch(intent: Intent) {
        val s = ActivityScenario.launch<ShareActivity>(intent)
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
    private fun ready(n: Int) = awaitUntil("$n pictures ready") { thumbCount() == n && !has("progress") }
    private fun tapSave() = compose.onNodeWithTag("save").performScrollTo().performClick()
    private fun savedAndClosed() = awaitUntil("saved and closed") { activity.isFinishing }
    private fun captures() = runBlocking { h.database.db.captures().live() }.map { it.capture }
    private fun attachmentsOf(id: String) = runBlocking { h.database.db.attachments().forCapture(id) }

    // ---------------------------------------------------------------- the card's acceptance checks

    @Test fun oneSharedImageThenSaveMakesOneCaptureAndOneAttachment() {
        launch(send("content://shot/1"))
        ready(1)
        compose.onNodeWithTag("words").performTextReplacement("saves the day at Phoenix")
        tapSave()
        savedAndClosed()
        val sqlSoFar = h.database.sql.toList()

        val c = captures().single()
        assertEquals(CaptureSource.SHARED, c.source)
        assertEquals("saves the day at Phoenix", c.text)
        assertFalse(c.sensitive)
        val a = attachmentsOf(c.id).single()
        assertEquals(0, a.position)
        assertEquals(AttachmentSource.SHARE, a.source)
        assertEquals(listOf("content://shot/1"), h.intake.prepared.toList())
        assertEquals(1, h.saveCalls.size)
        assertEquals(0, h.intake.discarded.size)
        assertEquals(1, h.cues.saved)

        // An adding screen: nothing stored was read (no SELECT on notes, records, the index, extractions or tasks).
        // (Saving reads the new capture's own attachment rows to number them; that is the save path, not a stored note.)
        val reads = sqlSoFar.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
        val notes = reads.filter { Regex("""\b(from|join)\s+"?(capture|record|search_fts|extraction|task)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        assertTrue("stored notes were read: $notes", notes.isEmpty())

        // No notification from this card's code.
        val nm = TestSupport.context.getSystemService(NotificationManager::class.java)
        assertEquals(0, shadowOf(nm).size())
    }

    @Test fun fourImagesTheSecondFailsMakesOneCaptureWithThreeAttachmentsInOrder() {
        h.intake.failUris = setOf("content://shot/2")
        launch(send("content://shot/1", "content://shot/2", "content://shot/3", "content://shot/4"))
        ready(3)
        compose.onNodeWithTag("notAdded").assertTextEquals("1 picture could not be added")
        tapSave()
        savedAndClosed()

        val c = captures().single()
        assertEquals("", c.text)
        val rows = attachmentsOf(c.id)
        assertEquals(listOf(0, 1, 2), rows.map { it.position })
        assertEquals(h.intake.prepared.toList(), listOf("content://shot/1", "content://shot/3", "content://shot/4"))
        // The rows are in the order the pictures were made: ids match the order the intake produced them.
        val expectedBytes = listOf("content://shot/1", "content://shot/3", "content://shot/4")
        val actualBytes = rows.map { r -> String(h.store.open(r.fileName, r.fileKey, r.id).readBytes()) }
        assertEquals(expectedBytes, actualBytes)
    }

    @Test fun savingWithPicturesAndNoWordsIsAllowedAndMakesACaptureWithEmptyText() {
        launch(send("content://shot/1"))
        ready(1)
        compose.onNodeWithTag("save").assertIsEnabled()
        tapSave()
        savedAndClosed()
        val c = captures().single()
        assertEquals("", c.text)
        assertEquals(1, attachmentsOf(c.id).size)
    }

    @Test fun savingWithNeitherIsNotPossible() {
        launch(send())
        awaitUntil("screen") { has("save") }
        compose.onNodeWithTag("save").assertIsNotEnabled()
        // Words alone enable it; taking the words away disables it again.
        compose.onNodeWithTag("words").performTextReplacement("milk")
        compose.onNodeWithTag("save").assertIsEnabled()
        compose.onNodeWithTag("words").performTextReplacement("")
        compose.onNodeWithTag("save").assertIsNotEnabled()
    }

    @Test fun removingTheOnlyPictureDisablesSaveAndDeletesItsFile() {
        launch(send("content://shot/1"))
        ready(1)
        compose.onNodeWithTag("remove").performClick()
        awaitUntil("removed") { thumbCount() == 0 }
        compose.onNodeWithTag("save").assertIsNotEnabled()
        assertEquals(1, h.intake.discarded.size)
        assertTrue(h.store.list().isEmpty())
    }

    @Test fun sharedTextOnlyMakesOneCaptureWithNoAttachmentAndTheTextAsShared() {
        launch(send(text = "https://example.test/page"))
        awaitUntil("screen") { has("words") }
        assertEquals("https://example.test/page", fieldText())
        tapSave()
        savedAndClosed()
        val c = captures().single()
        assertEquals("https://example.test/page", c.text)
        assertEquals(CaptureSource.SHARED, c.source)
        assertTrue(attachmentsOf(c.id).isEmpty())
        assertTrue(h.intake.prepared.isEmpty())
    }

    @Test fun cancelAfterThreePicturesDiscardsThreeAndSavesNothing() {
        launch(send("content://shot/1", "content://shot/2", "content://shot/3"))
        ready(3)
        compose.onNodeWithTag("cancel").performScrollTo().performClick()
        savedAndClosed()
        assertEquals(3, h.intake.discarded.size)
        assertEquals(h.intake.prepared.size, h.intake.discarded.size)
        assertTrue(h.store.list().isEmpty())
        assertTrue(captures().isEmpty())
        assertTrue(h.saveCalls.isEmpty())
    }

    @Test fun theBackGestureCancelsToo() {
        launch(send("content://shot/1", "content://shot/2"))
        ready(2)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        savedAndClosed()
        assertEquals(2, h.intake.discarded.size)
        assertTrue(captures().isEmpty())
    }

    @Test fun thePrivateToggleSavesAPrivateNote() {
        launch(send("content://shot/1"))
        ready(1)
        compose.onNodeWithText("Private").performScrollTo().performClick()
        tapSave()
        savedAndClosed()
        assertTrue(captures().single().sensitive)
    }

    @Test fun withPicturesOffAnImageShareSavesNothingAndShowsTheMessageAndClose() {
        h.picturesOn = false
        launch(send("content://shot/1"))
        awaitUntil("message") { has("notice") && has("close") }
        compose.onNodeWithTag("notice").assertTextEquals("Pictures are switched off in Stash. Turn them on in Settings.")
        assertFalse(has("save"))
        assertTrue(h.intake.prepared.isEmpty())
        compose.onNodeWithTag("close").performClick()
        savedAndClosed()
        assertTrue(captures().isEmpty())
        assertTrue(h.store.list().isEmpty())
    }

    @Test fun withPicturesOffSharedTextStillWorks() {
        h.picturesOn = false
        launch(send(text = "a link"))
        awaitUntil("screen") { has("save") }
        assertFalse(has("remove"))
        tapSave()
        savedAndClosed()
        assertEquals("a link", captures().single().text)
    }

    @Test fun withPicturesOffAnImageAndTextKeepsTheTextAndSaysWhyThePictureIsGone() {
        h.picturesOn = false
        launch(send("content://shot/1", text = "look at this"))
        awaitUntil("screen") { has("save") }
        compose.onNodeWithTag("notice").assertTextEquals("Pictures are switched off in Stash. Turn them on in Settings.")
        tapSave()
        savedAndClosed()
        val c = captures().single()
        assertEquals("look at this", c.text)
        assertTrue(attachmentsOf(c.id).isEmpty())
        assertTrue(h.intake.prepared.isEmpty())
    }

    @Test fun theScreenBlocksScreenshotsAndIsNotInRecentsAndNotShownWhenLocked() {
        launch(send("content://shot/1"))
        ready(1)
        val flags = activity.window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val block = manifest.substringAfter("android:name=\".share.ShareActivity\"").substringBefore("</activity>")
        assertTrue(block.contains("android:exported=\"true\""))
        assertTrue(block.contains("android:excludeFromRecents=\"true\""))
        assertTrue(block.contains("android:taskAffinity=\"\""))
        assertFalse(block.contains("showWhenLocked"))
        assertFalse(block.contains("noHistory"))
        assertFalse(block.contains("turnScreenOn"))
    }

    // ---------------------------------------------------------------- more behaviour

    @Test fun rotationLosesNothingAndPreparesNothingTwice() {
        launch(send("content://shot/1", "content://shot/2", "content://shot/3"))
        ready(3)
        compose.onNodeWithTag("words").performTextReplacement("kept")
        scenario!!.recreate()
        scenario!!.onActivity { activity = it }
        awaitUntil("redrawn") { thumbCount() == 3 }
        assertEquals("kept", fieldText())
        assertEquals(3, h.intake.prepared.size)
        assertEquals(0, h.intake.discarded.size)
        tapSave()
        savedAndClosed()
        assertEquals(3, attachmentsOf(captures().single().id).size)
        assertEquals(3, h.intake.prepared.size)
    }

    @Test fun saveTappedWhilePicturesAreStillBeingPreparedWaitsAndThenSaves() {
        val gate = CompletableDeferred<Unit>()
        h.intake.gate = gate
        launch(send("content://shot/1", "content://shot/2"))
        awaitUntil("progress") { has("progress") }
        compose.onNodeWithTag("progress").assertTextEquals("Getting 1 of 2 ready")
        compose.onNodeWithTag("save").assertIsEnabled()
        tapSave()
        compose.onNodeWithText("Saving...").assertExists()
        assertTrue(h.saveCalls.isEmpty())
        gate.complete(Unit)
        savedAndClosed()
        assertEquals(2, attachmentsOf(captures().single().id).size)
    }

    @Test fun aFailedSaveKeepsThePreparedFilesAndSaveCanBePressedAgain() {
        h.failSaves = 1
        launch(send("content://shot/1"))
        ready(1)
        tapSave()
        awaitUntil("failure shown") { has("message") }
        compose.onNodeWithTag("message").assertTextEquals("I couldn't save that. Try again.")
        assertEquals(0, h.intake.discarded.size)
        assertEquals(1, h.store.list().size)
        assertEquals(1, h.cues.failed)
        compose.onNodeWithTag("save").assertIsEnabled()
        tapSave()
        savedAndClosed()
        assertEquals(2, h.saveCalls.size)
        assertEquals(1, attachmentsOf(captures().single().id).size)
        assertEquals(1, h.store.list().size)
    }

    @Test fun theGalleryNoteShowsOnlyTheFirstTime() {
        launch(send("content://shot/1"))
        ready(1)
        assertTrue(has("galleryNote"))
        assertTrue(h.seenGalleryNote)
        scenario!!.close()
        launch(send("content://shot/2"))
        ready(1)
        assertFalse(has("galleryNote"))
    }

    @Test fun theGalleryButtonIsHiddenWhenThePhoneIsLocked() {
        launch(send("content://shot/1"))
        ready(1)
        compose.onNodeWithText("Gallery").assertExists()
        scenario!!.close()
        val km = TestSupport.context.getSystemService(KeyguardManager::class.java)
        shadowOf(km).setKeyguardLocked(true)
        h.locked = true
        launch(send("content://shot/1"))
        ready(1)
        assertTrue(compose.onAllNodesWithText("Gallery").fetchSemanticsNodes().isEmpty())
        tapSave()
        savedAndClosed()
        assertEquals(true, captures().single().capturedLocked)
    }

    @Test fun theGalleryButtonIsHiddenWhenPicturesAreOffButSharedTextStillSaves() {
        h.picturesOn = false
        launch(send(text = "x"))
        awaitUntil("screen") { has("save") }
        assertTrue(compose.onAllNodesWithText("Gallery").fetchSemanticsNodes().isEmpty())
    }

    @Test fun tappingAThumbnailShowsItLargerAndBackClosesThatFirst() {
        launch(send("content://shot/1"))
        ready(1)
        compose.onNodeWithContentDescription("Picture. Tap to see it larger").performClick()
        awaitUntil("enlarged") { has("enlarged") }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitUntil("closed") { !has("enlarged") }
        assertFalse(activity.isFinishing)
        assertEquals(0, h.intake.discarded.size)
    }

    // ---------------------------------------------------------------- listening

    @Test fun listeningStartsByItselfWhenTheFirstPictureIsReadyAndTheWordsJoinTheField() {
        h.voiceOn = true
        launch(send("content://shot/1", text = "link"))
        ready(1)
        awaitUntil("listening started") { h.speech.starts == 1 }
        assertEquals(1, h.cues.started)
        compose.onNodeWithTag("mic").assertExists()
        h.speech.listener!!.onPartial("tell me when")
        awaitUntil("partial shown") { has("words") && fieldText() == "link tell me when" }
        h.speech.listener!!.onFinal("tell me when they play")
        awaitUntil("final shown") { fieldText() == "link tell me when they play" }
        tapSave()
        savedAndClosed()
        assertEquals("link tell me when they play", captures().single().text)
        assertEquals(1, h.speech.starts) // one picture ready: started once
    }

    @Test fun tappingTheTextFieldStopsListening() {
        h.voiceOn = true
        launch(send("content://shot/1"))
        ready(1)
        awaitUntil("listening started") { h.speech.starts == 1 }
        h.speech.listener!!.onPartial("hello")
        awaitUntil("partial") { fieldText() == "hello" }
        compose.onNodeWithTag("words").performSemanticsAction(SemanticsActions.RequestFocus)
        awaitUntil("stop asked") { h.speech.stops == 1 }
    }

    @Test fun listeningNeverStartsWhenVoiceIsOffAndTheScreenSaysSo() {
        h.voiceOn = false
        launch(send("content://shot/1"))
        ready(1)
        assertEquals(0, h.speech.starts)
        assertEquals(0, h.cues.started)
        assertFalse(has("mic"))
        compose.onNodeWithTag("voiceHint").assertTextEquals("Voice is off. Type your note instead.")
    }

    @Test fun saveTappedWhileListeningStopsAndSavesTheWordsHeard() {
        h.voiceOn = true
        launch(send("content://shot/1"))
        ready(1)
        awaitUntil("listening started") { h.speech.starts == 1 }
        h.speech.listener!!.onPartial("buy oat milk")
        awaitUntil("partial") { fieldText() == "buy oat milk" }
        tapSave()
        awaitUntil("stop asked") { h.speech.stops == 1 }
        assertTrue(h.saveCalls.isEmpty())
        h.speech.listener!!.onFinal("buy oat milk")
        savedAndClosed()
        assertEquals("buy oat milk", captures().single().text)
    }

    @Test fun cancelWhileListeningStopsTheEngine() {
        h.voiceOn = true
        launch(send("content://shot/1"))
        ready(1)
        awaitUntil("listening started") { h.speech.starts == 1 }
        compose.onNodeWithTag("cancel").performScrollTo().performClick()
        savedAndClosed()
        assertTrue(h.speech.cancels >= 1)
        assertTrue(captures().isEmpty())
    }

    private fun fieldText(): String = compose.onNodeWithTag("words").fetchSemanticsNode()
        .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.orEmpty()

    @Test fun nothingIsKeptWhenTheActivityIsLeftWithoutSaving() {
        launch(send("content://shot/1", "content://shot/2"))
        ready(2)
        scenario!!.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, h.intake.discarded.size)
        assertTrue(h.store.list().isEmpty())
        assertNull(captures().firstOrNull())
    }
}
