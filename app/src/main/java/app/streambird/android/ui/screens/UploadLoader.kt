package app.streambird.android.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.streambird.android.R
import app.streambird.android.brand.UploadState

/** True unless the user turned animations off (Developer options "Animator duration scale" = off, or "Remove animations"). */
@Composable
fun animationsEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true) }
}

/**
 * The StreamBird loader: the bird logo with a gentle pulse inside a ring. With [progress] (0..1) the ring is determinate, without it it spins.
 * With animations switched off the bird is still and the ring is static (determinate) or absent (unknown progress).
 * [description] is the TalkBack name of the picture ("Uploading"); pass null when a neighbouring text already says it.
 */
@Composable
fun StreamBirdLoader(size: Dp = 28.dp, progress: Float? = null, modifier: Modifier = Modifier, description: String? = "Uploading") {
    val animate = animationsEnabled()
    var pulse = 1f
    var glow = 1f
    if (animate) {
        val t = rememberInfiniteTransition(label = "streambird-loader")
        pulse = t.animateFloat(0.88f, 1.06f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse").value
        glow = t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "glow").value
    }
    val sem = if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics { }
    Box(modifier.size(size).then(sem), contentAlignment = Alignment.Center) {
        val ring = size * 0.09f
        when {
            progress != null -> CircularProgressIndicator(progress = { progress }, Modifier.size(size), strokeWidth = ring.coerceAtLeast(2.dp))
            animate -> CircularProgressIndicator(Modifier.size(size), strokeWidth = ring.coerceAtLeast(2.dp))
        }
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.logo_bird), contentDescription = null,
            modifier = Modifier.size(size * 0.62f).scale(pulse).alpha(glow),
        )
    }
}

/**
 * The inline upload row: loader, "Uploading <file> 42%", a determinate bar (when the size is known) and an optional Cancel. ONE TalkBack node
 * announces "Uploading <file name>" politely (the percentage is not read out, it would be repeated 100 times); Cancel stays a separate button.
 */
@Composable
fun UploadingInline(state: UploadState, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = state.announcement; liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreamBirdLoader(28.dp, state.fraction, description = null)
                Text(state.visibleText, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        }
        state.fraction?.let { f -> LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().clearAndSetSemantics { }) }
    }
}

/** Small loader + "Uploading..." for the inside of the disabled upload button. */
@Composable
fun UploadingButtonLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StreamBirdLoader(20.dp, description = null)
        Text("Uploading...")
    }
}

/** Dimmed picture preview with the loader on top (the slot's thumbnail while its file is going up). */
@Composable
fun UploadingScrim(modifier: Modifier = Modifier, progress: Float? = null) {
    Box(modifier.background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        StreamBirdLoader(36.dp, progress, description = null)
    }
}

/**
 * Blocking scrim for an upload that has no visible slot to sit on (e.g. the other 16:9 / 9:16 set is showing): a modal dialog window, so the
 * screen behind is dimmed and cannot be touched meanwhile; it shows the loader, the file name and Cancel. Back and Cancel both cancel the
 * upload (a tap outside does nothing), so the screen can never be stuck behind it.
 */
@Composable
fun UploadingOverlay(state: UploadState, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel, properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false)) {
        Card(Modifier.fillMaxWidth()) { UploadingInline(state, Modifier.padding(16.dp), onCancel) }
    }
}
