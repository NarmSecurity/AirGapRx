package com.airgaprx.app.ui.results

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgaprx.app.ui.common.DevDataBanner
import com.airgaprx.app.ui.common.Disclaimer
import com.airgaprx.app.ui.common.PrintedLabel
import com.airgaprx.app.ui.common.SeverityChip
import com.airgaprx.app.ui.common.appViewModel
import com.airgaprx.app.ui.common.plural
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
            title = { Text(if (majors.size == 1) "Major interaction found" else "${majors.size} major interactions found") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    majors.forEach {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SeverityChip(it.severity)
                            Text("${it.first.name} + ${it.second.name}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Text("Do not stop or change any medicine on your own. Talk to your pharmacist or prescriber.", style = MaterialTheme.typography.bodyLarge)
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
        item { Verdict(s) }
        item { DevDataBanner(s.kb, Modifier.fillMaxWidth().padding(bottom = 6.dp)) }

        itemsIndexed(main, key = { _, iv -> "m${iv.index}" }) { _, iv -> AlertRow(iv.value) { onOpenAlert(iv.index) } }

        if (minor.isNotEmpty()) {
            item {
                TextButton(onClick = { showMinor = !showMinor }) {
                    Text(if (showMinor) "Hide ${minor.size} minor" else "Show ${plural(minor.size, "minor interaction")}")
                }
            }
            if (showMinor) itemsIndexed(minor, key = { _, iv -> "n${iv.index}" }) { _, iv -> AlertRow(iv.value) { onOpenAlert(iv.index) } }
        }

        if (s.unchecked.isNotEmpty()) item {
            PrintedLabel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("Not checked", style = MaterialTheme.typography.titleSmall)
                Text(
                    s.unchecked.joinToString { it.displayName } +
                        " is not in the knowledge base, so its interactions are unknown.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Sources: " + s.kb.sources.joinToString("; "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            Disclaimer(Modifier.padding(top = 8.dp))
            Text(
                "Evaluated on-device in ${s.timings.totalMs} ms",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The answer first, in words: how many interactions there are to raise with a pharmacist. */
@Composable
private fun Verdict(s: ResultsUi.Done) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val n = s.alerts.size
        Text(
            when (n) {
                0 -> "No interactions found in the offline knowledge base"
                1 -> "1 interaction to discuss with your pharmacist"
                else -> "$n interactions to discuss with your pharmacist"
            },
            style = MaterialTheme.typography.displaySmall,
        )
        if (n == 0) Text(
            "This doesn't prove there is no interaction. The knowledge base doesn't cover everything.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Checked ${plural(s.checkedCount, "ingredient")} against knowledge base ${s.kb.releaseId}.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlertRow(alert: Alert, onClick: () -> Unit) {
    PrintedLabel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityChip(alert.severity)
                Text(
                    if (alert.type == AlertType.DUPLICATE_INGREDIENT) "${alert.first.name} in more than one product"
                    else "${alert.first.name} + ${alert.second.name}",
                    style = MaterialTheme.typography.titleLarge,
                )
                alert.evidence.firstOrNull()?.mechanism?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
