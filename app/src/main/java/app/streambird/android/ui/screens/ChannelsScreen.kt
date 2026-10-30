package app.streambird.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.ui.StudioViewModel
import app.streambird.android.ui.theme.Amber

fun platformLabel(p: String) = when (p) {
    "youtube" -> "YouTube"
    "facebook" -> "Facebook"
    "twitch" -> "Twitch / RTMP"
    "linkedin" -> "LinkedIn"
    else -> p.replaceFirstChar { it.uppercase() }
}

@Composable
fun ChannelsScreen(vm: StudioViewModel, onNext: () -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val server = vm.baseUrl
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Where do you want to go live?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.logout() }) { Text("Sign out") }
        }
        s.limits?.let {
            Text(
                "${it.planName.ifBlank { it.planKey }} plan - up to ${it.maxDestinations} destination${if (it.maxDestinations == 1) "" else "s"}, up to ${it.maxResolution.uppercase()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            s.loading -> CircularProgressIndicator()
            s.error != null -> {
                Text(s.error!!, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::refresh) { Text("Retry") }
            }
            s.connections.isEmpty() -> Text("No channels connected yet. Connect YouTube, Facebook or Twitch on the website, then pull to refresh here.")
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.connections, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { vm.toggle(c.id) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.id in s.selected, onCheckedChange = { vm.toggle(c.id) })
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(c.label, style = MaterialTheme.typography.titleMedium)
                                Text(platformLabel(c.platform), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        s.limitNote?.let { Notice(it, Amber) }
        if (!s.loading) OutlinedButton(onClick = vm::refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh channels") }
        OutlinedButton(onClick = { openUrl(ctx, "$server/connections") }, modifier = Modifier.fillMaxWidth()) { Text("Add or connect channels on the website") }
        Button(onClick = onNext, enabled = s.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (s.selected.isEmpty()) "Pick at least one channel" else "Next: set up the stream (${s.selected.size})")
        }
    }
}
