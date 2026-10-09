package com.airgaprx.app.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airgaprx.app.AppContainer
import com.airgaprx.app.data.ShelfItem
import com.airgaprx.core.model.Alert
import com.airgaprx.core.model.KbVersion
import com.airgaprx.core.pipeline.StageTimings
import com.airgaprx.core.pipeline.timed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ResultsUi {
    data object Evaluating : ResultsUi
    data class Done(
        val alerts: List<Alert>,
        val unchecked: List<ShelfItem>,
        val checkedCount: Int,
        val kb: KbVersion,
        val acknowledged: Boolean,
        val timings: StageTimings,
    ) : ResultsUi {
        val needsAcknowledgement: Boolean get() = !acknowledged && alerts.any { it.interruptive }
    }
    data class Failed(val message: String) : ResultsUi
}

/** Triggers evaluation and holds the ranked alerts (M1 InteractionViewModel, R4/R5). */
class InteractionViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow<ResultsUi>(ResultsUi.Evaluating)
    val ui: StateFlow<ResultsUi> = _ui

    init { evaluate() }

    private fun evaluate() = viewModelScope.launch {
        val services = c.services() ?: run { _ui.value = ResultsUi.Failed("Knowledge base not loaded"); return@launch }
        c.medicationRepository.refresh()
        com.airgaprx.app.platform.PerfLog.begin()
        val t = LinkedHashMap<String, Long>()
        val (alerts, unchecked, checked) = withContext(Dispatchers.Default) {
            val entries = c.medicationRepository.checkable(services.kb)
            val raw = timed(t, "evaluate") { services.engine.evaluate(entries) }
            val ranked = timed(t, "rank") { c.ranker.rank(raw) }
            Triple(ranked, c.medicationRepository.unchecked(services.kb), entries.size)
        }
        c.lastAlerts = alerts
        com.airgaprx.app.platform.PerfLog.record(
            c.context, source = "check", checkMs = t.values.sum(), outcome = "${alerts.size} alerts / $checked ingredients",
        )
        _ui.value = ResultsUi.Done(alerts, unchecked, checked, services.kb.version, acknowledged = false, timings = StageTimings(t))
    }

    fun acknowledge() = _ui.update { (it as? ResultsUi.Done)?.copy(acknowledged = true) ?: it }
}
