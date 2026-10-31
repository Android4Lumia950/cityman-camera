package org.cityman.camera

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.cityman.camera.camera.CameraController
import org.cityman.camera.ui.Accent
import org.cityman.camera.ui.CameraScreen
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraController
    private lateinit var orientationListener: OrientationEventListener
    private var hasCamera by mutableStateOf(false)
    private var iconRotation by mutableFloatStateOf(0f)
    private var snappedOrientation = 0
    private var lastOrientation = OrientationEventListener.ORIENTATION_UNKNOWN

    // Fires for every rotation, including 90° to 270° which is not a configuration change.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            controller.displayRotationDegrees = displayDegrees()
            snappedOrientation = -1
            updateIconRotation()
        }
    }
    private var asked = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCamera = granted(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE
        }
        controller = CameraController(this)
        hasCamera = granted(Manifest.permission.CAMERA)
        handleLaunch(intent)

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                controller.deviceOrientation = orientation
                lastOrientation = orientation
                updateIconRotation()
            }
        }

        setContent {
            if (hasCamera) {
                CameraScreen(controller, iconRotation, ::openMedia, ::galleryApps)
            } else {
                PermissionScreen { requestPermissions() }
            }
        }
    }

    /**
     * The layout follows the device when auto-rotate is on; with rotation locked, the icons turn
     * instead. Orientation relative to the current layout, snapped with some hysteresis.
     */
    private fun updateIconRotation() {
        val o = lastOrientation
        if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
        val rel = (o + displayDegrees()) % 360
        val nearest = ((rel + 45) / 90 * 90) % 360
        val dist = abs(((rel - nearest) + 540) % 360 - 180)
        if (nearest != snappedOrientation && (dist < 30 || snappedOrientation == -1)) {
            snappedOrientation = nearest
            // Keep the angle continuous so the icons turn the short way.
            val target = -nearest.toFloat()
            var delta = (target - iconRotation) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            iconRotation += delta
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = DebugCommands.apply(intent, controller)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    /**
     * Lock-screen launches (power key double press, camera key while locked) show the camera
     * over the keyguard and keep earlier photos private; video and photo intents pick the mode.
     */
    private fun handleLaunch(intent: Intent?) {
        val secure = intent?.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        setShowWhenLocked(secure)
        setTurnScreenOn(secure)
        controller.setSecure(secure)
        when (intent?.action) {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> controller.setMode(org.cityman.camera.camera.CaptureMode.VIDEO)
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE ->
                controller.setMode(org.cityman.camera.camera.CaptureMode.PHOTO)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        controller.displayRotationDegrees = displayDegrees()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        orientationListener.enable()
        if (BuildConfig.DEBUG) {
            registerReceiver(debugReceiver, IntentFilter(DebugCommands.ACTION), RECEIVER_EXPORTED)
        }
        hasCamera = granted(Manifest.permission.CAMERA)
        if (hasCamera) controller.start() else if (!asked) requestPermissions()
    }

    override fun onPause() {
        if (BuildConfig.DEBUG) unregisterReceiver(debugReceiver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        orientationListener.disable()
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0 && hasCamera) controller.shutter()
                return true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                if (event.repeatCount == 0 && hasCamera) controller.focusKey()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_FOCUS -> true
        else -> super.onKeyUp(keyCode, event)
    }

    private fun requestPermissions() {
        asked = true
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun displayDegrees(): Int = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Opens the last photo or video in the gallery's review mode, which lets the user swipe
     * through the camera album (VIEW would show that single file only). With the phone locked,
     * the secure variant shows only what the camera gives it.
     */
    private fun openMedia(uri: Uri, video: Boolean) {
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val mime = if (video) "video/mp4" else "image/jpeg"
        val pkg = controller.state.value.galleryPackage
        val review = Intent(if (locked) MediaStore.ACTION_REVIEW_SECURE else MediaStore.ACTION_REVIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chosen gallery first (review mode, then plain view), then any app.
        val attempts = buildList {
            if (pkg != null) {
                add(Intent(review).setPackage(pkg))
                add(Intent(view).setPackage(pkg))
            }
            add(review)
            add(view)
        }
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
    }

    /** Apps that can show photos: (package, label), sorted by label. */
    private fun galleryApps(): List<Pair<String, String>> {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media/1"), "image/jpeg")
        return packageManager.queryIntentActivities(probe, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            BasicText(
                stringResource(R.string.permission_needed),
                style = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center),
                modifier = Modifier.widthIn(max = 420.dp),
            )
            BasicText(
                stringResource(R.string.permission_grant),
                style = TextStyle(color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .background(Accent, RoundedCornerShape(20.dp))
                    .clickable(onClick = onRequest)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}
