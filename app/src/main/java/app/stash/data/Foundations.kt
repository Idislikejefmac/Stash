package app.stash.data

import java.util.UUID

/** The one source of "now" for every write, so tests can fix it. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** New rows get random UUIDs. No code anywhere parses an id (docs/STORAGE.md rule 2). */
object Ids {
    fun new(): String = UUID.randomUUID().toString()
}

/**
 * Every row that can change after it was inserted has `created_at` and `updated_at`
 * (docs/STORAGE.md rule 6). Repositories set `updated_at` through [stamped] on every write.
 */
interface Stamped<T : Stamped<T>> {
    val updatedAt: Long
    fun withUpdatedAt(t: Long): T
}

fun <T : Stamped<T>> T.stamped(clock: Clock): T = withUpdatedAt(clock.now())
