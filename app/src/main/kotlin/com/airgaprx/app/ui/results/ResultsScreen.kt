package com.airgaprx.app.ui.results

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgaprx.app.ui.common.Disclaimer
import com.airgaprx.app.ui.common.SeverityChip
import com.airgaprx.app.ui.common.appViewModel
import com.airgaprx.app.ui.common.style
import com.airgaprx.app.ui.theme.SeverityColors
import com.airgaprx.core.model.Alert
import com.airgaprx.core.model.AlertType
import com.airgaprx.core.model.Severity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(onBack: () -> Unit, onOpenAlert: (Int) -> Unit) {
    val vm = appViewModel { InteractionViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Interaction check") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        when (val s = ui) {
            ResultsUi.Evaluating -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ResultsUi.Failed -> Text(s.message, Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.error)
            is ResultsUi.Done -> DoneContent(s, Modifier.padding(padding), onOpenAlert)
        }
    }

    // R5: a Major interaction interrupts the user and must be acknowledged before the list is usable.
    val done = ui as? ResultsUi.Done
    if (done?.needsAcknowledgement == true) {
        val majors = done.alerts.filter { it.interruptive }
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.ErrorOutline, null, tint = SeverityColors.major) },
            title = { Text(if (majors.size == 1) "Major interaction found" else "${majors.size} major interactions found") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    majors.forEach { Text("• ${it.first.name} + ${it.second.name}", style = MaterialTheme.typography.titleSmall) }
                    Text("Do not stop or change any medicine on your own. Talk to your pharmacist or prescriber.")
                }
            },
            confirmButton = { Button(onClick = vm::acknowledge) { Text("I understand") } },
        )
    }
}

@Composable
private fun DoneContent(s: ResultsUi.Done, modifier: Modifier, onOpenAlert: (Int) -> Unit) {
    var showMinor by remember { mutableStateOf(false) }
    val nonInterruptiveLow = setOf(Severity.MINOR)
    val main = s.alerts.withIndex().filter { it.value.severity !in nonInterruptiveLow }
    val minor = s.alerts.withIndex().filter { it.value.severity in nonInterruptiveLow }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Checked ${s.checkedCount} ingredient(s) against knowledge base ${s.kb.releaseId}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (s.alerts.isEmpty()) item { NoInteractionsCard() }

        itemsIndexed(main, key = { _, iv -> "m${iv.index}" }) { _, iv -> AlertCard(iv.value) { onOpenAlert(iv.index) } }

        if (minor.isNotEmpty()) {
            item {
                TextButton(onClick = { showMinor = !showMinor }) {
                    Text(if (showMinor) "Hide ${minor.size} minor" else "Show ${minor.size} minor interaction(s)")
                }
            }
            if (showMinor) itemsIndexed(minor, key = { _, iv -> "n${iv.index}" }) { _, iv -> AlertCard(iv.value) { onOpenAlert(iv.index) } }
        }

        if (s.unchecked.isNotEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Not checked", style = MaterialTheme.typography.titleSmall)
                    Text(
                        s.unchecked.joinToString { it.displayName } +
                            " — not in the knowledge base, so interactions involving them are unknown.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Text("Sources: " + s.kb.sources.joinToString("; "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Disclaimer(Modifier.padding(top = 8.dp))
            Text(
                "Evaluated on-device in ${s.timings.totalMs} ms",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AlertCard(alert: Alert, onClick: () -> Unit) {
    val st = alert.severity.style()
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SeverityChip(alert.severity)
                Text(
                    if (alert.type == AlertType.DUPLICATE_INGREDIENT) "${alert.first.name} in more than one product"
                    else "${alert.first.name} + ${alert.second.name}",
                    style = MaterialTheme.typography.titleMedium,
                )
                alert.evidence.firstOrNull()?.mechanism?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Details", tint = st.fg)
        }
    }
}

@Composable
private fun NoInteractionsCard() {
    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp)) {
                Text("No interactions found in the offline knowledge base", style = MaterialTheme.typography.titleSmall)
                Text(
                    "This doesn't prove there is no interaction. The knowledge base doesn't cover everything.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
