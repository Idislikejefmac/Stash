package app.stash.audio

import app.stash.domain.AudioFormat
import app.stash.domain.AudioFormats
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.domain.PcmBuffer
import app.stash.domain.Wav
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One platform codec turning 16 kHz mono chunks into one stored file, behind a seam so JVM tests use fakes.
 * Every method may throw except [abort]. Called from one thread at a time.
 */
interface AudioEncoder {
    fun start()
    fun feed(chunk: ShortArray, len: Int)
    /** Drains the codec and returns the finished file. May block; the caller bounds how long it waits. */
    fun finish(): EncodedAudio
    /** Stops and releases everything; never throws. */
    fun abort()
}

/** Which encoders this phone has, and how to make one. The phone's version is [PlatformEncoders]. */
interface EncoderFactory {
    fun available(format: AudioFormat): Boolean
    fun create(format: AudioFormat): AudioEncoder
}

/**
 * Card 26b item 2 and 4. Encodes while he speaks, so Stop has only the tail left to finish, and never loses a
 * recording to a codec:
 *  - [start] takes the requested format, or the first that works in [AudioFormats.fallbackOrder] (an Opus encoder
 *    missing or refusing to start gives AAC_32; no codec at all gives WAV).
 *  - [feed] keeps a copy of every sample and hands the chunk to the encoder on this object's own thread, never the
 *    caller's, so the recorder thread is never held up by the codec.
 *  - Any encoder error during the note, or [finish] taking longer than [finishLimitMs], abandons the encoder and
 *    returns the kept samples as WAV.
 * Pure Kotlin; the codec comes from [factory].
 */
class StreamingEncoder(
    private val factory: EncoderFactory,
    private val finishLimitMs: Long = AudioFormats.FINISH_LIMIT_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val samples = PcmBuffer()
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val done = CountDownLatch(1)
    private var worker: Thread? = null
    @Volatile private var encoder: AudioEncoder? = null
    @Volatile private var result: EncodedAudio? = null
    @Volatile private var closed = false
    @Volatile private var aborted = false

    /** The format asked for, and the one actually being made. */
    var requested: AudioFormat = AudioFormats.DEFAULT_FORMAT
        private set
    @Volatile var format: AudioFormat = AudioFormat.WAV
        private set

    /** For the debug screen: most chunks ever waiting for the encoder, the time [finish] took, and what went wrong. */
    @Volatile var maxQueued = 0
        private set
    @Volatile var finishMs = 0L
        private set
    @Volatile var problem: String? = null
        private set
    /** The codec that was started (kept for its diagnostics after it is released). */
    @Volatile var lastEncoder: AudioEncoder? = null
        private set

    fun start(requested: AudioFormat): AudioFormat {
        check(worker == null && !closed) { "start once" }
        this.requested = requested
        var chosen = AudioFormat.WAV
        for (f in AudioFormats.fallbackOrder(requested)) {
            if (f == AudioFormat.WAV) break
            if (!factory.available(f)) { note("no ${f.name} encoder"); continue }
            val e = try { factory.create(f) } catch (t: Throwable) { note("${f.name} create: ${t.javaClass.simpleName}"); continue }
            try {
                e.start()
                encoder = e
                lastEncoder = e
                chosen = f
                break
            } catch (t: Throwable) {
                note("${f.name} start: ${t.javaClass.simpleName}")
                e.abort()
            }
        }
        format = chosen
        if (chosen != requested) Dbg.event(DbgComponent.AUDIO, DbgCode.DOWNGRADED, requested.ordinal, chosen.ordinal)
        val w = Thread(::encodeLoop, "stash-encoder").apply { isDaemon = true }
        worker = w
        w.start()
        return chosen
    }

    /** Called for every 20 ms chunk, on the recorder's thread. Quick: a copy and a queue. */
    fun feed(chunk: ShortArray, len: Int = chunk.size) {
        if (closed || worker == null) return
        samples.append(chunk, len)
        if (encoder != null) {
            queue.put(chunk.copyOf(len))
            val q = queue.size
            if (q > maxQueued) maxQueued = q
        }
    }

    private fun encodeLoop() {
        try {
            while (true) {
                val c = queue.take()
                if (c === END) break
                val e = encoder ?: continue
                if (aborted) continue
                try {
                    e.feed(c, c.size)
                } catch (t: Throwable) {
                    fail(e, "feed: ${t.javaClass.simpleName}")
                }
            }
            val e = encoder
            if (aborted) {
                encoder = null
                e?.abort()
            } else if (e != null) {
                try {
                    result = e.finish()
                } catch (t: Throwable) {
                    fail(e, "finish: ${t.javaClass.simpleName}")
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            done.countDown()
        }
    }

    private fun fail(e: AudioEncoder, why: String) {
        note(why)
        encoder = null
        e.abort()
    }

    private fun note(why: String) { problem = if (problem == null) why else "$problem; $why" }

    /**
     * Ends the note. Waits at most [finishLimitMs] for the encoder; otherwise, or if it failed, returns the samples as WAV.
     * Always returns a recording holding every sample fed.
     */
    fun finish(): EncodedAudio {
        val t0 = clock()
        check(worker != null) { "not started" }
        check(!closed) { "already finished" }
        closed = true
        queue.put(END)
        val inTime = try { done.await(finishLimitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        val r = result
        val out = if (inTime && r != null) {
            r
        } else {
            val e = encoder
            encoder = null
            if (!inTime) {
                note("finish took over $finishLimitMs ms")
                worker?.interrupt()
                // The codec may be stuck inside a call: release it on another thread so this one never waits on it.
                if (e != null) Thread({ e.abort() }, "stash-encoder-abort").apply { isDaemon = true }.start()
            }
            if (format != AudioFormat.WAV) Dbg.event(DbgComponent.AUDIO, DbgCode.DOWNGRADED, format.ordinal, AudioFormat.WAV.ordinal)
            format = AudioFormat.WAV
            val pcm = samples.toPcm()
            EncodedAudio(Wav.encode(pcm), AudioFormat.WAV.mime, pcm.durationMs, AudioFormat.WAV)
        }
        samples.release()
        finishMs = clock() - t0
        Dbg.event(DbgComponent.AUDIO, DbgCode.ENCODED, out.bytes.size, out.format.ordinal, ms = finishMs)
        return out
    }

    /** Drops everything: the note was cancelled. */
    fun abort() {
        if (aborted) return
        aborted = true
        closed = true
        queue.clear()
        if (worker != null) queue.put(END) else { encoder?.abort(); encoder = null }
        samples.release()
    }

    private companion object { val END = ShortArray(0) }
}
