package com.airgaprx.app.platform

import com.airgaprx.core.model.OcrResult
import com.airgaprx.core.model.TextLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * M1 OcrService: converts a frame to text lines with per-line confidence (R2).
 * Uses the bundled Latin model, which runs entirely on-device.
 */
class MlKitOcrService {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognise(image: InputImage): OcrResult {
        val text = recognizer.process(image).await()
        val lines = text.textBlocks.flatMapIndexed { blockIndex, block ->
            block.lines.map { line ->
                // Bundled model reports confidence in [0, 1]; 0 means "not reported".
                TextLine(line.text, line.confidence.takeIf { it > 0f }, blockIndex)
            }
        }
        return OcrResult(lines)
    }

    fun close() = recognizer.close()
}

/** Minimal Task -> coroutine bridge, avoiding an extra Play Services coroutine dependency. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
