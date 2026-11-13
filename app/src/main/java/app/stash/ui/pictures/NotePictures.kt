package app.stash.ui.pictures

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stash.data.AttachmentInfo
import app.stash.domain.PicturePrivacy
import app.stash.domain.PictureSizing
import app.stash.domain.PictureWords
import app.stash.ui.privateTextStyle

/** One square thumbnail, at least 72 dp (the minimum touch target is 56). */
private val THUMB_SIZE = 88.dp

/**
 * The pictures of one note: **the only composable that draws stored pictures** (docs/cards/16d item 3). A strip under
 * the note's text, one square thumbnail per picture in `position` order, scrolling sideways, loaded lazily. A note
 * with no pictures shows nothing. A Private note shows one row until [onShowPrivate] is called and [shown] turns true
 * (the interim of card 02: "tap to show"); until then no picture of it is opened or decoded at all.
 *
 * [updatedAt] is only a reason to look again after an add or a remove (both stamp the note).
 */
@Composable
fun NotePictures(
    captureId: String,
    updatedAt: Long,
    sensitive: Boolean,
    shown: Boolean,
    host: PictureHost,
    onShowPrivate: () -> Unit,
    onOpen: (AttachmentInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pictures by remember(captureId) { mutableStateOf<List<AttachmentInfo>>(emptyList()) }
    LaunchedEffect(captureId, updatedAt) { pictures = host.pictures(captureId) }
    if (pictures.isEmpty()) return
    if (!PicturePrivacy.mayDraw(sensitive, shown)) {
        TextButton(onClick = onShowPrivate, modifier = modifier.heightIn(min = 56.dp).testTag("private-row")) {
            Text(PictureWords.privateRow(pictures.size), style = privateTextStyle(true, MaterialTheme.typography.labelLarge))
        }
        return
    }
    Column(modifier) {
        LazyRow(Modifier.testTag("strip"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item(key = "count") { Text(PictureWords.count(pictures.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("count")) }
            itemsIndexed(pictures, key = { _, p -> p.id }) { i, p ->
                PictureThumb(p, i, pictures.size, host.loader) { onOpen(p) }
            }
        }
        // Card 16f item 6: the words the phone read, collapsed. Drawn only here, inside the private wrapper above.
        val words = pictures.mapNotNull { it.ocrText?.takeIf { t -> t.isNotBlank() } }
        if (words.isNotEmpty()) {
            var open by remember(captureId) { mutableStateOf(false) }
            TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 56.dp).testTag("words-toggle")) {
                Text(PictureWords.READ_FROM + if (open) " (hide)" else " (show)", style = privateTextStyle(sensitive, MaterialTheme.typography.labelLarge))
            }
            if (open) Text(words.joinToString("\n\n"), Modifier.testTag("words"), style = privateTextStyle(sensitive, MaterialTheme.typography.bodySmall))
        }
    }
}

@Composable
private fun PictureThumb(info: AttachmentInfo, index: Int, count: Int, loader: PictureLoader, onOpen: () -> Unit) {
    var result by remember(info.id) { mutableStateOf<PictureResult?>(null) }
    LaunchedEffect(info.id) { result = loader.load(info, PictureSizing.THUMBNAIL_LONG_EDGE) }
    val description = PictureWords.position(index, count)
    Box(
        Modifier
            .size(THUMB_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFBDBDBD))
            .clickable(onClickLabel = "Open picture", onClick = onOpen)
            .semantics { contentDescription = description; role = Role.Button }
            .testTag("thumb-$index"),
        contentAlignment = Alignment.Center,
    ) {
        when (val r = result) {
            is PictureResult.Ready -> Image(r.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(THUMB_SIZE))
            PictureResult.Missing -> Text(PictureWords.MISSING, Modifier.padding(4.dp).testTag("picture-missing"), color = Color.Black, style = MaterialTheme.typography.labelSmall)
            null -> {}
        }
    }
}
