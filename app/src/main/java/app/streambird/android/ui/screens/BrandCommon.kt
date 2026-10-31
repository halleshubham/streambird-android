package app.streambird.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage

/** A library image loaded through the app's authenticated client (animated when it is a GIF). */
@Composable
fun AssetImage(url: String?, loader: ImageLoader, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    if (url == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("No logo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        AsyncImage(model = url, imageLoader = loader, contentDescription = "Logo", modifier = modifier, contentScale = contentScale)
    }
}

/** What the broadcast looks like: a 9:16 frame with the logo at the kit's position and size (logoSize is in 1280-px-high canvas pixels, 16 px margin). */
@Composable
fun KitPreview(logoUrl: String?, loader: ImageLoader, position: String, logoSize: Int, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.height(height).aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF374151), Color(0xFF111827)))),
    ) {
        val h = maxHeight
        val logoH = h * (logoSize.coerceIn(16, 600) / 1280f)
        val margin = h * (16f / 1280f)
        if (logoUrl != null) {
            AssetImage(
                logoUrl, loader,
                Modifier.align(if (position == "right") Alignment.TopEnd else Alignment.TopStart).padding(margin).height(logoH),
            )
        } else {
            Text("No logo", Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        content()
    }
}

