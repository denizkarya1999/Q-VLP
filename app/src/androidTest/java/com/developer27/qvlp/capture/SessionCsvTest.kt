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

    /** One capture and its processed folder: LED 1 is seen at [u] in every frame. */
    private fun attempt(session: File, name: String, position: Int, second: Int, state: String = "complete",
                        frames: Int = 2, u: Double = 100.0): File = File(session, "position_${position}_$name").apply {
        File(this, SessionLayout.FRAMES).mkdirs()
        SessionFiles.writeJson(File(this, "burst.json"), JSONObject().put("position", JSONObject().put("position_id", position))
            .put("state", state).put("frames_saved", frames).put("finished_at", "2026-10-23T12:00:${second.toString().padStart(2, '0')}Z"))
        File(File(this, SessionLayout.FRAMES), SessionLayout.frameName(0)).writeBytes(byteArrayOf(1, 2, 3))
        File(this, SessionLayout.MAPPING).writeText(SessionLayout.mapping((0 until frames).toList()))
        val processed = SessionLayout.processedFolder(this)
        val metadata = File(processed, SessionLayout.PROCESSED_METADATA).apply { mkdirs() }
        repeat(frames) { index ->
            File(metadata, SessionLayout.metadataName(index)).writeText(JSONObject().put("frame_id", index)
                .put("LED_1_u_px", u + index).put("LED_1_v_px", 50.0).put("LED_1_detected", 1)
                .put("LED_2_detected", 0).put("LED_3_detected", 0).put("LED_4_detected", 0).toString())
        }
        File(processed, SessionLayout.PROCESSED_MAPPING).writeText(SessionLayout.mapping((0 until frames).toList()))
    }

    @Test fun listsPositionsInSurveyOrderAndALaterCaptureReplacesAnEarlierOne() {
        val session = session()
        try {
            val first = attempt(session, "first", 1, 1, u = 100.0)
            val original = File(first, SessionLayout.MAPPING).readBytes()
            attempt(session, "other", 2, 2, u = 300.0)
            val repeat = attempt(session, "repeat", 1, 3, frames = 3, u = 200.0)
            attempt(session, "cancelled", 1, 4, "interrupted", u = 900.0)
            SessionCsv.update(session)
            val final = File(session, SessionLayout.FINAL).readLines()
            assertEquals(SessionCsv.finalHeader, final.first())
            // One row per position, in survey order. Position 1 comes from its latest completed capture.
            assertEquals(listOf("2,300.500,50.000,1,,,0,,,0,,,0", "1,201.000,50.000,1,,,0,,,0,,,0"), final.drop(1))
            assertArrayEquals(original, File(first, SessionLayout.MAPPING).readBytes())
            val index = SessionFiles.readJson(File(session, "session.json")).getJSONObject("export")
            assertEquals("latest_completed_capture_per_position", index.getString("repeat_policy"))
            assertEquals(repeat.name, index.getJSONArray("selected_attempts").getJSONObject(1).getString("attempt"))
            assertEquals(2, index.getJSONArray("excluded_attempts").length())
        } finally { session.deleteRecursively() }
    }

    @Test fun interruptedOnlyPositionsKeepTheirFramesButGetNoRow() {
        val session = session()
        try {
            val interrupted = attempt(session, "interrupted", 1, 1, "interrupted")
            SessionCsv.update(session)
            assertEquals(listOf(SessionCsv.finalHeader), File(session, SessionLayout.FINAL).readLines())
            assertTrue(File(File(interrupted, SessionLayout.FRAMES), SessionLayout.frameName(0)).isFile)
        } finally { session.deleteRecursively() }
    }

    @Test fun aCaptureThatWasNotProcessedKeepsEmptyLedCells() {
        val session = session()
        try {
            val capture = attempt(session, "raw", 1, 1)
            SessionLayout.processedFolder(capture).deleteRecursively()
            SessionCsv.update(session)
            assertEquals(listOf(SessionCsv.finalHeader, "1,,,,,,,,,,,,"), File(session, SessionLayout.FINAL).readLines())
        } finally { session.deleteRecursively() }
    }

    @Test fun unchangedFileIsNotRewrittenAndCorruptionIsRepaired() {
        val session = session()
        try {
            attempt(session, "first", 1, 1)
            SessionCsv.update(session)
            val csv = File(session, SessionLayout.FINAL)
            val metadata = File(session, "session.json")
            val expected = csv.readBytes()
            val times = csv.lastModified() to metadata.lastModified()
            SessionCsv.update(session)
            assertEquals(times, csv.lastModified() to metadata.lastModified())
            csv.writeText("interrupted or modified file")
            SessionCsv.update(session)
            assertArrayEquals(expected, csv.readBytes())
            csv.delete()
            SessionCsv.update(session)
            assertArrayEquals(expected, csv.readBytes())
        } finally { session.deleteRecursively() }
    }

    @Test fun archiveHoldsTheSessionsFilesWithoutTheAppsBookkeeping() {
        val parent = File(context.filesDir, "sessions").apply { mkdirs() }
        val session = session(parent)
        var archive: File? = null
        try {
            val old = attempt(session, "old", 1, 1, u = 100.0)
            val latest = attempt(session, "latest", 1, 2, u = 200.0)
            File(File(latest, SessionLayout.FRAMES), "frame_00001.yuv.part").writeBytes(byteArrayOf(9))
            archive = SessionFiles.archive(context, session.name)
            ZipFile(archive).use { zip ->
                val csv = zip.getInputStream(zip.getEntry("${session.name}/${SessionLayout.FINAL}")).bufferedReader().readLines()
                assertEquals(listOf(SessionCsv.finalHeader, "1,200.500,50.000,1,,,0,,,0,,,0"), csv)
                for (capture in listOf(old, latest)) {
                    assertNotNull(zip.getEntry("${session.name}/${capture.name}/${SessionLayout.FRAMES}/${SessionLayout.frameName(0)}"))
                    assertNotNull(zip.getEntry("${session.name}/${capture.name}/${SessionLayout.MAPPING}"))
                    assertNotNull(zip.getEntry("${session.name}/${SessionLayout.processedFolder(capture).name}/${SessionLayout.PROCESSED_MAPPING}"))
                    assertNull(zip.getEntry("${session.name}/${capture.name}/burst.json"))
                }
                assertNull(zip.getEntry("${session.name}/session.json"))
                assertNull(zip.getEntry("${session.name}/${latest.name}/${SessionLayout.FRAMES}/frame_00001.yuv.part"))
            }
        } finally { archive?.delete(); session.deleteRecursively() }
    }
}
