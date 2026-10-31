package app.streambird.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.streambird.android.R
import app.streambird.android.ui.theme.PlatformColors
import app.streambird.android.ui.theme.SbTokens

// Website-styled building blocks (web-app/src/index.css: button 6 px radius, .btn-ghost, .panel, .platform-badge, .badge--*, .segmented).
// Button/OutlinedButton/TextButton/Card are declared here on purpose: the screens in this package import nothing for them, so
// they pick up the website look without per-screen edits.

private val ButtonShape = RoundedCornerShape(6.dp)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(onClick, modifier.heightIn(min = 44.dp), enabled, ButtonShape, colors, content = content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick, modifier.heightIn(min = 44.dp), enabled, ButtonShape,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.4f else 0.2f)), content = content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(onClick, modifier, enabled, ButtonShape, content = content)

/** The website's `.panel`: card background, 1 px border, 10 px radius, no shadow. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(
    modifier, MaterialTheme.shapes.medium, colors, CardDefaults.cardElevation(0.dp), BorderStroke(1.dp, MaterialTheme.colorScheme.outline), content,
)

/** The StreamBird bird (res/drawable-nodpi/logo_bird.png, copied from the website's logo.png). */
@Composable
fun BirdLogo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.logo_bird), contentDescription = "StreamBird", modifier = modifier.size(size))
}

/** Website top bar: bird + wordmark on the left, actions on the right, a 1 px bottom border. */
@Composable
fun BirdTopBar(modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BirdLogo(32.dp)
            Text("StreamBird", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            actions()
        }
        Box(Modifier.fillMaxWidth().height1().background(MaterialTheme.colorScheme.outline))
    }
}

private fun Modifier.height1() = this.heightIn(min = 1.dp, max = 1.dp)

/** Sub-screen header: title with a back text action, same top-bar look. */
@Composable
fun SubTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        actions()
    }
}

fun platformName(p: String) = when (p) {
    "youtube" -> "YouTube"
    "facebook" -> "Facebook"
    "twitch" -> "Twitch"
    "linkedin" -> "LinkedIn"
    else -> p.replaceFirstChar { it.uppercase() }
}

/** Web PlatformBadge: coloured pill with the platform name. */
@Composable
fun PlatformBadge(platform: String, modifier: Modifier = Modifier) {
    Text(
        platformName(platform), color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.clip(CircleShape).background(PlatformColors.forPlatform(platform)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

enum class BadgeKind { NEUTRAL, SUCCESS, WARNING, DANGER, INFO }

/** Web `.badge--*`: tinted pill. */
@Composable
fun StatusBadge(text: String, kind: BadgeKind = BadgeKind.NEUTRAL, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (fg, base) = when (kind) {
        BadgeKind.NEUTRAL -> cs.onSurface to cs.onSurfaceVariant
        BadgeKind.SUCCESS -> SbTokens.success to SbTokens.success
        BadgeKind.WARNING -> cs.onSurface to SbTokens.warning
        BadgeKind.DANGER -> cs.error to cs.error
        BadgeKind.INFO -> cs.primary to cs.primary
    }
    Text(
        text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(base.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Web `.segmented`: pill track, the active option filled with the accent. [sub] is the small second line (e.g. "720x1280"). */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    sub: (T) -> String? = { null },
    enabled: (T) -> Boolean = { true },
) {
    Row(
        modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).background(MaterialTheme.colorScheme.surface, CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            val ok = enabled(o)
            Column(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = ok, role = Role.RadioButton) { onSelect(o) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val fg = (if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = if (ok) 1f else 0.4f)
                Text(label(o), color = fg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                sub(o)?.let { Text(it, color = fg.copy(alpha = fg.alpha * 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }
    }
}

/** Heading + optional lead, like the website's page h1 + intro paragraph. */
@Composable
fun PageHeading(title: String, lead: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        lead?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
