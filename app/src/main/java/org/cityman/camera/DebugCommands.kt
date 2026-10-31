package org.cityman.camera

import android.content.Intent
import org.cityman.camera.camera.CameraController
import org.cityman.camera.camera.CaptureMode
import org.cityman.camera.camera.ManualControls

/**
 * Debug builds only: drive the camera from adb without touching the screen, while the app is
 * in the foreground, e.g.
 * `adb shell am broadcast -a org.cityman.camera.DEBUG --ez dng true --ei iso 200 --ez shoot true`.
 * Extras: dng (bool), mf (bool), frames (int, 0 = auto), iso (int, 0 = auto), shutter_us (long, 0 = auto), wb (int K, 0 = auto),
 * focus (float diopters, -1 = auto), ev (int steps), reset (bool), mode (photo|video), shoot (bool).
 */
object DebugCommands {
    const val ACTION = "org.cityman.camera.DEBUG"

    fun apply(intent: Intent, controller: CameraController) {
        val extras = intent.extras ?: return
        val st = controller.state.value
        if (extras.containsKey("dng")) controller.setSaveDng(extras.getBoolean("dng"))
        if (extras.containsKey("mf")) controller.setMultiFrame(extras.getBoolean("mf"))
        if (extras.containsKey("frames")) controller.setMultiFrameFrames(extras.getInt("frames"))
        when (extras.getString("mode")) {
            "photo" -> controller.setMode(CaptureMode.PHOTO)
            "video" -> controller.setMode(CaptureMode.VIDEO)
        }
        var c = if (extras.getBoolean("reset")) ManualControls() else st.controls
        if (extras.containsKey("iso")) c = c.copy(iso = extras.getInt("iso").takeIf { it > 0 })
        if (extras.containsKey("shutter_us")) c = c.copy(exposureNs = extras.getLong("shutter_us").takeIf { it > 0 }?.times(1000))
        if (extras.containsKey("wb")) c = c.copy(wbKelvin = extras.getInt("wb").takeIf { it > 0 }?.toFloat())
        if (extras.containsKey("focus")) c = c.copy(focusDiopters = extras.getFloat("focus").takeIf { it >= 0f })
        if (extras.containsKey("ev")) c = c.copy(evSteps = extras.getInt("ev"))
        if (c != st.controls) controller.setControls(c)
        if (extras.getBoolean("shoot")) controller.shutter()
    }
}
