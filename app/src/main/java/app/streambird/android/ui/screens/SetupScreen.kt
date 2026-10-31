package app.streambird.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.media.CodecProbe
import app.streambird.android.media.PicturePriority
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.stream.LogoChoice
import app.streambird.android.ui.StudioViewModel
import app.streambird.android.ui.theme.Amber

@Composable
fun SetupScreen(vm: StudioViewModel, onBack: () -> Unit, onBrand: () -> Unit = {}) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val codecs = remember { CodecProbe.summary() }
    val hasHwH264 = remember { CodecProbe.h264Encoders().any { it.hardware } }
    val required = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    val wanted = if (Build.VERSION.SDK_INT >= 33) required + Manifest.permission.POST_NOTIFICATIONS else required
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (required.all { result[it] == true || ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) vm.goLive()
    }
    fun goLive() {
        if (required.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) vm.goLive()
        else launcher.launch(wanted.toTypedArray())
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StaleStreamsCard(s.staleStreams, s.staleNote, vm::endStale, vm::dismissStale)
        CrashReportCard()
        PageHeading("Stream setup", "Pick a title and quality, then go live.")
        OutlinedTextField(value = s.title, onValueChange = { v -> vm.update { it.copy(title = v) } }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Visibility (YouTube)", style = MaterialTheme.typography.titleSmall)
        Segmented(listOf("public", "unlisted", "private"), s.visibility, { it.replaceFirstChar { c -> c.uppercase() } }, { v -> vm.update { it.copy(visibility = v) } })

        Text("Shape (fixed once the stream is created)", style = MaterialTheme.typography.titleSmall)
        Segmented(StreamOrientation.entries, s.orientation, { it.label }, { o -> vm.update { it.copy(orientation = o) } })
        if (s.orientation.isLandscape) Text("Landscape: the screen turns to landscape while you stream. Hold the phone sideways.", style = MaterialTheme.typography.bodySmall)

        Text("Quality (${s.orientation.label.substringBefore(" ").lowercase()}, 30 fps)", style = MaterialTheme.typography.titleSmall)
        Segmented(
            StreamTier.entries, s.tier, { it.label }, { t -> vm.update { it.copy(tier = t) } },
            sub = { it.sizeLabel(s.orientation) }, enabled = { it in s.allowedTiers },
        )
        Text(
            "Up to ${s.tier.maxBitrateBps / 1_000_000} Mbps" + if (s.allowedTiers.size < StreamTier.entries.size) ". Higher qualities need a higher plan." else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CellularHint(s.tier)

        Text("Picture priority", style = MaterialTheme.typography.titleSmall)
        Segmented(PicturePriority.entries, s.priority, { it.label }, { p -> vm.update { it.copy(priority = p) } })
        Text(s.priority.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Logo overlay", style = MaterialTheme.typography.titleSmall)
        LogoChoice.entries.forEach { c ->
            val ok = c != LogoChoice.BRAND || s.brandAllowed
            Row(Modifier.fillMaxWidth().clickable(enabled = ok) { vm.update { it.copy(logoChoice = c) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.logoChoice == c, onClick = { vm.update { it.copy(logoChoice = c) } }, enabled = ok)
                Text(c.label + if (ok) "" else "  (brand kits are on paid plans)")
            }
        }

        if (s.logoChoice == LogoChoice.BRAND && s.brandAllowed) BrandSetupSection(s.selected, onBrand)
        AdsSetupBlock(
            freePlan = s.limits?.watermark == true, useKit = s.logoChoice == LogoChoice.BRAND && s.brandAllowed, destinationIds = s.selected,
            overrides = s.adOverrides, onChange = { o -> vm.update { it.copy(adOverrides = o) } }, serverUrl = vm.baseUrl,
        )
        TickerSetupSection(s.tickerText, s.tickerSize) { t, z -> vm.update { it.copy(tickerText = t, tickerSize = z) } }
        ThumbnailPickerSection(s.thumbnailUri) { u -> vm.update { it.copy(thumbnailUri = u) } }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Front camera", Modifier.weight(1f)); Switch(s.frontCamera, { v -> vm.update { it.copy(frontCamera = v) } })
        }
        Text("Experiments", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("H.264 High profile", Modifier.weight(1f)); Switch(s.h264HighProfile, { v -> vm.update { it.copy(h264HighProfile = v) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Keyframe every 2 s", Modifier.weight(1f)); Switch(s.forceKeyFrames, { v -> vm.update { it.copy(forceKeyFrames = v) } })
        }

        Text(codecs, style = MaterialTheme.typography.bodySmall)
        if (!hasHwH264) Notice("No hardware H.264 encoder was found. Streaming will likely fail or be very heavy on this phone.", Amber)
        if (s.limits?.watermark == true) Text("Free plan: streams carry a 'Powered by StreamBird' mark (added on the server side, not in this spike).", style = MaterialTheme.typography.bodySmall)

        Button(onClick = ::goLive, enabled = s.selected.isNotEmpty() && s.title.length <= 200, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Sensors, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Go live")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
