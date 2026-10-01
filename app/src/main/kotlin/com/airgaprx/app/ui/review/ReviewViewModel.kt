package com.airgaprx.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airgaprx.app.AppContainer
import com.airgaprx.core.model.Candidate
import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RowState {
    data class Accepted(val ingredient: Ingredient, val score: Double) : RowState
    /** R3: several plausible matches; the pipeline is suspended until the user picks one. */
    data class Choose(val options: List<Candidate>) : RowState
    /** Not in the knowledge base; the user decides whether to keep it as "not checked". */
    data class Unknown(val keep: Boolean? = null) : RowState
    data object Removed : RowState
}

data class ReviewRow(val id: Int, val mention: Mention, val state: RowState) {
    val pending: Boolean get() = state is RowState.Choose || (state is RowState.Unknown && state.keep == null)
}

data class ReviewUi(val rows: List<ReviewRow>, val product: String = "", val saved: Boolean = false) {
    val pendingCount: Int get() = rows.count { it.pending }
    val canSave: Boolean get() = pendingCount == 0 && rows.any { it.state is RowState.Accepted || (it.state as? RowState.Unknown)?.keep == true }
}

/** Holds the disambiguation state required by R3 (M1 ReviewViewModel). */
class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(
        ReviewUi(c.session.items.mapIndexed { i, item ->
            ReviewRow(i, item.mention, when (val r = item.resolution) {
                is Resolution.Resolved -> RowState.Accepted(r.candidate.ingredient, r.candidate.score)
                is Resolution.Ambiguous -> RowState.Choose(r.options)
                Resolution.Unrecognised -> RowState.Unknown()
            })
        })
    )
    val ui: StateFlow<ReviewUi> = _ui

    private fun set(id: Int, state: RowState) = _ui.update { u -> u.copy(rows = u.rows.map { if (it.id == id) it.copy(state = state) else it }) }

    fun choose(id: Int, candidate: Candidate) = set(id, RowState.Accepted(candidate.ingredient, candidate.score))
    fun noneOfThese(id: Int) = set(id, RowState.Unknown())
    fun keepUnchecked(id: Int) = set(id, RowState.Unknown(keep = true))
    fun remove(id: Int) = set(id, RowState.Removed)
    fun setProduct(name: String) = _ui.update { it.copy(product = name) }

    fun search(query: String): List<Candidate> =
        if (query.trim().length < 2) emptyList() else c.services()?.lexicon?.lookup(query, minScore = 0.6, limit = 6).orEmpty()

    fun addManual(ingredient: Ingredient) = _ui.update { u ->
        val next = (u.rows.maxOfOrNull { it.id } ?: -1) + 1
        u.copy(rows = u.rows + ReviewRow(next, Mention(ingredient.name, MentionSource.MANUAL), RowState.Accepted(ingredient, 1.0)))
    }

    fun save() {
        val u = _ui.value
        if (!u.canSave) return
        viewModelScope.launch {
            val product = u.product.trim().ifEmpty { null }
            val seen = HashSet<String>()
            u.rows.forEach { row ->
                when (val s = row.state) {
                    is RowState.Accepted -> if (seen.add(s.ingredient.id)) c.medicationRepository.add(s.ingredient.id, s.ingredient.name, product)
                    is RowState.Unknown -> if (s.keep == true) c.medicationRepository.add(null, row.mention.text.replaceFirstChar { it.uppercase() }, product)
                    else -> Unit
                }
            }
            c.session.items = emptyList()
            _ui.update { it.copy(saved = true) }
        }
    }
}
