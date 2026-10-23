package com.developer27.qvlp.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Public copies are published after capture, on SessionFiles.io. Private originals allow recovery. */
internal object PublicSessionStorage {
    const val PATH = "Pictures/Q-VLP"
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val PREFS = "public_session_storage"
    private const val TREE = "tree_uri"
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

    fun requireDestination(context: Context): Uri {
        check(configured(context)) { "Choose $PATH in Sessions → Storage before recording." }
        return destination(context, requireNotNull(selected(context)))
    }

    private fun destination(context: Context, tree: Uri): Uri {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val root = Document(context, document)
        check(root.isDirectory()) { "The selected folder is unavailable. Choose it again in Sessions → Storage." }
        return if (DocumentsContract.getTreeDocumentId(tree) == "primary:$PATH") document
            else root.directory("Q-VLP").uri
    }

    fun error(context: Context): String? = preferences(context).getString("error", null)

    fun syncAll(context: Context, verify: Boolean = false): Result<Int> = runCatching {
        val sessions = SessionFiles.directories(context)
        val failures = mutableListOf<String>()
        sessions.forEach { session ->
            syncSession(context, session, verify).exceptionOrNull()?.let { failures.add(it.message ?: "Save failed.") }
        }
        check(failures.isEmpty()) { failures.distinct().joinToString("\n") }
        sessions.size
    }.also { rememberResult(context, it) }

    fun syncSession(context: Context, session: File, verify: Boolean = false): Result<Unit> = runCatching {
        val root = Document(context, requireDestination(context))
        val target = root.directory(session.name)
        val receiptFile = File(context.filesDir, "public_session_receipts/${session.name}.json")
        val receipt = runCatching { SessionFiles.readJson(receiptFile) }.getOrElse { JSONObject() }
        val records = if (receipt.optString("destination") == root.uri.toString())
            receipt.optJSONObject("directories") ?: JSONObject() else JSONObject()
        // A crash during repair must not leave an old receipt claiming that the files are complete.
        if (receiptFile.exists()) check(receiptFile.delete()) { "Cannot update the session save record." }
        mirror(session, session, target, records, verify)
        receiptFile.parentFile?.mkdirs()
        SessionFiles.writeJson(receiptFile, JSONObject().put("destination", root.uri.toString())
            .put("tree", selected(context).toString()).put("directories", records)
            .put("signature", signature(session.walkTopDown().filter { it.isFile && publishable(it) }.toList(), session)))
    }.also { rememberResult(context, it) }

    fun status(context: Context, session: File): String {
        if (!configured(context)) return "Choose $PATH in Storage"
        if (error(context) != null) return "Public save needs attention · Open Storage"
        val receipt = runCatching { SessionFiles.readJson(File(context.filesDir, "public_session_receipts/${session.name}.json")) }.getOrNull()
        val current = signature(session.walkTopDown().filter { it.isFile && publishable(it) }.toList(), session)
        return if (receipt?.optString("tree") == selected(context).toString() && receipt.optString("signature") == current) "Saved to $PATH"
            else "Waiting to save to $PATH · Open Storage to retry"
    }

    private fun rememberResult(context: Context, result: Result<*>) {
        preferences(context).edit().apply {
            if (result.isFailure) putString("error", result.exceptionOrNull()?.message ?: "Save failed.")
            else remove("error")
        }.apply()
    }

    private fun publishable(file: File) = !file.name.endsWith(".part") && !file.name.endsWith(".bak") && !file.name.endsWith(".new")

    private fun signature(files: List<File>, root: File): String = digest(
        files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.joinToString("\n") {
            "${it.relativeTo(root).invariantSeparatorsPath}:${it.length()}:${it.lastModified()}"
        }.byteInputStream())

    private fun mirror(root: File, source: File, target: Document, records: JSONObject, verify: Boolean) {
        val children = source.listFiles() ?: error("Cannot read ${source.name}.")
        val files = children.filter { it.isFile && publishable(it) }
        val key = source.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
        val fingerprint = signature(files, source)
        val previous = records.optJSONObject(key)
        // Each completed attempt is immutable. Receipts avoid rereading old frames on every capture.
        if (verify || target.created || previous?.optString("signature") != fingerprint || previous.optString("uri") != target.uri.toString()) {
            val existing = target.children().associateBy { it.name }
            // Publish the capture's completion metadata only after its frames and CSV are verified.
            files.sortedWith(compareBy<File> {
                when (it.name) { "burst.json" -> 2; "frames.csv" -> 1; else -> 0 }
            }.thenBy { it.name }).forEach { sourceFile ->
                target.publish(sourceFile, existing[sourceFile.name], existing[".${sourceFile.name}.qvlp-part"])
            }
            records.put(key, JSONObject().put("signature", fingerprint).put("uri", target.uri.toString()))
        }
        children.filter { it.isDirectory }.forEach { child ->
            mirror(root, child, target.directory(child.name), records, verify)
        }
    }

    private fun digest(input: InputStream): String = input.buffered().use { stream ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Entry(val name: String, val uri: Uri, val directory: Boolean)

    private class Document(val context: Context, val uri: Uri, val created: Boolean = false) {
        private val resolver get() = context.contentResolver
        private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)

        fun isDirectory(): Boolean = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: false

        fun children(): List<Entry> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
            return resolver.query(children, columns, null, null, null)?.use { cursor ->
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
                return Document(context, found.uri)
            }
            return Document(context, DocumentsContract.createDocument(resolver, uri, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: error("Cannot create $name in $PATH."), created = true)
        }

        fun publish(source: File, existing: Entry?, unfinished: Entry?) {
            val expected = digest(source.inputStream())
            if (existing != null) {
                check(!existing.directory) { "${source.name} is a folder where a session file is needed." }
                val actual = resolver.openInputStream(existing.uri)?.let(::digest)
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
                output.buffered().use { out -> source.inputStream().buffered().use { it.copyTo(out) } }
                check(resolver.openInputStream(partial)?.let(::digest) == expected) { "Saved file verification failed: ${source.name}." }
                if (existing != null) check(DocumentsContract.deleteDocument(resolver, existing.uri)) { "Cannot update ${source.name}." }
                checkNotNull(DocumentsContract.renameDocument(resolver, partial, source.name)) { "Cannot finish saving ${source.name}." }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                throw e
            }
        }
    }
}
