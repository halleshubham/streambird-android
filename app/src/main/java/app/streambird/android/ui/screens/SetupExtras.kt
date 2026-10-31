package app.streambird.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.ui.BrandViewModel
import coil.compose.AsyncImage

/** Setup screen: which brand kit this stream uses (automatic by default) and a way to manage kits. Shown only when a brand logo is chosen. */
@Composable
fun BrandSetupSection(destinationIds: Set<String>, onManage: () -> Unit) {
    val vm: BrandViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selectedKitId.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (!st.loaded && !st.loading) vm.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Brand kit", style = MaterialTheme.typography.titleSmall)
        when {
            st.blocked -> Text("Brand kits are on the paid plans.", style = MaterialTheme.typography.bodySmall)
            !st.loaded -> Text("Loading brand kits...", style = MaterialTheme.typography.bodySmall)
            st.kits.isEmpty() -> Text("No brand kits yet. Create one to put your logo on the stream.", style = MaterialTheme.typography.bodySmall)
            else -> {
                val auto = vm.repo.effectiveKit(destinationIds, st)
                Row(Modifier.fillMaxWidth().clickable { vm.select(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == null, onClick = { vm.select(null) })
                    Column {
                        Text("Automatic")
                        Text(
                            auto?.let { "Today: \"${it.name}\"" } ?: "No kit",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                st.kits.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { vm.select(k.id) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == k.id, onClick = { vm.select(k.id) })
                        Text(k.name + if (k.isDefault) "  (default)" else "", Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        }
        TextButton(onClick = onManage) { Text("Manage brand kits") }
    }
}

/** Optional thumbnail for the stream (Android photo picker). Prepared (scaled, JPEG < 2 MB) and uploaded after go-live, in the background. */
@Composable
fun ThumbnailPickerSection(uri: String?, onPick: (String?) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) onPick(u.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Thumbnail (optional)", style = MaterialTheme.typography.titleSmall)
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = "Chosen thumbnail", contentScale = ContentScale.Crop, modifier = Modifier.height(96.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (uri == null) "Choose a picture" else "Change picture") }
            if (uri != null) TextButton(onClick = { onPick(null) }) { Text("Remove") }
        }
        Text(
            "Sent to YouTube / Facebook right after you go live (YouTube needs a verified channel). It never delays or stops the stream.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** Optional news ticker for the bottom of the picture; remembered between runs. */
@Composable
fun TickerSetupSection(text: String, size: app.streambird.android.media.TickerSize, onChange: (String, app.streambird.android.media.TickerSize) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ticker (optional)", style = MaterialTheme.typography.titleSmall)
        androidx.compose.material3.OutlinedTextField(
            value = text, onValueChange = { onChange(it.take(app.streambird.android.media.Ticker.MAX_LENGTH), size) },
            label = { Text("Scrolling text at the bottom") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Shown on every plan. You can change it while live.") },
        )
        Segmented(app.streambird.android.media.TickerSize.entries, size, { it.label }, { onChange(text, it) })
    }
}

/** Amber hint when the phone is on mobile data. */
@Composable
fun CellularHint(tier: app.streambird.android.media.StreamTier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hint = androidx.compose.runtime.remember(tier) { app.streambird.android.media.cellularHint(app.streambird.android.media.DeviceTelemetry(ctx).networkName(), tier) }
    if (hint != null) Notice(hint, app.streambird.android.ui.theme.Amber)
}

/** "The app closed unexpectedly last time": dismissible, with a Share crash report button. */
@Composable
fun CrashReportCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(app.streambird.android.diag.CrashReporter.pendingReport(ctx)) }
    val r = report ?: return
    Notice("The app closed unexpectedly last time. Sharing the crash report helps fix it.", app.streambird.android.ui.theme.Amber)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { app.streambird.android.diag.CrashReporter.share(ctx, r) }) { Text("Share crash report") }
        OutlinedButton(onClick = { app.streambird.android.diag.CrashReporter.dismiss(ctx); report = null }) { Text("Dismiss") }
    }
}

/** "A stream is still marked live": left behind by a crash or another device. End it, or dismiss if you are streaming elsewhere on purpose. */
@Composable
fun StaleStreamsCard(streams: List<app.streambird.android.data.StreamListItem>, note: String?, onEnd: (String) -> Unit, onDismiss: (String) -> Unit) {
    note?.let { Notice(it, app.streambird.android.ui.theme.Ok) }
    streams.forEach { st ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A stream is still marked live", style = MaterialTheme.typography.titleSmall)
                Text(
                    "\"${st.title}\"" + app.streambird.android.stream.formatStarted(st.startedAt ?: st.createdAt).let { if (it.isEmpty()) "" else ", started $it" } +
                        ". If you are not streaming from another device right now, end it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEnd(st.id) }) { Text("End stream") }
                    OutlinedButton(onClick = { onDismiss(st.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}

/** Setup: ads of the kit in use (automatic) with per-stream changes; Free shows the banner explanation. */
@Composable
fun AdsSetupBlock(
    freePlan: Boolean, useKit: Boolean, destinationIds: Set<String>,
    overrides: Map<app.streambird.android.ads.AdPlacement, app.streambird.android.ads.AdOverride>,
    onChange: (Map<app.streambird.android.ads.AdPlacement, app.streambird.android.ads.AdOverride>) -> Unit,
    serverUrl: String,
) {
    val vm: BrandViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(useKit) { if (useKit && !st.loaded && !st.loading) vm.refresh() }
    val kit = if (useKit && !freePlan) vm.repo.effectiveKit(destinationIds, st) else null
    AdsSetupSection(freePlan, kit?.ads.orEmpty(), overrides, onChange) { openUrl(ctx, "$serverUrl/billing") }
}
