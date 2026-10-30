package com.airgaprx.core.reasoning

import com.airgaprx.core.kb.KnowledgeBase
import com.airgaprx.core.model.Alert
import com.airgaprx.core.model.AlertType
import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.Interaction
import com.airgaprx.core.model.Severity

/** One saved item on the user's medication shelf. Two entries may share an ingredient. */
data class ShelfEntry(val entryId: Long, val ingredient: Ingredient, val productName: String?)

/**
 * Pairwise retrieval against the knowledge base (R4). Retrieval only: it reports what the
 * knowledge base says and does not decide how alerts are ordered or presented.
 */
class InteractionEngine(private val kb: KnowledgeBase) {

    fun evaluate(entries: List<ShelfEntry>): List<Alert> {
        val alerts = ArrayList<Alert>()
        val distinct = entries.map { it.ingredient }.distinctBy { it.id }.sortedBy { it.id }
        for (i in distinct.indices) for (j in i + 1 until distinct.size) {
            val a = distinct[i]; val b = distinct[j]
            val evidence = kb.interactionsBetween(a.id, b.id)
            if (evidence.isNotEmpty()) {
                alerts += Alert(AlertType.INTERACTION, a, b, worst(evidence), evidence)
            }
        }
        // Therapeutic duplication: the same ingredient from two different products (e.g. two
        // paracetamol-containing cold remedies) is a common, avoidable overdose route. Product
        // names are optional display labels, not identities; separate shelf rows remain separate
        // entries even when the user gave them the same name.
        entries.groupBy { it.ingredient.id }
            .filter { (_, group) -> group.mapTo(HashSet()) { it.entryId }.size > 1 }
            .forEach { (_, group) ->
                val ing = group.first().ingredient
                alerts += Alert(AlertType.DUPLICATE_INGREDIENT, ing, ing, Severity.MODERATE, emptyList())
            }
        return alerts
    }

    private fun worst(evidence: List<Interaction>): Severity {
        val known = evidence.map { it.severity }.filter { it != Severity.UNKNOWN }
        return listOf(Severity.MAJOR, Severity.MODERATE, Severity.MINOR).firstOrNull { it in known } ?: Severity.UNKNOWN
    }
}
