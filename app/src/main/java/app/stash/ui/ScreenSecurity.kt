package app.stash.ui

import android.view.Window
import android.view.WindowManager

/**
 * The one place a reading screen blocks screenshots and keeps its picture out of the recent-apps view
 * (docs/cards/16d item 1, D80). Set unconditionally, in every build. Card 02 later owns the call.
 */
object ScreenSecurity {
    fun secure(window: Window) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun isSecure(window: Window) = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
