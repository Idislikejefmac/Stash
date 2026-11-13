package app.stash

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stash.ask.AnswerWriter
import app.stash.ask.AskResult
import app.stash.ask.SourceNote
import app.stash.data.WrittenBy
import app.stash.ui.AskAnswer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Card 08 F17: the answer, its sources, the private count, "Why this answer?" and "That's not it", drawn on the JVM. */
@RunWith(RobolectricTestRunner::class)
class AskScreenTest {
    @get:Rule val compose = createComposeRule()

    private val source = SourceNote("r-1", "c-1", "Reading glasses in the nightstand drawer", "I'm putting my reading glasses in the nightstand drawer", "3 weeks ago")

    @Test fun anAnswerShowsItsSourcesThePrivateLineAndTheButtons() {
        var opened: String? = null
        var why = 0
        var wrong = 0
        val r = AskResult("t-1", "Nightstand drawer. You said so 3 weeks ago.", AskResult.Kind.ANSWER, listOf(source), WrittenBy.CODE, privateCount = 2)
        compose.setContent {
            MaterialTheme { AskAnswer(r, why = listOf("I took this as a 'where is' question."), wrongList = null, onOpenNote = { opened = it }, onWhy = { why++ }, onWrong = { wrong++ }) }
        }
        compose.onNodeWithText("Nightstand drawer. You said so 3 weeks ago.").assertIsDisplayed()
        compose.onNodeWithText("From these notes:").assertIsDisplayed()
        compose.onNodeWithText("3 weeks ago").assertIsDisplayed()
        compose.onNodeWithText("2 private notes also match. Private notes are not shown here yet.").assertIsDisplayed()
        compose.onNodeWithText("I took this as a 'where is' question.").assertIsDisplayed()
        compose.onNodeWithTag("source-r-1").performClick()
        assertEquals("c-1", opened)
        compose.onNodeWithTag("askWhy").performClick()
        compose.onNodeWithTag("askWrong").performClick()
        assertEquals(1, why); assertEquals(1, wrong)
        compose.onNodeWithText("Written by the model").assertDoesNotExist()
    }

    @Test fun aListHasItsHeadingAndTheModelMarkShowsWhenTheModelWroteIt() {
        val list = AskResult("t-2", AnswerWriter.LIST_NO_MODEL, AskResult.Kind.LIST, listOf(source), WrittenBy.CODE, 0)
        compose.setContent { MaterialTheme { AskAnswer(list, null, listOf(source), {}, {}, {}) } }
        compose.onNodeWithText(AnswerWriter.LIST_NO_MODEL).assertIsDisplayed()
        compose.onNodeWithText("From these notes:").assertDoesNotExist()
        compose.onNodeWithTag("askPrivate").assertDoesNotExist()
        compose.onNodeWithTag("askWrong").assertDoesNotExist() // already marked
        compose.onNodeWithText(AnswerWriter.LIST_MARKED_WRONG).assertIsDisplayed()
    }

    @Test fun theModelMark() {
        val r = AskResult("t-3", "In the drawer.", AskResult.Kind.ANSWER, emptyList(), WrittenBy.MODEL, 0)
        compose.setContent { MaterialTheme { AskAnswer(r, null, null, {}, {}, {}) } }
        compose.onNodeWithText("Written by the model").assertIsDisplayed()
    }
}
