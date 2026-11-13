package app.stash

import app.stash.domain.NoteKind
import app.stash.domain.Structured
import app.stash.model.hosted.AnthropicJson
import app.stash.model.hosted.HostedAnswerer
import app.stash.model.hosted.HostedStructurer
import app.stash.model.rules.RulesStructurer
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesStructurerTest {
    private val r = RulesStructurer()

    @Test fun puttingInto() = assertEquals(
        Structured(NoteKind.STORED_ITEM, "reading glasses", "in the nightstand drawer"),
        r.parse("I'm putting my reading glasses in the nightstand drawer."),
    )
    @Test fun putOn() = assertEquals(
        Structured(NoteKind.STORED_ITEM, "keys", "on the hook by the door"),
        r.parse("I put my keys on the hook by the door"),
    )
    @Test fun putAt() = assertEquals(
        Structured(NoteKind.STORED_ITEM, "spare tire", "in the frunk"),
        r.parse("I'm putting the spare tire in the frunk"),
    )
    @Test fun loanWithDate() = assertEquals(
        Structured(NoteKind.LOAN, "guitar", person = "Frank", eventDate = "Tuesday"),
        r.parse("I loaned my guitar to Frank on Tuesday"),
    )
    @Test fun loanWithoutDate() = assertEquals(
        Structured(NoteKind.LOAN, "drill", person = "Syd"),
        r.parse("I lent the drill to Syd"),
    )
    @Test fun borrowed() = assertEquals(
        Structured(NoteKind.LOAN, "ladder", person = "Frank"),
        r.parse("I borrowed a ladder from Frank"),
    )
    @Test fun rememberToBuyAt() = assertEquals(
        Structured(NoteKind.REMINDER, "milk", "at Costco"),
        r.parse("I need to remember to buy milk at Costco"),
    )
    @Test fun rememberToBuyNoPlace() = assertEquals(
        Structured(NoteKind.REMINDER, "milk"),
        r.parse("Remember to buy milk"),
    )
    @Test fun rememberOtherTask() = assertEquals(
        Structured(NoteKind.REMINDER, "call the dentist"),
        r.parse("I need to remember to call the dentist"),
    )
    @Test fun myXIsInY() = assertEquals(
        Structured(NoteKind.STORED_ITEM, "passport", "in the top drawer"),
        r.parse("My passport is in the top drawer"),
    )
    @Test fun otherText() = assertEquals(Structured(NoteKind.OTHER), r.parse("The wifi password is hunter2"))
    @Test fun meetingIsAtIsNotAStoredItem() = assertEquals(NoteKind.OTHER, r.parse("Meeting is at 3").kind)
    @Test fun emptyText() = assertEquals(NoteKind.OTHER, r.parse("").kind)
}

class HostedParsingTest {
    private fun wrap(inner: String): String =
        JSONObject().put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-haiku-5-5")
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", inner)))
            .put("stop_reason", "end_turn").toString()

    @Test fun goodResponse() {
        val body = wrap("""{"kind":"stored_item","item":"reading glasses","place":"in the nightstand drawer","person":null,"event_date":null}""")
        assertEquals(Structured(NoteKind.STORED_ITEM, "reading glasses", "in the nightstand drawer"), AnthropicJson.parseStructured(body))
    }
    @Test fun loanResponse() {
        val body = wrap("""{"kind":"loan","item":"guitar","place":null,"person":"Frank","event_date":"Tuesday"}""")
        assertEquals(Structured(NoteKind.LOAN, "guitar", null, "Frank", "Tuesday"), AnthropicJson.parseStructured(body))
    }
    @Test fun fencedJsonIsAccepted() {
        val body = wrap("```json\n{\"kind\":\"other\",\"item\":null,\"place\":null,\"person\":null,\"event_date\":null}\n```")
        assertEquals(Structured(NoteKind.OTHER), AnthropicJson.parseStructured(body))
    }
    @Test fun unknownKindRejected() = assertNull(AnthropicJson.parseStructured(wrap("""{"kind":"banana"}""")))
    @Test fun notJsonRejected() = assertNull(AnthropicJson.parseStructured(wrap("Sure! Here is the data.")))
    @Test fun garbageBodyRejected() = assertNull(AnthropicJson.parseStructured("<html>502</html>"))
    @Test fun errorBodyRejected() =
        assertNull(AnthropicJson.parseStructured("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
    @Test fun nullStringsBecomeNull() {
        val s = AnthropicJson.parseStructured(wrap("""{"kind":"reminder","item":"milk","place":"null","person":"","event_date":null}"""))
        assertEquals(Structured(NoteKind.REMINDER, "milk"), s)
    }

    @Test fun requestContainsOnlyTextAndWords() {
        val req = JSONObject(AnthropicJson.structureRequest("I put my keys in the bowl", listOf("Syd", "Frank")))
        assertEquals("claude-haiku-5-5", req.getString("model"))
        val user = req.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(user.contains("I put my keys in the bowl"))
        assertTrue(user.contains("Syd, Frank"))
        assertEquals(setOf("model", "max_tokens", "system", "messages"), req.keys().asSequence().toSet())
    }
    @Test fun answerRequestHasQuestionAndNoteTextOnly() {
        val req = AnthropicJson.answerRequest("where are my keys", listOf("I put my keys in the bowl"))
        assertTrue(req.contains("where are my keys"))
        assertTrue(req.contains("I put my keys in the bowl"))
    }

    @Test fun hostedStructurerParsesViaFakeTransport() = runTest {
        var seenHeaders: Map<String, String> = emptyMap()
        val h = HostedStructurer("sk-test") { _, _, headers, _ ->
            seenHeaders = headers
            wrap("""{"kind":"stored_item","item":"keys","place":"in the bowl","person":null,"event_date":null}""")
        }
        assertEquals(NoteKind.STORED_ITEM, h.structure("I put my keys in the bowl", listOf("Syd"))?.kind)
        assertEquals("sk-test", seenHeaders["x-api-key"])
    }
    @Test fun hostedStructurerFailureGivesNull() = runTest {
        val h = HostedStructurer("sk-test") { _, _, _, _ -> throw java.io.IOException("offline") }
        assertNull(h.structure("anything", emptyList()))
    }
    @Test fun hostedAnswererPhrases() {
        val a = HostedAnswerer("k") { _, _, _, _ -> wrap("Your keys are in the bowl.") }
        assertEquals("Your keys are in the bowl.", a.phrase("where are my keys", listOf("I put my keys in the bowl")))
    }
    @Test fun hostedAnswererFailureGivesNull() {
        assertNull(HostedAnswerer("k") { _, _, _, _ -> throw java.io.IOException("x") }.phrase("q", listOf("n")))
        assertFalse(HostedAnswerer("k") { _, _, _, _ -> wrap("") }.phrase("q", listOf("n")) != null)
    }
}
