package app.stash.domain

/**
 * The register of every kind of request that can leave the phone (docs/cards/13 section C3). The only code that may
 * make one is in `app.stash.model.hosted`, and it must name its kind. Card 23 draws "What leaves my phone" from this.
 */
enum class Outbound(
    val capability: Capability,
    /** Where it goes, in plain words. */
    val destination: String,
    /** What is sent, in plain words. */
    val sends: String,
) {
    EXTRACT_NOTE(
        Capability.HOSTED_MODEL, "The hosted model you chose (Anthropic)",
        "The words of one note and your word list. Never a Private note, never audio, places or times.",
    ),
    ANSWER_QUESTION(
        Capability.HOSTED_MODEL, "The hosted model you chose (Anthropic)",
        "Your question and the words of up to three matching notes. Never a Private note, never audio, places or times.",
    ),
}

/** Thrown before any connection is opened when a kind of request is not allowed right now. Carries no words. */
class OutboundRefused(val kind: Outbound) : IllegalStateException("outbound refused: ${kind.name}")

/** Decides whether a request may be made, and counts it if so. Throws [OutboundRefused] when it may not. */
fun interface OutboundGate {
    fun permit(kind: Outbound)
}

/** How often one kind went out: this month and when last. */
data class OutboundStats(val month: String, val countThisMonth: Int, val lastAt: Long?)
