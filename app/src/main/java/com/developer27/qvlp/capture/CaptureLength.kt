package com.developer27.qvlp.capture

internal enum class CaptureLengthMode(val key: String) { FRAMES("frames"), DURATION("duration") }

/** Immutable selection, snapshotted before the countdown starts. */
internal data class CaptureLength(val mode: CaptureLengthMode = CaptureLengthMode.FRAMES, val value: Int = DEFAULT_FRAMES) {
    init { require(value in range(mode)) }
    val frameCount: Int? get() = value.takeIf { mode == CaptureLengthMode.FRAMES }
    val durationSeconds: Int? get() = value.takeIf { mode == CaptureLengthMode.DURATION }
    val durationNs: Long? get() = durationSeconds?.times(1_000_000_000L)

    fun estimatedFrames(frameDurationNs: Long): Long {
        require(frameDurationNs > 0)
        return frameCount?.toLong() ?: ((requireNotNull(durationNs) + frameDurationNs - 1) / frameDurationNs)
    }

    fun timeoutMillis(frameDurationNs: Long): Long {
        require(frameDurationNs > 0)
        val expectedNs = durationNs ?: (requireNotNull(frameCount) * frameDurationNs)
        return maxOf(12_000L, expectedNs / 1_000_000L * 3 + 5_000L)
    }

    companion object {
        const val DEFAULT_FRAMES = 30
        const val DEFAULT_SECONDS = 5
        const val COUNTDOWN_SECONDS = 3
        fun range(mode: CaptureLengthMode) = if (mode == CaptureLengthMode.FRAMES) 1..1000 else 1..60
        fun parse(mode: CaptureLengthMode, raw: String?): CaptureLength? =
            raw?.trim()?.toIntOrNull()?.takeIf { it in range(mode) }?.let { CaptureLength(mode, it) }
    }
}

/** Camera-thread admission: duration uses sensor time, never an assumed frame rate. */
internal class CaptureWindow(private val length: CaptureLength) {
    private val ids = mutableMapOf<Long, Int>()
    var firstTimestampNs: Long? = null
        private set
    var complete = false
        private set

    fun start(frameNumber: Long, timestampNs: Long): Int? {
        ids[frameNumber]?.let { return it }
        if (complete) return null
        val first = firstTimestampNs ?: timestampNs.also { firstTimestampNs = it }
        val duration = length.durationNs
        if (duration != null && timestampNs - first >= duration) {
            complete = true
            return null
        }
        val id = ids.size
        ids[frameNumber] = id
        if (length.frameCount == ids.size) complete = true
        return id
    }

    fun frameId(frameNumber: Long): Int? = ids[frameNumber]
}
