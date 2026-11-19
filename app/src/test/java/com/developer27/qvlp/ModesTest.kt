package com.developer27.qvlp

import org.junit.Assert.assertEquals
import org.junit.Test

class ModesTest {
    @Test fun aNewInstallationAndAFactoryResetStartInInferenceMode() {
        // Nothing is stored on a new installation, and a factory reset removes what was stored.
        assertEquals(Modes.INFERENCE, Modes.chosen(null))
    }

    @Test fun theModeTheOperatorChoseIsKept() {
        assertEquals(Modes.DATA_COLLECTION, Modes.chosen(Modes.DATA_COLLECTION))
        assertEquals(Modes.INFERENCE, Modes.chosen(Modes.INFERENCE))
    }

    @Test fun aStoredValueThatIsNoModeCountsAsNoChoice() {
        assertEquals(Modes.DEFAULT, Modes.chosen("something_else"))
    }
}
