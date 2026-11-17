package com.developer27.qvlp

import org.junit.Assert.assertEquals
import org.junit.Test

class FactoryResetTest {
    @Test fun removesEverySettingOfEveryCamera() {
        val stored = setOf("camera_0_exposure_ns", "camera_0_iso", "camera_0_fps_rate", "camera_0_zoom", "camera_0_physical_id",
            "camera_1_exposure_ns", "camera_1_iso", "camera_1_fps_rate", "camera_1_zoom", "capture_camera", "capture_frame_count", "app_mode",
            "enable_cv_algorithms", "generate_post_inference_files", "session_mode", "position_id", "last_capture_position")
        assertEquals(stored, FactoryReset.keys(stored))
    }

    @Test fun keepsWhatIsNotASetting() {
        // The session being recorded, the one-time settings import and unrelated values stay.
        val kept = setOf("active_research_session", "camera_settings_migrated", "camera_0_resolution_note", "something_else")
        assertEquals(emptySet<String>(), FactoryReset.keys(kept))
        assertEquals(setOf("camera_1_iso"), FactoryReset.keys(kept + "camera_1_iso"))
    }
}
