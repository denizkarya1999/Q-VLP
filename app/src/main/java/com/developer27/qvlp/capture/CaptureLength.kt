package com.developer27.qvlp.capture

/** How many frames one capture records. Immutable, and snapshotted before the countdown starts. */
internal data class CaptureLength(val frames: Int = DEFAULT_FRAMES) {
    init { require(frames in RANGE) }

    fun timeoutMillis(frameDurationNs: Long): Long {
        require(frameDurationNs > 0)
        return maxOf(12_000L, frames * frameDurationNs / 1_000_000L * 3 + 5_000L)
    }

    companion object {
        const val DEFAULT_FRAMES = 30
        const val COUNTDOWN_SECONDS = 3
        val RANGE = 1..1000
        fun parse(raw: String?): CaptureLength? =
            raw?.trim()?.toIntOrNull()?.takeIf { it in RANGE }?.let { CaptureLength(it) }
    }
}

/** Camera-thread admission: counts started exposures and closes exactly at the requested number. */
internal class CaptureWindow(private val length: CaptureLength) {
    private val ids = mutableMapOf<Long, Int>()
    var complete = false
        private set

    fun start(frameNumber: Long): Int? {
        ids[frameNumber]?.let { return it }
        if (complete) return null
        val id = ids.size
        ids[frameNumber] = id
        if (length.frames == ids.size) complete = true
        return id
    }

    fun frameId(frameNumber: Long): Int? = ids[frameNumber]
}
