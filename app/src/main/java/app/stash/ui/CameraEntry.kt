package app.stash.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.stash.capture.CameraActivity
import app.stash.domain.Capability
import app.stash.domain.CameraRules

/** Does this phone have a camera? (The manifest does not require one.) */
fun phoneHasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * Whether the camera buttons are drawn at all (docs/cards/16e): the CAMERA switch is on and pictures are on and the
 * phone has a camera. The permission is not part of it: an owner who has not allowed it yet is asked on first use.
 */
fun cameraVisible(rows: List<CapabilityRow>, hasCamera: Boolean): Boolean = CameraRules.buttonVisible(
    cameraSwitchOn = rows.firstOrNull { it.capability == Capability.CAMERA }?.switchOn == true,
    picturesOn = rows.firstOrNull { it.capability == Capability.PICTURES }?.on == true,
    hasCamera = hasCamera,
)

/** "Take a picture": not drawn at all when [visible] is false. */
@Composable
fun CameraNoteButton(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (!visible) return
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp).testTag("takePicture")) { Text(CameraRules.TAKE_A_PICTURE) }
}

/** The main screen's pair of buttons: Add a note, and Take a picture (a new note) when the camera is on. */
@Composable
fun AddNoteRow(vm: MainViewModel, addNote: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val rows by vm.capabilityRows.collectAsState()
    val ctx = LocalContext.current
    val visible = cameraVisible(rows, phoneHasCamera(ctx))
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        addNote(Modifier.weight(1f))
        CameraNoteButton(visible, Modifier.weight(1f)) { ctx.startActivity(CameraActivity.intent(ctx)) }
    }
}
