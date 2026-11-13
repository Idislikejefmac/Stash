package app.stash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Card 26a: the debug-only recorder check screen is not in the main (release) source set, and the recorder code writes nothing. */
class RecorderCheckAbsentFromReleaseTest {
    @Test fun theMainSourceSetNamesNoRecorderCheckScreen() {
        val hits = File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { it.readText().contains("RecorderCheckActivity") }.map { it.path }.toList()
        assertTrue("debug screen leaked into main: $hits", hits.isEmpty())
        assertTrue(File("src/debug/java/app/stash/debug/RecorderCheckActivity.kt").exists())
    }

    @Test fun theRecorderPackageOpensNoFileAndNoNetworkConnection() {
        val files = File("src/main/java/app/stash/audio").listFiles()!!.toList() + File("src/main/java/app/stash/domain/Pcm.kt")
        assertTrue(files.size >= 3)
        for (f in files) {
            val t = f.readText()
            for (bad in listOf("FileOutputStream", "java.io.File", "java.net", "HttpURLConnection", "openFileOutput", "Log.")) {
                assertTrue("${f.name} uses $bad", !t.contains(bad))
            }
        }
    }
}
