package com.airgaprx.core.eval

import com.airgaprx.core.kb.InMemoryKnowledgeBase
import com.airgaprx.core.kb.LexiconTerm
import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.IngredientKind
import com.airgaprx.core.model.Interaction
import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.Resolution
import com.airgaprx.core.model.Severity
import com.airgaprx.core.normalise.IngredientNormaliser
import com.airgaprx.core.normalise.Lexicon
import com.airgaprx.core.reasoning.InteractionEngine
import com.airgaprx.core.reasoning.ShelfEntry
import java.io.File

/**
 * Reproducible evaluation for M1 criteria 4 (normalisation) and 5 (detection recall).
 *
 *     python kb/build_kb.py          # writes the CSV export to build/kb
 *     ./gradlew :core:evaluate       # prints metrics and writes docs/evaluation/results
 *
 * Case files live in docs/evaluation/. Detection pairs must come from a source *independent*
 * of the knowledge base (e.g. Medsafe data sheets), otherwise recall is circular.
 */
fun main(args: Array<String>) {
    val root = File(args.getOrNull(0) ?: ".").absoluteFile
    val kbDir = File(root, "build/kb")
    val evalDir = File(root, "docs/evaluation")
    val outDir = File(evalDir, "results").apply { mkdirs() }
    require(File(kbDir, "terms.csv").exists()) { "Run python kb/build_kb.py first (missing ${kbDir}/terms.csv)" }

    val kb = loadKb(kbDir)
    val lexicon = Lexicon(kb)
    val manifest = java.util.Properties().apply { File(kbDir, "kb.manifest.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    println("Knowledge base ${manifest.getProperty("release_id", "?")}: ${kb.ingredients().size} ingredients, ${kb.terms().size} terms")
    if (manifest.getProperty("data_status") != "release") {
        println("!! DEVELOPMENT knowledge base (drug-drug source: ${manifest.getProperty("ddi_source", "?")}). " +
            "These numbers test the software only and must not be reported as the M1 evaluation.")
    }

    val normaliser = IngredientNormaliser(lexicon)
    normalisation(File(evalDir, "normalisation_cases.csv"), normaliser, lexicon, File(outDir, "normalisation_results.csv"))
    detection(File(evalDir, "detection_pairs.csv"), kb, normaliser, File(outDir, "detection_results.csv"))
}

private fun requireCases(cases: File, target: Int, criterion: String): List<Map<String, String>> {
    require(cases.exists()) { "$criterion: case file ${cases.path} is missing" }
    val rows = Csv.read(cases)
    require(rows.isNotEmpty()) { "$criterion: ${cases.name} has no cases, nothing was evaluated" }
    if (rows.size < target) println("!! $criterion: ${rows.size} cases, below the M1 target of $target")
    return rows
}

/** Maps an expected canonical name to an ingredient by EXACT surface form only (never fuzzy). */
private fun expectedId(lexicon: Lexicon, name: String): String? =
    lexicon.exactMatches(com.airgaprx.core.normalise.Text.normalise(name)).singleOrNull()?.ingredient?.id

/**
 * Criterion 4. Verdicts:
 *  - correct: resolved automatically to the expected ingredient;
 *  - correct_option_offered: ambiguous, and the expected ingredient is among the options. This is a
 *    candidate-retrieval measure; it does NOT show that a user would pick it (that needs the usability study);
 *  - WRONG_silent_guess / missed: errors;
 *  - expected UNMAPPED or not in the KB vocabulary: the system must escalate (anything resolved is a
 *    silent guess). "Not in vocabulary" rows are reported as coverage gaps, not dropped.
 */
private fun normalisation(cases: File, normaliser: IngredientNormaliser, lexicon: Lexicon, out: File) {
    val rows = requireCases(cases, 100, "Criterion 4")
    var autoCorrect = 0; var offered = 0; var wrong = 0; var mappedTotal = 0
    var unmappedTotal = 0; var unmappedEscalated = 0; var gaps = 0
    val report = StringBuilder("input,expected,expected_in_kb,outcome,candidates,verdict\n")
    for (r in rows) {
        val input = r.getValue("input"); val expected = r.getValue("expected").trim()
        val res = normaliser.normalise(Mention(input, MentionSource.MANUAL)).resolution
        val declaredUnmapped = expected.equals("UNMAPPED", true)
        val expId = if (declaredUnmapped) null else expectedId(lexicon, expected)
        if (!declaredUnmapped && expId == null) gaps++
        val (outcome, top) = when (res) {
            is Resolution.Resolved -> "resolved" to res.candidate.ingredient.id
            is Resolution.Ambiguous -> "ambiguous" to res.options.joinToString("|") { it.ingredient.id }
            Resolution.Unrecognised -> "unrecognised" to ""
        }
        val verdict = if (expId == null) {
            unmappedTotal++
            if (res !is Resolution.Resolved) { unmappedEscalated++; if (declaredUnmapped) "escalated" else "escalated_coverage_gap" }
            else "WRONG_silent_guess"
        } else {
            mappedTotal++
            when {
                res is Resolution.Resolved && res.candidate.ingredient.id == expId -> { autoCorrect++; "correct" }
                res is Resolution.Ambiguous && res.options.any { it.ingredient.id == expId } -> { offered++; "correct_option_offered" }
                res is Resolution.Resolved -> { wrong++; "WRONG_silent_guess" }
                else -> { wrong++; "missed" }
            }
        }
        report.append(listOf(input, expected, (expId != null).toString(), outcome, top, verdict).joinToString(",") { Csv.quote(it) }).append('\n')
    }
    out.writeText(report.toString())
    println("\nCriterion 4: normalisation (${rows.size} cases)")
    println("  in-vocabulary terms: ${pct(autoCorrect, mappedTotal)} resolved correctly without asking; " +
        "${pct(offered, mappedTotal)} more had the correct option offered (candidate retrieval, not user choice); $wrong wrong/missed")
    println("  terms that must be escalated: ${pct(unmappedEscalated, unmappedTotal)} escalated (pass requires 100%)")
    if (gaps > 0) println("  $gaps expected ingredient(s) are not in the KB vocabulary: reported as coverage gaps")
    println("  -> ${out.path}")
}

/**
 * Criterion 5. Headline numbers make no assumption about user choices:
 *  - an input that normalises to Ambiguous counts as NOT resolved (the alert depends on a choice the
 *    harness cannot observe);
 *  - coverage: share of pairs whose two inputs both resolve automatically;
 *  - covered-pair recall / false-positive rate;
 *  - end-to-end recall over ALL interacting pairs (uncovered or ambiguous = missed).
 * A secondary, clearly labelled line shows what happens IF the user picked the first suggestion.
 */
private fun detection(cases: File, kb: InMemoryKnowledgeBase, normaliser: IngredientNormaliser, out: File) {
    val rows = requireCases(cases, 100, "Criterion 5")
    val engine = InteractionEngine(kb)
    var tp = 0; var fn = 0; var fp = 0; var tn = 0
    var posTotal = 0; var negTotal = 0; var posUncovered = 0; var negUncovered = 0; var ambiguousPairs = 0
    var tpIfFirst = 0; var fpIfFirst = 0
    val report = StringBuilder("a,b,expected,a_resolved,b_resolved,status,detected,severity,verdict,detected_if_first_option\n")
    fun resolve(text: String): Pair<com.airgaprx.core.model.Ingredient?, com.airgaprx.core.model.Ingredient?> =
        when (val r = normaliser.normalise(Mention(text, MentionSource.MANUAL)).resolution) {
            is Resolution.Resolved -> r.candidate.ingredient to r.candidate.ingredient
            is Resolution.Ambiguous -> null to r.options.first().ingredient
            Resolution.Unrecognised -> null to null
        }
    fun alerts(a: com.airgaprx.core.model.Ingredient, b: com.airgaprx.core.model.Ingredient) =
        engine.evaluate(listOf(ShelfEntry(1, a, null), ShelfEntry(2, b, null)))
    for (r in rows) {
        val expected = r.getValue("expected").trim().lowercase() in setOf("yes", "interacting", "1", "true")
        if (expected) posTotal++ else negTotal++
        val (a, aFirst) = resolve(r.getValue("a")); val (b, bFirst) = resolve(r.getValue("b"))
        val ambiguous = (a == null && aFirst != null) || (b == null && bFirst != null)
        val status = when { a != null && b != null -> "covered"; ambiguous -> "needs_user_choice"; else -> "not_in_kb" }
        if (ambiguous && status != "covered") ambiguousPairs++
        val ifFirst = if (aFirst != null && bFirst != null) alerts(aFirst, bFirst).isNotEmpty() else false
        if (ifFirst && expected) tpIfFirst++
        if (ifFirst && !expected) fpIfFirst++
        var detected = false; var severity = ""
        val verdict = if (status != "covered") {
            if (expected) { posUncovered++; "FN_$status" } else { negUncovered++; "TN_$status" }
        } else {
            val al = alerts(a!!, b!!)
            detected = al.isNotEmpty(); severity = al.firstOrNull()?.severity?.name ?: ""
            when { expected && detected -> { tp++; "TP" }; expected -> { fn++; "FN" }; detected -> { fp++; "FP" }; else -> { tn++; "TN" } }
        }
        report.append(listOf(r.getValue("a"), r.getValue("b"), expected.toString(), a?.id ?: "", b?.id ?: "",
            status, detected.toString(), severity, verdict, ifFirst.toString()).joinToString(",") { Csv.quote(it) }).append('\n')
    }
    out.writeText(report.toString())
    println("\nCriterion 5: detection (${posTotal} interacting, ${negTotal} non-interacting pairs)")
    println("  coverage (both inputs resolved without a user choice): interacting ${pct(posTotal - posUncovered, posTotal)}, " +
        "non-interacting ${pct(negTotal - negUncovered, negTotal)}; $ambiguousPairs pair(s) need a user choice")
    println("  covered pairs: recall ${pct(tp, tp + fn)}, false-positive rate ${pct(fp, fp + tn)}")
    println("  END-TO-END recall (uncovered or ambiguous = missed): ${pct(tp, posTotal)} - M1 pass requires >= 95%")
    println("  false-positive rate over all non-interacting pairs: ${pct(fp, negTotal)} - M1 pass requires <= 5%")
    println("  [assumption, not measured] if the user always picked the first suggestion: " +
        "recall ${pct(tpIfFirst, posTotal)}, false positives ${pct(fpIfFirst, negTotal)}")
    println("  -> ${out.path}")
}

private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.1f%% (%d/%d)".format(100.0 * n / d, n, d)

internal fun loadKb(dir: File): InMemoryKnowledgeBase {
    val ingredients = Csv.read(File(dir, "ingredients.csv")).map {
        Ingredient(it.getValue("id"), it.getValue("name"), IngredientKind.valueOf(it.getValue("kind")))
    }
    val terms = Csv.read(File(dir, "terms.csv")).map { LexiconTerm(it.getValue("term"), it.getValue("ingredient_id"), it["is_canonical"] == "1") }
    val interactions = Csv.read(File(dir, "interactions.csv")).map {
        Interaction(it.getValue("a_id"), it.getValue("b_id"), Severity.parse(it.getValue("severity")), null, null, it.getValue("source"), null)
    }
    return InMemoryKnowledgeBase(ingredients, terms, interactions)
}

/** Minimal RFC 4180 reader: quoted fields, embedded commas and doubled quotes. */
internal object Csv {
    fun read(file: File): List<Map<String, String>> {
        val lines = parse(file.readText(Charsets.UTF_8).removePrefix("﻿"))
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().map { it.trim() }
        return lines.drop(1).filter { row -> row.any { it.isNotBlank() } }
            .map { row -> header.indices.associate { header[it] to row.getOrElse(it) { "" } } }
    }

    fun quote(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val field = StringBuilder()
        var i = 0; var quoted = false
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"' && i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                else if (ch == '"') quoted = false else field.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = ArrayList() }
                '\r' -> Unit
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}

