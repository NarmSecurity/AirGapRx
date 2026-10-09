package com.airgaprx.app.ui.capture

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.airgaprx.app.platform.PerfLog
import androidx.lifecycle.viewModelScope
import com.airgaprx.app.AppContainer
import com.airgaprx.core.pipeline.RecaptureReason
import com.airgaprx.core.pipeline.RecognitionOutcome
import com.airgaprx.core.pipeline.timed
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CaptureState {
    data object Ready : CaptureState
    data object Processing : CaptureState
    data class Retake(val reason: RecaptureReason, val confidence: Float?) : CaptureState
    data object Recognised : CaptureState
    data class Error(val message: String) : CaptureState
}

/**
 * Drives the capture -> recognise cycle including the re-capture loop (M1 CaptureViewModel, R2).
 * State machine: Ready -> Processing -> (Retake -> Ready)* -> Recognised.
 */
class CaptureViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Ready)
    val state: StateFlow<CaptureState> = _state

    /**
     * @param shutterAtNanos SystemClock.elapsedRealtimeNanos() when the shutter was tapped, or null
     *   for a gallery photo. Used for the shutter-to-review latency (criterion 8).
     */
    fun onImage(image: InputImage, shutterAtNanos: Long? = null, releaseImage: () -> Unit) {
        var released = false
        val release = { if (!released) { released = true; releaseImage() } }
        if (_state.value == CaptureState.Processing) { release(); return }
        _state.value = CaptureState.Processing
        viewModelScope.launch {
            try {
                val services = c.services() ?: run {
                    release(); _state.value = CaptureState.Error("Knowledge base not loaded"); return@launch
                }
                val t = LinkedHashMap<String, Long>()
                val ocr = timed(t, "ocr") { c.ocr.recognise(image) }
                release()
                val outcome = withContext(Dispatchers.Default) { services.pipeline.process(ocr, t) }
                c.lastTimings = outcome.timings
                PerfLog.record(
                    c.context,
                    source = if (shutterAtNanos != null) "camera" else "gallery",
                    shutterToReviewMs = shutterAtNanos?.let { (SystemClock.elapsedRealtimeNanos() - it) / 1_000_000 },
                    stages = outcome.timings.stages,
                    outcome = outcome::class.simpleName ?: "",
                )
                _state.value = when (outcome) {
                    is RecognitionOutcome.NeedsRecapture -> CaptureState.Retake(outcome.reason, outcome.confidence)
                    is RecognitionOutcome.Recognised -> {
                        c.session.items = outcome.items
                        c.session.confidence = outcome.confidence
                        CaptureState.Recognised
                    }
                }
            } catch (e: Exception) {
                release()
                _state.value = CaptureState.Error(e.message ?: "Recognition failed")
            }
        }
    }

    fun reset() { _state.value = CaptureState.Ready }

    /** Manual entry: start the Review screen with an empty list. */
    fun prepareManual() { c.session.items = emptyList() }
}
