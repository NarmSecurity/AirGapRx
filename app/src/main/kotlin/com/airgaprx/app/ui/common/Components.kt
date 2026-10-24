package com.airgaprx.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import com.airgaprx.app.AirGapApp
import com.airgaprx.app.AppContainer
import com.airgaprx.app.ui.theme.SeverityColors
import com.airgaprx.app.ui.theme.StickerFont
import com.airgaprx.core.model.IngredientKind
import com.airgaprx.core.model.Severity

/** "1 ingredient", "2 ingredients": no "(s)" in the interface. */
fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

/** Creates a ViewModel with access to the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as AirGapApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}

data class SeverityStyle(val label: String, val icon: ImageVector, val fg: Color, val bg: Color)

fun Severity.style(): SeverityStyle = when (this) {
    Severity.MAJOR -> SeverityStyle("Major", Icons.Outlined.ErrorOutline, SeverityColors.onMajor, SeverityColors.major)
    Severity.MODERATE -> SeverityStyle("Moderate", Icons.Outlined.WarningAmber, SeverityColors.onModerate, SeverityColors.moderate)
    Severity.MINOR -> SeverityStyle("Minor", Icons.Outlined.Info, SeverityColors.onMinor, SeverityColors.minor)
    Severity.UNKNOWN -> SeverityStyle("Severity unknown", Icons.AutoMirrored.Outlined.HelpOutline, SeverityColors.onUnknown, SeverityColors.unknown)
}

/** Severity as a pharmacy warning sticker: saturated, near-square, bold condensed type. */
@Composable
fun SeverityChip(severity: Severity, modifier: Modifier = Modifier) {
    val s = severity.style()
    Row(
        modifier
            .background(s.bg, RoundedCornerShape(3.dp))
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = "${s.label} severity" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(s.icon, contentDescription = null, tint = s.fg, modifier = Modifier.size(18.dp))
        Text(s.label, color = s.fg, style = TextStyle(fontFamily = StickerFont, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp))
    }
}

/** A printed dispensing label: white paper, a hairline rule, no shadow. */
@Composable
fun PrintedLabel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val body: @Composable () -> Unit = { Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content) }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border, content = body)
    else Surface(modifier = modifier, shape = shape, color = color, border = border, content = body)
}

/** The product type, printed under the name like the second line of a dispensing label. */
@Composable
fun KindLine(kind: IngredientKind?, modifier: Modifier = Modifier) {
    Text(
        kind?.let { kindDescription(it) } ?: "Not in the knowledge base",
        style = MaterialTheme.typography.bodySmall,
        color = if (kind == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

fun kindDescription(kind: IngredientKind): String = when (kind) {
    IngredientKind.PRESCRIPTION -> "Prescription medicine"
    IngredientKind.OTC -> "Over-the-counter medicine"
    IngredientKind.SUPPLEMENT -> "Supplement"
    IngredientKind.HERBAL -> "Herbal supplement"
    IngredientKind.FOOD -> "Food or drink"
}

/** Visible trust signal: the device stays offline for every function. */
@Composable
fun OfflineBadge(ok: Boolean, modifier: Modifier = Modifier) {
    val fg = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer
    Row(
        if (ok) modifier else modifier.background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            if (ok) "Offline. Nothing leaves this phone." else "Integrity check failed",
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

/** Shown whenever the loaded knowledge base did not pass the release review gate. */
@Composable
fun DevDataBanner(version: com.airgaprx.core.model.KbVersion, modifier: Modifier = Modifier) {
    if (version.isReleaseGrade) return
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Test data, not clinically reviewed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Drug-drug source: ${version.ddiSource}. Use for software testing only.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
