package app.stash.speech

import app.stash.audio.PauseSplitter

/*
 * Card 26e item 5: is Moonshine base the default model, or Moonshine tiny? The rule that turns the phone measurement
 * into one of the card's named outcomes (M1, M2, M3), and the constants the app runs with. Pure Kotlin.
 */

/** D81's memory guide for the app's process, and the owner's open question 5 ("a hard line or a guide?") as one constant. */
object MemoryGuide {
    /** Peak memory (total PSS) the app's process should stay at or under while writing out, in MB. */
    const val GUIDE_MB = 400

    /**
     * The owner's answer to the index's question 5. false: 400 MB is a guide, and "about 400 MB" allows
     * [GUIDE_TOLERANCE_MB] over it. true: a hard line, nothing over; and base missing it even on short notes makes
     * tiny the default (M3).
     */
    const val HARD_LINE = false

    /**
     * How far over the guide still counts as "about 400 MB". 10 MB (2.5%) keeps card 25's readings where the log put
     * them: 401 MB on an 8-second note in a background job "on the bar" (passes), 418 MB on the 28-second note "misses by 5%".
     */
    const val GUIDE_TOLERANCE_MB = 10

    /** M1's accuracy condition: cutting may cost at most this many more wrong words on the ramble than the uncut run. */
    const val MAX_EXTRA_WRONG_WORDS = 2

    fun limitMb(hardLine: Boolean = HARD_LINE): Int = if (hardLine) GUIDE_MB else GUIDE_MB + GUIDE_TOLERANCE_MB
}

enum class PackOutcome {
    /** Base stays inside the guide in the background job at every length, and cutting costs no more than two words. Base is the default. */
    M1,
    /** Base passes up to a length L and misses beyond it: base up to L, tiny for longer notes when its pack is installed. */
    M2,
    /** Base misses even on short notes (or the owner holds 400 MB as a hard line and it misses): tiny is the default, base an opt-in. */
    M3,
}

/** One measured run: a note of [audioMs] written out in a background job, with its peak total PSS. */
data class MemoryRun(val audioMs: Long, val peakMb: Int)

data class PackDecision(val outcome: PackOutcome, val baseUpToMs: Long?, val order: List<KnownPack>) {
    /** The policy the app would run with: set [DefaultPack] to these values. */
    val policy: PackPolicy
        get() = PackPolicy(order.map { it.id }, baseUpToMs, if (baseUpToMs != null) KnownModels.TINY.id else null)
}

object DefaultPackRule {
    /**
     * [baseInJob]: Moonshine base's peak memory in the background job at each measured length (the card's s10,
     * three times s10, three minutes; the shortest run stands for "short notes"). [extraWrongWordsWhenCut]: words
     * wrong on the ramble cut, less words wrong uncut.
     *
     * - The shortest run over the limit: M3.
     * - Every run within the limit and cutting costs at most [MemoryGuide.MAX_EXTRA_WRONG_WORDS]: M1.
     * - Some run over the limit: M2, with L the longest length up to which every run passed.
     * - Every run within the limit but cutting costs more words: M2 with L = [PauseSplitter.MAX_MS], so that base
     *   writes out only notes it never has to cut (the card names no outcome for this case; this is the nearest).
     */
    fun decide(baseInJob: List<MemoryRun>, extraWrongWordsWhenCut: Int, hardLine: Boolean = MemoryGuide.HARD_LINE): PackDecision {
        require(baseInJob.isNotEmpty()) { "no measurement" }
        val limit = MemoryGuide.limitMb(hardLine)
        val runs = baseInJob.sortedBy { it.audioMs }
        val baseFirst = listOf(KnownModels.BASE, KnownModels.TINY)
        if (runs.first().peakMb > limit) return PackDecision(PackOutcome.M3, null, listOf(KnownModels.TINY, KnownModels.BASE))
        val firstMiss = runs.indexOfFirst { it.peakMb > limit }
        if (firstMiss < 0) {
            return if (extraWrongWordsWhenCut <= MemoryGuide.MAX_EXTRA_WRONG_WORDS) PackDecision(PackOutcome.M1, null, baseFirst)
            else PackDecision(PackOutcome.M2, PauseSplitter.MAX_MS, baseFirst)
        }
        var l = runs[firstMiss - 1].audioMs
        if (extraWrongWordsWhenCut > MemoryGuide.MAX_EXTRA_WRONG_WORDS) l = minOf(l, PauseSplitter.MAX_MS)
        return PackDecision(PackOutcome.M2, l, baseFirst)
    }
}

/**
 * The values the app runs with. **Not yet measured** (card 26e item 4 needs the owner's phone): until then this is
 * D82's proposal, base first and tiny as the fallback when base does not fit in the free memory, with no length
 * limit, which is exactly how card 26d behaved. After the measurement, set [MEASURED] to the outcome
 * [DefaultPackRule.decide] gives and [DEFAULT_PACK_ORDER] and [BASE_UP_TO_MS] to its order and L.
 */
object DefaultPack {
    val MEASURED: PackOutcome? = null
    val DEFAULT_PACK_ORDER: List<KnownPack> = listOf(KnownModels.BASE, KnownModels.TINY)
    /** M2's L; null: no limit. */
    val BASE_UP_TO_MS: Long? = null

    val POLICY: PackPolicy = PackPolicy(DEFAULT_PACK_ORDER.map { it.id }, BASE_UP_TO_MS, if (BASE_UP_TO_MS != null) KnownModels.TINY.id else null)
}
