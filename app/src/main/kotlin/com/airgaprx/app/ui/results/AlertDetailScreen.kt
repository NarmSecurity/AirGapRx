package com.airgaprx.app.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.airgaprx.app.AirGapApp
import com.airgaprx.app.data.KbState
import com.airgaprx.app.ui.common.KindChip
import com.airgaprx.app.ui.common.SeverityChip
import com.airgaprx.core.model.AlertType

/** Per-alert detail: mechanism, what to do, and provenance (M1 ExplanationGenerator output). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(index: Int, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AirGapApp).container
    val alert = container.lastAlerts.getOrNull(index)
    val kb = (container.kbRepository.state.value as? KbState.Ready)?.kb
    val explanation = remember(alert, kb) { if (alert != null && kb != null) container.explainer.explain(alert, kb.version) else null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Details") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (alert == null || explanation == null) {
            Text("This result is no longer available.", Modifier.padding(padding).padding(16.dp))
        } else Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SeverityChip(alert.severity)
            Text(explanation.headline, style = MaterialTheme.typography.headlineSmall)
            if (alert.type == AlertType.INTERACTION) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${alert.first.name}"); KindChip(alert.first.kind)
                    Text("${alert.second.name}"); KindChip(alert.second.kind)
                }
            }
            Text(explanation.summary, style = MaterialTheme.typography.bodyLarge)
            Section("Why it happens", explanation.mechanism ?: "The source records the interaction but does not describe the mechanism.")
            Section("What to do", explanation.management ?: "Ask your pharmacist or prescriber whether these can be taken together.")
            HorizontalDivider()
            Text("Where this comes from", style = MaterialTheme.typography.titleSmall)
            explanation.provenance.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text(explanation.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
