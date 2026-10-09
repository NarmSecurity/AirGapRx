package com.airgaprx.app

import android.content.Context
import android.util.Log
import com.airgaprx.app.data.KbState
import com.airgaprx.app.data.KnowledgeBaseRepository
import com.airgaprx.app.data.MedicationRepository
import com.airgaprx.app.platform.MediaPipeLlmParser
import com.airgaprx.app.platform.MlKitOcrService
import com.airgaprx.core.kb.KnowledgeBase
import com.airgaprx.core.model.RecognisedItem
import com.airgaprx.core.normalise.IngredientNormaliser
import com.airgaprx.core.normalise.Lexicon
import com.airgaprx.core.pipeline.RecognitionPipeline
import com.airgaprx.core.pipeline.StageTimings
import com.airgaprx.core.reasoning.ExplanationGenerator
import com.airgaprx.core.reasoning.InteractionEngine
import com.airgaprx.core.reasoning.SeverityRanker
import com.airgaprx.core.recognition.HybridLabelParser
import com.airgaprx.core.recognition.LexiconLabelParser
import java.io.File

/**
 * Manual dependency container (no DI framework: one developer, a dozen objects, and every
 * dependency is visible in one file). Domain services are rebuilt whenever a new knowledge base
 * is installed, because the Lexicon is derived from the knowledge-base vocabulary.
 */
class AppContainer(val context: Context) {
    val kbRepository = KnowledgeBaseRepository(context)
    val medicationRepository by lazy { MedicationRepository(context) }
    val ocr by lazy { MlKitOcrService() }
    val ranker = SeverityRanker()
    val explainer = ExplanationGenerator()

    /** Hand-off between Capture and Review screens for the current scan. */
    val session = ScanSession()

    /** Ranked alerts from the latest check, read by the alert detail screen. */
    @Volatile var lastAlerts: List<com.airgaprx.core.model.Alert> = emptyList()

    /** Last measured timings, shown on the About screen and logged for the R7 evaluation. */
    @Volatile var lastTimings: StageTimings? = null
        set(value) { field = value; value?.let { Log.i("AirGapRx.Timing", it.stages.toString() + " total=" + it.totalMs + "ms") } }

    private var cachedFor: KnowledgeBase? = null
    private var cachedServices: DomainServices? = null
    private var llm: MediaPipeLlmParser? = null

    /** Domain services bound to the currently loaded knowledge base. */
    @Synchronized
    fun services(): DomainServices? {
        val kb = (kbRepository.state.value as? KbState.Ready)?.kb ?: return null
        if (kb === cachedFor) return cachedServices
        val lexicon = Lexicon(kb)
        val normaliser = IngredientNormaliser(lexicon)
        val parser = HybridLabelParser(LexiconLabelParser(lexicon), llmParser())
        return DomainServices(kb, lexicon, normaliser, RecognitionPipeline(parser, normaliser), InteractionEngine(kb))
            .also { cachedFor = kb; cachedServices = it }
    }

    /** The optional language model parser, if the user has imported a model file. */
    @Synchronized
    fun llmParser(): MediaPipeLlmParser? {
        val file = MediaPipeLlmParser.installedModel(context) ?: return null.also { llm?.close(); llm = null }
        if (llm == null) llm = MediaPipeLlmParser(context, file)
        return llm
    }

    @Synchronized
    fun onModelChanged() {
        llm?.close(); llm = null
        cachedFor = null; cachedServices = null
    }

    fun modelFile(): File? = MediaPipeLlmParser.installedModel(context)
}

data class DomainServices(
    val kb: KnowledgeBase,
    val lexicon: Lexicon,
    val normaliser: IngredientNormaliser,
    val pipeline: RecognitionPipeline,
    val engine: InteractionEngine,
)

/** Items recognised from the most recent capture, awaiting review. */
class ScanSession {
    @Volatile var items: List<RecognisedItem> = emptyList()
    @Volatile var confidence: Float? = null
}
