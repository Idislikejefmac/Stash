package app.stash.speech.sherpa

import android.content.res.AssetManager
import android.os.Looper
import app.stash.domain.Pcm
import app.stash.speech.InstalledPack
import app.stash.speech.LoadedModel
import app.stash.speech.ModelLocator
import app.stash.speech.PackLocation
import app.stash.speech.PackTranscriber
import app.stash.speech.SpeechRuntime
import app.stash.speech.TranscribeOptions
import app.stash.speech.TranscribeResult
import app.stash.speech.Transcriber
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/*
 * Card 26d section B. The only package that may import com.k2fsa.sherpa.onnx (a source-scan test enforces it).
 * Compiled only when the sherpa-onnx 2.0.1 AAR is given to the build (app/build.gradle.kts, `stash.sherpaAar`);
 * otherwise `src/nosherpa/` supplies a [SherpaEngine] that has no engine and the app uses NoTranscriber.
 *
 * The recogniser configuration is copied from the card 25 spike (`Spike25Runner`, MOONSHINE_V2 branch), the code
 * that ran these models on the S25 Edge: CPU provider, greedy search, no VAD, no hotwords, no NNAPI or GPU,
 * 16 kHz features of dimension 80, dither 0, maxActivePaths 4, 2 threads.
 */

object SherpaEngine {
    /** True in a build that contains the engine. */
    const val BUILT = true

    fun transcriber(locator: ModelLocator, assets: AssetManager?, availableMemory: () -> Long?): Transcriber? =
        SherpaMoonshineTranscriber(locator, assets = assets, availableMemory = availableMemory)
}

/** The Moonshine adapter: a [PackTranscriber] over [SherpaRuntime]. */
class SherpaMoonshineTranscriber private constructor(private val pack: PackTranscriber) : Transcriber by pack {
    constructor(
        locator: ModelLocator,
        threads: Int = 2,
        assets: AssetManager? = null,
        availableMemory: () -> Long? = { null },
    ) : this(
        PackTranscriber(
            locator = locator,
            runtime = SherpaRuntime(assets),
            threads = threads,
            availableMemory = availableMemory,
            isMainThread = { Looper.getMainLooper().isCurrentThread },
        )
    )

    // Card 26e: forwarded explicitly, so the caller's thread count and note length always reach the adapter.
    override suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult = pack.transcribe(audio, options)
}

class SherpaRuntime(private val assets: AssetManager?) : SpeechRuntime {
    override val engineId: String = "sherpa-2.0.1"

    override fun loadLibrary() {
        System.loadLibrary("sherpa-onnx-jni")
    }

    override fun open(pack: InstalledPack, threads: Int): LoadedModel {
        val p = pack.pack
        val feat = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f)
        val mc = OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(encoder = pack.path(p.encoder), mergedDecoder = pack.path(p.decoder)),
            tokens = pack.path(p.tokens), numThreads = threads, provider = "cpu", debug = false,
        )
        val rc = OfflineRecognizerConfig(
            featConfig = feat, modelConfig = mc, decodingMethod = "greedy_search", maxActivePaths = 4,
            hotwordsFile = "", hotwordsScore = 1.5f,
        )
        // Files on disk are opened by path (as the spike did); a pack bundled in the APK is read through the assets.
        val rec = if (pack.location == PackLocation.ASSETS) OfflineRecognizer(assets, rc) else OfflineRecognizer(config = rc)
        return SherpaModel(rec)
    }
}

private class SherpaModel(private val rec: OfflineRecognizer) : LoadedModel {
    override fun decode(samples16k: FloatArray): String {
        val s = rec.createStream()
        try {
            s.acceptWaveform(samples16k, 16000)
            rec.decode(s)
            return rec.getResult(s).text
        } finally {
            s.release()
        }
    }

    override fun release() = rec.release()
}
