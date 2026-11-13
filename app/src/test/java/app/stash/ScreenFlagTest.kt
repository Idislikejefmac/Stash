package app.stash

import android.app.Activity
import android.view.WindowManager
import app.stash.debug.ScreenshotToggle
import app.stash.ui.ScreenSecurity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Card 16d item 1 and D80: FLAG_SECURE on the reading screen; the debug switch (debug source set) can clear it. */
@RunWith(RobolectricTestRunner::class)
class ScreenFlagTest {
    private fun window() = Robolectric.buildActivity(Activity::class.java).create().get().window

    @Test fun secureSetsTheFlagAndIsSecureSeesIt() {
        val w = window()
        assertFalse(ScreenSecurity.isSecure(w))
        ScreenSecurity.secure(w)
        assertTrue(w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun theDebugSwitchClearsAndRestoresTheFlag() {
        val w = window()
        ScreenSecurity.secure(w)
        ScreenshotToggle.applyTo(w, allow = true)
        assertFalse(ScreenSecurity.isSecure(w))
        ScreenshotToggle.applyTo(w, allow = false)
        assertTrue(ScreenSecurity.isSecure(w))
    }

    @Test fun theSwitchIsOffByDefaultAndRemembersItsState() {
        val ctx = TestSupport.context
        assertFalse(ScreenshotToggle.allowed(ctx))
        ScreenshotToggle.set(ctx, true)
        assertTrue(ScreenshotToggle.allowed(ctx))
        ScreenshotToggle.set(ctx, false)
        assertFalse(ScreenshotToggle.allowed(ctx))
    }
}
