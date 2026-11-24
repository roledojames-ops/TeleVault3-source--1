package app.televault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.biometric.BiometricManager
import app.televault.MainViewModel
import app.televault.data.Vault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val index by Vault.index.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf("") }
    val total = index.files.sumOf { it.size }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                vm.importTree(text)
            } catch (e: Exception) {
                vm.toast("Couldn't read that file")
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card("Connected") {
                Text("👤 ${vm.accountName}" + if (vm.accountPhone.isNotBlank()) " (+${vm.accountPhone.removePrefix("+")})" else "")
                Text("💬 Saved Messages")
                Text("🗂 ${index.files.size} files • ${formatSize(ctx, total)}")
            }
            Card("Security") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Lock app (fingerprint / PIN)")
                    Switch(checked = vm.lockEnabled, onCheckedChange = { on ->
                        if (!on) vm.setLock(false) else {
                            val ok = BiometricManager.from(ctx).canAuthenticate(
                                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            ) == BiometricManager.BIOMETRIC_SUCCESS
                            if (ok) vm.setLock(true) else vm.toast("Set up a screen lock on your phone first")
                        }
                    })
                }
            }
            Card("Index backup") {
                Text("The list of your files is stored on this phone. A backup copy (televault-index.json) is pinned in your Saved Messages so you can restore it after reinstalling.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto-backup after uploads")
                    Switch(checked = vm.autoBackup, onCheckedChange = { vm.updateAutoBackup(it) })
                }
                OutlinedButton(onClick = { vm.backupNow() }, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
                OutlinedButton(onClick = { confirm = "restore" }, modifier = Modifier.fillMaxWidth()) { Text("Restore from Saved Messages") }
            }
            Card("Folder info in captions") {
                Text(
                    "When ticked, every file's caption in Saved Messages also stores its folder (a line starting with #tv). If the index is ever lost, “Rebuild from captions” can recover your folders by scanning Saved Messages. Folders you created but left empty can't be recovered this way.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Write folder info into captions")
                    Switch(checked = vm.captionTags, onCheckedChange = { vm.updateCaptionTags(it) })
                }
                OutlinedButton(onClick = { vm.writeCaptionsNow() }, enabled = vm.captionTags, modifier = Modifier.fillMaxWidth()) { Text("Write captions for existing files") }
                OutlinedButton(onClick = { vm.scanCaptions() }, modifier = Modifier.fillMaxWidth()) { Text("Rebuild from captions") }
            }
            Card("Import") {
                Text("Already have a tdrive tree export (.json) of your Saved Messages? Import it to get its folders and files here.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import tree file") }
            }
            Card("Good to know") {
                Text("• Files are stored as normal documents in your Saved Messages, so you can also see them in the Telegram app.", style = MaterialTheme.typography.bodySmall)
                Text("• Files over about 1.9 GB are split into parts automatically and re-joined on download.", style = MaterialTheme.typography.bodySmall)
                Text("• If Telegram says you're uploading too fast, TeleVault waits the requested time and continues by itself.", style = MaterialTheme.typography.bodySmall)
                Text("• Don't delete TeleVault files from Saved Messages by hand, or they'll show as broken here.", style = MaterialTheme.typography.bodySmall)
                Text("• Files are not end-to-end encrypted (normal Telegram cloud storage).", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { confirm = "disconnect" }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }

    if (confirm == "restore") ConfirmDialog("Restore index?", "This replaces the file list on this phone with the backup from your Saved Messages.", { confirm = "" }) {
        vm.restoreNow(); confirm = ""
    }
    if (confirm == "disconnect") ConfirmDialog("Disconnect?", "You are logged out of Telegram on this phone and the local file list is cleared. Your files stay in Saved Messages.", { confirm = "" }) {
        vm.disconnect(); confirm = ""
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
