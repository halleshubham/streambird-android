package app.streambird.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.data.BrandKitPayload
import app.streambird.android.data.KitDraft
import app.streambird.android.data.PlatformConnection
import app.streambird.android.ui.BrandViewModel
import app.streambird.android.ui.theme.Ok

private fun styleLabel(s: String) = when (s) { "bar" -> "Bar"; "accent" -> "Accent"; "glass" -> "Glass"; else -> s }

/** Create (kitId == null) or edit a brand kit. Wallpaper fields of an existing kit are carried over untouched (see BrandKitPayload.patch). */
@Composable
fun BrandKitEditorScreen(kitId: String?, connections: List<PlatformConnection>, onBack: () -> Unit) {
    val vm: BrandViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.clearMsg(); if (!st.loaded) vm.refresh() }
    val original = st.kits.firstOrNull { it.id == kitId }
    val key = original?.id ?: "new"

    var name by remember(key) { mutableStateOf(original?.name ?: "") }
    var logoId by remember(key) { mutableStateOf(original?.logoAssetId) }
    var position by remember(key) { mutableStateOf(original?.logoPosition ?: "left") }
    var size by remember(key) { mutableStateOf((original?.logoSize ?: 90).toFloat()) }
    var style by remember(key) { mutableStateOf(original?.lowerThirdStyle ?: "accent") }
    var isDefault by remember(key) { mutableStateOf(original?.isDefault ?: false) }
    var channels by remember(key) { mutableStateOf(original?.connectionIds?.toSet() ?: emptySet()) }
    var showLibrary by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.upload(uri) { logoId = it } }

    val kitNames = st.kits.flatMap { k -> k.connectionIds.map { it to k } }.toMap()
    val canSave = name.isNotBlank() && name.length <= 80 && !busy && !st.blocked
    val title = if (kitId == null) "New brand kit" else "Edit brand kit"

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubTopBar(title, onBack)
        if (kitId != null && st.loaded && original == null) Notice("This brand kit no longer exists.", MaterialTheme.colorScheme.error)
        msg?.let { Notice(it.text, if (it.error) MaterialTheme.colorScheme.error else Ok) }
        if (!st.loaded) CircularProgressIndicator()
        if (st.blocked) Notice("Brand kits are on the paid plans.", MaterialTheme.colorScheme.error)

        OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Section("Logo") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KitPreview(logoId?.let { vm.repo.assetUrl(it) }, vm.repo.imageLoader, position, size.toInt(), 200.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showLibrary = true }, enabled = st.loaded, modifier = Modifier.fillMaxWidth()) { Text("Choose from library") }
                    OutlinedButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = st.storageEnabled && !busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Upload new") }
                    if (logoId != null) TextButton(onClick = { logoId = null }) { Text("Remove logo") }
                    if (!st.storageEnabled) Text("Brand storage is not enabled on this server yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val logoItem = st.library?.items?.firstOrNull { it.id == logoId }
                    if (logoItem?.isGif == true) Text("Animated GIF: shown animated here; the broadcast uses its first frame.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }

        Section("Logo position") {
            Segmented(listOf("left", "right"), position, { if (it == "left") "Left" else "Right" }, { position = it })
        }
        Section("Logo size: ${size.toInt()} px") {
            Slider(value = size, onValueChange = { size = it }, valueRange = BrandKitPayload.MIN_LOGO.toFloat()..BrandKitPayload.MAX_LOGO.toFloat())
            Text("Height on the picture, in pixels of the 1280 px tall frame (like the website).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("Lower-third style") {
            Segmented(BrandKitPayload.STYLES, style, ::styleLabel, { style = it })
            Text("Saved on the kit for the website studio; the app does not draw lower thirds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Use as account default", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (original?.isDefault == true) "To change the default, make another kit the default." else "Used for channels that have no kit of their own.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = isDefault, onCheckedChange = { isDefault = it }, enabled = original?.isDefault != true)
        }

        Section("Channels that use this kit") {
            if (connections.isEmpty()) Text("No channels connected yet.", style = MaterialTheme.typography.bodyMedium)
            connections.forEach { c ->
                val other = kitNames[c.id]?.takeIf { it.id != original?.id }
                Card(Modifier.fillMaxWidth().clickable { channels = if (c.id in channels) channels - c.id else channels + c.id }) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.id in channels, onCheckedChange = { channels = if (it) channels + c.id else channels - c.id })
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (other != null) Text(
                                if (c.id in channels) "Will move from \"${other.name}\"" else "Now uses \"${other.name}\"",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PlatformBadge(c.platform)
                    }
                }
            }
            Text("Unticked channels go back to the account default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Button(
            onClick = {
                vm.save(original, KitDraft(name, logoId, position, size.toInt(), style, isDefault), channels) { onBack() }
            },
            enabled = canSave && (kitId == null || original != null), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (kitId == null) "Create brand kit" else "Save changes") }
        if (original != null) OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Delete this kit", color = MaterialTheme.colorScheme.error) }
    }

    if (showLibrary) AlertDialog(
        onDismissRequest = { showLibrary = false },
        title = { Text("Choose a logo") },
        text = {
            val logos = st.library?.logos.orEmpty()
            if (logos.isEmpty()) Text("No logos in your library yet. Use \"Upload new\".")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logos, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { logoId = a.id; showLibrary = false }.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetImage(vm.repo.assetUrl(a.id), vm.repo.imageLoader, Modifier.size(48.dp))
                        Text(a.name.ifBlank { "Untitled" } + if (a.isGif) " (GIF)" else "", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showLibrary = false }) { Text("Cancel") } },
    )

    if (confirmDelete && original != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete \"${original.name}\"?") },
        text = { Text("Its channels go back to the account default. The logo file stays in your library.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteKit(original.id) { onBack() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
