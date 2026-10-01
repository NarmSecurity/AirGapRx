package com.airgaprx.core.normalise

import java.text.Normalizer

/** Text utilities shared by the parser and the normaliser. Deterministic and allocation-light. */
object Text {
    private val combiningMarks = Regex("\\p{M}+")
    private val apostrophes = Regex("['’ʼ`]")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val gluedDose = Regex("^\\d+([.,]\\d+)?(mg|mcg|ug|g|kg|iu|ml|l|x)$")
    private val number = Regex("^\\d+([.,]\\d+)?$")
    private val units = setOf(
        "mg", "mcg", "ug", "g", "kg", "iu", "ml", "l", "x", "microgram", "micrograms", "milligram",
        "milligrams", "gram", "grams", "unit", "units",
    )
    private val fillerWords = setOf(
        "tablet", "tablets", "tab", "tabs", "capsule", "capsules", "caps", "softgel", "softgels",
        "each", "per", "contains", "equivalent", "to", "of", "as", "and", "with", "the", "a", "film",
        "coated", "extract", "dry", "root", "leaf", "herb", "standardised", "standardized", "providing",
        "from", "eq", "total", "active", "ingredient", "ingredients",
    )

    /** Lower-case, strip accents and apostrophes, collapse everything else to single spaces. */
    fun normalise(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD).replace(combiningMarks, "")
        return decomposed.lowercase()
            .replace(apostrophes, "")
            .replace(nonAlnum, " ")
            .trim()
            .replace(Regex(" +"), " ")
    }

    fun tokens(raw: String): List<String> = normalise(raw).split(' ').filter { it.isNotEmpty() }

    fun isFiller(token: String): Boolean = token in fillerWords || token in units || number.matches(token)

    /**
     * Removes dose amounts and packaging words: "Calcium carbonate 1250 mg tablets" -> "calcium carbonate".
     * A bare number is only treated as a dose when a unit follows it, so "omega 3" and "vitamin b 12" survive.
     */
    fun stripDoses(raw: String): String {
        val toks = tokens(raw)
        val out = ArrayList<String>(toks.size)
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            when {
                gluedDose.matches(t) -> i += 1
                number.matches(t) && i + 1 < toks.size && toks[i + 1] in units -> i += 2
                t in fillerWords -> i += 1
                else -> { out += t; i += 1 }
            }
        }
        return out.joinToString(" ")
    }

    /** Optimal-string-alignment edit distance (Levenshtein plus adjacent transposition). */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prevPrev = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prevPrev[j - 2] + 1)
                }
                cur[j] = v
            }
            val t = prevPrev; prevPrev = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Similarity in [0, 1]: 1 - distance / longer length. */
    fun similarity(a: String, b: String): Double {
        val longer = maxOf(a.length, b.length)
        if (longer == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / longer
    }

    fun trigrams(s: String): Set<String> {
        val padded = "  $s "
        if (padded.length < 3) return emptySet()
        return (0..padded.length - 3).mapTo(HashSet()) { padded.substring(it, it + 3) }
    }
}
