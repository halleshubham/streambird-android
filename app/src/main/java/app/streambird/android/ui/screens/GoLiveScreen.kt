package app.streambird.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.media.PreviewCamera
import app.streambird.android.preset.connectionName
import app.streambird.android.preset.setupLine
import app.streambird.android.ui.StudioViewModel
import app.streambird.android.ui.theme.Amber
import app.streambird.android.ui.theme.SbTokens
import org.webrtc.SurfaceViewRenderer

private val LiveRed = Color(0xFFE53935)

/**
 * Camera-first go-live: the camera fills the screen; top row (close, status, flip), then a gradient panel with the title,
 * destination chips, a big red Go live, and a summary line with "Customize" (all the older setup, brand, ticker and ads controls in a bottom sheet).
 * One tap on Go live publishes, through the same camera / microphone permission flow as the Setup screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoLiveScreen(vm: StudioViewModel, openCustomize: Boolean, onClose: () -> Unit, onBrand: () -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val ps by vm.presets.state.collectAsStateWithLifecycle()
    val brand by vm.brandState.collectAsStateWithLifecycle()
    val brandSelected by vm.brandSelectedKitId.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var camOk by remember { mutableStateOf(hasPermission(ctx, Manifest.permission.CAMERA)) }
    var micOk by remember { mutableStateOf(hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camOk = hasPermission(ctx, Manifest.permission.CAMERA); micOk = hasPermission(ctx, Manifest.permission.RECORD_AUDIO)
    }
    var resumed by remember { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { resumed = true; camOk = hasPermission(ctx, Manifest.permission.CAMERA); micOk = hasPermission(ctx, Manifest.permission.RECORD_AUDIO) }
            if (e == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }

    // The camera preview belongs to this screen only; it is released before the stream starts (the engine opens the camera itself).
    val camera = remember { PreviewCamera(ctx) }
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var camError by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val showPreview = camOk && resumed && !starting
    DisposableEffect(showPreview, renderer, s.frontCamera) {
        val r = renderer
        if (showPreview && r != null) camError = camera.start(r, s.frontCamera)
        onDispose { camera.stop() }
    }

    val goLive = rememberGoLive(vm, onStarted = { starting = true; camera.stop() })

    var sheet by rememberSaveable { mutableStateOf(openCustomize) }
    var editTitle by rememberSaveable { mutableStateOf(false) }
    // What differs from the saved preset that is open (Channels, Quality...): shown as "edited" here and in Customize.
    val changes = remember(s, brandSelected, brand) { vm.presetChanges(s) }

    val ready = camOk && micOk && camError == null
    val pill = when {
        camError != null -> camError!!
        ready -> "Camera and mic ready"
        else -> "Allow camera and mic"
    }
    val brandName = if (!s.brandAllowed) null else vm.kitName(s.selected, brand, brandSelected)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (camOk) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c -> SurfaceViewRenderer(c).also { renderer = it } })
        } else {
            Box(Modifier.fillMaxSize().clickable { askPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, Alignment.Center) {
                Text("Tap to allow the camera", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Top row
        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon({ camera.stop(); onClose() }, "Close") { Icon(Icons.Filled.Close, null, tint = Color.White) }
            Box(Modifier.weight(1f), Alignment.Center) {
                Row(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = !ready && camError == null) {
                        askPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ready) Color(0xFF22C55E) else Amber))
                    Text(pill, Modifier.padding(start = 8.dp), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            RoundIcon({ vm.update { it.copy(frontCamera = !it.frontCamera) } }, "Flip camera (now ${if (s.frontCamera) "front" else "back"})") { Icon(Icons.Filled.FlipCameraAndroid, null, tint = Color.White) }
        }

        // Bottom panel
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.92f))))
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                s.title.ifBlank { "Add a title…" }, color = Color.White.copy(alpha = if (s.title.isBlank()) 0.65f else 1f),
                fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Edit the stream title", role = Role.Button) { editTitle = true },
            )
            s.titleFromKit?.let { Text("From brand kit $it. Edit it to change it for this stream only.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
            s.activePreset?.let { Text("Preset: ${it.name}" + if (changes.isNotEmpty()) " (edited: ${changes.joinToString(", ").lowercase()})" else "", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium) }
            s.presetNote?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall) }
            s.limitNote?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall) }

            if (s.connections.isEmpty()) {
                Text(if (s.loading) "Loading your channels..." else "No channels connected yet. Connect them on the website.", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                if (!s.loading) androidx.compose.material3.TextButton(onClick = { openUrl(ctx, "${vm.baseUrl}/connections") }) { Text("Open the website", color = Color.White) }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.connections.forEach { c ->
                        val on = c.id in s.selected
                        val name = connectionName(c, s.connections)
                        Box(
                            Modifier.clip(CircleShape).background(if (on) SbTokens.accent else Color.White.copy(alpha = 0.18f))
                                .selectable(selected = on, role = Role.Checkbox) { vm.toggle(c.id) }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (on) name else "+ $name", color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                    }
                }
            }

            androidx.compose.material3.Button(
                onClick = goLive, enabled = s.selected.isNotEmpty() && s.limits != null && s.title.length <= 200,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LiveRed, contentColor = Color.White, disabledContainerColor = LiveRed.copy(alpha = 0.35f), disabledContentColor = Color.White.copy(alpha = 0.7f)),
            ) { Text(if (s.selected.isEmpty()) "Pick a destination" else if (s.limits == null) "Loading your plan..." else "Go live", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    setupLine(brandName, s.tier, s.tickerText.isNotBlank()), Modifier.weight(1f),
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Customize", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { sheet = true }.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (editTitle) TitleDialog(s.title, onDone = { t -> vm.setTitle(t); editTitle = false }, onCancel = { editTitle = false })

    if (sheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageHeading("Customize", "Setup, brand, ticker and ads for this stream.")
                // A presets list that failed to load (offline at start-up) is tried again whenever the sheet opens.
                androidx.compose.runtime.LaunchedEffect(Unit) { if (!ps.loaded || ps.loadError != null) vm.refreshPresets() }
                if (ps.available) PresetSection(vm, s.activePreset, ps, s.visibility, changes)
                SetupFields(vm, s, onBrand = { sheet = false; camera.stop(); onBrand() }, showVisibility = !ps.available)
                Button(onClick = { sheet = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

/**
 * "Save as preset" (new) / "Preset" (open one) block of the Customize sheet. Shown whenever the server HAS the presets feature (a failed list load does
 * not hide it). The name field starts with a proposal (the ticked channels) so Save is usable at once and keeps what was typed (rememberSaveable);
 * a blank name says why Save is off, a duplicate name warns; "3 of 20 presets" counts, at 20 a new save is off with the reason; an open preset says
 * whether the setup differs from it ("Unsaved changes: Quality"); a success shows "Preset saved" / "Preset updated" right here, a failure its reason.
 * Delete here has the same Undo as on Home (no dialog): the preset closes and "Undo" reopens it.
 */
@Composable
private fun PresetSection(vm: StudioViewModel, active: app.streambird.android.data.Preset?, ps: app.streambird.android.preset.PresetsState, visibility: String, changes: List<String>) {
    var name by rememberSaveable(active?.id) { mutableStateOf(active?.name ?: vm.suggestPresetName()) }
    val check = app.streambird.android.preset.checkPresetName(name, ps.items, editingId = active?.id)
    val atLimit = app.streambird.android.preset.presetLimitReached(ps.items.size)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(if (active == null) "Save as preset" else "Preset: ${active.name}")
            Text(app.streambird.android.preset.PRESET_STORES_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(app.streambird.android.preset.PRESET_NOT_STORED_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active != null) ChangesLine(changes)
            PresetNameField(name, { name = it; vm.presets.clearError(); vm.presets.clearNotice() }, check)
            VisibilityPicker(vm, visibility)
            Text(app.streambird.android.preset.presetCountText(ps.items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (atLimit && active == null) Text(app.streambird.android.preset.LIMIT_REACHED_TEXT, style = MaterialTheme.typography.bodySmall, color = warningTextColor())
            ps.loadError?.let { Text("$it Saving still works.", color = warningTextColor(), style = MaterialTheme.typography.bodySmall) }
            ps.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            ps.notice?.let { Text(it, color = okTextColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            ps.pendingDelete?.let { gone ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(app.streambird.android.preset.deletedText(gone.name), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::undoDeletePreset) { Text("Undo") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.savePreset(name, overwrite = active != null) },
                    enabled = !ps.busy && check.ok && (active != null || !atLimit), modifier = Modifier.weight(1f),
                ) { Text(if (ps.busy) "Saving..." else if (active == null) "Save preset" else "Update preset") }
                if (active != null) OutlinedButton(onClick = { vm.deletePreset(active) }, enabled = !ps.busy, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
            if (active != null) OutlinedButton(
                onClick = { vm.savePreset(name, overwrite = false) }, enabled = !ps.busy && check.ok && !atLimit, modifier = Modifier.fillMaxWidth(),
            ) { Text("Save as a new preset") }
        }
    }
}

@Composable
private fun TitleDialog(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Stream title") },
        text = { OutlinedTextField(value = t, onValueChange = { t = it.take(200) }, singleLine = true, placeholder = { Text("Add a title…") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onDone(t.trim()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun RoundIcon(onClick: () -> Unit, description: String, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).semantics { contentDescription = description }) { content() }
}


private fun hasPermission(ctx: android.content.Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
