package com.airgaprx.core.normalise

import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.RecognisedItem
import com.airgaprx.core.model.Resolution

/**
 * Thresholds for turning candidate matches into a decision. Kept as data so evaluation
 * (criterion 4) can sweep them without code changes.
 */
data class NormalisationPolicy(
    /** Candidates scoring below this are not offered at all. */
    val candidateFloor: Double = 0.75,
    /** A non-exact match is accepted without asking only at or above this score... */
    val autoAccept: Double = 0.92,
    /** ...and only if it beats the runner-up by at least this margin. */
    val margin: Double = 0.05,
    /** Maximum options shown on the disambiguation screen. */
    val maxOptions: Int = 3,
)

/**
 * Maps free text to a canonical [com.airgaprx.core.model.Ingredient] (requirement R3).
 * Returns candidates rather than one answer when confidence is insufficient: an unresolved
 * ingredient produces a question for the user, never a silent best guess.
 */
class IngredientNormaliser(
    private val lexicon: Lexicon,
    private val policy: NormalisationPolicy = NormalisationPolicy(),
) {
    fun normalise(mention: Mention): RecognisedItem {
        val cleaned = Text.stripDoses(mention.text).ifEmpty { Text.normalise(mention.text) }
        val candidates = lexicon.lookup(cleaned, minScore = policy.candidateFloor, limit = policy.maxOptions + 1)
        val resolution = when {
            candidates.isEmpty() -> Resolution.Unrecognised
            candidates.size == 1 && candidates[0].score >= policy.autoAccept -> Resolution.Resolved(candidates[0])
            candidates[0].score == 1.0 && candidates.count { it.score == 1.0 } == 1 -> Resolution.Resolved(candidates[0])
            candidates[0].score >= policy.autoAccept &&
                candidates[0].score - candidates[1].score >= policy.margin -> Resolution.Resolved(candidates[0])
            else -> Resolution.Ambiguous(candidates.take(policy.maxOptions))
        }
        return RecognisedItem(mention, resolution)
    }

    /**
     * Normalises every mention and removes duplicates: resolved items collapse by ingredient,
     * unresolved items collapse by their normalised text.
     */
    fun normaliseAll(mentions: List<Mention>): List<RecognisedItem> {
        val seenIngredients = HashSet<String>()
        val seenText = HashSet<String>()
        val out = ArrayList<RecognisedItem>()
        for (m in mentions) {
            val item = normalise(m)
            when (val r = item.resolution) {
                is Resolution.Resolved -> if (seenIngredients.add(r.candidate.ingredient.id)) out += item
                else -> if (seenText.add(Text.normalise(m.text))) out += item
            }
        }
        // An ambiguous item whose options include an already-resolved ingredient is redundant.
        return out.filterNot { item ->
            val r = item.resolution
            r is Resolution.Ambiguous && r.options.any { it.ingredient.id in seenIngredients }
        }
    }
}
