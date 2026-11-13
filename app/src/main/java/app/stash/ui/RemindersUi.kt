package app.stash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.stash.domain.AlarmKind
import app.stash.domain.NudgeSettings
import app.stash.domain.ReviewTarget
import app.stash.domain.WeeklyReview
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** The "Reminders" part of Settings (docs/cards/06 item 15). */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.reminderSettingsItems(
    vm: MainViewModel,
    n: NudgeSettings,
    permissionGranted: Boolean,
    switchOn: Boolean,
) {
    item { Text("Reminders", style = MaterialTheme.typography.titleMedium) }
    item {
        // Nothing fails silently: say what is off, and why.
        val why = when {
            !switchOn -> "Reminders are off. Things to do are only shown on the To handle screen."
            !permissionGranted -> "Reminders are off because Stash is not allowed to show notifications. Use Allow in the list above."
            else -> "Reminders are on. A lock screen shows only a count, never the words of a note. Private notes are never in a reminder, not even in the count."
        }
        Text(why, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("reminders-status"))
    }
    item {
        SwitchRow("Daily reminder", n.dailyOn && !n.dailyAutoPaused, { vm.setDailyOn(it) })
        if (n.dailyAutoPaused) {
            Text(
                "Paused because it was being ignored. Turn it on to start again.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("daily-paused"),
            )
        }
    }
    item { TimeRow("Daily reminder time", n.dailyTime) { vm.setNudge(n.copy(dailyTime = it)) } }
    item { TimeRow("Afternoon check time", n.afternoonTime) { vm.setNudge(n.copy(afternoonTime = it)) } }
    item { SwitchRow("Weekly review", n.weeklyOn, { vm.setNudge(n.copy(weeklyOn = it)) }) }
    item {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayOfWeek.entries.forEach { d ->
                FilterChip(
                    selected = n.weeklyDay == d, onClick = { vm.setNudge(n.copy(weeklyDay = d)) },
                    label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) }, modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
    item { TimeRow("Weekly review time", n.weeklyTime) { vm.setNudge(n.copy(weeklyTime = it)) } }
    item { TimeRow("Quiet hours start", n.quietStart) { vm.setNudge(n.copy(quietStart = it)) } }
    item { TimeRow("Quiet hours end", n.quietEnd) { vm.setNudge(n.copy(quietEnd = it)) } }
    item {
        SwitchRow("Show the first task on the lock screen", n.lockScreenFirstTask, { vm.setNudge(n.copy(lockScreenFirstTask = it)) })
        Text("Off by default. When on, anyone who sees your lock screen can read that task.", style = MaterialTheme.typography.bodySmall)
    }
    item {
        Text(
            "At most two reminders a day, none in quiet hours. If you ignore them, they come less often.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (vm.reminderTestAvailable) item { ReminderTestButtons(vm) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onPick: (LocalTime) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                android.app.TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
            },
            modifier = Modifier.heightIn(min = 56.dp),
        ) { Text(time.toString()) }
    }
}

/** Test builds only: ring a reminder in about a minute, so it can be tried without waiting for a morning. */
@Composable
private fun ReminderTestButtons(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Try a reminder (test build only)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Makes your oldest open to-do due today and rings about a minute from now. Lock the phone right after you tap.",
                style = MaterialTheme.typography.bodySmall,
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AlarmKind.DAILY to "Daily in 1 minute", AlarmKind.AFTERNOON to "Due today in 1 minute", AlarmKind.WEEKLY to "Weekly review in 1 minute").forEach { (k, label) ->
                    Button(onClick = { scope.launch { status = vm.testReminder(k) } }, modifier = Modifier.heightIn(min = 56.dp)) { Text(label) }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reminder-test-status")) }
        }
    }
}

/** The weekly review screen (docs/cards/06 item 14): the lines of the review, each tappable to the right list. */
@Composable
fun WeeklyReviewContent(review: WeeklyReview?, onTarget: (ReviewTarget) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp).testTag("weekly-review"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Your week", style = MaterialTheme.typography.headlineSmall)
        if (review == null) {
            Text("I can't read your notes right now. Try again in a moment.")
        } else if (review.isEmpty) {
            Text("Nothing to review this week.")
        } else {
            review.lines().forEach { line ->
                Card(
                    Modifier.fillMaxWidth().clickable { onTarget(line.target) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(line.text, Modifier.padding(16.dp).heightIn(min = 24.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (review.spokenDoneTitles.isNotEmpty()) {
                Text("Finished because you said so", style = MaterialTheme.typography.titleSmall)
                review.spokenDoneTitles.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
            }
            Text("Private notes are not part of this.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
    }
}
