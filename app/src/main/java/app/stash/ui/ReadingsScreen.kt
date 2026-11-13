package app.stash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stash.data.MetricSummary
import app.stash.domain.ReadingAnswers
import app.stash.domain.ReadingFact
import app.stash.domain.ReadingRules

/** The "Readings" tab (docs/cards/07 pass 1): each thing he has measured, and a plain list of its values. */
@Composable
fun ReadingsScreen(vm: MainViewModel) {
    val summaries by vm.readingSummaries.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var values by remember { mutableStateOf<List<ReadingFact>>(emptyList()) }
    LaunchedEffect(selected, summaries) { values = selected?.let { vm.metricValues(it) }.orEmpty() }
    ReadingsContent(
        summaries = summaries, now = System.currentTimeMillis(),
        selected = summaries.firstOrNull { it.metric.metricId == selected }, values = values,
        onOpen = { selected = it }, onBack = { selected = null },
    )
}

/** The readings tab drawn from what it is given, so a test can seed it. */
@Composable
fun ReadingsContent(
    summaries: List<MetricSummary>,
    now: Long,
    selected: MetricSummary?,
    values: List<ReadingFact>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected != null) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp).testTag("readingsBack")) { Text("Back to readings") }
            Text(title(selected), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("metricTitle"))
            for (v in values) {
                Card(Modifier.fillMaxWidth().testTag("value-${v.readingId}")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(ReadingAnswers.display(v), style = MaterialTheme.typography.titleMedium)
                        Text(
                            ReadingAnswers.ago(v.measuredAt, now) + (v.place?.let { " at $it" }.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ReadingRules.flagText(v.attention)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("flag-${v.readingId}"))
                        }
                    }
                }
            }
            return@Column
        }
        if (summaries.isEmpty()) {
            Text(
                "No readings yet. Say something like: pool test, free chlorine 5, pH 7.2.",
                modifier = Modifier.testTag("readingsEmpty"),
            )
            return@Column
        }
        for (s in summaries) {
            val line = "Latest: ${ReadingAnswers.display(s.latest)}, ${ReadingAnswers.ago(s.latest.measuredAt, now)}"
            Card(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("metric-${s.metric.metricId}")
                    .semantics { contentDescription = "${title(s)}. $line. ${count(s.count)}" }
                    .clickable { onOpen(s.metric.metricId) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(title(s), style = MaterialTheme.typography.titleMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                    Text(count(s.count), style = MaterialTheme.typography.bodyMedium)
                    if (ReadingRules.flagText(s.latest.attention) != null) Text("Check the latest one.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun title(s: MetricSummary) = s.metric.subjectName?.let { "$it: ${s.metric.label}" } ?: s.metric.label
private fun count(n: Int) = if (n == 1) "1 value" else "$n values"
