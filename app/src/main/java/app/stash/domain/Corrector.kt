package app.stash.domain

/**
 * Deterministic clean-up of a transcript using the owner's personal vocabulary.
 *
 * 1. "heard -> meant" rules: case-insensitive, whole word (or whole phrase) replacement.
 * 2. Conservative fuzzy match of single words against the word list: only a word that is
 *    exactly one substitution or swap away from a vocabulary word, same first letter,
 *    at least 5 letters, not an ordinary English word, and not just the vocabulary word
 *    with letters added or dropped ("Franks" stays "Franks").
 */
class Corrector(vocabulary: Collection<String>, rules: Map<String, String>) {
    private val vocab = vocabulary.map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }.distinct()
    private val vocabLower = vocab.map { it.lowercase() }.toSet()
    private val rules: List<Pair<String, String>> = rules.entries
        .map { it.key.trim().lowercase() to it.value.trim() }
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        .sortedByDescending { it.first.length }

    fun correct(text: String): String = fuzzy(applyRules(text))

    private fun applyRules(text: String): String {
        var out = text
        for ((heard, meant) in rules) {
            out = wholeWord(heard).replace(out) { m -> matchCase(m.value, meant) }
        }
        return out
    }

    private fun fuzzy(text: String): String {
        if (vocab.isEmpty()) return text
        return WORD.replace(text) { m ->
            val w = m.value
            val lw = w.lowercase()
            if (w.length < 5 || lw in vocabLower || lw in COMMON) return@replace w
            val hits = vocab.filter { v -> v.length >= 5 && closeMatch(lw, v.lowercase()) }
            val hit = hits.singleOrNull() // ambiguous (two words equally close): leave it alone
            if (hit == null) w else matchCase(w, hit)
        }
    }

    companion object {
        private val WORD = Regex("\\p{L}[\\p{L}']*")

        /** Whole word, case-insensitive. A trailing 's is allowed ("Sid's"); other apostrophes are not ("can't"). */
        fun wholeWord(w: String) = Regex(
            "(?<![\\p{L}\\p{N}'])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])(?!'(?!s(?![\\p{L}\\p{N}])))",
            RegexOption.IGNORE_CASE,
        )

        /** Keep the meant spelling, but follow the heard word's shouting or sentence-case. */
        fun matchCase(heard: String, meant: String): String = when {
            heard.length > 1 && heard.all { !it.isLetter() || it.isUpperCase() } -> meant.uppercase()
            heard.first().isUpperCase() && meant.first().isLowerCase() ->
                meant.replaceFirstChar { it.uppercase() }
            else -> meant
        }

        /** Same first letter, same length, differ by one substitution or one adjacent swap. */
        fun closeMatch(a: String, b: String): Boolean {
            if (a == b || a.length != b.length || a[0] != b[0]) return false
            val diff = a.indices.filter { a[it] != b[it] }
            return when (diff.size) {
                1 -> true
                2 -> diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]
                else -> false
            }
        }

        /** Words that must never be fuzzy-corrected into a name (close to a seed word, but ordinary). */
        private val COMMON = setOf(
            "tests", "tesla", "franks", "franc", "frank", "blank", "plank", "crank", "thank", "drank",
            "trunk", "truck", "frunk", "cost", "costs", "costly", "coast", "toast", "least", "later",
            "there", "where", "which", "about", "other", "their", "these", "those", "would", "could",
            "should", "think", "thing", "things", "place", "house", "table", "drawer", "closet", "garage",
            "kitchen", "bedroom", "pocket", "glasses", "wallet", "phone", "charger", "jacket", "shelf",
            "under", "inside", "behind", "bought", "borrow", "loaned", "lent", "remember",
        )
    }
}

/** Works out "heard -> meant" rules from a user's edit of a note's text. */
object CorrectionLearner {
    private val TOKEN = Regex("\\S+")
    private val EDGE = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")

    private fun clean(t: String) = t.replace(EDGE, "")

    /**
     * Compares the text before and after an edit. Trims words both texts share at the start and
     * end; if what is left is a short stretch (up to 3 words each side), that is one rule.
     * Capitalisation-only changes are ignored (the rule would be a no-op).
     */
    fun learn(before: String, after: String): List<Pair<String, String>> {
        val a = TOKEN.findAll(before).map { clean(it.value) }.filter { it.isNotEmpty() }.toList()
        val b = TOKEN.findAll(after).map { clean(it.value) }.filter { it.isNotEmpty() }.toList()
        var s = 0
        while (s < a.size && s < b.size && a[s] == b[s]) s++
        var ea = a.size
        var eb = b.size
        while (ea > s && eb > s && a[ea - 1] == b[eb - 1]) { ea--; eb-- }
        val heard = a.subList(s, ea).joinToString(" ")
        val meant = b.subList(s, eb).joinToString(" ")
        if (heard.isEmpty() || meant.isEmpty()) return emptyList()
        if (ea - s > 3 || eb - s > 3) return emptyList()
        if (heard.equals(meant, ignoreCase = true)) return emptyList()
        return listOf(heard.lowercase() to meant)
    }

    /** Replace every whole-word occurrence of [heard] in [text] with [meant]. */
    fun replaceWord(text: String, heard: String, meant: String): String {
        return Corrector.wholeWord(heard).replace(text) { Corrector.matchCase(it.value, meant) }
    }
}
