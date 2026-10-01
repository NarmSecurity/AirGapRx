package com.airgaprx.core.recognition

import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.OcrResult

/**
 * Turns recognised label text into candidate ingredient mentions (M1 LabelParser).
 * Implementations are blocking; callers run them off the main thread.
 */
fun interface LabelParser {
    fun parse(ocr: OcrResult): List<Mention>
}

/**
 * Runs a deterministic primary parser and, when available, a secondary (on-device language
 * model) parser, and merges their mentions.
 *
 * Safety property: the language model only *proposes* mentions. Every mention, whatever its
 * source, still goes through [com.airgaprx.core.normalise.IngredientNormaliser], so model output
 * can never reach the interaction engine without matching the knowledge-base vocabulary or
 * being confirmed by the user.
 */
class HybridLabelParser(
    private val primary: LabelParser,
    private val secondary: LabelParser?,
) : LabelParser {
    override fun parse(ocr: OcrResult): List<Mention> {
        val first = primary.parse(ocr)
        val second = try {
            secondary?.parse(ocr).orEmpty()
        } catch (e: Exception) {
            emptyList() // model failure must degrade to the deterministic path, never block a check
        }
        val seen = HashSet<String>()
        return (first + second).filter { seen.add(com.airgaprx.core.normalise.Text.normalise(it.text)) }
    }
}
