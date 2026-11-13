package app.stash

import androidx.compose.ui.graphics.Color
import app.stash.ui.PrivateColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The Private label's colours: one place decides them for every screen. */
class PrivateLabelTest {
    private val onSurfaceDark = Color(0xFFE6E1E5)

    @Test fun offIsEightyPercentBlackInLight() {
        assertEquals(Color.Black.copy(alpha = 0.8f), PrivateColors.color(false, false, Color.Black))
    }

    @Test fun offIsEightyPercentOnSurfaceInDark() {
        assertEquals(onSurfaceDark.copy(alpha = 0.8f), PrivateColors.color(false, true, onSurfaceDark))
    }

    @Test fun onIsDarkRedInLightAndLighterRedInDark() {
        assertEquals(Color(0xFF8B0000), PrivateColors.color(true, false, Color.Black))
        assertEquals(PrivateColors.darkOn, PrivateColors.color(true, true, onSurfaceDark))
        assertNotEquals(PrivateColors.lightOn, PrivateColors.darkOn)
    }
}
