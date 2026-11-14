package com.developer27.qvlp.camera

import com.developer27.qvlp.camera.CameraChoice.Facing
import org.junit.Assert.*
import org.junit.Test

class CameraChoiceTest {
    // Typical phone order: rear first.
    private val phone = linkedMapOf("0" to Facing.REAR, "1" to Facing.FRONT, "2" to Facing.REAR, "3" to Facing.FRONT)

    @Test fun opensTheFrontCameraByDefault() {
        assertEquals("1", CameraChoice.initial(phone, null))
    }

    @Test fun keepsTheOperatorsChoiceWhileThatCameraExists() {
        assertEquals("0", CameraChoice.initial(phone, "0"))
        assertEquals("1", CameraChoice.initial(phone, "removed"))
    }

    @Test fun fallsBackToRearThenAnyCameraWithoutAFrontCamera() {
        assertEquals("0", CameraChoice.initial(linkedMapOf("9" to Facing.OTHER, "0" to Facing.REAR), null))
        assertEquals("9", CameraChoice.initial(linkedMapOf("9" to Facing.OTHER), null))
        assertNull(CameraChoice.initial(emptyMap(), null))
    }

    @Test fun switchesBetweenFrontAndRear() {
        assertEquals("0", CameraChoice.opposite(phone, "1"))
        assertEquals("1", CameraChoice.opposite(phone, "0"))
        assertEquals("1", CameraChoice.opposite(phone, "2"))
    }

    @Test fun hasNothingToSwitchToWithOneFacing() {
        assertNull(CameraChoice.opposite(linkedMapOf("0" to Facing.REAR), "0"))
        assertNull(CameraChoice.opposite(linkedMapOf("1" to Facing.FRONT, "3" to Facing.FRONT), "1"))
    }
}
