package com.airgaprx.core.normalise

import com.airgaprx.core.kb.KnowledgeBase
import com.airgaprx.core.model.Candidate

/**
 * In-memory index over every surface form in the knowledge base.
 *
 * Design note (reflect on in report): M1 planned SQLite FTS5 for ingredient search, but the
 * Android framework SQLite build does not guarantee FTS5. The vocabulary is small (thousands of
 * terms), so an in-memory trigram index gives fuzzy matching that is faster, testable on the JVM,
 * and independent of the platform SQLite build.
 */
class Lexicon(private val kb: KnowledgeBase) {
    private class Entry(val norm: String, val ingredientId: String, val grams: Set<String>)

    private val entries: List<Entry>
    private val exact: Map<String, Set<String>>
    private val gramIndex: Map<String, IntArray>

    /** Longest term length in tokens; the parser only needs to try n-grams up to this length. */
    val maxTermTokens: Int

    init {
        val list = kb.terms().mapNotNull { t ->
            val norm = Text.normalise(t.term)
            if (norm.isEmpty() || kb.ingredient(t.ingredientId) == null) null
            else Entry(norm, t.ingredientId, Text.trigrams(norm))
        }.distinctBy { it.norm to it.ingredientId }
        entries = list
        exact = list.groupBy({ it.norm }, { it.ingredientId }).mapValues { it.value.toSet() }
        val idx = HashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, e -> e.grams.forEach { g -> idx.getOrPut(g) { ArrayList() }.add(i) } }
        gramIndex = idx.mapValues { it.value.toIntArray() }
        maxTermTokens = list.maxOfOrNull { it.norm.count { c -> c == ' ' } + 1 } ?: 1
    }

    fun isExactTerm(norm: String): Boolean = norm in exact

    /** Exact surface-form hits, score 1.0. */
    fun exactMatches(norm: String): List<Candidate> =
        exact[norm].orEmpty().mapNotNull { id -> kb.ingredient(id)?.let { Candidate(it, 1.0, norm) } }

    /**
     * Fuzzy matches by trigram retrieval then edit-distance rescoring. Short strings (< 4 chars)
     * are exact-only, because one edit on a 3-letter word is too permissive for a safety tool.
     */
    fun fuzzyMatches(norm: String, minScore: Double = 0.75, limit: Int = 5): List<Candidate> {
        if (norm.length < 4) return emptyList()
        val qGrams = Text.trigrams(norm)
        val shared = HashMap<Int, Int>()
        for (g in qGrams) gramIndex[g]?.forEach { shared.merge(it, 1, Int::plus) }
        val minShared = maxOf(1, (qGrams.size * 0.3).toInt())
        val best = HashMap<String, Candidate>()
        shared.entries.asSequence()
            .filter { it.value >= minShared }
            .sortedByDescending { it.value }
            .take(60)
            .forEach { (i, _) ->
                val e = entries[i]
                if (kotlin.math.abs(e.norm.length - norm.length) > maxOf(3, norm.length / 3)) return@forEach
                val s = Text.similarity(norm, e.norm)
                if (s >= minScore) {
                    val ing = kb.ingredient(e.ingredientId) ?: return@forEach
                    val prev = best[ing.id]
                    if (prev == null || s > prev.score) best[ing.id] = Candidate(ing, s, e.norm)
                }
            }
        return best.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.ingredient.name }).take(limit)
    }

    /** Exact matches if any, otherwise fuzzy matches, best first, one per ingredient. */
    fun lookup(raw: String, minScore: Double = 0.75, limit: Int = 5): List<Candidate> {
        val norm = Text.normalise(raw)
        if (norm.isEmpty()) return emptyList()
        val ex = exactMatches(norm)
        if (ex.isNotEmpty()) return ex.sortedBy { it.ingredient.name }
        return fuzzyMatches(norm, minScore, limit)
    }

    /**
     * Search for manual entry ("Add by name", "Add a missing ingredient"): exact matches, then terms
     * with a word that starts with what was typed ("para" finds Paracetamol, "wort" St John's wort),
     * then fuzzy matches for typos. Prefix completion stays out of [lookup], which the normaliser uses
     * on label text: a fragment read from a label must not be completed into a guess. Here the user
     * picks the result themselves.
     */
    fun search(raw: String, limit: Int = 8): List<Candidate> {
        val norm = Text.normalise(raw)
        if (norm.isEmpty()) return emptyList()
        val found = LinkedHashMap<String, Candidate>()
        fun addAll(cs: List<Candidate>) = cs.forEach { found.putIfAbsent(it.ingredient.id, it) }
        addAll(exactMatches(norm).sortedBy { it.ingredient.name })
        if (norm.length >= MIN_PREFIX) addAll(prefixMatches(norm))
        addAll(fuzzyMatches(norm, minScore = 0.6, limit = limit))
        return found.values.take(limit)
    }

    /** Terms with a word starting with [norm], best first: the more of the term typed, the higher. */
    private fun prefixMatches(norm: String): List<Candidate> {
        val best = HashMap<String, Candidate>()
        for (e in entries) {
            if (!(e.norm.startsWith(norm) || e.norm.contains(" $norm"))) continue
            val ing = kb.ingredient(e.ingredientId) ?: continue
            val s = norm.length.toDouble() / e.norm.length
            val prev = best[ing.id]
            if (prev == null || s > prev.score) best[ing.id] = Candidate(ing, s, e.norm)
        }
        return best.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.ingredient.name })
    }

    private companion object {
        /** Two letters match too much of the vocabulary to be a useful completion. */
        const val MIN_PREFIX = 3
    }
}
