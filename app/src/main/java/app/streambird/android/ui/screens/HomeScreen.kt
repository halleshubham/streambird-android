package app.streambird.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.data.Preset
import app.streambird.android.preset.presetSummary
import app.streambird.android.ui.StudioViewModel
import app.streambird.android.ui.theme.Amber
import app.streambird.android.ui.theme.SbTokens

/**
 * Home: "Ready when you are". Preset cards (the most recently used one is the purple gradient card), a dashed "+ New preset",
 * and the older screens behind plain links. Without the server's presets feature (404 / offline) it shows one implicit
 * "Quick start" card built from the current setup, and no "+ New preset".
 */
@Composable
fun HomeScreen(
    vm: StudioViewModel,
    onOpen: (Preset?, customize: Boolean) -> Unit,
    onChannels: () -> Unit,
    onBrand: () -> Unit,
    onAccount: () -> Unit = {},
) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val ps by vm.presets.state.collectAsStateWithLifecycle()
    val brand by vm.brandState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var deleting by remember { mutableStateOf<Preset?>(null) }
    // Coming back to Home (after saving or editing a preset, or when the first load failed) shows what the server really has, also presets made on the website.
    LaunchedEffect(Unit) { if (ps.loaded) vm.refreshPresets() }

    val kitName: (String) -> String? = { id -> brand.kits.firstOrNull { it.id == id }?.name }
    val cards: List<Preset> = if (ps.available && ps.items.isNotEmpty()) ps.items else listOf(vm.implicitPreset())
    val ready = !s.loading && s.error == null && s.connections.isNotEmpty()

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BirdTopBar {
            TextButton(onClick = onAccount) { Text("Account") }
            TextButton(onClick = { vm.logout() }) { Text("Sign out") }
        }
        StaleStreamsCard(s.staleStreams, s.staleNote, vm::endStale, vm::dismissStale)
        CrashReportCard()
        PageHeading("Ready when you are", s.limits?.let { "${it.planName.ifBlank { it.planKey }} plan - up to ${it.maxDestinations} destination${if (it.maxDestinations == 1) "" else "s"}, up to ${it.maxResolution.uppercase()}" })
        when {
            s.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            s.error != null -> {
                Text(s.error!!, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::refresh) { Text("Retry") }
            }
            s.connections.isEmpty() -> {
                Text("No channels connected yet. Connect YouTube, Facebook or Twitch on the website, then refresh here.")
                OutlinedButton(onClick = { openUrl(ctx, "${vm.baseUrl}/connections") }, modifier = Modifier.fillMaxWidth()) { Text("Connect channels on the website") }
                OutlinedButton(onClick = vm::refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh channels") }
            }
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(cards, key = { i, p -> p.id.ifEmpty { "implicit$i" } }) { i, p ->
                    val summary = presetSummary(p.config, s.connections, kitName, s.brandAllowed)
                    PresetCard(
                        preset = p, summary = summary, prominent = i == 0,
                        onGo = { onOpen(p.takeUnless { it.isImplicit }, false) },
                        onEdit = { onOpen(p, true) },
                        onDelete = { deleting = p },
                    )
                }
                if (ps.available) item("new") { NewPresetCard { onOpen(null, true) } }
                item("links") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onChannels, modifier = Modifier.fillMaxWidth()) { Text("Pick channels and set up by hand") }
                        OutlinedButton(onClick = onBrand, modifier = Modifier.fillMaxWidth()) { Text("Brand kits") }
                        OutlinedButton(onClick = { openUrl(ctx, "${vm.baseUrl}/connections") }, modifier = Modifier.fillMaxWidth()) { Text("Add or connect channels on the website") }
                        OutlinedButton(onClick = vm::refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh channels") }
                    }
                }
            }
        }
        ps.loadError?.let { Notice(it, Amber); OutlinedButton(onClick = vm::refreshPresets, modifier = Modifier.fillMaxWidth()) { Text("Try loading presets again") } }
        ps.error?.let { Notice(it, Amber) }
        ps.notice?.let { n ->
            Notice(n, app.streambird.android.ui.theme.Ok)
            LaunchedEffect(n) { kotlinx.coroutines.delay(4000); vm.presets.clearNotice() }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("The preset is removed. Your channels and brand kits are not touched.") },
            confirmButton = { TextButton(onClick = { vm.deletePreset(p.id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PresetCard(preset: Preset, summary: String, prominent: Boolean, onGo: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val real = !preset.isImplicit
    val shape = RoundedCornerShape(16.dp)
    if (prominent) {
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.linearGradient(listOf(SbTokens.accent, Color(0xFF4C1D95))))
                .clickable(onClick = onGo).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (real && preset.lastUsedAt != null) "LAST USED" else "READY", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                    Text(preset.name, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (real) PresetMenu(menu, { menu = it }, onEdit, onDelete, Color.White)
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            androidx.compose.material3.Button(
                onClick = onGo, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SbTokens.accentHover),
            ) { Text("Go live now", fontWeight = FontWeight.Bold) }
        }
    } else {
        Card(Modifier.fillMaxWidth().clickable(onClick = onGo)) {
            Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onGo) { Text("Go live") }
                if (real) PresetMenu(menu, { menu = it }, onEdit, onDelete, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PresetMenu(open: Boolean, setOpen: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, tint: Color) {
    Box {
        IconButton(onClick = { setOpen(true) }) { Icon(Icons.Filled.MoreVert, contentDescription = "Preset options", tint = tint) }
        DropdownMenu(open, { setOpen(false) }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { setOpen(false); onEdit() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { setOpen(false); onDelete() })
        }
    }
}

@Composable
private fun NewPresetCard(onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).drawBehind {
            drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        },
        contentAlignment = Alignment.Center,
    ) { Text("+ New preset", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
}
