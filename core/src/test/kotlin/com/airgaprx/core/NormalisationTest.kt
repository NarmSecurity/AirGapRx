package com.airgaprx.core

import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.Resolution
import com.airgaprx.core.normalise.IngredientNormaliser
import com.airgaprx.core.normalise.Lexicon
import com.airgaprx.core.normalise.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun normaliseStripsPunctuationAccentsAndApostrophes() {
        assertEquals("st johns wort", Text.normalise("St. John’s  Wort"))
        assertEquals("cafe", Text.normalise("Café"))
    }

    @Test fun stripDosesRemovesAmountsButKeepsNumericNames() {
        assertEquals("calcium carbonate", Text.stripDoses("Calcium carbonate 1250 mg tablets"))
        assertEquals("calcium carbonate", Text.stripDoses("Calcium carbonate 1250mg"))
        assertEquals("omega 3 fish oil", Text.stripDoses("Omega-3 fish oil 1000 mg"))
        assertEquals("vitamin b 12", Text.stripDoses("Vitamin B 12"))
        // Found by the criterion-4 harness: spelled-out units were not stripped.
        assertEquals("levothyroxine sodium", Text.stripDoses("Levothyroxine sodium 100 microgram"))
    }

    @Test fun editDistanceHandlesTransposition() {
        assertEquals(1, Text.editDistance("warfarin", "warfarn"))
        assertEquals(1, Text.editDistance("warfarin", "wrafarin"))
        assertEquals(0, Text.editDistance("x", "x"))
    }
}

class LexiconTest {
    private val lexicon = Lexicon(Fixtures.kb())

    @Test fun brandNameResolvesExactly() {
        val c = lexicon.lookup("PANADOL")
        assertEquals(Fixtures.paracetamol, c.single().ingredient)
        assertEquals(1.0, c.single().score, 0.0)
    }

    @Test fun ocrErrorIsMatchedFuzzily() {
        val c = lexicon.lookup("warfarn")
        assertEquals(Fixtures.warfarin, c.first().ingredient)
        assertTrue(c.first().score < 1.0)
    }

    @Test fun shortStringsAreExactOnly() {
        assertTrue(lexicon.lookup("irn").isEmpty())
        assertEquals(Fixtures.iron, lexicon.lookup("iron").single().ingredient)
    }

    /** Emulator finding: typing "para" in "Add by name" gave "No match in the knowledge base". */
    @Test fun searchFindsIngredientFromTypedPrefix() {
        val c = lexicon.search("para")
        assertEquals("search results: ${c.map { it.ingredient.name }}", Fixtures.paracetamol, c.firstOrNull()?.ingredient)
    }

    @Test fun searchMatchesTheStartOfAnyWordInATerm() {
        val c = lexicon.search("wort")
        assertTrue("search results: ${c.map { it.ingredient.name }}", c.any { it.ingredient == Fixtures.sjw })
    }

    @Test fun searchPutsExactMatchesFirstAndToleratesTypos() {
        assertEquals(Fixtures.iron, lexicon.search("iron").first().ingredient)
        assertEquals(Fixtures.warfarin, lexicon.search("warfarn").first().ingredient)
        assertTrue("two letters are too few to complete", lexicon.search("pa").isEmpty())
    }

    /** Prefix completion is for manual search only: label text must not be completed into a guess. */
    @Test fun lookupDoesNotCompletePrefixes() {
        assertTrue(lexicon.lookup("para").isEmpty())
    }
}

class IngredientNormaliserTest {
    private val normaliser = IngredientNormaliser(Lexicon(Fixtures.kb()))
    private fun m(text: String) = Mention(text, MentionSource.MANUAL)

    @Test fun exactSynonymIsResolved() {
        val r = normaliser.normalise(m("Hypericum perforatum 300 mg")).resolution
        assertTrue(r is Resolution.Resolved)
        assertEquals(Fixtures.sjw, (r as Resolution.Resolved).candidate.ingredient)
    }

    @Test fun nearMissEscalatesInsteadOfGuessing() {
        val r = normaliser.normalise(m("warfarn")).resolution
        assertTrue("expected Ambiguous but was $r", r is Resolution.Ambiguous)
        assertEquals(Fixtures.warfarin, (r as Resolution.Ambiguous).options.first().ingredient)
    }

    @Test fun unknownIngredientIsEscalated() {
        assertEquals(Resolution.Unrecognised, normaliser.normalise(m("Ashwagandha")).resolution)
    }

    @Test fun duplicatesCollapse() {
        val items = normaliser.normaliseAll(listOf(m("Panadol"), m("paracetamol 500mg"), m("acetaminophen")))
        assertEquals(1, items.size)
    }

    /** Review finding 1: an ambiguous item must not vanish because one option overlaps a resolved one. */
    @Test fun ambiguousItemIsKeptEvenIfAnOptionIsAlreadyResolved() {
        val items = normaliser.normaliseAll(listOf(m("Vitamin K"), m("Vitamin E")))
        assertEquals(items.toString(), 2, items.size)
        assertTrue(items[0].resolution is Resolution.Resolved)
        assertTrue("Vitamin E must stay visible: ${items[1]}", items[1].resolution !is Resolution.Resolved)
    }
}
