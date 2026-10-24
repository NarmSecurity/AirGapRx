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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgaprx.app.data.KbState
import com.airgaprx.app.ui.common.DevDataBanner
import com.airgaprx.app.ui.common.KindLine
import com.airgaprx.app.ui.common.OfflineBadge
import com.airgaprx.app.ui.common.PrintedLabel
import com.airgaprx.app.ui.common.appViewModel
import com.airgaprx.app.ui.common.kindDescription
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
    val ready = state.kb is KbState.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My medicines", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, contentDescription = "Knowledge base and privacy") }
                },
            )
        },
        bottomBar = {
            ShelfActions(
                count = state.rows.size,
                canCheck = ready && state.checkableCount >= 1 && state.rows.size >= 2,
                canAdd = ready,
                onCheck = onCheck, onScan = onScan, onAdd = { showAdd = true },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineBadge(egressOk, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
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
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.rows, key = { it.item.id }) { row -> ShelfLabel(row, onRemove = { vm.remove(row.item) }) }
                }
            }
        }
    }

    if (showAdd) AddByNameDialog(vm, onDismiss = { showAdd = false })
}

/**
 * One bottom bar instead of a floating button over a button row, so no action is ever covered. The
 * filled button is whichever step comes next: scan while the shelf has fewer than two items, check after.
 */
@Composable
private fun ShelfActions(count: Int, canCheck: Boolean, canAdd: Boolean, onCheck: () -> Unit, onScan: () -> Unit, onAdd: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (count >= 2) {
                    Button(onClick = onCheck, enabled = canCheck, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Check $count medicines for interactions")
                    }
                } else if (count == 1) {
                    Text(
                        "Add one more to check for interactions.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val scanModifier = Modifier.weight(1f).height(52.dp)
                    if (count >= 2) {
                        FilledTonalButton(onClick = onScan, modifier = scanModifier) { ScanLabel() }
                    } else {
                        Button(onClick = onScan, modifier = scanModifier) { ScanLabel() }
                    }
                    OutlinedButton(onClick = onAdd, enabled = canAdd, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Add by name", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanLabel() {
    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
    Text("Scan a label", Modifier.padding(start = 8.dp))
}

@Composable
private fun ShelfLabel(row: ShelfRow, onRemove: () -> Unit) {
    PrintedLabel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.ingredient?.name ?: row.item.displayName, style = MaterialTheme.typography.titleLarge)
                row.item.productName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (row.ingredient == null) {
                    Text(
                        "Not in the knowledge base, so it can't be checked",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    KindLine(row.ingredient.kind)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove ${row.item.displayName}")
            }
        }
    }
}

@Composable
private fun EmptyShelf(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text("Your shelf is empty", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan the label of each medicine or supplement you take. Everything is processed on this phone.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    val isSelected = selected?.id == c.ingredient.id
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = isSelected, role = Role.RadioButton) { selected = c.ingredient; query = c.ingredient.name }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.ingredient.name, style = MaterialTheme.typography.titleSmall)
                            Text(kindDescription(c.ingredient.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSelected) Icon(Icons.Outlined.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                when {
                    query.length >= 2 && results.isEmpty() -> Text("No match in the knowledge base.", style = MaterialTheme.typography.bodySmall)
                    results.isNotEmpty() && selected == null -> Text(
                        "Choose a match to add it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(product, { product = it }, label = { Text("Product name (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let { vm.add(it, product) }; onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
