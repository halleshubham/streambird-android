package app.streambird.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.media.PicturePriority
import app.streambird.android.media.PreviewHandle
import app.streambird.android.media.formatBitrate
import app.streambird.android.media.StatsParser
import app.streambird.android.stream.LivePhase
import app.streambird.android.stream.buildShareText
import app.streambird.android.stream.resolveLiveLinks
import app.streambird.android.stream.LiveUi
import app.streambird.android.stream.StreamController
import app.streambird.android.ui.theme.Amber
import app.streambird.android.ui.theme.Ok
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun LiveScreen(controller: StreamController) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }

    // Keep the screen on while the stream (and its preview) is running.
    val view = LocalView.current
    DisposableEffect(ui.active) {
        view.keepScreenOn = ui.active
        onDispose { view.keepScreenOn = false }
    }

    val landscape = ui.request?.orientation?.isLandscape == true
    val handle = if (ui.phase == LivePhase.LIVE) controller.preview else null
    val frameRatio = if (landscape) 16f / 9f else 9f / 16f

    // Back: a finished stream goes back to the start; a running one keeps streaming and the app moves to the background.
    androidx.activity.compose.BackHandler(enabled = ui.phase != LivePhase.IDLE) {
        when (ui.phase) {
            LivePhase.ENDED, LivePhase.FAILED -> controller.acknowledge()
            else -> (ctx as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    // The preview lives in its own box, sized to FIT (letterbox, never crop) the stream's aspect inside the space it is given,
    // so it can never extend under other widgets. Overlay buttons sit in the TOP corners only (the ticker bar is at the bottom of the frame).
    val preview: @Composable (Modifier) -> Unit = { mod ->
        androidx.compose.foundation.layout.BoxWithConstraints(mod, contentAlignment = Alignment.Center) {
            val fit = fitInside(maxWidth.value, maxHeight.value, frameRatio)
            Box(Modifier.size(fit.first.dp, fit.second.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (handle != null) PreviewView(handle) else CircularProgressIndicator()
                if (ui.phase == LivePhase.LIVE || ui.phase == LivePhase.ENDING) {
                    StatusPill(ui, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                if (ui.phase == LivePhase.LIVE && ui.canFlipCamera) {
                    IconButton(
                        onClick = controller::flipCamera,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    ) { Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Flip camera (now ${if (ui.frontCamera) "front" else "back"})", tint = Color.White) }
                }
            }
        }
    }
    val controls: @Composable ColumnScope.() -> Unit = {
        if (ui.phase == LivePhase.FAILED || ui.phase == LivePhase.ENDED) {
            Notice(ui.message, if (ui.phase == LivePhase.FAILED) MaterialTheme.colorScheme.error else Ok)
        } else if (ui.message.isNotBlank()) Text(ui.message, style = MaterialTheme.typography.bodySmall)

        if (ui.phase == LivePhase.LIVE || ui.phase == LivePhase.ENDING) Expander("Details") {
            Column {
                StatsPanel(ui)
                if (ui.phase == LivePhase.LIVE) OutlinedButton(onClick = controller::simulateDrop, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("Simulate network drop") }
            }
        }

        if (ui.phase == LivePhase.LIVE) TickerControl(ui.tickerText, ui.tickerSize) { t, z -> controller.setTicker(t, z) }

        val links = resolveLiveLinks(
            ui.request?.destinationIds.orEmpty(), ui.request?.liveLinks.orEmpty(),
            ui.destinations.map { it.platformConnectionId to it.watchUrl } + ui.stream?.destinations.orEmpty().map { it.platformConnectionId to it.watchUrl },
        )
        if (links.isNotEmpty() && (ui.phase == LivePhase.LIVE || ui.phase == LivePhase.ENDING)) {
            OutlinedButton(onClick = { shareText(ctx, buildShareText(links)) }, modifier = Modifier.fillMaxWidth()) { Text("Share live link") }
        }

        if (ui.phase == LivePhase.LIVE) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Picture priority: sharp", style = MaterialTheme.typography.titleSmall)
                    Text("Off = balanced (encoder may shrink the picture)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = ui.request?.priority == PicturePriority.MAINTAIN_RESOLUTION,
                    onCheckedChange = { controller.setPriority(if (it) PicturePriority.MAINTAIN_RESOLUTION else PicturePriority.BALANCED) },
                )
            }
        }

        if (ui.destinations.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Destinations", style = MaterialTheme.typography.titleSmall)
                    ui.destinations.forEach {
                        KeyValue(it.platformConnectionId.take(8), "${it.status}${it.platformStatus?.let { p -> " / $p" } ?: ""}${it.errorMessage?.let { e -> " - $e" } ?: ""}")
                    }
                }
            }
        }

        if (ui.events.isNotEmpty()) {
            Text("Events", style = MaterialTheme.typography.titleSmall)
            ui.events.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    val bottomBar: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { controller.logCsv()?.let { shareLog(ctx, it) } }, enabled = ui.logRows > 0 || ui.phase == LivePhase.ENDED || ui.phase == LivePhase.FAILED, modifier = Modifier.weight(1f)) {
                Text("Share log (${ui.logRows})")
            }
            when (ui.phase) {
                LivePhase.LIVE, LivePhase.STARTING -> Button(onClick = { confirmEnd = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) { Text("End stream") }
                LivePhase.ENDED, LivePhase.FAILED -> Button(onClick = controller::acknowledge, modifier = Modifier.weight(1f)) { Text("Done") }
                else -> {}
            }
        }

    }

    if (landscape) {
        // Landscape stream: preview on the left, controls scroll on the right, End/Done pinned at the bottom right.
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(ui)
                preview(Modifier.weight(1f).fillMaxWidth())
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveBanners(ui, controller)
                    controls()
                }
                bottomBar()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(ui)
            preview(Modifier.weight(1.25f).fillMaxWidth())
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveBanners(ui, controller)
                controls()
            }
            bottomBar()
        }
    }

    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End the stream?") },
        text = { Text("This stops the broadcast on every destination.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; controller.end() }) { Text("End stream") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep streaming") } },
    )
}

/** Send-quality chip and the thumbnail upload notice, at the top of the Live screen. */
@Composable
private fun LiveBanners(ui: LiveUi, controller: StreamController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.phase == LivePhase.LIVE || ui.phase == LivePhase.ENDING) SendQualityChip(ui.quality)
        ReconnectBanner(ui.reconnect)
        ui.cameraNote?.let { Notice(it, Amber) }
        ui.thumbnailNotice?.let { ThumbnailNoticeBar(it, onDismiss = controller::dismissThumbnailNotice) }
    }
}

@Composable
private fun Header(ui: LiveUi) {
    val (label, color) = when (ui.phase) {
        LivePhase.LIVE -> "LIVE" to Color(0xFFE53935)
        LivePhase.STARTING -> "STARTING" to Amber
        LivePhase.ENDING -> "ENDING" to Amber
        LivePhase.ENDED -> "ENDED" to Color.Gray
        LivePhase.FAILED -> "FAILED" to Color(0xFFE53935)
        LivePhase.IDLE -> "" to Color.Gray
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 2.dp))
        Text(ui.stream?.title ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
        Text(formatElapsed(ui.elapsedS), style = MaterialTheme.typography.titleMedium)
    }
}

fun formatElapsed(s: Long): String = String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)

@Composable
private fun StatsPanel(ui: LiveUi) {
    val s = ui.stats
    val tier = ui.request?.tier
    val warn = ui.quality?.limited == true
    val d = ui.device
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("What is really being sent", style = MaterialTheme.typography.titleSmall)
            if (s == null) Text("Waiting for the first stats (2 s)...", style = MaterialTheme.typography.bodySmall)
            else {
                KeyValue("Sent size", "${s.width}x${s.height}" + (tier?.let { "  (chosen ${it.sizeLabel(ui.request!!.orientation)})" } ?: ""), if (warn) Amber else Color.Unspecified)
                KeyValue("Frame rate", "${Math.round(s.fps)} fps")
                KeyValue("Bitrate", formatBitrate(ui.kbps?.times(1000)) + (tier?.let { " / max ${it.maxBitrateBps / 1000} kbps" } ?: ""))
                KeyValue("Limited by", s.limitationReason ?: "none", if (s.limitationReason != null) Amber else Ok)
                KeyValue("Encoder", (s.encoderImpl ?: "?") + when (ui.hardware) { true -> " (hardware)"; false -> " (SOFTWARE)"; null -> "" }, if (ui.hardware == false) Amber else Color.Unspecified)
                KeyValue("RTT / loss", "${s.rttMs?.let { String.format(java.util.Locale.US, "%.0f ms", it) } ?: "--"} / ${s.lossPct?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "--"}")
                KeyValue("NACK / PLI / keyframes", "${s.nackCount} / ${s.pliCount} / ${s.keyFramesEncoded}")
                KeyValue("Encode time", s.avgEncodeMs?.let { String.format(java.util.Locale.US, "%.1f ms/frame", it) } ?: "--")
                s.availableOutgoingBps?.let { KeyValue("Estimated uplink", formatBitrate(it)) }
                KeyValue("Dropped by compositor", ui.droppedFrames.toString())
                if (ui.dropDetail.isNotEmpty()) Text(ui.dropDetail, style = MaterialTheme.typography.bodySmall)
            }
            if (d != null) {
                KeyValue("Battery", "${d.batteryPct ?: "?"}%  ${d.batteryTempC?.let { "$it C" } ?: ""}${if (d.charging == true) "  charging" else ""}")
                KeyValue("Thermal", d.thermal ?: "n/a (Android < 10)", if (d.thermal in setOf("SEVERE", "CRITICAL", "EMERGENCY")) Amber else Color.Unspecified)
                KeyValue("Network", d.network)
            }
        }
    }
}

/** The composited frames (camera + logo) exactly as handed to the encoder, rendered through a shared EGL context. */
@Composable
private fun PreviewView(handle: PreviewHandle) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(handle.eglContext, null)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                handle.track.addSink(this)
                renderer = this
            }
        },
    )
    DisposableEffect(handle) {
        onDispose {
            renderer?.let { r ->
                runCatching { handle.track.removeSink(r) }
                runCatching { r.release() }
            }
        }
    }
}

/** Largest (width, height) in dp with [ratio] = width / height that fits inside maxW x maxH. */
internal fun fitInside(maxW: Float, maxH: Float, ratio: Float): Pair<Float, Float> {
    if (maxW <= 0f || maxH <= 0f) return 0f to 0f
    return if (maxW / maxH > ratio) (maxH * ratio) to maxH else maxW to (maxW / ratio)
}

/** Small status pill in the top-left corner of the preview. */
@Composable
private fun StatusPill(ui: LiveUi, modifier: Modifier = Modifier) {
    val busy = ui.reconnect.phaseBusy
    val (label, color) = when {
        ui.phase == LivePhase.ENDING -> "ENDING" to Amber
        busy -> "RECONNECTING" to Amber
        else -> "LIVE" to Color(0xFFE53935)
    }
    Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 2.dp))
}
