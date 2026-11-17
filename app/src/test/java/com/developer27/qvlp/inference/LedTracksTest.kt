package com.developer27.qvlp.inference

import org.junit.Assert.*
import org.junit.Test

class LedTracksTest {
    /** An LED 40 pixels wide and high that the detection model is [detected] sure of. */
    private fun box(x: Float, y: Float, detected: Float = 0.9f) = LedDetection(LedDetection.UNNAMED, detected, x - 20f, y - 20f, x + 20f, y + 20f)

    /**
     * Stands in for the camera and the recognition model. A crop's first byte says which light it shows (its
     * x position in hundreds of pixels) and its second byte the frame it was cut from; the model names the
     * light by that first byte and is 0.9 sure.
     */
    private class Rig {
        val tracks = LedTracks()
        var frame = 0
        val runs = ArrayList<List<List<ByteArray>>>()

        fun next(vararg found: LedDetection): List<LedDetection> {
            frame++
            return tracks.update(found.toList(),
                { led -> ByteArray(LedCrop.BYTES).also { it[0] = ((led.left + led.right) / 200f).toInt().toByte(); it[1] = frame.toByte() } },
                { sequences ->
                    runs.add(sequences)
                    sequences.map { crops -> FloatArray(4) { if (it == crops.last()[0] - 1) 0.9f else 0.03f } }
                })
        }
        /** The frames each crop sequence of run [run] was cut from. */
        fun framesOf(run: Int) = runs[run].map { crops -> crops.map { it[1].toInt() } }
    }

    @Test fun anLedIsNamedFromItsFirstFrame() {
        val rig = Rig()
        val found = rig.next(box(300f, 100f, 0.8f), box(100f, 100f, 0.7f)).also { assertEquals(2, it.size) }
        // LED 1 to LED 4 in that order, each with the recognition model's probability and the detection model's confidence.
        assertEquals(listOf(1, 3), found.map { it.led })
        assertEquals(listOf(0.9f, 0.9f), found.map { it.confidence })
        assertEquals(listOf(0.7f, 0.8f), found.map { it.detected })
        assertEquals(100f, (found[0].left + found[0].right) / 2f, 0f)
        // One run of the model for both LEDs, each seen in one frame so far.
        assertEquals(listOf(listOf(listOf(1), listOf(1))), listOf(rig.framesOf(0)))
    }

    @Test fun anLedIsNamedAgainFromEveryFiveNewFrames() {
        val rig = Rig()
        repeat(11) { index ->
            // The box moves a little from frame to frame, as a detection does.
            val found = rig.next(box(200f + index % 3, 100f))
            assertEquals("frame ${index + 1}", listOf(2), found.map { it.led })
        }
        // Named at its first frame, then from frames 1 to 5 and from frames 6 to 10.
        assertEquals(3, rig.runs.size)
        assertEquals(listOf(listOf(1)), rig.framesOf(0))
        assertEquals(listOf(listOf(1, 2, 3, 4, 5)), rig.framesOf(1))
        assertEquals(listOf(listOf(6, 7, 8, 9, 10)), rig.framesOf(2))
    }

    @Test fun anLedThatMovesAwayIsANewLed() {
        val rig = Rig()
        rig.next(box(200f, 100f))
        rig.next(box(200f, 100f))
        // The boxes no longer overlap: this light has no earlier frames.
        assertEquals(listOf(4), rig.next(box(400f, 300f)).map { it.led })
        assertEquals(listOf(listOf(3)), rig.framesOf(1))
    }

    @Test fun anLedMissedInAFewFramesKeepsItsFrames() {
        val rig = Rig()
        repeat(3) { rig.next(box(200f, 100f)) }
        repeat(2) { assertTrue(rig.next().isEmpty()) }
        rig.next(box(200f, 100f))
        rig.next(box(200f, 100f))
        // Its fifth frame is frame 7: the sequence is the five frames it was seen in.
        assertEquals(listOf(listOf(1, 2, 3, 6, 7)), rig.framesOf(1))
    }

    @Test fun anLedThatStaysAwayIsForgotten() {
        val rig = Rig()
        repeat(2) { rig.next(box(200f, 100f)) }
        repeat(LedTracks.MAX_MISSED + 1) { rig.next() }
        rig.next(box(200f, 100f))
        assertEquals(listOf(listOf(9)), rig.framesOf(1))
    }

    @Test fun noTwoLedsGetTheSameName() {
        val boxes = listOf(box(100f, 100f, 0.6f), box(500f, 100f, 0.8f), box(900f, 100f, 0.7f))
        val named = LedTracks.name(boxes, listOf(
            floatArrayOf(0.05f, 0.7f, 0.2f, 0.05f), floatArrayOf(0.02f, 0.95f, 0.02f, 0.01f), floatArrayOf(0.9f, 0.05f, 0.03f, 0.02f)))
        // The light the model is surest of keeps LED 2; the other one stays without a name.
        assertEquals(listOf(1, 2, LedDetection.UNNAMED), named.map { it.led })
        assertEquals(500f, (named[1].left + named[1].right) / 2f, 0f)
        assertEquals("LED 2 | 95%", named[1].label)
        assertEquals("LED Detected | 60%", named[2].label)
        assertEquals(0.6f, named[2].detected, 0f)
    }

    @Test fun anLedTheModelIsNotSureOfKeepsNoName() {
        val named = LedTracks.name(listOf(box(100f, 100f, 0.93f), box(500f, 100f, 0.5f), box(900f, 100f, 0.8f)),
            listOf(floatArrayOf(0.4f, 0.3f, 0.2f, 0.1f), floatArrayOf(0.1f, 0.1f, 0.3f, 0.5f), null))
        assertEquals(listOf(4, LedDetection.UNNAMED, LedDetection.UNNAMED), named.map { it.led })
        // Without a name the number shown is the detection model's, and the surest detection comes first.
        assertEquals(listOf("LED 4 | 50%", "LED Detected | 93%", "LED Detected | 80%"), named.map { it.label })
    }
}
