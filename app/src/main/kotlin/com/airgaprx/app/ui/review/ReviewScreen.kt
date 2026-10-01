package com.airgaprx.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HelpCenter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgaprx.app.ui.common.KindChip
import com.airgaprx.app.ui.common.appViewModel
import com.airgaprx.core.model.MentionSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val vm = appViewModel { ReviewViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(ui.saved) { if (ui.saved) onSaved() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check what was read") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (ui.pendingCount > 0) Text(
                    "${ui.pendingCount} item(s) need your decision before saving.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = vm::save, enabled = ui.canSave, modifier = Modifier.fillMaxWidth()) { Text("Add to my medicines") }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    ui.product, vm::setProduct, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Product name (optional)") },
                    supportingText = { Text("Helps spot the same ingredient in two products.") },
                )
            }
            items(ui.rows.filter { it.state != RowState.Removed }, key = { it.id }) { row -> RowCard(row, vm) }
            item {
                TextButton(onClick = { searching = true }) {
                    Icon(Icons.Outlined.Search, null); Text("Add a missing ingredient")
                }
            }
        }
    }

    if (searching) SearchDialog(vm, onDismiss = { searching = false })
}

@Composable
private fun RowCard(row: ReviewRow, vm: ReviewViewModel) {
    val pendingColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    Card(Modifier.fillMaxWidth(), colors = if (row.pending) pendingColors else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Read as \"${row.mention.text}\"" + if (row.mention.source == MentionSource.LLM) " (language model)" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val s = row.state) {
                is RowState.Accepted -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(s.ingredient.name, style = MaterialTheme.typography.titleMedium)
                        KindChip(s.ingredient.kind)
                    }
                    IconButton(onClick = { vm.remove(row.id) }) { Icon(Icons.Outlined.Close, "Remove ${s.ingredient.name}") }
                }
                is RowState.Choose -> {
                    Text("Which of these is it?", style = MaterialTheme.typography.titleSmall)
                    s.options.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = false, role = Role.RadioButton) { vm.choose(row.id, c) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = false, onClick = null)
                            Text("${c.ingredient.name} · ${c.ingredient.kind.label}", Modifier.padding(start = 8.dp))
                        }
                    }
                    TextButton(onClick = { vm.noneOfThese(row.id) }) { Text("None of these") }
                }
                is RowState.Unknown -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.HelpCenter, null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            if (s.keep == true) "Kept, but it won't be checked (not in the knowledge base)."
                            else "Not in the knowledge base. Interactions for it can't be checked.",
                            Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (s.keep == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.keepUnchecked(row.id) }) { Text("Keep") }
                        OutlinedButton(onClick = { vm.remove(row.id) }) { Text("Not an ingredient") }
                    }
                }
                RowState.Removed -> Unit
            }
        }
    }
}

@Composable
private fun SearchDialog(vm: ReviewViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { vm.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an ingredient") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Name or brand") })
                results.forEach { c ->
                    TextButton(onClick = { vm.addManual(c.ingredient); onDismiss() }) { Text("${c.ingredient.name} · ${c.ingredient.kind.label}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
