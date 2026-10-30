package com.airgaprx.core

import com.airgaprx.core.model.AlertType
import com.airgaprx.core.model.Alert
import com.airgaprx.core.model.Interaction
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

    @Test fun duplicateIngredientIsDetectedWhenSeparateEntriesHaveTheSameProductName() {
        val alerts = engine.evaluate(listOf(
            ShelfEntry(101, Fixtures.paracetamol, "Cold medicine"),
            ShelfEntry(102, Fixtures.paracetamol, "Cold medicine"),
        ))
        assertEquals(AlertType.DUPLICATE_INGREDIENT, alerts.single().type)
    }

    @Test fun repeatedEntryDoesNotBecomeADuplicateBecauseItsDisplayNameChanged() {
        val alerts = engine.evaluate(listOf(
            ShelfEntry(101, Fixtures.paracetamol, "Cold medicine"),
            ShelfEntry(101, Fixtures.paracetamol, "Updated product name"),
        ))
        assertTrue(alerts.toString(), alerts.none { it.type == AlertType.DUPLICATE_INGREDIENT })
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

    private fun evidence(severity: Severity, mechanism: String?, management: String?, source: String) =
        Interaction(Fixtures.warfarin.id, Fixtures.aspirin.id, severity, mechanism, management, source, null)

    private fun explain(records: List<Interaction>) = ExplanationGenerator().explain(
        Alert(AlertType.INTERACTION, Fixtures.warfarin, Fixtures.aspirin, Severity.MAJOR, records), kb.version,
    )

    @Test fun explanationUsesTheRecordMatchingTheAlertsSeverity() {
        val result = explain(listOf(
            evidence(Severity.MINOR, "Minor mechanism", "Minor management", "MINOR-SOURCE"),
            evidence(Severity.MAJOR, "Major mechanism", "Major management", "MAJOR-SOURCE"),
        ))
        assertEquals("Major mechanism", result.mechanism)
        assertEquals("Major management", result.management)
        assertTrue(result.provenance.any { it.startsWith("MINOR-SOURCE") })
        assertTrue(result.provenance.any { it.startsWith("MAJOR-SOURCE") })
    }

    @Test fun explanationDoesNotMixFieldsFromDifferentRecords() {
        val result = explain(listOf(
            evidence(Severity.MAJOR, "First source mechanism", null, "FIRST"),
            evidence(Severity.MAJOR, null, "Second source management", "SECOND"),
        ))
        assertEquals(null, result.mechanism)
        assertEquals("Second source management", result.management)
    }

    @Test fun missingMajorDetailsDoNotFallBackToMinorAdvice() {
        val result = explain(listOf(
            evidence(Severity.MINOR, "Minor mechanism", "Minor management", "MINOR-SOURCE"),
            evidence(Severity.MAJOR, " ", null, "MAJOR-SOURCE"),
        ))
        assertEquals(null, result.mechanism)
        assertEquals(null, result.management)
    }
}
