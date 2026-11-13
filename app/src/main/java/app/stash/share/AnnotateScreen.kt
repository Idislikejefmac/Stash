package app.stash.share

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stash.domain.CameraRules
import app.stash.domain.ShareRules
import app.stash.ui.PrivateLabel

/** One picture in the strip. [id] is the attachment id; the bitmap is the in-memory thumbnail. */
class AnnotateThumb(val id: String, val bitmap: Bitmap)

/** Everything the annotate screen draws. A plain value, so card 16e can fill it for the camera as well. */
class AnnotateUi(
    val thumbs: List<AnnotateThumb> = emptyList(),
    /** "Getting 3 of 12 ready", or null. */
    val progress: String? = null,
    /** "1 picture could not be added", or null. */
    val notAdded: String? = null,
    /** Shown once under the strip. */
    val galleryNote: String? = null,
    /** Card 16f: "Read from the picture: ...". Empty until then. */
    val draftLine: String? = null,
    /** A line above everything, e.g. that pictures are switched off. */
    val notice: String? = null,
    val text: String = "",
    val listening: Boolean = false,
    val micAvailable: Boolean = false,
    /** What to say when listening is not available or did not hear anything. */
    val voiceHint: String? = null,
    val galleryVisible: Boolean = false,
    /** Card 16e: "Camera" beside "Gallery" (only on the camera's own screen). */
    val cameraVisible: Boolean = false,
    val markPrivate: Boolean = false,
    val saveEnabled: Boolean = false,
    val saveLabel: String = "Save",
    val cancelEnabled: Boolean = true,
    val message: String? = null,
    val saved: Boolean = false,
    val enlarged: Bitmap? = null,
)

interface AnnotateActions {
    fun onTextChange(text: String)
    fun onFieldFocused()
    fun onMic()
    fun onGallery()
    /** Card 16e: back to the camera for another picture. */
    fun onCamera() {}
    fun onPrivate()
    fun onSave()
    fun onCancel()
    fun onRemove(id: String)
    fun onOpen(id: String)
    fun onCloseEnlarged()
}

/**
 * The "Anything to add about this?" screen (docs/cards/16c B). Stateless: it draws [ui] and reports taps to [actions].
 * It shows only what is being added now, never a stored note.
 */
@Composable
fun AnnotateScreen(ui: AnnotateUi, actions: AnnotateActions) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(ShareRules.TITLE, style = MaterialTheme.typography.headlineSmall)
            ui.notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("notice")) }

            if (ui.thumbs.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("strip")) {
                    items(ui.thumbs, key = { it.id }) { t -> Thumb(t, actions) }
                }
            }
            ui.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("progress")) }
            ui.notAdded?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("notAdded")) }
            ui.galleryNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("galleryNote")) }
            ui.draftLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("draft")) }

            OutlinedTextField(
                value = ui.text,
                onValueChange = actions::onTextChange,
                label = { Text("Your words") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("words").onFocusChanged { if (it.isFocused) actions.onFieldFocused() },
            )

            if (ui.micAvailable) {
                OutlinedButton(
                    onClick = actions::onMic,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("mic"),
                ) { Text(if (ui.listening) "Listening. Tap to stop" else "Tap to speak") }
            }
            ui.voiceHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("voiceHint")) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.galleryVisible) {
                    OutlinedButton(onClick = actions::onGallery, modifier = Modifier.heightIn(min = 56.dp)) { Text("Gallery") }
                }
                if (ui.cameraVisible) {
                    OutlinedButton(onClick = actions::onCamera, modifier = Modifier.heightIn(min = 56.dp).testTag("camera")) { Text(CameraRules.ADD_ANOTHER_CAMERA) }
                }
                OutlinedButton(onClick = actions::onPrivate, modifier = Modifier.heightIn(min = 56.dp)) { PrivateLabel(ui.markPrivate) }
            }

            ui.message?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("message")) }
            if (ui.saved) Text("Saved", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("saved"))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = actions::onSave, enabled = ui.saveEnabled, modifier = Modifier.heightIn(min = 56.dp).testTag("save")) { Text(ui.saveLabel) }
                OutlinedButton(onClick = actions::onCancel, enabled = ui.cancelEnabled, modifier = Modifier.heightIn(min = 56.dp).testTag("cancel")) { Text("Cancel") }
            }
        }
        ui.enlarged?.let { Enlarged(it, actions) }
    }
}

@Composable
private fun Thumb(t: AnnotateThumb, actions: AnnotateActions) {
    Box(Modifier.size(width = 120.dp, height = 96.dp)) {
        if (!t.bitmap.isRecycled) {
            Image(
                bitmap = t.bitmap.asImageBitmap(),
                contentDescription = "Picture. Tap to see it larger",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 120.dp, height = 96.dp).clip(MaterialTheme.shapes.small).clickable { actions.onOpen(t.id) },
            )
        }
        // The remove mark: small to look at, 56 dp to hit.
        Box(
            Modifier.align(Alignment.TopEnd).size(56.dp).clickable { actions.onRemove(t.id) }
                .semantics { contentDescription = "Remove picture" }.testTag("remove"),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(Modifier.padding(4.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Text("x", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Enlarged(bitmap: Bitmap, actions: AnnotateActions) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable { actions.onCloseEnlarged() }.testTag("enlarged"),
        contentAlignment = Alignment.Center,
    ) {
        if (!bitmap.isRecycled) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Picture. Tap to close", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Shown when pictures were shared but the Pictures switch is off: one message and one Close button. */
@Composable
fun PicturesOffScreen(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ShareRules.PICTURES_OFF, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("notice"))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp).testTag("close")) { Text("Close") }
    }
}
