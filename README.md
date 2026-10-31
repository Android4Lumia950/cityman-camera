# Arco — camera app for the Lumia 950 XL (cityman) LineageOS port

A Camera2 camera app written for the Microsoft Lumia 950 XL running our LineageOS 23.2
(Android 16) port. Manual controls sit on half-ring dials around the shutter button, in the
spirit of the old Windows Phone camera. The look is inspired by it, not copied: every icon,
the launcher icon and the layout are original, and the app uses no Microsoft or Nokia names,
fonts or assets.

Package `org.cityman.camera`, minSdk 33, targetSdk 36 (compileSdk 37.2, which the AndroidX
libraries in use require).

![Arco: arc dial, quick panel and settings](docs/screenshots/overview.png)

## What works

- Full-screen viewfinder, portrait and landscape (follows the system auto-rotate setting; with
  rotation locked, the icons turn instead).
- Rear (camera 0, IMX230) and front (camera 1, VS6955) cameras, switch button.
- Photo: JPEG (quality 95, high-quality noise reduction and sharpening) and, from the settings,
  an extra DNG with the full 5344×3744 RAW frame (Android `DngCreator`). Files go to
  `DCIM/Camera` as `IMG_yyyyMMdd_HHmmss_SSS.jpg/.dng`.
- Video: H.264 MP4 with AAC sound, 1080p at 20 Mbit/s on the rear camera (720p front),
  `VID_…mp4`. Frames go camera → YUV → GL → recorder: this ROM's gralloc refuses buffers that
  the camera writes and the encoder reads directly (`usage 0x30023`, BAD_VALUE), so the usual
  recorder surface in the capture session records no frames. Rear video runs at 15–20 fps (the
  sensor's single mode tops out at 20 fps).
- Video extras: AE badge to lock exposure and white balance (photo too), time-lapse (5×, 10×,
  30×, no sound), experimental gyroscope stabilisation (90 % crop moved and rotated against the
  shake; off by default, not yet tried while moving).
- Viewfinder tools, each optional: histogram, zebra on blown highlights, focus peaking, level.
  They only show or hide the measurements: the viewfinder analysis that sets the multi-frame
  exposure always runs (paused while a photo is taken).
- Self-timer (3 or 10 s, a second press cancels), shutter sound on/off.
- Zoom buttons (1× · 2× · 4×) above the shutter (beside it in landscape), plus pinch.
- Lock-screen launch: Arco handles the secure still-camera intent (power key double press, camera
  key while locked), shows over the keyguard and hides earlier photos; choose Arco once as the
  default camera with "Always". The last camera and mode are restored at launch.
- Settings: a quick panel by the cog button (multi-frame, self-timer, grid, DNG; time-lapse and
  stabilisation in video) and a full-screen page with sliding sections (photo, video,
  processing, general). The accent colour follows Android's dynamic (wallpaper) colour, or amber.
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
- Gallery thumbnail of the last photo or video: opens the gallery in review mode, so the camera
  album can be swiped through; the gallery app can be chosen in the settings.
- Release builds use R8 (1.7 MB APK) and are signed from `keystore.properties` (storeFile,
  storePassword, keyAlias, keyPassword; not in git) when present.

- Multi-frame RAW processing (see below), optional.

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

Extras: `dng`, `mf`, `frames`, `iso` (0 = auto), `shutter_us`, `wb` (Kelvin, 0 = auto),
`focus` (diopters, −1 = auto), `ev` (steps), `reset`, `mode` (`photo`/`video`), `zoom`,
`timelapse`, `stabilise`, `tools` (`h`/`z`/`p`), `zsl` (RAW in every viewfinder frame, for the
zero shutter lag study), `switch`, `shoot`. See `DebugCommands.kt`.

Unit tests (dial labels, Planckian locus, noise model fit) run on the host:

```bash
./gradlew testDebugUnitTest
```

## Code map

- `camera/CameraController.kt` — Camera2 session, viewfinder (SurfaceView), still/RAW capture,
  burst planning, manual controls, tap metering, zoom, gyroscope/accelerometer, settings.
- `camera/VideoPipeline.kt` — video frames YUV → GL → recorder, time-lapse, stabilisation.
- `camera/FrameAnalyzer.kt` — viewfinder analysis: histogram, clipping, zebra/peaking overlay.
- `processing/` — the multi-frame pipeline (`MultiFrameProcessor`, `Shaders`, `Gl`) and the
  parallel JPEG encoder (`ParallelJpeg`).
- `camera/CameraCaps.kt` — what each camera supports, stream sizes, frame-rate ranges.
- `camera/ColorCalibration.kt` — Kelvin → white balance gains and colour transform.
- `camera/MediaStoreWriter.kt` — saving to `DCIM/Camera` through MediaStore.
- `ui/Dials.kt` — dial stops, labels and how they map to the controls.
- `ui/ArcDial.kt` — the half-ring dial (Compose Canvas) and its drag handling.
- `ui/CameraScreen.kt` — layout for both orientations, chips, shutter bar, quick panel, overlays.
- `ui/SettingsScreen.kt` — the full-screen settings page.
- `ui/Glyphs.kt` — the app's line icons.

## Multi-frame processing

Optional (settings, or the **MF** badge at the top left). When it is on, the shutter takes a burst
of RAW frames and merges them on the GPU (OpenGL ES 3.1 compute shaders, `processing/`) into a
20 MP JPEG (`IMG_…_MF.jpg`) from the full 5344×3744 sensor, instead of the HAL's 13 MP JPEG.

1. **Exposure plan** (`CameraController.burstPlan`): under what auto exposure measured, by
   0.4–1.6 EV depending on how much of the viewfinder is blown out; short frames against hand
   shake: 1/30 s (shorter when the gyroscope sees shake and there is light to spare), longer
   (up to 1/10 s, the rear lens has OIS) only once ISO is at its manual maximum and 2× digital
   gain would not be enough. When the phone rests for 1.5 s (tripod, table) the burst becomes a
   night burst: up to 12 frames of up to 1/2 s (badge "NIGHT · STILL"). Fewer frames when the
   thermal headroom runs out. The HAL clamps manual ISO at 342 on the rear camera (auto exposure reaches ~1800 with
   ISP digital gain), so whatever is missing becomes digital gain after the merge, where the
   noise is lower. 6 frames, 8 in low light (or 4/6/8/12 from the settings). With
   manual ISO or shutter set, the burst uses those values. Still detection uses the gyroscope,
   and the accelerometer's averaged jitter while the gyroscope has not moved yet (this phone's
   gyroscope reports exact zeros at rest). Backlit scenes (more than 2 % blown in the
   viewfinder) add two highlight frames 2 EV shorter at the end of the burst.
2. **Merge**, frame by frame as they arrive (memory stays constant): black level, hot and dead
   pixel correction, Bayer 2×2 blocks packed into one texel; the sharpest of the first three
   frames (Laplacian energy) is the reference; grey pyramid (½, ¼, ¼); coarse-to-fine tile alignment (tiles
   16/8 px, L1 at the finest level, L2 above), with a sub-pixel refinement at the finest level
   (parabola through the costs) and bilinear sampling of each colour plane; per 2×2 block, choice between the tile's offset and
   the two nearest tiles' offsets, and a robustness weight from the local difference against the
   noise expected at that brightness; weighted accumulation against the reference. Highlight
   frames are scaled to the reference exposure and only merged where the reference is near white.
3. **Noise model**: measured on every burst from the first aligned pair (median absolute
   difference per brightness bin, fit of variance = a·x + b), since the HAL reports no noise
   profile.
4. **Finishing**: lens shading map from the capture result, white balance gains and colour
   matrix of the HAL's auto white balance, highlight clipping to a common level (no magenta
   highlights), bilateral spatial noise reduction guided by the noise model and the number of
   merged frames, Malvar-He-Cutler demosaic, colour noise reduction (full-resolution luminance
   with blurred chroma, added as a luminance difference), local tone mapping (fine and coarse
   log-luminance base layers, the fine one near strong edges; highlights compressed more than
   shadows are lifted, more so in backlit scenes), black point, highlight shoulder, saturation,
   sRGB, S curve, zoom crop (Catmull-Rom ×2 from 1.5×), luma unsharp mask, rotation to upright,
   conversion to NV21 on the GPU.

Every finishing step can be turned off in the settings: noise reduction, local tone mapping,
colour noise reduction, sharpening; "vivid colours" switches to a stronger look. With DNG on,
the merged RAW is saved too (`IMG_…_MF.dng`, black level and white level of the sensor,
reference frame metadata). The JPEG carries EXIF (camera, ISO, shutter, aperture, frames).

Timing on the phone (31/10/2026, 8 frames): about 0.3 s per frame for upload, alignment and
merge (overlapping the capture), about 1 s of GPU finishing, 0.25 s readback; the JPEG is
encoded in the background in about 0.2 s: four strips in parallel by the system YUV encoder
(standard Huffman tables, so they can be joined with restart markers). The shutter is free
again as soon as the GPU is done. With `PROFILE = true` in
`MultiFrameProcessor.kt` the log (`adb logcat -s ArcoMerge`) shows each stage.

Not done yet: see [ROADMAP.md](ROADMAP.md).

## Roadmap

Future work, in phases with exit criteria: [ROADMAP.md](ROADMAP.md) (Italian).

## Licence

GPL-3.0-or-later (see `LICENSE`). The app is GPL so that code from GPL projects such as
PhotonCamera and Open Camera can be adapted later, with attribution, if that turns out useful.
So far none has been: the multi-frame pipeline is written from the papers below.

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
- Multi-frame pipeline, from the published methods (no code reused): **Samuel W. Hasinoff et
  al.**, "Burst photography for high dynamic range and low-light imaging on mobile cameras"
  (SIGGRAPH Asia 2016) — pyramid tile alignment, merging against a reference frame,
  under-exposure with digital gain; **Bartlomiej Wronski et al.**, "Handheld multi-frame
  super-resolution" (SIGGRAPH 2019) — the idea of a per-pixel robustness weight from the local
  difference against the noise model; **Henrique S. Malvar, Li-wei He, Ross Cutler**,
  "High-quality linear interpolation for demosaicing of Bayer-patterned color images"
  (ICASSP 2004) — the demosaic kernels; **Frédo Durand, Julie Dorsey**, "Fast bilateral
  filtering for the display of high-dynamic-range images" (SIGGRAPH 2002) — base/detail local
  tone mapping.
- Other multi-frame projects looked at (nothing reused): **eszdman** —
  [PhotonCamera](https://github.com/eszdman/PhotonCamera) (GPL-3.0); **Timothy Brooks** —
  [hdr-plus](https://github.com/timothybrooks/hdr-plus) (Halide HDR+ implementation);
  **Mark Harman** — [Open Camera](https://opencamera.org.uk/) (GPL-3.0).
- Design inspiration: the Lumia camera app of Windows Phone / Windows 10 Mobile. Nothing of its
  artwork, fonts or names is used.
