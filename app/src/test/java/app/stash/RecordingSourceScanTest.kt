package app.stash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Card 26c item 19: the code form of "audio never leaves the phone and is never sent to a model". A recording's bytes
 * are opened only through `AttachmentRepository.openRecording` (the picture door, `openBytes`, refuses a recording at
 * run time), and only the audio, pipeline and (card 10) backup packages may call it. The network packages never name
 * the attachment repository, raw samples or an encoded recording.
 */
class RecordingSourceScanTest {
    private val main = File("src/main/java/app/stash")

    private fun kotlinFiles(dir: File): List<File> =
        if (dir.exists()) dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else emptyList()

    private fun rel(f: File) = f.relativeTo(main).invariantSeparatorsPath

    @Test fun onlyTheAudioPipelineAndBackupPackagesOpenARecording() {
        val files = kotlinFiles(main)
        assertTrue("scanned too little", files.size > 100)
        val callers = files.filter { Regex("\\bopenRecording\\s*\\(").containsMatchIn(it.readText()) }.map(::rel)
        val allowed = callers.filter { it == "data/AttachmentRepository.kt" || it.startsWith("audio/") || it.startsWith("pipeline/") || it.startsWith("backup/") }
        assertEquals("openRecording called from outside audio/, pipeline/ and backup/", callers, allowed)
        // the definition is where it should be
        assertTrue(File(main, "data/AttachmentRepository.kt").readText().contains("suspend fun openRecording("))
    }

    @Test fun thePictureDoorRefusesARecording() {
        val text = File(main, "data/AttachmentRepository.kt").readText()
        assertTrue(text.contains("suspend fun openBytes(attachmentId: String): InputStream = open(attachmentId, audio = false)"))
        assertTrue(text.contains("(a.kind == AttachmentKind.AUDIO) != audio"))
    }

    @Test fun noNetworkPackageNamesTheAttachmentsSamplesOrARecording() {
        val netDirs = listOf("model/hosted", "watch/net", "lookup/net").map { File(main, it) }
        assertTrue("model/hosted must exist to be scanned", netDirs[0].exists())
        val words = listOf("AttachmentRepository", "Pcm", "EncodedAudio", "openRecording", "RecordingSaver", "TranscriptionRepository")
        val hits = netDirs.flatMap(::kotlinFiles).flatMap { f ->
            val text = f.readText()
            words.filter { Regex("\\b$it\\b").containsMatchIn(text) }.map { "${rel(f)}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
