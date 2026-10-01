package com.airgaprx.app.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airgaprx.app.AppContainer
import com.airgaprx.app.data.KbState
import com.airgaprx.app.data.ShelfItem
import com.airgaprx.core.model.Candidate
import com.airgaprx.core.model.Ingredient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShelfRow(val item: ShelfItem, val ingredient: Ingredient?)

data class ShelfUiState(
    val kb: KbState = KbState.Loading,
    val rows: List<ShelfRow> = emptyList(),
) {
    val checkableCount: Int get() = rows.count { it.ingredient != null }
}

class ShelfViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<ShelfUiState> =
        combine(c.kbRepository.state, c.medicationRepository.items) { kb, items ->
            val base = (kb as? KbState.Ready)?.kb
            ShelfUiState(kb, items.map { ShelfRow(it, it.ingredientId?.let { id -> base?.ingredient(id) }) })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfUiState())

    init {
        viewModelScope.launch { c.medicationRepository.refresh() }
    }

    /** Manual entry path (also the fallback when a label cannot be scanned). */
    fun search(query: String): List<Candidate> =
        if (query.trim().length < 2) emptyList()
        else c.services()?.lexicon?.lookup(query, minScore = 0.6, limit = 8).orEmpty()

    fun add(ingredient: Ingredient, product: String?) = viewModelScope.launch {
        c.medicationRepository.add(ingredient.id, ingredient.name, product)
    }

    fun remove(item: ShelfItem) = viewModelScope.launch { c.medicationRepository.remove(item.id) }
}
