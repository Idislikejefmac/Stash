package app.stash.speech

import app.stash.domain.Pcm

/*
 * Card 26d, section A: the one seam through which a recording becomes words. Feature code names only these types;
 * it never names the engine or the model (only `app.stash.speech.sherpa` may import the engine's library).
 * Pure Kotlin: no Android import.
 */

/** Writes out a recording, on the phone, never on the main thread. */
interface Transcriber {
    /** Names the engine and model, e.g. "moonshine-base-en-2026-02-27/sherpa-2.0.1". Null: none available. */
    val id: String?

    fun availability(): Availability

    suspend fun transcribe(audio: Pcm): TranscribeResult

    /**
     * Card 26e: the same, with the caller saying where it runs ([TranscribeOptions.place]: two engine threads on
     * screen, one in a background job) and, for a piece of a longer note, how long the whole note is. An engine
     * that has no use for either keeps this default, which ignores them.
     */
    suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult = transcribe(audio)

    /** Free the native memory now. The next [transcribe] loads the model again (about half a second). */
    fun release()
}

/**
 * Card 26e item 3: where a writing-out runs. On screen the engine uses 2 threads; in a background job 1 (card 25
 * measured 0.018 of real time at one thread, far inside the speed bar; fewer threads means less memory and heat).
 */
enum class RunPlace(val threads: Int) { ON_SCREEN(2), BACKGROUND(1) }

/** [place] null: the engine's own default. [noteMs] null: the audio handed over is the whole note. */
data class TranscribeOptions(val place: RunPlace? = null, val noteMs: Long? = null)

sealed interface Availability {
    data object Ready : Availability
    data object NoModel : Availability
    data class LoadFailed(val code: TranscribeFailure) : Availability
    /** The owner switched recording off (card 26g adds the switch). */
    data object Off : Availability
}

sealed interface TranscribeResult {
    /** [procMs] is the engine's own time for these words (loading the model is not counted). [confidence] is null unless the engine reports one. */
    data class Ok(val text: String, val procMs: Long, val chunks: Int, val confidence: Float?) : TranscribeResult
    data class Failed(val code: TranscribeFailure, val retryable: Boolean) : TranscribeResult
}

/** Why a writing-out failed. [code] is what events carry (n1); it never changes once shipped. */
enum class TranscribeFailure(val code: Int) {
    /** This build has no engine, or none was chosen. */
    NO_ENGINE(1),
    /** No speech model pack is installed. */
    MODEL_MISSING(2),
    /** A pack is installed but its files are not what was verified, or the engine refused them. */
    MODEL_CORRUPT(3),
    /** The engine's native library could not be loaded. */
    NATIVE_LOAD(4),
    /** Not enough memory to load or run the model (an `OutOfMemoryError`, or too little free memory to start). */
    OUT_OF_MEMORY(5),
    /** Anything else the engine threw while writing out. */
    ENGINE_ERROR(6),
}

/** Tier 0: no engine. Voice notes use the phone's own recogniser and the app says so. */
object NoTranscriber : Transcriber {
    override val id: String? = null
    override fun availability(): Availability = Availability.NoModel
    override suspend fun transcribe(audio: Pcm): TranscribeResult = TranscribeResult.Failed(TranscribeFailure.NO_ENGINE, retryable = false)
    override fun release() {}
}
