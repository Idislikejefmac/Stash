package app.stash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.stash.ask.AnswerWriter
import app.stash.ask.AskResult
import app.stash.ask.SourceNote
import app.stash.data.WrittenBy

/** Card 08 item 15: the line under an answer when Private notes also match. A count only; they are not shown here. */
fun privateLine(n: Int): String? = when {
    n <= 0 -> null
    n == 1 -> "1 private note also matches. Private notes are not shown here yet."
    else -> "$n private notes also match. Private notes are not shown here yet."
}

/**
 * The answer to one question (docs/cards/08 F17): the answer, "From these notes:" with each source (title, quote, age;
 * a tap opens its note), the model mark when the model wrote it, the private count, **Why this answer?** and
 * **That's not it**. Stateless, so a test can draw it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAnswer(
    result: AskResult,
    why: List<String>?,
    wrongList: List<SourceNote>?,
    onOpenNote: (String) -> Unit,
    onWhy: () -> Unit,
    onWrong: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("askAnswer"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (result.kind) {
                AskResult.Kind.LIST -> Text(result.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("askHeading"))
                else -> Text(result.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("askText"))
            }
            if (result.writtenBy == WrittenBy.MODEL) Text("Written by the model", style = MaterialTheme.typography.labelMedium)
            if (result.sources.isNotEmpty()) {
                if (result.kind == AskResult.Kind.ANSWER) Text("From these notes:", style = MaterialTheme.typography.labelLarge)
                result.sources.forEach { SourceRow(it, onOpenNote) }
            }
            privateLine(result.privateCount)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("askPrivate")) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onWhy, modifier = Modifier.heightIn(min = 56.dp).testTag("askWhy")) { Text(if (why == null) "Why this answer?" else "Hide why") }
                if (wrongList == null && result.kind != AskResult.Kind.NOTHING) {
                    OutlinedButton(onClick = onWrong, modifier = Modifier.heightIn(min = 56.dp).testTag("askWrong")) { Text("That's not it") }
                }
            }
            why?.let { lines ->
                Column(Modifier.testTag("askWhyText"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            wrongList?.let { list ->
                Text("Thanks, I'll remember that one was wrong.", style = MaterialTheme.typography.bodyMedium)
                if (list.isEmpty()) Text(AnswerWriter.NOTHING, style = MaterialTheme.typography.bodyMedium)
                else {
                    Text(AnswerWriter.LIST_MARKED_WRONG, style = MaterialTheme.typography.labelLarge)
                    list.forEach { SourceRow(it, onOpenNote) }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(s: SourceNote, onOpenNote: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpenNote(s.captureId) }.padding(vertical = 4.dp).testTag("source-${s.id}"),
    ) {
        Text(s.title, style = MaterialTheme.typography.titleSmall)
        if (s.quote.isNotBlank() && s.quote.trim() != s.title.trim()) Text("\"${s.quote.trim()}\"", style = MaterialTheme.typography.bodyMedium)
        Text(s.age, style = MaterialTheme.typography.bodySmall)
    }
}
