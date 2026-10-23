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
3. Align the ceiling LEDs in the preview and wait for **Camera ready**. Tap **Start Capture**, then step away. The app waits 3 seconds, requests 30 uncompressed frames, saves them, then beeps and vibrates. Device sound/vibration settings apply.
4. Read the result on the same screen. Tap the result to open capture details and warnings. **Repeat** keeps the earlier attempt; **Next Position** follows the imported file order. Move the mount to that mark before starting again. Manual positions advance the ID and ask for new coordinates.
5. Open **Sessions**. Select a session and choose **Share** or **Save to Files**. Save in Downloads to copy it to a computer over USB, or choose a mounted USB drive. **New Session** groups later captures separately; it does not delete previous data.

**Settings** contains Exposure Time, ISO Rate, Resolution, Preview Size, Target Frame Rate, Focus Mode, White Balance and Restore Recommended Settings. Only the selected values appear in the list; entry dialogs explain valid ranges. Mounting details have a separate page. Numeric entry works in light and dark mode. Tap the camera status for requested settings, reported measurements and lock status.

The original full-screen preview, centered title, top button row and bottom capture panel are restored. **Settings**, **Sessions** and **About** are across the top; **Position**, green **Start Capture**, and **Switch Camera** are at the bottom. **+ / −** on the right of the preview increase or decrease digital zoom, with the current magnification shown between them. After a capture, **Repeat** and **Next Position** replace Start Capture. Results open their details when tapped, so there is no separate details button. **Settings → How to Capture** opens the instructions. Legacy MP4 recording and audio/storage permissions remain removed. Switch Camera alternates between compatible front/rear cameras and resets the new camera to 1×; if the other camera lacks manual sensor/YUV support, an explanation is shown and the current camera stays selected. Zoom uses the camera’s supported digital crop range in 0.1× steps, from 1× to its maximum. Camera selection and zoom are remembered, and controls are disabled throughout countdown/capture. A change of camera or zoom starts a new session on the next capture; requested zoom and actual crop metadata are saved. The preview defaults to **Full screen (original)**, matching RoTemplate’s full-screen display mapping. **Settings → Preview Size** also offers **Fit entire frame**, **Medium**, and **Small**. Fit shows the whole sensor-native frame at its original proportions; Medium and Small use 75% and 50% of that fitted size, centered on a black background. The chosen display size is remembered across camera switches and app restarts. Restore Recommended Settings returns it to the original full-screen size. Preview size only changes the on-screen view; it does not change camera zoom, frame resolution, row-time calibration or saved pixels. Screen orientation is held during a burst; raw frames are not rotated. Cancel or leaving the app preserves any frames already saved and marks the attempt as interrupted.

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

- Manual exposure and ISO use device-reported limits. Frame cadence is requested through `SENSOR_FRAME_DURATION`. Recommended settings request 100 µs, minimum ISO, 1280 × 960 at 30 fps and automatic focus/white-balance locking where supported; fallbacks use available capabilities. Settings remain adjustable for device setup. For the document's research protocol, use the rear camera at 1× and select exposure at most 100 µs, at least 30 fps, full sensor aspect ratio, and locked or fixed focus/white balance. Unlocked modes and slower profiles remain available for setup and are not protocol-compliant research settings.
- Automatic focus/white-balance preparation briefly uses AE, freezes supported lens/color values or native locks, restores manual exposure, and requires matching camera results before capture. Returning from Settings reacquires the locks. Use manual focus and a fixed white-balance preset for values that must remain identical across camera reopenings.
- Requests disable electronic/optical stabilization and scene effects where exposed, apply the operator-selected digital crop (full sensor at 1×), disable distortion correction where supported, and disable rotate-and-crop where supported. Actual geometry-related result fields are logged; unavailable results stay unknown. Physical-camera changes reported within a burst produce a warning.
- Each frame is saved as tightly packed **I420 YUV**: 8-bit Y, then U, then V, with Android row padding/interleaving removed. It is uncompressed and sensor-native, without pixel rotation or rescaling.
- Camera images and metadata are paired by sensor timestamp. Actual exposure, ISO, timestamp, resolution and measured cadence are saved. Requested values are never substituted for missing actual measurements.
- Missing frames, a frame-rate drop greater than 10%, exposure/ISO changes, lost camera locks, missing metadata and storage errors remain visible in capture details and exported metadata.

Files live in the app's private `files/sessions/` directory and survive app restarts. Export before uninstalling. Each session has `session.json`, a format guide and, when imported, `positions.csv`. Each attempt has its own `position_<id>_<unique-id>/` folder containing YUV frames, `frames.csv` and `burst.json`. Repeating does not overwrite earlier attempts.

`frames.csv` retains the document's exact per-frame column names. Unknown values and unprocessed LED columns are empty. Per-frame FPS is `1e9 / elapsed_timestamp_ns` between consecutive saved frames (the first cell is empty); burst FPS uses `(saved_frames - 1) / elapsed_seconds`. Capture settings, original crop/strides, missing IDs and warnings are in `burst.json`. The journal is updated after each saved frame; interrupted attempts may lack a finalized CSV, but their files and journal remain exportable. Mount changes create a new session. Row time is only reused for its recorded camera, resolution and zoom; changing zoom marks it unknown until the matching calibration is entered or restored.

## Remaining requirements

This is the **pre-CV capture workflow**, not full implementation of the application description:

- Pin one physical rear lens for logical multi-camera devices; retaining a rear camera ID and 1× zoom alone does not guarantee that the device never changes physical lenses.
- Validate actual geometry, fixed settings and sustained capture rate on the mounted target phone. A successful build does not establish device compliance or the PDF's acceptance criteria.
- Implement LED detection, stripe frequency identification, row-time calibration from an LED, quality filtering, preview overlays and position-level fingerprint aggregation. LED CSV columns remain empty and `fingerprints.csv` is not generated yet.

The current export preserves each repeated attempt in its own folder. The final pipeline's session-level frame/fingerprint layout and repeat-selection policy must be integrated with CV. No LED counts or location estimates are presented as measured results in this version.

Android references: [document import/export](https://developer.android.com/training/data-storage/shared/documents-files), [Camera2 capture controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest), [YUV planes](https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888), and [temporary sharing permissions](https://developer.android.com/training/secure-file-sharing/share-file).

## Development

Use JDK 17 or 21 and the included Gradle wrapper. JDK 25 cannot run this project's Gradle 8.9 build; select JDK 21 under Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK in Android Studio.

The existing build targets Android SDK 34 with minimum SDK 26. Configure the Android SDK location in the ignored `local.properties` file if necessary. Build with `./gradlew :app:assembleDebug` and run existing unit tests with `./gradlew :app:testDebugUnitTest`.

Generated Gradle and IDE files are ignored. The Q-VLP launcher artwork and the university logo used on the About screen are retained, together with original developer and lab attribution. CV dependencies can be added when LED analysis is implemented.
