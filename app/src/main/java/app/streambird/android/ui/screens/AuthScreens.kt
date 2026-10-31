package app.streambird.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.streambird.android.BuildConfig
import app.streambird.android.data.AuthUi
import app.streambird.android.ui.AuthViewModel
import app.streambird.android.ui.theme.Amber

@Composable
fun LoginScreen(a: AuthUi, vm: AuthViewModel) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    var showServer by remember { mutableStateOf(false) }
    var serverText by remember(a.server) { mutableStateOf(a.server) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        BirdLogo(96.dp, Modifier.align(Alignment.CenterHorizontally))
        Text("StreamBird", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            if (a.codeSent) "Enter the 6-digit code we emailed you." else "Enter your email and we'll send you a one-time login code.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = a.email, onValueChange = vm::setEmail, label = { Text("Email") }, singleLine = true,
            enabled = !a.codeSent && !a.busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        if (a.codeSent) {
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            Button(onClick = { vm.verify(code) }, enabled = code.length == 6 && !a.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
            TextButton(onClick = { code = ""; vm.back() }) { Text("Use a different email / resend code") }
        } else {
            Button(onClick = { vm.sendCode() }, enabled = a.email.contains("@") && !a.busy, modifier = Modifier.fillMaxWidth()) { Text("Email me a code") }
        }
        if (a.busy) CircularProgressIndicator()
        a.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        a.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { openUrl(ctx, a.server) }, modifier = Modifier.fillMaxWidth()) { Text("New here? Register on the website") }
        TextButton(onClick = { showServer = !showServer }) { Text("Server: ${a.server.removePrefix("https://")}") }
        if (showServer) {
            OutlinedTextField(
                value = serverText, onValueChange = { serverText = it }, label = { Text("Server (staging)") }, singleLine = true,
                supportingText = { Text("Default ${BuildConfig.DEFAULT_BASE_URL}") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.setServer(serverText) }) { Text("Use this server") }
        }
    }
}

@Composable
fun PendingScreen(a: AuthUi, vm: AuthViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(32.dp))
        BirdLogo(120.dp, Modifier.align(Alignment.CenterHorizontally))
        Text("Your studio is being set up!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Thanks for signing up${a.user?.email?.let { " as $it" } ?: ""}. New accounts are approved by hand. " +
                "We will let you in as soon as it is ready; this screen checks every 30 seconds.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = { vm.checkAgain() }, enabled = !a.busy, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
        if (a.busy) CircularProgressIndicator()
        a.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        a.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { openUrl(ctx, "mailto:support@shackyapps.in") }) { Text("Email support") }
        TextButton(onClick = { vm.logout() }) { Text("Sign out") }
    }
}

@Composable
fun NeedsPhoneScreen(a: AuthUi, vm: AuthViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(32.dp))
        BirdLogo(80.dp, Modifier.align(Alignment.CenterHorizontally))
        Text("One more step", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text("Your account needs a mobile number before you can use the studio. Add it on the website, then come back.", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { openUrl(ctx, a.server) }) { Text("Open streambird on the web") }
        OutlinedButton(onClick = { vm.checkAgain() }) { Text("I added it, check again") }
        TextButton(onClick = { vm.logout() }) { Text("Sign out") }
    }
}
