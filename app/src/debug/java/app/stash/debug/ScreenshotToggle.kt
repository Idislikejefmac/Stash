package app.stash.debug

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.Window
import app.stash.ui.ScreenSecurity

/**
 * DEBUG BUILDS ONLY (docs/cards/16d, D80). A developer's switch that lets `tools/peek.py screen` and screenshots see
 * the main screen. The release source set must not contain it: the flag is set unconditionally there, and a test
 * (ScreenshotToggleAbsentFromReleaseTest) fails if the main or release source set names anything in this file.
 * It lives here, in the debug source set, and reaches the app only through [DebugSecureInit] in the debug manifest.
 */
object ScreenshotToggle {
    private const val PREFS = "debug_screen_flag"
    private const val KEY = "debug_allow_screenshots"

    fun allowed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, allow: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, allow).apply()
        current?.let { applyTo(it.window, allow) }
    }

    /** The reading screen that is open now, so a change takes effect without leaving it. */
    @Volatile private var current: Activity? = null

    /** On: the flag is cleared. Off: the release behaviour, the flag set. */
    fun applyTo(window: Window, allow: Boolean) {
        if (allow) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else ScreenSecurity.secure(window)
    }

    internal fun isReadingScreen(a: Activity) = a.javaClass.name == "app.stash.ui.MainActivity"

    internal fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (isReadingScreen(activity)) applyTo(activity.window, allowed(app))
            }
            override fun onActivityResumed(activity: Activity) {
                if (isReadingScreen(activity)) { current = activity; applyTo(activity.window, allowed(app)) }
            }
            override fun onActivityPaused(activity: Activity) { if (current === activity) current = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/** Starts [ScreenshotToggle] with the process, without any line in the main source set. Declared in the debug manifest only. */
class DebugSecureInit : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { ScreenshotToggle.install(it) }
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
