package app.streambird.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.streambird.android.media.qualityChip
import app.streambird.android.media.SendQuality
import app.streambird.android.stream.NoticeKind
import app.streambird.android.stream.ThumbnailNotice
import app.streambird.android.ui.theme.Amber
import app.streambird.android.ui.theme.Ok

/** "Sending 720x1280 - 30 fps - 2.4 Mbps"; amber with the plain-language reason when the encoder is limited (same rules as the website). */
@Composable
fun SendQualityChip(q: SendQuality?, modifier: Modifier = Modifier) {
    val chip = qualityChip(q)
    val color = if (chip.limited) Amber else Ok
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(chip.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        }
        chip.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground) }
    }
}

/** Result of the background thumbnail upload; dismissible. */
@Composable
fun ThumbnailNoticeBar(n: ThumbnailNotice, onDismiss: () -> Unit) {
    val color = when (n.kind) { NoticeKind.SUCCESS -> Ok; NoticeKind.WARNING -> Amber; NoticeKind.ERROR -> MaterialTheme.colorScheme.error }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(color.copy(alpha = 0.16f)).padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(n.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** Collapsed-by-default section (the developer stats table lives in it). */
@Composable
fun Expander(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(onClick = { open = !open }, Modifier.fillMaxWidth()) {
                Text(title, Modifier.weight(1f))
                Text(if (open) "Hide" else "Show")
            }
            if (open) Box(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) { content() }
        }
    }
}
