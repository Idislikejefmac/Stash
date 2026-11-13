package app.stash.domain

import app.stash.data.Confidence
import app.stash.data.Direction
import app.stash.data.SubjectType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * Extraction schema version 1 (docs/cards/03-extraction-core.md). The rules extractor and (card 04) the hosted
 * model both produce this shape. Pure Kotlin plus org.json; no Android classes.
 */

const val EXTRACTION_SCHEMA_VERSION = 1

/** What he said about time, as data. The phone resolves it to dates ([DateResolver]); the model is never told the date. */
data class DateExpr(
    val said: String,
    /** today, tonight, tomorrow, yesterday, this_weekend, next_weekend, this_week, next_week, end_of_month, soon, someday. */
    val named: String? = null,
    /** mon..sun */
    val weekday: String? = null,
    /** this, next, last */
    val which: String? = null,
    val n: Int? = null,
    /** day, week, month */
    val unit: String? = null,
    val month: Int? = null,
    val day: Int? = null,
    val year: Int? = null,
    /** on, by, around */
    val strictness: String? = null,
)

/** One value as spoken. [value] is his words or digits, never corrected; [number] only if there is one. */
data class ExtractedReading(
    val metric: String,
    val value: String,
    val number: Double? = null,
    val unit: String? = null,
)

data class ExtractedRecord(
    val kind: RecordKind,
    val title: String,
    val quote: String,
    val subject: String? = null,
    val subjectType: SubjectType? = null,
    val item: String? = null,
    /** With its preposition: "in the nightstand drawer". */
    val where: String? = null,
    val person: String? = null,
    /** LENT, BORROWED or RETURNED only. */
    val direction: Direction? = null,
    /** Task: where it is to be done. */
    val atPlace: String? = null,
    /** Any kind: where it happened, or where he says he is. */
    val place: String? = null,
    /** Where within the place, in his words. */
    val spot: String? = null,
    val photoHint: Boolean? = null,
    val perishable: Boolean? = null,
    /** When it happened, if he said. */
    val whenExpr: DateExpr? = null,
    /** Task. */
    val due: DateExpr? = null,
    /** Label of an open task this finishes, e.g. "T3". */
    val closes: String? = null,
    val readings: List<ExtractedReading> = emptyList(),
    val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val sure: Confidence = Confidence.MEDIUM,
)

/** A record after [ExtractionChecks]: the same record or its downgrade, and why. */
data class CheckedRecord(val record: ExtractedRecord, val attention: String? = null)

/** Strict reading and writing of the extraction shape. A reply that is not this shape gives null; it never throws. */
object ExtractionJson {
    fun serialise(records: List<ExtractedRecord>): String {
        val arr = JSONArray()
        for (r in records) arr.put(record(r))
        return JSONObject().put("records", arr).toString()
    }

    fun parse(json: String): List<ExtractedRecord>? {
        return try {
            val root = JSONObject(stripFence(json))
            val arr = root.optJSONArray("records") ?: return null
            val out = mutableListOf<ExtractedRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return null
                out += parseRecord(o) ?: return null
            }
            out
        } catch (e: JSONException) {
            null
        }
    }

    private fun stripFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    // --- writing ---

    private fun record(r: ExtractedRecord): JSONObject = JSONObject().apply {
        put("kind", r.kind.name.lowercase())
        put("title", r.title)
        put("quote", r.quote)
        put("subject", r.subject ?: JSONObject.NULL)
        put("subject_type", r.subjectType?.name?.lowercase() ?: JSONObject.NULL)
        put("item", r.item ?: JSONObject.NULL)
        put("where", r.where ?: JSONObject.NULL)
        put("person", r.person ?: JSONObject.NULL)
        put("direction", r.direction?.name?.lowercase() ?: JSONObject.NULL)
        put("at_place", r.atPlace ?: JSONObject.NULL)
        put("place", r.place ?: JSONObject.NULL)
        put("spot", r.spot ?: JSONObject.NULL)
        put("photo_hint", r.photoHint ?: JSONObject.NULL)
        put("perishable", r.perishable ?: JSONObject.NULL)
        put("when", r.whenExpr?.let(::dateExpr) ?: JSONObject.NULL)
        put("due", r.due?.let(::dateExpr) ?: JSONObject.NULL)
        put("closes", r.closes ?: JSONObject.NULL)
        put("readings", JSONArray().also { a ->
            for (x in r.readings) a.put(
                JSONObject().put("metric", x.metric).put("value", x.value)
                    .put("number", x.number ?: JSONObject.NULL).put("unit", x.unit ?: JSONObject.NULL),
            )
        })
        put("aliases", JSONArray(r.aliases))
        put("tags", JSONArray(r.tags))
        put("sure", r.sure.name.lowercase())
    }

    private fun dateExpr(d: DateExpr): JSONObject = JSONObject().apply {
        put("said", d.said)
        put("named", d.named ?: JSONObject.NULL)
        put("weekday", d.weekday ?: JSONObject.NULL)
        put("which", d.which ?: JSONObject.NULL)
        put("n", d.n ?: JSONObject.NULL)
        put("unit", d.unit ?: JSONObject.NULL)
        put("month", d.month ?: JSONObject.NULL)
        put("day", d.day ?: JSONObject.NULL)
        put("year", d.year ?: JSONObject.NULL)
        put("strictness", d.strictness ?: JSONObject.NULL)
    }

    // --- reading ---

    /** Null when [o] lacks a title or quote, or has a malformed readings list. An unknown or missing kind becomes OTHER. */
    private fun parseRecord(o: JSONObject): ExtractedRecord? {
        // Present as strings, but may be empty: the checks (not the parser) decide what an empty title or quote means.
        val title = (o.opt("title") as? String)?.trim() ?: return null
        val quote = (o.opt("quote") as? String)?.trim() ?: return null
        val readingsArr = o.opt("readings")
        val readings = mutableListOf<ExtractedReading>()
        if (readingsArr != null && readingsArr != JSONObject.NULL) {
            val a = readingsArr as? JSONArray ?: return null
            for (i in 0 until a.length()) {
                val ro = a.optJSONObject(i) ?: return null
                val metric = str(ro, "metric") ?: return null
                val value = str(ro, "value") ?: return null
                readings += ExtractedReading(metric, value, num(ro, "number"), str(ro, "unit"))
            }
        }
        val when_ = o.opt("when")
        val due = o.opt("due")
        return ExtractedRecord(
            kind = kindOf(str(o, "kind")),
            title = title,
            quote = quote,
            subject = str(o, "subject"),
            subjectType = str(o, "subject_type")?.let { t -> SubjectType.entries.firstOrNull { it.name.equals(t, true) } },
            item = str(o, "item"),
            where = str(o, "where"),
            person = str(o, "person"),
            direction = str(o, "direction")?.let { d ->
                listOf(Direction.LENT, Direction.BORROWED, Direction.RETURNED).firstOrNull { it.name.equals(d, true) }
            },
            atPlace = str(o, "at_place"),
            place = str(o, "place"),
            spot = str(o, "spot"),
            photoHint = bool(o, "photo_hint"),
            perishable = bool(o, "perishable"),
            whenExpr = (when_ as? JSONObject)?.let { dateExprOf(it) ?: return null },
            due = (due as? JSONObject)?.let { dateExprOf(it) ?: return null },
            closes = str(o, "closes"),
            readings = readings,
            aliases = strings(o, "aliases") ?: return null,
            tags = strings(o, "tags") ?: return null,
            sure = when (str(o, "sure")?.lowercase()) {
                "high" -> Confidence.HIGH
                "low" -> Confidence.LOW
                else -> Confidence.MEDIUM
            },
        )
    }

    private fun dateExprOf(o: JSONObject): DateExpr? {
        val said = str(o, "said") ?: return null
        return DateExpr(
            said = said,
            named = str(o, "named")?.lowercase(),
            weekday = str(o, "weekday")?.lowercase(),
            which = str(o, "which")?.lowercase(),
            n = num(o, "n")?.toInt(),
            unit = str(o, "unit")?.lowercase(),
            month = num(o, "month")?.toInt(),
            day = num(o, "day")?.toInt(),
            year = num(o, "year")?.toInt(),
            strictness = str(o, "strictness")?.lowercase(),
        )
    }

    private fun kindOf(s: String?): RecordKind =
        RecordKind.entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: RecordKind.OTHER

    /** A string field; JSON null, the word "null" and blank all mean absent. Non-string values are absent too. */
    private fun str(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        if (v !is String) return null
        val t = v.trim()
        return if (t.isEmpty() || t.equals("null", ignoreCase = true)) null else t
    }

    private fun num(o: JSONObject, key: String): Double? = when (val v = o.opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun bool(o: JSONObject, key: String): Boolean? = o.opt(key) as? Boolean

    /** A list of strings; absent or null is empty; a non-array is a malformed reply (null). */
    private fun strings(o: JSONObject, key: String): List<String>? {
        val v = o.opt(key)
        if (v == null || v == JSONObject.NULL) return emptyList()
        val a = v as? JSONArray ?: return null
        return (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }
}
