package com.developer27.qvlp.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionTest {
    @Test fun aPositionIsOnlyItsId() {
        assertEquals("""{"position_id":7}""", Position(7).json().toString())
    }
}
