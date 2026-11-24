package app.televault.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/**
 * The "drive". Files live in YOUR Saved Messages as ordinary documents (a file over ~2 GB is split into
 * several documents). The folder tree and the list of message ids live in a small JSON index that is kept
 * on the phone and also uploaded + pinned in Saved Messages. Optionally (Settings tick-box) every file's
 * caption also carries its folder, so the index can be rebuilt by scanning Saved Messages.
 */
object Vault {
    /** Telegram's limit is 2000 MB per file for normal accounts, so stay a little below it. */
    const val CHUNK_SIZE = 1950L * 1024 * 1024

    const val INDEX_NAME = "televault-index.json"
    private const val INDEX_TAG = "#televault_index"
    private const val TAG_PREFIX = "#tv"

    private lateinit var app: Context

    lateinit var settings: Settings
        private set

    private val _index = MutableStateFlow(VaultIndex())
    val index: StateFlow<VaultIndex> = _index.asStateFlow()

    private val fileLock = Any()
    private val indexFile: File get() = File(app.filesDir, "index.json")

    fun init(context: Context) {
        app = context.applicationContext
        settings = Settings(app)
        Td.start(app, settings)
        File(app.cacheDir, "open").deleteRecursively()
        File(app.cacheDir, "chunks").deleteRecursively()
        _index.value = try {
            if (indexFile.exists()) IndexJson.fromJson(indexFile.readText()) else VaultIndex()
        } catch (e: Exception) {
            VaultIndex()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun cacheSub(name: String): File = File(app.cacheDir, name).apply { mkdirs() }

    fun openCacheDir(): File = cacheSub("open")

    fun openOutput(uri: Uri): OutputStream =
        app.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Can't write to that location")

    private fun persist() {
        synchronized(fileLock) {
            val tmp = File(app.filesDir, "index.json.tmp")
            tmp.writeText(IndexJson.toJson(_index.value))
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val finalName = (name ?: uri.lastPathSegment ?: "file").replace('/', '_')
        return finalName to size
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uniqueName(index: VaultIndex, folder: String, wanted: String): String {
        val taken = index.files.filter { it.folder == folder }.map { it.name.lowercase(Locale.ROOT) }.toSet()
        if (wanted.lowercase(Locale.ROOT) !in taken) return wanted
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
            n++
        }
    }

    private fun shorten(s: String): String = if (s.length > 180) s.take(177) + "…" else s

    private fun waitNote(seconds: Int): String? =
        if (seconds > 0) "Telegram rate limit – continuing in $seconds s" else null

    /** File name on disk = the document name Telegram shows. Keep it short enough for Android. */
    private fun diskName(name: String, suffix: String): String {
        val max = 200 - suffix.length
        if (name.length <= max) return name + suffix
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 12) name.substring(dot) else ""
        return name.take(max - ext.length) + ext + suffix
    }

    private fun fileSize(uri: Uri, known: Long): Long {
        if (known >= 0) return known
        return try {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    // ------------------------------------------------------------ captions

    /** Caption for one part. With the tick-box on it ends with a machine-readable "#tv {json}" line. */
    private fun captionFor(name: String, id: String, folder: String, idx: Int, total: Int): String {
        val human = if (total == 1) "TeleVault • ${shorten(name)}" else "TeleVault • ${shorten(name)} • part ${idx + 1}/$total"
        if (!settings.captionTags) return human
        fun tag(withName: Boolean): String {
            val o = JSONObject().put("id", id).put("p", folder).put("i", idx).put("n", total)
            if (withName) o.put("nm", name)
            return "$TAG_PREFIX " + o.toString().replace("\\/", "/")
        }
        for (c in listOf(human + "\n" + tag(true), human + "\n" + tag(false), tag(false))) {
            if (c.length <= 1000) return c
        }
        return human.take(1000)
    }

    /** Rewrites the captions of [files] to match the current folder/name. Returns how many edits failed. */
    suspend fun syncCaptions(files: List<VaultFile>, cancel: CancelToken? = null): Int = withContext(Dispatchers.IO) {
        var failed = 0
        for (f in files) {
            for (c in f.chunks) {
                try {
                    Td.editCaption(c.messageId, captionFor(f.name, f.id, f.folder, c.index, f.chunks.size))
                } catch (e: Exception) {
                    failed++
                }
            }
        }
        failed
    }

    /** Fire-and-forget caption refresh after a move/rename (only when the tick-box is on). */
    private fun syncLater(vararg ids: String) {
        if (!settings.captionTags) return
        val files = _index.value.files.filter { it.id in ids }
        if (files.isEmpty()) return
        bg.launch { syncCaptions(files) }
    }

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------- upload

    suspend fun upload(
        uri: Uri,
        folder: String,
        cancel: CancelToken,
        onMeta: (String, Long) -> Unit,
        onProgress: (Long, Long) -> Unit,
        onNote: (String?) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val (name, knownSize) = queryMeta(uri)
            val size = fileSize(uri, knownSize)
            onMeta(name, size)
            if (size == 0L) throw IOException("\"$name\" is empty")
            if (size < 0) throw IOException("Can't read the size of \"$name\"")
            val mime = app.contentResolver.getType(uri) ?: guessMime(name)
            val fileId = UUID.randomUUID().toString().replace("-", "").take(12)
            val finalName = uniqueName(_index.value, folder, name)
            val parts = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
            val dir = File(cacheSub("chunks"), "up_$fileId").apply { mkdirs() }
            val done = mutableListOf<Chunk>()
            var ok = false
            try {
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't open \"$name\"")
                input.use { ins ->
                    var base = 0L
                    var idx = 0
                    val buf = ByteArray(256 * 1024)
                    while (idx < parts) {
                        if (cancel.cancelled) throw TransferCancelled()
                        val partName = if (parts == 1) diskName(name, "") else diskName(name, ".part" + (idx + 1).toString().padStart(3, '0'))
                        val tmp = File(dir, partName)
                        var written = 0L
                        tmp.outputStream().use { out ->
                            while (written < CHUNK_SIZE) {
                                if (cancel.cancelled) throw TransferCancelled()
                                val want = minOf(buf.size.toLong(), CHUNK_SIZE - written).toInt()
                                val n = ins.read(buf, 0, want)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                            }
                        }
                        if (written == 0L) break
                        val baseNow = base
                        val sent = Td.uploadDocument(
                            file = tmp,
                            caption = captionFor(finalName, fileId, folder, idx, parts),
                            cancel = cancel,
                            onBytes = { s -> onProgress(baseNow + s, size) },
                            onWait = { s -> onNote(waitNote(s)) },
                        )
                        tmp.delete()
                        done.add(Chunk(idx, sent.id, written))
                        base += written
                        idx++
                        onProgress(base, size)
                    }
                }
                if (done.isEmpty()) throw IOException("\"$name\" is empty")
                val newFile = VaultFile(
                    id = fileId,
                    name = finalName,
                    size = done.sumOf { it.size },
                    mime = mime,
                    folder = folder,
                    createdAt = System.currentTimeMillis(),
                    chunks = done.toList(),
                )
                _index.update { cur -> cur.copy(files = cur.files + newFile) }
                persist()
                ok = true
            } finally {
                dir.deleteRecursively()
                if (!ok && done.isNotEmpty()) {
                    try {
                        Td.deleteMessages(done.map { it.messageId })
                    } catch (e: Exception) {
                        // best effort clean-up of a half-finished upload
                    }
                }
            }
            Unit
        }
    }

    // -------------------------------------------------------------- download

    suspend fun download(
        file: VaultFile,
        out: OutputStream,
        cancel: CancelToken,
        onProgress: (Long, Long) -> Unit,
        onNote: (String?) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            var base = 0L
            for (c in file.chunks.sortedBy { it.index }) {
                if (cancel.cancelled) throw TransferCancelled()
                val baseNow = base
                val d = Td.downloadMessage(
                    mid = c.messageId,
                    cancel = cancel,
                    onBytes = { n -> onProgress(baseNow + n, file.size) },
                    onWait = { s -> onNote(waitNote(s)) },
                )
                try {
                    d.path.inputStream().use { it.copyTo(out) }
                    base += c.size
                    onProgress(base, file.size)
                } finally {
                    Td.deleteLocal(d.fileId)
                }
            }
            out.flush()
        }
    }

    // ------------------------------------------------------- index operations

    fun createFolder(parent: String, rawName: String): String? {
        val name = rawName.trim().replace("/", "")
        if (name.isEmpty()) return "Folder name can't be empty"
        val path = Paths.child(parent, name)
        if (_index.value.folders.any { it.equals(path, ignoreCase = true) }) return "That folder already exists"
        _index.update { it.copy(folders = it.folders + path) }
        persist()
        return null
    }

    fun renameFile(id: String, rawName: String): String? {
        val name = rawName.trim().replace("/", "")
        if (name.isEmpty()) return "Name can't be empty"
        _index.update { cur ->
            cur.copy(files = cur.files.map { if (it.id == id) it.copy(name = name) else it })
        }
        persist()
        syncLater(id)
        return null
    }

    fun moveFile(id: String, folder: String) {
        _index.update { cur ->
            val target = cur.files.firstOrNull { it.id == id }
            if (target == null) {
                cur
            } else {
                val unique = uniqueName(cur, folder, target.name)
                cur.copy(files = cur.files.map { if (it.id == id) it.copy(folder = folder, name = unique) else it })
            }
        }
        persist()
        syncLater(id)
    }

    private suspend fun deleteMessages(files: List<VaultFile>): Int {
        val ids = files.flatMap { f -> f.chunks.map { it.messageId } }
        if (ids.isEmpty()) return 0
        return try {
            Td.deleteMessages(ids)
            0
        } catch (e: Exception) {
            ids.size
        }
    }

    /** Returns how many Telegram messages could NOT be deleted. */
    suspend fun deleteFile(id: String): Int = withContext(Dispatchers.IO) {
        val f = _index.value.files.firstOrNull { it.id == id } ?: return@withContext 0
        val failed = deleteMessages(listOf(f))
        _index.update { cur -> cur.copy(files = cur.files.filter { it.id != id }) }
        persist()
        failed
    }

    suspend fun deleteFolder(path: String): Int = withContext(Dispatchers.IO) {
        if (path == Paths.ROOT) return@withContext 0
        val doomed = _index.value.files.filter { Paths.isInside(it.folder, path) }
        val failed = deleteMessages(doomed)
        val ids = doomed.map { it.id }.toSet()
        _index.update { cur ->
            cur.copy(
                folders = cur.folders.filter { !Paths.isInside(it, path) }.toSet(),
                files = cur.files.filter { it.id !in ids },
            )
        }
        persist()
        failed
    }

    // ------------------------------------------------------------ index backup

    private fun isIndexMessage(m: TdApi.Message): Boolean =
        (m.content as? TdApi.MessageDocument)?.document?.fileName == INDEX_NAME

    /** The pinned index if there is one, otherwise the newest index document found by scanning recent messages. */
    private suspend fun findIndexMessage(): TdApi.Message? {
        Td.pinnedMessage()?.let { if (isIndexMessage(it)) return it }
        var from = 0L
        var seen = 0
        while (seen < 3000) {
            val page = Td.history(from, 100)
            if (page.isEmpty()) break
            page.firstOrNull { isIndexMessage(it) }?.let { return it }
            from = page.last().id
            seen += page.size
        }
        return null
    }

    /** Uploads the index to Saved Messages and pins it. Returns a warning text if pinning failed. */
    suspend fun backupIndex(): String? = withContext(Dispatchers.IO) {
        val dir = File(cacheSub("chunks"), "index_backup").apply { deleteRecursively(); mkdirs() }
        val tmp = File(dir, INDEX_NAME)
        tmp.writeText(IndexJson.toJson(_index.value))
        try {
            val count = _index.value.files.size
            val cancel = CancelToken()
            val sent = Td.uploadDocument(tmp, "$INDEX_TAG • $count files", cancel, { }, { })
            var warning: String? = null
            var pinned = false
            try {
                Td.pin(sent.id)
                pinned = true
            } catch (e: Exception) {
                warning = "Backup uploaded, but it couldn't be pinned (${e.message}). Restore will still find it by searching Saved Messages."
            }
            val old = settings.lastBackupMsgId
            if (old != 0L && old != sent.id) {
                try {
                    Td.deleteMessages(listOf(old))
                } catch (e: Exception) {
                    // old backup already gone – harmless
                }
            }
            settings.lastBackupMsgId = sent.id
            if (pinned) null else warning
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Replaces the local index with the backup from Saved Messages. Returns the number of files. */
    suspend fun restoreIndex(): Int = withContext(Dispatchers.IO) {
        val msg = findIndexMessage() ?: throw IOException("No TeleVault index was found in your Saved Messages yet")
        val d = Td.downloadMessage(msg.id, CancelToken(), { }, { })
        try {
            val restored = IndexJson.fromJson(d.path.readText())
            _index.value = restored
            persist()
            settings.lastBackupMsgId = msg.id
            restored.files.size
        } finally {
            Td.deleteLocal(d.fileId)
        }
    }

    // --------------------------------------------------- rebuild from captions

    private val partSuffix = Regex("\\.part\\d{3}$")

    private class Seen(
        val idx: Int,
        val total: Int,
        val messageId: Long,
        val size: Long,
        val docName: String,
        val niceName: String?,
        val mime: String,
        val folder: String,
        val date: Long,
    )

    /**
     * Scans Saved Messages for files whose caption carries a "#tv {…}" tag (written when the Settings tick-box
     * is on) and merges them into the index. Returns (files found, files skipped because a part is missing).
     */
    suspend fun scanCaptions(onProgress: (Int) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val groups = LinkedHashMap<String, MutableList<Seen>>()
        var from = 0L
        var scanned = 0
        while (true) {
            val page = Td.history(from, 100)
            if (page.isEmpty()) break
            for (m in page) {
                val media = Td.mediaOf(m) ?: continue
                val line = Td.captionOf(m).lineSequence().firstOrNull { it.startsWith("$TAG_PREFIX {") } ?: continue
                val o = try { JSONObject(line.removePrefix("$TAG_PREFIX ")) } catch (e: Exception) { continue }
                val id = o.optString("id")
                if (id.isBlank()) continue
                groups.getOrPut(id) { mutableListOf() }.add(
                    Seen(
                        idx = o.optInt("i"),
                        total = o.optInt("n", 1),
                        messageId = m.id,
                        size = media.file.size.toLong(),
                        docName = media.name,
                        niceName = o.optString("nm").ifBlank { null },
                        mime = media.mime.ifBlank { guessMime(media.name) },
                        folder = o.optString("p", Paths.ROOT).let { if (it.startsWith("/")) it else Paths.ROOT },
                        date = m.date * 1000L,
                    )
                )
            }
            from = page.last().id
            scanned += page.size
            onProgress(scanned)
        }
        val found = ArrayList<VaultFile>()
        var incomplete = 0
        for ((id, list) in groups) {
            val total = list.maxOf { it.total }
            val byIdx = list.associateBy { it.idx }
            if ((0 until total).any { it !in byIdx }) {
                incomplete++
                continue
            }
            val first = byIdx.getValue(0)
            val name = first.niceName ?: if (total > 1) first.docName.replace(partSuffix, "") else first.docName
            val chunks = (0 until total).map { Chunk(it, byIdx.getValue(it).messageId, byIdx.getValue(it).size) }
            found.add(VaultFile(id, name, chunks.sumOf { it.size }, first.mime, first.folder, first.date, chunks))
        }
        _index.update { cur ->
            val foundIds = found.map { it.id }.toSet()
            val folders = LinkedHashSet(cur.folders)
            for (f in found) addWithAncestors(folders, f.folder)
            cur.copy(folders = folders, files = cur.files.filter { it.id !in foundIds } + found)
        }
        persist()
        found.size to incomplete
    }

    private fun addWithAncestors(into: MutableSet<String>, path: String) {
        var p = path
        while (p != Paths.ROOT && p.isNotEmpty()) {
            into.add(p)
            p = Paths.parent(p)
        }
    }

    // ------------------------------------------------------ import a tdrive tree

    /**
     * Imports a "tdrive:ttree:v1" export (folders + Saved Messages entries) so files you already organised
     * elsewhere show up here. Returns (imported, skipped).
     */
    suspend fun importTree(text: String, onProgress: (Int) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val start = text.indexOf('{')
        if (start < 0) throw IOException("That doesn't look like a tree export")
        val root = JSONObject(text.substring(start))
        val folderObjs = root.optJSONArray("folders") ?: throw IOException("No folders found in that file")
        class F(val id: String, val name: String, val parent: String?)
        val byId = HashMap<String, F>()
        for (i in 0 until folderObjs.length()) {
            val o = folderObjs.optJSONObject(i) ?: continue
            byId[o.optString("id")] = F(o.optString("id"), o.optString("name"), if (o.isNull("parentId")) null else o.optString("parentId"))
        }
        fun pathOf(id: String?): String {
            var cur = id?.let { byId[it] }
            val parts = ArrayList<String>()
            var guard = 0
            while (cur != null && cur.parent != null && guard++ < 50) {
                parts.add(0, cur.name.replace("/", ""))
                cur = byId[cur.parent]
            }
            return if (parts.isEmpty()) Paths.ROOT else "/" + parts.joinToString("/")
        }
        val entries = root.optJSONArray("entries") ?: throw IOException("No entries found in that file")
        val known = _index.value.files.flatMap { f -> f.chunks.map { it.messageId } }.toHashSet()
        val me = Td.savedChatId.toString()
        val added = ArrayList<VaultFile>()
        var skipped = 0
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val mid = e.optLong("m")
            if (e.opt("c").toString() != me || mid == 0L) { skipped++; continue }
            if (mid in known) continue
            try {
                val msg = Td.getMessage(mid)
                val media = Td.mediaOf(msg)
                if (media == null) {
                    skipped++
                    continue
                }
                val size = media.file.size.toLong()
                val folder = pathOf(e.optString("f"))
                val tmpIndex = _index.value.copy(files = _index.value.files + added)
                added.add(
                    VaultFile(
                        id = UUID.randomUUID().toString().replace("-", "").take(12),
                        name = uniqueName(tmpIndex, folder, media.name.replace('/', '_')),
                        size = size,
                        mime = media.mime.ifBlank { guessMime(media.name) },
                        folder = folder,
                        createdAt = e.optLong("d", msg.date * 1000L),
                        chunks = listOf(Chunk(0, mid, size)),
                    )
                )
            } catch (x: Exception) {
                skipped++
            }
            onProgress(i + 1)
        }
        _index.update { cur ->
            val folders = LinkedHashSet(cur.folders)
            for (f in byId.values) addWithAncestors(folders, pathOf(f.id))
            for (f in added) addWithAncestors(folders, f.folder)
            cur.copy(folders = folders, files = cur.files + added)
        }
        persist()
        added.size to skipped
    }

    // ------------------------------------------------------------------- reset

    /** Logs out of Telegram on this phone and forgets the local index (files stay in Saved Messages). */
    suspend fun resetAll() {
        try {
            Td.logOut()
        } catch (e: Exception) {
            // already logged out / offline – still clear local data
        }
        settings.clearKeepApi()
        _index.value = VaultIndex()
        indexFile.delete()
    }
}
