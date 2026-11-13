package app.stash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The one look of every "Private" label, so no screen can drift.
 * Off: the normal text colour at 80% (black at 80% in the light theme). On: bold dark red.
 */
object PrivateColors {
    val lightOff = Color.Black.copy(alpha = 0.8f)
    val lightOn = Color(0xFF8B0000)
    /** A lighter red that keeps contrast on a dark surface. */
    val darkOn = Color(0xFFFF8A80)

    fun color(enabled: Boolean, dark: Boolean, onSurface: Color): Color = when {
        enabled -> if (dark) darkOn else lightOn
        dark -> onSurface.copy(alpha = 0.8f)
        else -> lightOff
    }
}

@Composable
fun privateTextStyle(enabled: Boolean, base: TextStyle = MaterialTheme.typography.bodyLarge): TextStyle {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = PrivateColors.color(enabled, dark, MaterialTheme.colorScheme.onSurface)
    return if (enabled) base.copy(color = color, fontWeight = FontWeight.Bold) else base.copy(color = color)
}

/** [text] is "Private" unless a screen needs its own words; the style is the same either way. */
@Composable
fun PrivateLabel(enabled: Boolean, modifier: Modifier = Modifier, text: String = "Private", base: TextStyle = MaterialTheme.typography.bodyLarge) {
    Text(text, modifier, style = privateTextStyle(enabled, base))
}
