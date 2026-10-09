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
