package app.stash

import app.stash.domain.AudioFormat
import app.stash.domain.AudioFormats
import app.stash.domain.Ogg
import app.stash.domain.OggCrc
import app.stash.domain.OggOpusInfo
import app.stash.domain.OggOpusWriter
import app.stash.domain.OggStreamWriter
import app.stash.domain.OpusCodecConfig
import app.stash.domain.OpusContainer
import app.stash.domain.OpusHeaders
import app.stash.domain.OpusToc
import app.stash.domain.Pcm
import app.stash.domain.RecordingQuality
import app.stash.domain.Resampler
import app.stash.domain.Wav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Card 26b: the pure parts of the stored format. Resampler, WAV, Ogg pages and CRC, Opus headers, format rules. */
class AudioCodecLogicTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun tone(hz: Double, rate: Int, n: Int, amp: Double) = ShortArray(n) { (amp * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }
    private fun rms(s: ShortArray, from: Int, to: Int): Double { var a = 0.0; for (i in from until to) a += s[i].toDouble() * s[i]; return sqrt(a / (to - from)) }

    // ---- Resampler ----

    @Test fun `a 1 kHz tone at 48 kHz comes out as the same 1 kHz tone at 16 kHz, of the right length`() {
        val input = tone(1000.0, 48_000, 48_000, 10_000.0)
        val out = Resampler.to16k(input, 48_000)
        assertEquals(16_000, out.size)
        // Away from the edges, sample i must equal the 1 kHz tone at 16 kHz (the filter is centred: no delay).
        var worst = 0.0
        for (i in 200 until 15_800) worst = maxOf(worst, abs(out[i] - 10_000.0 * sin(2 * PI * 1000 * i / 16_000)))
        assertTrue("worst error $worst", worst < 100.0) // under 1% of the amplitude
        // A length that is not a multiple of 3 still gives every third input sample's position.
        assertEquals(3, Resampler.to16k(ShortArray(7), 48_000).size)
        assertEquals(0, Resampler.to16k(ShortArray(0), 48_000).size)
    }

    @Test fun `a 10 kHz tone is strongly reduced, so it does not fold back into speech`() {
        val input = tone(10_000.0, 48_000, 48_000, 10_000.0)
        val out = Resampler.to16k(input, 48_000)
        val ratio = rms(out, 200, 15_800) / rms(input, 600, 47_400)
        assertTrue("10 kHz kept $ratio of its level", ratio < 0.01) // at least 40 dB down
        // 9 kHz, just past the new 8 kHz limit, is also cut.
        val r9 = rms(Resampler.to16k(tone(9_000.0, 48_000, 48_000, 10_000.0), 48_000), 200, 15_800) / rms(input, 600, 47_400)
        assertTrue("9 kHz kept $r9", r9 < 0.01)
    }

    @Test fun `16 kHz input is returned unchanged`() {
        val input = tone(440.0, 16_000, 1_000, 5_000.0)
        assertSame(input, Resampler.to16k(input, 16_000))
    }

    @Test fun `the resampler never clips or wraps, even on full-scale input`() {
        val sine = tone(1000.0, 48_000, 9_600, 32_767.0)
        val out = Resampler.to16k(sine, 48_000)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 32_400..32_767)
        // Sign is kept sample by sample (a wrap would turn a near-full positive sample negative).
        for (i in 100 until out.size - 100) {
            val want = 32_767.0 * sin(2 * PI * 1000 * i / 16_000)
            if (abs(want) > 20_000) assertEquals(Math.signum(want), Math.signum(out[i].toDouble()), 0.0)
        }
        // A full-scale square wave rings past full scale inside the filter: it is held at the limits, never wrapped.
        val square = ShortArray(9_600) { if ((it / 24) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val sq = Resampler.to16k(square, 48_000)
        for (i in 100 until sq.size - 100) {
            val want = if (((i * 3) / 24) % 2 == 0) 1 else -1
            if ((i * 3) % 24 in 6..18) assertEquals("sample $i", want.toDouble(), Math.signum(sq[i].toDouble()), 0.0)
        }
    }

    @Test fun `the filter has unit gain at 0 Hz and odd symmetric taps, and unsupported rates are refused`() {
        val h = Resampler.taps(48_000)
        assertEquals(1, h.size % 2)
        assertEquals(1.0, h.sumOf { it.toDouble() }, 1e-5)
        for (i in h.indices) assertEquals(h[i], h[h.size - 1 - i], 1e-7f)
        val dc = Resampler.to16k(ShortArray(3_000) { 1_000 }, 48_000)
        assertEquals(1_000, dc[500].toInt())
        assertTrue(runCatching { Resampler.to16k(ShortArray(10), 44_100) }.isFailure)
        assertEquals(500, Resampler.to16k(ShortArray(1_000), 32_000).size)
    }

    @Test fun `downmix averages channels`() {
        val st = shortArrayOf(100, 300, -50, -150, 7, 7)
        assertArrayEquals(shortArrayOf(200, -100, 7), Resampler.downmix(st, 6, 2))
    }

    // ---- WAV ----

    @Test fun `WAV header fields are exact`() {
        val b = Wav.encode(Pcm(shortArrayOf(1, -2, 32_767)))
        assertEquals(44 + 6, b.size)
        fun s(at: Int, n: Int) = String(b, at, n, Charsets.US_ASCII)
        fun le32(at: Int) = Ogg.le32(b, at)
        fun le16(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        assertEquals("RIFF", s(0, 4)); assertEquals(36 + 6, le32(4)); assertEquals("WAVE", s(8, 4))
        assertEquals("fmt ", s(12, 4)); assertEquals(16, le32(16)); assertEquals(1, le16(20)); assertEquals(1, le16(22))
        assertEquals(16_000, le32(24)); assertEquals(32_000, le32(28)); assertEquals(2, le16(32)); assertEquals(16, le16(34))
        assertEquals("data", s(36, 4)); assertEquals(6, le32(40))
        assertArrayEquals(byteArrayOf(1, 0, 0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f), b.copyOfRange(44, 50))
    }

    @Test fun `WAV round trip is exact, and other chunks or a cut-off end are tolerated`() {
        val s = ShortArray(16_003) { Random(it).nextInt(-32_768, 32_768).toShort() }
        val b = Wav.encode(Pcm(s))
        assertArrayEquals(s, Wav.decode(b).samples)
        assertEquals(1_000L, Wav.decode(b).durationMs)
        // A LIST chunk of odd length (padded) before the data.
        val list = byteArrayOf('L'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), 3, 0, 0, 0, 9, 9, 9, 0)
        val withList = b.copyOfRange(0, 36) + list + b.copyOfRange(36, b.size)
        assertArrayEquals(s, Wav.decode(withList).samples)
        // Cut off: what is there is read.
        assertEquals(10, Wav.decode(b.copyOf(44 + 20)).samples.size)
        assertTrue(runCatching { Wav.decode(ByteArray(44)) }.isFailure)
    }

    @Test fun `a 48 kHz stereo WAV is read as 16 kHz mono`() {
        val mono48 = tone(500.0, 48_000, 4_800, 8_000.0)
        val st = ShortArray(mono48.size * 2) { mono48[it / 2] }
        val b = Wav.encode(st, st.size, 48_000)
        // Patch the header to two channels: channels, byte rate, block align.
        b[22] = 2; val br = 48_000 * 4; for (i in 0 until 4) b[28 + i] = (br ushr (8 * i)).toByte(); b[32] = 4
        val pcm = Wav.decode(b)
        assertEquals(1_600, pcm.samples.size)
        assertEquals(100L, pcm.durationMs)
    }

    // ---- Ogg CRC ----

    @Test fun `Ogg CRC matches the known check value and pages the phone's own muxer wrote`() {
        // CRC-32, polynomial 0x04C11DB7, initial 0, not reflected, no final XOR: the check value of "123456789".
        assertEquals(0x89A1897F.toInt(), OggCrc.of("123456789".toByteArray()))
        assertEquals(0, OggCrc.of(ByteArray(0)))
        // The first two pages of card 25's s13.ogg (headers only, no sound), written on the S25 by MediaMuxer.
        val p0 = hex("4f67675300020000000000000000e95a1d2d00000000e879bf5c01134f7075734865616401016800803e0000000000")
        val p1 = hex("4f67675300000000000000000000e95a1d2d010000008509631401174f70757354616773070000006c69626f70757300000000")
        val pages = Ogg.readPages(p0 + p1) // throws on any CRC mismatch
        assertEquals(2, pages.size)
        assertTrue(pages[0].bos); assertEquals(0L, pages[0].granule); assertEquals(0x2d1d5ae9, pages[0].serial)
        val head = OpusHeaders.parseHead(pages[0].body)!!
        assertEquals(1, head.version); assertEquals(1, head.channels); assertEquals(16_000, head.inputRate)
        assertEquals(104, head.preSkip) // the phone's encoder counts its 6.5 ms delay at 16 kHz (see OpusCodecConfig)
        assertEquals("libopus", OpusHeaders.parseVendor(pages[1].body))
        // A changed byte is caught.
        val bad = p0.copyOf(); bad[30] = (bad[30] + 1).toByte()
        assertTrue(runCatching { Ogg.readPages(bad) }.isFailure)
    }

    // ---- Opus headers (RFC 7845 section 5) and the TOC (RFC 6716 section 3.1) ----

    @Test fun `OpusHead and OpusTags are laid out as RFC 7845 says`() {
        val h = OpusHeaders.head(channels = 1, preSkip = 312, inputRate = 16_000)
        // "OpusHead", version 1, 1 channel, pre-skip 312 (0x0138 LE), 16,000 Hz (0x3e80 LE), gain 0, family 0.
        assertEquals("4f707573486561640101" + "3801" + "803e0000" + "0000" + "00", h.joinToString("") { "%02x".format(it) })
        assertEquals(19, h.size)
        val t = OpusHeaders.tags("Stash")
        assertArrayEquals("OpusTags".toByteArray() + byteArrayOf(5, 0, 0, 0) + "Stash".toByteArray() + byteArrayOf(0, 0, 0, 0), t)
        val tc = OpusHeaders.tags("v", listOf("A=b"))
        assertArrayEquals("OpusTags".toByteArray() + byteArrayOf(1, 0, 0, 0) + "v".toByteArray() + byteArrayOf(1, 0, 0, 0, 3, 0, 0, 0) + "A=b".toByteArray(), tc)
        val g = OpusHeaders.parseHead(OpusHeaders.head(2, 3840, 48_000, outputGain = -256))!!
        assertEquals(2, g.channels); assertEquals(3840, g.preSkip); assertEquals(48_000, g.inputRate); assertEquals(-256, g.outputGain); assertEquals(0, g.family)
    }

    @Test fun `the TOC byte gives each packet's length at 48 kHz`() {
        fun p(config: Int, c: Int, vararg more: Int) = byteArrayOf(((config shl 3) or c).toByte(), *more.map { it.toByte() }.toByteArray())
        assertEquals(960, OpusToc.samplesAt48k(p(9, 0)))        // SILK WB 20 ms: what this phone's encoder makes at 12 kbps
        assertEquals(480, OpusToc.samplesAt48k(p(8, 0)))        // 10 ms
        assertEquals(2880, OpusToc.samplesAt48k(p(11, 0)))      // 60 ms
        assertEquals(1920, OpusToc.samplesAt48k(p(13, 1)))      // hybrid 20 ms, two frames
        assertEquals(120, OpusToc.samplesAt48k(p(28, 0)))       // CELT 2.5 ms
        assertEquals(240 * 2, OpusToc.samplesAt48k(p(17, 2)))   // CELT 5 ms, two frames
        assertEquals(960 * 3, OpusToc.samplesAt48k(p(31, 3, 0x83))) // code 3, M = 3 (VBR bit set)
        assertEquals(0, OpusToc.samplesAt48k(ByteArray(0)))
    }

    @Test fun `the codec's delay becomes the pre-skip at 48 kHz, whatever form the codec data takes`() {
        fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }
        assertEquals(6_500_000L, OpusCodecConfig.delayNs(le64(6_500_000L)))
        assertEquals(312, OpusCodecConfig.preSkipFromDelay(6_500_000L))
        val wrapped = "AOPUSHDR".toByteArray() + le64(19) + OpusHeaders.head(1, 104, 16_000) +
            "AOPUSDLY".toByteArray() + le64(8) + le64(6_500_000L) + "AOPUSPRL".toByteArray() + le64(8) + le64(80_000_000L)
        assertEquals(6_500_000L, OpusCodecConfig.delayNs(wrapped))
        assertEquals(104, OpusCodecConfig.headPreSkip(wrapped))
        assertNull(OpusCodecConfig.delayNs(OpusHeaders.head(1, 104, 16_000)))
        assertNull(OpusCodecConfig.preSkipFromDelay(null))
        assertNull(OpusCodecConfig.preSkipFromDelay(500_000_000L))
    }

    // ---- The Ogg Opus writer, read back with the reader ----

    private fun packet(config: Int, size: Int, seed: Int): ByteArray {
        val r = Random(seed)
        return ByteArray(size) { if (it == 0) (config shl 3).toByte() else r.nextInt(256).toByte() }
    }

    @Test fun `written pages read back, headers alone on their pages, packets intact, CRCs good, last page ends the stream`() {
        val w = OggOpusWriter(serial = 0x1234567, preSkip = 312)
        val packets = (0 until 140).map { packet(9, 20 + it % 40, it) } // 140 x 20 ms = 2.8 s
        packets.forEach { w.addPacket(it) }
        val inputSamples = 44_600L // 2.7875 s at 16 kHz: the encoder padded the last packet
        val bytes = w.finish(inputSamples)
        val pages = Ogg.readPages(bytes)
        // Page 0: OpusHead alone, BOS, granule 0. Page 1: OpusTags, ends its page, granule 0.
        assertTrue(pages[0].bos); assertEquals(1, pages[0].lacing.size); assertEquals(0L, pages[0].granule)
        assertEquals(19, pages[0].body.size)
        assertFalse(pages[1].bos); assertEquals(0L, pages[1].granule); assertEquals(1, pages[1].packetsEnding)
        assertEquals(OggOpusWriter.VENDOR, OpusHeaders.parseVendor(pages[1].body))
        // Only the first page has BOS; only the last has EOS; one serial; sequence 0, 1, 2 ...
        assertEquals(1, pages.count { it.bos }); assertEquals(1, pages.count { it.eos }); assertTrue(pages.last().eos)
        pages.forEachIndexed { i, p -> assertEquals(i, p.sequence); assertEquals(0x1234567, p.serial); assertTrue(p.lacing.size <= 255) }
        // 50 packets a page: 140 packets on 3 audio pages.
        assertEquals(5, pages.size)
        assertEquals(listOf(50, 50, 40), pages.drop(2).map { it.packetsEnding })
        // Packets come back byte for byte, in order.
        val back = Ogg.packets(pages)
        assertEquals(142, back.size)
        for (i in packets.indices) assertArrayEquals(packets[i], back[i + 2])
        // Granules: 48 kHz samples through each page's last packet; the last page trims to pre-skip + the input length.
        assertEquals(50L * 960, pages[2].granule)
        assertEquals(100L * 960, pages[3].granule)
        assertEquals(312 + inputSamples * 3, pages[4].granule)
        val info = OggOpusInfo.read(bytes)!!
        assertEquals(inputSamples * 3, info.playable48k)
        assertEquals(140, info.audioPackets)
        assertEquals(312, info.head.preSkip)
    }

    @Test fun `granules never go backwards, and the end trim never adds samples`() {
        val w = OggOpusWriter(1, 312)
        repeat(10) { w.addPacket(packet(9, 30, it)) }
        val pages = Ogg.readPages(w.finish(inputSamples = 999_999L)) // longer than the packets: no trim
        val gs = pages.map { it.granule }
        assertEquals(gs.sorted(), gs)
        assertEquals(10L * 960, gs.last())
    }

    @Test fun `a page never holds more than 255 segments, and a huge packet continues across pages`() {
        val s = OggStreamWriter(serial = 7)
        val small = (0 until 300).map { ByteArray(10) { b -> it.toByte() } }
        small.forEachIndexed { i, p -> s.addPacket(p, (i + 1) * 960L) }
        val big = ByteArray(255 * 300 + 17) { (it % 251).toByte() } // 301 segments: must span two pages
        s.addPacket(big, 301 * 960L)
        val exact = ByteArray(255 * 2) { 5 } // a multiple of 255 ends with a 0 lacing value
        s.addPacket(exact, 302 * 960L)
        val pages = Ogg.readPages(s.finish())
        assertTrue(pages.all { it.lacing.size <= 255 })
        val back = Ogg.packets(pages)
        assertEquals(302, back.size)
        assertArrayEquals(big, back[300])
        assertArrayEquals(exact, back[301])
        // The page the big packet starts on ends no packet: granule -1; the next is marked continued.
        val startsBig = pages.indexOfFirst { p -> p.lacing.size == 255 && p.lacing.all { it == 255 } }
        assertTrue(startsBig >= 0)
        assertEquals(-1L, pages[startsBig].granule)
        assertTrue(pages[startsBig + 1].continued)
        assertTrue(pages.last().eos)
        assertTrue(pages.last().lacing.takeLast(3) == listOf(255, 255, 0))
    }

    @Test fun `a stream with no sound packets is still well formed`() {
        val bytes = OggOpusWriter(3, 312).finish(0L)
        val pages = Ogg.readPages(bytes)
        assertEquals(3, pages.size)
        assertTrue(pages.last().eos)
        assertEquals(0, OggOpusInfo.read(bytes)!!.audioPackets)
    }

    @Test fun `the reader refuses what is not Ogg Opus`() {
        assertNull(OggOpusInfo.read(ByteArray(100)))
        assertNull(OggOpusInfo.read(Wav.encode(Pcm(ShortArray(10)))))
        val good = OggOpusWriter(3, 312).also { it.addPacket(packet(9, 20, 1)) }.finish(320L)
        assertNotNull(OggOpusInfo.read(good))
        assertNull(OggOpusInfo.read(good.copyOf(good.size - 3)))
    }

    // ---- The format rules ----

    @Test fun `formats, default, fallback order and the quality setting`() {
        assertEquals(AudioFormat.OPUS_12, AudioFormats.DEFAULT_FORMAT)
        assertEquals("audio/ogg", AudioFormat.OPUS_12.mime); assertEquals(12_000, AudioFormat.OPUS_12.bitRate)
        assertEquals("audio/ogg", AudioFormat.OPUS_24.mime); assertEquals(24_000, AudioFormat.OPUS_24.bitRate)
        assertEquals("audio/mp4", AudioFormat.AAC_32.mime); assertEquals(32_000, AudioFormat.AAC_32.bitRate)
        assertEquals("audio/wav", AudioFormat.WAV.mime); assertNull(AudioFormat.WAV.codecMime)
        assertEquals(listOf(AudioFormat.OPUS_12, AudioFormat.AAC_32, AudioFormat.WAV), AudioFormats.fallbackOrder(AudioFormat.OPUS_12))
        assertEquals(listOf(AudioFormat.OPUS_24, AudioFormat.AAC_32, AudioFormat.WAV), AudioFormats.fallbackOrder(AudioFormat.OPUS_24))
        assertEquals(listOf(AudioFormat.WAV), AudioFormats.fallbackOrder(AudioFormat.WAV))
        assertEquals(1_500L, AudioFormats.FINISH_LIMIT_MS)
        assertEquals(RecordingQuality.SMALL, RecordingQuality.DEFAULT)
        assertEquals(RecordingQuality.SMALL, RecordingQuality.parse(null))
        assertEquals(RecordingQuality.CLEARER, RecordingQuality.parse("CLEARER"))
        assertEquals(AudioFormat.OPUS_24, RecordingQuality.CLEARER.format)
        assertEquals("Small (about 90 KB a minute)", RecordingQuality.SMALL.label)
        assertEquals("Clearer (about 180 KB a minute)", RecordingQuality.CLEARER.label)
        assertEquals(OpusContainer.OWN_WRITER, OpusContainer.DEFAULT)
        assertEquals(AudioFormat.AAC_32, AudioFormats.byMime("audio/mp4"))
    }
}
