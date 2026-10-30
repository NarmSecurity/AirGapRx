package com.airgaprx.core.recognition

import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.OcrResult
import com.airgaprx.core.normalise.Lexicon
import com.airgaprx.core.normalise.Text

/**
 * Deterministic, section-aware label parser.
 *
 * The label is split into segments at section headers. Each segment has a mode:
 *  - **ACTIVE**: front-of-pack text and active-ingredient panels. Scanned for known names
 *    (lexicon spotting). Inside an active-ingredient panel ("Each tablet contains…") every list
 *    item also becomes a mention, even if it is not in the vocabulary, so unknown actives are
 *    escalated to the user (R3) instead of being silently ignored.
 *  - **EXCIPIENT** ("Other ingredients", "Excipients"): skipped, so "magnesium stearate" does not
 *    raise a magnesium interaction.
 *  - **WARNING** ("Do not take with warfarin", "Interactions"): skipped, because warnings name
 *    *other* medicines that are not in the product.
 *  - **OTHER** ("Directions", "Storage"): skipped.
 *
 * Modes are not permanent. A later header switches mode again, and every new OCR text block
 * starts back in ACTIVE, so an active panel that OCR reads *after* an excipient list or a warning
 * is still parsed. The one exception is an unfinished warning sentence ("Do not take with"): OCR
 * can split a warning before the medicine it names. A block starting with "or" or "and" also
 * continues the warning, even after a sentence end. Otherwise the new block starts in ACTIVE.
 * The design choice is to fail visibly: when in doubt, text is kept and shown on the Review screen
 * rather than dropped.
 */
class LexiconLabelParser(
    private val lexicon: Lexicon,
    private val fuzzySpotFloor: Double = 0.85,
) : LabelParser {

    enum class Mode { ACTIVE, EXCIPIENT, WARNING, OTHER }

    /** A piece of label text with its section mode. [inPanel] is true inside an active-ingredient panel. */
    data class Segment(val text: String, val mode: Mode, val inPanel: Boolean, val declaredActive: Boolean = false)

    override fun parse(ocr: OcrResult): List<Mention> {
        val segs = segments(ocr)
        val active = segs.filter { it.mode == Mode.ACTIVE }.map {
            if (it.declaredActive) it else it.copy(text = removeExcipients(it.text))
        }
        val mentions = ArrayList<Mention>()
        mentions += panelMentions(active.filter { it.inPanel })
        mentions += spotMentions(active.map { it.text })
        val seen = HashSet<String>()
        return mentions.filter { seen.add(Text.normalise(it.text)) }
    }

    private data class Hit(
        val start: Int,
        val end: Int,
        val mode: Mode,
        val opensPanel: Boolean,
        /** Only punctuation/space before the header on its line (a real section title, not running text). */
        val atLineStart: Boolean = false,
        /** Common words ("contains", "ingredients") that only count as headers at the start of a line. */
        val generic: Boolean = false,
        /** "contains" may be the verb continuing a warning, not a section title. */
        val warningVerb: Boolean = false,
        /** "Active ingredients": the label declares what follows as actives, fillers included. */
        val declaresActives: Boolean = false,
    )

    /** Splits OCR lines into moded segments. Public for tests and for the evaluation report. */
    fun segments(ocr: OcrResult): List<Segment> {
        val out = ArrayList<Segment>()
        var block = Int.MIN_VALUE
        var mode = Mode.ACTIVE
        var inPanel = false
        var declaredActive = false
        var panelLines = 0
        var previous = ""
        for (line in ocr.lines) {
            val text = line.text
            if (text.isBlank()) continue
            if (line.block != block) {
                block = line.block
                // A new OCR block is a layout boundary: skipped modes end here, but an open
                // ingredient panel may continue into it, and so may a warning whose sentence is
                // unfinished ("Do not take with" / "warfarin") or explicitly continued
                // ("Do not take with warfarin." / "Or aspirin.").
                val continuingWarning = mode == Mode.WARNING &&
                    (!SENTENCE_END.containsMatchIn(previous) || WARNING_CONTINUATION.containsMatchIn(text))
                if (mode != Mode.ACTIVE && !continuingWarning) { mode = Mode.ACTIVE; inPanel = false; declaredActive = false }
            }
            if (inPanel && ++panelLines > MAX_PANEL_LINES) { inPanel = false; declaredActive = false }

            var cursor = 0
            for (hit in headerHits(text)) {
                // A panel header inside running text is not a section title: "Do not take with a
                // product that contains warfarin" must stay in WARNING mode. Generic words only
                // open a panel at the start of a line; no panel header re-opens ACTIVE mid-warning.
                if (hit.opensPanel && mode == Mode.WARNING && hit.warningVerb && !SENTENCE_END.containsMatchIn(previous)) continue
                if (hit.opensPanel && !hit.atLineStart && (hit.generic || mode == Mode.WARNING)) continue
                if (hit.start > cursor) out += Segment(text.substring(cursor, hit.start), mode, inPanel && mode == Mode.ACTIVE, declaredActive)
                mode = hit.mode
                inPanel = hit.opensPanel
                // Only an "Active ingredients" heading turns the filler filter off. "Each tablet
                // contains" lists the whole composition, so magnesium stearate there is a filler.
                declaredActive = hit.opensPanel && (hit.declaresActives || declaredActive)
                if (hit.opensPanel) panelLines = 0
                cursor = hit.end
            }
            if (cursor < text.length) out += Segment(text.substring(cursor), mode, inPanel && mode == Mode.ACTIVE, declaredActive)
            previous = text
        }
        return out.filter { it.text.isNotBlank() }
    }

    /** All header matches in a line; overlapping matches resolve to the longest ("each dose contains" beats "dose"). */
    private fun headerHits(line: String): List<Hit> {
        val all = ArrayList<Hit>()
        fun atStart(i: Int) = line.substring(0, i).none { it.isLetterOrDigit() }
        PANEL_HEADERS.zip(PANEL_HEADER_PATTERNS).forEach { (h, p) ->
            p.findAll(line).forEach { all += Hit(it.range.first, it.range.last + 1, Mode.ACTIVE, true, atStart(it.range.first), h in GENERIC_PANEL_HEADERS, h == "contains", h in ACTIVE_DECLARATION_HEADERS) }
        }
        EXCIPIENT_HEADER_PATTERNS.forEach { p -> p.findAll(line).forEach { all += Hit(it.range.first, it.range.last + 1, Mode.EXCIPIENT, false) } }
        WARNING_HEADER_PATTERNS.forEach { p -> p.findAll(line).forEach { all += Hit(it.range.first, it.range.last + 1, Mode.WARNING, false) } }
        // Section titles such as "Directions" only count at the start of a line.
        OTHER_HEADER_PATTERNS.forEach { p -> p.find(line)?.let { if (line.substring(0, it.range.first).isBlank()) all += Hit(it.range.first, it.range.last + 1, Mode.OTHER, false) } }
        val chosen = ArrayList<Hit>()
        for (h in all.sortedWith(compareByDescending<Hit> { it.end - it.start }.thenBy { it.start })) {
            if (chosen.none { it.start < h.end && h.start < it.end }) chosen += h
        }
        return chosen.sortedBy { it.start }
    }

    private fun removeExcipients(text: String): String {
        var s = text
        for (p in EXCIPIENT_PHRASE_PATTERNS) s = p.replace(s, " ")
        return s.trim().replace(Regex(" +"), " ")
    }

    private fun panelMentions(panel: List<Segment>): List<Mention> =
        panel.flatMap { Text.withoutDoseAmounts(it.text).replace(STRENGTH_FIELD_LABEL, "$1").split(SEPARATORS) }
            .map { Text.stripDoses(it) }
            .filter { seg -> seg.length >= 3 && seg.any { it.isLetter() } && seg.split(' ').size <= 5 }
            .map { Mention(it, MentionSource.INGREDIENT_SECTION) }

    internal fun spotMentions(lines: List<String>): List<Mention> {
        val out = ArrayList<Mention>()
        val maxN = lexicon.maxTermTokens
        for (line in lines) {
            val toks = Text.tokens(Text.withoutDoseAmounts(line))
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
        /** Safety valve for a panel header that OCR never closes; long panels (20+ lines) are rare. */
        private const val MAX_PANEL_LINES = 30
        /** Whole conjunctions, optionally preceded by a bullet or opening quote/bracket. */
        private val WARNING_CONTINUATION = Regex("""^\s*(?:[•·*-]\s*)?["'“‘(]?\s*(?:or|and)\b""", RegexOption.IGNORE_CASE)
        /** Remove the field label, retaining its value and any following unknown active. */
        private val STRENGTH_FIELD_LABEL = Regex("""(^|[,;•·|])\s*strength\s*:""", RegexOption.IGNORE_CASE)
        /** A line that ends a sentence, allowing closing brackets or quotes after the mark. */
        private val SENTENCE_END = Regex("[.!?][)\\]\"'”’]*\\s*$")
        private val SEPARATORS = Regex("[,;:•·|/()\\[\\]]|\\s+and\\s+(?=[a-z])|\\s+\\+\\s+", RegexOption.IGNORE_CASE)

        val PANEL_HEADERS = listOf(
            "active ingredients", "active ingredient", "each tablet contains", "each capsule contains",
            "each softgel contains", "each caplet contains", "each 5 ml contains", "each dose contains",
            "each serve contains", "each sachet contains", "composition", "ingredients", "contains",
        )
        /** Panel headers that are also everyday words; honoured only at the start of a line. */
        val GENERIC_PANEL_HEADERS = setOf("contains", "ingredients", "composition")
        /** Headers under which the excipient phrase filter is not applied. */
        val ACTIVE_DECLARATION_HEADERS = setOf("active ingredients", "active ingredient")
        val EXCIPIENT_HEADERS = listOf(
            "inactive ingredients", "other ingredients", "excipients", "also contains", "non active ingredients",
            "nonactive ingredients", "non medicinal ingredients",
        )
        val WARNING_HEADERS = listOf(
            "warning", "warnings", "caution", "precautions", "do not take", "do not use", "interactions",
            "consult your doctor", "talk to your doctor", "ask your doctor", "consult your pharmacist",
            "if you are taking", "side effects", "not suitable for",
        )
        val OTHER_HEADERS = listOf(
            "directions", "directions for use", "dosage", "dose", "storage", "store below", "keep out of reach",
            "indications", "uses", "batch", "exp", "expiry",
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

        private val PANEL_HEADER_PATTERNS = PANEL_HEADERS.map { phrasePattern(it) }
        private val EXCIPIENT_HEADER_PATTERNS = EXCIPIENT_HEADERS.map { phrasePattern(it) }
        private val WARNING_HEADER_PATTERNS = WARNING_HEADERS.map { phrasePattern(it) }
        private val OTHER_HEADER_PATTERNS = OTHER_HEADERS.map { phrasePattern(it) }
        private val EXCIPIENT_PHRASE_PATTERNS = EXCIPIENT_PHRASES.sortedByDescending { it.length }.map { phrasePattern(it) }
    }
}
