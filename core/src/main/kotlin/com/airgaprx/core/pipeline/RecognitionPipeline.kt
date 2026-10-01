package com.airgaprx.core.pipeline

import com.airgaprx.core.model.OcrResult
import com.airgaprx.core.model.RecognisedItem
import com.airgaprx.core.normalise.IngredientNormaliser
import com.airgaprx.core.recognition.LabelParser

/** When a capture is good enough to parse (R2). Kept as data so evaluation can tune it. */
data class CapturePolicy(
    val minMeanConfidence: Float = 0.60f,
    val minCharacters: Int = 8,
)

enum class RecaptureReason { NO_TEXT, LOW_CONFIDENCE, NO_INGREDIENTS }

sealed interface RecognitionOutcome {
    val timings: StageTimings

    /** R2: low-confidence capture returns a re-capture prompt instead of a silent guess. */
    data class NeedsRecapture(val reason: RecaptureReason, val confidence: Float?, override val timings: StageTimings) : RecognitionOutcome

    /** Items for the Review screen; any Ambiguous/Unrecognised item suspends the flow until the user decides (R3). */
    data class Recognised(val items: List<RecognisedItem>, val confidence: Float?, override val timings: StageTimings) : RecognitionOutcome
}

/** Per-stage wall-clock timings in milliseconds, recorded for the latency evaluation (R7, criterion 8). */
data class StageTimings(val stages: Map<String, Long>) {
    val totalMs: Long get() = stages.values.sum()
    fun plus(stage: String, ms: Long) = StageTimings(stages + (stage to ms))
}

inline fun <T> timed(sink: MutableMap<String, Long>, stage: String, block: () -> T): T {
    val start = System.nanoTime()
    try { return block() } finally { sink[stage] = (System.nanoTime() - start) / 1_000_000 }
}

/**
 * Runs the post-OCR part of the pipeline: capture gate -> parse -> normalise.
 * OCR itself is platform code (ML Kit) and is timed by the caller as stage "ocr".
 */
class RecognitionPipeline(
    private val parser: LabelParser,
    private val normaliser: IngredientNormaliser,
    private val policy: CapturePolicy = CapturePolicy(),
) {
    fun process(ocr: OcrResult, priorTimings: Map<String, Long> = emptyMap()): RecognitionOutcome {
        val t = LinkedHashMap(priorTimings)
        val confidence = ocr.meanConfidence
        val chars = ocr.fullText.count { it.isLetterOrDigit() }
        if (chars < policy.minCharacters) {
            return RecognitionOutcome.NeedsRecapture(RecaptureReason.NO_TEXT, confidence, StageTimings(t))
        }
        if (confidence != null && confidence < policy.minMeanConfidence) {
            return RecognitionOutcome.NeedsRecapture(RecaptureReason.LOW_CONFIDENCE, confidence, StageTimings(t))
        }
        val mentions = timed(t, "parse") { parser.parse(ocr) }
        val items = timed(t, "normalise") { normaliser.normaliseAll(mentions) }
        if (items.isEmpty()) {
            return RecognitionOutcome.NeedsRecapture(RecaptureReason.NO_INGREDIENTS, confidence, StageTimings(t))
        }
        return RecognitionOutcome.Recognised(items, confidence, StageTimings(t))
    }
}
