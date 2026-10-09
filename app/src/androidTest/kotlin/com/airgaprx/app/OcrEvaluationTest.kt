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
     * Criterion 2 and the parser comparison. Ground truth must be complete: every photo needs NAME.txt
     * and NAME.ingredients.txt, otherwise the run fails rather than silently scoring a subset.
     *
     * Parser configuration comes from an instrumentation argument so each configuration runs in its own
     * fresh process (cold start, separate memory peak):
     *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.parser=deterministic
     *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.parser=llm
     *
     * Per-label columns:
     *  - found_auto: expected actives resolved automatically on the Review list;
     *  - correct_option_offered: expected actives present only as an option of an ambiguous item. This
     *    is candidate retrieval; whether a person picks it is measured in the usability study, not here;
     *  - expected_not_in_kb: expected actives that are not in the KB vocabulary. They stay in the
     *    denominator (coverage gaps), so recall is never inflated by dropping them;
     *  - run_index 1 is the cold run (includes model load for the llm configuration);
     *  - peak_rss_kb is per label when peak_scope=per_operation.
     */
    @Test
    fun recognitionAccuracyAndParserComparison(): Unit = runBlocking {
        val s = services()
        val names = images("labels", minimum = 60)
        val config = InstrumentationRegistry.getArguments().getString("parser") ?: "deterministic"
        val parser: LabelParser = when (config) {
            "deterministic" -> HybridLabelParser(LexiconLabelParser(s.lexicon), null)
            "llm" -> HybridLabelParser(
                LexiconLabelParser(s.lexicon),
                app.container.llmParser() ?: throw AssertionError("parser=llm but no .task model is imported in the app"),
            )
            else -> throw AssertionError("Unknown parser configuration '$config' (use deterministic or llm)")
        }

        val missing = names.flatMap { n ->
            val base = "eval/labels/" + n.substringBeforeLast('.')
            listOfNotNull(
                "$base.txt".takeIf { asset(it) == null },
                "$base.ingredients.txt".takeIf { asset(it) == null },
            )
        }
        assertTrue("Incomplete ground truth, add these files: ${missing.joinToString()}", missing.isEmpty())

        val csv = StringBuilder(
            "device,parser,run_index,image,truth_chars,char_accuracy,ocr_confidence,outcome,expected,found_auto," +
                "correct_option_offered,expected_not_in_kb,missed,extra,llm_mentions,ocr_ms,parse_ms,normalise_ms,peak_rss_kb,peak_scope\n"
        )
        names.forEachIndexed { i, name ->
            val base = "eval/labels/" + name.substringBeforeLast('.')
            val truth = asset("$base.txt")!!
            val expectedNames = asset("$base.ingredients.txt")!!.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val expectedIds = expectedNames.associateWith { n -> s.lexicon.exactMatches(Text.normalise(n)).singleOrNull()?.ingredient?.id }
            val notInKb = expectedIds.filterValues { it == null }.keys
            val exp = expectedIds.values.filterNotNull().toSet()

            val peakScope = if (PerfLog.resetPeak()) "per_operation" else "since_process_start"
            val t = LinkedHashMap<String, Long>()
            val ocr = timed(t, "ocr") { app.container.ocr.recognise(input("eval/labels/$name")) }
            val a = Text.normalise(truth); val b = Text.normalise(ocr.fullText)
            val charAccuracy = 1.0 - Text.editDistance(a, b).toDouble() / maxOf(1, a.length)
            val outcome = RecognitionPipeline(parser, s.normaliser).process(ocr, t)
            val items = (outcome as? RecognitionOutcome.Recognised)?.items.orEmpty()
            val resolved = items.mapNotNull { (it.resolution as? Resolution.Resolved)?.candidate?.ingredient?.id }.toSet()
            val options = items.flatMap { (it.resolution as? Resolution.Ambiguous)?.options.orEmpty().map { o -> o.ingredient.id } }.toSet()
            csv.append(listOf(
                device, config, i + 1, name, truth.length, "%.4f".format(charAccuracy), ocr.meanConfidence ?: "",
                outcome::class.simpleName, expectedNames.size, (exp intersect resolved).size,
                ((exp intersect options) - resolved).size, notInKb.size,
                ((exp - resolved - options).toList() + notInKb).joinToString("|"), (resolved - exp).joinToString("|"),
                items.count { it.mention.source == MentionSource.LLM },
                t["ocr"] ?: "", t["parse"] ?: "", t["normalise"] ?: "", PerfLog.peakRssKb(), peakScope,
            ).joinToString(",")).append('\n')
        }
        File(outDir, "recognition_$config.csv").writeText(csv.toString())
        Log.i(TAG, "criterion 2 results ($device, parser=$config):\n$csv")
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
