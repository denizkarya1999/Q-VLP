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

This camera foundation provides preview, exposure/ISO controls, camera switching, zoom and MP4 video recording. Legacy tracking code, model assets, unused imagery and unused CV/ML dependencies have been removed. The full Q-VLP frame-burst and metadata workflow is still to be implemented.

## Camera settings

Open **Settings** from the camera screen. Under **Camera Settings**, tap **Exposure Time (µs)** or **ISO Rate** to enter a value, and tap **Resolution** or **Target Frame Rate** to choose a device-reported option. Return to the camera screen to apply the saved profile. Each camera has its own settings; changes are disabled during recording.

- Exposure is expressed in microseconds (100 µs = 0.1 ms), with up to three decimal places. It is validated against the sensor range and the selected frame interval. ISO uses the selected camera's reported sensitivity range.
- Resolution choices are shared by preview and MediaRecorder. Ordinary session frame rates are filtered using stream minimum durations, the reported normal-session FPS ceiling, the maximum frame duration, and an available H.264 surface encoder. High-speed sessions are excluded. A resolution change that requires a different rate displays a message; an exposure too long for a requested rate must be shortened first.
- The recommended profile requests 100 µs, minimum ISO, 1280 × 960 and 30 fps where supported. Otherwise it chooses an available resolution near that size, preferring the sensor aspect ratio, and a usable rate. Exposure is bounded by the camera range. Existing manual exposure/ISO preferences are imported once. Use **Restore Recommended Settings** to replace those imported values.
- Exposure and ISO are manual during capture (`CONTROL_AE_MODE_OFF`); `SENSOR_FRAME_DURATION` requests the cadence. The preparation phase for automatic focus/white balance locks briefly enables AE, then restores the saved exposure and ISO before recording. Preview and recording use the same capture profile. Cameras without the required manual controls show an explanation.
- The preview shows requested values separately from reported exposure/ISO and cadence measured over roughly one second of sensor timestamps. Cadence is the rate of received capture results, not a count of encoded MP4 frames. These readings are not saved yet.

Device capability reports cannot guarantee a particular simultaneous stream/encoder combination or sustained rate. Runtime failures ask the user to lower resolution or FPS; actual exposure and cadence may differ because of sensor rounding, readout overhead, or device load. These require a physical-device check. Very short exposure and low ISO can make the scene dark; adjust ISO in Settings as needed. Preview display geometry, fixed physical lens/stabilization, individual frame export and metadata logging are later milestones.

### Focus and white balance

- **Focus Mode** offers **Auto then Lock**, **Manual (Fixed)**, and **Continuous Auto** when supported. Fixed-focus cameras show their lens status. **Focus Distance** accepts the device's supported range, with 0 for infinity; cameras without calibrated distances use the label **Focus Position** and camera units. The numeric field is editable only in Manual mode and uses the existing light/dark dialog theme.
- **White Balance** offers **Auto then Lock**, **Auto (Unlocked)**, and only the fixed lighting presets reported by the selected camera. Unsupported locks are explained in Settings. No estimated Kelvin-to-gain conversion is used.
- Auto then Lock is the default when supported. On each camera opening, automatic exposure is temporarily enabled for focus and white balance acquisition. Autofocus receives one START trigger, then IDLE requests; white balance settles before a lock is requested. Failed autofocus is not treated as a successful lock.
- Once locks are acknowledged, supported cameras freeze the reported lens position and white balance gains/transform using manual controls. Other cameras retain the supported native locks. These values remain in memory across preview/video session changes. Manual exposure is restored, and two matching results are required before capture is ready. The preview displays the preparation state, confirmed locks, or an actionable failure message.
- Recording is blocked until the selected controls are confirmed. Its new camera session first sends preview-only frames to confirm the retained controls before sending any frames to the encoder. A timeout prevents an indefinite recording start. Returning from Settings or reopening the camera acquires new automatic locks; numeric focus values and mode selections persist per camera. Use manual focus and a fixed white balance preset when values must remain repeatable across camera reopenings.
- Continuous Auto and Auto (Unlocked) deliberately allow changes; their behavior with manual exposure is device dependent. **Restore Recommended Settings** also restores the recommended focus and white balance modes.

Implementation references: Android's [focus controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_MODE), [white balance lock and transfer to manual controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AWB_LOCK), and [manual focus distance](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#LENS_FOCUS_DISTANCE).

Camera2 references: [manual exposure and frame duration](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_FRAME_DURATION), [stream timing](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap#getOutputMinFrameDuration(java.lang.Class,%20android.util.Size)).

## Next capture milestones

1. Check the new camera settings and reported values on the target Android phone.
2. Fix the physical camera, zoom, stabilization and capture geometry for a session.
3. Save individual camera frames with matched actual capture metadata.
4. Add countdown, automatic bursts and completion feedback.
5. Import surveyed positions and associate them with each burst.
6. Add session summaries and export.

LED detection, stripe estimation and fingerprint generation follow those capture milestones.

## Development

Use JDK 17 or 21 and the included Gradle wrapper. JDK 25 cannot run this project's Gradle 8.9 build; select JDK 21 under Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK in Android Studio.

The existing build targets Android SDK 34 with minimum SDK 26. Configure the Android SDK location in the ignored `local.properties` file if necessary. Build with `./gradlew :app:assembleDebug` and run existing unit tests with `./gradlew :app:testDebugUnitTest`.

Generated Gradle and IDE files are ignored. The Q-VLP launcher artwork and the university logo used on the About screen are retained, together with original developer and lab attribution. CV dependencies can be added when LED analysis is implemented.
