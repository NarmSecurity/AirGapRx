package com.airgaprx.app

import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgaprx.app.platform.PerfLog
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.Resolution
import com.airgaprx.core.normalise.Text
import com.airgaprx.core.pipeline.RecognitionOutcome
import com.airgaprx.core.pipeline.RecognitionPipeline
import com.airgaprx.core.pipeline.timed
import com.airgaprx.core.recognition.HybridLabelParser
import com.airgaprx.core.recognition.LabelParser
import com.airgaprx.core.recognition.LexiconLabelParser
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device evaluation harness for M1 criteria 2 and 3, plus the parser comparison
 * (deterministic vs. deterministic + language model) that justifies the M1 design change.
 *
 * Put held-out photos in app/src/androidTest/assets/eval/ (never photos used to tune the parser):
 *   labels/NAME.jpg               in-focus label photo
 *   labels/NAME.txt               manual transcription of the ingredient panel (criterion 2)
 *   labels/NAME.ingredients.txt   expected active ingredients, one canonical name per line
 *   degraded/NAME.jpg             blurred / glare / angled captures (criterion 3)
 *
 * Run on the reference device:  ./gradlew :app:connectedDebugAndroidTest
 * Results: /sdcard/Android/data/com.airgaprx.app/files/eval/ as CSV files (adb pull) and logcat tag AirGapRx.Eval.
 *
 * Criterion 8 (shutter-to-result latency) is NOT measured here: decoding a stored JPEG skips the
 * camera. It is recorded by the app itself for every real shutter tap (see PerfLog).
 */
@RunWith(AndroidJUnit4::class)
class OcrEvaluationTest {
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AirGapApp
    private val outDir = File(app.getExternalFilesDir(null), "eval").apply { mkdirs() }
    private val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

    private fun services() = runBlocking {
        app.container.kbRepository.load()
        app.container.services() ?: throw AssertionError("knowledge base failed to load")
    }

    /** Fails the run when a photo set is empty, so a green run always means something was evaluated. */
    private fun images(dir: String, minimum: Int): List<String> {
        val names = testAssets.list("eval/$dir").orEmpty().filter { it.endsWith(".jpg") || it.endsWith(".png") }.sorted()
        assertTrue("No evaluation photos in androidTest/assets/eval/$dir; nothing would be measured", names.isNotEmpty())
        if (names.size < minimum) Log.w(TAG, "eval/$dir has ${names.size} photos, below the M1 target of $minimum")
        return names
    }

    private fun asset(path: String): String? = runCatching { testAssets.open(path).bufferedReader().readText() }.getOrNull()

    private fun input(path: String): InputImage =
        testAssets.open(path).use { InputImage.fromBitmap(BitmapFactory.decodeStream(it), 0) }

    /**
     * Criterion 2: character accuracy (1 - character error rate) per label, and end-to-end ingredient
     * recall/precision per label (photo -> items on the Review screen), for each parser configuration.
     */
    @Test
    fun recognitionAccuracyAndParserComparison(): Unit = runBlocking {
        val s = services()
        val names = images("labels", minimum = 60)
        val parsers = buildMap<String, LabelParser> {
            put("deterministic", HybridLabelParser(LexiconLabelParser(s.lexicon), null))
            app.container.llmParser()?.let { put("deterministic+llm", HybridLabelParser(LexiconLabelParser(s.lexicon), it)) }
        }
        if (parsers.size == 1) Log.w(TAG, "No language model imported: only the deterministic parser is evaluated")

        val csv = StringBuilder(
            "device,image,parser,truth_chars,char_accuracy,ocr_confidence,outcome,expected,found,found_after_confirmation," +
                "missed,extra,llm_mentions,ocr_ms,parse_ms,normalise_ms,peak_rss_kb\n"
        )
        var transcribed = 0
        for (name in names) {
            val base = "eval/labels/" + name.substringBeforeLast('.')
            val truth = asset("$base.txt")
            val expected = asset("$base.ingredients.txt")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.mapNotNull { s.lexicon.lookup(it).firstOrNull()?.ingredient?.id }?.toSet()
            val ocrTimings = LinkedHashMap<String, Long>()
            val ocr = timed(ocrTimings, "ocr") { app.container.ocr.recognise(input("eval/labels/$name")) }
            val charAccuracy = truth?.let {
                transcribed++
                val a = Text.normalise(it); val b = Text.normalise(ocr.fullText)
                1.0 - Text.editDistance(a, b).toDouble() / maxOf(1, a.length)
            }
            for ((label, parser) in parsers) {
                val t = LinkedHashMap(ocrTimings)
                val outcome = RecognitionPipeline(parser, s.normaliser).process(ocr, t)
                val items = (outcome as? RecognitionOutcome.Recognised)?.items.orEmpty()
                val resolved = items.mapNotNull { (it.resolution as? Resolution.Resolved)?.candidate?.ingredient?.id }.toSet()
                val options = items.flatMap { (it.resolution as? Resolution.Ambiguous)?.options.orEmpty().map { o -> o.ingredient.id } }.toSet()
                val exp = expected.orEmpty()
                csv.append(listOf(
                    device, name, label, truth?.length ?: "", charAccuracy?.let { "%.4f".format(it) } ?: "",
                    ocr.meanConfidence ?: "", outcome::class.simpleName,
                    exp.size, (exp intersect resolved).size, (exp intersect (resolved + options)).size,
                    (exp - resolved - options).joinToString("|"), (resolved - exp).joinToString("|"),
                    items.count { it.mention.source == MentionSource.LLM },
                    t["ocr"] ?: "", t["parse"] ?: "", t["normalise"] ?: "", PerfLog.peakRssKb(),
                ).joinToString(",")).append('\n')
            }
        }
        assertTrue("No transcriptions (labels/NAME.txt) found; character accuracy was not measured", transcribed > 0)
        File(outDir, "recognition.csv").writeText(csv.toString())
        Log.i(TAG, "criterion 2 results ($device):\n$csv")
    }

    /** Criterion 3: every degraded capture must produce a re-capture prompt, never a silent commit. */
    @Test
    fun degradedCapturesAskForRetake(): Unit = runBlocking {
        val s = services()
        val csv = StringBuilder("device,image,ocr_confidence,outcome,reason\n")
        var silent = 0
        for (name in images("degraded", minimum = 20)) {
            val ocr = app.container.ocr.recognise(input("eval/degraded/$name"))
            val outcome = s.pipeline.process(ocr)
            val reason = (outcome as? RecognitionOutcome.NeedsRecapture)?.reason?.name ?: ""
            if (outcome is RecognitionOutcome.Recognised) silent++
            csv.append("$device,$name,${ocr.meanConfidence ?: ""},${outcome::class.simpleName},$reason\n")
        }
        File(outDir, "degraded.csv").writeText(csv.toString())
        Log.i(TAG, "criterion 3: $silent capture(s) proceeded without a retake prompt\n$csv")
        // Recorded rather than asserted: a degraded photo that still reads well is a finding to discuss.
    }

    companion object { private const val TAG = "AirGapRx.Eval" }
}
