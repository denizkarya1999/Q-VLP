# Q-VLP — Quantum-based Location Estimation

Q-VLP is an Android research app for **Quantum-based Location Estimation** using visible-light positioning. The current implementation provides the camera capture foundation; computer vision and location-estimation algorithms are planned.

The app is derived from [RoTemplate](https://github.com/denizkarya1999/RoTemplate). Open this directory in Android Studio; `app` is the application module.

- App name: Q-VLP
- Application ID and namespace: `com.developer27.qvlp`
- Version: 1.0
- Release date: TBD
- Source template: [RoTemplate](https://github.com/denizkarya1999/RoTemplate)

**Topics:** `android` · `kotlin` · `camera2` · `visible-light-positioning` · `indoor-positioning` · `location-estimation` · `quantum-based-localization` · `manual-camera-controls`

## Authors

Thuy-Anh Nguyen, Shahed Alabdulrahman, Deniz Karya Acikbas, Christian Nwobu, Clark Friese, and Ethan Liu.

Professor: Xiao Zhang · [Trustworthy AIoT Lab](https://www.zhangxiao.me/) · University of Michigan-Dearborn.

## Using Q-VLP

1. Tap **Choose Position**. Import your surveyed `positions.csv`, or enter the floor mark ID and its measured X/Y coordinates. Coordinates are required before capture and are never estimated by the phone.
2. Open **Settings → Mounting Setup** once to record lens height, offsets and notes. Enter row time only if it is known for this camera and resolution. Keep the camera lens over the surveyed mark.
3. Align the ceiling LEDs in the preview and wait for **Camera ready**. Tap **Start Capture**, then step away. Choose **Settings → Recording → Capture Length**: **Number of frames** (1–1000, default 30) or **Duration** (1–60 whole seconds, initially 5). The app waits 3 seconds, captures the selected length, saves a private recovery copy, then beeps and vibrates. Public folder copying continues in the background; its status appears below Camera ready. Device sound/vibration settings apply.
4. Read the result on the same screen. Tap the result to open capture details and warnings. **Repeat** keeps the earlier attempt; **Next Position** follows the imported file order. Move the mount to that mark before starting again. Manual positions advance the ID and ask for new coordinates.
5. Once, open **Sessions → Save location → Choose folder** and select **Internal storage → Pictures** (or its **Q-VLP** folder), then **Use this folder → Allow**. Existing sessions are copied into **Pictures/Q-VLP**, and future captures save there automatically. The first capture also prompts for this setup if needed. Browse that folder in Files or over USB. **Share** and **Save ZIP** still export a compressed copy. **New Session** groups later captures separately; it does not delete previous data.

**Settings** shows the Fixed Camera Lens and contains Exposure Time, ISO Rate, Resolution, Preview Size, Target Frame Rate, Focus Mode, White Balance and Factory Reset. Only the selected values appear in the list; entry dialogs explain valid ranges. Mounting details have a separate page. Numeric entry works in light and dark mode. Tap the camera status for requested settings, reported measurements and lock status.

The original full-screen preview, centered title, top button row and bottom capture panel are restored. **Settings**, **Sessions** and **About** are across the top; **Position**, green **Start Capture**, and **Switch Camera** are at the bottom. **+ / −** on the right of the preview increase or decrease digital zoom, with the current magnification shown between them. After a capture, **Repeat** and **Next Position** replace Start Capture. Results open their details when tapped, so there is no separate details button. **Settings → How to Capture** opens the instructions. Legacy MP4 recording and audio/storage permissions remain removed. Switch Camera alternates between compatible front/rear cameras and resets the new camera to 1×; if the other camera lacks manual sensor/YUV support, an explanation is shown and the current camera stays selected. Zoom uses the camera’s supported digital crop range in 0.1× steps, from 1× to its maximum. Camera selection and zoom are remembered, and controls are disabled throughout countdown/capture. A change of camera or zoom starts a new session on the next capture; requested zoom and actual crop metadata are saved. The preview defaults to **Full screen (original)**, matching RoTemplate’s full-screen display mapping. **Settings → Preview Size** also offers **Fit entire frame**, **Medium**, and **Small**. Fit shows the whole sensor-native frame at its original proportions; Medium and Small use 75% and 50% of that fitted size, centered on a black background. The chosen display size is remembered across camera switches and app restarts. Factory Reset also restores the original full-screen preview size. Preview size only changes the on-screen view; it does not change camera zoom, frame resolution, row-time calibration or saved pixels. Screen orientation is held during a burst; raw frames are not rotated. Cancel or leaving the app preserves any frames already saved and marks the attempt as interrupted.

### Survey file

Use UTF-8 CSV with exactly these columns (column order may vary):

```csv
position_id,rx_x_m,rx_y_m
0,0.3,0.3
1,0.6,0.3
```

These are examples; use measurements from the real room. Decimal points, LF/CRLF, an optional UTF-8 BOM and quoted numeric cells are supported. IDs must be unique nonnegative integers up to 999999, and coordinates must be finite numbers. Files are limited to 1 MB / 10,000 positions. Invalid files leave the existing list untouched. Import preserves file order, so researchers can prepare a snake path. The last position shows **End of List**; this is not a claim that every position was captured.

A successful import selects the first position and starts a new session on the next capture. The app keeps the imported list locally. Session metadata includes the input file hash and surveyed positions; a normalized `positions.csv` is included in new sessions. Manual entries record their source separately. Existing attempts are never relabeled when the list changes.

## Camera and saved data

- Manual exposure and ISO use device-reported limits. Frame cadence is requested through `SENSOR_FRAME_DURATION`. Recommended settings request 100 µs, minimum ISO, 1280 × 960 at 30 fps and automatic focus/white-balance locking where supported; fallbacks use available capabilities. Settings remain adjustable for device setup. For the document's research protocol, use the rear camera at 1× and select exposure at most 100 µs, at least 30 fps, full sensor aspect ratio, and locked or fixed focus/white balance. Unlocked modes and slower profiles remain available for setup, but research capture is blocked until the required settings are selected and confirmed.
- Automatic focus/white-balance preparation briefly uses AE, freezes supported lens/color values or native locks, restores manual exposure, and requires matching camera results before capture. Returning from Settings reacquires the locks. Use manual focus and a fixed white-balance preset for values that must remain identical across camera reopenings.
- Requests disable electronic/optical stabilization and scene effects where exposed, apply the operator-selected digital crop (full sensor at 1×), disable distortion correction where supported, and disable rotate-and-crop where supported. Actual geometry-related result fields are logged; unavailable results stay unknown. Both preview and YUV outputs are pinned to the same physical rear lens. Physical-stream metadata confirms readiness and supplies the saved exposure, ISO and timestamps; missing physical metadata blocks or interrupts capture.
- Each frame is saved as tightly packed **I420 YUV**: 8-bit Y, then U, then V, with Android row padding/interleaving removed. It is uncompressed and sensor-native, without pixel rotation or rescaling.
- Camera images and metadata are paired by sensor timestamp. Actual exposure, ISO, timestamp, resolution and measured cadence are saved. Requested values are never substituted for missing actual measurements.
- Missing frames, a frame-rate drop greater than 10%, exposure/ISO changes, lost camera locks, missing metadata and storage errors remain visible in capture details and exported metadata.

Each finished position **auto-saves to Internal storage/Pictures/Q-VLP/session_<id>/**. There is no Save button or pause/verification menu: finish a capture and continue with Repeat or Next Position. Each attempt has its own `position_<id>_<unique-id>/` folder containing YUV frames, `frames.csv` and `burst.json`. Repeat preserves earlier attempts in the same session. The camera screen shows “Saving position …” followed by “Position … saved”; saved is shown only after publication succeeds. Sessions shows the folder path; Share / Save ZIP remains optional.

Android's folder picker grants access once through **Sessions → Save location**; no broad storage permission is needed. Existing folder access is retained on upgrade, and the previous pause setting is removed so it cannot disable auto-save. A save error shows a Retry action and keeps the original recordings safe. Reopening automatically retries unfinished saves. Completed public folders are not recreated just because they were removed outside the app.

The app first finishes recording in its private recovery store, then publishes a snapshot on a separate worker, verifies each file's SHA-256 and renames temporary files. Previously verified, unchanged attempts are skipped. Newly finished positions take priority over queued recovery work. Copying yields during another capture, and neither saving nor recovery locks the camera controls. The completion beep means recording has finished; the separate save message confirms when the public files are ready. Interrupted attempts are also preserved and auto-saved. Private recovery copies are retained, so storage is needed for both copies. Public files remain accessible outside the app and after uninstalling. Each session also includes `session.json`, a format guide and, for imported surveys, `positions.csv`.


`frames.csv` retains the document's exact per-frame column names. Unknown values and unprocessed LED columns are empty. Per-frame FPS is `1e9 / elapsed_timestamp_ns` between consecutive saved frames (the first cell is empty); burst FPS uses `(saved_frames - 1) / elapsed_seconds`. Capture settings, original crop/strides, missing IDs and warnings are in `burst.json`. The journal is updated after each saved frame; interrupted attempts may lack a finalized CSV, but their files and journal remain exportable. Mount changes create a new session. Row time is only reused for its recorded camera, resolution and zoom; changing zoom marks it unknown until the matching calibration is entered or restored.

### Capture length

The selected mode and each mode’s last value are remembered. The choice is frozen when Start Capture is tapped and shown on the capture screen; settings are unavailable during countdown and recording. The 3-second countdown is separate from capture duration. Frame mode stops after the selected number of exposures, and missing saved frames are reported rather than silently replaced. Duration mode keeps frames whose sensor timestamps fall within the selected interval starting at the first exposure; it does not convert seconds into a fixed frame count. Camera shutdown and saving may finish after that interval. A single repeating request avoids constructing a large request list, and extra in-flight frames outside the selection are discarded.

Progress, completion details, storage checks, missing-frame accounting and timeouts use the selected length. `burst.json` records `requested.length_mode`, `requested.frames` (null for duration), `requested.duration_seconds` (null for frame mode), `requested.estimated_frames`, `frames_started`, and the measured `saved_frame_span_s`. Estimates are never substituted for actual FPS or timestamps. Timed captures reserve storage using the stream’s fastest reported cadence; the existing CSV columns are unchanged. Cancel or leaving the app preserves saved frames and marks the attempt interrupted.

### Factory Reset and research checks

**Settings → Camera Settings → Factory Reset** restores a supported research camera preset: a rear camera, 1× zoom, a fixed full-sensor-aspect-ratio resolution (closest to 1280 × 960), at least 30 fps, exposure at most 100 µs, minimum ISO, and locked or fixed focus/white balance. It first checks the entire preset and makes no changes if no rear camera supports it. The button remains available when the current camera is unsupported, so a front-camera setup can be recovered. Returning to the camera reopens the selected rear camera and reacquires its locks.

This resets camera settings only. Capture length, surveyed positions, mounting measurements and saved sessions are kept; the next capture starts a new session. A supplied row time is still used only when its recorded camera, resolution and zoom match. The physical rear-lens selection is retained by Factory Reset.

Capture is checked before the countdown and again immediately before camera capture. The selected rear camera, 1× zoom, fixed full-aspect-ratio resolution, short exposure, manual ISO, target FPS and fixed/locked focus and white balance must be valid. Camera readiness also requires confirmed focus/white-balance controls and two stable actual exposure/ISO/frame-duration readings with the expected sensor crop. Unknown measurements, exposure above 100 µs, or a reported frame duration longer than 33,350,000 ns prevent capture. Nominal 30 fps allows at most 0.05% sensor-timing rounding; the requested FPS must still be at least 30. Sensor frame duration confirms the reported cadence; sustained delivered FPS still needs measurement on the phone and is logged separately. Later drift generates capture warnings.

### Fixed physical rear lens

On a logical multi-camera device (Android 9+), the app chooses the physical rear lens
whose field of view is closest to the logical camera's native 1× view, then persists
that physical ID. Both the preview and YUV `OutputConfiguration` objects explicitly
target that lens. A missing saved lens, unsupported configuration or missing physical
capture results prevents capture; the app never silently substitutes another lens.
Devices exposing a single rear camera use that camera directly.

Settings and Camera Details identify the fixed lens. Capabilities, focus/white-balance
checks, exposure/ISO, crop and timestamps come from the selected physical camera.
Frame-count mode counts all started exposures; duration mode uses the physical
sensor's timestamps. `burst.json` records the logical and physical IDs, selection
method and metadata source; each frame also records its metadata camera ID/source.
The first pin starts a fresh session, preserving earlier captures. Reopening,
switching back to the rear camera and Factory Reset retain the same pin.

On the tested Pixel 8a, logical camera 0 is pinned to physical main rear camera 2.
Android reports both output streams as physical camera 2. This uses Camera2's
[physical output routing](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#setPhysicalCameraId(java.lang.String))
and [physical capture results](https://developer.android.com/reference/android/hardware/camera2/TotalCaptureResult#getPhysicalCameraResults()).

### Pixel 8a timing compatibility

The connected Pixel 8a reports 98.504 µs exposure and a 33,340,371 ns sensor frame duration (approximately 29.994 fps) with the full-frame 1280 × 960, 100 µs, ISO 50, 30 fps preset. Focus, white balance and 1× crop confirm correctly. Its advertised faster modes use a cropped 16:9 stream; the full-frame mode is nominal 30 fps. The selected policy keeps full-frame capture and accepts only tiny sensor-timing rounding (0.05%, maximum 33,350,000 ns). This is nominal 30 fps compatibility, not a claim of mathematically exact ≥30 measured fps. The limit rejects 29.97 fps and larger slowdowns; exposure and the other research checks retain their existing limits.

Camera Details displays measured cadence to three decimal places and explicitly notes when nominal-timing tolerance is used. Saved timestamps and frame durations remain unmodified. No phone-model name is used to bypass validation; the same narrow measured-timing rule applies to supported cameras. The ordinary defaults and Factory Reset continue to select rear camera, 1×, 1280 × 960 where supported, 100 µs, minimum ISO (50 on this phone), nominal 30 fps and fixed/locked focus and white balance.

On Pixel 8 and Pixel 8a, initial defaults and Factory Reset prefer manual focus at 0.5 dioptres (a 2 m starting distance) and fixed Daylight white balance when supported. This avoids autofocus-lock failures on a blank or dark ceiling and removes white-balance convergence from startup. The focus distance must be adjusted for the real camera-to-LED distance and checked for sharpness; the preset does not certify focus quality. Other phones retain their existing control preferences, and unsupported fixed modes fall back to supported locks. Existing user settings are retained until Factory Reset is tapped.

## Remaining requirements

This is the **pre-CV capture workflow**, not full implementation of the application description:

- Validate actual geometry, fixed settings and sustained capture rate on the mounted target phone. A successful build does not establish device compliance or the PDF's acceptance criteria.
- Implement LED detection, stripe frequency identification, row-time calibration from an LED, quality filtering, preview overlays and position-level fingerprint aggregation. LED CSV columns remain empty and `fingerprints.csv` is not generated yet.

The current export preserves each repeated attempt in its own folder. The final pipeline's session-level frame/fingerprint layout and repeat-selection policy must be integrated with CV. No LED counts or location estimates are presented as measured results in this version.

Android references: [document import/export](https://developer.android.com/training/data-storage/shared/documents-files), [Camera2 capture controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest), [YUV planes](https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888), and [temporary sharing permissions](https://developer.android.com/training/secure-file-sharing/share-file).

## Development

Use JDK 17 or 21 and the included Gradle wrapper. JDK 25 cannot run this project's Gradle 8.9 build; select JDK 21 under Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK in Android Studio.

The existing build targets Android SDK 34 with minimum SDK 26. Configure the Android SDK location in the ignored `local.properties` file if necessary. Build with `./gradlew :app:assembleDebug` and run existing unit tests with `./gradlew :app:testDebugUnitTest`.

Generated Gradle and IDE files are ignored. The Q-VLP launcher artwork and the university logo used on the About screen are retained, together with original developer and lab attribution. CV dependencies can be added when LED analysis is implemented.
