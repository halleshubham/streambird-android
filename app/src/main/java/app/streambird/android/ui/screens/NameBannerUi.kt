package app.streambird.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.streambird.android.media.NameBanner
import app.streambird.android.media.NameBannerState

/**
 * Name banner row (1.0.15): a name and a small title line at the bottom left of the picture, above the ticker. Same shape as [TickerControl]: a button
 * that opens a small dialog; Show / Update puts the banner on the live picture (it fades in), Hide fades it out. The texts are never saved.
 */
@Composable
fun NameBannerControl(state: NameBannerState, onSend: (name: String, title: String, show: Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(BannerEditor.rowLabel(state)) }
    if (open) {
        var name by rememberSaveable { mutableStateOf(BannerEditor.draftName(state)) }
        var title by rememberSaveable { mutableStateOf(BannerEditor.draftTitle(state)) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(BannerEditor.NAME_ROW) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = BannerEditor.limitName(it); error = null }, singleLine = true,
                        label = { Text("Name") }, isError = error != null, supportingText = { Text(error ?: "${name.length}/${NameBanner.NAME_MAX}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = title, onValueChange = { title = BannerEditor.limitTitle(it) }, singleLine = true,
                        label = { Text("Title (optional)") }, supportingText = { Text("${title.length}/${NameBanner.TITLE_MAX}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val r = BannerEditor.show(name, title)) {
                        is BannerEditor.Result.Send -> { onSend(r.name, r.title, r.show); open = false }
                        is BannerEditor.Result.Refused -> error = r.message
                    }
                }) { Text(BannerEditor.confirmLabel(state)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val r = BannerEditor.hide(name, title) as BannerEditor.Result.Send
                    onSend(r.name, r.title, r.show); open = false
                }) { Text(BannerEditor.HIDE_LABEL) }
            },
        )
    }
}
