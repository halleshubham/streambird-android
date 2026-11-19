package app.streambird.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.data.Preset
import app.streambird.android.preset.QUICK_START_OLD_SERVER_TEXT
import app.streambird.android.preset.checkPresetName
import app.streambird.android.preset.deletedText
import app.streambird.android.preset.lastUsedLabel
import app.streambird.android.preset.presetLimitReached
import app.streambird.android.preset.presetSummaryParts
import app.streambird.android.ui.StudioViewModel
import app.streambird.android.ui.theme.Amber

/**
 * Home: "Ready when you are". Preset cards (the most recently used one is the purple gradient card), a dashed "+ New preset" with a "3 of 20" counter,
 * and the older screens behind plain links. Without the server's presets feature (404) it shows one implicit "Quick start" card built from the
 * current setup and says why; with the feature but no preset yet it explains what a preset is. Deleting offers Undo (snackbar), the card menu has
 * Edit / Rename / Duplicate / Delete. On wide screens (tablet, foldable, landscape) the cards flow into columns.
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
    val snack = remember { SnackbarHostState() }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    // Coming back to Home (after saving or editing a preset, or when the first load failed) shows what the server really has, also presets made on the website.
    LaunchedEffect(Unit) { if (ps.loaded) vm.refreshPresets() }

    // Feedback is a snackbar (same place for every outcome): confirmations, errors (with Dismiss) and "Deleted ... Undo".
    LaunchedEffect(ps.notice) { ps.notice?.let { snack.showSnackbar(it, duration = SnackbarDuration.Short); vm.presets.clearNotice() } }
    LaunchedEffect(ps.error) { ps.error?.let { snack.showSnackbar(it, actionLabel = "Dismiss", duration = SnackbarDuration.Long); vm.presets.clearError() } }
    var undoShowing by remember { mutableStateOf(false) }
    LaunchedEffect(ps.pendingDelete?.id) {
        val p = ps.pendingDelete
        if (p != null) {
            undoShowing = true
            val r = snack.showSnackbar(deletedText(p.name), actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
            if (r == SnackbarResult.ActionPerformed) vm.undoDeletePreset()
        } else if (undoShowing) { undoShowing = false; snack.currentSnackbarData?.dismiss() }
    }

    val kitName: (String) -> String? = { id -> brand.kits.firstOrNull { it.id == id }?.name }
    val cards: List<Preset> = if (ps.available && ps.items.isNotEmpty()) ps.items else listOf(vm.implicitPreset())

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BirdTopBar {
                TextButton(onClick = onAccount) { Text("Account") }
                TextButton(onClick = { vm.logout() }) { Text("Sign out") }
            }
            StaleStreamsCard(s.staleStreams, s.staleNote, vm::endStale, vm::dismissStale)
            CrashReportCard()
            PageHeading("Ready when you are", s.limits?.let { "${it.planName.ifBlank { it.planKey }} plan - up to ${it.maxDestinations} destination${if (it.maxDestinations == 1) "" else "s"}, up to ${it.maxResolution.uppercase()}" })
            if (ps.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
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
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(340.dp), Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!ps.loaded) item("loading", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Loading your presets...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    itemsIndexed(cards, key = { i, p -> p.id.ifEmpty { "implicit$i" } }, span = { i, _ -> if (i == 0) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { i, p ->
                        val parts = presetSummaryParts(p.config, s.connections, kitName, s.brandAllowed)
                        PresetCard(
                            PresetCardModel(p.name, parts, vm.presetWarnings(p), lastUsedLabel(p.lastUsedAt), real = !p.isImplicit, prominent = i == 0),
                            busy = ps.busy,
                            onGo = { onOpen(p.takeUnless { it.isImplicit }, false) },
                            onEdit = { onOpen(p, true) },
                            onRename = { renamingId = p.id },
                            onDuplicate = { vm.duplicatePreset(p) },
                            onDelete = { vm.deletePreset(p) },
                        )
                    }
                    if (ps.available && ps.loaded && ps.items.isEmpty()) item("empty", span = { GridItemSpan(maxLineSpan) }) { PresetsEmptyCard() }
                    if (!ps.available && ps.loaded) item("oldserver", span = { GridItemSpan(maxLineSpan) }) {
                        Text(QUICK_START_OLD_SERVER_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ps.available) item("new", span = { GridItemSpan(maxLineSpan) }) {
                        NewPresetCard(ps.items.size, presetLimitReached(ps.items.size), onClick = { onOpen(null, true) })
                    }
                    item("links", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onChannels, modifier = Modifier.fillMaxWidth()) { Text("Pick channels and set up by hand") }
                            OutlinedButton(onClick = onBrand, modifier = Modifier.fillMaxWidth()) { Text("Brand kits") }
                            OutlinedButton(onClick = { openUrl(ctx, "${vm.baseUrl}/connections") }, modifier = Modifier.fillMaxWidth()) { Text("Add or connect channels on the website") }
                            OutlinedButton(onClick = { vm.refresh(); vm.refreshPresets() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh channels and presets") }
                        }
                    }
                }
            }
            ps.loadError?.let { Notice(it, Amber); OutlinedButton(onClick = vm::refreshPresets, modifier = Modifier.fillMaxWidth()) { Text("Try loading presets again") } }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp))
    }

    renamingId?.let { id ->
        val p = ps.items.firstOrNull { it.id == id }
        if (p == null) renamingId = null
        else RenameDialog(p, ps.items, onDone = { name -> vm.renamePreset(p, name); renamingId = null }, onCancel = { renamingId = null })
    }
}

/** Rename: one field, the same name rules as "Save as preset"; the typed name survives process death. */
@Composable
private fun RenameDialog(p: Preset, all: List<Preset>, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable(p.id) { mutableStateOf(p.name) }
    val check = checkPresetName(name, all, editingId = p.id)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename preset") },
        text = { PresetNameField(name, { name = it }, check, onDone = { if (check.ok && name.trim() != p.name) onDone(name) }) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = check.ok && name.trim() != p.name) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
