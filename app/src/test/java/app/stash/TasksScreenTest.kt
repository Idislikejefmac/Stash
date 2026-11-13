package app.stash

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stash.data.ClosedHow
import app.stash.data.DueKind
import app.stash.data.ExtractionEntity
import app.stash.data.ExtractionStatus
import app.stash.data.RecordBundle
import app.stash.data.RecordEntity
import app.stash.data.StashDatabase
import app.stash.data.TaskEntity
import app.stash.data.TaskItem
import app.stash.data.TaskItems
import app.stash.data.TaskState
import app.stash.domain.RecordKind
import app.stash.domain.SnoozePreset
import app.stash.domain.TaskSections
import app.stash.ui.TaskActions
import app.stash.ui.TasksContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/** The "To handle" screen drawn from a seeded database (docs/cards/05 acceptance). */
@RunWith(RobolectricTestRunner::class)
class TasksScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: StashDatabase
    private lateinit var repo: app.stash.data.Repository
    private val clock = FakeClock(1_794_398_400_000L)
    private val today = LocalDate.ofEpochDay(Math.floorDiv(clock.now(), 86_400_000L))
    private val tapped = mutableListOf<String>()

    private val actions = object : TaskActions {
        override fun done(item: TaskItem) { tapped += "done:" + item.record.title }
        override fun later(item: TaskItem, preset: SnoozePreset) { tapped += "later:" + preset.name }
        override fun dontNeed(item: TaskItem) { tapped += "drop:" + item.record.title }
        override fun notDone(item: TaskItem) { tapped += "notdone:" + item.record.title }
        override fun bringBack(item: TaskItem) { tapped += "back:" + item.record.title }
        override fun open(item: TaskItem) { tapped += "open:" + item.record.title }
    }

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun seed(title: String, task: TaskEntity, sensitive: Boolean = false, quote: String = title) {
        val cap = repo.captures.insertCapture(quote, quote, sensitive = sensitive, createdAt = clock.now() - 2 * 86_400_000L)
        val ex = ExtractionEntity("x-${cap.id}", cap.id, clock.now(), "rules-2", 1, "h", "{}", ExtractionStatus.CURRENT)
        val rec = RecordEntity(
            id = "r-${cap.id}", captureId = cap.id, extractionId = ex.id, seq = 0, kind = RecordKind.TASK, title = title, quote = quote,
            createdAt = clock.now(), updatedAt = clock.now(),
        )
        repo.records.replaceRecordsFor(cap.id, ex, listOf(RecordBundle(rec, task = task.copy(recordId = rec.id))))
    }

    private fun base() = TaskEntity("x", lastActiveAt = clock.now(), createdAt = clock.now(), updatedAt = clock.now())

    private fun show() {
        val sections = runBlocking {
            TaskSections.build(TaskItems.from(db.captures().live(), includeSensitive = false), today, clock.now())
        }
        compose.setContent { MaterialTheme { TasksContent(sections, clock.now(), actions) } }
    }

    private fun assertShown(vararg tags: String) = tags.forEach { compose.onNodeWithTag("section-$it").assertExists() }
    private fun assertHidden(vararg tags: String) = tags.forEach { compose.onNodeWithTag("section-$it").assertDoesNotExist() }

    @Test fun showsTheSectionsForASeededDatabaseAndHidesTheEmptyOnes() {
        runBlocking {
            seed("Clean out the back of the car", base().copy(dueKind = DueKind.SOON, dueText = "soon"), quote = "I have to clean out the back of the car soon")
            seed("Pay the plumber", base().copy(dueKind = DueKind.BY, dueTo = today.plusDays(2).toString(), dueText = "by Friday"))
            seed("Buy milk", base().copy(atPlaceText = "Costco"))
            seed("Sort the garage", base())
            seed("Wash the dog", base().copy(state = TaskState.SNOOZED, snoozedUntil = today.plusDays(5).toString()))
            seed("Call mum", base().copy(state = TaskState.DONE, closedHow = ClosedHow.TAP, closedAt = clock.now()))
        }
        show()
        assertShown("Due", "Soon", "At a place", "Whenever", "Later", "Done recently")
        assertHidden("Stopped asking")
        compose.onNodeWithText("Clean out the back of the car").assertExists()
        compose.onNodeWithText("You said: \"I have to clean out the back of the car soon\", 2 days ago").assertExists()
        compose.onNodeWithText("by Friday").assertExists()
        compose.onNodeWithText("Costco").assertExists()
        compose.onNodeWithText("Back on your list on ${today.plusDays(5)}").assertExists()
        compose.onNodeWithText("You marked it done").assertExists()
        compose.onNodeWithText("Not done").assertExists()
    }

    @Test fun anEmptyDatabaseShowsNoSectionsAndAFriendlyLine() {
        show()
        assertHidden("Due", "Soon", "At a place", "Whenever", "Later", "Done recently", "Stopped asking")
        compose.onNodeWithText("Nothing to handle right now. When you say something you have to do, it shows up here.").assertExists()
    }

    @Test fun aPrivateTaskIsNotOnTheScreenAndNeitherIsItsText() {
        runBlocking {
            seed("Wash the secret dog", base().copy(dueKind = DueKind.SOON), sensitive = true)
        }
        show()
        assertHidden("Soon", "Due", "Whenever")
        compose.onNodeWithText("Wash the secret dog").assertDoesNotExist()
        compose.onNodeWithText("Private notes are kept off this list.").assertExists()
    }

    @Test fun theButtonsCallTheActions() {
        runBlocking { seed("Sort the garage", base()) }
        show()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Don't need").performClick()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("This weekend").performClick()
        compose.onNodeWithText("Sort the garage").performClick()
        assertEquals(listOf("done:Sort the garage", "drop:Sort the garage", "later:THIS_WEEKEND", "open:Sort the garage"), tapped)
    }

    @Test fun stoppedAskingHasBringBack() {
        runBlocking { seed("Fix the gate", base().copy(state = TaskState.EXPIRED, closedHow = ClosedHow.EXPIRED, closedAt = clock.now())) }
        show()
        assertShown("Stopped asking")
        compose.onNodeWithText("Bring back").performClick()
        assertEquals(listOf("back:Fix the gate"), tapped)
    }
}
