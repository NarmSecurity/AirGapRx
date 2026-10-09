package com.airgaprx.core.model

/**
 * Kind discriminator from the M1 data model (Figure 5). Lets prescription medicines and
 * supplements share one type without special-casing supplements (requirement R6).
 */
enum class IngredientKind { PRESCRIPTION, OTC, SUPPLEMENT, HERBAL, FOOD;

    val label: String
        get() = when (this) {
            PRESCRIPTION -> "Prescription"
            OTC -> "Over the counter"
            SUPPLEMENT -> "Supplement"
            HERBAL -> "Herbal"
            FOOD -> "Food"
        }
}

/** A canonical ingredient in the knowledge base. [id] is a stable slug of the canonical name. */
data class Ingredient(
    val id: String,
    val name: String,
    val kind: IngredientKind,
    val rxcui: String? = null,
)

/**
 * Severity levels follow DDInter's Major/Moderate/Minor/Unknown classification.
 * The order in which they are *ranked* is a policy decision owned by [com.airgaprx.core.reasoning.RankingPolicy],
 * not by this enum (M1 Section 4.1: ranking separated from retrieval).
 */
enum class Severity { MAJOR, MODERATE, MINOR, UNKNOWN;

    companion object {
        fun parse(raw: String): Severity = when (raw.trim().lowercase()) {
            "major", "severe", "high" -> MAJOR
            "moderate", "medium" -> MODERATE
            "minor", "low" -> MINOR
            else -> UNKNOWN
        }
    }
}

/** One interaction record as stored in the knowledge base. Ingredient ids are stored in sorted order. */
data class Interaction(
    val ingredientA: String,
    val ingredientB: String,
    val severity: Severity,
    val mechanism: String?,
    val management: String?,
    val source: String,
    val sourceRef: String?,
)

/** Kind of alert raised by the reasoning subsystem. */
enum class AlertType { INTERACTION, DUPLICATE_INGREDIENT }

/** A ranked, user-facing alert produced by [com.airgaprx.core.reasoning.SeverityRanker]. */
data class Alert(
    val type: AlertType,
    val first: Ingredient,
    val second: Ingredient,
    val severity: Severity,
    /** All knowledge-base records supporting this alert (may come from more than one source). */
    val evidence: List<Interaction>,
    /** True when policy says the alert must interrupt the user (R5). */
    val interruptive: Boolean = false,
)

/**
 * One line of OCR output with the engine's confidence in [0, 1] (null if the engine gave none).
 * [block] is the index of the OCR text block the line belongs to; blocks are layout boundaries
 * the parser uses to end a skipped section (see LexiconLabelParser).
 */
data class TextLine(val text: String, val confidence: Float?, val block: Int = 0)

/** Raw recogniser output for one captured frame (M1 OcrService output). */
data class OcrResult(val lines: List<TextLine>) {
    val fullText: String get() = lines.joinToString("\n") { it.text }

    /** Character-weighted mean confidence over lines that report one; null if none do. */
    val meanConfidence: Float?
        get() {
            val scored = lines.filter { it.confidence != null && it.text.isNotBlank() }
            if (scored.isEmpty()) return null
            val totalChars = scored.sumOf { it.text.length }.toFloat()
            return scored.sumOf { (it.confidence!! * it.text.length).toDouble() }.toFloat() / totalChars
        }
}

/** Where a candidate span came from. Recorded so evaluation can attribute errors to a stage. */
enum class MentionSource { LEXICON, INGREDIENT_SECTION, LLM, MANUAL }

/** A span of label text that may name an ingredient, produced by a [com.airgaprx.core.recognition.LabelParser]. */
data class Mention(val text: String, val source: MentionSource)

/** A scored match of free text to a canonical ingredient. */
data class Candidate(val ingredient: Ingredient, val score: Double, val matchedTerm: String)

/** Outcome of normalising one mention (R3: escalate ambiguity rather than guess). */
sealed interface Resolution {
    data class Resolved(val candidate: Candidate) : Resolution
    data class Ambiguous(val options: List<Candidate>) : Resolution
    data object Unrecognised : Resolution
}

/** A mention paired with its normalisation outcome, shown on the Review screen. */
data class RecognisedItem(val mention: Mention, val resolution: Resolution)

/** Release metadata exposed on the Knowledge Base screen (R4). */
data class KbVersion(
    val releaseId: String,
    val compiledAt: String,
    val dbSha256: String,
    val sources: List<String>,
    val ingredientCount: Int,
    val interactionCount: Int,
)
