package app.stash.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.stash.capture.CameraActivity
import app.stash.capture.CaptureActivity
import app.stash.domain.CameraRules
import app.stash.data.CaptureWithRecords
import app.stash.data.ExtractState
import app.stash.data.PhotoHint
import app.stash.domain.PictureOffer
import app.stash.domain.RecordKind
import app.stash.domain.Capability
import app.stash.data.NoteViews
import app.stash.domain.Age
import app.stash.domain.Searcher
import app.stash.domain.CloseSetting
import app.stash.domain.TaskLabels
import app.stash.domain.PictureWords
import app.stash.ui.pictures.NotePictures
import app.stash.ui.pictures.PictureViewer

/** The main app. It is NOT shown over the lock screen: the phone must be unlocked to open it. */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onStop() {
        super.onStop()
        // Private pictures are hidden again, the viewer closes and every decoded picture is forgotten (card 16d).
        vm.pictures.onStop()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshCapabilities() // permissions are changed outside the app
        vm.refreshToday()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleReminderIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every reading screen blocks screenshots and recent-apps thumbnails, in every build (card 16d, D80).
        ScreenSecurity.secure(window)
        vm.handleReminderIntent(intent)
        setContent {
            MaterialTheme {
                val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    vm.setNotification(vm.settings.notificationEnabled) // post it now that it may be allowed
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    askPerms.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
                App(vm)
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    var tab by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val sections by vm.taskSections.collectAsState()
    val openRequest by vm.openRequest.collectAsState()
    var showReview by remember { mutableStateOf(false) }
    val viewing by vm.pictures.viewer.collectAsState()
    // A tap on a reminder: "To handle", or the weekly review (docs/cards/06 item 11).
    androidx.compose.runtime.LaunchedEffect(openRequest) {
        when (openRequest) {
            "to_handle" -> { tab = 2; showReview = false }
            "review" -> showReview = true
        }
        if (openRequest != null) vm.openRequest.value = null
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                listOf("Ask", "Notes", "To handle", "Readings", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, icon = {},
                        label = {
                            if (i == 2 && sections.badge > 0) {
                                BadgedBox(
                                    badge = { Badge { Text("${sections.badge}") } },
                                    modifier = Modifier.semantics { contentDescription = "To handle, ${sections.badge} due or soon" },
                                ) { Text(label) }
                            } else Text(label)
                        },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            val reviewNow by vm.review.collectAsState()
            if (showReview) {
                WeeklyReviewContent(
                    reviewNow,
                    onTarget = { t -> tab = if (t == app.stash.domain.ReviewTarget.TO_HANDLE) 2 else 1; showReview = false },
                    onClose = { showReview = false },
                )
                return@Column
            }
            ScreenNameLine(listOf("Ask", "Notes", "To handle", "Readings", "Settings").getOrElse(tab) { "Main" })
            when (tab) {
                0 -> AskScreen(vm)
                1 -> NotesScreen(vm)
                2 -> TasksScreen(vm, snackbar)
                3 -> ReadingsScreen(vm)
                else -> SettingsScreen(vm)
            }
        }

    }
    // The full-screen picture viewer, over the list (card 16d item 5).
    viewing?.let { v -> PictureViewer(v.captureId, v.pictureId, vm.pictureHost, onClose = { vm.pictures.closeViewer() }) }
    }
}

@Composable
private fun AddNoteButton(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Button(
        onClick = { ctx.startActivity(Intent(ctx, CaptureActivity::class.java)) },
        modifier = modifier.heightIn(min = 56.dp),
    ) { Text("Add a note") }
}

@Composable
private fun AskScreen(vm: MainViewModel) {
    val st by vm.ask.collectAsState()
    val notes by vm.notes.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startVoice() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ask Stash", style = MaterialTheme.typography.headlineSmall)
        AddNoteRow(vm, { AddNoteButton(it) })
        OutlinedTextField(
            value = st.question,
            onValueChange = vm::setQuestion,
            label = { Text("Where did I put my...?") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submitQuestion() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.submitQuestion() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Ask") }
            OutlinedButton(
                onClick = {
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.startVoice()
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text(if (st.listening) "Stop listening" else "Speak") }
        }
        st.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        st.result?.let { r ->
            AskAnswer(r, st.why, st.wrongList, onOpenNote = { detail = it }, onWhy = vm::toggleWhy, onWrong = vm::markWrong)
        }
    }
    // A source tapped: the note it came from (card 08 F17), the same view as the "To handle" tab's.
    detail?.let { id ->
        val n = notes.firstOrNull { it.capture.id == id }
        if (n == null) detail = null
        else CaptureDetailDialog(n, onReadAgain = { vm.readAgain(n) }, onClose = { detail = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesScreen(vm: MainViewModel) {
    val notes by vm.notes.collectAsState()
    val shownPrivate by vm.pictures.shownPrivate.collectAsState()
    val adding by vm.adding.collectAsState()
    val pictureMessage by vm.pictureMessage.collectAsState()
    // The note a picture is being picked for (an id only).
    var pickFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // The note whose "Add a picture" offers the camera as well (card 16e): an id only.
    var chooseFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val capRows by vm.capabilityRows.collectAsState()
    val cameraOn = cameraVisible(capRows, phoneHasCamera(androidx.compose.ui.platform.LocalContext.current))
    val notesCtx = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        pickFor?.let { vm.addPictures(it, uris) }
        pickFor = null
    }
    // The same choice for the row's "Add a picture" and the quiet offer's: camera or gallery, or the gallery alone.
    val addPictureTo: (String) -> Unit = { id ->
        if (cameraOn) {
            chooseFor = id // card 16e: the camera is the second choice
        } else {
            pickFor = id
            picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    var query by remember { mutableStateOf("") }
    val speechReady by vm.speechModelReady.collectAsState()
    var correcting by remember { mutableStateOf<CaptureWithRecords?>(null) }
    var unmarking by remember { mutableStateOf<CaptureWithRecords?>(null) }
    var typed by remember { mutableStateOf("") }
    val typedStatus by vm.typedStatus.collectAsState()
    val now = System.currentTimeMillis()
    val shown = if (query.isBlank()) notes else {
        val kws = Searcher.keywords(query).ifEmpty { Searcher.tokens(query) }
        notes.filter { n ->
            val parts = n.records.filter { it.record.deletedAt == null }.map { NoteViews.view(n.capture, it, emptyMap()) }
            val hay = Searcher.tokens("${n.capture.text} " + parts.joinToString(" ") { "${it.item} ${it.place} ${it.person}" }).map(Searcher::stem)
            kws.all { k -> hay.any { it.startsWith(Searcher.stem(k)) } }
        }
    }
    Column(Modifier.padding(top = 24.dp)) {
        AddNoteRow(vm, { AddNoteButton(it) }, Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = typed, onValueChange = { typed = it; vm.clearTypedStatus() }, label = { Text("Type a note") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.saveTyped(typed); typed = "" }),
        )
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.saveTyped(typed); typed = "" }, enabled = typed.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("Save note") }
            typedStatus?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Search notes") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        pictureMessage?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
        if (shown.isEmpty()) Text(if (notes.isEmpty()) "No notes yet. Tap Add a note, or use the notification or the Add a note tile." else "No notes match.", Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(shown, key = { it.capture.id }) { n ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (n.capture.text.isNotBlank()) Text(n.capture.text, style = MaterialTheme.typography.titleMedium)
                        NotePictures(
                            captureId = n.capture.id, updatedAt = n.capture.updatedAt, sensitive = n.capture.sensitive,
                            shown = n.capture.id in shownPrivate, host = vm.pictureHost,
                            onShowPrivate = { vm.pictures.showPrivate(n.capture.id) },
                            onOpen = { vm.pictures.open(n.capture.id, it.id) },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        adding[n.capture.id]?.let { (k, total) -> Text(PictureWords.adding(k, total), style = MaterialTheme.typography.bodyMedium) }
                        if (n.capture.sensitive) PrivateLabel(true, text = "Private: stays on this phone", base = MaterialTheme.typography.labelMedium)
                        Text(
                            Age.describe(n.capture.createdAt, now) + if (n.capture.lat != null) "  |  location saved" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // The records the app made from this note, beneath it.
                        n.records.filter { it.record.deletedAt == null }.sortedBy { it.record.seq }.forEach { r ->
                            val v = NoteViews.view(n.capture, r, emptyMap())
                            val bits = listOfNotNull(
                                v.kind.name.lowercase().replace('_', ' '),
                                v.item?.let { "item: $it" }, v.place?.let { "where: $it" },
                                v.person?.let { "who: $it" }, v.eventDate?.let { "when: $it" },
                                if (r.record.supersedesRecordId != null) "replaces an older note" else null,
                                r.task?.let { "task: " + TaskLabels.stateLabel(it.state, it.snoozedUntil) },
                            )
                            Text(bits.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        }
                        // Card 26f: a recorded note waiting for its words says so, in plain words (no notification).
                        // Card 26g item 13: with no words yet it is never a blank row: "Voice note, 0:12 · Writing it out…".
                        val hasWords = n.capture.text.isNotBlank()
                        val waitingForWords = n.capture.transcribeState != null && !hasWords
                        val recordingMs by androidx.compose.runtime.produceState<Long?>(null, n.capture.id, waitingForWords) {
                            value = if (waitingForWords) vm.recordingMs(n.capture.id) else null
                        }
                        val transcribeLine = app.stash.pipeline.TranscribeNotice.voiceNoteRow(n.capture.transcribeState, hasWords, speechReady, recordingMs)
                        if (transcribeLine != null) Text(transcribeLine, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("voice-note-row"))
                        else filingNote(n)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (n.capture.rawText != n.capture.text && !n.capture.sensitive) Text("Heard: ${n.capture.rawText}", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PrivateLabel(n.capture.sensitive, Modifier.weight(1f))
                            Switch(
                                checked = n.capture.sensitive,
                                onCheckedChange = { on -> if (on) vm.setPrivate(n, true) else unmarking = n },
                            )
                        }
                        // Card 16g: while the offer stands, one quiet line. No badge, no count, no sound.
                        if (n.capture.photoHint == PhotoHint.PENDING) {
                            val offered by androidx.compose.runtime.produceState(false, n.capture.id, n.capture.photoHint, n.capture.updatedAt, n.capture.sensitive) {
                                value = vm.offerShown(n.capture.id)
                            }
                            if (offered) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("pictureOffer")) {
                                    TextButton(
                                        onClick = { vm.offerAccepted(n.capture.id); addPictureTo(n.capture.id) },
                                        modifier = Modifier.heightIn(min = 56.dp),
                                    ) { Text(PictureOffer.ADD_LABEL) }
                                    Text("·", style = MaterialTheme.typography.bodyMedium)
                                    TextButton(
                                        onClick = { vm.dismissOffer(n.capture.id) },
                                        modifier = Modifier.heightIn(min = 56.dp),
                                    ) { Text(PictureOffer.NO_THANKS_LABEL) }
                                }
                            }
                        }
                        FlowRow {
                            TextButton(
                                onClick = { addPictureTo(n.capture.id) },
                                enabled = adding[n.capture.id] == null,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Add a picture") }
                            TextButton(onClick = { vm.readAgain(n) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read it again") }
                            TextButton(onClick = { correcting = n }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Correct a word") }
                            TextButton(onClick = { vm.deleteNote(n) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    chooseFor?.let { id ->
        AlertDialog(
            onDismissRequest = { chooseFor = null },
            title = { Text("Add a picture") },
            confirmButton = {
                TextButton(
                    onClick = { chooseFor = null; notesCtx.startActivity(CameraActivity.intent(notesCtx, id)) },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(CameraRules.TAKE_A_PICTURE) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        chooseFor = null
                        pickFor = id
                        picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("Choose from gallery") }
            },
        )
    }
    unmarking?.let { n ->
        AlertDialog(
            onDismissRequest = { unmarking = null },
            title = { Text("Stop keeping this note private?") },
            text = { Text("It may then be sent to a hosted model if you have switched one on, and it will show in answers.") },
            confirmButton = { TextButton(onClick = { vm.setPrivate(n, false); unmarking = null }) { Text("Stop keeping it private") } },
            dismissButton = { TextButton(onClick = { unmarking = null }) { Text("Keep it private") } },
        )
    }
    correcting?.let { n -> CorrectDialog(n, onDismiss = { correcting = null }) { heard, meant -> vm.correctWord(n, heard, meant); correcting = null } }
}

/**
 * How a note was filed, so the phone never looks broken (docs/cards/13 C2): a small mark for the offline rules, a
 * plain line for a note that is not sorted, and where a waiting note stands.
 */
private fun filingNote(n: CaptureWithRecords): String? {
    val live = n.records.filter { it.record.deletedAt == null }
    val fromRules = live.any { it.extraction?.extractor?.contains("rules") == true }
    val fromModel = live.any { it.extraction?.extractor?.let { x -> !x.contains("rules") } == true }
    val unsorted = live.isEmpty() || live.all { it.record.kind == RecordKind.OTHER }
    return when {
        n.capture.extractState == ExtractState.PENDING && live.isEmpty() -> "Saved. Sorting it..."
        n.capture.extractState == ExtractState.GAVE_UP && live.isEmpty() -> "Saved. I could not sort it."
        unsorted && n.capture.sensitive -> "Saved. Not sorted: Private notes are never sent to a model."
        unsorted && !fromModel -> "Saved. Not sorted: needs a model"
        n.capture.extractState == ExtractState.RETRY -> "Filed by simple rules. Will try a model again later."
        fromRules && !fromModel -> "Filed by simple rules"
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorrectDialog(note: CaptureWithRecords, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val words = remember(note.capture.id) {
        Regex("[\\p{L}\\p{N}'][\\p{L}\\p{N}']*").findAll(note.capture.text).map { it.value }.distinct().toList()
    }
    var picked by remember { mutableStateOf<String?>(null) }
    var meant by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which word is wrong?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    words.forEach { w ->
                        FilterChip(selected = picked == w, onClick = { picked = w; meant = w }, label = { Text(w) })
                    }
                }
                if (picked != null) {
                    OutlinedTextField(value = meant, onValueChange = { meant = it }, label = { Text("It should say") }, singleLine = true)
                    Text("Stash will fix this note and change \"${picked}\" to this word from now on.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked != null && meant.isNotBlank() && meant != picked, onClick = { onSave(picked!!, meant.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val vocab by vm.vocab.collectAsState()
    val corrections by vm.corrections.collectAsState()
    val hosted by vm.hostedEnabled.collectAsState()
    val notif by vm.notificationEnabled.collectAsState()
    val hasKey by vm.hasKey.collectAsState()
    val privateWords by vm.alwaysPrivate.collectAsState()
    val capRows by vm.capabilityRows.collectAsState()
    val closeSetting by vm.closeSetting.collectAsState()
    val pictureSize by vm.pictureSize.collectAsState()
    val nudge by vm.nudge.collectAsState()
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshCapabilities() }
    var newPrivate by remember { mutableStateOf("") }
    var newWord by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") }
    var meant by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showNotices by remember { mutableStateOf(false) }
    if (showNotices) {
        OpenSourceNoticesScreen(vm.notices, { vm.noticeText(it) }, onClose = { showNotices = false })
        return
    }

    LazyColumn(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Prototype: notes are not encrypted yet; don't store anything sensitive.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        item { Text("What Stash may use", style = MaterialTheme.typography.titleMedium) }
        items(capRows, key = { "cap" + it.capability.name }) { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.capability.plainName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = row.switchOn, onCheckedChange = { vm.setCapability(row.capability, it) })
                }
                if (row.capability.permissions.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (row.permissionGranted) "Permission: allowed" else "Permission: not allowed yet",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        if (!row.permissionGranted) {
                            TextButton(onClick = { askPermission.launch(row.capability.permissions.first()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
                        }
                    }
                }
                Text(
                    (if (row.on) "On. " else "Off. ") + "Without it: " + row.capability.withoutIt,
                    style = MaterialTheme.typography.bodySmall,
                )
                // Card 16f item 13: fixed disclosure, until card 23 builds the explanation card.
                if (row.capability == Capability.PICTURE_TEXT) {
                    Text(app.stash.domain.PictureTextRules.DISCLOSURE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("picture-text-disclosure"))
                }
            }
        }
        val remindersRow = capRows.firstOrNull { it.capability == Capability.REMINDERS }
        reminderSettingsItems(vm, nudge, remindersRow?.permissionGranted ?: false, remindersRow?.switchOn ?: false)
        item { Text("Picture size", style = MaterialTheme.typography.titleMedium) }
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.stash.domain.PictureSize.entries.forEach { p ->
                    FilterChip(
                        selected = pictureSize == p, onClick = { vm.setPictureSize(p) }, label = { Text(pictureSizeName(p)) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }
        item { Text(pictureSizeLine(pictureSize), style = MaterialTheme.typography.bodyMedium) }
        item { Text("Applies to pictures you add from now on.", style = MaterialTheme.typography.bodySmall) }
        item {
            val stats by vm.pictureStats.collectAsState()
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPictureStats() }
            Text(PictureWords.storageLine(stats.count, stats.bytes), style = MaterialTheme.typography.bodyMedium)
        }
        // Card 26d: the speech model pack, and the Open source notices row.
        speechModelItems(vm, showNotices = { showNotices = true })
        item { Text("When I say I did something", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "If you say you finished something on your list, Stash can close that task for you.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloseSetting.entries.forEach { c ->
                    FilterChip(
                        selected = closeSetting == c, onClick = { vm.setCloseSetting(c) }, label = { Text(c.label) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }
        item { Text("Always private words", style = MaterialTheme.typography.titleMedium) }
        item { Text("A note that says one of these is kept Private: never sent anywhere.", style = MaterialTheme.typography.bodySmall) }
        items(privateWords, key = { "pw$it" }) { w ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w, Modifier.weight(1f))
                TextButton(onClick = { vm.removePrivateWord(w) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newPrivate, { newPrivate = it }, label = { Text("New private word") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addPrivateWord(newPrivate); newPrivate = "" }, enabled = newPrivate.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("Add") }
            }
        }
        item { Text("Words I use", style = MaterialTheme.typography.titleMedium) }
        item { Text("Names and words the speech engine should get right.", style = MaterialTheme.typography.bodySmall) }
        items(vocab, key = { "w$it" }) { w ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w, Modifier.weight(1f))
                TextButton(onClick = { vm.removeWord(w) }) { Text("Remove") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newWord, { newWord = it }, label = { Text("New word") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addWord(newWord); newWord = "" }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Add") }
            }
        }
        item { Text("Corrections (heard, then meant)", style = MaterialTheme.typography.titleMedium) }
        items(corrections, key = { "c${it.heard}" }) { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${c.heard}  ->  ${c.meant}", Modifier.weight(1f))
                TextButton(onClick = { vm.removeCorrection(c.heard) }) { Text("Remove") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(heard, { heard = it }, label = { Text("Heard") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(meant, { meant = it }, label = { Text("Meant") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        item { Button(onClick = { vm.addCorrection(heard, meant); heard = ""; meant = "" }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Add correction") } }
        item { Text("Hosted model (optional)", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "With an Anthropic API key, note text (and, for questions, the text of matching notes) can be sent to a hosted model " +
                    "to tidy up the fields and phrase answers. Never audio, location or dates. Without a key everything works on the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Use the hosted model", Modifier.weight(1f))
                Switch(checked = hosted, onCheckedChange = vm::setHosted)
            }
        }
        item {
            Text(if (hasKey) "A key is saved (kept encrypted by the phone's Keystore)." else "No key saved.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                key, { key = it }, label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveKey(key); key = "" }, enabled = key.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("Save key") }
                OutlinedButton(onClick = { vm.saveKey("") }, enabled = hasKey, modifier = Modifier.heightIn(min = 56.dp)) { Text("Remove key") }
            }
        }
        item { Text("Export", style = MaterialTheme.typography.titleMedium) }
        item {
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Stash notes")
                        putExtra(Intent.EXTRA_TEXT, vm.exportText())
                    }
                    ctx.startActivity(Intent.createChooser(send, "Export notes as text"))
                },
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("Export notes as text") }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

private fun pictureSizeName(p: app.stash.domain.PictureSize) = when (p) {
    app.stash.domain.PictureSize.SMALL -> "Small"
    app.stash.domain.PictureSize.STANDARD -> "Standard"
    app.stash.domain.PictureSize.LARGE -> "Large"
}

private fun pictureSizeLine(p: app.stash.domain.PictureSize) = when (p) {
    app.stash.domain.PictureSize.SMALL -> "Small: about 1000 dots wide. Fine for most photos."
    app.stash.domain.PictureSize.STANDARD -> "Standard: small print stays readable."
    app.stash.domain.PictureSize.LARGE -> "Large: for documents and fine detail. About twice the storage."
}
