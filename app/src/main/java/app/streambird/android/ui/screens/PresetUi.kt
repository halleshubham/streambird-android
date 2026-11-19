package app.streambird.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.streambird.android.data.Preset
import app.streambird.android.preset.NameCheck
import app.streambird.android.preset.PRESETS_EMPTY_BODY
import app.streambird.android.preset.PRESETS_EMPTY_TITLE
import app.streambird.android.preset.PresetSummaryParts
import app.streambird.android.preset.presetCountText
import app.streambird.android.ui.theme.SbTokens
import app.streambird.android.ui.theme.StreamBirdTheme

// Presets UI building blocks (1.0.16, docs/UX-AUDIT-PRESETS.md). Stateless on purpose: Home and the Customize sheet pass data in and get events out,
// and the @Preview functions at the end render them without a view model. Screenshots of these previews could not be reviewed (no device / emulator).

/** Everything one card shows. [real] false = the implicit "Quick start" card (no menu). */
data class PresetCardModel(
    val name: String,
    val parts: PresetSummaryParts,
    val warnings: List<String>,
    val lastUsed: String?,
    val real: Boolean,
    val prominent: Boolean,
)

private val LightAmberOnPurple = Color(0xFFFDE68A)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetCard(
    m: PresetCardModel,
    busy: Boolean,
    onGo: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onDark = m.prominent
    val text = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (onDark) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant
    val warn = if (onDark) LightAmberOnPurple else warningTextColor()

    val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.Top) {
            // The information area is its own tap target (same as Go live); the menu and the button are siblings, so TalkBack reaches each one.
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Open ${m.name}", role = Role.Button, onClick = onGo),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (m.prominent) Text(if (m.real && m.lastUsed != null && m.lastUsed != "Never used") "LAST USED" else "READY", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.88f))
                Text(m.name, style = if (m.prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, color = text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.parts.channels.isEmpty() && m.parts.missingChannels == 0) ChannelChip("No destination", onDark)
                    m.parts.channels.forEach { ChannelChip(it, onDark) }
                    if (m.parts.missingChannels > 0) ChannelChip("${m.parts.missingChannels} not connected", onDark)
                }
                Text(m.parts.details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = sub)
                m.lastUsed?.takeIf { m.real }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = sub) }
                m.warnings.forEach { w ->
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Heads up: $w" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = warn, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                        Text(w, style = MaterialTheme.typography.bodySmall, color = warn)
                    }
                }
            }
            if (m.real) PresetMenu(m.name, !busy, onEdit, onRename, onDuplicate, onDelete, if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (m.prominent) {
            androidx.compose.material3.Button(
                onClick = onGo, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = "Go live now with ${m.name}" },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SbTokens.accentHover),
            ) { Text("Go live now", fontWeight = FontWeight.Bold) }
        } else {
            Button(onClick = onGo, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Go live with ${m.name}" }) { Text("Go live") }
        }
    }

    if (m.prominent) {
        Column(
            modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(SbTokens.accent, Color(0xFF4C1D95)))).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = body,
        )
    } else {
        Card(modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = body) }
    }
}

@Composable
private fun ChannelChip(text: String, onDark: Boolean) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(CircleShape).background(if (onDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PresetMenu(name: String, enabled: Boolean, onEdit: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options for $name", tint = tint) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, enabled = enabled, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, enabled = enabled, onClick = { open = false; onDuplicate() })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                enabled = enabled, onClick = { open = false; onDelete() },
            )
        }
    }
}

/** The dashed "+ New preset" card; at the limit it is a plain explanation instead (a disabled control that says why). */
@Composable
fun NewPresetCard(count: Int, atLimit: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (atLimit) {
        Card(modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(presetCountText(count), style = MaterialTheme.typography.titleSmall)
                Text(app.streambird.android.preset.LIMIT_REACHED_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val line = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    Column(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).drawBehind {
            drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("+ New preset", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (count > 0) Text(presetCountText(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What a preset is and how to make one: shown on Home while the account has none. */
@Composable
fun PresetsEmptyCard(modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(PRESETS_EMPTY_TITLE)
            Text(PRESETS_EMPTY_BODY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Name field of "Save as preset" and "Rename": counter, inline reason when blank, soft warning for a duplicate, Done closes the keyboard. */
@Composable
fun PresetNameField(value: String, onChange: (String) -> Unit, check: NameCheck, modifier: Modifier = Modifier, label: String = "Preset name", onDone: () -> Unit = {}) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(Preset.MAX_NAME)) }, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth(),
        isError = check.blocking != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onDone() }),
        supportingText = {
            when {
                check.blocking != null -> Text(check.blocking)
                check.warning != null -> Text(check.warning, color = warningTextColor())
                else -> Text("${value.length}/${Preset.MAX_NAME}")
            }
        },
    )
}

/** Tinted status line with an icon-free text that says the state in words (never colour alone). */
@Composable
fun ChangesLine(changes: List<String>, modifier: Modifier = Modifier) {
    val dirty = changes.isNotEmpty()
    Text(
        app.streambird.android.preset.changesText(changes), modifier, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
        color = if (dirty) warningTextColor() else okTextColor(),
    )
}

// ---- previews (light / dark / large font) ----

private fun sampleModel(prominent: Boolean, warnings: List<String> = emptyList()) = PresetCardModel(
    name = "Friday night show with a very long name that wraps", parts = PresetSummaryParts(listOf("YouTube", "Facebook (Main page)"), listOf("Acme brand", "Public", "Ticker on", "720p"), 1),
    warnings = warnings, lastUsed = "Used yesterday", real = true, prominent = prominent,
)

@Composable
private fun PreviewBody() {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PresetCard(sampleModel(true), false, {}, {}, {}, {}, {})
        PresetCard(sampleModel(false, listOf("720p is above your plan; using 540p.")), false, {}, {}, {}, {}, {})
        NewPresetCard(count = 2, atLimit = false, onClick = {})
        NewPresetCard(count = 20, atLimit = true, onClick = {})
        PresetsEmptyCard()
        PresetNameField("Friday", {}, NameCheck(warning = "You already have a preset called \"Friday\"."))
        ChangesLine(listOf("Quality", "Ticker"))
        ChangesLine(emptyList())
    }
}

@Preview(name = "Presets light", showBackground = true, widthDp = 380, heightDp = 1100)
@Composable
private fun PresetsLightPreview() { StreamBirdTheme(dark = false) { Box(Modifier.background(MaterialTheme.colorScheme.background)) { PreviewBody() } } }

@Preview(name = "Presets dark", showBackground = true, widthDp = 380, heightDp = 1100)
@Composable
private fun PresetsDarkPreview() { StreamBirdTheme(dark = true) { Box(Modifier.background(MaterialTheme.colorScheme.background)) { PreviewBody() } } }

@Preview(name = "Presets font 200%", showBackground = true, widthDp = 380, heightDp = 2200, fontScale = 2f)
@Composable
private fun PresetsBigFontPreview() { StreamBirdTheme(dark = false) { Box(Modifier.background(MaterialTheme.colorScheme.background)) { PreviewBody() } } }
