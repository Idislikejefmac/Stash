package app.stash

import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EventSink
import app.stash.domain.Pcm
import app.stash.speech.AssetLister
import app.stash.speech.Availability
import app.stash.speech.ImportFailure
import app.stash.speech.ImportResult
import app.stash.speech.ImportWords
import app.stash.speech.InstalledPack
import app.stash.speech.KnownModels
import app.stash.speech.KnownPack
import app.stash.speech.LoadedModel
import app.stash.speech.ModelImporter
import app.stash.speech.ModelLocator
import app.stash.speech.ModelPacks
import app.stash.speech.NoTranscriber
import app.stash.speech.PackChoice
import app.stash.speech.PackFile
import app.stash.speech.PackLocation
import app.stash.speech.PackMarker
import app.stash.speech.PackTranscriber
import app.stash.speech.SpeechRuntime
import app.stash.speech.TranscribeFailure
import app.stash.speech.TranscribeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Card 26d: the Transcriber seam, model packs, the importer and the engine-neutral adapter, with fakes (no native code). */
class SpeechEngineTest {
    private lateinit var dir: File
    private lateinit var root: File
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        dir = Files.createTempDirectory("speech").toFile()
        root = File(dir, "files/models")
        Dbg.sink = EventSink { c, code, n1, n2, ref, ms -> synchronized(events) { events += "$c $code $n1 $n2 $ref ${ms != null}" } }
    }

    @After fun tearDown() {
        Dbg.sink = null
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ fake packs: small "models" with real hashes

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class Fake(val pack: KnownPack, val content: Map<String, ByteArray>)

    private fun fakePack(id: String, seed: Int, workingSet: Long = 100): Fake {
        fun bytes(name: String, n: Int) = ByteArray(n) { ((it * 31 + seed + name.length) % 251).toByte() }
        val content = mapOf(
            "encoder_model.ort" to bytes("encoder_model.ort", 3000 + seed),
            "decoder_model_merged.ort" to bytes("decoder_model_merged.ort", 5000 + seed),
            "tokens.txt" to "a\nb\nc\n".toByteArray(),
            "LICENSE" to "MIT License\n".toByteArray(),
        )
        fun f(n: String) = PackFile(n, content.getValue(n).size.toLong(), sha(content.getValue(n)))
        val p = KnownPack(id, "Fake $id", "licence line", f("encoder_model.ort"), f("decoder_model_merged.ort"), f("tokens.txt"), f("LICENSE"), workingSet)
        return Fake(p, content)
    }

    private val base = fakePack("fake-base-en", 1, workingSet = 300)
    private val tiny = fakePack("fake-tiny-en", 2, workingSet = 200)
    private val known = listOf(base.pack, tiny.pack)

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipFor(f: Fake, change: (String, ByteArray) -> ByteArray = { _, b -> b }): ByteArray =
        zipOf(*f.content.map { (n, b) -> "${f.pack.id}/$n" to change(n, b) }.toTypedArray())

    private fun importer(free: Long = Long.MAX_VALUE) = ModelImporter(root, known, freeBytes = { free })
    private fun locator(assets: AssetLister? = null) = ModelLocator(root, assets, known)
    private fun import(bytes: ByteArray, free: Long = Long.MAX_VALUE) = importer(free).import(ByteArrayInputStream(bytes))

    /** Everything under files/ (the importer must never write outside files/models/<id>/). */
    private fun allFiles(): List<String> = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun leftovers() = root.listFiles().orEmpty().filter { it.name.endsWith(ModelImporter.TMP) || it.name.endsWith(ModelImporter.OLD) }

    // ------------------------------------------------------------------ KnownModels

    @Test fun everyKnownPackHasNamesSizesAndHashesAndTheIdsAreUnique() {
        val all = KnownModels.ALL
        assertEquals(listOf("moonshine-base-en-2026-02-27", "moonshine-tiny-en-2026-02-27"), all.map { it.id })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (p in all) {
            assertTrue(p.displayName.isNotBlank())
            assertTrue(p.licenceLine.startsWith("MIT"))
            assertTrue("only English packs may load: ${p.id}", p.id.contains("-en-"))
            assertEquals(listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt", "LICENSE"), p.files.map { it.name })
            for (f in p.files) {
                assertTrue(f.bytes > 0)
                assertTrue("${p.id}/${f.name}", f.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        }
        // The register's figures (docs/LICENSES.md) and the measured sizes on disk.
        assertEquals(31_326_816, KnownModels.BASE.encoder.bytes)
        assertEquals(109_424_400, KnownModels.BASE.decoder.bytes)
        assertEquals(549_350, KnownModels.BASE.tokens.bytes)
        assertEquals(13_281_600, KnownModels.TINY.encoder.bytes)
        assertEquals(30_412_256, KnownModels.TINY.decoder.bytes)
        assertEquals(141L, KnownModels.BASE.megabytes)
        assertEquals(44L, KnownModels.TINY.megabytes)
        assertTrue(KnownModels.TINY.workingSetBytes < KnownModels.BASE.workingSetBytes)
    }

    @Test fun thePackToolChecksTheSameFilesAsKnownModels() {
        val py = File("../tools/make_model_pack.py").readText()
        for (p in KnownModels.ALL) {
            assertTrue(p.id, "\"${p.id}\"" in py)
            for (f in p.files) {
                val line = Regex("""\("${Regex.escape(f.name)}", ([0-9_]+), "([0-9a-f]{64})"\)""").findAll(py).map { it.groupValues }
                    .any { it[1].replace("_", "").toLong() == f.bytes && it[2] == f.sha256 }
                assertTrue("${p.id}/${f.name} differs in tools/make_model_pack.py", line)
            }
        }
    }

    // ------------------------------------------------------------------ the importer

    @Test fun aGoodZipInstallsAndIsFoundAndLeavesNothingElse() {
        val r = import(zipFor(base))
        assertTrue(r.toString(), r is ImportResult.Installed)
        assertEquals(base.pack, (r as ImportResult.Installed).pack)
        assertEquals(base.pack.totalBytes, r.bytes)
        val found = locator().find()
        assertNotNull(found)
        assertEquals(base.pack, found!!.pack)
        assertEquals(PackLocation.FILES, found.location)
        assertEquals(File(root, base.pack.id).absolutePath + "/encoder_model.ort", found.path(base.pack.encoder))
        assertEquals(
            (base.content.keys.map { "files/models/fake-base-en/$it" } + "files/models/fake-base-en/.verified").sorted(),
            allFiles(),
        )
        assertTrue(PackMarker.matches(File(root, "fake-base-en/.verified").readText(), base.pack))
        assertTrue(leftovers().isEmpty())
        assertTrue(events.any { it.startsWith("TRANSCRIBE STORED ${base.pack.totalBytes} 0") })
    }

    @Test fun oneWrongByteInstallsNothingAndLeavesNoTemporaryFolder() {
        val r = import(zipFor(base) { n, b -> if (n == "decoder_model_merged.ort") b.copyOf().also { it[100] = (it[100] + 1).toByte() } else b })
        assertEquals(ImportResult.Failed(ImportFailure.WRONG_FILE, "decoder_model_merged.ort"), r)
        assertNull(locator().find())
        assertTrue(allFiles().isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(events.any { it.startsWith("TRANSCRIBE REFUSED ${ImportFailure.WRONG_FILE.code} ") })
    }

    @Test fun aWrongByteDoesNotTouchAPackAlreadyInstalled() {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        val before = allFiles().associateWith { File(dir, it).readBytes().toList() }
        val r = import(zipFor(base) { n, b -> if (n == "tokens.txt") "x\n".toByteArray() else b })
        assertEquals(ImportFailure.WRONG_FILE, (r as ImportResult.Failed).reason)
        assertEquals(before, allFiles().associateWith { File(dir, it).readBytes().toList() })
        assertEquals(base.pack, locator().find()!!.pack)
    }

    @Test fun anEntryThatClimbsOutOfTheFolderIsIgnoredAndTheImportFails() {
        val entries = base.content.map { (n, b) -> "${base.pack.id}/$n" to b } + ("../x" to "evil".toByteArray())
        val r = import(zipOf(*entries.toTypedArray()))
        assertEquals(ImportResult.Failed(ImportFailure.UNKNOWN_ENTRY), r)
        assertFalse(File(dir, "files/x").exists())
        assertFalse(File(root.parentFile, "x").exists())
        assertTrue(allFiles().isEmpty())
        assertNull(locator().find())
        // Also as the first entry, and dressed up as a pack path.
        assertEquals(ImportFailure.UNKNOWN_ENTRY, (import(zipOf("../x" to "e".toByteArray())) as ImportResult.Failed).reason)
        assertEquals(ImportFailure.UNKNOWN_ENTRY, (import(zipOf("fake-base-en/../../x" to "e".toByteArray())) as ImportResult.Failed).reason)
        assertTrue(allFiles().isEmpty())
    }

    @Test fun anUnknownNameIsIgnoredAndTheImportFails() {
        val entries = base.content.map { (n, b) -> "${base.pack.id}/$n" to b } + ("${base.pack.id}/extra.onnx" to ByteArray(10))
        assertEquals(ImportResult.Failed(ImportFailure.UNKNOWN_ENTRY), import(zipOf(*entries.toTypedArray())))
        // A real Moonshine file name under an id that is not in the list (a non-English pack) is refused too.
        assertEquals(ImportResult.Failed(ImportFailure.UNKNOWN_ENTRY), import(zipOf("moonshine-base-es/encoder_model.ort" to ByteArray(10))))
        // Files at the top level, without the pack folder, are refused.
        assertEquals(ImportResult.Failed(ImportFailure.UNKNOWN_ENTRY), import(zipOf(*base.content.map { (n, b) -> n to b }.toTypedArray())))
        assertTrue(allFiles().isEmpty())
    }

    @Test fun aSecondImportOfTheSamePackReplacesItCleanly() {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        // Damage the installed copy: it no longer counts as installed.
        File(root, "fake-base-en/encoder_model.ort").writeBytes(ByteArray(3))
        assertNull(locator().find())
        assertEquals(listOf(base.pack), locator().broken())
        val r = import(zipFor(base))
        assertTrue(r is ImportResult.Installed)
        assertEquals(base.pack, locator().find()!!.pack)
        assertTrue(locator().broken().isEmpty())
        assertTrue(leftovers().isEmpty())
        assertEquals(base.content.getValue("encoder_model.ort").toList(), File(root, "fake-base-en/encoder_model.ort").readBytes().toList())
    }

    @Test fun baseIsPreferredOverTinyWhenBothArePresent() {
        assertTrue(import(zipFor(tiny)) is ImportResult.Installed)
        assertEquals(tiny.pack, locator().find()!!.pack)
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        assertEquals(base.pack, locator().find()!!.pack)
        assertEquals(listOf(base.pack, tiny.pack), locator().installed().map { it.pack })
        assertTrue(ModelPacks(root, known).remove(base.pack.id))
        assertEquals(tiny.pack, locator().find()!!.pack)
        assertTrue(events.any { it.startsWith("TRANSCRIBE REMOVED 0 1") })
    }

    @Test fun missingDuplicateMixedLongAndNonZipFilesAreRefusedAndLeaveNothing() {
        val noTokens = base.content.filterKeys { it != "tokens.txt" }.map { (n, b) -> "${base.pack.id}/$n" to b }
        assertEquals(ImportResult.Failed(ImportFailure.MISSING_FILE, "tokens.txt"), import(zipOf(*noTokens.toTypedArray())))
        val dup = base.content.map { (n, b) -> "${base.pack.id}/$n" to b } + ("${base.pack.id}/LICENSE" to base.content.getValue("LICENSE"))
        // java.util.zip refuses to write a duplicate entry, so the duplicate is spelled through a second, equal zip name check.
        assertEquals(ImportFailure.DUPLICATE_ENTRY, runCatching { import(zipOfAllowingDuplicates(dup)) }.getOrThrow().let { (it as ImportResult.Failed).reason })
        val mixed = listOf("${base.pack.id}/LICENSE" to base.content.getValue("LICENSE"), "${tiny.pack.id}/LICENSE" to tiny.content.getValue("LICENSE"))
        assertEquals(ImportResult.Failed(ImportFailure.MIXED_PACKS), import(zipOf(*mixed.toTypedArray())))
        assertEquals(
            ImportResult.Failed(ImportFailure.WRONG_FILE, "encoder_model.ort"),
            import(zipFor(base) { n, b -> if (n == "encoder_model.ort") b + ByteArray(5000) else b }),
        )
        assertEquals(ImportResult.Failed(ImportFailure.NOT_A_ZIP), import("this is not a zip".toByteArray()))
        assertEquals(ImportResult.Failed(ImportFailure.NOT_A_PACK), import(zipOf("${base.pack.id}/" to ByteArray(0))))
        assertTrue(allFiles().isEmpty())
        assertTrue(leftovers().isEmpty())
    }

    /** A zip with a repeated entry name, written by hand (ZipOutputStream will not write one). */
    private fun zipOfAllowingDuplicates(entries: List<Pair<String, ByteArray>>): ByteArray {
        val unique = entries.mapIndexed { i, (n, b) -> (if (i == entries.size - 1) n.replaceAfterLast('/', "LICENSZ") else n) to b }
        val bytes = zipOf(*unique.toTypedArray())
        // Rename the last entry's "LICENSZ" back to "LICENSE" in both its local and central headers (same length).
        val s = String(bytes, Charsets.ISO_8859_1).replace("LICENSZ", "LICENSE")
        return s.toByteArray(Charsets.ISO_8859_1)
    }

    @Test fun tooLittleFreeSpaceIsSaidAndNothingIsWritten() {
        val r = import(zipFor(base), free = ModelImporter.NEEDED_FREE_BYTES - 1)
        assertEquals(ImportResult.Failed(ImportFailure.NO_SPACE), r)
        assertEquals("Not added: the phone needs 300 MB free to add the speech model.", ImportWords.message(r))
        assertTrue(allFiles().isEmpty())
    }

    @Test fun aFolderLeftByACrashIsCleanedAndNeverLoaded() {
        File(root, "fake-base-en.tmp").mkdirs()
        base.content.forEach { (n, b) -> File(root, "fake-base-en.tmp/$n").writeBytes(b) }
        File(root, "fake-base-en.tmp/.verified").writeText(PackMarker.text(base.pack))
        assertNull(locator().find())
        assertTrue(import(zipFor(tiny)) is ImportResult.Installed)
        assertTrue(leftovers().isEmpty())
    }

    @Test fun theWordsArePlainAndNameOnlyThePacksOwnFiles() {
        assertEquals("Speech model added: Moonshine base, 141 MB.", ImportWords.message(ImportResult.Installed(KnownModels.BASE, 1, 1)))
        assertEquals("Speech model: Moonshine base, 141 MB", ImportWords.installedLine(KnownModels.BASE))
        assertEquals(
            "Not added: the file decoder_model_merged.ort is not the one Stash expects. Nothing was changed.",
            ImportWords.message(ImportResult.Failed(ImportFailure.WRONG_FILE, "decoder_model_merged.ort")),
        )
        for (f in ImportFailure.entries) assertTrue(ImportWords.message(ImportResult.Failed(f, "x")).startsWith("Not added"))
        assertEquals(ImportFailure.entries.size, ImportFailure.entries.map { it.code }.toSet().size)
    }

    // ------------------------------------------------------------------ the locator and the memory choice

    @Test fun aPackCountsOnlyWithItsMarkerAndItsFilesAtTheVerifiedSizes() {
        val d = File(root, base.pack.id).apply { mkdirs() }
        base.content.forEach { (n, b) -> File(d, n).writeBytes(b) }
        assertNull("no marker", locator().find())
        File(d, PackMarker.NAME).writeText(PackMarker.text(tiny.pack))
        assertNull("marker of another pack", locator().find())
        File(d, PackMarker.NAME).writeText(PackMarker.text(base.pack))
        assertEquals(base.pack, locator().find()!!.pack)
        File(d, "tokens.txt").delete()
        assertNull("a file missing", locator().find())
        assertEquals(listOf(base.pack), locator().broken())
        // A folder for a pack that is not in the list is never looked at.
        File(root, "moonshine-base-es").mkdirs()
        assertEquals(listOf(base.pack), locator().broken())
    }

    @Test fun thePhonesFilesComeBeforeTheApksAssets() {
        val assets = mapOf("models/${tiny.pack.id}" to tiny.pack.files.map { it.name } + PackMarker.NAME)
        val lister = AssetLister { assets[it] }
        val fromAssets = locator(lister).find()!!
        assertEquals(tiny.pack, fromAssets.pack)
        assertEquals(PackLocation.ASSETS, fromAssets.location)
        assertEquals("models/${tiny.pack.id}/tokens.txt", fromAssets.path(tiny.pack.tokens))
        assertNull("an asset folder without its marker", ModelLocator(root, { listOf("encoder_model.ort") }, known).find())
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        assertEquals(listOf(PackLocation.FILES, PackLocation.ASSETS), locator(lister).installed().map { it.location })
        assertTrue(import(zipFor(tiny)) is ImportResult.Installed)
        assertEquals(PackLocation.FILES, locator(lister).installed()[1].location)
    }

    @Test fun theMemoryChoiceTakesTheBestPackThatFits() {
        val both = listOf(InstalledPack(base.pack, PackLocation.FILES, "b"), InstalledPack(tiny.pack, PackLocation.FILES, "t"))
        assertEquals(base.pack, PackChoice.choose(both, null)!!.pack)
        assertEquals(base.pack, PackChoice.choose(both, 300)!!.pack)
        assertEquals(tiny.pack, PackChoice.choose(both, 299)!!.pack)
        assertNull(PackChoice.choose(both, 199))
        assertNull(PackChoice.choose(emptyList(), null))
    }

    // ------------------------------------------------------------------ NoTranscriber

    @Test fun noTranscriberSaysNoModelAndFailsWithNoEngine() = runBlocking {
        assertNull(NoTranscriber.id)
        assertEquals(Availability.NoModel, NoTranscriber.availability())
        assertEquals(TranscribeResult.Failed(TranscribeFailure.NO_ENGINE, retryable = false), NoTranscriber.transcribe(Pcm(ShortArray(16_000))))
        NoTranscriber.release()
        assertEquals(TranscribeFailure.entries.size, TranscribeFailure.entries.map { it.code }.toSet().size)
    }

    // ------------------------------------------------------------------ the adapter, with a fake engine

    private class FakeRuntime(
        var libraryFails: Boolean = false,
        var openFails: Boolean = false,
        var decode: (FloatArray) -> String = { "  heard ${it.size} samples \n" },
    ) : SpeechRuntime {
        override val engineId = "fake-1"
        val libraryLoads = AtomicInteger()
        val opens = mutableListOf<String>()
        val releases = AtomicInteger()
        val threads = mutableListOf<String>()
        val inside = AtomicInteger()
        var maxInside = 0
        var lastSamples: FloatArray? = null

        override fun loadLibrary() {
            libraryLoads.incrementAndGet()
            if (libraryFails) throw UnsatisfiedLinkError("no library")
        }

        override fun open(pack: InstalledPack, threads: Int): LoadedModel {
            if (openFails) throw IllegalArgumentException("failed to create native OfflineRecognizer")
            opens += "${pack.pack.id}@$threads"
            return object : LoadedModel {
                override fun decode(samples16k: FloatArray): String {
                    val n = inside.incrementAndGet()
                    synchronized(this@FakeRuntime) { maxInside = maxOf(maxInside, n); this@FakeRuntime.threads += Thread.currentThread().name }
                    try {
                        Thread.sleep(5)
                        lastSamples = samples16k
                        return this@FakeRuntime.decode(samples16k)
                    } finally { inside.decrementAndGet() }
                }
                override fun release() { releases.incrementAndGet() }
            }
        }
    }

    private val engineThread = Executors.newSingleThreadExecutor { Thread(it, "test-transcriber") }

    @After fun stopThread() { engineThread.shutdownNow() }

    private fun adapter(rt: FakeRuntime, mem: Long? = null, main: Boolean = false) =
        PackTranscriber(locator(), rt, threads = 2, availableMemory = { mem }, isMainThread = { main }, executor = engineThread)

    private fun pcm(vararg s: Short) = Pcm(shortArrayOf(*s))

    @Test fun withNoPackTheAdapterSaysNoModelAndNeverTouchesTheEngine() = runBlocking {
        val rt = FakeRuntime()
        val t = adapter(rt)
        assertEquals(Availability.NoModel, t.availability())
        assertNull(t.id)
        assertEquals(TranscribeResult.Failed(TranscribeFailure.MODEL_MISSING, false), t.transcribe(pcm(1, 2, 3)))
        assertEquals(0, rt.libraryLoads.get())
        assertTrue(rt.opens.isEmpty())
    }

    @Test fun theModelIsLoadedOnFirstUseKeptAndReleasedOnRequest() = runBlocking {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        val rt = FakeRuntime()
        val t = adapter(rt)
        assertEquals(Availability.Ready, t.availability())
        assertEquals("fake-base-en/fake-1", t.id)
        assertEquals("nothing is loaded before the first writing-out", 0, rt.libraryLoads.get())
        val r = t.transcribe(pcm(16384, -32768, 0, 32767)) as TranscribeResult.Ok
        assertEquals("heard 4 samples", r.text)
        assertEquals(1, r.chunks)
        assertNull("confidence is never invented", r.confidence)
        assertEquals(listOf(0.5f, -1f, 0f, 32767f / 32768f), rt.lastSamples!!.toList())
        t.transcribe(pcm(1))
        assertEquals(1, rt.libraryLoads.get())
        assertEquals(listOf("fake-base-en@2"), rt.opens)
        t.release()
        assertEquals(1, rt.releases.get())
        t.transcribe(pcm(1))
        assertEquals("loaded again after release", listOf("fake-base-en@2", "fake-base-en@2"), rt.opens)
        assertEquals("the library is loaded once per process", 1, rt.libraryLoads.get())
        t.release(); t.release()
        assertEquals(2, rt.releases.get())
        // kotlinx.coroutines debug mode appends " @coroutine#N" to the thread name while a coroutine runs.
        assertTrue(rt.threads.toString(), rt.threads.isNotEmpty() && rt.threads.all { it.startsWith("test-transcriber") })
    }

    @Test fun anEmptyRecordingGivesNoWordsWithoutLoadingAnything() = runBlocking {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        val rt = FakeRuntime()
        assertEquals(TranscribeResult.Ok("", 0, 0, null), adapter(rt).transcribe(Pcm(ShortArray(0))))
        assertTrue(rt.opens.isEmpty())
    }

    @Test fun failuresMapToTheirCodes() = runBlocking {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        val noLib = FakeRuntime(libraryFails = true)
        val a = adapter(noLib)
        assertEquals(TranscribeResult.Failed(TranscribeFailure.NATIVE_LOAD, false), a.transcribe(pcm(1)))
        assertEquals(Availability.LoadFailed(TranscribeFailure.NATIVE_LOAD), a.availability())

        val badModel = FakeRuntime(openFails = true)
        val b = adapter(badModel)
        assertEquals(TranscribeResult.Failed(TranscribeFailure.MODEL_CORRUPT, false), b.transcribe(pcm(1)))
        assertEquals(Availability.LoadFailed(TranscribeFailure.MODEL_CORRUPT), b.availability())
        badModel.openFails = false
        b.release() // after a new import the caller releases: the next writing-out tries again
        assertEquals(Availability.Ready, b.availability())
        assertTrue(b.transcribe(pcm(1)) is TranscribeResult.Ok)

        val oom = FakeRuntime(decode = { throw OutOfMemoryError() })
        val c = adapter(oom)
        assertEquals(TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, true), c.transcribe(pcm(1)))
        assertEquals("the model is let go after running out of memory", 1, oom.releases.get())

        val throws = FakeRuntime(decode = { error("engine") })
        assertEquals(TranscribeResult.Failed(TranscribeFailure.ENGINE_ERROR, true), adapter(throws).transcribe(pcm(1)))

        File(root, "fake-base-en/decoder_model_merged.ort").writeBytes(ByteArray(1))
        val d = adapter(FakeRuntime())
        assertEquals(Availability.LoadFailed(TranscribeFailure.MODEL_CORRUPT), d.availability())
        assertEquals(TranscribeResult.Failed(TranscribeFailure.MODEL_CORRUPT, false), d.transcribe(pcm(1)))
    }

    @Test fun neverOnTheMainThread() = runBlocking {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        val rt = FakeRuntime()
        assertEquals(TranscribeResult.Failed(TranscribeFailure.ENGINE_ERROR, false), adapter(rt, main = true).transcribe(pcm(1)))
        assertEquals(0, rt.libraryLoads.get())
        assertTrue(rt.opens.isEmpty())
    }

    @Test fun oneWritingOutAtATime() = runBlocking {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        val rt = FakeRuntime()
        val t = adapter(rt)
        val results = (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { t.transcribe(pcm(1, 2)) } }.awaitAll()
        assertTrue(results.all { it is TranscribeResult.Ok })
        assertEquals(1, rt.maxInside)
        assertEquals(1, rt.opens.size)
    }

    @Test fun theLoadChoosesByFreeMemoryAndKeepsWhatIsLoaded() = runBlocking {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        assertTrue(import(zipFor(tiny)) is ImportResult.Installed)
        var free: Long? = 250
        val rt = FakeRuntime()
        val t = PackTranscriber(locator(), rt, availableMemory = { free }, isMainThread = { false }, executor = engineThread)
        assertTrue(t.transcribe(pcm(1)) is TranscribeResult.Ok)
        assertEquals(listOf("fake-tiny-en@2"), rt.opens)
        assertEquals("fake-tiny-en/fake-1", t.id)
        free = 100 // the loaded model's own memory is not free any more: it is kept
        assertTrue(t.transcribe(pcm(1)) is TranscribeResult.Ok)
        assertEquals(1, rt.opens.size)
        t.release()
        assertEquals(TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, true), t.transcribe(pcm(1)))
        assertEquals("nothing is loaded when nothing fits", 1, rt.opens.size)
        free = 1_000
        assertTrue(t.transcribe(pcm(1)) is TranscribeResult.Ok)
        assertEquals("fake-base-en@2", rt.opens.last())
    }

    @Test fun eventsCarryNumbersOnlyNeverWords() = runBlocking {
        assertTrue(import(zipFor(base)) is ImportResult.Installed)
        events.clear()
        val t = adapter(FakeRuntime(decode = { "the secret words" }))
        t.transcribe(Pcm(ShortArray(16_000)))
        t.release()
        val c = DbgComponent.TRANSCRIBE
        assertEquals(
            listOf("$c ${DbgCode.STARTED} 1000 null null false", "$c ${DbgCode.LOADED} 0 0 null true", "$c ${DbgCode.OK} 1000 1 null true", "$c ${DbgCode.RELEASED} null null null false"),
            events,
        )
        assertTrue(events.none { "secret" in it })
    }
}
