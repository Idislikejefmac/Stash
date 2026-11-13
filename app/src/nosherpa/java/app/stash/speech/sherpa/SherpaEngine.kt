package app.stash.speech.sherpa

import android.content.res.AssetManager
import app.stash.speech.ModelLocator
import app.stash.speech.Transcriber

/*
 * Card 26d: the build without the sherpa-onnx AAR (no `stash.sherpaAar` given to Gradle). There is no engine, so
 * Container uses NoTranscriber and voice notes stay with the phone's own recogniser (tier 0). The real
 * implementation is in `src/sherpa/`, compiled only when the AAR is present.
 */
object SherpaEngine {
    const val BUILT = false

    @Suppress("UNUSED_PARAMETER")
    fun transcriber(locator: ModelLocator, assets: AssetManager?, availableMemory: () -> Long?): Transcriber? = null
}
