package com.developer27.qvlp.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.SystemClock
import com.developer27.qvlp.R
import com.developer27.qvlp.capture.BurstRequestTag
import kotlin.math.abs

/** Acquires locks once per camera opening and carries them across preview/record sessions. */
internal class FocusWhiteBalanceController(
    private val context: Context,
    private val capabilities: FocusWhiteBalanceCapabilities,
    private val profile: FocusWhiteBalanceProfile
) {
    data class Update(val repeat: Boolean = false, val triggerFocus: Boolean = false)
    private data class RequestTag(val owner: FocusWhiteBalanceController, val revision: Int)

    private var revision = 0
    private var focusTriggerSent = false
    private var focusTriggerFrame: Long? = null
    private var focusLocked = false
    private var lockedFocusDistance: Float? = null
    private var whiteBalanceLockRequested = false
    private var whiteBalanceLocked = false
    private var lockedGains: RggbChannelVector? = null
    private var lockedTransform: ColorSpaceTransform? = null
    private var sessionStartMs = 0L
    private var matchingFrames = 0
    private var mismatchingFrames = 0
    private var wasReady = false
    @Volatile var ready = false
        private set
    @Volatile var error: String? = null
        private set

    private val acquiring: Boolean get() = error == null &&
        ((profile.focusMode == FocusMode.LOCK && !focusLocked) ||
            (profile.whiteBalanceMode == FocusWhiteBalanceCapabilities.WB_LOCK && !whiteBalanceLocked))

    @Synchronized fun beginSession() {
        // Keep acquired values, but require the new session to acknowledge them.
        ready = false
        matchingFrames = 0
        mismatchingFrames = 0
        wasReady = false
        sessionStartMs = SystemClock.elapsedRealtime()
    }

    @Synchronized fun apply(builder: CaptureRequest.Builder) {
        builder.setTag(RequestTag(this, revision))
        // AF/AWB behavior with manual AE is device dependent. Meter only before capture,
        // then restore the user's manual exposure once both requested locks are acquired.
        builder.set(CaptureRequest.CONTROL_AE_MODE, if (acquiring)
            CaptureRequest.CONTROL_AE_MODE_ON else CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        when (profile.focusMode) {
            FocusMode.LOCK -> {
                val distance = lockedFocusDistance
                builder.set(CaptureRequest.CONTROL_AF_MODE, if (distance != null)
                    CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_AUTO)
                if (distance != null) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            }
            FocusMode.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, profile.focusDistance)
            }
            FocusMode.AUTO -> builder.set(CaptureRequest.CONTROL_AF_MODE, capabilities.continuousFocusMode)
            FocusMode.FIXED -> if (capabilities.afOff)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            FocusMode.DEVICE -> Unit
        }
        when (profile.whiteBalanceMode) {
            FocusWhiteBalanceCapabilities.WB_LOCK -> {
                val gains = lockedGains
                val transform = lockedTransform
                if (gains != null && transform != null) {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                    builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
                } else {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLockRequested)
                }
            }
            FocusWhiteBalanceCapabilities.WB_DEVICE -> Unit
            else -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, profile.whiteBalanceMode)
                if (capabilities.whiteBalanceLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
        }
    }

    @Synchronized fun onResult(result: TotalCaptureResult): Update {
        val requestTag = result.request.tag
        val tag = ((requestTag as? BurstRequestTag)?.controlsTag ?: requestTag) as? RequestTag ?: return Update()
        if (tag.owner !== this || error != null) return Update()
        if (result.request[CaptureRequest.CONTROL_AF_TRIGGER] == CaptureRequest.CONTROL_AF_TRIGGER_START) {
            focusTriggerFrame = result.frameNumber
        }
        // Older in-flight requests must never confirm a newer lock/configuration.
        if (tag.revision != revision) return Update()
        var changed = false
        var trigger = false
        if (profile.focusMode == FocusMode.LOCK && !focusLocked) {
            if (!focusTriggerSent) {
                focusTriggerSent = true
                trigger = true
            } else if (focusTriggerFrame?.let { result.frameNumber >= it } == true) {
                when (result[CaptureResult.CONTROL_AF_STATE]) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                        focusLocked = true
                        if (capabilities.manualFocus) {
                            lockedFocusDistance = result[CaptureResult.LENS_FOCUS_DISTANCE]
                                ?.takeIf { it.isFinite() && it in 0f..capabilities.maxFocusDistance }
                        }
                        changed = true
                    }
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        fail(context.getString(R.string.focus_lock_failed))
                        changed = true
                    }
                }
            }
        }
        if (profile.whiteBalanceMode == FocusWhiteBalanceCapabilities.WB_LOCK && !whiteBalanceLocked) {
            if (!whiteBalanceLockRequested &&
                result[CaptureResult.CONTROL_AWB_STATE] == CaptureResult.CONTROL_AWB_STATE_CONVERGED) {
                whiteBalanceLockRequested = true
                changed = true
            } else if (whiteBalanceLockRequested &&
                result[CaptureResult.CONTROL_AWB_LOCK] == true &&
                result[CaptureResult.CONTROL_AWB_STATE] == CaptureResult.CONTROL_AWB_STATE_LOCKED) {
                whiteBalanceLocked = true
                if (capabilities.manualWhiteBalance) {
                    val gains = result[CaptureResult.COLOR_CORRECTION_GAINS]
                    val transform = result[CaptureResult.COLOR_CORRECTION_TRANSFORM]
                    if (gains != null && transform != null &&
                        (0..3).all { gains.getComponent(it).isFinite() && gains.getComponent(it) > 0f } &&
                        (0..2).all { row -> (0..2).all { col -> transform.getElement(col, row).toFloat().isFinite() } }) {
                        lockedGains = gains
                        lockedTransform = transform
                    }
                }
                changed = true
            }
        }
        if (changed) {
            revision++
            matchingFrames = 0
            ready = false
            return Update(repeat = true, triggerFocus = trigger && error == null)
        }
        val matches = !acquiring && result[CaptureResult.CONTROL_AE_MODE] == CaptureResult.CONTROL_AE_MODE_OFF &&
            focusMatches(result) && whiteBalanceMatches(result)
        matchingFrames = if (matches) matchingFrames + 1 else 0
        mismatchingFrames = if (matches) 0 else mismatchingFrames + 1
        ready = error == null && matchingFrames >= 2
        if (ready) wasReady = true
        if ((wasReady && mismatchingFrames >= 3) ||
            (!ready && SystemClock.elapsedRealtime() - sessionStartMs > SETTLE_TIMEOUT_MS)) {
            fail(context.getString(R.string.camera_locks_unconfirmed))
            revision++
            return Update(repeat = true)
        }
        return Update(triggerFocus = trigger)
    }

    private fun focusMatches(result: CaptureResult): Boolean = when (profile.focusMode) {
        FocusMode.LOCK -> lockedFocusDistance?.let { distanceMatches(result, it) } ?: (
            focusLocked && result[CaptureResult.CONTROL_AF_MODE] == CaptureResult.CONTROL_AF_MODE_AUTO &&
                result[CaptureResult.CONTROL_AF_STATE] == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
        FocusMode.MANUAL -> distanceMatches(result, profile.focusDistance)
        FocusMode.AUTO -> result[CaptureResult.CONTROL_AF_MODE] == capabilities.continuousFocusMode
        FocusMode.FIXED -> !capabilities.afOff || result[CaptureResult.CONTROL_AF_MODE] == CaptureResult.CONTROL_AF_MODE_OFF
        FocusMode.DEVICE -> true
    }

    private fun distanceMatches(result: CaptureResult, distance: Float): Boolean {
        val actual = result[CaptureResult.LENS_FOCUS_DISTANCE] ?: return false
        return result[CaptureResult.CONTROL_AF_MODE] == CaptureResult.CONTROL_AF_MODE_OFF &&
            result[CaptureResult.LENS_STATE] == CaptureResult.LENS_STATE_STATIONARY &&
            abs(actual - distance) <= maxOf(0.01f, capabilities.maxFocusDistance * 0.01f)
    }

    private fun whiteBalanceMatches(result: CaptureResult): Boolean {
        if (profile.whiteBalanceMode == FocusWhiteBalanceCapabilities.WB_DEVICE) return true
        if (profile.whiteBalanceMode != FocusWhiteBalanceCapabilities.WB_LOCK)
            return result[CaptureResult.CONTROL_AWB_MODE] == profile.whiteBalanceMode
        val gains = lockedGains
        val transform = lockedTransform
        if (gains == null || transform == null) return whiteBalanceLocked &&
            result[CaptureResult.CONTROL_AWB_MODE] == CaptureResult.CONTROL_AWB_MODE_AUTO &&
            result[CaptureResult.CONTROL_AWB_LOCK] == true &&
            result[CaptureResult.CONTROL_AWB_STATE] == CaptureResult.CONTROL_AWB_STATE_LOCKED
        val actualGains = result[CaptureResult.COLOR_CORRECTION_GAINS] ?: return false
        val actualTransform = result[CaptureResult.COLOR_CORRECTION_TRANSFORM] ?: return false
        return result[CaptureResult.CONTROL_AWB_MODE] == CaptureResult.CONTROL_AWB_MODE_OFF &&
            result[CaptureResult.COLOR_CORRECTION_MODE] == CaptureResult.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX &&
            (0..3).all { abs(actualGains.getComponent(it) - gains.getComponent(it)) < 0.02f } &&
            (0..2).all { row -> (0..2).all { col ->
                abs(actualTransform.getElement(col, row).toFloat() - transform.getElement(col, row).toFloat()) < 0.02f
            } }
    }

    @Synchronized fun fail(message: String) {
        error = message
        ready = false
    }

    @Synchronized fun status(): String {
        error?.let { return it }
        if (!ready) return context.getString(if (acquiring) R.string.camera_locks_acquiring else R.string.camera_locks_confirming)
        val focus = if (profile.focusMode == FocusMode.LOCK) context.getString(R.string.lock_confirmed)
            else context.getString(profile.focusMode.label)
        val wb = if (profile.whiteBalanceMode == FocusWhiteBalanceCapabilities.WB_LOCK) context.getString(R.string.lock_confirmed)
            else context.getString(capabilities.whiteBalanceOptions.first { it.mode == profile.whiteBalanceMode }.label)
        return context.getString(R.string.camera_locks_status, focus, wb)
    }

    companion object {
        const val SETTLE_TIMEOUT_MS = 10_000L
    }
}
