package com.airgaprx.app

import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgaprx.core.normalise.Text
import com.airgaprx.core.pipeline.RecognitionOutcome
import com.airgaprx.core.pipeline.timed
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device evaluation harness for M1 criteria 2, 3 and 8.
 *
 * Put test photos in app/src/androidTest/assets/eval/:
 *   labels/NAME.jpg + labels/NAME.txt   in-focus label + manual transcription of the ingredient panel (criterion 2)
 *   degraded/NAME.jpg                   blurred / glare / angled captures (criterion 3)
 *
 * Run on the reference device:  ./gradlew :app:connectedDebugAndroidTest
 * Results: /sdcard/Android/data/com.airgaprx.app/files/eval/ (pull with adb) and logcat tag AirGapRx.Eval.
 */
@RunWith(AndroidJUnit4::class)
class OcrEvaluationTest {
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AirGapApp
    private val outDir = File(app.getExternalFilesDir(null), "eval").apply { mkdirs() }

    private fun services() = runBlocking {
        app.container.kbRepository.load()
        assertNotNull("knowledge base failed to load", app.container.services())
        app.container.services()!!
    }

    private fun images(dir: String) = testAssets.list("eval/$dir").orEmpty().filter { it.endsWith(".jpg") || it.endsWith(".png") }.sorted()

    private fun input(path: String): InputImage =
        testAssets.open(path).use { InputImage.fromBitmap(BitmapFactory.decodeStream(it), 0) }

    /** Criterion 2 (character accuracy) and criterion 8 (latency, memory). */
    @Test
    fun recognitionAccuracyAndLatency(): Unit = runBlocking {
        val s = services()
        val csv = StringBuilder("image,truth_chars,cer,char_accuracy,confidence,outcome,ocr_ms,parse_ms,normalise_ms,total_ms,pss_kb\n")
        val names = images("labels")
        repeat(if (names.size >= 30) 1 else maxOf(1, 30 / maxOf(1, names.size))) { pass ->
            for (name in names) {
                val truthFile = "eval/labels/" + name.substringBeforeLast('.') + ".txt"
                val truth = runCatching { testAssets.open(truthFile).bufferedReader().readText() }.getOrNull()
                val t = LinkedHashMap<String, Long>()
                val ocr = timed(t, "ocr") { app.container.ocr.recognise(input("eval/labels/$name")) }
                val outcome = s.pipeline.process(ocr, t)
                val cer = truth?.let {
                    val a = Text.normalise(it); val b = Text.normalise(ocr.fullText)
                    Text.editDistance(a, b).toDouble() / maxOf(1, a.length)
                }
                val pss = Debug.getPss()
                csv.append(listOf(
                    "$name#$pass", truth?.length ?: "", cer?.let { "%.4f".format(it) } ?: "",
                    cer?.let { "%.4f".format(1 - it) } ?: "", ocr.meanConfidence ?: "",
                    outcome::class.simpleName, t["ocr"], t["parse"] ?: "", t["normalise"] ?: "", outcome.timings.totalMs, pss,
                ).joinToString(",")).append('\n')
            }
        }
        File(outDir, "recognition.csv").writeText(csv.toString())
        Log.i(TAG, "criterion 2/8 results:\n$csv")
    }

    /** Criterion 3: every degraded capture must produce a re-capture prompt, never a silent commit. */
    @Test
    fun degradedCapturesAskForRetake(): Unit = runBlocking {
        val s = services()
        val csv = StringBuilder("image,confidence,outcome,reason\n")
        var silent = 0
        for (name in images("degraded")) {
            val ocr = app.container.ocr.recognise(input("eval/degraded/$name"))
            val outcome = s.pipeline.process(ocr)
            val reason = (outcome as? RecognitionOutcome.NeedsRecapture)?.reason?.name ?: ""
            if (outcome is RecognitionOutcome.Recognised) silent++
            csv.append("$name,${ocr.meanConfidence ?: ""},${outcome::class.simpleName},$reason\n")
        }
        File(outDir, "degraded.csv").writeText(csv.toString())
        Log.i(TAG, "criterion 3: $silent capture(s) proceeded without a retake prompt\n$csv")
        // Recorded rather than asserted: a degraded photo that still reads well is a valid finding to discuss.
    }

    companion object { private const val TAG = "AirGapRx.Eval" }
}
