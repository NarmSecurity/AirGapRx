package com.airgaprx.app.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Evaluation criterion 8 instrumentation.
 *
 * Every camera scan appends one row to `files/eval/timings.csv` in app-specific external storage
 * (pull with `adb pull /sdcard/Android/data/com.airgaprx.app/files/eval/timings.csv`). Each row has:
 *  - shutter_to_review_ms: from the shutter tap to the Review items being ready. This covers
 *    camera capture, OCR, parsing and normalisation, i.e. the full user-visible scan latency;
 *  - per-stage timings (ocr, parse, normalise);
 *  - peak_rss_kb: the process's resident-memory high-water mark (VmHWM) since start. This is
 *    a true peak, not a sample.
 * Interaction-check latency (evaluate + rank) is logged the same way with source=check.
 */
object PerfLog {
    private const val TAG = "AirGapRx.Timing"
    private const val HEADER = "timestamp,source,device,android,total_ram_mb,shutter_to_review_ms,ocr_ms,parse_ms,normalise_ms,check_ms,outcome,peak_rss_kb"

    /** Peak resident set size of this process in kB, read from /proc/self/status (VmHWM). */
    fun peakRssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }
            .substringAfter(':').trim().substringBefore(' ').toLong()
    }.getOrDefault(-1)

    fun record(
        context: Context,
        source: String,
        shutterToReviewMs: Long? = null,
        stages: Map<String, Long> = emptyMap(),
        checkMs: Long? = null,
        outcome: String = "",
    ) {
        val am = context.getSystemService(ActivityManager::class.java)
        val totalRamMb = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024)
        val row = listOf(
            System.currentTimeMillis(), source, "${Build.MANUFACTURER} ${Build.MODEL}".replace(',', ' '),
            Build.VERSION.RELEASE, totalRamMb, shutterToReviewMs ?: "", stages["ocr"] ?: "", stages["parse"] ?: "",
            stages["normalise"] ?: "", checkMs ?: "", outcome, peakRssKb(),
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
