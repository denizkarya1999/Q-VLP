package com.developer27.qvlp.camera

import kotlin.math.abs
import kotlin.math.ln

/** Choose the physical view closest to the logical camera's native 1x view once. */
internal object PhysicalLensChoice {
    data class Geometry(val focalMm: Double, val sensorWidthMm: Double, val sensorHeightMm: Double) {
        val valid: Boolean get() = listOf(focalMm, sensorWidthMm, sensorHeightMm).all { it.isFinite() && it > 0 }
    }

    fun choose(reference: Geometry?, candidates: Map<String, Geometry?>, savedId: String?): String {
        if (savedId != null) {
            require(savedId in candidates) { "The pinned rear lens is unavailable. Capture cannot switch to another lens." }
            return savedId
        }
        require(reference?.valid == true) { "Cannot identify the main rear lens from camera geometry." }
        val main = requireNotNull(reference)
        return candidates.filterValues { it?.valid == true }.minWithOrNull(
            compareBy<Map.Entry<String, Geometry?>> {
                val lens = requireNotNull(it.value)
                abs(ln((lens.sensorWidthMm / lens.focalMm) / (main.sensorWidthMm / main.focalMm))) +
                    abs(ln((lens.sensorHeightMm / lens.focalMm) / (main.sensorHeightMm / main.focalMm)))
            }.thenBy { it.key }
        )?.key ?: error("No identifiable physical rear lens is available for pinning.")
    }
}
