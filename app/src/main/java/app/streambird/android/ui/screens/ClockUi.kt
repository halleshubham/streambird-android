package app.streambird.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.clock.Clock
import app.streambird.android.clock.ClockHours
import app.streambird.android.clock.ClockPosition
import app.streambird.android.clock.ClockSettings
import app.streambird.android.clock.ClockSize
import app.streambird.android.clock.ClockZones
import app.streambird.android.clock.formatClock
import app.streambird.android.ui.StudioUi
import app.streambird.android.ui.StudioViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

/**
 * The date and time controls, shared by the brand-kit editor and the stream's Setup / Customize: switch, corner (2x2), 12 / 24 hour, date, seconds, size,
 * timezone and a live example of what will show. Edits go out through [onChange]; nothing here knows about kits or streams.
 */
@Composable
fun ClockFields(c: ClockSettings, onChange: (ClockSettings) -> Unit, enabledSwitchLabel: String = "Show date and time on the picture") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(enabledSwitchLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = c.enabled, onCheckedChange = { onChange(c.copy(enabled = it)) })
        }
        if (!c.enabled) return@Column
        Text("Corner", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CornerPicker(c.position) { onChange(c.copy(position = it)) }
        Text("Time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Segmented(ClockHours.entries, c.hours, { it.label }, { onChange(c.copy(hours = it)) })
        SwitchRow("Show the date", c.showDate) { onChange(c.copy(showDate = it)) }
        SwitchRow("Show seconds", c.showSeconds) { onChange(c.copy(showSeconds = it)) }
        Text("Size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Segmented(ClockSize.entries, c.size, { it.label }, { onChange(c.copy(size = it)) })
        ZonePicker(c.timezone) { onChange(c.copy(timezone = it)) }
        ClockExample(c)
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** The four corners as a 2x2 grid; the chosen one is a filled button. */
@Composable
private fun CornerPicker(selected: ClockPosition, onSelect: (ClockPosition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(listOf(ClockPosition.TOP_LEFT, ClockPosition.TOP_RIGHT), listOf(ClockPosition.BOTTOM_LEFT, ClockPosition.BOTTOM_RIGHT)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    if (p == selected) Button(onClick = { onSelect(p) }, modifier = Modifier.weight(1f)) { Text(p.label, maxLines = 1) }
                    else OutlinedButton(onClick = { onSelect(p) }, modifier = Modifier.weight(1f)) { Text(p.label, maxLines = 1) }
                }
            }
        }
    }
}

/** What the overlay will say right now in the chosen format and zone, refreshed every second. */
@Composable
private fun ClockExample(c: ClockSettings) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = Instant.now() } }
    val text = formatClock(now, c, Locale.getDefault()).line
    Text("Now: $text", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Drawn in a dark rounded box in the ${c.position.label.lowercase()} corner" + if (c.position.isTop) ", under the logo when it is in the same corner." else ", above the ticker bar.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Timezone: <zone>" with a button that opens a searchable list (this phone, common zones, every zone). */
@Composable
private fun ZonePicker(zone: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Timezone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ClockZones.label(zone), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = { open = true }) { Text("Change") }
    }
    if (open) ZoneDialog(zone, onPick = { onPick(it); open = false }, onDismiss = { open = false })
}

private sealed interface ZoneRow {
    data object Auto : ZoneRow
    data class Header(val text: String) : ZoneRow
    data class Zone(val id: String) : ZoneRow
}

@Composable
private fun ZoneDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { ClockZones.all() }
    val rows = remember(query) {
        val q = query.trim()
        buildList<ZoneRow> {
            if (q.isEmpty()) {
                add(ZoneRow.Auto)
                add(ZoneRow.Header("Common"))
                ClockZones.COMMON.forEach { add(ZoneRow.Zone(it)) }
                add(ZoneRow.Header("All timezones"))
                all.forEach { add(ZoneRow.Zone(it)) }
            } else {
                if (ClockZones.AUTOMATIC_LABEL.contains(q, ignoreCase = true) || "automatic".startsWith(q.lowercase())) add(ZoneRow.Auto)
                ClockZones.filter(q, all).forEach { add(ZoneRow.Zone(it)) }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timezone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(40) }, singleLine = true, label = { Text("Search, for example Kolkata") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(300.dp)) {
                    items(rows) { r ->
                        when (r) {
                            ZoneRow.Auto -> ZoneLine(ClockZones.AUTOMATIC_LABEL, current == null) { onPick(null) }
                            is ZoneRow.Header -> Text(r.text, Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            is ZoneRow.Zone -> ZoneLine(r.id, current == r.id) { onPick(r.id) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ZoneLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label + if (selected) "  (selected)" else "",
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Setup / Customize: the "Date and time" group. It starts from the settings of the brand kit in use ("From brand kit <name>") and any change makes this
 * stream's own copy (the kit is never modified); "Use the brand kit's settings" drops the copy. Without a kit in use the starting point is "off".
 */
@Composable
fun ColumnScope.ClockSetupSection(vm: StudioViewModel, s: StudioUi) {
    val brandSt by vm.brandState.collectAsStateWithLifecycle()
    val kit = vm.kitInUse(s, brandSt)
    val kitClock = if (kit != null) ClockSettings.fromKit(kit) else null
    val effective = s.clock ?: kitClock ?: ClockSettings()
    Text("Date and time", style = MaterialTheme.typography.titleSmall)
    Text(
        when {
            s.clock != null -> "Changed for this stream only."
            kit != null -> "From brand kit ${kit.name}: ${Clock.describe(effective)}."
            else -> "Not set by a brand kit. Turn it on for this stream below."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ClockFields(effective, { n -> vm.update { it.copy(clock = n) } })
    if (s.clock != null) TextButton(onClick = { vm.update { it.copy(clock = null) } }) { Text(if (kit != null) "Use the brand kit's settings" else "Turn it off and forget my changes") }
}

/** Live screen: switch the date and time on or off for this stream (the rest of the settings come from the kit or from Setup). */
@Composable
fun ClockLiveControl(c: ClockSettings, onChange: (ClockSettings) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Date and time", style = MaterialTheme.typography.titleSmall)
            Text(Clock.describe(c.copy(enabled = true)).removePrefix("On: ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = c.enabled, onCheckedChange = { onChange(c.copy(enabled = it)) })
    }
}
