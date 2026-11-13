package app.stash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D80, card 16d: the debug-only screenshot switch is physically absent from the release source set. Scans every text
 * file under `app/src/main` and `app/src/release` (if it exists) for each name the switch's code uses. The card 14
 * release checklist repeats this on the built release APK (see EXPERIMENT_LOG, "Card 16d").
 */
class ScreenshotToggleAbsentFromReleaseTest {
    /** Names that exist only under `app/src/debug`. */
    private val names = listOf("ScreenshotToggle", "DebugSecureInit", "DebugSettingsActivity", "debug_allow_screenshots", "debug_screen_flag", "allowScreenshots")
    private val textExtensions = setOf("kt", "java", "xml", "kts", "gradle", "txt", "json", "properties", "md", "pro")

    private fun hits(root: File): List<String> {
        if (!root.exists()) return emptyList()
        val out = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension.lowercase() in textExtensions }.forEach { f ->
            val text = f.readText().lowercase()
            for (n in names) if (text.contains(n.lowercase())) out += "${f.path}: $n"
        }
        return out
    }

    @Test fun theMainAndReleaseSourceSetsNameNoneOfIt() {
        val found = hits(File("src/main")) + hits(File("src/release"))
        assertTrue("the debug-only screenshot switch leaked into the release source set: $found", found.isEmpty())
    }

    @Test fun theScanCanSeeFilesAndTheDebugSourceSetReallyHasTheSwitch() {
        // Without these two the test above could pass by scanning nothing.
        assertTrue(File("src/main/java/app/stash/ui/MainActivity.kt").exists())
        assertTrue(hits(File("src/debug")).isNotEmpty())
        assertTrue(File("src/debug/java/app/stash/debug/ScreenshotToggle.kt").readText().contains("clearFlags"))
    }

    @Test fun mainSetsTheFlagUnconditionallyInTheOneReadingScreen() {
        val main = File("src/main/java/app/stash/ui/MainActivity.kt").readText()
        val onCreate = main.substringAfter("override fun onCreate").substringBefore("setContent")
        assertTrue(onCreate.contains("ScreenSecurity.secure(window)"))
        // no condition on it: the call is a statement of its own, not inside an if
        assertTrue(Regex("""^\s*ScreenSecurity\.secure\(window\)\s*$""", RegexOption.MULTILINE).containsMatchIn(onCreate))
        assertTrue(File("src/main/java/app/stash/ui/ScreenSecurity.kt").readText().contains("FLAG_SECURE"))
    }
}
