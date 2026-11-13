package app.stash.debug

import android.content.Context

/**
 * Card 26d removed the ML Kit GenAI Speech library (measured in card 25, not adopted: its terms forbid production
 * use while in preview). The card 25 spike's `Spike25MlKit.kt` is left on disk but no longer compiled; this stand-in
 * keeps `EngineSpikeActivity`'s `--es mode mlkit` compiling and says so instead of running.
 */
class Spike25MlKit(
    @Suppress("unused") private val ctx: Context,
    @Suppress("unused") private val tag: String,
    @Suppress("unused") private val mode: Int,
    @Suppress("unused") private val allowDownload: Boolean,
    private val say: (String) -> Unit,
) {
    fun run() {
        say("ML Kit GenAI was removed from the build in card 26d; results are in docs/EXPERIMENT_LOG.md.")
    }
}
