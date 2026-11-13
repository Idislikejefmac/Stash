package app.stash

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stash.data.Container
import app.stash.domain.OpenSourceNotices
import app.stash.domain.Pcm
import app.stash.speech.Availability
import app.stash.speech.ModelLocator
import app.stash.speech.NoTranscriber
import app.stash.speech.TranscribeResult
import app.stash.speech.Transcriber
import app.stash.ui.OpenSourceNoticesScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Card 26d: the Container's choice of transcriber, the bundled notices, and the notices screen (Robolectric). */
@RunWith(RobolectricTestRunner::class)
class SpeechContainerTest {
    @get:Rule val compose = createComposeRule()

    private fun container() = Container(TestSupport.context as android.app.Application)

    @Test fun withNoPackTheContainersTranscriberSaysNoModelInEitherBuild() = runBlocking {
        val c = container()
        assertEquals(Availability.NoModel, c.transcriber.availability())
        assertEquals(null, c.transcriber.id)
        val r = c.transcriber.transcribe(Pcm(ShortArray(1600))) as TranscribeResult.Failed
        // With the engine built there is no pack; without it there is no engine. Either way nothing is retried.
        assertTrue(r.code.name, r.code.name in setOf("MODEL_MISSING", "NO_ENGINE"))
        assertEquals(false, r.retryable)
        if (!c.speechEngineBuilt) assertSame(NoTranscriber, c.transcriber)
        assertEquals(File(TestSupport.context.filesDir, ModelLocator.DIR), c.modelPacks.root)
    }

    @Test fun aFakeTranscriberCanBeChosen() = runBlocking {
        val c = container()
        val fake = object : Transcriber {
            override val id = "fake"
            override fun availability() = Availability.Ready
            override suspend fun transcribe(audio: Pcm) = TranscribeResult.Ok("words", 1, 1, null)
            override fun release() {}
        }
        val before = c.transcriber
        c.transcriber = fake
        assertSame(fake, c.transcriber)
        assertEquals(TranscribeResult.Ok("words", 1, 1, null), c.transcriber.transcribe(Pcm(ShortArray(10))))
        c.transcriber = before
        assertSame(before, c.transcriber)
    }

    @Test fun theNoticesAreInTheApksAssets() {
        val assets = TestSupport.context.assets
        for (n in OpenSourceNotices.forBuild(true)) for (p in n.assetPaths) {
            assertTrue(p, assets.open(p).use { it.readBytes().isNotEmpty() })
        }
    }

    @Test fun theNoticesScreenListsThreeEntriesAndShowsEachFullText() {
        val notices = OpenSourceNotices.forBuild(true)
        val texts = notices.associateWith { n -> n.assetPaths.joinToString("\n\n") { TestSupport.context.assets.open(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } } }
        var closed = false
        compose.setContent { OpenSourceNoticesScreen(notices, { texts.getValue(it) }, onClose = { closed = true }) }
        for (n in notices) compose.onNodeWithText(n.name).assertIsDisplayed()
        compose.onNodeWithText("Moonshine speech models (English)").performClick()
        // The licence line and the text itself both carry the copyright line.
        assertTrue(compose.onAllNodesWithText("Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)", substring = true).fetchSemanticsNodes().size >= 2)
        compose.onNodeWithText("The code in this repo", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Back to the list").onFirst().performClick()
        compose.onNodeWithText("ONNX Runtime").performClick()
        assertTrue(compose.onAllNodesWithText("Copyright (c) Microsoft Corporation", substring = true).fetchSemanticsNodes().size >= 2)
        compose.onAllNodesWithText("Back to the list").onFirst().performClick()
        compose.onNodeWithText("Back to Settings").performClick()
        assertTrue(closed)
    }
}
