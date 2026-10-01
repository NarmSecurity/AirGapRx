package com.airgaprx.core.kb

import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.Interaction
import com.airgaprx.core.model.KbVersion

/** A surface form (canonical name, synonym or brand) that maps to an ingredient. */
data class LexiconTerm(val term: String, val ingredientId: String, val isCanonical: Boolean)

/**
 * Read-only view of the interaction knowledge base. In the app this is backed by the
 * signed SQLite file; in tests by [InMemoryKnowledgeBase]. The repository in the app is the
 * single access path, so the integrity check in [KbVerifier] cannot be bypassed (R4).
 */
interface KnowledgeBase {
    val version: KbVersion
    fun ingredient(id: String): Ingredient?
    fun ingredients(): Collection<Ingredient>
    fun terms(): List<LexiconTerm>
    /** All records for the unordered pair (a, b). */
    fun interactionsBetween(a: String, b: String): List<Interaction>
}

class InMemoryKnowledgeBase(
    ingredients: Collection<Ingredient>,
    private val terms: List<LexiconTerm>,
    interactions: Collection<Interaction>,
    override val version: KbVersion = KbVersion(
        releaseId = "in-memory",
        compiledAt = "n/a",
        dbSha256 = "n/a",
        sources = emptyList(),
        ingredientCount = ingredients.size,
        interactionCount = interactions.size,
    ),
) : KnowledgeBase {
    private val byId = ingredients.associateBy { it.id }
    private val pairs: Map<Pair<String, String>, List<Interaction>> =
        interactions.groupBy { key(it.ingredientA, it.ingredientB) }

    override fun ingredient(id: String) = byId[id]
    override fun ingredients(): Collection<Ingredient> = byId.values
    override fun terms() = terms
    override fun interactionsBetween(a: String, b: String) = pairs[key(a, b)].orEmpty()

    companion object {
        fun key(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a
    }
}
