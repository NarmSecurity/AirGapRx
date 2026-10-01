package com.airgaprx.app.platform

import android.content.Context
import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.OcrResult
import com.airgaprx.core.recognition.LabelParser
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Optional on-device language model parser (M1 LabelParser with a quantised small model).
 *
 * The model is a local `.task` file the user imports from storage; nothing is downloaded (R1).
 * It is the only class that loads model weights, so the R7 memory budget is confined here.
 * Its output is only a list of proposed names: every proposal is still normalised against the
 * knowledge-base vocabulary (see HybridLabelParser), so the model cannot invent an ingredient.
 *
 * Reflection note: MediaPipe LLM Inference is now in maintenance mode, with LiteRT-LM as its
 * successor, and NNAPI is deprecated from Android 15. Both are design changes from M1.
 */
class MediaPipeLlmParser(private val context: Context, private val modelFile: File) : LabelParser {
    @Volatile private var engine: LlmInference? = null

    val isLoaded: Boolean get() = engine != null

    @Synchronized
    private fun engine(): LlmInference = engine ?: LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(512)
            .build(),
    ).also { engine = it }

    override fun parse(ocr: OcrResult): List<Mention> {
        val label = ocr.fullText.take(MAX_INPUT_CHARS)
        if (label.isBlank()) return emptyList()
        val response = engine().generateResponse(PROMPT + label + "\n\nIngredients:\n")
        return response.lines()
            .map { it.trim().trimStart('-', '*', '•', '·').trim().trimEnd('.', ',') }
            .filter { it.isNotEmpty() && it.length <= 60 && !it.equals("NONE", ignoreCase = true) }
            .take(MAX_ITEMS)
            .map { Mention(it, MentionSource.LLM) }
    }

    @Synchronized
    fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        private const val MAX_INPUT_CHARS = 1500
        private const val MAX_ITEMS = 12
        private val PROMPT = """
            You extract the ACTIVE ingredients from a medicine or supplement label.
            Rules: list one ingredient per line, generic or botanical name only, no doses, no brand
            names, no excipients (e.g. magnesium stearate, cellulose, gelatin). If there are none, write NONE.

            Label text:
        """.trimIndent() + "\n"

        /** Imported model files live in app-private storage. */
        fun modelDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
        fun installedModel(context: Context): File? =
            modelDir(context).listFiles { f -> f.isFile && f.name.endsWith(".task") }?.maxByOrNull { it.lastModified() }
    }
}
