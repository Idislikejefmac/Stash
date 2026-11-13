package app.stash.domain

import app.stash.data.Ids
import app.stash.data.MetricEntity
import app.stash.data.SubjectEntity
import app.stash.data.SubjectType
import org.json.JSONArray

/** The outcome of looking a name up: the subject to link, whether it is new, and an existing near match to flag. */
data class ResolvedSubject(val subject: SubjectEntity, val created: Boolean, val sameAsId: String? = null)

/**
 * Finds or creates the subject a name stands for (docs/cards/03 item 14; DESIGN 4.5). Pure over the list it is given:
 * it never writes, and a subject created here is remembered so two mentions in one capture share it.
 */
class SubjectResolver(
    existing: List<SubjectEntity>,
    private val now: Long,
    private val newId: () -> String = Ids::new,
) {
    private val all = existing.toMutableList()
    private val byId = existing.associateBy { it.id }.toMutableMap()
    private val created = mutableListOf<SubjectEntity>()

    /** Subjects made by this resolver, in creation order. The caller writes them. */
    val newSubjects: List<SubjectEntity> get() = created.toList()

    /**
     * [type] decides what a new subject is. [sensitive] marks a subject made only from a sensitive capture; an
     * existing subject is never changed.
     */
    fun resolve(name: String, type: SubjectType, sensitive: Boolean): ResolvedSubject? {
        val display = displayName(name)
        val norm = normalise(name)
        if (norm.isEmpty()) return null

        val exact = all.firstOrNull { it.mergedIntoId == null && compatible(it.type, type) && matches(it, norm) }
        if (exact != null) return ResolvedSubject(follow(exact), created = false)

        // A subject that was merged into another still answers to its own name.
        val merged = all.firstOrNull { it.mergedIntoId != null && compatible(it.type, type) && matches(it, norm) }
        if (merged != null) return ResolvedSubject(follow(merged), created = false)

        val near = all.firstOrNull {
            it.mergedIntoId == null && compatible(it.type, type) && norm.length >= 5 && it.normName.length >= 5 &&
                Corrector.closeMatch(norm, it.normName)
        }
        val s = SubjectEntity(
            id = newId(), type = type, name = display, normName = norm, aliases = "[]", sensitive = sensitive,
            createdAt = now, updatedAt = now,
        )
        all += s
        byId[s.id] = s
        created += s
        return ResolvedSubject(s, created = true, sameAsId = near?.id)
    }

    private fun matches(s: SubjectEntity, norm: String): Boolean =
        s.normName == norm || SearchDocs.jsonList(s.aliases).any { normalise(it) == norm }

    private fun follow(s: SubjectEntity): SubjectEntity {
        var cur = s
        val seen = mutableSetOf(cur.id)
        while (cur.mergedIntoId != null) {
            cur = byId[cur.mergedIntoId] ?: break
            if (!seen.add(cur.id)) break
        }
        return cur
    }

    /** Items and things are one pool ("pool" may be said about an item); people and places stay apart. */
    private fun compatible(a: SubjectType, b: SubjectType): Boolean =
        a == b || (a in POOL && b in POOL)

    companion object {
        private val POOL = setOf(SubjectType.ITEM, SubjectType.THING)
        private val ARTICLE = Regex("^(?:my|the|a|an|our)(?:\\s+|$)", RegexOption.IGNORE_CASE)

        /** As said, trimmed, without a leading my/the/a/an/our. */
        fun displayName(name: String): String {
            var s = name.trim().trim(',', '.', ';', ':', '!', '?')
            while (ARTICLE.containsMatchIn(s)) s = s.replace(ARTICLE, "")
            return s.trim()
        }

        /** Lower case, no leading my/the/a/our, each word singular by [Searcher.stem]. */
        fun normalise(name: String): String =
            Searcher.tokens(displayName(name)).map(Searcher::stem).joinToString(" ")

        fun aliasesJson(list: List<String>): String = JSONArray(list).toString()
    }
}

/** Finds the metric a reading belongs to, within its subject, by normalised label; creates one when there is none. */
class MetricResolver(existing: List<MetricEntity>, private val now: Long, private val newId: () -> String = Ids::new) {
    private val all = existing.toMutableList()
    private val created = mutableListOf<MetricEntity>()

    val newMetrics: List<MetricEntity> get() = created.toList()

    fun resolve(subjectId: String?, label: String, unit: String?): MetricEntity {
        val key = keyOf(label)
        val found = all.firstOrNull { it.subjectId == subjectId && (it.key == key || SearchDocs.jsonList(it.aliases).any { a -> keyOf(a) == key }) }
        if (found != null) return found
        val m = MetricEntity(
            id = newId(), subjectId = subjectId, key = key, label = label.trim(), aliases = "[]", unit = unit,
            createdAt = now, updatedAt = now,
        )
        all += m
        created += m
        return m
    }

    companion object {
        /** "pH", "ph" and "P H" stay different, but case and spacing do not make two metrics. */
        fun keyOf(label: String): String = label.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
