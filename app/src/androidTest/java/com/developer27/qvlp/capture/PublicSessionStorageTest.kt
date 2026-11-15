package com.developer27.qvlp.capture

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PublicSessionStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "com.android.externalstorage.documents"

    @Test fun onlyAcceptsTheRequestedInternalPicturesFolder() {
        for (path in listOf("primary:Pictures", "primary:Pictures/Q-VLP"))
            assertTrue(PublicSessionStorage.accepts(DocumentsContract.buildTreeDocumentUri(authority, path)))
        for (path in listOf("primary:", "primary:Download", "primary:Pictures/Other", "primary:Pictures/Q-VLP/child", "other:Pictures"))
            assertFalse(PublicSessionStorage.accepts(DocumentsContract.buildTreeDocumentUri(authority, path)))
        assertFalse(PublicSessionStorage.accepts(Uri.parse("content://another.provider/tree/primary%3APictures")))
    }

    @Test fun missingAccessCannotPretendToSaveAndKeepsTheOriginal() {
        val prefsName = "storage_test_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefsName, mode)
        }
        val source = File(context.cacheDir, "session_${UUID.randomUUID()}").apply { mkdirs() }
        val frame = File(source, "frame.yuv").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            assertFalse(PublicSessionStorage.configured(isolated))
            assertTrue(PublicSessionStorage.syncSession(isolated, source).isFailure)
            assertArrayEquals(byteArrayOf(1, 2, 3), frame.readBytes())
            assertNotNull(PublicSessionStorage.error(isolated))
        } finally {
            source.deleteRecursively()
            context.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun publishesExactFilesAndRepeatedAttemptsAndRepairsInterruptedOrModifiedCopies() {
        // Integration test: enable Pictures/Q-VLP once in the app before running on a device.
        assumeTrue("Choose Pictures/Q-VLP in the app first", PublicSessionStorage.configured(context))
        val root = PublicSessionStorage.requireDestination(context)
        val source = File(context.cacheDir, "session_${UUID.randomUUID()}").apply { mkdirs() }
        val first = File(source, "position_999999_first").apply { mkdirs() }
        val firstFrames = File(first, SessionLayout.FRAMES).apply { mkdirs() }
        val bytes = ByteArray(131_073) { (it * 31).toByte() }
        File(source, "session.json").writeText("{\"test\":true}")
        File(firstFrames, "frame_00000.png").writeBytes(bytes)
        File(first, "burst.json").writeText("{\"state\":\"interrupted\"}")
        File(firstFrames, "frame_00001.png.part").writeText("unfinished")
        try {
            PublicSessionStorage.syncSession(context, source).getOrThrow()
            val publicSession = requireNotNull(find(root, source.name))
            val publicFirst = requireNotNull(find(publicSession, first.name))
            val publicFrames = requireNotNull(find(publicFirst, SessionLayout.FRAMES))
            assertNull(find(publicFrames, "frame_00001.png.part"))
            // The app's own bookkeeping stays in its private storage.
            assertNull(find(publicSession, "session.json"))
            assertNull(find(publicFirst, "burst.json"))
            val frameUri = requireNotNull(find(publicFrames, "frame_00000.png"))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(frameUri)!!.use { it.readBytes() })
            // A second save must preserve the existing attempt and URI, without duplicate folders.
            PublicSessionStorage.syncSession(context, source).getOrThrow()
            assertEquals(publicFirst, find(publicSession, first.name))
            assertEquals(frameUri, find(publicFrames, "frame_00000.png"))
            File(first, SessionLayout.MAPPING).writeText(SessionLayout.mapping(listOf(0)))
            val second = File(source, "position_999999_repeat").apply { mkdirs() }
            File(File(second, SessionLayout.FRAMES).apply { mkdirs() }, "frame_00000.png").writeBytes(byteArrayOf(7, 8, 9))
            PublicSessionStorage.syncSession(context, source).getOrThrow()
            assertNotNull(find(publicSession, second.name))
            assertEquals(File(first, SessionLayout.MAPPING).readText(), context.contentResolver.openInputStream(
                requireNotNull(find(publicFirst, SessionLayout.MAPPING)))!!.bufferedReader().use { it.readText() })
            // Repair corruption of the same size and a missing file during explicit verification.
            context.contentResolver.openOutputStream(frameUri, "wt")!!.use { it.write(ByteArray(bytes.size)) }
            DocumentsContract.deleteDocument(context.contentResolver, requireNotNull(find(publicFirst, SessionLayout.MAPPING)))
            PublicSessionStorage.syncSession(context, source, verify = true).getOrThrow()
            assertArrayEquals(bytes, context.contentResolver.openInputStream(
                requireNotNull(find(publicFrames, "frame_00000.png")))!!.use { it.readBytes() })
            assertNotNull(find(publicFirst, SessionLayout.MAPPING))
            assertNull(PublicSessionStorage.error(context))
            assertArrayEquals(bytes, File(firstFrames, "frame_00000.png").readBytes())
            // A missing public attempt is recreated during automatic recovery as well.
            DocumentsContract.deleteDocument(context.contentResolver, publicFirst)
            PublicSessionStorage.syncSession(context, source).getOrThrow()
            assertNotNull(find(requireNotNull(find(requireNotNull(find(publicSession, first.name)), SessionLayout.FRAMES)), "frame_00000.png"))
            // A failed frame copy must not publish a mapping that lists the frame as present.
            val failed = File(source, "position_999999_failed").apply { mkdirs() }
            val failedFrames = File(failed, SessionLayout.FRAMES).apply { mkdirs() }
            File(failed, SessionLayout.MAPPING).writeText(SessionLayout.mapping(listOf(0)))
            File(failedFrames, "frame_00000.png").writeBytes(bytes)
            val publicFailed = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
                publicSession, DocumentsContract.Document.MIME_TYPE_DIR, failed.name))
            val publicFailedFrames = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
                publicFailed, DocumentsContract.Document.MIME_TYPE_DIR, SessionLayout.FRAMES))
            val obstacle = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
                publicFailedFrames, DocumentsContract.Document.MIME_TYPE_DIR, "frame_00000.png"))
            assertTrue(PublicSessionStorage.syncSession(context, source).isFailure)
            assertNull(find(publicFailed, SessionLayout.MAPPING))
            assertNotNull(PublicSessionStorage.error(context))
            assertArrayEquals(bytes, File(failedFrames, "frame_00000.png").readBytes())
            DocumentsContract.deleteDocument(context.contentResolver, obstacle)
            // Simulate a process stopping while a public temporary file was being written.
            val partial = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
                publicFailedFrames, "application/octet-stream", ".frame_00000.png.qvlp-part"))
            context.contentResolver.openOutputStream(partial)!!.use { it.write(byteArrayOf(1)) }
            PublicSessionStorage.syncSession(context, source).getOrThrow()
            assertNotNull(find(publicFailed, SessionLayout.MAPPING))
            assertNull(find(publicFailedFrames, ".frame_00000.png.qvlp-part"))
            assertNull(PublicSessionStorage.error(context))
        } finally {
            find(root, source.name)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            File(context.filesDir, "public_session_receipts/${source.name}.json").delete()
            source.deleteRecursively()
        }
    }

    @Test fun snapshotsExcludeActiveCapturesAndDoNotGrowWhileBeingPublished() {
        val session = File(context.cacheDir, "session_${UUID.randomUUID()}").apply { mkdirs() }
        val active = File(SessionLayout.originals(session), "position_active").apply { mkdirs() }
        File(active, "frame_00000.png").writeBytes(byteArrayOf(1))
        // The processed folder of a capture still being written is not ready either.
        File(SessionLayout.processedFolder(active).apply { mkdirs() }, "frame_00000.png").writeBytes(byteArrayOf(1))
        File(session, "session.json").writeText("{}")
        File(session, SessionLayout.FINAL).writeText("position_id\n")
        try {
            SessionFiles.activeCapture = active
            val before = PublicSessionStorage.snapshot(session)
            assertEquals(listOf(SessionLayout.FINAL), before.root.allFiles.map { it.name })
            SessionFiles.activeCapture = null
            val closed = PublicSessionStorage.snapshot(session)
            assertEquals(3, closed.root.allFiles.size)
            File(active, "frame_00001.png").writeBytes(byteArrayOf(2))
            assertEquals(3, closed.root.allFiles.size)
            assertEquals(4, PublicSessionStorage.snapshot(session).root.allFiles.size)
        } finally {
            SessionFiles.activeCapture = null
            session.deleteRecursively()
        }
    }

    @Test fun pausedCopyKeepsOriginalsAndCanResumeWithoutMarkingAnUnfinishedSessionSaved() {
        assumeTrue(PublicSessionStorage.configured(context))
        val root = PublicSessionStorage.requireDestination(context)
        val source = File(context.cacheDir, "session_${UUID.randomUUID()}").apply { mkdirs() }
        File(source, "first.yuv").writeBytes(byteArrayOf(1, 2, 3))
        File(source, "second.yuv").writeBytes(byteArrayOf(4, 5, 6))
        val snapshot = PublicSessionStorage.snapshot(source)
        val control = CopyControl()
        try {
            val result = PublicSessionStorage.syncSession(context, snapshot, control = control) { done, _ ->
                if (done == 1) control.cancel()
            }
            assertTrue(result.isFailure)
            assertTrue(PublicSessionStorage.needsSave(context, snapshot))
            assertEquals(2, source.listFiles()!!.size)
            PublicSessionStorage.syncSession(context, snapshot).getOrThrow()
            assertFalse(PublicSessionStorage.needsSave(context, snapshot))
            // Deleting an already-published folder must not make reopening trigger its restoration.
            DocumentsContract.deleteDocument(context.contentResolver, requireNotNull(find(root, source.name)))
            assertFalse(PublicSessionStorage.needsSave(context, snapshot))
        } finally {
            find(root, source.name)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            File(context.filesDir, "public_session_receipts/${source.name}.json").delete()
            source.deleteRecursively()
        }
    }

    @Test fun blockedPublicCopyDoesNotBlockTheCaptureWorker() {
        assumeTrue(PublicSessionStorage.configured(context))
        val root = PublicSessionStorage.requireDestination(context)
        val source = File(context.cacheDir, "session_${UUID.randomUUID()}").apply { mkdirs() }
        File(source, "frame.yuv").writeBytes(byteArrayOf(1, 2, 3))
        val snapshot = PublicSessionStorage.snapshot(source)
        val control = CopyControl(yieldToCapture = true)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            SessionFiles.activeCapture = File(context.cacheDir, "simulated_active_capture")
            val copy = executor.submit<Result<Unit>> { PublicSessionStorage.syncSession(context, snapshot, control = control) }
            val captureWork = SessionFiles.io.submit<Int> { 42 }
            assertEquals(42, captureWork.get(1, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(copy.isDone)
            SessionFiles.activeCapture = null
            copy.get(10, java.util.concurrent.TimeUnit.SECONDS).getOrThrow()
        } finally {
            SessionFiles.activeCapture = null
            control.cancel()
            executor.shutdown()
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            find(root, source.name)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            File(context.filesDir, "public_session_receipts/${source.name}.json").delete()
            source.deleteRecursively()
        }
    }

    @Test fun finishedPositionsAndRepeatsAutoSaveInOneSessionEvenAfterAnEarlierPause() {
        assumeTrue(PublicSessionStorage.configured(context))
        val root = PublicSessionStorage.requireDestination(context)
        val source = File(context.cacheDir, "session_${UUID.randomUUID()}").apply { mkdirs() }
        File(source, "session.json").writeText("{\"test\":true}")
        val prefs = context.getSharedPreferences("public_session_storage", Context.MODE_PRIVATE)
        val previousPause = prefs.all["paused"] as? Boolean
        prefs.edit().putBoolean("paused", true).commit()
        fun finish(position: Int, name: String, bytes: ByteArray) {
            val attempt = File(source, name).apply { mkdirs() }
            File(attempt, "frame_00000.yuv").writeBytes(bytes)
            File(attempt, "burst.json").writeText(org.json.JSONObject().put("state", "complete")
                .put("position", org.json.JSONObject().put("position_id", position))
                .put("frames_saved", 1).put("finished_at", java.time.Instant.now().toString()).toString())
            val before = PublicSessionStorage.snapshot(source)
            assertTrue(PublicSessionStorage.needsSave(context, before))
            SessionFiles.io.submit { SessionPublisher.captureReady(context, source, position) }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            val snapshot = PublicSessionStorage.snapshot(source)
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            while (PublicSessionStorage.needsSave(context, snapshot) && android.os.SystemClock.uptimeMillis() < deadline)
                Thread.sleep(50)
            assertFalse("Position should save without a manual Save action", PublicSessionStorage.needsSave(context, snapshot))
            val publicSession = requireNotNull(find(root, source.name))
            val publicAttempt = requireNotNull(find(publicSession, name))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(
                requireNotNull(find(publicAttempt, "frame_00000.yuv")))!!.use { it.readBytes() })
        }
        try {
            finish(999998, "position_999998_first", byteArrayOf(1, 2, 3))
            assertFalse(prefs.contains("paused"))
            finish(999999, "position_999999_next", byteArrayOf(4, 5, 6))
            finish(999999, "position_999999_repeat", byteArrayOf(7, 8, 9))
            val session = requireNotNull(find(root, source.name))
            for (name in listOf("position_999998_first", "position_999999_next", "position_999999_repeat"))
                assertNotNull("Every position and repeat must remain in the same session", find(session, name))
        } finally {
            if (previousPause == null) prefs.edit().remove("paused").commit()
            else prefs.edit().putBoolean("paused", previousPause).commit()
            find(root, source.name)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            File(context.filesDir, "public_session_receipts/${source.name}.json").delete()
            source.deleteRecursively()
        }
    }

    @Test fun publicationFreezesMutableIndexAndPublishesAttemptsBeforeIt() {
        assumeTrue(PublicSessionStorage.configured(context))
        val root = PublicSessionStorage.requireDestination(context)
        val source = File(context.cacheDir, "session_${UUID.randomUUID()}").apply { mkdirs() }
        File(source, "session.json").writeText("generation one")
        File(source, SessionLayout.FINAL).writeText("first index")
        val attempt = File(source, "position_first").apply { mkdirs() }
        File(attempt, "frame.yuv").writeBytes(byteArrayOf(1, 2, 3))
        val frozen = PublicSessionStorage.freeze(context, PublicSessionStorage.snapshot(source))
        try {
            File(source, "session.json").writeText("generation two")
            File(source, SessionLayout.FINAL).writeText("second index")
            PublicSessionStorage.syncSession(context, frozen) { done, _ ->
                if (done == 1) {
                    val target = requireNotNull(find(root, source.name))
                    assertNotNull(find(requireNotNull(find(target, attempt.name)), "frame.yuv"))
                    assertNull("Index must follow its frames", find(target, SessionLayout.FINAL))
                }
            }.getOrThrow()
            val target = requireNotNull(find(root, source.name))
            assertEquals("first index", context.contentResolver.openInputStream(requireNotNull(find(target, SessionLayout.FINAL)))!!.bufferedReader().use { it.readText() })
            assertNull("Bookkeeping stays private", find(target, "session.json"))
            assertTrue(PublicSessionStorage.needsSave(context, PublicSessionStorage.snapshot(source)))
            PublicSessionStorage.syncSession(context, source).getOrThrow()
            assertEquals("second index", context.contentResolver.openInputStream(requireNotNull(find(target, SessionLayout.FINAL)))!!.bufferedReader().use { it.readText() })
        } finally {
            frozen.discard()
            find(root, source.name)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            File(context.filesDir, "public_session_receipts/${source.name}.json").delete()
            source.deleteRecursively()
        }
    }

    private fun find(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
            while (it.moveToNext()) if (it.getString(1) == name)
                return@use DocumentsContract.buildDocumentUriUsingTree(parent, it.getString(0))
            null
        }
    }
}
