package com.airgaprx.app.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.airgaprx.app.ui.common.KindLine
import com.airgaprx.app.ui.common.PrintedLabel
import com.airgaprx.app.ui.common.appViewModel
import com.airgaprx.app.ui.common.kindDescription
import com.airgaprx.core.model.Candidate
import com.airgaprx.core.model.MentionSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val vm = appViewModel { ReviewViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ui.saved) { if (ui.saved) onSaved() }
    BackHandler(enabled = ui.saving) { /* Finish the product transaction before leaving. */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check what was read") },
                navigationIcon = { IconButton(onClick = onBack, enabled = !ui.saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.pendingCount > 0) Text(
                            if (ui.pendingCount == 1) "1 item needs your decision before saving."
                            else "${ui.pendingCount} items need your decision before saving.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = vm::save, enabled = ui.canSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(if (ui.saving) "Saving…" else "Add to my medicines")
                        }
                    }
                }
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
                    enabled = !ui.saving,
                    supportingText = { Text("Helps spot the same ingredient in two products.") },
                )
            }
            items(ui.rows.filter { it.state != RowState.Removed }, key = { it.id }) { row -> RowCard(row, vm, enabled = !ui.saving, onChange = { replacing = row.id }) }
            item {
                TextButton(onClick = { searching = true }, enabled = !ui.saving) {
                    Icon(Icons.Outlined.Search, null); Text("Add a missing ingredient")
                }
            }
        }
    }

    if (searching) SearchDialog(vm, onPick = vm::addManual, onDismiss = { searching = false })
    replacing?.let { id -> SearchDialog(vm, onPick = { vm.replace(id, it) }, onDismiss = { replacing = null }) }
}

@Composable
private fun RowCard(row: ReviewRow, vm: ReviewViewModel, enabled: Boolean, onChange: () -> Unit) {
    PrintedLabel(
        Modifier.fillMaxWidth(),
        color = if (row.pending) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Read as \"${row.mention.text}\"" + if (row.mention.source == MentionSource.LLM) " (language model)" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val s = row.state) {
                is RowState.Accepted -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(s.ingredient.name, style = MaterialTheme.typography.titleLarge)
                        KindLine(s.ingredient.kind)
                        // Fuzzy (non-exact) and model-proposed matches are accepted provisionally; say so.
                        if (s.score < 1.0 || row.mention.source == MentionSource.LLM) Text(
                            "Matched approximately. Check this is right.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onChange, enabled = enabled) { Text("Change") }
                    IconButton(onClick = { vm.remove(row.id) }, enabled = enabled) { Icon(Icons.Outlined.Close, "Remove ${s.ingredient.name}") }
                }
                is RowState.Choose -> {
                    Text("Which of these is it?", style = MaterialTheme.typography.titleSmall)
                    s.options.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = false, enabled = enabled, role = Role.RadioButton) { vm.choose(row.id, c) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = false, onClick = null)
                            CandidateText(c, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                        }
                    }
                    TextButton(onClick = { vm.noneOfThese(row.id) }, enabled = enabled) { Text("None of these") }
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
                        OutlinedButton(onClick = { vm.keepUnchecked(row.id) }, enabled = enabled) { Text("Keep") }
                        OutlinedButton(onClick = { vm.remove(row.id) }, enabled = enabled) { Text("Not an ingredient") }
                    }
                }
                RowState.Removed -> Unit
            }
        }
    }
}

@Composable
private fun SearchDialog(vm: ReviewViewModel, onPick: (com.airgaprx.core.model.Ingredient) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { vm.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an ingredient") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Name or brand") })
                results.forEach { c ->
                    CandidateText(
                        c,
                        Modifier.fillMaxWidth()
                            .selectable(selected = false, role = Role.Button) { onPick(c.ingredient); onDismiss() }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A knowledge-base match as name over type, the way a dispensing label prints it. */
@Composable
private fun CandidateText(c: Candidate, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(c.ingredient.name, style = MaterialTheme.typography.titleSmall)
        Text(kindDescription(c.ingredient.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
