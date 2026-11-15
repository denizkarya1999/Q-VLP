# Q-VLP — Quantum-based Location Estimation

Q-VLP is an Android research app for **Quantum-based Location Estimation** using visible-light positioning. This version records frames of the ceiling LEDs at numbered positions, detects the LEDs in every frame and builds the fingerprint file `final.csv`. Location estimation is planned.

The app is derived from [RoTemplate](https://github.com/denizkarya1999/RoTemplate). Open this directory in Android Studio; `app` is the application module.

- App name: Q-VLP
- Application ID and namespace: `com.developer27.qvlp`
- Version: 1.0.0
- Release date: November 14, 2026
- Source template: [RoTemplate](https://github.com/denizkarya1999/RoTemplate)

**Topics:** `android` · `kotlin` · `camera2` · `visible-light-positioning` · `indoor-positioning` · `location-estimation` · `quantum-based-localization` · `manual-camera-controls`

## Authors

Thuy-Anh Nguyen, Shahed Alabdulrahman, Deniz Karya Acikbas, Christian Nwobu, Clark Friese, and Ethan Liu.

Professor: Xiao Zhang · [Trustworthy AIoT Lab](https://www.zhangxiao.me/) · University of Michigan-Dearborn.

## Using Q-VLP

**Settings** has three groups. **Modes** is two radio buttons: **Data Collection Mode** is selected and runs the recording workflow; **Inference Mode** is faded, marked **Not implemented yet** and cannot be selected. **Camera Settings** has **Rolling Shutter Rate** and **ISO Rate**. **Recording** has **Number of Frames**. There is no Help section.

1. Tap **Position** and enter the position ID. Only the ID is typed: there are no X/Y coordinates and no list to import.
2. Mount the phone with the camera lens over the mark, at the same height for every position.
3. Align the ceiling LEDs in the preview and tap **Start Capture** (or press a volume key), then step away. The app waits 3 seconds, captures **Settings → Recording → Number of Frames** frames (1–1000, default 30), and processes them. There is no beep or vibration.
4. When processing ends, the buttons below replace Start Capture. Nothing is written on the result screen: no result line, no warning line and no status boxes.

   | | |
   |---|---|
   | **Repeat** | **Replace Position** |
   | **Previous Position** | **Next Position** |
   | **End Session** | |

   - **Repeat** captures the same position again.
   - **Next Position** and **Previous Position** select the next or previous position (ID ± 1). Capturing a position that already has a capture replaces it.
   - **Replace Position** asks for the ID of the position to capture again. It lists the IDs captured in this session and rejects an ID that has no capture.
   - **End Session** can be tapped after any position. Captured positions stay saved, and the next capture starts a new session.

   "Replace" means the latest completed capture of a position is the one used in `final.csv`. The earlier capture's folders are kept in the session.
5. The app creates **Internal storage/Pictures/Q-VLP** by itself when it starts (Android 11 and later). Once, choose where sessions are saved: when auto-save needs setting up, a notice appears on the camera screen; tap it, then **Choose folder → Internal storage → Pictures → Use this folder → Allow**. After that every position saves to **Pictures/Q-VLP** automatically and the notice stays hidden.

### The information button

The **i** button under the camera-switch button opens everything the screen no longer shows:

- **Camera** — whether the camera is ready, and the requested and reported settings.
- **Saving** — the auto-save progress of the session folder.
- **Last capture** — frames saved, measured fps, which LEDs were detected, any warnings, and the session folder.

The camera and saving notices appear on the camera screen only when something needs attention, such as a camera error, a missing permission or a failed save.

### Camera screen

**Sessions**, **Settings** and **About** are across the top; **Position** and a full-width **Start Capture** button are at the bottom. On the right of the preview, from top to bottom: **+**, the zoom level, **−**, the **switch-camera** button and the **i** button. Zoom uses the camera's digital crop range in 0.1× steps. The app opens the **front camera** by default; the switch button's icon shows the camera a tap selects (a camera for rear, a person for front) and is hidden when the device has one facing. Each camera keeps its own settings and zoom, and switching camera or changing zoom starts a new session on the next capture.

The preview always fills the screen at the camera image's true proportions, on both cameras, and crops what does not fit; there is no preview size setting. It is display-only: it does not change zoom, resolution or saved pixels. Screen orientation is held during a capture and frames are not rotated. Cancelling or leaving the app keeps the frames already saved and marks the capture as interrupted.

**Settings → Camera Settings** has two options. **Rolling Shutter Rate** is a list in Hz, as in RoTemplate and LiFind; the exposure time is one period of the rate (10000 Hz, the default, is 100 µs). Only rates the camera supports are listed, and every listed rate can be used for capture: choosing one never blocks the camera. The LED stripes (1.8–4.8 kHz) are sharpest at 10000 Hz or higher; slower rates give a brighter picture but blur the stripes, so fewer LEDs are detected. **ISO Rate** is typed within the camera's range. Everything else is fixed by the app and has no setting: the lens, the resolution (1280 × 960 on a Pixel 8a, front and rear), 30 fps, focus and white balance.

## Camera and saved data

- Manual exposure and ISO use device-reported limits. The defaults are a 10000 Hz rolling shutter rate (100 µs) and minimum ISO; the app fixes 1280 × 960 at 30 fps. A 4:3 size also qualifies when the sensor is slightly wider than 4:3, as on the Pixel 8a front camera (4208 × 3072): such frames keep every sensor row and crop under 3% of the width.
- Focus and white balance are fixed or locked by the app before capture. Returning from Settings reacquires the locks.
- Requests disable stabilization and scene effects where exposed, apply the selected digital crop (full sensor at 1×) and disable distortion correction and rotate-and-crop where supported. On the rear camera, preview and capture are pinned to the same physical lens; the front camera is used directly.
- Camera images and their results are paired by sensor timestamp. Actual exposure, ISO, timestamp, resolution and measured cadence are saved. Requested values are never substituted for missing measurements.
- Frames are copied off the camera in bulk and up to 12 wait in memory for their capture result (within a 32 MB budget). On a Pixel 8a, 30 of 30 frames are saved at 1280 × 960 and 30 fps.
- Missing frames, a frame-rate drop greater than 10%, exposure/ISO changes, lost camera locks and storage errors are listed as warnings under **i → Last capture** and in **Sessions**.

### What a session contains

Each session is one folder in **Internal storage/Pictures/Q-VLP**. That folder is created when the app starts, so it is there before the first capture. It contains exactly this, and nothing else:

```
session_<id>/
  final.csv                                        the fingerprint: one row per position
  Original_Frames/                                 the original frames
    position_<id>_<attempt_timestamp>/             one folder per capture
      frames/
        frame_00000.png ...                        the frames, in colour
      metadata/
        frame_00000_metadata.json ...              camera settings of each frame
      frame_and_metadata_mapping.csv               pairs each frame with its metadata file
  Processed_Frames/                                the frames after LED detection
    processed_position_frames_<id>_<attempt_timestamp>/   one folder per capture
      processed_frames/
        frame_00000.png ...                        the frames, in colour, with the detection drawn on
      processed_frame_metadata/
        frame_00000_metadata.json ...              LED_k_u_px, LED_k_v_px, LED_k_detected
      processed_frame_and_metadata_mapping.csv     pairs each processed frame with its metadata file
```

`<id>` is the position ID and `<attempt_timestamp>` the local time the capture started, as `yyyyMMdd_HHmmss`. Every capture of a position makes one `position_…` folder in `Original_Frames` and its `processed_position_frames_…` twin in `Processed_Frames`. A session recorded before these two folders existed is moved into them the next time the app starts.

**Frames.** Each frame is a lossless colour PNG in sensor-native orientation, without rotation, rescaling or lossy compression, so compression cannot blur the stripes. The camera delivers luminance with half-resolution colour (YUV 4:2:0); the app converts it to RGB once with full-range BT.601. No `.yuv` file is kept.

**Frame metadata.** `metadata/frame_00000_metadata.json` holds the camera settings of that one frame only:

- `frame_id`, `frame_file`, `timestamp_ns`.
- `camera`: front or rear, the lens, zoom and sensor orientation.
- `requested`: what was selected — `rolling_shutter_rate_hz`, `iso`, size and fps.
- `reported`: what the camera says it used for that frame — `rolling_shutter_rate_hz`, `exposure_time_ns`, `iso`, `fps`, `width`, `height` and `row_time_us`. The reported rate can differ slightly from the selected one (10000 Hz selected is about 10152 Hz on a Pixel 8a).
- `capture_result`: the raw camera result.

**Mapping files.** Both mapping CSVs have the columns `frame_file,metadata_file` and one row per frame, such as `frame_00000.png,frame_00000_metadata.json`. `frame_and_metadata_mapping.csv` pairs `frames/` with `metadata/`; `processed_frame_and_metadata_mapping.csv` pairs `processed_frames/` with `processed_frame_metadata/`.

**Processed frames.** `processed_frames/frame_00000.png` is the same frame with the detection drawn on it: a box around every blob (dashed when the blob was rejected), a cross at its centre and a label with the LED number. LEDs 1–4 are drawn in red, blue, green and purple; a blob that matches no LED is yellow.

**Processed frame metadata.** `processed_frame_metadata/frame_00000_metadata.json` holds, for LEDs 1–4, `LED_k_u_px`, `LED_k_v_px` and `LED_k_detected` (1 or 0; the coordinates are `null` when the LED was not detected). A `details` block adds what the detector measured: background level, closing window, row time and, per LED, the estimated frequency, stripe period, fit quality, blob area and the quality flags.

**final.csv.** One row per position with 13 columns: `position_id` (the typed ID) and, for LEDs 1–4, `LED_k_u_px`, `LED_k_v_px` and `LED_k_detected`. It is built from the processed frame metadata, read through `Processed_Frames/processed_position_frames_…/processed_frame_and_metadata_mapping.csv`, of the **latest completed capture of each position**, and its rows are in order of position ID. An LED is detected at a position when it was valid in at least 70% of that capture's frames; its coordinates are the median over those valid frames. An LED that is not detected has `0` and empty coordinates. An interrupted capture never replaces a completed one.

`LED_k_u_px` is the column and `LED_k_v_px` the row of LED k's blob centre, in pixels with sub-pixel precision. The origin is the centre of the top-left pixel; u grows to the right and v grows downward, in sensor-native orientation.

The app keeps its own bookkeeping (`session.json` and one `burst.json` per capture, with warnings and the full capture record) in its private storage. Those files are never copied to **Pictures/Q-VLP** or put in a ZIP.

### Saving, sessions and deletion

A capture is first finished in the app's private store, then copied to **Pictures/Q-VLP** on a separate worker; each file is verified by SHA-256 and `final.csv` is published last. Copying yields to a running capture and never locks the camera controls. Interrupted captures are saved too. The private copy is retained, so storage is needed for both copies; the public files stay accessible outside the app and after uninstalling. A failed save shows a notice with Retry, and reopening the app retries unfinished saves.

Open **Sessions** to see each session with its captures, frames and warnings. Tap a row for details, **Share** and **Save ZIP**; the ZIP has the same layout as the folder. Press and hold a row to begin selection, tap other rows to add them, then tap **Delete (count)** to remove those sessions from both the app and `Pictures/Q-VLP`. **New Session** sits beside **Delete** and does the same as **End Session**. Deletion runs in the background; if it is interrupted or folder access is missing, the session stays marked for deletion and is retried on reopening.

### LED detection

After each capture the app finds the LED blobs in every frame. It follows `extract` in the project's simulator and the app description:

1. Background = median of every 8th pixel; Gaussian smoothing (σ = 1 px); closing along rows to join dark stripes; threshold at background + 25 grey levels (8-bit luminance); connected blobs of at least 400 px.
2. Centre = mean pixel position of the blob mask, sub-pixel.
3. Identity = stripe frequency. A sinusoid is fitted to the row profile through the blob's central 80% of columns; `f = 1 / (stripe_period_rows × row time)`. The blob is named by the one configured frequency (1.8, 2.8, 3.8, 4.8 kHz) within 5%.
4. Quality rules: not within 17 px of the image edge, not saturated, roughly round, at least 2 stripe periods with a good fit, and only one blob per LED. A doubtful LED is reported as not detected.

**Row time.** The app uses the camera's own report (rolling-shutter skew ÷ image rows) and writes it as `row_time_us` in both metadata files. There is no way to enter a calibrated value. If the camera reports none, blobs are measured but cannot be named, and the capture carries a warning. A wrong row time makes LEDs go undetected rather than misnamed, because the estimate then falls outside every 5% band.

**Closing window.** The 21-row default suits the simulator's 30 µs rows. With a known row time the window grows to cover 75% of the slowest LED's period in rows, so a faster sensor's longer dark stripes do not split a blob.

**Speed.** Processing starts when the last frame arrives and is built to be short: about 0.75 s for 30 frames on a Pixel 8a.

- During the capture each frame is written raw, because only that keeps up with the camera. Afterwards every frame is read once and turned into its four files; the raw copy is deleted and never published.
- Frames are processed in parallel on up to 8 cores.
- Detection looks only at the parts of the frame that hold bright pixels, with a margin wide enough that the result is identical to processing the whole frame.
- The PNG encoder is written for speed (fast deflate, no row filtering). A processed frame with nothing drawn on it reuses the bytes of the original.
- The code is warmed up during the countdown so the first capture is as fast as later ones.

Thresholds and frequencies are fixed defaults in this version.

### Hardware capture

On the camera screen, either volume key or a camera shutter key starts the same countdown as Start Capture. Press again to cancel the countdown or recording; holding a button never repeatedly starts or stops captures. After a result, the key repeats the selected position. Buttons do not trigger capture in Settings, dialogs or while the app is in the background. Android-paired Bluetooth shutters that send volume, camera or Enter key events are supported.

### Capture length

A capture is always a number of frames; there is no duration mode. The number is remembered and frozen when Start Capture is tapped; settings are unavailable during countdown and recording. The 3-second countdown is separate from the capture. The capture stops after the selected number of exposures, and missing frames are reported rather than silently replaced.

### Fixed settings and research checks

There is no Factory Reset: nothing but the rolling shutter rate and ISO can be changed, so there is nothing else to restore. On each camera the app uses the resolution closest to the planned 1280 × 960 that covers the sensor, at 30 fps, and holds focus and white balance still. Resolution, frame-rate, focus and white-balance values saved by earlier versions are ignored. If a camera cannot meet these fixed values, a notice on the camera screen explains why and the other camera can be selected.

Capture is checked before the countdown and again immediately before camera capture. On the selected camera, 1× zoom, fixed full-aspect-ratio resolution, a rolling shutter rate from the list, manual ISO, target FPS and the app-held focus and white balance must be valid. Camera readiness also requires confirmed focus/white-balance controls and two stable actual exposure/ISO/frame-duration readings with the expected sensor crop. Unknown measurements or a reported frame duration longer than 33,350,000 ns prevent capture. Nominal 30 fps allows at most 0.05% sensor-timing rounding; the requested FPS must still be at least 30. Sensor frame duration confirms the reported cadence; sustained delivered FPS still needs measurement on the phone and is logged separately. Later drift generates capture warnings.

### Fixed physical rear lens

On a logical multi-camera device (Android 9+), the app chooses the physical rear lens
whose field of view is closest to the logical camera's native 1× view, then persists
that physical ID. Both the preview and YUV `OutputConfiguration` objects explicitly
target that lens. A missing saved lens, unsupported configuration or missing physical
capture results prevents capture; the app never silently substitutes another lens.
Devices exposing a single rear camera use that camera directly.

The information button identifies the fixed lens. Capabilities, focus/white-balance
checks, exposure/ISO, crop and timestamps come from the selected physical camera.
The capture counts all started exposures and pairs images by the physical
sensor's timestamps. Each frame's metadata records the camera and the lens used.
The first pin starts a fresh session, preserving earlier captures. Reopening
the camera retains the same pin.

On the tested Pixel 8a, logical camera 0 is pinned to physical main rear camera 2.
Android reports both output streams as physical camera 2. This uses Camera2's
[physical output routing](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#setPhysicalCameraId(java.lang.String))
and [physical capture results](https://developer.android.com/reference/android/hardware/camera2/TotalCaptureResult#getPhysicalCameraResults()).

### Pixel 8a timing compatibility

The connected Pixel 8a reports 98.504 µs exposure and a 33,340,371 ns sensor frame duration (approximately 29.994 fps) with the full-frame 1280 × 960, 100 µs, ISO 50, 30 fps preset. Focus, white balance and 1× crop confirm correctly. Its advertised faster modes use a cropped 16:9 stream; the full-frame mode is nominal 30 fps. The selected policy keeps full-frame capture and accepts only tiny sensor-timing rounding (0.05%, maximum 33,350,000 ns). This is nominal 30 fps compatibility, not a claim of mathematically exact ≥30 measured fps. The limit rejects 29.97 fps and larger slowdowns; the other checks retain their existing limits.

The information button shows the measured cadence to three decimal places and notes when nominal-timing tolerance is used. Saved timestamps and frame durations remain unmodified. No phone-model name is used to bypass validation; the same narrow measured-timing rule applies to supported cameras. The app continues to use 1×, 1280 × 960 where supported, nominal 30 fps and fixed/locked focus and white balance, with defaults of 10000 Hz (100 µs) and minimum ISO (50 on this phone).

Focus and white balance have no settings; the app picks a fixed or locked mode for each camera. On Pixel 8 and Pixel 8a it uses manual focus at 0.5 dioptres (about 2 m) and fixed Daylight white balance when supported, which avoids autofocus-lock failures on a blank or dark ceiling and removes white-balance convergence from startup. A fixed-focus lens, such as the Pixel 8a front camera, needs no focus control. Other phones use automatic focus and white balance that lock once after the camera opens. Check sharpness in a saved frame picture; the app does not certify focus quality. Focus and white-balance values saved by earlier versions are ignored.

## Remaining requirements

This is the capture and LED-detection workflow, not a full implementation of the application description:

- Validate actual geometry, fixed settings and sustained capture rate on the mounted target phone. A successful build does not establish device compliance or the PDF's acceptance criteria.
- Validate LED detection under the real LEDs: thresholds, the camera-reported row time and centroid repeatability have only been checked on simulated frames.
- Add a settings screen for the detection thresholds and LED frequencies, a row-time calibration mode, and a live preview overlay of detected blobs. The document's `fingerprints.csv` (with per-LED counts and standard deviations) is not generated; `final.csv` carries the core fingerprint columns.
- The on-device (instrumented) tests were updated for the new session layout but have not been run on a phone.

`final.csv`, replacing a position and LED detection are implemented. No location estimates are produced in this version.

Android references: [document import/export](https://developer.android.com/training/data-storage/shared/documents-files), [Camera2 capture controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest), [YUV planes](https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888), and [temporary sharing permissions](https://developer.android.com/training/secure-file-sharing/share-file).

## Development

Use JDK 17 or 21 and the included Gradle wrapper. JDK 25 cannot run this project's Gradle 8.9 build; select JDK 21 under Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK in Android Studio.

The existing build targets Android SDK 34 with minimum SDK 26. Configure the Android SDK location in the ignored `local.properties` file if necessary. Build with `./gradlew :app:assembleDebug` and run existing unit tests with `./gradlew :app:testDebugUnitTest`.

Generated Gradle and IDE files are ignored. The Q-VLP launcher artwork and the university logo used on the About screen are retained, together with original developer and lab attribution. LED detection and PNG encoding are written in Kotlin and need no CV library.

## Installable release

The GitHub release includes **Install Q-VLP.apk** and screenshots from the Pixel 8a. The APK uses the release build type and the project's existing development signing certificate so it can update the installed research app without deleting its sessions or settings. It is intended for direct installation on research devices. A public store release would need its own release-signing and distribution setup.
