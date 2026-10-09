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

private fun normalisation(cases: File, normaliser: IngredientNormaliser, lexicon: Lexicon, out: File) {
    val rows = requireCases(cases, 100, "Criterion 4")
    var autoCorrect = 0; var confirmCorrect = 0; var wrong = 0; var mappedTotal = 0
    var unmappedTotal = 0; var unmappedEscalated = 0
    val report = StringBuilder("input,expected,outcome,top_candidate,verdict\n")
    for (r in rows) {
        val input = r.getValue("input"); val expected = r.getValue("expected").trim()
        val res = normaliser.normalise(Mention(input, MentionSource.MANUAL)).resolution
        val expectedId = if (expected.equals("UNMAPPED", true)) null else lexicon.lookup(expected).firstOrNull()?.ingredient?.id
        val (outcome, top) = when (res) {
            is Resolution.Resolved -> "resolved" to res.candidate.ingredient.id
            is Resolution.Ambiguous -> "ambiguous" to res.options.joinToString("|") { it.ingredient.id }
            Resolution.Unrecognised -> "unrecognised" to ""
        }
        val verdict = if (expectedId == null) {
            unmappedTotal++
            if (res !is Resolution.Resolved) { unmappedEscalated++; "escalated" } else "WRONG_silent_guess"
        } else {
            mappedTotal++
            when {
                res is Resolution.Resolved && res.candidate.ingredient.id == expectedId -> { autoCorrect++; "correct" }
                res is Resolution.Ambiguous && res.options.any { it.ingredient.id == expectedId } -> { confirmCorrect++; "correct_after_confirmation" }
                res is Resolution.Resolved -> { wrong++; "WRONG_silent_guess" }
                else -> { wrong++; "missed" }
            }
        }
        report.append(listOf(input, expected, outcome, top, verdict).joinToString(",") { Csv.quote(it) }).append('\n')
    }
    out.writeText(report.toString())
    println("\nCriterion 4: normalisation (${rows.size} cases)")
    println("  mapped terms:   ${pct(autoCorrect, mappedTotal)} auto-correct, ${pct(autoCorrect + confirmCorrect, mappedTotal)} correct incl. confirmation, $wrong wrong/missed")
    println("  unmapped terms: ${pct(unmappedEscalated, unmappedTotal)} escalated (pass requires 100%)")
    println("  -> ${out.path}")
}

/**
 * Criterion 5, reported three ways so incomplete coverage cannot inflate the headline:
 *  - coverage: share of pairs whose two inputs both normalise to a KB ingredient;
 *  - covered-pair recall / false-positive rate: how well the engine does on what it covers;
 *  - end-to-end recall: over ALL interacting pairs, counting an uncovered pair as a miss
 *    (the user would get no alert). This is the number a user experiences.
 * Inputs go through the same IngredientNormaliser the app uses. An Ambiguous input is resolved
 * to its first option and counted separately as "needs user confirmation".
 */
private fun detection(cases: File, kb: InMemoryKnowledgeBase, normaliser: IngredientNormaliser, out: File) {
    val rows = requireCases(cases, 100, "Criterion 5")
    val engine = InteractionEngine(kb)
    var tp = 0; var fn = 0; var fp = 0; var tn = 0
    var posTotal = 0; var negTotal = 0; var posUncovered = 0; var negUncovered = 0; var needsConfirm = 0
    val report = StringBuilder("a,b,expected,a_resolved,b_resolved,covered,needs_confirmation,detected,severity,verdict\n")
    fun resolve(text: String): Pair<com.airgaprx.core.model.Ingredient?, Boolean> =
        when (val r = normaliser.normalise(Mention(text, MentionSource.MANUAL)).resolution) {
            is Resolution.Resolved -> r.candidate.ingredient to false
            is Resolution.Ambiguous -> r.options.first().ingredient to true
            Resolution.Unrecognised -> null to false
        }
    for (r in rows) {
        val expected = r.getValue("expected").trim().lowercase() in setOf("yes", "interacting", "1", "true")
        if (expected) posTotal++ else negTotal++
        val (a, ca) = resolve(r.getValue("a")); val (b, cb) = resolve(r.getValue("b"))
        val covered = a != null && b != null
        val confirm = ca || cb
        if (confirm) needsConfirm++
        var detected = false; var severity = ""
        val verdict = if (!covered) {
            if (expected) { posUncovered++; "FN_uncovered" } else { negUncovered++; "TN_uncovered" }
        } else {
            val alerts = engine.evaluate(listOf(ShelfEntry(1, a!!, null), ShelfEntry(2, b!!, null)))
            detected = alerts.isNotEmpty(); severity = alerts.firstOrNull()?.severity?.name ?: ""
            when { expected && detected -> { tp++; "TP" }; expected -> { fn++; "FN" }; detected -> { fp++; "FP" }; else -> { tn++; "TN" } }
        }
        report.append(listOf(r.getValue("a"), r.getValue("b"), expected.toString(), a?.id ?: "", b?.id ?: "",
            covered.toString(), confirm.toString(), detected.toString(), severity, verdict).joinToString(",") { Csv.quote(it) }).append('\n')
    }
    out.writeText(report.toString())
    println("\nCriterion 5: detection (${posTotal} interacting, ${negTotal} non-interacting pairs)")
    println("  coverage: interacting ${pct(posTotal - posUncovered, posTotal)}, non-interacting ${pct(negTotal - negUncovered, negTotal)}")
    println("  covered pairs: recall ${pct(tp, tp + fn)}, false-positive rate ${pct(fp, fp + tn)}")
    println("  END-TO-END recall (uncovered = missed): ${pct(tp, posTotal)} - M1 pass requires >= 95%")
    println("  false-positive rate over all non-interacting pairs: ${pct(fp, negTotal)} - M1 pass requires <= 5%")
    if (needsConfirm > 0) println("  $needsConfirm pair(s) needed user confirmation of an ambiguous input (first option assumed)")
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

