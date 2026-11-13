package app.stash

import app.stash.domain.OpenSourceNotices
import app.stash.speech.KnownModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Card 26d items 3, 5, 11, 12 and 13: what the sources, the build file and the assets must and must not contain. */
class SpeechSourceScanTest {
    /** The code that ships: the main source set and both variants of the engine adapter. Paths are package paths. */
    private fun shippingSources(): List<Pair<String, String>> = listOf("src/main/java", "src/sherpa/java", "src/nosherpa/java").flatMap { r ->
        val root = File(r)
        assertTrue("run from the app folder: $r", root.isDirectory)
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { "$r:" + it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toList()
    }

    private fun pkg(path: String) = path.substringAfter(':')

    @Test fun onlyTheSherpaPackageImportsTheEngineLibrary() {
        val debug = File("src/debug/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { "src/debug/java:" + it.relativeTo(File("src/debug/java")).invariantSeparatorsPath to it.readText() }.toList()
        val offenders = (shippingSources() + debug).filter { (p, t) -> "com.k2fsa" in t && !pkg(p).startsWith("app/stash/speech/sherpa/") }
        assertTrue("engine library named outside speech/sherpa: ${offenders.map { it.first }}", offenders.isEmpty())
        assertTrue(shippingSources().any { (p, t) -> p.startsWith("src/sherpa/java:app/stash/speech/sherpa/") && "import com.k2fsa.sherpa.onnx.OfflineRecognizer" in t })
    }

    @Test fun featureCodeNeverNamesTheEngineOrTheModel() {
        val allowedSherpa = { p: String -> pkg(p).startsWith("app/stash/speech/") || pkg(p) in setOf("app/stash/data/Container.kt", "app/stash/domain/OpenSourceNotices.kt") }
        val allowedMoonshine = { p: String -> pkg(p).startsWith("app/stash/speech/") || pkg(p) == "app/stash/domain/OpenSourceNotices.kt" }
        val sherpa = shippingSources().filter { (p, t) -> t.contains("sherpa", ignoreCase = true) && !allowedSherpa(p) }.map { it.first }
        val moonshine = shippingSources().filter { (p, t) -> t.contains("moonshine", ignoreCase = true) && !allowedMoonshine(p) }.map { it.first }
        assertTrue("sherpa named in feature code: $sherpa", sherpa.isEmpty())
        assertTrue("Moonshine named in feature code: $moonshine", moonshine.isEmpty())
    }

    @Test fun theSpeechCodeOpensNoConnectionAndLogsOnlyThroughDbg() {
        val banned = listOf("java.net.", "HttpURLConnection", "okhttp", "Socket(", "WebView", "URL(", "android.util.Log", "println(", "printStackTrace(")
        val offenders = shippingSources().filter { (p, _) -> pkg(p).startsWith("app/stash/speech/") || pkg(p) == "app/stash/ui/SpeechModelSettings.kt" }
            .flatMap { (p, t) -> banned.filter { it in t }.map { "$p: $it" } }
        assertTrue("$offenders", offenders.isEmpty())
        val tool = File("../tools/make_model_pack.py").readText()
        assertFalse("the pack tool downloads nothing", listOf("urllib", "requests", "http://", "https://", "curl", "wget").any { it in tool.substringAfter("never downloads anything.") })
    }

    @Test fun bothVariantsOfTheEngineEntryPointHaveTheSameShape() {
        val with = File("src/sherpa/java/app/stash/speech/sherpa/SherpaMoonshineTranscriber.kt").readText()
        val without = File("src/nosherpa/java/app/stash/speech/sherpa/SherpaEngine.kt").readText()
        val sig = "fun transcriber(locator: ModelLocator, assets: AssetManager?, availableMemory: () -> Long?): Transcriber?"
        assertTrue(sig in with && sig in without)
        assertTrue("const val BUILT = true" in with)
        assertTrue("const val BUILT = false" in without)
        // The recogniser is configured as the card 25 spike ran it: CPU, greedy search, no hotwords, 80-dim features, no dither.
        for (s in listOf("provider = \"cpu\"", "decodingMethod = \"greedy_search\"", "hotwordsFile = \"\"", "featureDim = 80", "dither = 0f", "sampleRate = 16000", "maxActivePaths = 4")) {
            assertTrue(s, s in with)
        }
        assertFalse("no VAD", "Vad" in with)
    }

    @Test fun theBuildFileTakesTheEngineFromOutsideTheRepositoryForEveryBuildTypeAndDropsTheSpikeOnlyLines() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue("implementation(files(sherpaAar))" in gradle)
        assertFalse("debugImplementation(files(" in gradle)
        assertFalse("ML Kit GenAI library line", "genai-speech-recognition" in gradle)
        assertFalse("-Xskip-metadata-version-check" in gradle)
        assertTrue("stash.sherpaAar" in gradle)
        assertTrue("a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7" in gradle)
        val defaultConfig = gradle.substringAfter("defaultConfig {").substringBefore("\n    }")
        assertTrue("arm64 for every build type", "abiFilters += \"arm64-v8a\"" in defaultConfig)
        assertFalse("no AAR inside the repository", File("..").walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".git", ".idea") }.any { it.extension == "aar" })
    }

    @Test fun modelPacksAreLeftOutOfBackupAndDeviceTransfer() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue(section, "<exclude domain=\"file\" path=\"models/\" />" in body)
            assertTrue(section, "<exclude domain=\"file\" path=\"att/\" />" in body)
        }
    }

    @Test fun theDebugImportRouteIsNotInTheShippingSources() {
        val names = shippingSources().map { it.first }
        assertTrue(names.none { it.endsWith("ModelImportReceiver.kt") || it.endsWith("TranscriberCheckActivity.kt") })
        assertTrue(File("src/debug/java/app/stash/debug/ModelImportReceiver.kt").isFile)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("ModelImportReceiver" in manifest)
    }

    @Test fun everyNoticeTextIsBundledAndTheMoonshineTextIsThePackagesOwnFile() {
        val notices = OpenSourceNotices.forBuild(speechEngineBuilt = true)
        assertEquals(listOf("sherpa-onnx 2.0.1", "ONNX Runtime", "Moonshine speech models (English)"), notices.map { it.name })
        assertTrue(OpenSourceNotices.forBuild(speechEngineBuilt = false).isEmpty())
        for (n in notices) for (path in n.assetPaths) {
            val f = File("src/main/assets/$path")
            assertTrue(path, f.isFile && f.length() > 0)
        }
        fun text(path: String) = File("src/main/assets/$path").readText()
        assertTrue(text("licenses/sherpa-onnx-LICENSE.txt").contains("Apache License\n                           Version 2.0, January 2004"))
        assertTrue(text("licenses/onnxruntime-LICENSE.txt").contains("Copyright (c) Microsoft Corporation"))
        assertTrue(text("licenses/onnxruntime-ThirdPartyNotices.txt").startsWith("THIRD PARTY SOFTWARE NOTICES AND INFORMATION"))
        val moonshine = File("src/main/assets/licenses/moonshine-LICENSE.txt").readBytes()
        assertTrue(String(moonshine).contains("Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)"))
        val sha = MessageDigest.getInstance("SHA-256").digest(moonshine).joinToString("") { "%02x".format(it) }
        assertEquals("the app ships the same LICENSE file as each pack", KnownModels.BASE.licence.sha256, sha)
    }
}
