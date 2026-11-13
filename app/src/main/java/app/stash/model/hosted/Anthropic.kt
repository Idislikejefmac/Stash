package app.stash.model.hosted

import app.stash.domain.NoteKind
import app.stash.domain.Outbound
import app.stash.domain.OutboundGate
import app.stash.domain.Structured
import app.stash.model.Structurer
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * The ONLY code in the app allowed to open a network connection (see README).
 * What is sent: a note's text (or a question plus the text of a few matching notes) and the
 * personal word list. Never audio, location or time.
 */

/** Minimal HTTP seam so the request/response handling can be tested with no network. */
fun interface HttpPoster {
    /** Returns the response body, or throws on any failure. Every request names its [kind] (docs/cards/13 C3). */
    fun post(kind: Outbound, url: String, headers: Map<String, String>, body: String): String
}

/**
 * Refuses a request whose kind is not allowed right now, before [inner] can open anything, and counts the ones it
 * lets through. This is the poster the app uses; [UrlConnectionPoster] alone is never handed to a feature.
 */
class GatedPoster(private val gate: OutboundGate, private val inner: HttpPoster) : HttpPoster {
    override fun post(kind: Outbound, url: String, headers: Map<String, String>, body: String): String {
        gate.permit(kind)
        return inner.post(kind, url, headers, body)
    }
}

class UrlConnectionPoster(private val timeoutMs: Int = 15_000) : HttpPoster {
    override fun post(kind: Outbound, url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }
}

/** Pure request building and response parsing (unit-tested against recorded samples). */
object AnthropicJson {
    const val URL = "https://api.anthropic.com/v1/messages"
    const val MODEL = "claude-haiku-5-5"

    fun headers(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "content-type" to "application/json",
    )

    private const val STRUCTURE_SYSTEM =
        "You extract fields from one short personal note. Reply with ONE JSON object and nothing else, " +
            "exactly these keys: kind (one of stored_item, loan, reminder, other), item (string or null), " +
            "place (string or null: where, including its preposition, e.g. \"in the nightstand drawer\"), " +
            "person (string or null), event_date (string or null: a date or day named in the note, copied as written). " +
            "Use only what the note says; use null when unsure. Drop leading 'my'/'the' from item. " +
            "Spell names as in the provided word list when they sound alike."

    fun structureRequest(text: String, vocabulary: List<String>): String {
        val user = "Word list: ${vocabulary.joinToString(", ")}\nNote: $text"
        return request(STRUCTURE_SYSTEM, user, 300)
    }

    private const val ANSWER_SYSTEM =
        "Answer the user's question in one or two short plain sentences using ONLY the notes given. " +
            "Each note is the owner's own words. Do not mention dates or times. If the notes do not answer it, say so. " +
            "Do not invent anything."

    fun answerRequest(question: String, noteTexts: List<String>): String {
        val user = "Question: $question\nNotes:\n" + noteTexts.joinToString("\n") { "- $it" }
        return request(ANSWER_SYSTEM, user, 200)
    }

    private fun request(system: String, user: String, maxTokens: Int): String = JSONObject()
        .put("model", MODEL)
        .put("max_tokens", maxTokens)
        .put("system", system)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        .toString()

    /** The model's text out of a Messages API response, or null. */
    fun responseText(body: String): String? = try {
        val content = JSONObject(body).getJSONArray("content")
        (0 until content.length()).map { content.getJSONObject(it) }
            .firstOrNull { it.optString("type") == "text" }?.getString("text")
    } catch (e: Exception) {
        null
    }

    /** Strict parse of the structuring reply. Null if it is not the expected JSON. */
    fun parseStructured(body: String): Structured? = try {
        val text = responseText(body)?.trim() ?: return null
        val json = JSONObject(stripFences(text))
        val kind = NoteKind.parse(json.optString("kind")) ?: return null
        fun s(key: String): String? =
            if (json.isNull(key)) null else json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        Structured(kind, s("item"), s("place"), s("person"), s("event_date"))
    } catch (e: Exception) {
        null
    }

    private fun stripFences(t: String): String {
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}

class HostedStructurer(
    private val apiKey: String,
    private val poster: HttpPoster,
) : Structurer {
    override suspend fun structure(text: String, vocabulary: List<String>): Structured? = try {
        val body = poster.post(Outbound.EXTRACT_NOTE, AnthropicJson.URL, AnthropicJson.headers(apiKey), AnthropicJson.structureRequest(text, vocabulary))
        AnthropicJson.parseStructured(body)
    } catch (e: Exception) {
        null
    }
}

/** Phrases an answer from the few matching notes' text. Null on any failure. */
class HostedAnswerer(
    private val apiKey: String,
    private val poster: HttpPoster,
) {
    fun phrase(question: String, noteTexts: List<String>): String? = try {
        val body = poster.post(Outbound.ANSWER_QUESTION, AnthropicJson.URL, AnthropicJson.headers(apiKey), AnthropicJson.answerRequest(question, noteTexts))
        AnthropicJson.responseText(body)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
