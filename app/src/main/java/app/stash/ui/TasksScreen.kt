package app.stash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stash.data.CaptureWithRecords
import app.stash.data.TaskEntity
import app.stash.data.TaskItem
import app.stash.domain.SnoozePreset
import app.stash.domain.TaskLabels
import app.stash.domain.TaskSections
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a row can do. The screen owns none of the logic: each is a call into the view model. */
interface TaskActions {
    fun done(item: TaskItem)
    fun later(item: TaskItem, preset: SnoozePreset)
    fun dontNeed(item: TaskItem)
    fun notDone(item: TaskItem)
    fun bringBack(item: TaskItem)
    fun open(item: TaskItem)
}

/** The "To handle" tab (docs/cards/05 section E). */
@Composable
fun TasksScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val sections by vm.taskSections.collectAsState()
    val notes by vm.notes.collectAsState()
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<String?>(null) }

    // Each action shows an Undo for five seconds (the snackbar is held open, then dismissed).
    fun act(message: String, change: suspend () -> TaskEntity?) {
        scope.launch {
            val before = change() ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val timer = launch { delay(5_000); snackbar.currentSnackbarData?.dismiss() }
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
            timer.cancel()
            if (result == SnackbarResult.ActionPerformed) vm.undoTask(before)
        }
    }

    val actions = object : TaskActions {
        override fun done(item: TaskItem) = act("Marked done") { vm.markDone(item.record.id) }
        override fun later(item: TaskItem, preset: SnoozePreset) = act("Put off") { vm.snoozeTask(item.record.id, preset) }
        override fun dontNeed(item: TaskItem) = act("Stopped asking about it") { vm.dropTask(item.record.id) }
        override fun notDone(item: TaskItem) = act("Back on your list") { vm.reopenTask(item.record.id) }
        override fun bringBack(item: TaskItem) = act("Back on your list") { vm.bringBackTask(item.record.id) }
        override fun open(item: TaskItem) { detail = item.capture.id }
    }
    TasksContent(sections, System.currentTimeMillis(), actions)

    detail?.let { id ->
        val n = notes.firstOrNull { it.capture.id == id }
        if (n == null) detail = null
        else CaptureDetailDialog(n, onReadAgain = { vm.readAgain(n) }, onClose = { detail = null })
    }
}

/** Stateless, so a test can draw it from a seeded database. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksContent(sections: TaskSections, now: Long, actions: TaskActions) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp).testTag("tasks"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("To handle", style = MaterialTheme.typography.headlineSmall)
        if (sections.isEmpty) {
            Text("Nothing to handle right now. When you say something you have to do, it shows up here.")
        }
        if (sections.due.isNotEmpty()) {
            Header("Due")
            sections.due.forEach { OpenRow(it, now, actions) }
        }
        if (sections.soon.isNotEmpty()) {
            Header("Soon")
            sections.soon.forEach { OpenRow(it, now, actions) }
        }
        if (sections.atPlace.isNotEmpty()) {
            Header("At a place")
            sections.atPlace.forEach { (place, rows) ->
                Text(place, style = MaterialTheme.typography.titleSmall)
                rows.forEach { OpenRow(it, now, actions) }
            }
        }
        if (sections.whenever.isNotEmpty()) {
            Header("Whenever")
            sections.whenever.forEach { OpenRow(it, now, actions) }
        }
        if (sections.later.isNotEmpty()) {
            Header("Later")
            sections.later.forEach { LaterRow(it, now, actions) }
        }
        if (sections.doneRecently.isNotEmpty()) {
            Header("Done recently")
            sections.doneRecently.forEach { ClosedRow(it, now, actions, "Not done") { actions.notDone(it) } }
        }
        if (sections.stoppedAsking.isNotEmpty()) {
            Header("Stopped asking")
            sections.stoppedAsking.forEach { ClosedRow(it, now, actions, "Bring back") { actions.bringBack(it) } }
        }
        Text("Private notes are kept off this list.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun Header(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("section-$title").semantics { contentDescription = "$title section" })
}

@Composable
private fun RowCard(item: TaskItem, now: Long, actions: TaskActions, extra: String?, buttons: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { actions.open(item) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.record.title, style = MaterialTheme.typography.titleMedium)
            Text(TaskLabels.saidLine(item, now), style = MaterialTheme.typography.bodySmall)
            TaskLabels.dueWords(item)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            extra?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            buttons()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenRow(item: TaskItem, now: Long, actions: TaskActions) {
    RowCard(item, now, actions, null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { actions.done(item) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Done") }
            LaterButton(item, actions)
            TextButton(onClick = { actions.dontNeed(item) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Don't need") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaterRow(item: TaskItem, now: Long, actions: TaskActions) {
    RowCard(item, now, actions, item.task.snoozedUntil?.let { "Back on your list on $it" }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { actions.done(item) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Done") }
            TextButton(onClick = { actions.notDone(item) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Put back now") }
            TextButton(onClick = { actions.dontNeed(item) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Don't need") }
        }
    }
}

@Composable
private fun ClosedRow(item: TaskItem, now: Long, actions: TaskActions, label: String, onClick: () -> Unit) {
    RowCard(item, now, actions, TaskLabels.howClosed(item)) {
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 56.dp)) { Text(label) }
    }
}

@Composable
private fun LaterButton(item: TaskItem, actions: TaskActions) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Later") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SnoozePreset.entries.forEach { p ->
            DropdownMenuItem(text = { Text(p.label) }, onClick = { open = false; actions.later(item, p) }, modifier = Modifier.heightIn(min = 56.dp))
        }
    }
}

/** The capture a task came from: the whole text, the other things made from it, and "Read it again". */
@Composable
internal fun CaptureDetailDialog(n: CaptureWithRecords, onReadAgain: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What you said") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(n.capture.text, style = MaterialTheme.typography.titleMedium)
                val parts = n.records.filter { it.record.deletedAt == null }.sortedBy { it.record.seq }
                if (parts.isNotEmpty()) Text("Made from it:", style = MaterialTheme.typography.labelLarge)
                parts.forEach { r ->
                    val state = r.task?.let { " (" + TaskLabels.stateLabel(it.state, it.snoozedUntil) + ")" }.orEmpty()
                    Text(r.record.kind.name.lowercase().replace('_', ' ') + ": " + r.record.title + state, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") } },
        dismissButton = { TextButton(onClick = { onReadAgain(); onClose() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Read it again") } },
    )
}
