package app.televault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.televault.MainViewModel
import app.televault.data.AuthState

/** Log in with your own Telegram account – that is what gives the app access to Saved Messages. */
@Composable
fun SetupScreen(vm: MainViewModel) {
    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var apiCode by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    val auth = vm.auth

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("☁️✈️", fontSize = 48.sp)
        Text("TeleVault", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your own cloud drive inside your Telegram Saved Messages. Log in with your Telegram account and your files are stored in your private Saved Messages chat.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (auth) {
            AuthState.Starting -> {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                Text("Starting Telegram…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AuthState.ApiPhone -> {
                Text("Step 1 – connect your Telegram account", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Telegram requires every app to have its own API keys. TeleVault gets them for you: enter your phone number, then the code Telegram sends to your Telegram app. Nothing to copy by hand.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.trim() },
                    label = { Text("Phone number with country code") },
                    placeholder = { Text("+98 912 345 6789") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                StepButtons(vm, "Send code") { vm.requestApiCode(phone) }
                TextButton(onClick = { vm.useManualKeys() }) { Text("Enter keys manually instead") }
            }

            AuthState.ApiCode -> {
                Text("Step 1 – code from my.telegram.org", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Telegram sent a code to your Telegram app (a message from “Telegram”), or by SMS. Enter it here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = apiCode,
                    onValueChange = { apiCode = it.trim() },
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                StepButtons(vm, "Continue") { vm.submitApiCode(apiCode) }
                TextButton(onClick = { vm.backToApiPhone() }) { Text("Wrong number? Start over") }
            }

            AuthState.NeedCredentials -> {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Enter your API keys by hand", style = MaterialTheme.typography.titleMedium)
                        Text("1. Open my.telegram.org and log in with your phone number.")
                        Text("2. Choose “API development tools” and create an app (any name).")
                        Text("3. Copy the api_id (a number) and api_hash (32 characters) below.")
                        TextButton(onClick = {
                            try { uri.openUri("https://my.telegram.org/apps") } catch (e: Exception) { }
                        }) { Text("Open my.telegram.org") }
                    }
                }
                OutlinedTextField(
                    value = apiId,
                    onValueChange = { apiId = it.filter(Char::isDigit) },
                    label = { Text("api_id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it.trim() },
                    label = { Text("api_hash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.saveCredentials(apiId, apiHash) }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                TextButton(onClick = { vm.backToApiPhone() }) { Text("Get keys automatically instead") }
            }

            AuthState.WaitPhone -> {
                Text("Step 2 – your phone number", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.trim() },
                    label = { Text("Phone number with country code") },
                    placeholder = { Text("+98 912 345 6789") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                StepButtons(vm, "Send code") { vm.submitPhone(phone) }
                TextButton(onClick = { vm.backToCredentials() }) { Text("Use different API keys") }
            }

            AuthState.WaitCode -> {
                Text("Step 3 – login code", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Telegram sent a code to your Telegram app on another device (or by SMS). Enter it here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit) },
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                StepButtons(vm, "Log in") { vm.submitCode(code) }
                TextButton(onClick = { vm.restartLogin() }) { Text("Wrong number? Start over") }
            }

            is AuthState.WaitPassword -> {
                Text("Step 4 – two-step verification", style = MaterialTheme.typography.titleMedium)
                if (auth.hint.isNotBlank()) Text("Hint: ${auth.hint}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Cloud password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                StepButtons(vm, "Continue") { vm.submitPassword(password) }
            }

            is AuthState.Unsupported -> {
                Text(auth.text, color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.restartLogin() }) { Text("Try again") }
            }

            is AuthState.Ready -> {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }
        }

        val err = vm.setupError
        if (err != null) Text(err, color = MaterialTheme.colorScheme.error)

        Text(
            "Logging in gives this app access to your Telegram account, like any Telegram app. The session stays on this phone, inside the app's private storage, and the app only talks to Telegram's servers. You can end it any time under Settings → Disconnect, or from Telegram → Settings → Devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepButtons(vm: MainViewModel, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onClick, enabled = !vm.busy) { Text(label) }
        if (vm.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
    }
}
