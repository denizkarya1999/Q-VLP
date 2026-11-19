package com.developer27.qvlp.capture

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCheckTest {
    /** The record of one capture as BurstWriter writes it, with [frames] of [asked] frames saved. */
    private fun burst(position: Int, finished: String, state: String = "complete", asked: Int = 50, frames: Int = asked,
                      pictures: Int? = frames, lostFile: Boolean = false) = JSONObject()
        .put("state", state).put("position", JSONObject().put("position_id", position)).put("finished_at", finished)
        .put("requested", JSONObject().put("frames", asked))
        .put("frames", JSONArray((0 until frames).map { id ->
            JSONObject().put("frame_id", id).put("file", if (lostFile && id == 0) JSONObject.NULL else SessionLayout.frameName(id))
        }))
        .put("missing_frame_ids", JSONArray((frames until asked).toList()))
        .apply { if (pictures != null) put("pictures_saved", pictures) }

    @Test fun aCaptureIsCompleteWhenItFinishedWithEveryFrameAndEveryPicture() {
        assertTrue(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z")))
        // Cut short, a frame the camera never delivered, a frame whose pictures could not be written.
        assertFalse(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", state = "interrupted")))
        assertFalse(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", state = "capturing")))
        assertFalse(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", frames = 49)))
        assertFalse(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", pictures = 49)))
        assertFalse(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", pictures = 0)))
        assertFalse(SessionCheck.complete(JSONObject().put("state", "complete")))
        assertFalse(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", asked = 0, frames = 0)))
    }

    @Test fun aRecordOfAnEarlierVersionIsReadByItsFileNames() {
        assertTrue(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", pictures = null)))
        assertFalse(SessionCheck.complete(burst(1, "2026-11-19T17:00:00Z", pictures = null, lostFile = true)))
    }

    @Test fun fiftyPositionsCapturedOnceEachAreFiftyCompletePositions() {
        val session = (1..50).map { burst(it, "2026-11-19T17:%02d:00Z".format(it)) }
        val report = SessionCheck.report(session.shuffled(java.util.Random(7)), saved = true)
        assertEquals((1..50).toList(), report.complete)
        assertTrue(report.incomplete.isEmpty())
        assertTrue(report.saved)
    }

    @Test fun aPositionIsJudgedByTheCaptureFinalCsvTakes() {
        val report = SessionCheck.report(listOf(
            burst(1, "2026-11-19T17:01:00Z"),
            // Only ever interrupted: missing.
            burst(2, "2026-11-19T17:02:00Z", state = "interrupted", frames = 12),
            // Interrupted, then captured again: there.
            burst(3, "2026-11-19T17:03:00Z", state = "interrupted", frames = 12),
            burst(3, "2026-11-19T17:04:00Z"),
            // Captured, then again with frames missing: final.csv takes the later one, so it is not complete.
            burst(4, "2026-11-19T17:05:00Z"),
            burst(4, "2026-11-19T17:06:00.250Z", frames = 47),
            // Captured, then a second capture cut short: the first one still counts.
            burst(5, "2026-11-19T17:07:00Z"),
            burst(5, "2026-11-19T17:08:00Z", state = "interrupted", frames = 3),
            // The app was closed while its LEDs were being found.
            burst(6, "", state = "capturing", pictures = null),
            // A record that names no position is no position.
            JSONObject().put("state", "complete")))
        assertEquals(listOf(1, 3, 5), report.complete)
        assertEquals(listOf(2, 4, 6), report.incomplete)
        assertFalse(report.saved)
    }

    @Test fun aCaptureSummaryIsCompleteOnlyWithEveryPicture() {
        val length = CaptureLength(50)
        fun summary(saved: Int, pictures: Int, interrupted: Boolean) =
            BurstSummary("session_1", 7, saved, length, 30.0, emptyList(), interrupted, null, pictures)
        assertTrue(summary(50, 50, false).complete)
        assertFalse(summary(49, 49, false).complete)
        assertFalse(summary(50, 48, false).complete)
        assertFalse(summary(50, 50, true).complete)
    }
}
