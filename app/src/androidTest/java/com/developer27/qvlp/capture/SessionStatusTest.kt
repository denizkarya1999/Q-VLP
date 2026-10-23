package com.developer27.qvlp.capture

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SessionStatusTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private fun fixture() = File(context.filesDir, "sessions/session_${UUID.randomUUID()}").apply {
        mkdirs()
        File(this, "session.json").writeText("{\"test\":\"BENCH SESSION STATUS ONLY\"}")
        File(this, "sample.txt").writeText("Disposable status test")
    }
    private fun enqueue(session: File, position: Int) {
        SessionFiles.io.submit { SessionPublisher.captureReady(context, session, position) }.get(5, TimeUnit.SECONDS)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(25)
        assertTrue(message, condition())
    }
    private fun restoreAndClean(previous: String?, sessions: List<File>) {
        SessionFiles.activeCapture = null
        prefs.edit().apply {
            if (previous == null) remove("active_research_session") else putString("active_research_session", previous)
        }.commit()
        sessions.forEach { session ->
            val done = CountDownLatch(1)
            var result: Result<Unit>? = null
            SessionPublisher.delete(context, session.name, {}, { result = it; done.countDown() })
            assertTrue(done.await(15, TimeUnit.SECONDS))
            requireNotNull(result).getOrThrow()
        }
        SessionPublisher.sessionChanged()
    }

    @Test fun newSessionClearsTheCompletedPositionMessageWithoutRemovingItsRecording() {
        assumeTrue(PublicSessionStorage.configured(context))
        val previous = prefs.getString("active_research_session", null)
        val session = fixture()
        val state = AtomicReference(SessionPublisher.State())
        val stop = SessionPublisher.observe { state.set(it) }
        try {
            prefs.edit().putString("active_research_session", session.name).commit()
            enqueue(session, 2)
            await("Current session should report its saved position") { state.get().message.startsWith("Position 2 saved") }
            SessionFiles.newSession(context)
            await("New Session must clear the old confirmation") { state.get().message == SessionPublisher.State().message }
            assertNull(SessionFiles.activeSessionId(context))
            assertEquals("Disposable status test", File(session, "sample.txt").readText())
            assertFalse(PublicSessionStorage.needsSave(context, PublicSessionStorage.snapshot(session)))
        } finally { stop(); restoreAndClean(previous, listOf(session)) }
    }

    @Test fun anEarlierSaveFinishingAfterNewSessionCannotRestoreItsPositionMessage() {
        assumeTrue(PublicSessionStorage.configured(context))
        val previous = prefs.getString("active_research_session", null)
        val old = fixture()
        val next = fixture()
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val state = AtomicReference(SessionPublisher.State())
        val stop = SessionPublisher.observe { state.set(it); messages.add(it.message) }
        try {
            prefs.edit().putString("active_research_session", old.name).commit()
            SessionFiles.activeCapture = File(context.cacheDir, "simulated_capture_${UUID.randomUUID()}")
            enqueue(old, 2) // Publication waits for the simulated capture; New Session must not cancel it.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SessionFiles.newSession(context)
                messages.clear()
            }
            prefs.edit().putString("active_research_session", next.name).commit()
            enqueue(next, 3)
            SessionFiles.activeCapture = null
            await("The new session should still report its own successful save") {
                state.get().message.startsWith("Position 3 saved")
            }
            assertFalse(PublicSessionStorage.needsSave(context, PublicSessionStorage.snapshot(old)))
            assertFalse(PublicSessionStorage.needsSave(context, PublicSessionStorage.snapshot(next)))
            assertFalse("Old progress/completion must never reappear: $messages",
                messages.any { it.contains("position 2", ignoreCase = true) })
            assertEquals("Disposable status test", File(old, "sample.txt").readText())
        } finally { stop(); restoreAndClean(previous, listOf(old, next)) }
    }
}
