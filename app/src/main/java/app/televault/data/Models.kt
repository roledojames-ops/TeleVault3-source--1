package app.televault.data

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** One piece of a stored file: a message in Saved Messages. Files over ~2 GB are split into several. */
data class Chunk(
    val index: Int,
    val messageId: Long,
    val size: Long,
)

data class VaultFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val folder: String,
    val createdAt: Long,
    val chunks: List<Chunk>,
)

/** The whole "drive": virtual folders + files. Persisted locally and backed up to Telegram. */
data class VaultIndex(
    val folders: Set<String> = emptySet(),
    val files: List<VaultFile> = emptyList(),
)

enum class SortMode(val label: String) {
    NAME("Name"),
    DATE("Newest"),
    SIZE("Largest"),
}

enum class TState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class TransferItem(
    val id: String,
    val title: String,
    val isUpload: Boolean,
    val state: TState,
    val done: Long,
    val total: Long,
    val error: String? = null,
    /** Short status line, e.g. "Rate limited – retrying in 12 s". */
    val note: String? = null,
) {
    val active: Boolean get() = state == TState.QUEUED || state == TState.RUNNING
}

sealed interface TransferEvent {
    data class OpenFile(val file: File, val mime: String) : TransferEvent
    data class Info(val text: String) : TransferEvent
}

class TransferCancelled : Exception("Cancelled")

/** Lets the UI abort a transfer, even while it waits on Telegram. */
class CancelToken {
    @Volatile
    var cancelled: Boolean = false
        private set

    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    fun cancel() {
        cancelled = true
        for (h in hooks) try { h() } catch (e: Exception) { }
    }

    /** Runs [hook] when cancelled (immediately if already cancelled). Returns a function that removes it. */
    fun addHook(hook: () -> Unit): () -> Unit {
        hooks.add(hook)
        if (cancelled) try { hook() } catch (e: Exception) { }
        return { hooks.remove(hook); Unit }
    }
}

/** An error reported by Telegram (TDLib). [retryAfter] > 0 means "you're sending too fast, wait N seconds". */
class TdError(message: String, val code: Int = 0) : IOException(message) {
    val retryAfter: Int
        get() {
            val m = RETRY.find(message.orEmpty())
            if (m != null) return m.groupValues[1].toIntOrNull() ?: 5
            return if (code == 429) 10 else 0
        }

    private companion object {
        val RETRY = Regex("(?:retry after |FLOOD_WAIT_|FLOOD_PREMIUM_WAIT_|SLOWMODE_WAIT_)(\\d+)", RegexOption.IGNORE_CASE)
    }
}

/** Virtual folder paths: "/" is the root, "/Docs/Taxes" is a nested folder. */
object Paths {
    const val ROOT = "/"

    fun child(parent: String, name: String): String =
        if (parent == ROOT) "/$name" else "$parent/$name"

    fun parent(path: String): String {
        if (path == ROOT) return ROOT
        val i = path.lastIndexOf('/')
        return if (i <= 0) ROOT else path.substring(0, i)
    }

    fun name(path: String): String = if (path == ROOT) "Home" else path.substringAfterLast('/')

    /** True when [path] is [folder] itself or lives somewhere below it. [folder] must not be the root. */
    fun isInside(path: String, folder: String): Boolean =
        path == folder || path.startsWith("$folder/")
}
