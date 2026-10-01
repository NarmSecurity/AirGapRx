package com.airgaprx.core.recognition

import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.OcrResult
import com.airgaprx.core.normalise.Lexicon
import com.airgaprx.core.normalise.Text

/**
 * Deterministic parser with two passes:
 *
 * 1. **Ingredient section**: text after headers such as "Active ingredients" or "Each tablet contains"
 *    is split into segments. Every segment becomes a mention even if it is not in the vocabulary,
 *    so an unknown active ingredient is escalated to the user instead of silently ignored (R3).
 * 2. **Lexicon spotting**: the whole label is scanned for known surface forms (longest match first,
 *    with conservative fuzzy matching to tolerate OCR errors).
 *
 * Excipient sections and common excipient phrases are removed first, so "magnesium stearate" or
 * "iron oxide" (a colourant) do not raise magnesium or iron interactions.
 */
class LexiconLabelParser(
    private val lexicon: Lexicon,
    private val fuzzySpotFloor: Double = 0.85,
) : LabelParser {

    override fun parse(ocr: OcrResult): List<Mention> {
        val lines = ocr.lines.map { it.text }.filter { it.isNotBlank() }
        val active = activeLines(lines)
        val mentions = ArrayList<Mention>()
        mentions += sectionMentions(active)
        mentions += spotMentions(active)
        val seen = HashSet<String>()
        return mentions.filter { seen.add(Text.normalise(it.text)) }
    }

    /**
     * Drops everything from an excipient header onwards and strips known excipient phrases.
     * Works on the original text (punctuation preserved) so segment separators survive.
     */
    internal fun activeLines(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        for (line in lines) {
            val stopAt = EXCIPIENT_HEADER_PATTERNS.mapNotNull { it.find(line)?.range?.first }.minOrNull()
            if (stopAt != null) {
                val before = line.substring(0, stopAt).trim()
                if (before.isNotEmpty()) out += removeExcipients(before)
                break
            }
            out += removeExcipients(line)
        }
        return out.filter { it.isNotBlank() }
    }

    private fun removeExcipients(line: String): String {
        var s = line
        for (p in EXCIPIENT_PHRASE_PATTERNS) s = p.replace(s, " ")
        return s.trim().replace(Regex(" +"), " ")
    }

    internal fun sectionMentions(lines: List<String>): List<Mention> {
        val out = ArrayList<Mention>()
        var i = 0
        while (i < lines.size) {
            val match = SECTION_HEADER_PATTERNS.firstNotNullOfOrNull { it.find(lines[i]) }
            if (match == null) { i++; continue }
            val segments = ArrayList<String>()
            segments += lines[i].substring(match.range.last + 1)
            var j = i + 1
            while (j < lines.size && j <= i + MAX_SECTION_LINES) {
                val n = Text.normalise(lines[j])
                if (STOP_HEADERS.any { n == it || n.startsWith("$it ") } ||
                    SECTION_HEADER_PATTERNS.any { it.containsMatchIn(lines[j]) }
                ) break
                segments += lines[j]
                j++
            }
            segments.flatMap { it.split(SEPARATORS) }
                .map { Text.stripDoses(it) }
                .filter { seg -> seg.length >= 3 && seg.any { it.isLetter() } && seg.split(' ').size <= 5 }
                .forEach { out += Mention(it, MentionSource.INGREDIENT_SECTION) }
            i = j
        }
        return out
    }

    internal fun spotMentions(lines: List<String>): List<Mention> {
        val out = ArrayList<Mention>()
        val maxN = lexicon.maxTermTokens
        for (line in lines) {
            val toks = Text.tokens(line)
            var i = 0
            while (i < toks.size) {
                val maxHere = minOf(maxN, toks.size - i)
                // Exact surface forms first, longest first; fuzzy only if nothing matches exactly,
                // and never across a filler word ("as calcium carbonate" must not fuzzy-match).
                var matched = (maxHere downTo 1).firstOrNull { n ->
                    lexicon.isExactTerm(toks.subList(i, i + n).joinToString(" "))
                } ?: 0
                if (matched == 0 && Text.isFiller(toks[i])) { i++; continue }
                if (matched == 0) {
                    matched = (minOf(2, maxHere) downTo 1).firstOrNull { n ->
                        val window = toks.subList(i, i + n)
                        val gram = window.joinToString(" ")
                        window.none { Text.isFiller(it) } && gram.length >= 6 && gram.any { it.isLetter() } &&
                            lexicon.fuzzyMatches(gram, minScore = fuzzySpotFloor, limit = 1).isNotEmpty()
                    } ?: 0
                }
                if (matched > 0) out += Mention(toks.subList(i, i + matched).joinToString(" "), MentionSource.LEXICON)
                i += maxOf(1, matched)
            }
        }
        return out
    }

    companion object {
        private const val MAX_SECTION_LINES = 8
        private val SEPARATORS = Regex("[,;:•·|/()\\[\\]]|\\s+and\\s+(?=[a-z])|\\s+\\+\\s+", RegexOption.IGNORE_CASE)

        val SECTION_HEADERS = listOf(
            "active ingredients", "active ingredient", "each tablet contains", "each capsule contains",
            "each softgel contains", "each caplet contains", "each 5 ml contains", "each dose contains",
            "each serve contains", "composition", "contains",
        )
        val STOP_HEADERS = listOf(
            "directions", "dosage", "dose", "warning", "warnings", "caution", "store", "storage",
            "keep out", "adults", "children", "use", "indications", "batch", "exp",
        )
        val EXCIPIENT_HEADERS = listOf(
            "inactive ingredients", "other ingredients", "excipients", "also contains", "non active",
            "nonactive", "non medicinal",
        )
        val EXCIPIENT_PHRASES = listOf(
            "magnesium stearate", "calcium stearate", "microcrystalline cellulose", "silicon dioxide",
            "colloidal silica", "croscarmellose sodium", "sodium starch glycolate", "titanium dioxide",
            "iron oxide", "iron oxide red", "iron oxide yellow", "iron oxide black", "lactose monohydrate",
            "maize starch", "pregelatinised starch", "hypromellose", "povidone", "stearic acid",
            "carnauba wax", "purified water", "dicalcium phosphate", "calcium hydrogen phosphate",
            "macrogol", "polyethylene glycol", "talc", "gelatin", "glycerol", "sorbitol", "shellac",
        )

        /** "other ingredients" -> /\bother\W+ingredients\b/i, tolerant of OCR punctuation and spacing. */
        private fun phrasePattern(phrase: String) =
            Regex("\\b" + phrase.split(' ').joinToString("\\W+") { Regex.escape(it) } + "\\b", RegexOption.IGNORE_CASE)

        private val SECTION_HEADER_PATTERNS = SECTION_HEADERS.map { phrasePattern(it) }
        private val EXCIPIENT_HEADER_PATTERNS = EXCIPIENT_HEADERS.map { phrasePattern(it) }
        private val EXCIPIENT_PHRASE_PATTERNS = EXCIPIENT_PHRASES.sortedByDescending { it.length }.map { phrasePattern(it) }
    }
}
