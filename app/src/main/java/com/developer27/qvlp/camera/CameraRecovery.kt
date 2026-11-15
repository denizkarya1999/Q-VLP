package com.developer27.qvlp.camera

/**
 * What to do when the camera reports that it needs attention. The operator is not asked to fix it: first the
 * camera in use goes back to its default settings, then the app falls back to its default camera, and only a
 * problem that survives both is put in front of the operator.
 */
internal class CameraRecovery(private val patience: Int = 3) {
    enum class Step { NONE, RESET_SETTINGS, USE_DEFAULT_CAMERA, SHOW }

    private var tried = 0
    private var reports = 0

    /** The camera works, or the operator changed something: the next problem starts from the first step again. */
    fun restart() {
        tried = 0
        reports = 0
    }

    /**
     * A [certain] problem (a setting outside what capture allows, a camera that failed to open) is acted on at
     * once. Any other must be reported [patience] times in a row, because a camera that is still settling
     * reports such problems for a moment.
     */
    fun report(problem: Boolean, certain: Boolean, onDefaultCamera: Boolean): Step {
        if (!problem) {
            reports = 0
            return Step.NONE
        }
        reports += 1
        if (!certain && reports < patience) return Step.NONE
        if (tried >= 2 || (tried == 1 && onDefaultCamera)) return Step.SHOW
        reports = 0
        tried += 1
        return if (tried == 1) Step.RESET_SETTINGS else Step.USE_DEFAULT_CAMERA
    }
}
