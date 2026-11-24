package app.televault.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Everything that touches TDLib lives in this file (plus the `import org.drinkless.tdlib…` lines in Vault.kt).
 * If your TDLib package uses a different Java package name, change those imports.
 */
sealed interface AuthState {
    data object Starting : AuthState
    /** Step 1a: phone number for my.telegram.org (to fetch the API keys automatically). */
    data object ApiPhone : AuthState
    /** Step 1b: code that my.telegram.org sent. */
    data object ApiCode : AuthState
    /** Fallback: type api_id / api_hash by hand. */
    data object NeedCredentials : AuthState
    data object WaitPhone : AuthState
    data object WaitCode : AuthState
    data class WaitPassword(val hint: String) : AuthState
    data class Ready(val name: String, val phone: String) : AuthState
    data class Unsupported(val text: String) : AuthState
}

/** A file attached to a message, whatever kind of media it is. */
data class Media(val file: TdApi.File, val name: String, val mime: String)

data class Downloaded(val path: File, val fileId: Int)

object Td {
    /** Telegram asks for waits of hours sometimes; beyond this we give up instead of hanging forever. */
    private const val MAX_WAIT_SECONDS = 3600
    private const val MAX_FLOOD_RETRIES = 40

    private lateinit var app: Context
    private lateinit var settings: Settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var client: Client? = null
    private var started = false

    @Volatile
    private var afterClose: (() -> Unit)? = null

    private val _auth = MutableStateFlow<AuthState>(AuthState.Starting)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    /** The chat id of your Saved Messages (it is your own user id). */
    @Volatile
    var savedChatId: Long = 0L
        private set

    private val fileListeners = ConcurrentHashMap<Int, (TdApi.File) -> Unit>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Message>>()

    // ------------------------------------------------------------------ lifecycle

    fun start(context: Context, s: Settings) {
        if (started) return
        started = true
        app = context.applicationContext
        settings = s
        if (s.apiId == 0 || s.apiHash.isBlank()) _auth.value = AuthState.ApiPhone else createClient()
    }

    /** Phone already typed for my.telegram.org; reused automatically for the TDLib login. */
    @Volatile
    private var pendingPhone: String? = null

    fun saveCredentials(id: Int, hash: String, phone: String? = null) {
        settings.apiId = id
        settings.apiHash = hash
        pendingPhone = phone
        createClient()
    }

    fun showApiStep(state: AuthState) {
        _auth.value = state
    }

    /** From the phone-number step: go back and enter different API credentials. */
    fun resetCredentials() {
        afterClose = {
            settings.apiId = 0
            settings.apiHash = ""
            pendingPhone = null
            _auth.value = AuthState.ApiPhone
        }
        client?.send(TdApi.Close(), null)
    }

    suspend fun logOut() {
        afterClose = { createClient() }
        send(TdApi.LogOut())
    }

    /** Throws the current client away and starts a fresh login attempt. */
    fun restart() {
        afterClose = { createClient() }
        val c = client
        if (c != null) c.send(TdApi.Close(), null) else createClient()
    }

    private fun createClient() {
        _auth.value = AuthState.Starting
        try {
            try {
                Client.execute(TdApi.SetLogVerbosityLevel(1))
            } catch (t: Throwable) {
                // not important
            }
            client = Client.create(Client.ResultHandler { onUpdate(it) }, null, null)
        } catch (t: Throwable) {
            _auth.value = AuthState.Unsupported("Couldn't start the Telegram library: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ updates

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateFile -> fileListeners[o.file.id]?.invoke(o.file)
            is TdApi.UpdateMessageSendSucceeded ->
                pending.getOrPut(o.oldMessageId) { CompletableDeferred() }.complete(o.message)
            is TdApi.UpdateMessageSendFailed ->
                pending.getOrPut(o.oldMessageId) { CompletableDeferred() }
                    .completeExceptionally(TdError(o.error.message.orEmpty(), o.error.code))
            else -> {}
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> scope.launch {
                try {
                    val p = TdApi.SetTdlibParameters()
                    p.useTestDc = false
                    p.databaseDirectory = File(app.filesDir, "td").absolutePath
                    p.filesDirectory = File(app.cacheDir, "td_files").absolutePath
                    p.databaseEncryptionKey = ByteArray(0)
                    p.useFileDatabase = true
                    p.useChatInfoDatabase = true
                    p.useMessageDatabase = true
                    p.useSecretChats = false
                    p.apiId = settings.apiId
                    p.apiHash = settings.apiHash
                    p.systemLanguageCode = "en"
                    p.deviceModel = Build.MODEL ?: "Android"
                    p.systemVersion = Build.VERSION.RELEASE ?: ""
                    p.applicationVersion = "2.0.0"
                    raw(p)
                } catch (e: Exception) {
                    _auth.value = AuthState.Unsupported("Telegram rejected the API credentials: ${e.message}")
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _auth.value = AuthState.WaitPhone
                val phone = pendingPhone
                pendingPhone = null
                if (phone != null) scope.launch {
                    try {
                        submitPhone(phone)
                    } catch (e: Exception) {
                        _auth.value = AuthState.Unsupported("Couldn't send the login code: ${e.message}")
                    }
                }
            }
            is TdApi.AuthorizationStateWaitCode -> _auth.value = AuthState.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = AuthState.WaitPassword(s.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> scope.launch { openSavedMessages() }
            is TdApi.AuthorizationStateLoggingOut -> _auth.value = AuthState.Starting
            is TdApi.AuthorizationStateClosed -> {
                client = null
                savedChatId = 0L
                val next = afterClose
                afterClose = null
                next?.invoke()
            }
            is TdApi.AuthorizationStateWaitRegistration ->
                _auth.value = AuthState.Unsupported("This phone number has no Telegram account yet. Create one in the official Telegram app first.")
            else ->
                _auth.value = AuthState.Unsupported("Telegram asked for a login step this app doesn't support (${s.javaClass.simpleName}). Log in once in the official Telegram app, then try again.")
        }
    }

    private suspend fun openSavedMessages() {
        try {
            val me = send(TdApi.GetMe())
            val req = TdApi.CreatePrivateChat()
            req.userId = me.id
            req.force = true
            val chat = send(req)
            savedChatId = chat.id
            val name = listOf(me.firstName, me.lastName).filter { !it.isNullOrBlank() }.joinToString(" ")
            _auth.value = AuthState.Ready(name.ifBlank { "Your account" }, me.phoneNumber.orEmpty())
        } catch (e: Exception) {
            _auth.value = AuthState.Unsupported("Logged in, but Saved Messages couldn't be opened: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ login steps

    suspend fun submitPhone(number: String) {
        val r = TdApi.SetAuthenticationPhoneNumber()
        r.phoneNumber = number
        send(r)
    }

    suspend fun submitCode(value: String) {
        val r = TdApi.CheckAuthenticationCode()
        r.code = value
        send(r)
    }

    suspend fun submitPassword(value: String) {
        val r = TdApi.CheckAuthenticationPassword()
        r.password = value
        send(r)
    }

    // ------------------------------------------------------------------ requests

    private suspend fun <T : TdApi.Object> raw(f: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            cont.resumeWithException(IOException("Not connected to Telegram"))
        } else {
            c.send(f) { r ->
                if (r is TdApi.Error) {
                    cont.resumeWithException(TdError(r.message.orEmpty(), r.code))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(r as T)
                }
            }
        }
    }

    /** One request, with automatic wait-and-retry when Telegram says "too many requests". */
    suspend fun <T : TdApi.Object> send(
        f: TdApi.Function<T>,
        cancel: CancelToken? = null,
        onWait: (Int) -> Unit = {},
    ): T = retrying(cancel, onWait) { raw(f) }

    /**
     * Runs [block]; if Telegram throttles us (FLOOD_WAIT / HTTP 429 / "retry after N") it waits N seconds
     * (counting down through [onWait]) and tries again. Server hiccups (5xx) are retried a few times too.
     */
    suspend fun <R> retrying(cancel: CancelToken?, onWait: (Int) -> Unit, block: suspend () -> R): R {
        var floods = 0
        var hiccups = 0
        while (true) {
            if (cancel?.cancelled == true) throw TransferCancelled()
            try {
                return block()
            } catch (e: TdError) {
                val wait = e.retryAfter
                if (wait > 0) {
                    if (wait > MAX_WAIT_SECONDS || floods >= MAX_FLOOD_RETRIES) {
                        throw IOException("Telegram says you're doing this too fast and asked to wait ${formatWait(wait)}. Try again later.")
                    }
                    floods++
                    countdown(wait + 1, cancel, onWait)
                } else if (e.code >= 500 && hiccups < 3) {
                    hiccups++
                    countdown(2 * hiccups + 1, cancel, onWait)
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun countdown(seconds: Int, cancel: CancelToken?, onWait: (Int) -> Unit) {
        var left = seconds
        try {
            while (left > 0) {
                if (cancel?.cancelled == true) throw TransferCancelled()
                onWait(left)
                delay(1000L)
                left--
            }
        } finally {
            onWait(0)
        }
    }

    private fun formatWait(sec: Int): String = when {
        sec >= 3600 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
        sec >= 60 -> "${sec / 60} min ${sec % 60} s"
        else -> "$sec s"
    }

    // ------------------------------------------------------------------ messages & files

    private fun formatted(t: String): TdApi.FormattedText {
        val f = TdApi.FormattedText()
        f.text = t
        f.entities = emptyArray<TdApi.TextEntity>()
        return f
    }

    private fun deleteRequest(ids: List<Long>): TdApi.DeleteMessages {
        val r = TdApi.DeleteMessages()
        r.chatId = savedChatId
        r.messageIds = ids.toLongArray()
        r.revoke = true
        return r
    }

    fun mediaOf(m: TdApi.Message): Media? = when (val c = m.content) {
        is TdApi.MessageDocument -> Media(c.document.document, c.document.fileName.orEmpty().ifBlank { "document_${m.id}" }, c.document.mimeType.orEmpty())
        is TdApi.MessageVideo -> Media(c.video.video, c.video.fileName.orEmpty().ifBlank { "video_${m.id}.mp4" }, c.video.mimeType.orEmpty().ifBlank { "video/mp4" })
        is TdApi.MessageAudio -> Media(c.audio.audio, c.audio.fileName.orEmpty().ifBlank { "audio_${m.id}.mp3" }, c.audio.mimeType.orEmpty().ifBlank { "audio/mpeg" })
        is TdApi.MessageAnimation -> Media(c.animation.animation, c.animation.fileName.orEmpty().ifBlank { "animation_${m.id}.mp4" }, c.animation.mimeType.orEmpty().ifBlank { "video/mp4" })
        is TdApi.MessageVoiceNote -> Media(c.voiceNote.voice, "voice_${m.id}.ogg", c.voiceNote.mimeType.orEmpty().ifBlank { "audio/ogg" })
        is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.width * it.height }?.let { Media(it.photo, "photo_${m.id}.jpg", "image/jpeg") }
        else -> null
    }

    fun captionOf(m: TdApi.Message): String = when (val c = m.content) {
        is TdApi.MessageDocument -> c.caption?.text
        is TdApi.MessageVideo -> c.caption?.text
        is TdApi.MessageAudio -> c.caption?.text
        is TdApi.MessageAnimation -> c.caption?.text
        is TdApi.MessageVoiceNote -> c.caption?.text
        is TdApi.MessagePhoto -> c.caption?.text
        else -> null
    }.orEmpty()

    suspend fun getMessage(mid: Long, cancel: CancelToken? = null, onWait: (Int) -> Unit = {}): TdApi.Message {
        val r = TdApi.GetMessage()
        r.chatId = savedChatId
        r.messageId = mid
        return send(r, cancel, onWait)
    }

    /** Newest-first page of Saved Messages. Keep calling with the last id until it returns an empty list. */
    suspend fun history(fromId: Long, count: Int): List<TdApi.Message> {
        val r = TdApi.GetChatHistory()
        r.chatId = savedChatId
        r.fromMessageId = fromId
        r.offset = 0
        r.limit = count
        r.onlyLocal = false
        return send(r).messages.orEmpty().filter { it.id != fromId }
    }

    suspend fun pinnedMessage(): TdApi.Message? {
        val r = TdApi.GetChatPinnedMessage()
        r.chatId = savedChatId
        return try {
            send(r)
        } catch (e: TdError) {
            null
        }
    }

    suspend fun pin(mid: Long) {
        val r = TdApi.PinChatMessage()
        r.chatId = savedChatId
        r.messageId = mid
        r.disableNotification = true
        r.onlyForSelf = false
        send(r)
    }

    suspend fun deleteMessages(ids: List<Long>, cancel: CancelToken? = null) {
        for (batch in ids.chunked(100)) send(deleteRequest(batch), cancel)
    }

    /** Replaces a message caption. Telegram's "nothing changed" answer is not an error for us. */
    suspend fun editCaption(mid: Long, text: String) {
        val r = TdApi.EditMessageCaption()
        r.chatId = savedChatId
        r.messageId = mid
        r.caption = formatted(text)
        try {
            send(r)
        } catch (e: TdError) {
            if (!e.message.orEmpty().contains("MESSAGE_NOT_MODIFIED", ignoreCase = true)) throw e
        }
    }

    /**
     * Uploads [file] into Saved Messages as a document (the document's name is the file's name on disk).
     * Waits and retries on throttling. Returns the final message (its id is the permanent one).
     */
    suspend fun uploadDocument(
        file: File,
        caption: String,
        cancel: CancelToken,
        onBytes: (Long) -> Unit,
        onWait: (Int) -> Unit,
    ): TdApi.Message = retrying(cancel, onWait) {
        val content = TdApi.InputMessageDocument()
        content.document = TdApi.InputFileLocal(file.absolutePath)
        content.disableContentTypeDetection = true
        content.caption = formatted(caption)
        val req = TdApi.SendMessage()
        req.chatId = savedChatId
        req.inputMessageContent = content

        val sent = raw(req)
        val tempId = sent.id
        val local = (sent.content as? TdApi.MessageDocument)?.document?.document
        val waiter = pending.getOrPut(tempId) { CompletableDeferred() }
        if (local != null) fileListeners[local.id] = { f -> onBytes(f.remote.uploadedSize.toLong()) }
        val unhook = cancel.addHook {
            waiter.completeExceptionally(TransferCancelled())
            scope.launch { try { raw(deleteRequest(listOf(tempId))) } catch (e: Exception) { } }
        }
        try {
            waiter.await()
        } catch (e: TdError) {
            // Don't leave a failed copy lying around in Saved Messages before we retry.
            try { raw(deleteRequest(listOf(tempId))) } catch (x: Exception) { }
            throw e
        } finally {
            unhook()
            if (local != null) fileListeners.remove(local.id)
            pending.remove(tempId)
        }
    }

    /** Downloads the file of message [mid] to TDLib's cache and returns where it is. Call [deleteLocal] afterwards. */
    suspend fun downloadMessage(
        mid: Long,
        cancel: CancelToken,
        onBytes: (Long) -> Unit,
        onWait: (Int) -> Unit,
    ): Downloaded = retrying(cancel, onWait) {
        val msg = try {
            getMessage(mid)
        } catch (e: TdError) {
            if (e.code == 404 || e.code == 400) throw IOException("A part of this file no longer exists in Saved Messages")
            throw e
        }
        val media = mediaOf(msg) ?: throw IOException("That Saved Messages entry doesn't contain a file anymore")
        val id = media.file.id
        fileListeners[id] = { f -> onBytes(f.local.downloadedSize.toLong()) }
        val unhook = cancel.addHook {
            val c = TdApi.CancelDownloadFile()
            c.fileId = id
            c.onlyIfPending = false
            client?.send(c, null)
        }
        try {
            val d = TdApi.DownloadFile()
            d.fileId = id
            d.priority = 32
            d.offset = 0
            d.limit = 0
            d.synchronous = true
            val f = raw(d)
            if (cancel.cancelled) throw TransferCancelled()
            if (!f.local.isDownloadingCompleted) throw IOException("The download didn't finish")
            Downloaded(File(f.local.path), f.id)
        } catch (e: TdError) {
            if (cancel.cancelled) throw TransferCancelled()
            throw e
        } finally {
            unhook()
            fileListeners.remove(id)
        }
    }

    /** Frees TDLib's cached copy once we've copied the bytes where they belong. */
    suspend fun deleteLocal(fileId: Int) {
        try {
            val r = TdApi.DeleteFile()
            r.fileId = fileId
            raw(r)
        } catch (e: Exception) {
            // cache clean-up only
        }
    }
}
