package app.stash.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Card 26b. Brings decoded sound to what the engine takes: 16 kHz mono 16-bit. The Opus decoder gives 48 kHz.
 * A windowed-sinc low-pass filter (Blackman window, cut-off 7 kHz, so nothing above the new 8 kHz limit folds back
 * into speech) evaluated only at every k-th input sample (k = rate / 16,000). Pure Kotlin.
 */
object Resampler {
    const val TARGET = AudioFormatConstants.SAMPLE_RATE
    const val CUTOFF_HZ = 7_000.0

    /** Transition band of about 2 kHz: a Blackman window's width is about 5.5 / taps of the input rate. */
    private const val TRANSITION_HZ = 2_000.0

    private val cache = HashMap<Int, FloatArray>()

    /** 16 kHz is returned as it is (the same array). Rates that are whole multiples of 16 kHz are filtered and decimated. */
    fun to16k(samples: ShortArray, rate: Int): ShortArray {
        if (rate == TARGET) return samples
        require(rate > TARGET && rate % TARGET == 0) { "unsupported sample rate $rate" }
        val k = rate / TARGET
        val h = taps(rate)
        val half = h.size / 2
        val n = samples.size
        val outLen = (n + k - 1) / k
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val c = i * k
            var acc = 0.0
            val lo = maxOf(-half, c - (n - 1))
            val hi = minOf(half, c)
            for (j in lo..hi) acc += h[j + half] * samples[c - j]
            // Held at the limits (symmetric), never wrapped: a filter can ring a hair past full scale.
            out[i] = acc.roundToInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** The filter for an input [rate]: odd length, symmetric, gain 1 at 0 Hz. */
    @Synchronized
    fun taps(rate: Int): FloatArray = cache.getOrPut(rate) {
        var len = kotlin.math.ceil(5.5 * rate / TRANSITION_HZ).toInt()
        if (len % 2 == 0) len++
        val m = len - 1
        val fc = CUTOFF_HZ / rate
        val raw = DoubleArray(len) { i ->
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m)
            sinc * w
        }
        val sum = raw.sum()
        FloatArray(len) { (raw[it] / sum).toFloat() }
    }

    /** Interleaved [channels] to mono by averaging; [count] is the number of interleaved samples. One channel is copied. */
    fun downmix(inter: ShortArray, count: Int, channels: Int): ShortArray {
        if (channels == 1) return if (count == inter.size) inter else inter.copyOf(count)
        val frames = count / channels
        return ShortArray(frames) { f ->
            var s = 0
            for (c in 0 until channels) s += inter[f * channels + c]
            (s / channels).toShort()
        }
    }
}
