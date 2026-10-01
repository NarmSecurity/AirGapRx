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
    println("Knowledge base: ${kb.ingredients().size} ingredients, ${kb.terms().size} terms")

    normalisation(File(evalDir, "normalisation_cases.csv"), IngredientNormaliser(lexicon), lexicon, File(outDir, "normalisation_results.csv"))
    detection(File(evalDir, "detection_pairs.csv"), kb, lexicon, File(outDir, "detection_results.csv"))
}

private fun normalisation(cases: File, normaliser: IngredientNormaliser, lexicon: Lexicon, out: File) {
    if (!cases.exists()) { println("skip: ${cases.name} not found"); return }
    val rows = Csv.read(cases)
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
    println("  -> ${out.relativeTo(out.parentFile.parentFile.parentFile)}")
}

private fun detection(cases: File, kb: InMemoryKnowledgeBase, lexicon: Lexicon, out: File) {
    if (!cases.exists()) { println("skip: ${cases.name} not found"); return }
    val engine = InteractionEngine(kb)
    var tp = 0; var fn = 0; var fp = 0; var tn = 0; var skipped = 0
    val report = StringBuilder("a,b,expected,detected,severity,verdict\n")
    for (r in Csv.read(cases)) {
        val a = lexicon.lookup(r.getValue("a")).firstOrNull()?.ingredient
        val b = lexicon.lookup(r.getValue("b")).firstOrNull()?.ingredient
        val expected = r.getValue("expected").trim().lowercase() in setOf("yes", "interacting", "1", "true")
        if (a == null || b == null) { skipped++; report.append("${Csv.quote(r.getValue("a"))},${Csv.quote(r.getValue("b"))},$expected,,,not_in_vocabulary\n"); continue }
        val alerts = engine.evaluate(listOf(ShelfEntry(1, a, null), ShelfEntry(2, b, null)))
        val detected = alerts.isNotEmpty()
        val verdict = when { expected && detected -> { tp++; "TP" }; expected -> { fn++; "FN" }; detected -> { fp++; "FP" }; else -> { tn++; "TN" } }
        report.append("${Csv.quote(a.name)},${Csv.quote(b.name)},$expected,$detected,${alerts.firstOrNull()?.severity ?: ""},$verdict\n")
    }
    out.writeText(report.toString())
    println("\nCriterion 5: detection")
    println("  recall ${pct(tp, tp + fn)} (TP $tp, FN $fn) - pass requires >= 95%")
    println("  false-positive rate ${pct(fp, fp + tn)} (FP $fp, TN $tn) - pass requires <= 5%")
    if (skipped > 0) println("  $skipped pair(s) skipped: ingredient not in vocabulary (report as a coverage limitation)")
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

