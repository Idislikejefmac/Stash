package app.stash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Card 16e: scans of the source, so the camera can never write a photo where the gallery can see it. */
class CameraSourceScanTest {
    // Built from pieces so this file does not contain the words it forbids elsewhere.
    private val forbidden = listOf(
        "Media" + "Store",
        "Output" + "FileOptions",
        "getExternal" + "FilesDir",
        "getExternal" + "StorageDirectory",
        "Environment.DIRECTORY_" + "PICTURES",
    )

    private fun kotlinFiles(dir: File): List<File> = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun noMainCodeUsesTheGalleryOrAFileFormOfTakePicture() {
        val files = kotlinFiles(File("src/main/java"))
        assertTrue("scanned too little", files.size > 100)
        val hits = files.flatMap { f ->
            val text = f.readText()
            forbidden.filter { text.contains(it) }.map { "${f.path}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun theCameraUsesTheInMemoryCallbackAndNothingElse() {
        val engine = File("src/main/java/app/stash/capture/CameraXEngine.kt").readText()
        assertTrue(engine.contains("OnImageCapturedCallback"))
        assertTrue(engine.contains("controller.takePicture("))
        assertTrue(engine.contains("image.close()"))
        // The one call that takes a picture is the executor-and-callback form.
        assertEquals(1, Regex("takePicture\\(").findAll(engine).count())
    }

    @Test fun cameraCodeOpensNoConnection() {
        val files = listOf("CameraActivity", "CameraDeps", "CameraEngine", "CameraViewModel", "CameraXEngine").map { File("src/main/java/app/stash/capture/$it.kt") } +
            listOf(File("src/main/java/app/stash/domain/CameraRules.kt"), File("src/main/java/app/stash/domain/JpegOrientation.kt"))
        for (f in files) {
            val t = f.readText()
            for (bad in listOf("java.net", "HttpURLConnection", "okhttp", "URL(", "Socket")) assertTrue("${f.name} mentions $bad", !t.contains(bad))
        }
    }

    @Test fun theOnlyLoggingInCameraCodeIsDbgEvent() {
        val files = File("src/main/java/app/stash/capture").listFiles { f -> f.name.startsWith("Camera") }!!.toList() +
            listOf(File("src/main/java/app/stash/domain/CameraRules.kt"), File("src/main/java/app/stash/ui/CameraEntry.kt"))
        for (f in files) {
            val t = f.readText()
            for (bad in listOf("Log.", "println(")) assertTrue("${f.name} uses $bad", !t.contains(bad))
        }
    }
}
