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

Open **Settings** from the camera screen. Tap **Exposure time (µs)** or **ISO** to enter a value, and tap **Resolution** or **Target frame rate** to choose a device-reported option. Return to the camera screen to apply the saved profile. Each camera has its own settings; changes are disabled during recording.

- Exposure is expressed in microseconds (100 µs = 0.1 ms), with up to three decimal places. It is validated against the sensor range and the selected frame interval. ISO uses the selected camera's reported sensitivity range.
- Resolution choices are shared by preview and MediaRecorder. Ordinary session frame rates are filtered using stream minimum durations, the reported normal-session FPS ceiling, the maximum frame duration, and an available H.264 surface encoder. High-speed sessions are excluded. A resolution change that requires a different rate displays a message; an exposure too long for a requested rate must be shortened first.
- The recommended profile requests 100 µs, minimum ISO, 1280 × 960 and 30 fps where supported. Otherwise it chooses an available resolution near that size, preferring the sensor aspect ratio, and a usable rate. Exposure is bounded by the camera range. Existing manual exposure/ISO preferences are imported once. Use **Restore recommended settings** to replace those imported values.
- Exposure and ISO are always manual (`CONTROL_AE_MODE_OFF`); `SENSOR_FRAME_DURATION` requests the cadence. Preview and recording use the same profile and recording FPS. Cameras without the required manual controls show an explanation instead of silently enabling automatic exposure.
- The preview shows requested values separately from reported exposure/ISO and cadence measured over roughly one second of sensor timestamps. Cadence is the rate of received capture results, not a count of encoded MP4 frames. These readings are not saved yet.

Device capability reports cannot guarantee a particular simultaneous stream/encoder combination or sustained rate. Runtime failures ask the user to lower resolution or FPS; actual exposure and cadence may differ because of sensor rounding, readout overhead, or device load. These require a physical-device check. Very short exposure and low ISO can make the scene dark; adjust ISO in Settings as needed. Preview display geometry, fixed focus/lens/stabilization, individual frame export and metadata logging are later milestones.

Camera2 references: [manual exposure and frame duration](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_FRAME_DURATION), [stream timing](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap#getOutputMinFrameDuration(java.lang.Class,%20android.util.Size)).

## Next capture milestones

1. Check the new camera settings and reported values on the target Android phone.
2. Fix camera, zoom, focus, stabilization and capture geometry for a session.
3. Save individual camera frames with matched actual capture metadata.
4. Add countdown, automatic bursts and completion feedback.
5. Import surveyed positions and associate them with each burst.
6. Add session summaries and export.

LED detection, stripe estimation and fingerprint generation follow those capture milestones.

## Development

Use JDK 17 or 21 and the included Gradle wrapper. JDK 25 cannot run this project's Gradle 8.9 build; select JDK 21 under Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK in Android Studio.

The existing build targets Android SDK 34 with minimum SDK 26. Configure the Android SDK location in the ignored `local.properties` file if necessary. Build with `./gradlew :app:assembleDebug` and run existing unit tests with `./gradlew :app:testDebugUnitTest`.

Generated Gradle and IDE files are ignored. The Q-VLP launcher artwork and the university logo used on the About screen are retained, together with original developer and lab attribution. CV dependencies can be added when LED analysis is implemented.
