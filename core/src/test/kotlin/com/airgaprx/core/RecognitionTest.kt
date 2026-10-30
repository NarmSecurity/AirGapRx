package com.airgaprx.core

import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.OcrResult
import com.airgaprx.core.model.Resolution
import com.airgaprx.core.model.TextLine
import com.airgaprx.core.normalise.IngredientNormaliser
import com.airgaprx.core.normalise.Lexicon
import com.airgaprx.core.pipeline.RecaptureReason
import com.airgaprx.core.pipeline.RecognitionOutcome
import com.airgaprx.core.pipeline.RecognitionPipeline
import com.airgaprx.core.recognition.HybridLabelParser
import com.airgaprx.core.recognition.LabelParser
import com.airgaprx.core.recognition.LexiconLabelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelParserTest {
    private val lexicon = Lexicon(Fixtures.kb())
    private val parser = LexiconLabelParser(lexicon)
    private fun ocr(vararg lines: String) = OcrResult(lines.map { TextLine(it, 0.95f) })

    private val supplementLabel = ocr(
        "MOOD SUPPORT",
        "Hypericum perforatum (St John's Wort)",
        "Each tablet contains:",
        "Hypericum perforatum dry extract 300 mg",
        "Ashwagandha root extract 200 mg",
        "Other ingredients: magnesium stearate, iron oxide red, microcrystalline cellulose",
        "Directions: Adults take 1 tablet daily.",
    )

    @Test fun findsKnownAndUnknownActives() {
        val texts = parser.parse(supplementLabel).map { it.text.lowercase() }
        assertTrue(texts.any { it.contains("hypericum") })
        assertTrue("unknown active must be surfaced: $texts", texts.any { it.contains("ashwagandha") })
    }

    @Test fun excipientsDoNotRaiseMineralMentions() {
        val texts = parser.parse(supplementLabel).map { it.text.lowercase() }
        assertFalse(texts.toString(), texts.any { it == "magnesium" || it == "iron" })
    }

    @Test fun inlineExcipientPhraseIsIgnored() {
        val texts = parser.parse(ocr("Paracetamol 500 mg", "Contains magnesium stearate")).map { it.text }
        assertTrue(texts.contains("paracetamol"))
        assertFalse(texts.toString(), texts.contains("magnesium"))
    }

    @Test fun spotsBrandOnFrontOfPack() {
        val mentions = parser.parse(ocr("Eltroxin", "100 microgram tablets"))
        assertEquals("eltroxin", mentions.single { it.source == MentionSource.LEXICON }.text)
    }

    @Test fun toleratesOcrErrorInDrugName() {
        val mentions = parser.parse(ocr("Ciprofloxacn 500mg"))
        assertTrue(mentions.toString(), mentions.any { it.text.startsWith("ciproflox") })
    }

    /** Review finding 3: an excipient list read first must not hide an active panel read later. */
    @Test fun activesAfterExcipientSectionInSameBlockAreKept() {
        val texts = parser.parse(ocr(
            "Other ingredients: magnesium stearate, cellulose",
            "Active ingredients: Ciprofloxacin 500 mg",
        )).map { it.text }
        assertTrue(texts.toString(), texts.any { it.startsWith("ciprofloxacin") })
    }

    @Test fun newOcrBlockEndsAnExcipientSection() {
        val label = OcrResult(listOf(
            TextLine("Excipients:", 0.95f, block = 0),
            TextLine("microcrystalline cellulose, starch", 0.95f, block = 0),
            TextLine("Eltroxin 100 microgram", 0.95f, block = 1),
        ))
        assertTrue(parser.parse(label).any { it.text == "eltroxin" })
    }

    @Test fun warningTextDoesNotAddOtherMedicines() {
        val texts = parser.parse(ocr(
            "Each tablet contains Paracetamol 500 mg",
            "Warnings: Do not take with warfarin or other medicines containing paracetamol.",
        )).map { it.text }
        assertTrue(texts.contains("paracetamol"))
        assertFalse("warfarin is only mentioned in a warning: $texts", texts.any { it.contains("warfarin") })
    }

    /** Emulator finding: ML Kit can split a warning before the medicine it names. */
    @Test fun warningSplitAcrossOcrBlocksDoesNotAddOtherMedicines() {
        val label = OcrResult(listOf(
            TextLine("Each tablet contains Paracetamol 500 mg", 0.95f, block = 0),
            TextLine("Warnings: Do not take with", 0.95f, block = 1),
            TextLine("warfarin. Consult your doctor.", 0.95f, block = 2),
        ))
        val texts = parser.parse(label).map { it.text }
        assertTrue("the product's active must still be kept: $texts", texts.contains("paracetamol"))
        assertFalse("warfarin is only mentioned in a split warning: $texts", texts.any { it.contains("warfarin") })
    }

    @Test fun containsAtStartOfWarningContinuationDoesNotOpenActivePanel() {
        val label = OcrResult(listOf(
            TextLine("Each tablet contains Paracetamol 500 mg", 0.95f, block = 0),
            TextLine("Warnings: Do not take with a product that", 0.95f, block = 1),
            TextLine("contains warfarin.", 0.95f, block = 2),
        ))
        val texts = parser.parse(label).map { it.text }
        assertTrue(texts.toString(), texts.contains("paracetamol"))
        assertFalse("a continued warning must not add warfarin: $texts", texts.any { it.contains("warfarin") })
    }

    @Test fun containsAfterFinishedWarningInTheSameBlockCanOpenPanel() {
        val texts = parser.parse(ocr(
            "Warnings: Do not take with warfarin.",
            "Contains St John's wort 300 mg",
        )).map { it.text }
        assertTrue("a finished warning must not hide the following active: $texts", texts.any { it.contains("wort") })
        assertFalse(texts.toString(), texts.any { it.contains("warfarin") })
    }

    @Test fun explicitlyDeclaredActivesAreNotRemovedByExcipientPhraseFilter() {
        val texts = parser.parse(ocr(
            "Active ingredients: Calcium hydrogen phosphate 500 mg, Paracetamol 500 mg",
        )).map { it.text }
        assertTrue("declared actives must reach review: $texts", texts.containsAll(listOf("calcium hydrogen phosphate", "paracetamol")))
    }

    /**
     * Review of the declared-active fix: "Each tablet contains" lists the whole composition, so
     * fillers listed there without an "Other ingredients" header must still not raise a mineral.
     */
    @Test fun excipientInsideACompositionPanelDoesNotRaiseAMineral() {
        val texts = parser.parse(ocr("Each tablet contains: Calcium carbonate 600 mg, magnesium stearate")).map { it.text }
        assertTrue(texts.toString(), texts.contains("calcium carbonate"))
        assertFalse("magnesium stearate is a filler, not magnesium: $texts", texts.any { it.contains("magnesium") })
    }

    @Test fun excipientInACompositionPanelContinuedInTheNextBlockDoesNotRaiseAMineral() {
        val label = OcrResult(listOf(
            TextLine("Each tablet contains: Calcium 600 mg", 0.95f, block = 0),
            TextLine("magnesium stearate, silicon dioxide", 0.95f, block = 1),
        ))
        val texts = parser.parse(label).map { it.text }
        assertTrue(texts.toString(), texts.contains("calcium"))
        assertFalse("magnesium stearate is a filler, not magnesium: $texts", texts.any { it.contains("magnesium") })
    }

    @Test fun genericHeaderDoesNotEraseAnExplicitActivePanelDeclaration() {
        for (block in listOf(0, 1)) {
            val label = OcrResult(listOf(
                TextLine("Active ingredients:", 0.95f, block = 0),
                TextLine("Contains calcium hydrogen phosphate 500 mg", 0.95f, block = block),
                TextLine("Other ingredients: magnesium stearate", 0.95f, block = block + 1),
            ))
            val texts = parser.parse(label).map { it.text }
            assertTrue(texts.toString(), texts.contains("calcium hydrogen phosphate"))
            assertFalse(texts.toString(), texts.any { it.contains("magnesium") })
        }
    }

    @Test fun warningContinuesAcrossSeveralOcrBlocks() {
        val label = OcrResult(listOf(
            TextLine("Each tablet contains Paracetamol 500 mg", 0.95f, block = 0),
            TextLine("Warnings: Do not take with", 0.95f, block = 1),
            TextLine("warfarin or a product that contains", 0.95f, block = 2),
            TextLine("aspirin.", 0.95f, block = 3),
        ))
        val texts = parser.parse(label).map { it.text }
        assertEquals(listOf("paracetamol"), texts)
    }

    @Test fun warningContinuationAfterSentenceEndDoesNotAddMedicine() {
        for (continuation in listOf("Or aspirin.", "And aspirin.", "\u2022 Or aspirin.")) {
            val label = OcrResult(listOf(
                TextLine("Each tablet contains Paracetamol 500 mg", 0.95f, block = 0),
                TextLine("Warnings: Do not take with warfarin.", 0.95f, block = 1),
                TextLine(continuation, 0.95f, block = 2),
                TextLine("Hypericum perforatum (St John's Wort) 300 mg", 0.95f, block = 3),
            ))
            val texts = parser.parse(label).map { it.text }
            assertFalse("warning continuation must not add aspirin: $texts", texts.any { it.contains("aspirin") })
            assertFalse(texts.toString(), texts.any { it.contains("warfarin") })
            assertTrue(texts.toString(), texts.contains("paracetamol"))
            assertTrue("a later active must still reach review: $texts", texts.any { it.contains("wort") })
        }
    }

    @Test fun strengthFieldDoesNotBecomeAnIngredient() {
        val labels = listOf(
            ocr("Each tablet contains:", "Warfarn", "Strength: 5 mg"),
            ocr("Each tablet contains: Warfarn; Strength: 5 mg; Ashwagandha 300 mg"),
        )
        for (label in labels) {
            val texts = parser.parse(label).map { it.text }
            assertFalse("a field label is not an ingredient: $texts", texts.contains("strength"))
            assertTrue("the misspelt active must reach review: $texts", texts.contains("warfarn"))
        }
        assertTrue(parser.parse(labels[1]).any { it.text.contains("ashwagandha") })
    }

    @Test fun strengthFieldKeepsUnknownValuesAndNamesWithoutAFieldColon() {
        val texts = parser.parse(ocr(
            "Active ingredients:",
            "Strength: Ashwagandha 300 mg",
            "Strength complex 200 mg",
        )).map { it.text }
        assertTrue(texts.toString(), texts.containsAll(listOf("ashwagandha", "strength complex")))
        assertFalse(texts.toString(), texts.contains("strength"))
    }

    @Test fun conjunctionPrefixesDoNotHideActivesAfterFinishedWarning() {
        for (active in listOf("Oral Eltroxin 100 microgram", "Andrographis. Each tablet contains Eltroxin 100 microgram")) {
            val label = OcrResult(listOf(
                TextLine("Warnings: Do not take with warfarin.", 0.95f, block = 0),
                TextLine(active, 0.95f, block = 1),
            ))
            assertTrue(active, parser.parse(label).any { it.text.contains("eltroxin") })
        }
    }

    @Test fun headerAtLineStartInNewBlockAfterWarningStartsNewPanel() {
        val label = OcrResult(listOf(
            TextLine("Warnings: Do not take with", 0.95f, block = 0),
            TextLine("warfarin.", 0.95f, block = 1),
            TextLine("Active ingredients: Ashwagandha 300 mg, Magnesium 100 mg", 0.95f, block = 2),
        ))
        val texts = parser.parse(label).map { it.text }
        assertTrue("known and unknown actives must reach review: $texts", texts.containsAll(listOf("ashwagandha", "magnesium")))
        assertFalse(texts.toString(), texts.any { it.contains("warfarin") })
    }

    /** Probe of the split-warning fix: a finished warning must not hide actives in the next OCR block. */
    @Test fun activeAfterFinishedWarningInNewBlockIsKept() {
        val label = OcrResult(listOf(
            TextLine("Warnings: Do not take with warfarin.", 0.95f, block = 0),
            TextLine("Hypericum perforatum (St John's Wort) 300 mg", 0.95f, block = 1),
        ))
        val texts = parser.parse(label).map { it.text }
        assertTrue("an active after a finished warning must reach review: $texts", texts.any { it.contains("wort") })
        assertFalse(texts.toString(), texts.any { it.contains("warfarin") })
    }

    @Test fun midLinePanelAfterFinishedWarningInNewBlockIsKept() {
        val label = OcrResult(listOf(
            TextLine("Warnings: Do not take with warfarin.", 0.95f, block = 0),
            TextLine("Mood support. Each tablet contains St John's Wort 300 mg", 0.95f, block = 1),
        ))
        val texts = parser.parse(label).map { it.text }
        assertTrue("an active after a finished warning must reach review: $texts", texts.any { it.contains("wort") })
        assertFalse(texts.toString(), texts.any { it.contains("warfarin") })
    }

    /** Second review: "contains" inside a warning re-opened an active panel and added warfarin. */
    @Test fun containsInsideWarningDoesNotReopenActivePanel() {
        val texts = parser.parse(ocr(
            "Each tablet contains Paracetamol 500 mg",
            "Warnings: Do not take with a product that contains warfarin.",
        )).map { it.text }
        assertTrue(texts.contains("paracetamol"))
        assertFalse("warfarin is only mentioned in a warning: $texts", texts.any { it.contains("warfarin") })
    }

    @Test fun genericHeaderMidSentenceIsNotAPanel() {
        // Mid-sentence "contains" in front-of-pack text: no panel opens (so "Plus" is not escalated),
        // but known names are still spotted.
        val mentions = parser.parse(ocr("This pack contains Panadol Plus"))
        assertTrue(mentions.any { it.text == "panadol" })
        assertFalse(mentions.toString(), mentions.any { it.source == MentionSource.INGREDIENT_SECTION })
    }

    @Test fun headerAtLineStartAfterWarningStartsNewPanel() {
        val texts = parser.parse(ocr(
            "Warnings: do not use if pregnant",
            "Ingredients: Ashwagandha 300 mg, Magnesium 100 mg",
        )).map { it.text }
        assertTrue(texts.toString(), texts.containsAll(listOf("ashwagandha", "magnesium")))
    }

    @Test fun multiLinePanelWithSeveralActivesUnknownsAndMisspellings() {
        val lines = listOf(
            "Each tablet contains:",
            "Calcium carbonate 1250 mg",
            "(equivalent to calcium 500 mg)",
            "Cholecalciferol 25 microgram",
            "Magnesium oxide 100 mg",
            "Zinc citrate 10 mg",
            "Ferrous fumarate 20 mg",
            "Boron 1 mg",
            "Levothyroxne 50 microgram",
            "Lutein 2 mg",
        )
        val texts = parser.parse(OcrResult(lines.map { TextLine(it, 0.95f) })).map { it.text }
        listOf("calcium carbonate", "cholecalciferol", "magnesium oxide", "ferrous fumarate", "boron", "lutein")
            .forEach { assertTrue("missing $it in $texts", texts.contains(it)) }
        assertTrue("misspelt active must reach review: $texts", texts.any { it.startsWith("levothyrox") })
    }

    @Test fun hybridParserSurvivesModelFailure() {
        val failing = LabelParser { throw IllegalStateException("model not loaded") }
        val hybrid = HybridLabelParser(parser, failing)
        assertTrue(hybrid.parse(ocr("Panadol")).isNotEmpty())
    }
}

class RecognitionPipelineTest {
    private val lexicon = Lexicon(Fixtures.kb())
    private val pipeline = RecognitionPipeline(LexiconLabelParser(lexicon), IngredientNormaliser(lexicon))

    @Test fun joinedDoseAndStrengthFieldReachReviewAsOneAmbiguousIngredient() {
        for (active in listOf("Warfarn5 mg", "Warfarn5mg", "Warfarn2.5 mg", "Warfarn2,5mg")) {
            val out = pipeline.process(OcrResult(listOf(
                TextLine("Each tablet contains:", 0.95f),
                TextLine(active, 0.95f),
                TextLine("Strength: 5 mg", 0.95f),
            )))
            assertTrue(out.toString(), out is RecognitionOutcome.Recognised)
            val item = (out as RecognitionOutcome.Recognised).items.single()
            assertTrue(item.toString(), item.resolution is Resolution.Ambiguous)
            assertEquals(Fixtures.warfarin, (item.resolution as Resolution.Ambiguous).options.first().ingredient)
        }
    }

    @Test fun joinedDoseOnFrontOfPackStillReachesReview() {
        val out = pipeline.process(OcrResult(listOf(TextLine("Warfarn5 mg tablets", 0.95f))))
        assertTrue(out.toString(), out is RecognitionOutcome.Recognised)
        val item = (out as RecognitionOutcome.Recognised).items.single()
        assertTrue(item.toString(), item.resolution is Resolution.Ambiguous)
        assertEquals(Fixtures.warfarin, (item.resolution as Resolution.Ambiguous).options.first().ingredient)
    }

    @Test fun lowConfidenceCaptureAsksForRetake() {
        val out = pipeline.process(OcrResult(listOf(TextLine("Warfar1n 5mg tab1ets", 0.31f))))
        assertTrue(out is RecognitionOutcome.NeedsRecapture)
        assertEquals(RecaptureReason.LOW_CONFIDENCE, (out as RecognitionOutcome.NeedsRecapture).reason)
    }

    @Test fun blankCaptureAsksForRetake() {
        val out = pipeline.process(OcrResult(emptyList()))
        assertEquals(RecaptureReason.NO_TEXT, (out as RecognitionOutcome.NeedsRecapture).reason)
    }

    @Test fun goodCaptureProducesReviewItemsAndTimings() {
        val out = pipeline.process(
            OcrResult(listOf(TextLine("Marevan warfarin 5 mg", 0.93f), TextLine("Ashwagandha 200 mg", 0.9f))),
            priorTimings = mapOf("ocr" to 120L),
        )
        out as RecognitionOutcome.Recognised
        assertTrue(out.items.any { (it.resolution as? Resolution.Resolved)?.candidate?.ingredient == Fixtures.warfarin })
        assertTrue(out.timings.stages.keys.containsAll(listOf("ocr", "parse", "normalise")))
    }
}
