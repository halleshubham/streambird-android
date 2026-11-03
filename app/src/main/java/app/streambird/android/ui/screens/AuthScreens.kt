package app.streambird.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.streambird.android.BuildConfig
import app.streambird.android.auth.BrowserSignIn
import app.streambird.android.auth.CredentialManagerGoogleProvider
import app.streambird.android.auth.GoogleSignInText
import app.streambird.android.data.AuthUi
import app.streambird.android.data.GoogleStage
import app.streambird.android.ui.AuthViewModel
import app.streambird.android.ui.theme.Amber

const val COMPANY_SIGNUP_URL = "https://streambird.app/signup-company"

@Composable
fun LoginScreen(a: AuthUi, vm: AuthViewModel) {
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
            if (a.codeSent) "Enter the 6-digit code we emailed you." else "Sign in or create your account. Enter your email and we'll send a one-time code. New here? The same code creates your account.",
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

        if (!a.codeSent) {
            OrDivider()
            val ctx = LocalContext.current
            GoogleSignInButton(
                label = when (a.google) {
                    GoogleStage.LAUNCHING -> "Opening Google..."
                    GoogleStage.EXCHANGING -> "Signing in..."
                    GoogleStage.REDEEMING -> BrowserSignIn.Text.SIGNING_IN
                    else -> "Continue with Google"
                },
                enabled = !a.busy,
                onClick = { vm.signInWithGoogle(CredentialManagerGoogleProvider(ctx, BuildConfig.GOOGLE_WEB_CLIENT_ID)) },
            )
            if (a.google == GoogleStage.REDEEMING) Text(BrowserSignIn.Text.SIGNING_IN, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            a.googleNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { app.streambird.android.diag.CrashReporter.shareDiagnostics(ctx, "Google sign-in diagnostics") }) { Text("Share sign-in diagnostics") }
            }
            OutlinedButton(
                onClick = { vm.beginBrowserSignIn()?.let { if (!openInBrowser(ctx, it)) vm.browserUnavailable() } },
                enabled = !a.busy, modifier = Modifier.fillMaxWidth(),
            ) { Text(GoogleSignInText.BROWSER_LABEL) }
        }

        if (!a.codeSent) {
            val ctx = LocalContext.current
            Text(
                "Setting up a company account for your team? Create it at streambird.app/signup-company",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { openUrl(ctx, COMPANY_SIGNUP_URL) },
            )
        }

        Spacer(Modifier.height(8.dp))
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
private fun OrDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text("or", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
}

/**
 * Google's "Sign in with Google" button look (light theme: white, thin grey outline, dark text, the "G" mark on the left), drawn in Compose.
 * The G is Google's standard four-colour mark (res/drawable/ic_google_g.xml).
 */
@Composable
private fun GoogleSignInButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color(0xFF747775)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F), disabledContainerColor = Color.White.copy(alpha = 0.6f), disabledContentColor = Color(0xFF1F1F1F).copy(alpha = 0.5f)),
    ) {
        GoogleGMark(Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun GoogleGMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(app.streambird.android.R.drawable.ic_google_g),
        contentDescription = null,
        modifier = modifier,
    )
}

@Composable
fun PendingScreen(a: AuthUi, vm: AuthViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(32.dp))
        BirdLogo(120.dp, Modifier.align(Alignment.CenterHorizontally))
        Text("Your studio is being set up!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Thanks for signing up${a.user?.email?.let { " as $it" } ?: ""}. We approve new accounts by hand, so there is nothing more for you to do. " +
                "We will let you in as soon as it is ready; this screen checks every 30 seconds, so you can leave the app open or come back later.",
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
    var phone by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(32.dp))
        BirdLogo(80.dp, Modifier.align(Alignment.CenterHorizontally))
        Text("One last thing", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "What is the best mobile number to reach you on${a.user?.email?.let { " ($it)" } ?: ""}?",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = phone, onValueChange = { phone = it.take(32) }, label = { Text("Mobile number") }, singleLine = true,
            placeholder = { Text("+91 98765 43210") },
            supportingText = { Text("Indian numbers need no country code. For any other country, start with + and the code.") },
            isError = a.error != null, enabled = !a.busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        a.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = { vm.savePhone(phone) }, enabled = phone.isNotBlank() && !a.busy, modifier = Modifier.fillMaxWidth()) { Text("Save and continue") }
        if (a.busy) CircularProgressIndicator()
        Text(
            "Why we ask: so our team can reach you about your account. We only use it to help with your account and setup, never for marketing.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { vm.logout() }) { Text("Sign out") }
    }
}
