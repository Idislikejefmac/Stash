package app.stash.domain

/*
 * Card 26b. What a stored recording is, and the rules that pick one. Pure Kotlin, no Android imports.
 * (Named AudioFormat as the card says; Android's android.media.AudioFormat is a different class, so code that needs
 * both imports one under another name.)
 */

/**
 * A stored recording's format. [mime] is what the file is (written beside it for ever, so formats can sit side by side);
 * [codecMime] is the platform encoder it needs (null for WAV, which needs none); [bitRate] is the encoder's nominal rate.
 * [approxBytesPerMinute] is what the owner is shown: see [AudioFormats] for where each number comes from.
 */
enum class AudioFormat(val mime: String, val codecMime: String?, val bitRate: Int, val approxBytesPerMinute: Int) {
    OPUS_12("audio/ogg", "audio/opus", 12_000, 91_000),
    OPUS_24("audio/ogg", "audio/opus", 24_000, 180_000),
    AAC_32("audio/mp4", "audio/mp4a-latm", 32_000, 257_000),
    WAV("audio/wav", null, 256_000, 1_920_000);

    val isOpus: Boolean get() = codecMime == "audio/opus"
}

object AudioFormats {
    /**
     * Set by open check C3 (the owner listening). Q1 "12 is fine" keeps OPUS_12; Q2 sets OPUS_24. Never AAC (card 26b).
     */
    val DEFAULT_FORMAT = AudioFormat.OPUS_12

    /**
     * The sizes above. Opus: card 25's own packets from the phone's encoder (docs/spike25/results/enc/opus12 and
     * opus24, 100.7 s of speech) repacked 50 packets to an Ogg page as [OggOpusWriter] writes them: 91,093 and
     * 180,034 bytes a minute. Card 25 measured 170,372 and 259,313 because Android's Ogg muxer writes one 20 ms packet
     * per page, so half of a 12 kbps file was page headers. The encoder's packets are the same either way, so the
     * engine hears the same sound. AAC: card 25, 257,000. WAV: 16,000 x 2 bytes x 60. To be confirmed on the phone.
     */
    fun bytesPerMinuteText(f: AudioFormat): String = "about ${(f.approxBytesPerMinute + 5_000) / 10_000 * 10} KB a minute"

    /** The fallback order at run time (card 26b item 4): the requested format, then AAC_32, then WAV, which cannot fail. */
    fun fallbackOrder(requested: AudioFormat): List<AudioFormat> = when (requested) {
        AudioFormat.OPUS_12, AudioFormat.OPUS_24 -> listOf(requested, AudioFormat.AAC_32, AudioFormat.WAV)
        AudioFormat.AAC_32 -> listOf(AudioFormat.AAC_32, AudioFormat.WAV)
        AudioFormat.WAV -> listOf(AudioFormat.WAV)
    }

    /** Finishing the encoder after Stop may take at most this long; past it the samples are kept as WAV. */
    const val FINISH_LIMIT_MS = 1_500L

    fun byMime(mime: String): AudioFormat? = AudioFormat.entries.firstOrNull { it.mime == mime }
}

/** The setting "Recording quality" (card 26b item 1). Applied to notes recorded after the change. */
enum class RecordingQuality(val format: AudioFormat) {
    SMALL(AudioFormat.OPUS_12),
    CLEARER(AudioFormat.OPUS_24);

    val label: String get() = when (this) {
        SMALL -> "Small (${AudioFormats.bytesPerMinuteText(format)})"
        CLEARER -> "Clearer (${AudioFormats.bytesPerMinuteText(format)})"
    }

    companion object {
        /** The default follows DEFAULT_FORMAT, so check C3's answer changes one constant only. */
        val DEFAULT: RecordingQuality get() = entries.firstOrNull { it.format == AudioFormats.DEFAULT_FORMAT } ?: SMALL
        fun parse(s: String?): RecordingQuality = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}

/** How Opus packets are put into Ogg (card 26b item 3). See [AudioFormats] and the card's "What was built". */
enum class OpusContainer {
    /** E2: this app's own page writer ([OggOpusWriter]), 50 packets a page. Pure Kotlin, in memory. The default. */
    OWN_WRITER,
    /** E1: Android's MediaMuxer (MUXER_OUTPUT_OGG) writing into an in-memory file (memfd). One packet a page. */
    MUXER;

    companion object { val DEFAULT = OWN_WRITER }
}

/** One finished recording, held in memory. [durationMs] is the length of the sound fed in. */
class EncodedAudio(val bytes: ByteArray, val mime: String, val durationMs: Long, val format: AudioFormat)
