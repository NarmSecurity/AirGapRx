package com.airgaprx.core

import com.airgaprx.core.kb.InMemoryKnowledgeBase
import com.airgaprx.core.kb.LexiconTerm
import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.IngredientKind
import com.airgaprx.core.model.IngredientKind.HERBAL
import com.airgaprx.core.model.IngredientKind.OTC
import com.airgaprx.core.model.IngredientKind.PRESCRIPTION
import com.airgaprx.core.model.IngredientKind.SUPPLEMENT
import com.airgaprx.core.model.Interaction
import com.airgaprx.core.model.Severity

/** Small, hand-built knowledge base for unit tests. Not clinical data. */
object Fixtures {
    private fun ing(id: String, name: String, kind: IngredientKind) = Ingredient(id, name, kind)

    val warfarin = ing("warfarin", "Warfarin", PRESCRIPTION)
    val aspirin = ing("acetylsalicylic-acid", "Acetylsalicylic acid", OTC)
    val sjw = ing("st-johns-wort", "St John's wort", HERBAL)
    val sertraline = ing("sertraline", "Sertraline", PRESCRIPTION)
    val ginkgo = ing("ginkgo", "Ginkgo", HERBAL)
    val calcium = ing("calcium", "Calcium", SUPPLEMENT)
    val levothyroxine = ing("levothyroxine", "Levothyroxine", PRESCRIPTION)
    val magnesium = ing("magnesium", "Magnesium", SUPPLEMENT)
    val ciprofloxacin = ing("ciprofloxacin", "Ciprofloxacin", PRESCRIPTION)
    val paracetamol = ing("paracetamol", "Paracetamol", OTC)
    val iron = ing("iron", "Iron", SUPPLEMENT)
    val simvastatin = ing("simvastatin", "Simvastatin", PRESCRIPTION)
    val fishOil = ing("omega-3-fish-oil", "Omega-3 fish oil", SUPPLEMENT)

    private val all = listOf(warfarin, aspirin, sjw, sertraline, ginkgo, calcium, levothyroxine, magnesium,
        ciprofloxacin, paracetamol, iron, simvastatin, fishOil)

    private val synonyms = mapOf(
        aspirin to listOf("aspirin", "cartia"),
        sjw to listOf("st johns wort", "st john wort", "hypericum perforatum", "hypericum"),
        sertraline to listOf("zoloft"),
        ginkgo to listOf("ginkgo biloba"),
        calcium to listOf("calcium carbonate", "calcium citrate"),
        levothyroxine to listOf("thyroxine", "eltroxin", "synthroid"),
        ciprofloxacin to listOf("ciproxin", "cipro"),
        paracetamol to listOf("acetaminophen", "panadol"),
        iron to listOf("ferrous sulfate", "ferrous fumarate", "ferrous sulphate"),
        simvastatin to listOf("zocor"),
        fishOil to listOf("fish oil", "omega 3"),
        warfarin to listOf("marevan", "coumadin"),
    )

    private fun x(a: Ingredient, b: Ingredient, s: Severity, mech: String? = null) =
        Interaction(minOf(a.id, b.id), maxOf(a.id, b.id), s, mech, null, "TEST", null)

    val interactions = listOf(
        x(warfarin, aspirin, Severity.MAJOR, "Additive bleeding risk"),
        x(warfarin, sjw, Severity.MAJOR, "CYP induction lowers warfarin levels"),
        x(sertraline, sjw, Severity.MAJOR, "Serotonin toxicity"),
        x(warfarin, ginkgo, Severity.MODERATE),
        x(levothyroxine, calcium, Severity.MODERATE),
        x(ciprofloxacin, magnesium, Severity.MODERATE),
        x(levothyroxine, iron, Severity.MODERATE),
        x(warfarin, paracetamol, Severity.MINOR),
        x(warfarin, fishOil, Severity.UNKNOWN),
    )

    fun kb() = InMemoryKnowledgeBase(
        ingredients = all,
        terms = all.map { LexiconTerm(it.name, it.id, true) } +
            synonyms.flatMap { (i, list) -> list.map { LexiconTerm(it, i.id, false) } },
        interactions = interactions,
    )
}
