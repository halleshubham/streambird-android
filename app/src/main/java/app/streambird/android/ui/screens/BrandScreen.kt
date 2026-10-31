package app.streambird.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.brand.formatBytes
import app.streambird.android.data.AssetItem
import app.streambird.android.data.PlatformConnection
import app.streambird.android.ui.BrandViewModel
import app.streambird.android.ui.theme.Amber
import app.streambird.android.ui.theme.Ok

/** "Brand kits": the kits (select for the next stream, edit), and the asset library behind them. Paid plans only. */
@Composable
fun BrandScreen(connections: List<PlatformConnection>, serverUrl: String, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val vm: BrandViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selectedKitId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var deleteAsset by remember { mutableStateOf<AssetItem?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    var uploadKind by remember { mutableStateOf("logo") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.upload(uri, uploadKind) { } }
    val labels = connections.associate { it.id to it.label }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubTopBar("Brand kits", onBack)
        PageHeading("Brand", "A kit is a logo and where it sits. Pick one for your next stream, or let the app choose by channel.")
        msg?.let { Notice(it.text, if (it.error) MaterialTheme.colorScheme.error else Ok) }
        if (st.loading || busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }

        when {
            st.blocked -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Brand kits are on the paid plans", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The Free plan has no brand library, so logos cannot be saved or used in the app. Upgrade to keep brand kits and put your logo on every stream.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { openUrl(ctx, "$serverUrl/billing") }) { Text("See plans") }
                }
            }
            st.error != null -> {
                Notice(st.error!!, MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { vm.refresh() }) { Text("Retry") }
            }
            !st.loaded -> {}
            else -> {
                if (!st.storageEnabled) Notice("Brand storage is not enabled on this server yet, so logos cannot be uploaded. Existing kits still work.", Amber)

                Section("Use for the next stream") {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Automatic", style = MaterialTheme.typography.titleMedium)
                                Text("The kit of your first channel, else the account default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected == null) StatusBadge("In use", BadgeKind.INFO) else OutlinedButton(onClick = { vm.select(null) }) { Text("Use") }
                        }
                    }
                    st.kits.forEach { kit ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                KitPreview(kit.logoAssetId?.let { vm.repo.assetUrl(it) }, vm.repo.imageLoader, kit.logoPosition, kit.logoSize, 120.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(kit.name, style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (kit.isDefault) StatusBadge("Default", BadgeKind.SUCCESS)
                                        if (selected == kit.id) StatusBadge("In use", BadgeKind.INFO)
                                    }
                                    Text(
                                        "Logo ${kit.logoPosition}, ${kit.logoSize} px" + if (kit.connectionIds.isEmpty()) "" else "\nChannels: " + kit.connectionIds.joinToString { labels[it] ?: "channel" },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (selected != kit.id) OutlinedButton(onClick = { vm.select(kit.id) }) { Text("Use") }
                                        OutlinedButton(onClick = { onEdit(kit.id) }) { Text("Edit") }
                                    }
                                }
                            }
                        }
                    }
                    if (st.kits.isEmpty()) Text("No brand kits yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) { Text("New brand kit") }
                }

                val lib = st.library
                Section("File library") {
                    if (lib != null) {
                        val quota = lib.quotaBytes.coerceAtLeast(1)
                        Text("${formatBytes(lib.usedBytes)} of ${formatBytes(lib.quotaBytes)} used", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { (lib.usedBytes.toFloat() / quota).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        lib.items.forEach { a ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AssetImage(vm.repo.assetUrl(a.id), vm.repo.imageLoader, Modifier.size(56.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(a.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                        Text(
                                            "${a.kind} - ${a.width}x${a.height} - ${formatBytes(a.sizeBytes)}" + if (a.isGif) " - animated GIF (first frame on air)" else "",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            if (a.usedBy.isEmpty()) "Not used by any kit" else "Used by: " + a.usedBy.joinToString(),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { deleteAsset = a }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                        if (lib.items.isEmpty()) Text("No files yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(
                        onClick = { uploadKind = "logo"; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = st.storageEnabled && !busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Upload a logo from the gallery") }
                    OutlinedButton(
                        onClick = { uploadKind = "ad"; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = st.storageEnabled && !busy && app.streambird.android.ads.adsAccess(st.plan) == app.streambird.android.ads.AdsAccess.EDITABLE, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Upload an ad picture or GIF") }
                    Text("PNG, JPG or WebP (GIF for logos), up to 4 MB and 8192 px.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer16()
    }

    deleteAsset?.let { a ->
        AlertDialog(
            onDismissRequest = { deleteAsset = null },
            title = { Text("Delete this file?") },
            text = { Text("\"${a.name}\" will be removed from your library.") },
            confirmButton = { TextButton(onClick = { deleteAsset = null; vm.deleteAsset(a.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteAsset = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Spacer16() = androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
