package app.stash.speech

import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.Pcm
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * Card 26d items 2, 4 and 6: everything about running a model pack that does not depend on the engine library.
 * The engine itself (`speech/sherpa/`) is a [SpeechRuntime]: load its native library, open a pack, decode samples.
 * Tests drive this class with a fake runtime, so no JVM test ever loads native code. Pure Kotlin.
 */

/** The engine behind the seam. Implemented only in `app.stash.speech.sherpa`. */
interface SpeechRuntime {
    /** E.g. "sherpa-2.0.1". */
    val engineId: String

    /** Loads the native library. Throws (`UnsatisfiedLinkError`) if it cannot. */
    fun loadLibrary()

    /** Builds a recogniser from the pack's files. Throws if the engine refuses them. */
    fun open(pack: InstalledPack, threads: Int): LoadedModel
}

interface LoadedModel {
    /** 16 kHz mono samples in -1..1; returns the words as the engine wrote them. */
    fun decode(samples16k: FloatArray): String

    fun release()
}

/** The one thread every writing-out in the process runs on: one at a time, never the main thread. */
object TranscriberThread {
    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "stash-transcriber").apply { isDaemon = true } }
    }
}

/**
 * A [Transcriber] over the installed model packs. The model is loaded on first use (never at app start) and kept
 * until [release]; the caller (card 26f) releases it after each job, because ONNX Runtime's memory pool does not
 * shrink. A pack already loaded is kept; otherwise the best installed pack that fits in the free memory is loaded.
 */
class PackTranscriber(
    private val locator: ModelLocator,
    private val runtime: SpeechRuntime,
    private val threads: Int = 2,
    /** Bytes of memory free right now, or null if unknown. The app passes `ActivityManager.MemoryInfo.availMem`. */
    private val availableMemory: () -> Long? = { null },
    /** True on the main (UI) thread. The app passes `Looper.getMainLooper().isCurrentThread`. */
    private val isMainThread: () -> Boolean,
    private val executor: ExecutorService = TranscriberThread.executor,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Card 26e: which pack is preferred, and (outcome M2) which one long notes use. */
    private val policy: PackPolicy = DefaultPack.POLICY,
) : Transcriber {
    private val dispatcher = executor.asCoroutineDispatcher()

    // Touched only on the engine thread.
    private var model: LoadedModel? = null
    @Volatile private var modelPack: InstalledPack? = null
    private var modelThreads = 0
    private var libraryLoaded = false

    @Volatile private var engineThread: Thread? = null
    @Volatile private var loadFailure: TranscribeFailure? = null

    override val id: String?
        get() = (modelPack ?: locator.find())?.let { "${it.pack.id}/${runtime.engineId}" }

    override fun availability(): Availability {
        loadFailure?.let { return Availability.LoadFailed(it) }
        if (locator.find() != null) return Availability.Ready
        return if (locator.broken().isNotEmpty()) Availability.LoadFailed(TranscribeFailure.MODEL_CORRUPT) else Availability.NoModel
    }

    override suspend fun transcribe(audio: Pcm): TranscribeResult = transcribe(audio, TranscribeOptions())

    override suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult = withContext(dispatcher) {
        engineThread = Thread.currentThread()
        // Item 2: the work never runs on the main thread, whatever dispatcher a caller or a test supplies.
        if (isMainThread()) return@withContext failed(TranscribeFailure.ENGINE_ERROR, retryable = false)
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.STARTED, n1 = audio.durationMs)
        if (audio.samples.isEmpty()) {
            Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.OK, n1 = 0, n2 = 0, ms = 0)
            return@withContext TranscribeResult.Ok("", 0, 0, null)
        }
        try {
            val m = when (val l = ensureLoaded(options.place?.threads ?: threads, options.noteMs ?: audio.durationMs)) {
                is Load.Ok -> l.model
                is Load.No -> return@withContext failed(l.code, l.retryable)
            }
            val floats = toFloats(audio)
            val t = nanoTime()
            val text = m.decode(floats).trim()
            val ms = (nanoTime() - t) / 1_000_000
            Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.OK, n1 = audio.durationMs, n2 = 1, ms = ms)
            TranscribeResult.Ok(text, ms, 1, null)
        } catch (e: OutOfMemoryError) {
            dropModel()
            failed(TranscribeFailure.OUT_OF_MEMORY, retryable = true)
        } catch (e: Exception) {
            failed(TranscribeFailure.ENGINE_ERROR, retryable = true)
        }
    }

    override fun release() {
        // A pack that failed to open may have been replaced since: let the next writing-out try again.
        if (loadFailure == TranscribeFailure.MODEL_CORRUPT) loadFailure = null
        val t = engineThread
        when {
            t === Thread.currentThread() -> dropModel()
            // Never block the main thread behind a writing-out: queue it; it runs as soon as the engine is free.
            isMainThread() -> executor.execute { dropModel() }
            else -> executor.submit { dropModel() }.get()
        }
    }

    private sealed interface Load {
        class Ok(val model: LoadedModel) : Load
        class No(val code: TranscribeFailure, val retryable: Boolean) : Load
    }

    private fun ensureLoaded(threads: Int, noteMs: Long): Load {
        val installed = locator.installed()
        if (installed.isEmpty()) {
            dropModel()
            return if (locator.broken().isNotEmpty()) Load.No(TranscribeFailure.MODEL_CORRUPT, false)
            else Load.No(TranscribeFailure.MODEL_MISSING, false)
        }
        // A model already in memory is kept if its pack is still installed (its own memory is not "free" any more),
        // it was opened with the threads asked for (card 26e item 3), and the note's length does not call for
        // another pack (outcome M2).
        val current = model
        val wanted = policy.longNotePack(noteMs)?.takeIf { id -> installed.any { it.pack.id == id } }
        if (current != null && installed.any { it == modelPack } && modelThreads == threads &&
            (wanted == null || modelPack?.pack?.id == wanted)
        ) return Load.Ok(current)
        dropModel()
        val choice = PackChoice.choose(installed, availableMemory(), noteMs, policy) ?: return Load.No(TranscribeFailure.OUT_OF_MEMORY, true)
        // Outcome M2 with no tiny pack: the long note is written out with base, and that is logged (n1 note ms).
        if (choice.longNoteWithoutItsPack) Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.NO_LONG_NOTE_PACK, n1 = noteMs)
        val pick = choice.pack
        if (!libraryLoaded) {
            try {
                runtime.loadLibrary()
                libraryLoaded = true
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) return Load.No(TranscribeFailure.OUT_OF_MEMORY, true)
                loadFailure = TranscribeFailure.NATIVE_LOAD
                return Load.No(TranscribeFailure.NATIVE_LOAD, false)
            }
        }
        val t = nanoTime()
        val opened = try {
            runtime.open(pick, threads)
        } catch (e: OutOfMemoryError) {
            return Load.No(TranscribeFailure.OUT_OF_MEMORY, true)
        } catch (e: Exception) {
            loadFailure = TranscribeFailure.MODEL_CORRUPT
            return Load.No(TranscribeFailure.MODEL_CORRUPT, false)
        }
        loadFailure = null
        model = opened
        modelPack = pick
        modelThreads = threads
        Dbg.event(
            DbgComponent.TRANSCRIBE, DbgCode.LOADED, n1 = locator.rank(pick.pack),
            n2 = pick.location.ordinal, ms = (nanoTime() - t) / 1_000_000,
        )
        return Load.Ok(opened)
    }

    private fun dropModel() {
        val m = model ?: return
        model = null
        modelPack = null
        try { m.release() } catch (e: Throwable) { /* the native side is gone either way */ }
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.RELEASED)
    }

    private fun failed(code: TranscribeFailure, retryable: Boolean): TranscribeResult.Failed {
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.FAILED, n1 = code.code, n2 = if (retryable) 1 else 0)
        return TranscribeResult.Failed(code, retryable)
    }

    companion object {
        /** 16-bit samples to the -1..1 floats the engine takes, exactly as the card 25 spike did (divide by 32768). */
        fun toFloats(audio: Pcm): FloatArray {
            val s = audio.samples
            return FloatArray(s.size) { s[it] / 32768f }
        }
    }
}
