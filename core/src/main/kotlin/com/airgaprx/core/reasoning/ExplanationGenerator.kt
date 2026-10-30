package com.airgaprx.core.reasoning

import com.airgaprx.core.model.Alert
import com.airgaprx.core.model.AlertType
import com.airgaprx.core.model.KbVersion

/** User-facing explanation of one alert, with provenance and non-diagnostic framing (R8). */
data class Explanation(
    val headline: String,
    val summary: String,
    val mechanism: String?,
    val management: String?,
    val provenance: List<String>,
    val disclaimer: String,
)

class ExplanationGenerator {
    fun explain(alert: Alert, kb: KbVersion): Explanation {
        val severityWord = alert.severity.name.lowercase().replaceFirstChar { it.uppercase() }
        // Explain the severity actually shown. Keep mechanism and management from one record,
        // preferring complete details, then management; never substitute lower-severity advice.
        val detail = alert.evidence.filter { it.severity == alert.severity }.maxByOrNull {
            (if (!it.management.isNullOrBlank()) 2 else 0) + (if (!it.mechanism.isNullOrBlank()) 1 else 0)
        }
        return when (alert.type) {
            AlertType.DUPLICATE_INGREDIENT -> Explanation(
                headline = "${alert.first.name} appears in more than one product",
                summary = "Taking two products that contain the same ingredient can add up to more than the intended dose.",
                mechanism = "Additive exposure to the same active ingredient.",
                management = "Check the combined daily amount with a pharmacist before taking both products.",
                provenance = listOf("Rule: duplicate ingredient check (built in)", kbLine(kb)),
                disclaimer = DISCLAIMER,
            )
            AlertType.INTERACTION -> Explanation(
                headline = "${alert.first.name} + ${alert.second.name}",
                summary = "$severityWord interaction recorded in the offline knowledge base.",
                mechanism = detail?.mechanism?.takeIf(String::isNotBlank),
                management = detail?.management?.takeIf(String::isNotBlank),
                provenance = alert.evidence.map { e ->
                    buildString {
                        append(e.source)
                        append(" (").append(e.severity.name.lowercase()).append(")")
                        e.sourceRef?.takeIf { it.isNotBlank() }?.let { append(", ref: ").append(it) }
                    }
                }.distinct() + kbLine(kb),
                disclaimer = DISCLAIMER,
            )
        }
    }

    private fun kbLine(kb: KbVersion) = "Knowledge base ${kb.releaseId}, compiled ${kb.compiledAt}"

    companion object {
        const val DISCLAIMER =
            "This is information, not medical advice. Do not stop or change any medicine without talking to your pharmacist or prescriber."
    }
}
