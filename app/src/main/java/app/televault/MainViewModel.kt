package app.televault

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.televault.data.AuthState
import app.televault.data.MyTelegramSession
import app.televault.data.Td
import app.televault.data.Paths
import app.televault.data.SortMode
import app.televault.data.Vault
import app.televault.data.VaultFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings get() = Vault.settings

    var auth by mutableStateOf<AuthState>(Td.auth.value)
    val configured: Boolean get() = auth is AuthState.Ready
    val accountName: String get() = (auth as? AuthState.Ready)?.name.orEmpty()
    val accountPhone: String get() = (auth as? AuthState.Ready)?.phone.orEmpty()
    var locked by mutableStateOf(false)
    var tab by mutableStateOf(0)

    var folder by mutableStateOf(Paths.ROOT)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var sort by mutableStateOf(SortMode.NAME)

    var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    var pendingSave: VaultFile? = null

    var busy by mutableStateOf(false)
    var setupError by mutableStateOf<String?>(null)

    var lockEnabled by mutableStateOf(Vault.settings.lockEnabled)
    var autoBackup by mutableStateOf(Vault.settings.autoBackup)
    var captionTags by mutableStateOf(Vault.settings.captionTags)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(text: String) {
        _messages.tryEmit(text)
    }

    private fun friendly(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"

    private var triedAutoRestore = false

    init {
        viewModelScope.launch {
            Td.auth.collect { a ->
                auth = a
                if (a is AuthState.Ready && !triedAutoRestore) {
                    triedAutoRestore = true
                    if (Vault.index.value.files.isEmpty()) {
                        try {
                            val n = Vault.restoreIndex()
                            if (n > 0) toast("Restored $n files from your Saved Messages")
                        } catch (e: Exception) {
                            // no backup yet – totally fine
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ login

    private fun login(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            setupError = null
            try {
                block()
            } catch (e: Exception) {
                setupError = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    private var apiSession: MyTelegramSession? = null
    private var apiPhone = ""

    /** Step 1a: sign in to my.telegram.org with the phone number; Telegram sends a code. */
    fun requestApiCode(phone: String) {
        val p = phone.trim()
        if (p.length < 6) {
            setupError = "Enter your phone number with country code, e.g. +98912…"
            return
        }
        login {
            val session = MyTelegramSession(p)
            session.sendCode()
            apiSession = session
            apiPhone = p
            Td.showApiStep(AuthState.ApiCode)
        }
    }

    /** Step 1b: code accepted -> API keys fetched/created -> straight into the normal login. */
    fun submitApiCode(code: String) {
        val session = apiSession ?: run {
            Td.showApiStep(AuthState.ApiPhone)
            return
        }
        if (code.isBlank()) return
        login {
            val keys = session.login(code)
            apiSession = null
            Td.saveCredentials(keys.id, keys.hash, apiPhone)
        }
    }

    fun backToApiPhone() {
        setupError = null
        apiSession = null
        Td.showApiStep(AuthState.ApiPhone)
    }

    fun useManualKeys() {
        setupError = null
        Td.showApiStep(AuthState.NeedCredentials)
    }

    fun saveCredentials(idText: String, hash: String) {
        val id = idText.trim().toIntOrNull()
        val h = hash.trim()
        if (id == null || id <= 0 || h.length < 16) {
            setupError = "Enter the numeric api_id and the 32-character api_hash from my.telegram.org"
            return
        }
        setupError = null
        Td.saveCredentials(id, h)
    }

    fun submitPhone(phone: String) {
        val p = phone.trim()
        if (p.length < 6) {
            setupError = "Enter your phone number with country code, e.g. +98912…"
            return
        }
        login { Td.submitPhone(p) }
    }

    fun submitCode(code: String) {
        if (code.isBlank()) return
        login { Td.submitCode(code.trim()) }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        login { Td.submitPassword(password) }
    }

    fun backToCredentials() {
        setupError = null
        Td.resetCredentials()
    }

    fun restartLogin() {
        setupError = null
        Td.restart()
    }

    fun disconnect() {
        viewModelScope.launch {
            Vault.resetAll()
            lockEnabled = false
            autoBackup = true
            captionTags = false
            folder = Paths.ROOT
            tab = 0
            triedAutoRestore = false
        }
    }

    // ------------------------------------------------------- navigation

    fun openFolder(path: String) {
        folder = path
        searching = false
        query = ""
    }

    fun goUp() {
        folder = Paths.parent(folder)
    }

    // ---------------------------------------------------------- settings

    fun setLock(on: Boolean) {
        lockEnabled = on
        Vault.settings.lockEnabled = on
    }

    fun updateAutoBackup(on: Boolean) {
        autoBackup = on
        Vault.settings.autoBackup = on
    }

    fun backupNow() {
        viewModelScope.launch {
            toast("Backing up index…")
            try {
                val warn = Vault.backupIndex()
                toast(warn ?: "Index backed up and pinned in Saved Messages")
            } catch (e: Exception) {
                toast("Backup failed: ${friendly(e)}")
            }
        }
    }

    fun restoreNow() {
        viewModelScope.launch {
            try {
                val n = Vault.restoreIndex()
                toast("Restored $n files")
            } catch (e: Exception) {
                toast("Restore failed: ${friendly(e)}")
            }
        }
    }

    fun updateCaptionTags(on: Boolean) {
        captionTags = on
        Vault.settings.captionTags = on
    }

    fun writeCaptionsNow() {
        val files = Vault.index.value.files
        if (files.isEmpty()) {
            toast("No files yet")
            return
        }
        viewModelScope.launch {
            toast("Updating captions of ${files.size} file(s)…")
            val failed = Vault.syncCaptions(files)
            toast(if (failed == 0) "Captions updated" else "Captions updated, $failed message(s) couldn't be edited")
        }
    }

    fun scanCaptions() {
        viewModelScope.launch {
            toast("Scanning Saved Messages…")
            try {
                val (found, incomplete) = Vault.scanCaptions { }
                toast(
                    "Found $found tagged file(s)" +
                        if (incomplete > 0) " ($incomplete skipped: a part is missing)" else ""
                )
            } catch (e: Exception) {
                toast("Scan failed: ${friendly(e)}")
            }
        }
    }

    fun importTree(text: String) {
        viewModelScope.launch {
            toast("Importing…")
            try {
                val (ok, skipped) = Vault.importTree(text) { }
                toast("Imported $ok file(s)" + if (skipped > 0) ", skipped $skipped" else "")
            } catch (e: Exception) {
                toast("Import failed: ${friendly(e)}")
            }
        }
    }

    // ------------------------------------------------------ file actions

    fun createFolder(name: String) {
        val err = Vault.createFolder(folder, name)
        if (err != null) toast(err)
    }

    fun rename(file: VaultFile, name: String) {
        val err = Vault.renameFile(file.id, name)
        if (err != null) toast(err)
    }

    fun move(file: VaultFile, target: String) {
        Vault.moveFile(file.id, target)
    }

    fun deleteFile(file: VaultFile) {
        viewModelScope.launch {
            try {
                val failed = Vault.deleteFile(file.id)
                toast(
                    if (failed == 0) "Deleted ${file.name}"
                    else "Removed from TeleVault, but $failed message(s) couldn't be deleted from Saved Messages – delete them there if you want."
                )
            } catch (e: Exception) {
                toast("Delete failed: ${friendly(e)}")
            }
        }
    }

    fun deleteFolder(path: String) {
        viewModelScope.launch {
            try {
                val failed = Vault.deleteFolder(path)
                toast(
                    if (failed == 0) "Folder deleted"
                    else "Folder removed, but $failed message(s) couldn't be deleted from Saved Messages."
                )
            } catch (e: Exception) {
                toast("Delete failed: ${friendly(e)}")
            }
        }
    }
}
