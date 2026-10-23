package com.developer27.qvlp.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureTriggerTest {
    @Test fun heldAndDuplicatePressesActOnlyOnceUntilReleased() {
        val trigger = CaptureTrigger()
        assertTrue(trigger.press(1, 24, 0))
        assertFalse(trigger.press(1, 24, 0))
        assertFalse(trigger.press(1, 24, 1))
        assertFalse(trigger.press(1, 24, 20))
        trigger.release(1, 24)
        assertTrue(trigger.press(1, 24, 0))
    }
    @Test fun focusLossCannotTurnAnOngoingLongPressIntoANewCapture() {
        val trigger = CaptureTrigger()
        assertTrue(trigger.press(1, 24, 0))
        trigger.reset()
        assertFalse(trigger.press(1, 24, 4))
        trigger.release(1, 24)
        assertTrue(trigger.press(1, 24, 0))
    }
    @Test fun separateKeysAndDevicesHaveIndependentReleaseTracking() {
        val trigger = CaptureTrigger()
        assertTrue(trigger.press(1, 24, 0))
        assertTrue(trigger.press(2, 24, 0))
        trigger.release(1, 24)
        assertFalse(trigger.press(2, 24, 1))
        assertTrue(trigger.press(1, 25, 0))
    }
}
