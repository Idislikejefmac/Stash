package app.stash

import app.stash.domain.Age
import app.stash.domain.AnswerComposer
import app.stash.domain.NoteKind
import app.stash.domain.NoteView
import app.stash.domain.Searcher
import app.stash.domain.itemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val day = 24 * 3600_000L
    private val now = 1_000 * day

    private fun note(id: String, ageDays: Long, text: String, kind: NoteKind = NoteKind.STORED_ITEM,
                     item: String? = null, place: String? = null, person: String? = null, supersedes: String? = null) =
        NoteView(id, now - ageDays * day, text, kind, item, place, person, null, supersedes)

    private val glasses = note("1", 21, "I'm putting my reading glasses in the nightstand drawer", item = "reading glasses", place = "in the nightstand drawer")

    @Test fun ageWording() {
        assertEquals("just now", Age.describe(now, now))
        assertEquals("5 minutes ago", Age.describe(now - 5 * 60_000, now))
        assertEquals("1 hour ago", Age.describe(now - 3600_000, now))
        assertEquals("yesterday", Age.describe(now - day, now))
        assertEquals("5 days ago", Age.describe(now - 5 * day, now))
        assertEquals("3 weeks ago", Age.describe(now - 21 * day, now))
        assertEquals("4 months ago", Age.describe(now - 125 * day, now))
        assertEquals("2 years ago", Age.describe(now - 800 * day, now))
    }

    @Test fun glassesAnswerMentionsAge() {
        val m = Searcher.search("Where did I put my glasses?", listOf(glasses))
        assertEquals("Your reading glasses are in the nightstand drawer, noted 3 weeks ago.", AnswerComposer.compose(m, now))
    }

    @Test fun nothingMatchesSaysSo() {
        assertEquals(AnswerComposer.NOTHING, AnswerComposer.compose(Searcher.search("where is my kayak", listOf(glasses)), now))
        assertTrue(Searcher.search("where did i put", listOf(glasses)).isEmpty())
    }

    @Test fun latestNoteWinsAndOlderIsHistory() {
        val old = note("a", 30, "keys in the garage", item = "keys", place = "in the garage")
        val new = note("b", 2, "keys on the hook", item = "keys", place = "on the hook", supersedes = "a")
        val m = Searcher.search("where are my keys", listOf(old, new))
        assertEquals(1, m.size)
        assertEquals("b", m[0].note.id)
        assertEquals(listOf("a"), m[0].history.map { it.id })
        assertEquals(
            "Your keys are on the hook, noted 2 days ago. Before that: in the garage, 4 weeks ago.",
            AnswerComposer.compose(m, now),
        )
    }

    @Test fun supersededNoteNeverAnswersByItself() {
        val old = note("a", 30, "keys in the garage", item = "keys", place = "in the garage")
        val new = note("b", 2, "my wallet and keys in the bowl", item = "wallet", place = "in the bowl", supersedes = "a")
        assertEquals("b", Searcher.search("keys", listOf(old, new)).first().note.id)
    }

    @Test fun loanAnswer() {
        val loan = note("l", 14, "I loaned my guitar to Frank", NoteKind.LOAN, item = "guitar", person = "Frank")
        assertEquals("You loaned your guitar to Frank, noted 2 weeks ago.",
            AnswerComposer.compose(Searcher.search("who has my guitar", listOf(loan)), now))
    }

    @Test fun itemMatchBeatsTextMatch() {
        val a = note("a", 1, "thinking about glasses", NoteKind.OTHER)
        val b = note("b", 10, "put reading glasses away", item = "reading glasses", place = "in the case")
        assertEquals("b", Searcher.search("glasses", listOf(a, b)).first().note.id)
    }

    @Test fun plainTextNoteAnswer() {
        val n = note("w", 3, "The wifi password is hunter2", NoteKind.OTHER)
        assertEquals("You noted \"The wifi password is hunter2\", 3 days ago.", AnswerComposer.compose(Searcher.search("wifi password", listOf(n)), now))
    }

    @Test fun pluralAndSingularMatch() {
        assertEquals(itemKey("reading glasses"), itemKey("Reading Glass"))
        assertEquals(itemKey("my keys".removePrefix("my ")), itemKey("key"))
    }

    @Test fun pluralVerb() {
        val keys = note("k", 1, "k", item = "keys", place = "in the bowl")
        assertTrue(AnswerComposer.describe(keys, now).startsWith("Your keys are in the bowl"))
        val wallet = note("w", 1, "w", item = "wallet", place = "in the car")
        assertTrue(AnswerComposer.describe(wallet, now).startsWith("Your wallet is in the car"))
    }
}
