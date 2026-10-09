package com.airgaprx.app.ui.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgaprx.app.data.KbState
import com.airgaprx.app.ui.common.DevDataBanner
import com.airgaprx.app.ui.common.KindChip
import com.airgaprx.app.ui.common.OfflineBadge
import com.airgaprx.app.ui.common.appViewModel
import com.airgaprx.core.model.Ingredient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    egressOk: Boolean,
    onScan: () -> Unit,
    onCheck: () -> Unit,
    onAbout: () -> Unit,
) {
    val vm = appViewModel { ShelfViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My medicines") },
                actions = {
                    IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, contentDescription = "Knowledge base and privacy") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                text = { Text("Scan a label") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineBadge(egressOk, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when (val kb = state.kb) {
                is KbState.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is KbState.Failed -> Text(
                    "Knowledge base unavailable: ${kb.reason}. Interaction checks are disabled.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp),
                )
                is KbState.Ready -> DevDataBanner(kb.kb.version, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            if (state.rows.isEmpty()) {
                EmptyShelf(Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.item.id }) { row -> ShelfCard(row, onRemove = { vm.remove(row.item) }) }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showAdd = true }, enabled = state.kb is KbState.Ready) {
                    Icon(Icons.Outlined.Add, contentDescription = null); Text("Add by name")
                }
                Button(
                    onClick = onCheck,
                    enabled = state.kb is KbState.Ready && state.checkableCount >= 1 && state.rows.size >= 2,
                ) { Text("Check interactions") }
            }
        }
    }

    if (showAdd) AddByNameDialog(vm, onDismiss = { showAdd = false })
}

@Composable
private fun ShelfCard(row: ShelfRow, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.ingredient?.name ?: row.item.displayName, style = MaterialTheme.typography.titleMedium)
                row.item.productName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
                KindChip(row.ingredient?.kind)
                if (row.ingredient == null) Text(
                    "Not in the knowledge base, so it can't be checked",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${row.item.displayName}")
            }
        }
    }
}

@Composable
private fun EmptyShelf(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Your shelf is empty", style = MaterialTheme.typography.titleMedium)
        Text(
            "Scan the label of each medicine or supplement you take. Everything is processed on this phone.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddByNameDialog(vm: ShelfViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var product by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Ingredient?>(null) }
    val results = remember(query) { vm.search(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add by name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it; selected = null }, label = { Text("Ingredient or brand") }, singleLine = true)
                results.take(5).forEach { c ->
                    TextButton(onClick = { selected = c.ingredient; query = c.ingredient.name }) {
                        Text("${c.ingredient.name} · ${c.ingredient.kind.label}")
                    }
                }
                if (query.length >= 2 && results.isEmpty()) Text("No match in the knowledge base.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(product, { product = it }, label = { Text("Product name (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let { vm.add(it, product) }; onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
