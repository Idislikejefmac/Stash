package app.stash.capture

/**
 * "Has [intervalMs] passed since this last ran?", true the first time it is asked. Pure, so it can be tested.
 *
 * 2026-11-16: the capture screen's ticker kept its last-run times in `Long.MIN_VALUE` and asked
 * `now - last >= interval`; the subtraction overflows to a large negative number for any clock reading, so the
 * words-after-save watch (card 26g item 11) and the "Add a picture?" offer on the Saved screen (card 16g) never ran.
 */
class Every(private val intervalMs: Long) {
    private var last: Long? = null

    fun due(now: Long): Boolean {
        val l = last
        if (l != null && now - l < intervalMs) return false
        last = now
        return true
    }
}
