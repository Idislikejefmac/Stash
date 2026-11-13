package app.stash.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.stash.domain.Notice
import app.stash.speech.ImportWords

/*
 * Card 26d items 10 and 13: Settings, "Voice notes" (add or remove the speech model pack, its licence line) and the
 * "Open source notices" row with each notice's full text. Plain words, 56 dp targets.
 */

object SpeechModelWords {
    const val NO_ENGINE = "This version of Stash has no speech engine. Voice notes use the phone's own speech recogniser."
    const val NO_MODEL = "Speech model: none. Voice notes use the phone's own speech recogniser."
    const val WHAT_IT_IS = "The speech model is one file you put on the phone once (about 141 MB). Stash never downloads it, " +
        "and it works with no network."
}

/** The "Voice notes" items of the Settings list. */
fun LazyListScope.speechModelItems(vm: MainViewModel, showNotices: () -> Unit) {
    item { Text("Voice notes", style = MaterialTheme.typography.titleMedium) }
    item { SpeechModelRow(vm) }
    item { VoiceNoteChoices(vm) }
    item {
        OutlinedButton(onClick = showNotices, modifier = Modifier.heightIn(min = 56.dp).testTag("open-source-notices")) {
            Text("Open source notices")
        }
    }
}

@Composable
private fun SpeechModelRow(vm: MainViewModel) {
    val state by vm.speechModel.collectAsState()
    LaunchedEffect(Unit) { vm.refreshSpeechModel() }
    var confirmRemove by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importSpeechModel(uri) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val p = state.installed
        when {
            !state.engineBuilt -> Text(SpeechModelWords.NO_ENGINE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("speech-model-line"))
            p != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ImportWords.installedLine(p), Modifier.weight(1f).testTag("speech-model-line"), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { confirmRemove = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Remove") }
                }
                Text(p.licenceLine, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("speech-model-licence"))
            }
            else -> {
                Text(SpeechModelWords.NO_MODEL, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("speech-model-line"))
                Text(SpeechModelWords.WHAT_IT_IS, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("Add the speech model") }
            }
        }
        state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("speech-model-message")) }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the speech model?") },
            text = { Text("Voice notes will use the phone's own speech recogniser until you add it again.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeSpeechModel() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep it") } },
        )
    }
}

/**
 * Card 26g: when a recorded note ends by itself (26a's setting, drawn here for the first time), and, only if recording
 * was found not to work on the locked phone, what to do about that.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VoiceNoteChoices(vm: MainViewModel) {
    val pause by vm.endPause.collectAsState()
    val blocked by vm.lockedMicBlocked.collectAsState()
    val choice by vm.lockedChoice.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(VoiceNoteWords.PAUSE_TITLE, style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.stash.domain.EndPause.entries.forEach { p ->
                androidx.compose.material3.FilterChip(
                    selected = pause == p, onClick = { vm.setEndPause(p) }, label = { Text(p.label) },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("end-pause-" + p.name),
                )
            }
        }
        Text(VoiceNoteWords.PAUSE_HELP, style = MaterialTheme.typography.bodySmall)
        if (blocked) {
            Text(VoiceNoteWords.LOCKED_BLOCKED, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("locked-mic-line"))
            OutlinedButton(onClick = { vm.tryLockedRecordingAgain() }, modifier = Modifier.heightIn(min = 56.dp).testTag("locked-mic-retry")) {
                Text(VoiceNoteWords.LOCKED_RETRY)
            }
            Text(VoiceNoteWords.LOCKED_TITLE, style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.stash.capture.LockedChoice.entries.forEach { c ->
                    androidx.compose.material3.FilterChip(
                        selected = choice == c, onClick = { vm.setLockedChoice(c) }, label = { Text(c.label) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }
    }
}

object VoiceNoteWords {
    const val PAUSE_TITLE = "End a voice note after a pause of"
    const val PAUSE_HELP = "For notes that keep a recording. A note also ends when you tap Done, and at 3 minutes."
    const val LOCKED_BLOCKED = "Recording didn't work while the phone was locked, so locked notes are written down as you speak and no sound is kept."
    const val LOCKED_RETRY = "Try recording on the lock screen again"
    const val LOCKED_TITLE = "When the phone is locked"
}

/** The list of notices, and one notice's full text. Back returns to the list, then to Settings. */
@Composable
fun OpenSourceNoticesScreen(notices: List<Notice>, loadText: suspend (Notice) -> String, onClose: () -> Unit) {
    var open by remember { mutableStateOf<Notice?>(null) }
    BackHandler { if (open != null) open = null else onClose() }
    val shown = open
    if (shown == null) {
        LazyColumn(Modifier.padding(top = 24.dp).testTag("notices-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Open source notices", style = MaterialTheme.typography.titleLarge) }
            item { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text("Back to Settings") } }
            if (notices.isEmpty()) item { Text("This version of Stash contains no code that needs a notice here.") }
            items(notices, key = { it.name }) { n ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = n }.padding(vertical = 8.dp).testTag("notice-" + n.name),
                ) {
                    Text(n.name, style = MaterialTheme.typography.titleSmall)
                    Text(n.what, style = MaterialTheme.typography.bodySmall)
                    Text(n.licence, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    } else {
        var text by remember(shown) { mutableStateOf<String?>(null) }
        LaunchedEffect(shown) { text = loadText(shown) }
        // Split into paragraphs so a long text (ONNX Runtime's notices are 325 KB) is drawn a piece at a time.
        val paragraphs = remember(text) { text?.split(Regex("\n\\s*\n"))?.filter { it.isNotBlank() } ?: emptyList() }
        LazyColumn(Modifier.padding(top = 24.dp).testTag("notice-text"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(shown.name, style = MaterialTheme.typography.titleLarge) }
            item { Text(shown.licence, style = MaterialTheme.typography.bodyMedium) }
            item { TextButton(onClick = { open = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Back to the list") } }
            if (text == null) item { Text("Loading...") }
            items(paragraphs) { Text(it.trimEnd(), style = MaterialTheme.typography.bodySmall) }
            item { TextButton(onClick = { open = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Back to the list") } }
        }
    }
}
