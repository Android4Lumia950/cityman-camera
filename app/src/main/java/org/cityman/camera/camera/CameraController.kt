package org.cityman.camera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class CaptureMode { PHOTO, VIDEO }

data class CameraState(
    val caps: CameraCaps? = null,
    val cameraIndex: Int = 0,
    val cameraCount: Int = 0,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val format: PhotoFormat = PhotoFormat.FULL,
    val saveDng: Boolean = false,
    val controls: ManualControls = ManualControls(),
    val live: LiveValues = LiveValues(),
    val previewSize: Size? = null,
    val zoom: Float = 1f,
    val capturing: Boolean = false,
    val recording: Boolean = false,
    val recordingSince: Long = 0L,
    val thumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    /** Tap point in normalised viewfinder coordinates, while a tap focus/metering lock is active. */
    val focusPoint: Pair<Float, Float>? = null,
    val shutterCount: Int = 0,
    val message: String? = null,
)

/**
 * Camera2 front end. All camera work happens on one handler thread; files are written on a
 * second one. The UI talks to it through the public methods (any thread) and reads [state].
 */
class CameraController(private val context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val camThread = HandlerThread("arco-camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val ioThread = HandlerThread("arco-io").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val camExecutor = Executor { camHandler.post(it) }
    private val store = MediaStoreWriter(context)
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state

    /** Back cameras first, then front ones. */
    private val cameraIds: List<String> = runCatching {
        manager.cameraIdList.sortedBy {
            if (manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraMetadata.LENS_FACING_FRONT) 1 else 0
        }
    }.getOrDefault(emptyList())

    // Device orientation in degrees (OrientationEventListener convention) and display rotation.
    @Volatile var deviceOrientation: Int = OrientationEventListener.ORIENTATION_UNKNOWN
    @Volatile var displayRotationDegrees: Int = 90

    // ---- main thread: viewfinder surface -------------------------------------------------
    private var holder: SurfaceHolder? = null
    private var surfaceSize: Size? = null
    private var wantedSize: Size? = null
    private var pendingGen = -1
    private var configuredGen = -1
    private var started = false

    val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) {
            holder = h
            wantedSize?.let { h.setFixedSize(it.width, it.height) }
        }

        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            holder = h
            surfaceSize = Size(width, height)
            maybeConfigure()
        }

        override fun surfaceDestroyed(h: SurfaceHolder) {
            holder = null
            surfaceSize = null
            configuredGen = -1
            // The camera must stop drawing into the surface before it goes away.
            runOnCamAndWait { closeSession() }
        }
    }

    private fun maybeConfigure() {
        val h = holder ?: return
        val want = wantedSize ?: return
        if (surfaceSize != want || pendingGen == configuredGen) return
        configuredGen = pendingGen
        val gen = pendingGen
        val surface = h.surface
        camHandler.post { createSession(surface, gen) }
    }

    // ---- camera thread state -----------------------------------------------------------
    private var device: CameraDevice? = null
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var caps: CameraCaps? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var recorderSurface: Surface? = null
    private var recorderSurfaceSize: Size? = null
    private var recorder: MediaRecorder? = null
    private var recordingUri: Uri? = null
    private var recordingFd: ParcelFileDescriptor? = null
    private var configGen = 0
    private var controls = ManualControls()
    private var zoom = 1f
    private var focusRegion: MeteringRectangle? = null
    private var meterRegion: MeteringRectangle? = null
    private var afState = CaptureResult.CONTROL_AF_STATE_INACTIVE
    private var autoIso = 100
    private var autoExposureNs = 33_000_000L
    private var lastLiveUpdate = 0L
    private var waitingAf = false
    private var afTriggeredForShot = false
    private var shot: Shot? = null

    private class Shot(val orientation: Int, val rawExpected: Boolean, val startedAt: Long) {
        var jpegDone = false
        var rawDone = false
        var raw: Image? = null
        var result: TotalCaptureResult? = null
    }

    // ---- lifecycle ----------------------------------------------------------------------

    fun start() {
        started = true
        _state.update { it.copy(cameraCount = cameraIds.size) }
        camHandler.post { openCamera() }
        ioHandler.post { loadLatestThumbnail() }
    }

    fun stop() {
        started = false
        wantedSize = null
        configuredGen = -1
        runOnCamAndWait(3000) { closeCamera() }
    }

    fun release() {
        camHandler.post {
            recorderSurface?.release()
            recorderSurface = null
        }
        camThread.quitSafely()
        ioThread.quitSafely()
        sound.release()
    }

    // ---- public actions -----------------------------------------------------------------

    fun switchCamera() = camHandler.post {
        if (cameraIds.size < 2) return@post
        closeCamera()
        controls = ManualControls()
        zoom = 1f
        _state.update {
            it.copy(
                cameraIndex = (it.cameraIndex + 1) % cameraIds.size,
                controls = controls, zoom = 1f, focusPoint = null, live = LiveValues(),
            )
        }
        if (started) openCamera()
    }

    fun setMode(mode: CaptureMode) = camHandler.post {
        if (_state.value.mode == mode) return@post
        if (recorder != null) stopRecording()
        clearTapRegions()
        _state.update { it.copy(mode = mode) }
        reconfigure()
    }

    fun setFormat(format: PhotoFormat) = camHandler.post {
        if (_state.value.format == format) return@post
        _state.update { it.copy(format = format) }
        if (_state.value.mode == CaptureMode.PHOTO) reconfigure()
    }

    fun setSaveDng(enabled: Boolean) = camHandler.post {
        if (_state.value.saveDng == enabled) return@post
        _state.update { it.copy(saveDng = enabled) }
        if (_state.value.mode == CaptureMode.PHOTO) reconfigure()
    }

    fun setControls(c: ManualControls) = camHandler.post {
        // Entering a manual value starts from what auto exposure is doing right now.
        controls = c
        if (c.focusDiopters != null) {
            focusRegion = null
            _state.update { it.copy(focusPoint = if (meterRegion != null) it.focusPoint else null) }
        }
        _state.update { it.copy(controls = c) }
        startRepeating()
    }

    fun setZoom(z: Float) = camHandler.post {
        val c = caps ?: return@post
        zoom = z.coerceIn(1f, c.maxZoom)
        _state.update { it.copy(zoom = zoom) }
        startRepeating()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Focus and meter on a viewfinder point, given in normalised (0..1) view coordinates. */
    fun tapToFocus(nx: Float, ny: Float) = camHandler.post {
        val c = caps ?: return@post
        val preview = _state.value.previewSize ?: return@post
        val crop = cropRegion(c)
        var u = nx.coerceIn(0f, 1f)
        var v = ny.coerceIn(0f, 1f)
        if (c.front) u = 1f - u
        repeat(previewRotation(c) / 90) {
            val nu = v
            val nv = 1f - u
            u = nu
            v = nv
        }
        val pa = preview.width.toFloat() / preview.height
        var vw = crop.width().toFloat()
        var vh = crop.height().toFloat()
        if (vw / vh > pa) vw = vh * pa else vh = vw / pa
        val left = crop.centerX() - vw / 2f
        val top = crop.centerY() - vh / 2f
        val sx = left + u * vw
        val sy = top + v * vh
        val half = min(vw, vh) * 0.08f
        val aw = c.activeArray.width()
        val ah = c.activeArray.height()
        val r = Rect(
            (sx - half).roundToInt().coerceIn(0, aw - 1),
            (sy - half).roundToInt().coerceIn(0, ah - 1),
            (sx + half).roundToInt().coerceIn(1, aw),
            (sy + half).roundToInt().coerceIn(1, ah),
        )
        val region = MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        meterRegion = if (c.maxAeRegions > 0) region else null
        focusRegion = if (c.hasAutoFocus && c.maxAfRegions > 0 && controls.focusDiopters == null) region else null
        _state.update { it.copy(focusPoint = nx to ny) }
        startRepeating()
        if (focusRegion != null) triggerAf()
    }

    fun clearFocusPoint() = camHandler.post {
        clearTapRegions()
        startRepeating()
    }

    /** Half press of the camera key. */
    fun focusKey() = camHandler.post {
        val c = caps ?: return@post
        if (c.hasAutoFocus && controls.focusDiopters == null) triggerAf()
    }

    fun shutter() = camHandler.post {
        when (_state.value.mode) {
            CaptureMode.PHOTO -> takePicture()
            CaptureMode.VIDEO -> if (recorder == null) startRecording() else stopRecording()
        }
    }

    // ---- camera device / session --------------------------------------------------------

    private fun openCamera() {
        if (device != null || opening || !started || cameraIds.isEmpty()) return
        val index = _state.value.cameraIndex.coerceIn(0, cameraIds.size - 1)
        val id = cameraIds[index]
        val c = try {
            CameraCaps(id, manager.getCameraCharacteristics(id))
        } catch (e: Exception) {
            report("Camera $id: ${e.message}")
            return
        }
        caps = c
        _state.update { it.copy(caps = c) }
        try {
            opening = true
            manager.openCamera(id, camExecutor, deviceCallback)
        } catch (e: SecurityException) {
            opening = false
            report("Camera permission missing")
        } catch (e: CameraAccessException) {
            opening = false
            report("Camera $id unavailable: ${e.message}")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            if (!started) {
                camera.close()
                return
            }
            device = camera
            _state.update { it.copy(message = null) }
            reconfigure()
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            camera.close()
            if (device == camera) device = null
            closeSession()
            report("The camera is in use by another app")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            camera.close()
            if (device == camera) device = null
            closeSession()
            report("Camera error $error")
        }
    }

    private fun closeCamera() {
        if (recorder != null) stopRecording()
        closeSession()
        device?.close()
        device = null
        waitingAf = false
        camHandler.removeCallbacks(afTimeout)
    }

    private fun closeSession() {
        if (recorder != null) stopRecording()
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        previewSurface = null
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        finishShot(force = true)
    }

    /** New stream set: pick sizes, resize the viewfinder surface, then [createSession]. */
    private fun reconfigure() {
        val c = caps ?: return
        if (device == null) return
        closeSession()
        configGen++
        val gen = configGen
        val st = _state.value
        val preview = if (st.mode == CaptureMode.VIDEO) c.previewSize(c.videoSize()) else c.previewSize(c.jpegSize(st.format))
        _state.update { it.copy(previewSize = preview) }
        mainHandler.post {
            wantedSize = preview
            pendingGen = gen
            holder?.setFixedSize(preview.width, preview.height)
            maybeConfigure()
        }
    }

    private fun createSession(surface: Surface, gen: Int) {
        val dev = device ?: return
        val c = caps ?: return
        if (gen != configGen || !surface.isValid) return
        val st = _state.value
        previewSurface = surface
        val outputs = mutableListOf(OutputConfiguration(surface))
        var withRaw = false
        if (st.mode == CaptureMode.PHOTO) {
            val js = c.jpegSize(st.format)
            jpegReader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2).also {
                it.setOnImageAvailableListener(::onJpeg, ioHandler)
                outputs += OutputConfiguration(it.surface)
            }
            val rs = c.rawSize
            if (st.saveDng && rs != null) {
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2).also {
                    it.setOnImageAvailableListener(::onRaw, ioHandler)
                    outputs += OutputConfiguration(it.surface)
                }
                withRaw = true
            }
        } else {
            val rs = ensureRecorderSurface(c.videoSize()) ?: return
            outputs += OutputConfiguration(rs)
        }
        Log.i(TAG, "session gen=$gen camera=${c.id} mode=${st.mode} preview=${_state.value.previewSize} raw=$withRaw")
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, camExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != configGen || device == null) {
                        s.close()
                        return
                    }
                    session = s
                    startRepeating()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "session configuration failed (raw=$withRaw)")
                    if (gen != configGen) return
                    if (withRaw) {
                        _state.update { it.copy(saveDng = false) }
                        report("DNG is not available together with this photo size")
                        reconfigure()
                    } else {
                        report("Could not start the camera session")
                    }
                }
            },
        )
        try {
            dev.createCaptureSession(config)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession", e)
            report("Could not start the camera session: ${e.message}")
        }
    }

    private fun previewRequest(): CaptureRequest.Builder? {
        val dev = device ?: return null
        val surface = previewSurface ?: return null
        val recording = recorder != null
        val b = dev.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        if (recording) recorderSurface?.let { b.addTarget(it) }
        applyControls(b, still = false)
        return b
    }

    private fun startRepeating() {
        val s = session ?: return
        val b = previewRequest() ?: return
        try {
            s.setRepeatingRequest(b.build(), repeatingCallback, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingRequest", e)
        }
    }

    private fun triggerAf() {
        val s = session ?: return
        val b = previewRequest() ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, camHandler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "AF trigger", e)
        }
    }

    private fun cancelAfTrigger() {
        val s = session ?: return
        val b = previewRequest() ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, camHandler)
        } catch (_: Exception) {
        }
    }

    private fun clearTapRegions() {
        focusRegion = null
        meterRegion = null
        _state.update { it.copy(focusPoint = null) }
    }

    // ---- request settings ---------------------------------------------------------------

    private fun applyControls(b: CaptureRequest.Builder, still: Boolean) {
        val c = caps ?: return
        val video = _state.value.mode == CaptureMode.VIDEO
        val m = controls
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Focus
        val fd = m.focusDiopters
        when {
            fd != null && c.hasFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd.coerceIn(0f, c.minFocusDiopters))
            }
            focusRegion != null -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRegion))
            }
            else -> {
                val wanted = if (video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                val mode = when {
                    wanted in c.afModes -> wanted
                    CameraMetadata.CONTROL_AF_MODE_AUTO in c.afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                }
                b.set(CaptureRequest.CONTROL_AF_MODE, mode)
            }
        }

        // Exposure
        if ((m.iso == null && m.exposureNs == null) || !c.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, m.evSteps.coerceIn(c.evRange.lower, c.evRange.upper))
            c.fpsRange(video)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            meterRegion?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        } else {
            val (iso, exposure) = manualExposure(c, m)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (video) max(exposure, 33_333_333L) else exposure)
        }

        // White balance
        val k = m.wbKelvin
        val cal = c.color
        if (k != null && cal != null && c.manualPostProcessing) {
            val corr = cal.forKelvin(k)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, corr.gains)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, corr.transform)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }

        b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(c))

        if (still) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(c))
        }
    }

    /**
     * ISO and shutter for manual exposure. When only one of them is set, the other keeps the
     * exposure that auto exposure last measured, shifted by the exposure compensation.
     */
    private fun manualExposure(c: CameraCaps, m: ManualControls): Pair<Int, Long> {
        val isoRange = c.isoRange
        val expRange = c.exposureRange
        val ev = 2.0.pow(m.evSteps * c.evStep.toDouble())
        val product = autoIso.toDouble() * autoExposureNs * ev
        val iso = m.iso ?: (product / (m.exposureNs ?: autoExposureNs)).roundToInt()
        val clampedIso = if (isoRange != null && m.iso == null) iso.coerceIn(isoRange.lower, isoRange.upper) else iso
        val exposure = m.exposureNs ?: (product / clampedIso).roundToLong().coerceAtMost(MAX_AUTO_SHUTTER_NS)
        val clampedExp = if (expRange != null) exposure.coerceIn(expRange.lower, expRange.upper) else exposure
        return clampedIso to clampedExp
    }

    private fun cropRegion(c: CameraCaps): Rect {
        val w = c.activeArray.width()
        val h = c.activeArray.height()
        val z = zoom.coerceIn(1f, max(1f, c.maxZoom))
        val cw = (w / z).roundToInt()
        val ch = (h / z).roundToInt()
        val left = (w - cw) / 2
        val top = (h - ch) / 2
        return Rect(left, top, left + cw, top + ch)
    }

    /** Clockwise rotation from the sensor image to the viewfinder on this display. */
    private fun previewRotation(c: CameraCaps): Int =
        if (c.front) (c.sensorOrientation + displayRotationDegrees) % 360
        else (c.sensorOrientation - displayRotationDegrees + 360) % 360

    private fun jpegOrientation(c: CameraCaps): Int {
        var d = deviceOrientation
        if (d == OrientationEventListener.ORIENTATION_UNKNOWN) d = (360 - displayRotationDegrees) % 360
        d = ((d + 45) / 90 * 90) % 360
        if (c.front) d = -d
        return (c.sensorOrientation + d + 360) % 360
    }

    // ---- results ------------------------------------------------------------------------

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result[CaptureResult.SENSOR_SENSITIVITY]
            val exp = result[CaptureResult.SENSOR_EXPOSURE_TIME]
            if (result[CaptureResult.CONTROL_AE_MODE] == CameraMetadata.CONTROL_AE_MODE_ON && iso != null && exp != null && iso > 0 && exp > 0) {
                autoIso = iso
                autoExposureNs = exp
            }
            val af = result[CaptureResult.CONTROL_AF_STATE] ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
            afState = af
            if (waitingAf && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                waitingAf = false
                fireStill()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveUpdate < 150) return
            lastLiveUpdate = now
            val awbAuto = result[CaptureResult.CONTROL_AWB_MODE] == CameraMetadata.CONTROL_AWB_MODE_AUTO
            val gains = result[CaptureResult.COLOR_CORRECTION_GAINS]
            val kelvin = if (awbAuto && gains != null) caps?.color?.kelvinFor(gains) else null
            _state.update {
                it.copy(
                    live = LiveValues(
                        iso = iso ?: 0,
                        exposureNs = exp ?: 0,
                        focusDiopters = result[CaptureResult.LENS_FOCUS_DISTANCE] ?: 0f,
                        kelvin = kelvin,
                        afState = af,
                    ),
                )
            }
        }
    }

    // ---- photo --------------------------------------------------------------------------

    private val afTimeout = Runnable {
        if (waitingAf) {
            waitingAf = false
            fireStill()
        }
    }

    private fun takePicture() {
        val c = caps ?: return
        if (session == null || jpegReader == null) return
        val current = shot
        if (current != null) {
            if (SystemClock.elapsedRealtime() - current.startedAt < 15_000) return
            finishShot(force = true)
        }
        shot = Shot(jpegOrientation(c), rawReader != null, SystemClock.elapsedRealtime())
        _state.update { it.copy(capturing = true) }
        val needAf = c.hasAutoFocus && controls.focusDiopters == null && focusRegion == null &&
            afState != CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED &&
            afState != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        if (needAf) {
            afTriggeredForShot = true
            waitingAf = true
            triggerAf()
            camHandler.postDelayed(afTimeout, 1200)
        } else {
            fireStill()
        }
    }

    private fun fireStill() {
        camHandler.removeCallbacks(afTimeout)
        val s = session
        val dev = device
        val jpeg = jpegReader
        val preview = previewSurface
        if (s == null || dev == null || jpeg == null || preview == null) {
            finishShot(force = true)
            return
        }
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jpeg.surface)
            rawReader?.let { b.addTarget(it.surface) }
            b.addTarget(preview)
            applyControls(b, still = true)
            s.capture(b.build(), stillCallback, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "still capture", e)
            report("Capture failed: ${e.message}")
            finishShot(force = true)
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            sound.play(MediaActionSound.SHUTTER_CLICK)
            _state.update { it.copy(shutterCount = it.shutterCount + 1) }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val current = shot
            if (current != null) {
                synchronized(current) { current.result = result }
                ioHandler.post { writeDngIfReady(current) }
            }
            if (afTriggeredForShot) {
                afTriggeredForShot = false
                cancelAfTrigger()
            }
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Log.e(TAG, "still capture failed: reason ${failure.reason}")
            report("Capture failed")
            if (afTriggeredForShot) {
                afTriggeredForShot = false
                cancelAfTrigger()
            }
            finishShot(force = true)
        }
    }

    private fun onJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }
        val current = shot
        val orientation = current?.orientation ?: 0
        val name = store.baseName("IMG")
        try {
            val uri = store.createImage("$name.jpg", "image/jpeg") ?: error("MediaStore insert failed")
            store.write(uri) { it.write(bytes) }
            store.publish(uri)
            val thumb = MediaStoreWriter.thumbnailFromJpeg(bytes)
            _state.update { it.copy(thumbnail = thumb ?: it.thumbnail, lastUri = uri, lastIsVideo = false) }
            Log.i(TAG, "saved $uri (${bytes.size} bytes, orientation $orientation)")
        } catch (e: Exception) {
            Log.e(TAG, "save JPEG", e)
            report("Could not save the photo: ${e.message}")
        }
        if (current != null) {
            synchronized(current) { current.jpegDone = true }
            camHandler.post { if (shot === current) finishShot(force = false) }
        }
    }

    private fun onRaw(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        val current = shot
        if (current == null) {
            image.close()
            return
        }
        synchronized(current) { current.raw = image }
        writeDngIfReady(current)
    }

    /** io thread */
    private fun writeDngIfReady(current: Shot) {
        val (raw, result) = synchronized(current) {
            val r = current.raw ?: return
            val res = current.result ?: return
            current.raw = null
            r to res
        }
        val c = caps
        try {
            if (c != null) {
                val uri = store.createImage(store.baseName("IMG") + ".dng", "image/x-adobe-dng") ?: error("MediaStore insert failed")
                DngCreator(c.chars, result).use { dng ->
                    dng.setOrientation(exifOrientation(current.orientation))
                    store.write(uri) { dng.writeImage(it, raw) }
                }
                store.publish(uri)
                Log.i(TAG, "saved $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "save DNG", e)
            report("Could not save the DNG: ${e.message}")
        } finally {
            raw.close()
        }
        synchronized(current) { current.rawDone = true }
        camHandler.post { if (shot === current) finishShot(force = false) }
    }

    /** cam thread */
    private fun finishShot(force: Boolean) {
        val current = shot ?: return
        val done = synchronized(current) { current.jpegDone && (!current.rawExpected || current.rawDone) }
        if (!done && !force) return
        if (force) synchronized(current) { current.raw?.close(); current.raw = null }
        shot = null
        waitingAf = false
        camHandler.removeCallbacks(afTimeout)
        _state.update { it.copy(capturing = false) }
    }

    // ---- video --------------------------------------------------------------------------

    private fun ensureRecorderSurface(size: Size): Surface? {
        if (recorderSurface != null && recorderSurfaceSize == size) return recorderSurface
        recorderSurface?.release()
        recorderSurface = null
        return try {
            val surface = MediaCodec.createPersistentInputSurface()
            // A throwaway recorder gives the persistent surface its buffer size.
            val dummy = File(context.cacheDir, "sizing.mp4")
            MediaRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(dummy.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(size.width, size.height)
                setVideoFrameRate(30)
                setInputSurface(surface)
                prepare()
                release()
            }
            dummy.delete()
            recorderSurface = surface
            recorderSurfaceSize = size
            surface
        } catch (e: Exception) {
            Log.e(TAG, "recorder surface", e)
            report("Video recording is not available: ${e.message}")
            null
        }
    }

    private fun startRecording() {
        val c = caps ?: return
        val surface = recorderSurface ?: return
        if (session == null || recorder != null) return
        val size = c.videoSize()
        val audio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val uri = store.createVideo(store.baseName("VID") + ".mp4") ?: run {
            report("Could not create the video file")
            return
        }
        val fd = store.openFd(uri)
        val fps = c.fpsRange(video = true)?.upper ?: 30
        val r = MediaRecorder(context)
        try {
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(if (size.width >= 1920) 20_000_000 else 10_000_000)
            r.setVideoFrameRate(fps)
            r.setVideoSize(size.width, size.height)
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(192_000)
                r.setAudioSamplingRate(48_000)
            }
            r.setOrientationHint(jpegOrientation(c))
            r.setInputSurface(surface)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "start recording", e)
            r.release()
            fd.close()
            store.discard(uri)
            report("Could not start recording: ${e.message}")
            return
        }
        recorder = r
        recordingUri = uri
        recordingFd = fd
        startRepeating()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        _state.update { it.copy(recording = true, recordingSince = SystemClock.elapsedRealtime()) }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        startRepeating()
        val uri = recordingUri
        val ok = try {
            r.stop()
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop recording", e)
            false
        }
        r.release()
        recordingFd?.close()
        recordingFd = null
        recordingUri = null
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        _state.update { it.copy(recording = false) }
        if (uri != null) {
            if (ok) {
                store.publish(uri)
                ioHandler.post {
                    val thumb = store.thumbnail(uri, true)
                    _state.update { it.copy(thumbnail = thumb ?: it.thumbnail, lastUri = uri, lastIsVideo = true) }
                }
            } else {
                store.discard(uri)
                report("The video was too short and was not saved")
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun loadLatestThumbnail() {
        if (_state.value.lastUri != null) return
        val (uri, video) = store.latest() ?: return
        val thumb = store.thumbnail(uri, video) ?: return
        _state.update { if (it.lastUri == null) it.copy(thumbnail = thumb, lastUri = uri, lastIsVideo = video) else it }
    }

    private fun report(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(message = msg) }
    }

    private fun runOnCamAndWait(timeoutMs: Long = 1000, block: () -> Unit) {
        if (Looper.myLooper() == camThread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        camHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "ArcoCamera"
        private const val MAX_AUTO_SHUTTER_NS = 250_000_000L

        fun exifOrientation(degrees: Int) = when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
