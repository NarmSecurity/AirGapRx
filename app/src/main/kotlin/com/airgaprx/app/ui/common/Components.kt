package com.airgaprx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import com.airgaprx.app.AirGapApp
import com.airgaprx.app.AppContainer
import com.airgaprx.app.ui.theme.SeverityColors
import com.airgaprx.core.model.IngredientKind
import com.airgaprx.core.model.Severity

/** Creates a ViewModel with access to the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as AirGapApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}

data class SeverityStyle(val label: String, val icon: ImageVector, val fg: Color, val bg: Color)

fun Severity.style(): SeverityStyle = when (this) {
    Severity.MAJOR -> SeverityStyle("Major", Icons.Outlined.ErrorOutline, SeverityColors.major, SeverityColors.majorContainer)
    Severity.MODERATE -> SeverityStyle("Moderate", Icons.Outlined.WarningAmber, SeverityColors.moderate, SeverityColors.moderateContainer)
    Severity.MINOR -> SeverityStyle("Minor", Icons.Outlined.Info, SeverityColors.minor, SeverityColors.minorContainer)
    Severity.UNKNOWN -> SeverityStyle("Severity unknown", Icons.AutoMirrored.Outlined.HelpOutline, SeverityColors.minor, SeverityColors.minorContainer)
}

@Composable
fun SeverityChip(severity: Severity, modifier: Modifier = Modifier) {
    val s = severity.style()
    Row(
        modifier
            .background(s.bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "${s.label} severity" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(s.icon, contentDescription = null, tint = s.fg, modifier = Modifier.size(16.dp))
        Text(s.label, color = s.fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun KindChip(kind: IngredientKind?) {
    Text(
        kind?.label ?: "Not checked",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Visible trust signal: the device stays offline for every function. */
@Composable
fun OfflineBadge(ok: Boolean, modifier: Modifier = Modifier) {
    val fg = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else SeverityColors.major
    val bg = if (ok) MaterialTheme.colorScheme.primaryContainer else SeverityColors.majorContainer
    Row(
        modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            if (ok) "Offline only · nothing leaves this phone" else "Integrity check failed",
            color = fg, style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun Disclaimer(modifier: Modifier = Modifier) {
    Text(
        com.airgaprx.core.reasoning.ExplanationGenerator.DISCLAIMER,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
