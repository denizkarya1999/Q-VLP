package com.developer27.qvlp.camera

import org.junit.Assert.*
import org.junit.Test

class PhysicalLensChoiceTest {
    private val main = PhysicalLensChoice.Geometry(5.43, 7.3984, 5.5552)
    private val wide = PhysicalLensChoice.Geometry(1.835, 4.71296, 3.4944)
    private val cropped = PhysicalLensChoice.Geometry(5.43, 3.6992, 2.7776)

    @Test fun choosesTheNativeOneTimesViewRegardlessOfEnumerationOrder() {
        for (candidates in listOf(linkedMapOf("3" to wide, "4" to cropped, "2" to main),
            linkedMapOf("2" to main, "3" to wide, "4" to cropped))) {
            assertEquals("2", PhysicalLensChoice.choose(main, candidates, null))
        }
    }
    @Test fun retainsTheSavedLensEvenWhenAnotherViewIsACloserMatch() {
        assertEquals("3", PhysicalLensChoice.choose(main, mapOf("2" to main, "3" to wide), "3"))
    }
    @Test fun refusesToReplaceAMissingSavedLens() {
        assertTrue(runCatching { PhysicalLensChoice.choose(main, mapOf("2" to main), "3") }.isFailure)
    }
    @Test fun refusesToGuessWhenGeometryIsMissingOrInvalid() {
        assertTrue(runCatching { PhysicalLensChoice.choose(null, mapOf("2" to main), null) }.isFailure)
        assertTrue(runCatching { PhysicalLensChoice.choose(main, mapOf("2" to null), null) }.isFailure)
        assertTrue(runCatching { PhysicalLensChoice.choose(main,
            mapOf("2" to main.copy(focalMm = Double.NaN)), null) }.isFailure)
    }
    @Test fun identicalViewsHaveADeterministicTieBreak() {
        assertEquals("a", PhysicalLensChoice.choose(main, linkedMapOf("b" to main, "a" to main), null))
    }
}
