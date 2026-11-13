package app.stash

import app.stash.audio.AudioEncoder
import app.stash.audio.Compactor
import app.stash.audio.EncoderFactory
import app.stash.audio.StreamingEncoder
import app.stash.domain.AudioFormat
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.domain.EventSink
import app.stash.domain.Pcm
import app.stash.domain.Wav
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Card 26b: the fallback rule with fake encoders (no Android codec on the JVM), the compactor, and the source checks. */
class StreamingEncoderTest {
    private val n = AudioFormatConstants.CHUNK_SAMPLES

    private class FakeEncoder(
        val format: AudioFormat,
        val throwOnChunk: Int = -1,
        val startThrows: Boolean = false,
        val finishSleepMs: Long = 0,
    ) : AudioEncoder {
        var fed = 0
        var samples = 0L
        @Volatile var aborted = false
        @Volatile var finished = false
        override fun start() { if (startThrows) throw IllegalStateException("refused") }
        override fun feed(chunk: ShortArray, len: Int) {
            fed++
            if (fed == throwOnChunk) throw IllegalStateException("codec error")
            samples += len
        }
        override fun finish(): EncodedAudio {
            if (finishSleepMs > 0) Thread.sleep(finishSleepMs)
            finished = true
            return EncodedAudio(ByteArray(10), format.mime, AudioFormatConstants.samplesToMs(samples), format)
        }
        override fun abort() { aborted = true }
    }

    private class FakeFactory(val present: Set<AudioFormat>, val make: (AudioFormat) -> FakeEncoder = { FakeEncoder(it) }) : EncoderFactory {
        val made = CopyOnWriteArrayList<FakeEncoder>()
        override fun available(format: AudioFormat) = format in present
        override fun create(format: AudioFormat): AudioEncoder = make(format).also { made += it }
    }

    private fun chunk(i: Int) = ShortArray(n) { ((i * 37 + it) % 2000 - 1000).toShort() }

    private val events = CopyOnWriteArrayList<List<Any?>>()
    private fun capture() { events.clear(); Dbg.sink = EventSink { c, code, n1, n2, _, ms -> events += listOf(c, code, n1, n2, ms) } }
    @After fun clearSink() { Dbg.sink = null }

    @Test fun `with every encoder working, the note comes out in the asked format and is logged as ENCODED`() {
        capture()
        val f = FakeFactory(setOf(AudioFormat.OPUS_12, AudioFormat.AAC_32))
        val e = StreamingEncoder(f)
        assertEquals(AudioFormat.OPUS_12, e.start(AudioFormat.OPUS_12))
        repeat(100) { e.feed(chunk(it), n) }
        val out = e.finish()
        assertEquals(AudioFormat.OPUS_12, out.format)
        assertEquals(2_000L, out.durationMs)
        assertEquals(100, f.made.single().fed)
        val enc = events.single { it[1] == DbgCode.ENCODED }
        assertEquals(listOf(DbgComponent.AUDIO, DbgCode.ENCODED, 10L, AudioFormat.OPUS_12.ordinal.toLong()), enc.take(4))
        assertTrue(events.none { it[1] == DbgCode.DOWNGRADED })
    }

    @Test fun `an encoder that throws on the 40th chunk yields a WAV holding every sample fed`() {
        capture()
        val f = FakeFactory(setOf(AudioFormat.OPUS_12)) { FakeEncoder(it, throwOnChunk = 40) }
        val e = StreamingEncoder(f)
        e.start(AudioFormat.OPUS_12)
        val fed = ShortArray(100 * n)
        repeat(100) { i -> val c = chunk(i); c.copyInto(fed, i * n); e.feed(c, n) }
        val out = e.finish()
        assertEquals(AudioFormat.WAV, out.format)
        assertEquals("audio/wav", out.mime)
        assertArrayEquals(fed, Wav.decode(out.bytes).samples)
        assertEquals(2_000L, out.durationMs)
        assertTrue(f.made.single().aborted)
        assertTrue(events.any { it.take(4) == listOf(DbgComponent.AUDIO, DbgCode.DOWNGRADED, AudioFormat.OPUS_12.ordinal.toLong(), AudioFormat.WAV.ordinal.toLong()) })
        assertEquals(AudioFormat.WAV.ordinal.toLong(), events.single { it[1] == DbgCode.ENCODED }[3])
    }

    @Test fun `an encoder whose finish hangs is abandoned after 1,5 s with the same result`() {
        val f = FakeFactory(setOf(AudioFormat.OPUS_12)) { FakeEncoder(it, finishSleepMs = 20_000) }
        val e = StreamingEncoder(f)
        e.start(AudioFormat.OPUS_12)
        val fed = ShortArray(50 * n)
        repeat(50) { i -> val c = chunk(i); c.copyInto(fed, i * n); e.feed(c, n) }
        val t0 = System.nanoTime()
        val out = e.finish()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 1_450..3_000)
        assertEquals(AudioFormat.WAV, out.format)
        assertArrayEquals(fed, Wav.decode(out.bytes).samples)
        assertTrue(e.finishMs >= 1_450)
        // The stuck codec is released on another thread.
        val deadline = System.currentTimeMillis() + 2_000
        while (!f.made.single().aborted && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(f.made.single().aborted)
    }

    @Test fun `no Opus encoder present chooses AAC_32, and says so`() {
        capture()
        val f = FakeFactory(setOf(AudioFormat.AAC_32))
        val e = StreamingEncoder(f)
        assertEquals(AudioFormat.AAC_32, e.start(AudioFormat.OPUS_12))
        repeat(10) { e.feed(chunk(it), n) }
        assertEquals(AudioFormat.AAC_32, e.finish().format)
        assertEquals(listOf(AudioFormat.AAC_32), f.made.map { it.format })
        assertTrue(events.any { it.take(4) == listOf(DbgComponent.AUDIO, DbgCode.DOWNGRADED, AudioFormat.OPUS_12.ordinal.toLong(), AudioFormat.AAC_32.ordinal.toLong()) })
    }

    @Test fun `an Opus encoder that refuses to start falls to AAC_32, and with no codec at all the note is WAV`() {
        val f = FakeFactory(setOf(AudioFormat.OPUS_12, AudioFormat.AAC_32)) { FakeEncoder(it, startThrows = it.isOpus) }
        val e = StreamingEncoder(f)
        assertEquals(AudioFormat.AAC_32, e.start(AudioFormat.OPUS_12))
        assertTrue(f.made.first().aborted)
        assertNotNull(e.problem)
        e.finish()

        val none = StreamingEncoder(FakeFactory(emptySet()))
        assertEquals(AudioFormat.WAV, none.start(AudioFormat.OPUS_24))
        val fed = ShortArray(5 * n)
        repeat(5) { i -> val c = chunk(i); c.copyInto(fed, i * n); none.feed(c, n) }
        val out = none.finish()
        assertEquals(AudioFormat.WAV, out.format)
        assertArrayEquals(fed, Wav.decode(out.bytes).samples)
    }

    @Test fun `feed hands chunks to the encoder's own thread, never the caller's`() {
        val threads = CopyOnWriteArrayList<String>()
        val f = object : EncoderFactory {
            override fun available(format: AudioFormat) = true
            override fun create(format: AudioFormat) = object : AudioEncoder {
                override fun start() {}
                override fun feed(chunk: ShortArray, len: Int) { threads += Thread.currentThread().name }
                override fun finish() = EncodedAudio(ByteArray(1), format.mime, 0, format)
                override fun abort() {}
            }
        }
        val e = StreamingEncoder(f)
        e.start(AudioFormat.OPUS_12)
        repeat(5) { e.feed(chunk(it), n) }
        e.finish()
        assertEquals(5, threads.size)
        assertTrue(threads.none { it == Thread.currentThread().name })
    }

    @Test fun `abort drops the note and releases the codec, and later feeds are ignored`() {
        val f = FakeFactory(setOf(AudioFormat.OPUS_12))
        val e = StreamingEncoder(f)
        e.start(AudioFormat.OPUS_12)
        repeat(3) { e.feed(chunk(it), n) }
        e.abort()
        e.feed(chunk(9), n)
        val deadline = System.currentTimeMillis() + 2_000
        while (!f.made.single().aborted && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(f.made.single().aborted)
        assertTrue(!f.made.single().finished)
        assertTrue(runCatching { e.finish() }.isFailure)
    }

    // ---- Compactor ----

    @Test fun `the compactor re-encodes a stored WAV to the default format, or returns null if the encoder still fails`() {
        val pcm = Pcm(ShortArray(16_000) { (it % 100).toShort() })
        val wav = Wav.encode(pcm)
        val ok = FakeFactory(setOf(AudioFormat.OPUS_12))
        val out = Compactor(ok).compact(wav)!!
        assertEquals(AudioFormat.OPUS_12, out.format)
        assertEquals(1_000L, out.durationMs)
        assertEquals(50, ok.made.single().fed)

        val broken = FakeFactory(setOf(AudioFormat.OPUS_12)) { FakeEncoder(it, throwOnChunk = 3) }
        assertNull(Compactor(broken).compact(wav))
        assertTrue(broken.made.single().aborted)
        assertNull(Compactor(FakeFactory(emptySet())).compact(wav))
        assertNull(Compactor(ok).compact(ByteArray(20)))
        assertNull(Compactor(ok).compact(wav, AudioFormat.WAV))
    }

    // ---- Source checks ----

    @Test fun `the format check screen is debug only, and the audio package writes no file and opens no connection`() {
        val hits = File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { it.readText().contains("AudioFormatCheckActivity") }.map { it.path }.toList()
        assertTrue("debug screen leaked into main: $hits", hits.isEmpty())
        assertTrue(File("src/debug/java/app/stash/debug/AudioFormatCheckActivity.kt").exists())
        val files = File("src/main/java/app/stash/audio").listFiles()!!.toList() +
            listOf("AudioFormat", "Wav", "Resampler", "Ogg", "OggOpus").map { File("src/main/java/app/stash/domain/$it.kt") }
        assertTrue(files.size >= 9)
        for (f in files) {
            val t = f.readText()
            for (bad in listOf("FileOutputStream", "java.io.File", "RandomAccessFile", "java.net", "HttpURLConnection", "openFileOutput", "Log.", "println", "setDataSource(\"", "filesDir", "cacheDir")) {
                assertTrue("${f.name} uses $bad", !t.contains(bad))
            }
        }
        // The pure parts import nothing from Android.
        for (f in files.filter { it.parentFile.name == "domain" } + File("src/main/java/app/stash/audio/StreamingEncoder.kt") + File("src/main/java/app/stash/audio/Compactor.kt")) {
            assertTrue("${f.name} imports Android", !f.readText().contains("import android."))
        }
    }

    @Test fun `no Opus or other audio library was added to the build`() {
        val build = File("build.gradle.kts").readText() + File("../gradle/libs.versions.toml").readText()
        for (bad in listOf("opus", "ogg", "exoplayer", "media3", "ffmpeg")) assertTrue("build names $bad", !build.lowercase().contains(bad))
    }
}
