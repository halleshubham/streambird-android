package app.streambird.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.account.DeletePhase
import app.streambird.android.account.canDeleteNow
import app.streambird.android.data.DeletionPreview
import app.streambird.android.data.deleteAccountWebUrl
import app.streambird.android.ui.AccountViewModel
import app.streambird.android.ui.theme.Amber

/** What deleting the account removes, in plain words, from the server's preview. Pure so it can be unit-tested. */
fun deletionBullets(p: DeletionPreview): List<String> = buildList {
    add("Your streams and their history")
    add("Your connected destinations (YouTube, Facebook, Twitch)" + if (p.connectionsCount > 0) ": ${p.connectionsCount} connected" else "")
    add("Your brand kits and uploaded files" + if (p.assetsCount > 0) ": ${p.assetsCount} file${if (p.assetsCount == 1) "" else "s"}" else "")
    add("Your presets and settings")
    if (p.isOnlyAdmin) add(
        "The whole company account" + if (p.memberCount > 1) ", and the access of its ${p.memberCount - 1} other member${if (p.memberCount - 1 == 1) "" else "s"}" else "",
    )
    p.activeSubscription?.let { add("Your subscription autopay will be cancelled" + if (it.plan.isNotBlank()) " (${it.plan})" else "") }
    if (p.liveStream) add("The stream that is live right now will be ended")
}

/** Account: the signed-in email, sign out, and the "Delete account" danger section with the whole deletion flow. */
@Composable
fun AccountScreen(onBack: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var deleteOpen by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var companyOk by remember { mutableStateOf(false) }
    val webUrl = deleteAccountWebUrl()

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubTopBar("Account", if (s.phase == DeletePhase.DELETING) ({}) else onBack)
        PageHeading("Your account", "Signed in as ${vm.email.ifBlank { "your StreamBird account" }}")
        OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth(), enabled = s.phase != DeletePhase.DELETING) { Text("Sign out") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Delete account", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                if (!deleteOpen) {
                    Text("Permanently delete your StreamBird account and the data that belongs to it. This cannot be undone.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { deleteOpen = true; code = ""; companyOk = false; vm.load() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete account", color = MaterialTheme.colorScheme.error)
                    }
                } else when (s.phase) {
                    DeletePhase.LOADING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Checking what will be deleted...")
                    }
                    DeletePhase.UNAVAILABLE -> Notice(s.error ?: "", Amber)
                    DeletePhase.LOAD_FAILED -> {
                        Text(s.error ?: "Could not load.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { vm.load() }) { Text("Retry") }
                    }
                    DeletePhase.DONE -> Text("Your account was deleted.")
                    else -> s.preview?.let { p ->
                        Text("Deleting your account will permanently remove:", style = MaterialTheme.typography.bodyMedium)
                        deletionBullets(p).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                        val sending = s.phase == DeletePhase.SENDING_CODE
                        val deleting = s.phase == DeletePhase.DELETING
                        if (s.phase == DeletePhase.READY || sending) {
                            Text("We will email a 6-digit confirmation code to ${vm.email.ifBlank { "you" }} first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { vm.sendCode() }, enabled = !sending, modifier = Modifier.fillMaxWidth()) { Text("Send confirmation code") }
                        } else {
                            Text("We emailed a 6-digit code to ${vm.email.ifBlank { "you" }}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code") }, singleLine = true,
                                enabled = !deleting, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            )
                            if (s.needsCompanyConfirm) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = companyOk, onCheckedChange = { companyOk = it }, enabled = !deleting)
                                Text("I understand my whole company account will be deleted", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                            Button(
                                onClick = { vm.delete(code, companyOk && s.needsCompanyConfirm) },
                                enabled = canDeleteNow(code, s.needsCompanyConfirm, companyOk) && !deleting,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Delete my account permanently") }
                            TextButton(onClick = { code = ""; vm.sendCode() }, enabled = !deleting) { Text("Send a new code") }
                        }
                        if (sending || deleting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                if (deleteOpen && s.phase != DeletePhase.UNAVAILABLE && s.phase != DeletePhase.LOAD_FAILED) {
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                if (deleteOpen && s.phase != DeletePhase.DELETING) TextButton(onClick = { deleteOpen = false; code = ""; companyOk = false }) { Text("Cancel") }
                Text("You can also request deletion at $webUrl", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openUrl(ctx, webUrl) }) { Text("Open the deletion page") }
            }
        }
    }
}
