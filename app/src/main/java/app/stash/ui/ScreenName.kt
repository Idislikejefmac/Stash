package app.stash.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun debuggable(): Boolean = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * A readable title in the top centre of each screen, below the camera cutout, so the owner and the developer can say
 * which screen they are looking at ("Notes", "Camera"). Debug builds only: it draws nothing when the app is not debuggable.
 */
@Composable
fun ScreenName(name: String) {
    if (!debuggable()) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
        Text("[$name]", color = Color(0xFFB0B0B0), fontSize = 14.sp)
    }
}

/** The same title as one line in a column (the main screens), so it never covers anything. */
@Composable
fun ScreenNameLine(name: String) {
    if (!debuggable()) return
    Text("[$name]", color = Color(0xFF8A8A8A), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
}
