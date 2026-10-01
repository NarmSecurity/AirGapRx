package com.airgaprx.app.ui.kb

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgaprx.app.BuildConfig
import com.airgaprx.app.data.KbState
import com.airgaprx.app.platform.EgressGuard
import com.airgaprx.app.ui.common.Disclaimer
import com.airgaprx.app.ui.common.OfflineBadge
import com.airgaprx.app.ui.common.appViewModel

/** Knowledge base, privacy and diagnostics screen (M1 "knowledge base screen", R1/R4/R7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val vm = appViewModel { AboutViewModel(it) }
    val kb by vm.kb.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val egress = EgressGuard.check(context)

    val kbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::installKb) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(context, it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge base & privacy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Text(it, Modifier.weight(1f)); TextButton(onClick = vm::clearMessage) { Text("OK") }
                    }
                }
            }

            Block("Privacy") {
                OfflineBadge(egress.ok)
                Text(
                    if (egress.ok) "This app declares no network permission, so Android does not let it open a network connection."
                    else "Network permission detected: ${egress.violations.joinToString()}. The build is not trusted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Permissions requested: " + egress.requestedPermissions.joinToString().ifEmpty { "none" }, style = MaterialTheme.typography.bodySmall)
            }

            Block("Knowledge base") {
                when (val s = kb) {
                    KbState.Loading -> Text("Loading…")
                    is KbState.Failed -> Text("Unavailable: ${s.reason}", color = MaterialTheme.colorScheme.error)
                    is KbState.Ready -> {
                        val v = s.kb.version
                        Field("Release", v.releaseId)
                        Field("Compiled", v.compiledAt)
                        Field("Ingredients", v.ingredientCount.toString())
                        Field("Interaction records", v.interactionCount.toString())
                        Text("SHA-256 checksum", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(v.dbSha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        Text("Sources", style = MaterialTheme.typography.labelMedium)
                        v.sources.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Text("Signature verified against the publisher key built into this app.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { kbPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text("Install update from file")
                }
            }

            Block("Label-reading model (optional)") {
                Text(
                    model?.let { "Installed: ${it.name}" }
                        ?: "Not installed. Labels are read with the built-in rule-based parser only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Import .task model") }
                    if (model != null) TextButton(onClick = { vm.removeModel(context) }) { Text("Remove") }
                }
            }

            Block("Diagnostics") {
                val t = vm.lastTimings
                Text(
                    t?.let { "Last scan: " + it.stages.entries.joinToString { (k, v) -> "$k ${v}ms" } + " (total ${it.totalMs}ms)" }
                        ?: "No scan yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            }

            Disclaimer()
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row { Text("$label: ", style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.bodySmall) }
}
