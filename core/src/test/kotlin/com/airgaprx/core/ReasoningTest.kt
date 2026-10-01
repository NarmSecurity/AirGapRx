package com.airgaprx.core

import com.airgaprx.core.model.AlertType
import com.airgaprx.core.model.Severity
import com.airgaprx.core.reasoning.ExplanationGenerator
import com.airgaprx.core.reasoning.InteractionEngine
import com.airgaprx.core.reasoning.SeverityRanker
import com.airgaprx.core.reasoning.ShelfEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningTest {
    private val kb = Fixtures.kb()
    private val engine = InteractionEngine(kb)
    private val ranker = SeverityRanker()

    private val shelf = listOf(
        ShelfEntry(1, Fixtures.warfarin, "Marevan"),
        ShelfEntry(2, Fixtures.aspirin, "Cartia"),
        ShelfEntry(3, Fixtures.ginkgo, "Ginkgo 6000"),
        ShelfEntry(4, Fixtures.paracetamol, "Panadol"),
        ShelfEntry(5, Fixtures.paracetamol, "Cold and Flu PE"),
        ShelfEntry(6, Fixtures.fishOil, "Fish Oil 1000"),
    )

    @Test fun ranksBySeverityAndMarksOnlyMajorInterruptive() {
        val ranked = ranker.rank(engine.evaluate(shelf))
        assertEquals(Severity.MAJOR, ranked.first().severity)
        assertTrue(ranked.first().interruptive)
        assertEquals(Severity.MINOR, ranked.last().severity)
        assertTrue(ranked.drop(1).none { it.interruptive })
        val order = ranked.map { it.severity }
        assertEquals(order, order.sortedBy { listOf(Severity.MAJOR, Severity.MODERATE, Severity.UNKNOWN, Severity.MINOR).indexOf(it) })
    }

    @Test fun detectsDuplicateIngredientAcrossProducts() {
        val dup = engine.evaluate(shelf).filter { it.type == AlertType.DUPLICATE_INGREDIENT }
        assertEquals(Fixtures.paracetamol, dup.single().first)
    }

    @Test fun noInteractionsIsAnExplicitEmptyResult() {
        val alerts = engine.evaluate(listOf(ShelfEntry(1, Fixtures.simvastatin, null), ShelfEntry(2, Fixtures.magnesium, null)))
        assertTrue(alerts.isEmpty())
    }

    @Test fun explanationCarriesProvenanceAndDisclaimer() {
        val alert = ranker.rank(engine.evaluate(shelf)).first()
        val e = ExplanationGenerator().explain(alert, kb.version)
        assertTrue(e.provenance.any { it.startsWith("TEST") })
        assertTrue(e.disclaimer.contains("not medical advice"))
        assertFalse(e.mechanism.isNullOrBlank())
    }
}
