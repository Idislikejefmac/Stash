package app.stash

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stash.data.CaptureSource
import app.stash.data.ExtractionRunner
import app.stash.data.MetricSummary
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.domain.ReadingFact
import app.stash.model.rules.RulesExtractor
import app.stash.pipeline.ReadingHook
import app.stash.ui.ReadingsContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/** The "Readings" tab drawn from a seeded database (docs/cards/07 pass 1). */
@RunWith(RobolectricTestRunner::class)
class ReadingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: StashDatabase
    private lateinit var repo: Repository
    private val DAY = 86_400_000L
    private val clock = FakeClock(1_794_398_400_000L)
    private val now = clock.now()

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun say(text: String, daysAgo: Int) {
        val c = repo.captures.insertCapture(text, text, CaptureSource.VOICE, createdAt = now - daysAgo * DAY)
        val runner = ExtractionRunner(db, repo.records, clock) { ZoneOffset.UTC }
        val out = runner.runLocal(c.id, RulesExtractor()) as ExtractionRunner.Outcome.Stored
        ReadingHook({ Store.Ready(db) }, clock).afterProjection(c.id, out.recordIds)
    }

    private fun seed() = runBlocking {
        say("Pool test: free chlorine 3, pH 7.4, alkalinity 80", 14)
        say("Pool test: free chlorine 4, pH 7.6, alkalinity 90", 9)
        say("Pool test: free chlorine 5, pH 7.2, alkalinity 70", 6)
        say("Pool test: free chlorine 5, pH 72, alkalinity 70", 2)
        say("The Tide is on sale for \$24.99 at Costco", 21)
    }

    private fun show() {
        var summaries: List<MetricSummary> = emptyList()
        runBlocking { summaries = repo.readings.summaries(includeSensitive = false) }
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf<String?>(null) }
                var values by remember { mutableStateOf<List<ReadingFact>>(emptyList()) }
                ReadingsContent(
                    summaries = summaries, now = now,
                    selected = summaries.firstOrNull { it.metric.metricId == open }, values = values,
                    onOpen = { id -> open = id; values = runBlocking { repo.readings.all(id, includeSensitive = false) } },
                    onBack = { open = null },
                )
            }
        }
    }

    @Test fun theListShowsEachMetricWithItsLatestValue() {
        seed()
        show()
        compose.onNodeWithText("Pool: pH").assertExists()
        compose.onNodeWithText("Latest: 72, two days ago").assertExists()
        compose.onNodeWithText("Pool: free chlorine").assertExists()
        compose.onNodeWithText("Pool: alkalinity").assertExists()
        compose.onNodeWithText("Tide: price").assertExists()
        compose.onNodeWithText("Latest: \$24.99, three weeks ago").assertExists()
        assertEquals(3, compose.onAllNodesWithText("4 values", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun openingAMetricListsEveryValueWithItsDateAndFlag() {
        seed()
        show()
        val ph = runBlocking { repo.readings.summaries(false).single { it.metric.label == "pH" } }
        compose.onNodeWithTag("metric-${ph.metric.metricId}").performScrollTo().performClick()
        compose.onNodeWithTag("metricTitle").assertIsDisplayed()
        val values = runBlocking { repo.readings.all(ph.metric.metricId, false) }
        assertEquals(listOf("72", "7.2", "7.6", "7.4"), values.map { it.valueText })
        for (v in values) compose.onNodeWithTag("value-${v.readingId}").assertIsDisplayed()
        // The 72 is flagged, and the flag never says whether anything is good.
        compose.onNodeWithTag("flag-${values.first().readingId}").assertIsDisplayed()
        compose.onNodeWithText("Very different from the usual (7.4). Check it was heard right.").assertIsDisplayed()
        compose.onNodeWithTag("readingsBack").performClick()
        compose.onNodeWithText("Pool: pH").assertIsDisplayed()
    }

    @Test fun aPriceValueShowsItsPlace() {
        seed()
        show()
        val price = runBlocking { repo.readings.summaries(false).single { it.metric.label == "price" } }
        compose.onNodeWithTag("metric-${price.metric.metricId}").performScrollTo().performClick()
        compose.onNodeWithText("three weeks ago at Costco").assertIsDisplayed()
    }

    @Test fun anEmptyDatabaseSaysHowToAddOne() {
        show()
        compose.onNodeWithTag("readingsEmpty").assertIsDisplayed()
    }

    @Test fun privateReadingsAreNotDrawn() {
        runBlocking {
            val c = repo.captures.insertCapture("Pool test: free chlorine 5, pH 7.2", "Pool test: free chlorine 5, pH 7.2", CaptureSource.VOICE, sensitive = true, createdAt = now - DAY)
            ExtractionRunner(db, repo.records, clock) { ZoneOffset.UTC }.runLocal(c.id, RulesExtractor())
        }
        show()
        compose.onNodeWithTag("readingsEmpty").assertIsDisplayed()
    }
}
