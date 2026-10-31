# Arco — camera app for the Lumia 950 XL (cityman) LineageOS port

A Camera2 camera app written for the Microsoft Lumia 950 XL running our LineageOS 23.2
(Android 16) port. Manual controls sit on half-ring dials around the shutter button, in the
spirit of the old Windows Phone camera. The look is inspired by it, not copied: every icon,
the launcher icon and the layout are original, and the app uses no Microsoft or Nokia names,
fonts or assets.

Package `org.cityman.camera`, minSdk 33, targetSdk 36 (compileSdk 37.2, which the AndroidX
libraries in use require).

## What works (phase 1)

- Full-screen viewfinder, portrait and landscape (follows the system auto-rotate setting; with
  rotation locked, the icons turn instead).
- Rear (camera 0, IMX230) and front (camera 1, VS6955) cameras, switch button.
- Photo: JPEG (quality 95, high-quality noise reduction and sharpening) and, from the settings,
  an extra DNG with the full 5344×3744 RAW frame (Android `DngCreator`). Files go to
  `DCIM/Camera` as `IMG_yyyyMMdd_HHmmss_SSS.jpg/.dng`.
- Video: H.264 MP4 with AAC sound, 1080p at 20 Mbit/s on the rear camera, `VID_…mp4`.
- Manual controls on arc dials (tap a control chip to open its dial, drag along the ring, the
  automatic position is at the start of the ring; long-press a chip to put it back to auto):
  - **WB**: auto or 2400–8000 K. The Kelvin value becomes white balance gains through the
    sensor's own calibration matrices (see below); the AUTO chip shows the temperature the
    camera's auto white balance is using.
  - **FOCUS**: auto or ∞ to 10 cm (rear only; the front lens is fixed focus).
  - **ISO**: auto or the stops the HAL accepts for manual exposure (42–342 on the rear camera,
    100–800 on the front one; auto exposure itself goes higher, up to ~1800, through ISP gain).
  - **SHUTTER**: auto or 1/8000 s to 0.8 s (rear) / 1.8 s (front).
  - **EV**: −2 to +2 in thirds, while ISO or shutter is still automatic. With only one of ISO
    and shutter set, the other one keeps the exposure last measured by auto exposure, shifted
    by EV.
- Tap the viewfinder to focus and meter on a point (AF·AE lock); long-press it to unlock.
  Pinch to zoom (crop, up to 4×).
- Hardware camera key: half press focuses, full press shoots. Volume keys also shoot.
- Gallery thumbnail of the last photo or video, opens it in the system viewer.

Not in scope: Living Images and Movie Moments (dropped on request).

## Phone notes

From the HAL's static metadata on the cityman (31/10/2026):

| | rear (0) | front (1) |
|---|---|---|
| JPEG / viewfinder max | 4160×3120 / 1440×1080 | 1304×972 |
| RAW | 5344×3744 RGGB, black 64, white 1023 | 1304×972 GRBG |
| ISO (manual) | 42–342 | 100–800 |
| Exposure | 12.6 µs – 0.83 s | 27.5 µs – 1.8 s |
| AF | auto/continuous, min focus 10 cm | fixed |
| Sensor orientation | 270 | 90 |

The rear sensor's widest JPEG is 4:3, so "Full" and "4:3" are the same 13 MP size and the
settings show only one of them. The DNG keeps the whole sensor.

The camera-to-sRGB transform this app computes from the HAL's forward matrix matches the one the
HAL's own auto white balance uses (178/−34/−16 … in /128 units), so manual Kelvin and auto agree.

## Build

Needs JDK 17+ and the Android SDK (platform 37.2, build tools); no Android source tree.

```bash
cd cityman-camera
./gradlew assembleDebug
```

`local.properties` must point at the SDK (`sdk.dir=/home/lorenzo/Android/Sdk`); it is not
committed. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

Install and run on the phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb shell am start -n org.cityman.camera/.MainActivity
```

Camera log lines are tagged `ArcoCamera`:

```bash
adb logcat -s ArcoCamera
```

Debug builds also take commands over adb while the app is in the foreground (no screen taps),
for example a DNG shot at ISO 200:

```bash
adb shell am broadcast -a org.cityman.camera.DEBUG --ez dng true --ei iso 200 --ez shoot true
```

Extras: `dng`, `iso` (0 = auto), `shutter_us`, `wb` (Kelvin, 0 = auto), `focus` (diopters,
−1 = auto), `ev` (steps), `reset`, `mode` (`photo`/`video`), `shoot`. See `DebugCommands.kt`.

## Code map

- `camera/CameraController.kt` — Camera2 session, viewfinder (SurfaceView), still/RAW capture,
  video recording with a persistent input surface, manual controls, tap metering, zoom.
- `camera/CameraCaps.kt` — what each camera supports, stream sizes, frame-rate ranges.
- `camera/ColorCalibration.kt` — Kelvin → white balance gains and colour transform.
- `camera/MediaStoreWriter.kt` — saving to `DCIM/Camera` through MediaStore.
- `ui/Dials.kt` — dial stops, labels and how they map to the controls.
- `ui/ArcDial.kt` — the half-ring dial (Compose Canvas) and its drag handling.
- `ui/CameraScreen.kt` — layout for both orientations, chips, shutter bar, settings.
- `ui/Glyphs.kt` — the app's line icons.

## Phase 2 (planned, not started)

Burst of 4–8 RAW frames, tile alignment, temporal merge, demosaic, tone mapping and sharpening
on the GPU, tuned to the IMX230 (black level 64, white level 1023, colour matrices from the
HAL, lens shading from `camera-sensors/rear-tuning` of the device tree). References to study:
PhotonCamera, Tim Brooks's HDR+ Halide implementation and Open Camera's noise-reduction mode.

## Licence

GPL-3.0-or-later (see `LICENSE`). The app is GPL so that phase 2 can adapt code from GPL
projects such as PhotonCamera and Open Camera, with attribution, if that turns out useful.

## Credits

- **Android Open Source Project / Google** — Camera2, `DngCreator`, Jetpack Compose and AndroidX
  (Apache 2.0). Sizing a `MediaCodec` persistent input surface with a throwaway `MediaRecorder`
  before using it in a capture session follows the approach of Google's Camera2Video sample,
  [android/camera-samples](https://github.com/android/camera-samples) (Apache 2.0); no code
  copied.
- **Adobe DNG specification** — the colour model (ColorMatrix, ForwardMatrix, interpolation by
  inverse colour temperature) used for manual white balance.
- **Yongsoo Kim et al.** — the cubic-spline approximation of the Planckian locus
  (US patent 7,024,034), used to turn Kelvin into a CIE xy white point.
- **Bruce Lindbloom** — the Bradford-adapted XYZ (D50) → linear sRGB matrix
  ([brucelindbloom.com](http://www.brucelindbloom.com/)).
- **Himanshu Raj (hraj9258)** — the rear IMX230 chromatix and AF tuning of his talkman port
  ([gitlab.com/hraj9258/android_device_msft_talkman](https://gitlab.com/hraj9258/android_device_msft_talkman),
  `camera/sensors/imx230` at `901b039`), on which this app's colours and the HAL's calibration
  data depend. Device side only; nothing from it is in this repository.
- **Android4Lumia950 community** (djokcho, EpicLPer, j0sh1x and others) — the Lumia 950/950 XL
  Android port this camera stack runs on.
- References for phase 2 (studied, nothing reused yet): **eszdman** —
  [PhotonCamera](https://github.com/eszdman/PhotonCamera) (GPL-3.0); **Timothy Brooks** —
  [hdr-plus](https://github.com/timothybrooks/hdr-plus) (Halide HDR+ implementation);
  **Mark Harman** — [Open Camera](https://opencamera.org.uk/) (GPL-3.0).
- Design inspiration: the Lumia camera app of Windows Phone / Windows 10 Mobile. Nothing of its
  artwork, fonts or names is used.
