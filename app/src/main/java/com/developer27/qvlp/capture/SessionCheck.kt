package com.developer27.qvlp.capture

import org.json.JSONObject
import java.time.Instant

/**
 * Says whether the positions of a session are all there, read from each capture's record (burst.json).
 *
 * A capture is complete when it finished and every frame that was asked for has its pictures. A position is
 * complete when the capture final.csv takes for it is complete: the latest one that finished. So a position
 * captured again after a bad capture is complete, and one whose only capture was interrupted is not.
 */
internal object SessionCheck {
    /**
     * [complete] and [incomplete] hold position IDs in rising order. [saved] is true when every file of the
     * session is in Pictures/Q-VLP as well as in the app.
     */
    data class Report(val complete: List<Int>, val incomplete: List<Int>, val saved: Boolean = false)

    /** Whether the capture this record belongs to finished with all of its frames. */
    fun complete(burst: JSONObject): Boolean {
        if (burst.optString("state") != "complete") return false
        val frames = burst.optJSONArray("frames") ?: return false
        if (frames.length() == 0) return false
        val asked = burst.optJSONObject("requested")?.optInt("frames", 0) ?: 0
        if (asked > 0 && frames.length() != asked) return false
        if ((burst.optJSONArray("missing_frame_ids")?.length() ?: 0) > 0) return false
        // Since version 1.6.0 the record counts the frames whose pictures were all written.
        if (burst.has("pictures_saved")) return burst.optInt("pictures_saved") == frames.length()
        // Before that, a frame whose pictures could not be written lost its file name.
        return (0 until frames.length()).all { !frames.getJSONObject(it).isNull("file") }
    }

    /** [bursts] holds the record of every capture of one session, in any order. */
    fun report(bursts: List<JSONObject>, saved: Boolean = false): Report {
        val positions = bursts.mapNotNull { burst ->
            burst.optJSONObject("position")?.takeIf { it.has("position_id") }?.optInt("position_id")?.let { it to burst }
        }.groupBy({ it.first }, { it.second }).toSortedMap()
        val complete = ArrayList<Int>()
        val incomplete = ArrayList<Int>()
        positions.forEach { (position, captures) ->
            val taken = captures.filter { it.optString("state") == "complete" }.maxByOrNull { finished(it) }
            if (taken != null && complete(taken)) complete.add(position) else incomplete.add(position)
        }
        return Report(complete, incomplete, saved)
    }

    private fun finished(burst: JSONObject): Instant =
        runCatching { Instant.parse(burst.optString("finished_at")) }.getOrDefault(Instant.EPOCH)
}
