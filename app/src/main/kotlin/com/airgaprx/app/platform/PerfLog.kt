package com.airgaprx.app.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Evaluation criterion 8 instrumentation. One CSV row per measured operation is appended to
 * `files/eval/timings.csv` in app-specific external storage
 * (`adb pull /sdcard/Android/data/com.airgaprx.app/files/eval/timings.csv`).
 *
 * What is measured, precisely:
 *  - source=camera: `recognition_ms` = shutter tap -> Review list ready (camera capture, OCR, parsing,
 *    normalisation). source=gallery has no shutter, so recognition_ms is empty there.
 *  - source=check: `check_ms` = pairwise evaluation + ranking for the whole shelf.
 *  - source=review: `review_ms` = Review list ready -> Save tapped (user time, reported separately).
 *  Recognition and check are reported separately. M1's "shutter-to-result" also spans the Review step,
 *  which is user time. A combined *processing* time is computed per session by pairing each recognition
 *  with the check that follows it in the same app run and summing the pair (scripts/summarise_timings.py);
 *  medians of the separate intervals must not simply be added.
 *  - `run_index`: operations since the process started (1 = cold, first after launch; >1 = warm).
 *  - `peak_rss_kb` with `peak_scope`:
 *      per_operation      the kernel peak-RSS counter (VmHWM) was reset at the start of this operation,
 *                         so the value is the peak during it;
 *      since_process_start the reset was refused (e.g. by SELinux), so the value is the process peak
 *                         since launch. Only the first (cold) row is then attributable to one operation.
 */
object PerfLog {
    private const val TAG = "AirGapRx.Timing"
    private const val HEADER =
        "timestamp,session,source,device,android,total_ram_mb,run_index,process_uptime_ms,recognition_ms,ocr_ms,parse_ms,normalise_ms,review_ms,check_ms,outcome,peak_rss_kb,peak_scope"

    private val operations = AtomicInteger(0)

    data class Operation(val runIndex: Int, val peakScope: String)

    @Volatile private var current: Operation? = null

    /** Wall-clock start of this process in seconds: identical for every row from one app run. */
    private val session: Long by lazy {
        (System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())) / 1000
    }

    /** Reset the kernel's peak-RSS counter for this process. Returns false if not permitted. */
    fun resetPeak(): Boolean = runCatching { File("/proc/self/clear_refs").writeText("5"); true }.getOrDefault(false)

    /** Peak resident set size in kB since the last reset (or process start), from /proc/self/status VmHWM. */
    fun peakRssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }
            .substringAfter(':').trim().substringBefore(' ').toLong()
    }.getOrDefault(-1)

    /** Call at the start of a measured operation (shutter tap, gallery pick, interaction check). */
    fun begin(): Operation =
        Operation(operations.incrementAndGet(), if (resetPeak()) "per_operation" else "since_process_start").also { current = it }

    fun record(
        context: Context,
        source: String,
        recognitionMs: Long? = null,
        stages: Map<String, Long> = emptyMap(),
        reviewMs: Long? = null,
        checkMs: Long? = null,
        outcome: String = "",
    ) {
        // Review rows measure user time: they neither consume a run index nor reset the memory peak.
        val op = current ?: if (source == "review") Operation(operations.get(), "not_measured") else begin()
        current = null
        val am = context.getSystemService(ActivityManager::class.java)
        val totalRamMb = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024)
        val uptime = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        // Rows with the same session come from one app run; scripts/summarise_timings.py uses this to pair
        // a recognition with the check that follows it.
        val row = listOf(
            System.currentTimeMillis(), session, source, "${Build.MANUFACTURER} ${Build.MODEL}".replace(',', ' '),
            Build.VERSION.RELEASE, totalRamMb, op.runIndex, uptime, recognitionMs ?: "", stages["ocr"] ?: "",
            stages["parse"] ?: "", stages["normalise"] ?: "", reviewMs ?: "", checkMs ?: "", outcome.replace(',', ' '),
            peakRssKb(), op.peakScope,
        ).joinToString(",")
        Log.i(TAG, row)
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "eval").apply { mkdirs() }
            val file = File(dir, "timings.csv")
            if (!file.exists()) file.writeText(HEADER + "\n")
            file.appendText(row + "\n")
        }
    }
}
