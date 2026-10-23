package com.developer27.qvlp.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.CancellationSignal
import java.util.concurrent.CancellationException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal class CopyControl(private val yieldToCapture: Boolean = false) {
    val signal = CancellationSignal()
    fun cancel() = signal.cancel()
    fun check() {
        while (yieldToCapture && SessionFiles.activeCapture != null && !signal.isCanceled) Thread.sleep(25)
        if (signal.isCanceled) throw CancellationException("Saving paused. Original files remain safe.")
    }
}

internal data class SessionFolder(val file: File, val files: List<File>, val children: List<SessionFolder>, val signature: String) {
    val allFiles: List<File> get() = files + children.flatMap { it.allFiles }
}
internal data class SessionSnapshot(val root: SessionFolder, val signature: String, val frozenRoot: File? = null) {
    fun discard() { frozenRoot?.deleteRecursively() }
}

/** The capture worker snapshots immutable attempts; a separate worker publishes public copies. */
internal object PublicSessionStorage {
    const val PATH = "Pictures/Q-VLP"
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val PREFS = "public_session_storage"
    private const val TREE = "tree_uri"
    private val receiptsLock = Any()
    private val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val initialUri: Uri = DocumentsContract.buildDocumentUri(AUTHORITY, "primary:Pictures")

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun selected(context: Context): Uri? = preferences(context).getString(TREE, null)?.let(Uri::parse)

    fun accepts(uri: Uri): Boolean = uri.authority == AUTHORITY && runCatching {
        DocumentsContract.getTreeDocumentId(uri) in setOf("primary:Pictures", "primary:$PATH")
    }.getOrDefault(false)

    fun configured(context: Context): Boolean {
        val uri = selected(context) ?: return false
        return accepts(uri) && context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun select(context: Context, uri: Uri) {
        require(accepts(uri)) { "Select Internal storage → Pictures, or its Q-VLP folder." }
        context.contentResolver.takePersistableUriPermission(uri, access)
        val previous = selected(context)
        // Resolve/create Q-VLP before remembering this destination. Cancellation never changes it.
        destination(context, uri)
        check(preferences(context).edit().putString(TREE, uri.toString()).remove("error").commit()) {
            "Cannot remember the session folder."
        }
        if (previous != null && previous != uri) runCatching {
            context.contentResolver.releasePersistableUriPermission(previous, access)
        }
    }

    fun requireDestination(context: Context, control: CopyControl = CopyControl()): Uri {
        check(configured(context)) { "Tap the auto-save status on the camera screen and choose $PATH before recording." }
        return destination(context, requireNotNull(selected(context)), control)
    }

    private fun destination(context: Context, tree: Uri, control: CopyControl = CopyControl()): Uri {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val root = Document(context, document, control)
        check(root.isDirectory()) { "The selected folder is unavailable. Tap the auto-save status on the camera screen to choose it again." }
        return if (DocumentsContract.getTreeDocumentId(tree) == "primary:$PATH") document
            else root.directory("Q-VLP").uri
    }

    fun error(context: Context): String? = preferences(context).all.entries
        .filter { it.key.startsWith("error_session_") }.mapNotNull { it.value as? String }
        .distinct().joinToString("\n").ifBlank { null }

    /** Called on SessionFiles.io. Never include an attempt that the camera is still writing. */
    fun snapshot(session: File): SessionSnapshot {
        fun read(directory: File): SessionFolder {
            val entries = directory.listFiles() ?: error("Cannot read ${directory.name}.")
            val files = entries.filter { it.isFile && publishable(it) }
            val children = entries.filter { it.isDirectory && it != SessionFiles.activeCapture }.map(::read)
            return SessionFolder(directory, files, children, signature(files, directory))
        }
        val root = read(session)
        return SessionSnapshot(root, signature(root.allFiles, session))
    }

    /** Freeze mutable session CSV/metadata while immutable attempt folders are copied in place. */
    fun freeze(context: Context, snapshot: SessionSnapshot): SessionSnapshot {
        val folder = File(context.cacheDir, "public_session_snapshots/${java.util.UUID.randomUUID()}")
        check(folder.mkdirs()) { "Cannot prepare the session files for saving." }
        try {
            val files = snapshot.root.files.map { source -> source.copyTo(File(folder, source.name)) }
            return snapshot.copy(root = snapshot.root.copy(files = files), frozenRoot = folder)
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    fun clearAbandonedSnapshots(context: Context) {
        File(context.cacheDir, "public_session_snapshots").deleteRecursively()
    }

    fun recordError(context: Context, id: String, error: Throwable) {
        preferences(context).edit().putString("error_$id", error.message ?: "Cannot prepare session files.").apply()
    }

    private fun receiptFile(context: Context, id: String) = File(context.filesDir, "public_session_receipts/$id.json")

    /** Find only: deletion must never create a missing destination or session folder. */
    fun deleteSession(context: Context, id: String) {
        require(SessionFiles.validId(id) && SessionFiles.isDeleting(context, id))
        val tree = selected(context)
        if (tree == null) {
            check(!receiptFile(context, id).exists()) { "Tap the auto-save status on the camera screen and choose $PATH to finish deleting this session." }
            return // Never published and no public folder selected.
        }
        check(configured(context)) { "Tap the auto-save status on the camera screen and choose $PATH to finish deleting this session." }
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val control = CopyControl()
        val root = Document(context, rootUri, control)
        val destination = if (DocumentsContract.getTreeDocumentId(tree) == "primary:$PATH") root else {
            val entry = root.children().firstOrNull { it.name == "Q-VLP" } ?: return
            check(entry.directory) { "$PATH is not a folder." }
            Document(context, entry.uri, control)
        }
        val session = destination.children().firstOrNull { it.name == id } ?: return
        check(session.directory) { "The saved session is not a folder. Check $PATH before retrying." }
        check(DocumentsContract.deleteDocument(context.contentResolver, session.uri)) { "Cannot delete the session from $PATH. Retry deletion." }
    }

    fun forgetSession(context: Context, id: String) {
        require(SessionFiles.validId(id))
        synchronized(receiptsLock) { android.util.AtomicFile(receiptFile(context, id)).delete() }
        check(preferences(context).edit().remove("error_$id").commit()) { "Cannot finish session cleanup. Retry deletion." }
    }

    fun needsSave(context: Context, snapshot: SessionSnapshot): Boolean {
        val receipt = synchronized(receiptsLock) { runCatching { SessionFiles.readJson(receiptFile(context, snapshot.root.file.name)) }.getOrNull() }
        return preferences(context).contains("error_${snapshot.root.file.name}") ||
            receipt?.optString("tree") != selected(context).toString() || receipt.optString("signature") != snapshot.signature
    }

    fun syncAll(context: Context, verify: Boolean = false): Result<Int> = runCatching {
        val failures = mutableListOf<String>()
        val snapshots = SessionFiles.directories(context).filterNot { SessionFiles.isDeleting(context, it.name) }.map(::snapshot)
        snapshots.filter { verify || needsSave(context, it) }.forEach { snapshot ->
            syncSession(context, snapshot, verify).exceptionOrNull()?.let { failures.add(it.message ?: "Save failed.") }
        }
        check(failures.isEmpty()) { failures.distinct().joinToString("\n") }
        snapshots.size
    }

    fun syncSession(context: Context, session: File, verify: Boolean = false): Result<Unit> =
        syncSession(context, snapshot(session), verify)

    fun syncSession(context: Context, snapshot: SessionSnapshot, verify: Boolean = false,
                    control: CopyControl = CopyControl(), progress: (Int, Int) -> Unit = { _, _ -> }): Result<Unit> {
        val id = snapshot.root.file.name
        return runCatching {
            if (SessionFiles.isDeleting(context, id)) throw CancellationException("Session is being deleted.")
            control.check()
            val root = Document(context, requireDestination(context, control), control)
            val target = root.directory(id)
            val receiptFile = receiptFile(context, id)
            val receipt = synchronized(receiptsLock) { runCatching { SessionFiles.readJson(receiptFile) }.getOrElse { JSONObject() } }
            val records = if (!verify && receipt.optString("destination") == root.uri.toString())
                receipt.optJSONObject("directories") ?: JSONObject() else JSONObject()
            receiptFile.parentFile?.mkdirs()
            fun checkpoint(signature: String = "") = synchronized(receiptsLock) {
                SessionFiles.writeJson(receiptFile, JSONObject().put("destination", root.uri.toString())
                    .put("tree", selected(context).toString()).put("directories", records).put("signature", signature))
            }
            // Preserve completed attempts across pause/process death, while keeping this session pending.
            checkpoint()
            var completed = 0
            val total = snapshot.root.allFiles.size
            progress(0, total)
            mirror(snapshot.root.file, snapshot.root, target, records, verify, control, { checkpoint() }) { count ->
                completed += count
                progress(completed, total)
            }
            control.check()
            checkpoint(snapshot.signature)
        }.also { result ->
            if (result.exceptionOrNull() !is CancellationException && result.exceptionOrNull() !is android.os.OperationCanceledException) {
                preferences(context).edit().remove("error").apply {
                    if (result.isFailure) putString("error_$id", result.exceptionOrNull()?.message ?: "Save failed.")
                    else remove("error_$id")
                }.apply()
            }
        }
    }

    fun status(context: Context, session: File): String {
        if (!configured(context)) return "Set up auto-save from the camera screen"
        if (preferences(context).contains("error_${session.name}")) return "Auto-save needs attention · Tap the camera screen’s auto-save status"
        return if (!needsSave(context, snapshot(session))) "Saved to $PATH"
            else "Auto-save pending · $PATH"
    }

    private fun publishable(file: File) = !file.name.endsWith(".part") && !file.name.endsWith(".bak") && !file.name.endsWith(".new")

    private fun signature(files: List<File>, root: File): String = digest(
        files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.joinToString("\n") {
            "${it.relativeTo(root).invariantSeparatorsPath}:${it.length()}:${it.lastModified()}"
        }.byteInputStream())

    private fun mirror(root: File, source: SessionFolder, target: Document, records: JSONObject,
                       verify: Boolean, control: CopyControl, checkpoint: () -> Unit, advance: (Int) -> Unit) {
        control.check()
        // Publish attempts before the session index that references them.
        source.children.forEach { child -> mirror(root, child, target.directory(child.file.name), records, verify, control, checkpoint, advance) }
        val key = source.file.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
        val previous = records.optJSONObject(key)
        if (verify || target.created || previous?.optString("signature") != source.signature || previous.optString("uri") != target.uri.toString()) {
            records.remove(key)
            checkpoint()
            val existing = target.children().associateBy { it.name }
            source.files.sortedWith(compareBy<File> {
                when (it.name) { "burst.json", "session.json" -> 2; "frames.csv" -> 1; else -> 0 }
            }.thenBy { it.name }).forEach { file ->
                control.check()
                target.publish(file, existing[file.name], existing[".${file.name}.qvlp-part"])
                advance(1)
            }
            records.put(key, JSONObject().put("signature", source.signature).put("uri", target.uri.toString()))
            checkpoint()
        } else advance(source.files.size)
    }

    private fun digest(input: InputStream, control: CopyControl? = null): String = input.buffered().use { stream ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            control?.check()
            val count = stream.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Entry(val name: String, val uri: Uri, val directory: Boolean)

    private class Document(val context: Context, val uri: Uri, private val control: CopyControl, val created: Boolean = false) {
        private val resolver get() = context.contentResolver
        private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)

        fun isDirectory(): Boolean = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null, control.signal)?.use {
            it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: false

        fun children(): List<Entry> {
            control.check()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
            return resolver.query(children, columns, null, null, null, control.signal)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Entry(cursor.getString(1),
                        DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)),
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            } ?: error("Cannot read $PATH.")
        }

        fun directory(name: String): Document {
            val found = children().firstOrNull { it.name == name }
            if (found != null) {
                check(found.directory) { "$name is a file where a session folder is needed." }
                return Document(context, found.uri, control)
            }
            return Document(context, DocumentsContract.createDocument(resolver, uri, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: error("Cannot create $name in $PATH."), control, created = true)
        }

        fun publish(source: File, existing: Entry?, unfinished: Entry?) {
            val expected = digest(source.inputStream(), control)
            if (existing != null) {
                check(!existing.directory) { "${source.name} is a folder where a session file is needed." }
                val actual = resolver.openInputStream(existing.uri)?.let { digest(it, control) }
                if (actual == expected) return
            }
            val partialName = ".${source.name}.qvlp-part"
            unfinished?.let {
                check(DocumentsContract.deleteDocument(resolver, it.uri)) { "Cannot replace an unfinished save." }
            }
            val partial = DocumentsContract.createDocument(resolver, uri, "application/octet-stream", partialName)
                ?: error("Cannot save ${source.name} to $PATH.")
            try {
                val output = resolver.openOutputStream(partial, "wt") ?: error("Cannot open ${source.name} for saving.")
                output.buffered().use { out -> source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        control.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                } }
                check(resolver.openInputStream(partial)?.let { digest(it, control) } == expected) { "Saved file verification failed: ${source.name}." }
                control.check()
                if (existing != null) check(DocumentsContract.deleteDocument(resolver, existing.uri)) { "Cannot update ${source.name}." }
                checkNotNull(DocumentsContract.renameDocument(resolver, partial, source.name)) { "Cannot finish saving ${source.name}." }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                throw e
            }
        }
    }
}
