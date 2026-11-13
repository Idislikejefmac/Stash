package app.stash.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Debug builds only: the developer's settings. Today one switch (card 16d). Open with `am start -n app.stash/app.stash.debug.DebugSettingsActivity`. */
class DebugSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var allow by remember { mutableStateOf(ScreenshotToggle.allowed(this)) }
                Column(Modifier.padding(24.dp)) {
                    Text("Debug settings", style = MaterialTheme.typography.headlineSmall)
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow screenshots of the main screen", Modifier.weight(1f))
                        Switch(checked = allow, onCheckedChange = { allow = it; ScreenshotToggle.set(this@DebugSettingsActivity, it) })
                    }
                    Text(
                        "Off (the default) is what a release build always does: the main screen cannot be captured. " +
                            "Turn it on only to use tools/peek.py screen or take a screenshot while developing.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
