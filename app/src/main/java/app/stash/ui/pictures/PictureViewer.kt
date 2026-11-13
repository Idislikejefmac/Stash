package app.stash.ui.pictures

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.stash.data.AttachmentInfo
import app.stash.domain.PictureRemoval
import app.stash.domain.PictureSizing
import app.stash.domain.PictureWords
import app.stash.domain.RemoveKind
import app.stash.domain.ZoomState
import kotlinx.coroutines.launch

/**
 * The full-screen viewer (docs/cards/16d item 5): a composable over the list, not a new activity. Pinch to zoom, drag
 * to pan, swipe sideways through the note's pictures, "2 of 5" at the top, Close, and Remove picture (one question,
 * then gone for good). Pictures are decoded in memory by the host's loader; only ids are handed around.
 */
@Composable
fun PictureViewer(captureId: String, startPictureId: String, host: PictureHost, onClose: () -> Unit) {
    var pictures by remember(captureId) { mutableStateOf<List<AttachmentInfo>?>(null) }
    LaunchedEffect(captureId) { pictures = host.pictures(captureId) }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("viewer")) {
        val list = pictures ?: return@Box
        if (list.isEmpty()) {
            LaunchedEffect(Unit) { onClose() }
            return@Box
        }
        ViewerBody(captureId, list, list.indexOfFirst { it.id == startPictureId }.coerceAtLeast(0), host, onClose) { pictures = it }
    }
}

@Composable
private fun ViewerBody(
    captureId: String,
    list: List<AttachmentInfo>,
    start: Int,
    host: PictureHost,
    onClose: () -> Unit,
    onList: (List<AttachmentInfo>) -> Unit,
) {
    val pager = rememberPagerState(initialPage = start) { list.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<RemoveKind?>(null) }
    val page = pager.currentPage.coerceIn(0, list.size - 1)
    val current = list[page]

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(PictureWords.viewerTitle(page, list.size), Modifier.weight(1f).testTag("viewer-title"), color = Color.White)
            Button(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp).testTag("viewer-close")) { Text("Close") }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = !zoomed, key = { list[it].id }) { i ->
            ZoomablePicture(list[i], host.loader, active = i == pager.currentPage) { zoomed = it }
        }
        current.ocrText?.takeIf { it.isNotBlank() }?.let {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(PictureWords.READ_FROM, color = Color.White, style = MaterialTheme.typography.labelMedium)
                Text(it, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("viewer-words"))
            }
        }
        OutlinedButton(
            onClick = { scope.launch { asking = host.removeKind(captureId) } },
            modifier = Modifier.padding(16.dp).heightIn(min = 56.dp).testTag("viewer-remove"),
        ) { Text("Remove picture", color = Color.White) }
    }

    asking?.let { kind ->
        AlertDialog(
            onDismissRequest = { asking = null },
            // A dialog is its own window: it must not be a way round FLAG_SECURE.
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(PictureRemoval.question(kind)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        scope.launch {
                            val outcome = host.remove(captureId, current.id, kind)
                            val left = host.pictures(captureId)
                            val next = PictureRemoval.nextIndex(page, left.size)
                            if (outcome == RemoveOutcome.NOTE_REMOVED || next == null) {
                                onClose()
                            } else {
                                onList(left)
                                pager.scrollToPage(next)
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("remove-confirm"),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }, modifier = Modifier.heightIn(min = 56.dp).testTag("remove-keep")) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun ZoomablePicture(info: AttachmentInfo, loader: PictureLoader, active: Boolean, onZoomed: (Boolean) -> Unit) {
    var result by remember(info.id) { mutableStateOf<PictureResult?>(null) }
    LaunchedEffect(info.id) { result = loader.load(info, PictureSizing.READING_LONG_EDGE) }
    var zoom by remember(info.id) { mutableStateOf(ZoomState()) }
    var width by remember { mutableStateOf(1f) }
    var height by remember { mutableStateOf(1f) }
    LaunchedEffect(zoom.zoomed, active) { if (active) onZoomed(zoom.zoomed) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.toFloat(); height = it.height.toFloat() }
            .pointerInput(info.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger on an unzoomed picture is left alone, so the pager can swipe.
                        if (zoom.zoomed || event.changes.size > 1) {
                            val pan = event.calculatePan()
                            zoom = zoom.transformed(event.calculateZoom(), pan.x, pan.y, width, height)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val r = result) {
            is PictureResult.Ready -> Image(
                r.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.x; translationY = zoom.y }
                    .semantics { contentDescription = "Picture" },
            )
            PictureResult.Missing -> Text(PictureWords.MISSING, color = Color.White, modifier = Modifier.testTag("viewer-missing"))
            null -> {}
        }
    }
}
