package com.airgaprx.core.reasoning

import com.airgaprx.core.model.Alert
import com.airgaprx.core.model.Severity

/**
 * Clinical presentation policy, revisable independently of the data (M1 Section 4.1, citing
 * alert-fatigue evidence). Unknown severity is ranked above Minor on the precautionary principle.
 */
data class RankingPolicy(
    val order: List<Severity> = listOf(Severity.MAJOR, Severity.MODERATE, Severity.UNKNOWN, Severity.MINOR),
    val interruptive: Set<Severity> = setOf(Severity.MAJOR),
)

/** Orders alerts and decides which interrupt the user (R5). */
class SeverityRanker(private val policy: RankingPolicy = RankingPolicy()) {
    fun rank(alerts: List<Alert>): List<Alert> = alerts
        .map { it.copy(interruptive = it.severity in policy.interruptive) }
        .sortedWith(
            compareBy<Alert> { policy.order.indexOf(it.severity).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.type.ordinal }
                .thenBy { it.first.name.lowercase() }
                .thenBy { it.second.name.lowercase() }
        )
}
