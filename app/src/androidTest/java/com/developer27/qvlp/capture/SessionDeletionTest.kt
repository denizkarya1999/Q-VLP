package com.developer27.qvlp.capture

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionDeletionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture() = File(context.filesDir, "sessions/session_${UUID.randomUUID()}").apply {
        mkdirs()
        SessionFiles.writeJson(File(this, "session.json"), JSONObject().put("test", "BENCH DELETE ONLY"))
        File(this, "frame.yuv").writeBytes(byteArrayOf(1, 2, 3))
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
    private fun clean(session: File) {
        if (PublicSessionStorage.configured(context)) find(PublicSessionStorage.requireDestination(context), session.name)
            ?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
        session.deleteRecursively()
        PublicSessionStorage.forgetSession(context, session.name)
        SessionFiles.finishDeletion(context, session.name)
    }
    private fun delete(session: File): Boolean {
        val done = CountDownLatch(1)
        var result: Result<Unit>? = null
        var active = false
        SessionPublisher.delete(context, session.name, { active = it }, { result = it; done.countDown() })
        assertTrue("Deletion should finish", done.await(20, TimeUnit.SECONDS))
        requireNotNull(result).getOrThrow()
        return active
    }

    @Test fun removesBothCopiesAndCachedZipButKeepsOtherSessionsAndPositionSettings() {
        assumeTrue(PublicSessionStorage.configured(context))
        val target = fixture()
        val sibling = fixture()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf("active_research_session", "last_capture_position")
        val before = keys.associateWith { prefs.getString(it, null) }
        val untouched = prefs.all.filterKeys { it !in keys }
        val cached = File(context.cacheDir, "session_exports/${target.name}_test.zip").apply { parentFile!!.mkdirs(); writeText("test") }
        val externalExport = File(context.cacheDir, "independent_export_${UUID.randomUUID()}.zip").apply { writeText("keep") }
        try {
            PublicSessionStorage.syncSession(context, target).getOrThrow()
            PublicSessionStorage.syncSession(context, sibling).getOrThrow()
            prefs.edit().putString(keys[0], target.name).putString(keys[1], "test position").commit()
            assertTrue(delete(target))
            assertFalse(target.exists())
            assertFalse(cached.exists())
            assertEquals("keep", externalExport.readText())
            assertNull(find(PublicSessionStorage.requireDestination(context), target.name))
            assertNotNull(find(PublicSessionStorage.requireDestination(context), sibling.name))
            assertTrue(sibling.exists())
            assertFalse(File(context.filesDir, "public_session_receipts/${target.name}.json").exists())
            assertFalse(SessionFiles.isDeleting(context, target.name))
            keys.forEach { assertFalse(prefs.contains(it)) }
            assertEquals(untouched, prefs.all.filterKeys { it !in keys })
        } finally {
            prefs.edit().apply { before.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
            cached.delete(); externalExport.delete(); clean(target); clean(sibling)
        }
    }

    @Test fun deletingDuringAutoSaveCancelsTheCopyAndCannotRecreateTheSession() {
        assumeTrue(PublicSessionStorage.configured(context))
        val target = fixture()
        try {
            SessionFiles.activeCapture = File(context.cacheDir, "unrelated_capture")
            SessionFiles.io.submit { SessionPublisher.captureReady(context, target, 999999) }.get(5, TimeUnit.SECONDS)
            // The publisher is either queued or waiting for this unrelated capture to finish.
            assertFalse(delete(target))
            SessionFiles.activeCapture = null
            SessionPublisher.request(context)
            SessionFiles.io.submit {}.get(10, TimeUnit.SECONDS)
            assertFalse(target.exists())
            assertNull(find(PublicSessionStorage.requireDestination(context), target.name))
        } finally { SessionFiles.activeCapture = null; clean(target) }
    }

    @Test fun failedPublicDeletionKeepsOriginalAndMarkerUntilAccessIsRestored() {
        assumeTrue(PublicSessionStorage.configured(context))
        val target = fixture()
        val name = "delete_storage_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(if (key == "public_session_storage") name else key, mode)
        }
        try {
            PublicSessionStorage.syncSession(context, target).getOrThrow()
            SessionFiles.io.submit { SessionFiles.beginDeletion(context, target.name) }.get(5, TimeUnit.SECONDS)
            prefs.edit().putString("tree_uri", "content://com.android.externalstorage.documents/tree/other%3APictures").commit()
            assertTrue(runCatching { PublicSessionStorage.deleteSession(isolated, target.name) }.isFailure)
            assertArrayEquals(byteArrayOf(1, 2, 3), File(target, "frame.yuv").readBytes())
            assertTrue(SessionFiles.isDeleting(context, target.name))
            assertTrue(SessionFiles.list(context).first { it.id == target.name }.deleting)
            assertTrue(runCatching { SessionFiles.archive(context, target.name) }.isFailure)
            assertTrue(PublicSessionStorage.syncSession(context, target).isFailure)
            prefs.edit().putString("tree_uri", context.getSharedPreferences("public_session_storage", 0).getString("tree_uri", null)).commit()
            PublicSessionStorage.deleteSession(isolated, target.name)
            // Simulate process death after the public folder has gone: retry is idempotent.
            delete(target)
            assertFalse(target.exists())
            assertFalse(SessionFiles.isDeleting(context, target.name))
        } finally { clean(target); context.deleteSharedPreferences(name) }
    }

    @Test fun retryAfterPrivateCleanupFinishesAndRemovesThePersistentListEntry() {
        val target = fixture()
        try {
            SessionFiles.io.submit { SessionFiles.beginDeletion(context, target.name) }.get(5, TimeUnit.SECONDS)
            PublicSessionStorage.deleteSession(context, target.name)
            SessionFiles.deletePrivateFiles(context, target.name)
            assertFalse(target.exists())
            assertTrue(SessionFiles.list(context).any { it.id == target.name && it.deleting })
            delete(target)
            assertFalse(SessionFiles.list(context).any { it.id == target.name })
        } finally { clean(target) }
    }

    private fun node(text: String): android.view.accessibility.AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(root: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.text?.toString() == text) return root
            for (index in 0 until root.childCount) find(root.getChild(index))?.let { return it }
            return null
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow)?.let { return it }
            Thread.sleep(50)
        }
        fun visible(root: android.view.accessibility.AccessibilityNodeInfo?): List<String> =
            if (root == null) emptyList() else listOfNotNull(root.text?.toString()) +
                (0 until root.childCount).flatMap { visible(root.getChild(it)) }
        error("Screen did not show: $text. Visible: ${visible(automation.rootInActiveWindow)}")
    }

    private fun press(text: String, hold: Boolean = false) {
        node(text)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 3_000)
        val bounds = android.graphics.Rect()
        node(text).getBoundsInScreen(bounds)
        val x = bounds.centerX(); val y = bounds.centerY()
        val command = if (hold) "input swipe $x $y $x $y 800" else "input tap $x $y"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Test fun tapShowsDetailsAndHoldingSelectsMultipleSessionsForConfirmedDeletion() {
        assumeTrue(PublicSessionStorage.configured(context))
        val first = fixture()
        val second = fixture()
        val sibling = fixture()
        val root = PublicSessionStorage.requireDestination(context)
        try {
            listOf(first, second, sibling).forEachIndexed { index, file ->
                SessionFiles.writeJson(File(file, "session.json"), JSONObject().put("test", "BENCH DELETE UI ONLY")
                    .put("created_at", "2038-01-01T12:00:0${index + 1}Z"))
                SessionCsv.update(file)
                PublicSessionStorage.syncSession(context, file).getOrThrow()
            }
            val labels = SessionFiles.list(context).associate { it.id to it.label }
            androidx.test.core.app.ActivityScenario.launch<com.developer27.qvlp.MainActivity>(
                android.content.Intent(context, com.developer27.qvlp.MainActivity::class.java)).use {
                press("Sessions")
                assertFalse(node("Delete (0)").isEnabled)
                press(labels.getValue(first.name))
                node("Session details")
                press("Close")
                assertFalse(node("Delete (0)").isEnabled)
                press(labels.getValue(first.name), hold = true)
                node("Delete (1)")
                assertTrue(node(labels.getValue(first.name)).isChecked)
                press(labels.getValue(second.name))
                press("Delete (2)")
                node("Delete 2 sessions?")
                press("Cancel")
                assertTrue(first.exists() && second.exists())
                assertNotNull(find(root, first.name)); assertNotNull(find(root, second.name))
                assertTrue(node(labels.getValue(first.name)).isChecked)
                assertTrue(node(labels.getValue(second.name)).isChecked)
                press(labels.getValue(second.name))
                node("Delete (1)")
                press(labels.getValue(second.name))
                press("Delete (2)")
                press("Delete")
                val deadline = android.os.SystemClock.uptimeMillis() + 20_000
                while ((first.exists() || second.exists() || SessionFiles.isDeleting(context, second.name)) &&
                    android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                assertFalse(first.exists()); assertFalse(second.exists())
                assertNull(find(root, first.name)); assertNull(find(root, second.name))
                assertTrue(sibling.exists()); assertNotNull(find(root, sibling.name))
            }
        } finally { clean(first); clean(second); clean(sibling) }
    }

    @Test fun csvHelpExampleImportsWithoutChangingTheUsersPositions() {
        val name = "csv_help_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val example = context.getString(com.developer27.qvlp.R.string.positions_csv_example)
            assertEquals(3, PositionCatalog.import(example.byteInputStream(), prefs))
            val positions = PositionCatalog.read(prefs)
            assertEquals(listOf(0, 1, 2), positions.map { it.id })
            assertEquals(0.5, positions[2].x!!, 0.0)
            assertEquals(0.5, positions[2].y!!, 0.0)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun rejectsInvalidIdsAndTheSessionCurrentlyRecording() {
        val target = fixture()
        try {
            for (id in listOf("../sessions", "session_../other", "", "session_example/child"))
                assertTrue(runCatching { SessionFiles.beginDeletion(context, id) }.isFailure)
            SessionFiles.activeCapture = File(target, "position_active")
            assertTrue(runCatching { SessionFiles.beginDeletion(context, target.name) }.isFailure)
            assertFalse(SessionFiles.isDeleting(context, target.name))
            assertTrue(target.exists())
        } finally { SessionFiles.activeCapture = null; clean(target) }
    }
}
