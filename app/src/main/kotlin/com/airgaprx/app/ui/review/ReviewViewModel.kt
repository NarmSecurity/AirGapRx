package com.airgaprx.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airgaprx.app.AppContainer
import com.airgaprx.app.data.ShelfDraft
import com.airgaprx.core.model.Candidate
import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.Resolution
import com.airgaprx.core.model.RecognisedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

data class ReviewUi(
    val rows: List<ReviewRow>, val product: String = "", val saved: Boolean = false,
    val saving: Boolean = false, val error: String? = null,
) {
    val pendingCount: Int get() = rows.count { it.pending }
    val canSave: Boolean get() = !saving && !saved && pendingCount == 0 && rows.any { it.state is RowState.Accepted || (it.state as? RowState.Unknown)?.keep == true }
}

/** Holds the disambiguation state required by R3 (M1 ReviewViewModel). */
class ReviewViewModel internal constructor(
    initialItems: List<RecognisedItem>,
    private val persistProduct: suspend (List<ShelfDraft>, String?) -> Unit,
    private val findCandidates: (String) -> List<Candidate>,
    private val didSave: (Int) -> Unit,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    constructor(c: AppContainer) : this(
        c.session.items,
        { entries, product -> c.medicationRepository.addProduct(entries, product) },
        { query -> c.services()?.lexicon?.search(query, limit = 6).orEmpty() },
        { count ->
            // Diagnostics must not turn a committed save into a retry that inserts it twice.
            kotlin.runCatching {
                c.session.readyAtElapsed?.let { ready ->
                    com.airgaprx.app.platform.PerfLog.record(
                        c.context, source = "review", reviewMs = android.os.SystemClock.elapsedRealtime() - ready,
                        outcome = "$count accepted",
                    )
                }
            }
            c.session.readyAtElapsed = null
            c.session.items = emptyList()
        },
    )

    private val _ui = MutableStateFlow(
        ReviewUi(initialItems.mapIndexed { i, item ->
            ReviewRow(i, item.mention, when (val r = item.resolution) {
                is Resolution.Resolved -> RowState.Accepted(r.candidate.ingredient, r.candidate.score)
                is Resolution.Ambiguous -> RowState.Choose(r.options)
                Resolution.Unrecognised -> RowState.Unknown()
            })
        })
    )
    val ui: StateFlow<ReviewUi> = _ui

    private val saveLock = Any()
    private fun set(id: Int, state: RowState) = _ui.update { u ->
        if (u.saving || u.saved) u else u.copy(rows = u.rows.map { if (it.id == id) it.copy(state = state) else it })
    }

    fun choose(id: Int, candidate: Candidate) = set(id, RowState.Accepted(candidate.ingredient, candidate.score))
    fun noneOfThese(id: Int) = set(id, RowState.Unknown())
    fun keepUnchecked(id: Int) = set(id, RowState.Unknown(keep = true))
    fun remove(id: Int) = set(id, RowState.Removed)
    fun setProduct(name: String) = _ui.update { if (it.saving || it.saved) it else it.copy(product = name) }

    fun search(query: String): List<Candidate> =
        if (query.trim().length < 2) emptyList() else findCandidates(query)

    /** User correction of an accepted row (e.g. a fuzzy match that picked the wrong ingredient). */
    fun replace(id: Int, ingredient: Ingredient) = set(id, RowState.Accepted(ingredient, 1.0))

    fun addManual(ingredient: Ingredient) = _ui.update { u ->
        if (u.saving || u.saved) return@update u
        val next = (u.rows.maxOfOrNull { it.id } ?: -1) + 1
        u.copy(rows = u.rows + ReviewRow(next, Mention(ingredient.name, MentionSource.MANUAL), RowState.Accepted(ingredient, 1.0)))
    }

    fun save() {
        val u = synchronized(saveLock) {
            val snapshot = _ui.value
            if (!snapshot.canSave) return
            _ui.value = snapshot.copy(saving = true, error = null)
            snapshot
        }
        val seen = HashSet<String>()
        val entries = buildList {
            u.rows.forEach { row ->
                when (val s = row.state) {
                    is RowState.Accepted -> if (seen.add(s.ingredient.id)) add(ShelfDraft(s.ingredient.id, s.ingredient.name))
                    is RowState.Unknown -> if (s.keep == true) add(ShelfDraft(null, row.mention.text.replaceFirstChar { it.uppercase() }))
                    else -> Unit
                }
            }
        }
        (scopeOverride ?: viewModelScope).launch {
            try {
                persistProduct(entries, u.product.trim().ifEmpty { null })
            } catch (e: CancellationException) {
                _ui.update { it.copy(saving = false) }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = "Could not save this product. Please try again.") }
                return@launch
            }
            runCatching { didSave(u.rows.count { it.state is RowState.Accepted }) }
            _ui.update { it.copy(saved = true, saving = false) }
        }
    }
}
