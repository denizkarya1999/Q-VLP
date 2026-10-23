package com.developer27.qvlp.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SessionCsvTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun session(parent: File = context.cacheDir): File = File(parent, "session_${UUID.randomUUID()}").apply {
        mkdirs()
        SessionFiles.writeJson(File(this, "session.json"), JSONObject().put("survey", JSONObject().put("positions",
            JSONArray().put(JSONObject().put("position_id", 2)).put(JSONObject().put("position_id", 1)))))
    }
    private fun attempt(session: File, name: String, position: Int, second: Int, state: String = "complete", frames: Int = 2): File =
        File(session, "position_$name").apply {
            mkdirs()
            SessionFiles.writeJson(File(this, "burst.json"), JSONObject().put("position", JSONObject().put("position_id", position))
                .put("state", state).put("frames_saved", frames).put("finished_at", "2026-10-23T12:00:${second.toString().padStart(2, '0')}Z"))
            File(this, "frames.csv").writeText(SessionCsv.header + "\n" + (0 until frames).joinToString("") { index ->
                MutableList(SessionCsv.columns.size) { "" }.apply {
                    this[0] = session.name; this[1] = position.toString(); this[2] = "0.3"; this[3] = "0.6"
                    this[4] = (index * 2).toString(); this[5] = (second * 1_000_000_000L + index * 33_340_371L).toString()
                }.joinToString(",") + "\n"
            })
            File(this, "frame_00000.yuv").writeBytes(byteArrayOf(1, 2, 3))
        }

    @Test fun combinesPositionsInSurveyOrderAndSelectsLatestCompletedRepeat() {
        val session = session()
        try {
            val first = attempt(session, "first", 1, 1)
            val original = File(first, "frames.csv").readBytes()
            val other = attempt(session, "other", 2, 2)
            val repeat = attempt(session, "repeat", 1, 3, frames = 3)
            attempt(session, "cancelled", 1, 4, "interrupted")
            SessionCsv.update(session)
            assertEquals(listOf(SessionCsv.header) + File(other, "frames.csv").readLines().drop(1) +
                File(repeat, "frames.csv").readLines().drop(1), File(session, "frames.csv").readLines())
            assertEquals(44, File(session, "frames.csv").readLines().first().split(',').size)
            assertArrayEquals(original, File(first, "frames.csv").readBytes())
            val index = SessionFiles.readJson(File(session, "session.json")).getJSONObject("frame_export")
            assertEquals("latest_completed_attempt_per_position", index.getString("repeat_policy"))
            assertEquals(repeat.name, index.getJSONArray("selected_attempts").getJSONObject(1).getString("attempt"))
            assertEquals(2, index.getJSONArray("excluded_attempts").length())
        } finally { session.deleteRecursively() }
    }

    @Test fun interruptedOnlyPositionsKeepOriginalsButDoNotInventSessionRows() {
        val session = session()
        try {
            val interrupted = attempt(session, "interrupted", 1, 1, "interrupted")
            SessionCsv.update(session)
            assertEquals(listOf(SessionCsv.header), File(session, "frames.csv").readLines())
            assertTrue(File(interrupted, "frame_00000.yuv").isFile)
        } finally { session.deleteRecursively() }
    }

    @Test fun malformedLatestAttemptLeavesPreviousIndexAndMetadataIntact() {
        val session = session()
        try {
            attempt(session, "first", 1, 1)
            SessionCsv.update(session)
            val csv = File(session, "frames.csv").readBytes()
            val metadata = File(session, "session.json").readBytes()
            val bad = attempt(session, "bad", 1, 2)
            File(bad, "frames.csv").appendText("broken,row\n")
            assertTrue(runCatching { SessionCsv.update(session) }.isFailure)
            assertArrayEquals(csv, File(session, "frames.csv").readBytes())
            assertArrayEquals(metadata, File(session, "session.json").readBytes())
        } finally { session.deleteRecursively() }
    }

    @Test fun unchangedIndexIsNotRewrittenAndCorruptionIsRepaired() {
        val session = session()
        try {
            attempt(session, "first", 1, 1)
            SessionCsv.update(session)
            val csv = File(session, "frames.csv")
            val metadata = File(session, "session.json")
            val expected = csv.readBytes()
            val times = csv.lastModified() to metadata.lastModified()
            SessionCsv.update(session)
            assertEquals(times, csv.lastModified() to metadata.lastModified())
            csv.writeText("interrupted or modified index")
            SessionCsv.update(session)
            assertArrayEquals(expected, csv.readBytes())
        } finally { session.deleteRecursively() }
    }

    @Test fun archiveBackfillsExistingSessionsAndIncludesOriginalRepeats() {
        val parent = File(context.filesDir, "sessions").apply { mkdirs() }
        val session = session(parent)
        var archive: File? = null
        try {
            val old = attempt(session, "old", 1, 1)
            val latest = attempt(session, "latest", 1, 2)
            archive = SessionFiles.archive(context, session.name)
            ZipFile(archive).use { zip ->
                val csv = zip.getInputStream(zip.getEntry("${session.name}/frames.csv")).bufferedReader().readText()
                assertEquals(File(latest, "frames.csv").readText(), csv)
                assertNotNull(zip.getEntry("${session.name}/${old.name}/frame_00000.yuv"))
                assertNotNull(zip.getEntry("${session.name}/${latest.name}/frame_00000.yuv"))
                assertNotNull(zip.getEntry("${session.name}/session.json"))
            }
        } finally { archive?.delete(); session.deleteRecursively() }
    }
}
