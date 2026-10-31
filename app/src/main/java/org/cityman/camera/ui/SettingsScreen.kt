package org.cityman.camera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.cityman.camera.BuildConfig
import org.cityman.camera.R
import org.cityman.camera.camera.CameraController
import org.cityman.camera.camera.CameraState
import org.cityman.camera.camera.PhotoFormat
import java.util.Locale

private val Dim = Color.White.copy(alpha = 0.55f)
private val TitleStyle = TextStyle(color = Color.White, fontSize = 20.sp)
private val DescStyle = TextStyle(color = Dim, fontSize = 14.sp, lineHeight = 18.sp)
private val GroupStyle get() = TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)

/**
 * Full-screen settings: a small app name, big lower-case section headers that slide sideways
 * (tap one or swipe the page), and large rows with an explanation under each title.
 */
@Composable
fun SettingsScreen(
    controller: CameraController,
    state: CameraState,
    galleryApps: () -> List<Pair<String, String>>,
    initialPage: Int = 0,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val titles = listOf(
        stringResource(R.string.page_photo),
        stringResource(R.string.page_processing),
        stringResource(R.string.page_general),
    )
    val pager = rememberPagerState(initialPage = initialPage) { titles.size }
    val scope = rememberCoroutineScope()
    val headerScroll = rememberScrollState()
    // Keep the current header at the left edge, as it slides with the page.
    LaunchedEffect(pager.currentPage) {
        headerScroll.animateScrollTo(if (pager.currentPage == 0) 0 else headerScroll.maxValue * pager.currentPage / (titles.size - 1))
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(start = 20.dp, top = 18.dp)) {
        BasicText(
            stringResource(R.string.app_name).uppercase(Locale.getDefault()) + " · " + stringResource(R.string.settings_title),
            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
            modifier = Modifier.clickable(onClick = onClose).padding(vertical = 4.dp),
        )
        Row(Modifier.horizontalScroll(headerScroll).padding(top = 2.dp, bottom = 10.dp)) {
            titles.forEachIndexed { i, t ->
                BasicText(
                    t,
                    style = TextStyle(
                        color = if (pager.currentPage == i) Color.White else Color.White.copy(alpha = 0.3f),
                        fontSize = 44.sp, fontWeight = FontWeight.Light,
                    ),
                    modifier = Modifier
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(end = 26.dp),
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                when (page) {
                    0 -> PhotoPage(controller, state)
                    1 -> ProcessingPage(controller, state)
                    else -> GeneralPage(controller, state, galleryApps)
                }
            }
        }
    }
}

@Composable
private fun PhotoPage(controller: CameraController, state: CameraState) {
    val caps = state.caps
    if (caps != null) {
        val formats = PhotoFormat.entries.distinctBy { caps.jpegSize(it) }
        Choice(
            title = stringResource(R.string.settings_format_title),
            desc = null,
            options = formats.map { f ->
                val size = caps.jpegSize(f)
                val mp = size.width.toLong() * size.height / 1_000_000.0
                val name = when (f) {
                    PhotoFormat.FULL -> stringResource(R.string.format_full)
                    PhotoFormat.RATIO_4_3 -> "4:3"
                    PhotoFormat.RATIO_16_9 -> "16:9"
                }
                f to String.format(Locale.US, "%s · %.0f MP", name, mp)
            },
            selected = formats.firstOrNull { caps.jpegSize(it) == caps.jpegSize(state.format) },
            onSelect = { controller.setFormat(it) },
        )
    }
    if (caps?.rawSize != null) {
        Toggle(
            stringResource(R.string.settings_dng_title), stringResource(R.string.settings_dng_desc),
            state.saveDng,
        ) { controller.setSaveDng(it) }
    }
    Choice(
        title = stringResource(R.string.settings_timer_title),
        desc = null,
        options = listOf(0 to stringResource(R.string.settings_off), 3 to "3 s", 10 to "10 s"),
        selected = state.timerSeconds,
        onSelect = { controller.setTimer(it) },
    )
    Toggle(stringResource(R.string.settings_grid), stringResource(R.string.settings_grid_desc), state.grid) { controller.setGrid(it) }
    Group(stringResource(R.string.group_viewfinder))
    BasicText(stringResource(R.string.settings_tools_desc), style = DescStyle)
    Toggle(stringResource(R.string.settings_histogram), null, state.showHistogram) {
        controller.setAnalysisTools(it, state.zebra, state.peaking)
    }
    Toggle(stringResource(R.string.settings_zebra), null, state.zebra) {
        controller.setAnalysisTools(state.showHistogram, it, state.peaking)
    }
    Toggle(stringResource(R.string.settings_peaking), null, state.peaking) {
        controller.setAnalysisTools(state.showHistogram, state.zebra, it)
    }
}

@Composable
private fun ProcessingPage(controller: CameraController, state: CameraState) {
    val available = state.caps?.rawSize != null
    Toggle(
        stringResource(R.string.settings_mf_title), stringResource(R.string.settings_mf_desc),
        state.multiFrame, enabled = available,
    ) { controller.setMultiFrame(it) }
    val on = state.multiFrame && available
    Choice(
        title = stringResource(R.string.settings_mf_frames_title),
        desc = stringResource(R.string.settings_mf_frames_desc),
        options = listOf(0 to stringResource(R.string.settings_auto), 4 to "4", 6 to "6", 8 to "8", 12 to "12"),
        selected = state.mfFrames,
        enabled = on,
        horizontal = true,
        onSelect = { controller.setMultiFrameFrames(it) },
    )
    val o = state.mfOptions
    Group(stringResource(R.string.group_look))
    Toggle(stringResource(R.string.settings_mf_vivid), stringResource(R.string.settings_mf_vivid_desc), o.vivid, enabled = on) {
        controller.setMultiFrameOptions(o.copy(vivid = it))
    }
    Toggle(stringResource(R.string.settings_mf_tone), stringResource(R.string.settings_mf_tone_desc), o.localTone, enabled = on) {
        controller.setMultiFrameOptions(o.copy(localTone = it))
    }
    Group(stringResource(R.string.group_detail))
    Toggle(stringResource(R.string.settings_mf_denoise), stringResource(R.string.settings_mf_denoise_desc), o.denoise, enabled = on) {
        controller.setMultiFrameOptions(o.copy(denoise = it))
    }
    Toggle(stringResource(R.string.settings_mf_chroma), stringResource(R.string.settings_mf_chroma_desc), o.chromaDenoise, enabled = on) {
        controller.setMultiFrameOptions(o.copy(chromaDenoise = it))
    }
    Toggle(stringResource(R.string.settings_mf_sharpen), stringResource(R.string.settings_mf_sharpen_desc), o.sharpen, enabled = on) {
        controller.setMultiFrameOptions(o.copy(sharpen = it))
    }
}

@Composable
private fun GeneralPage(controller: CameraController, state: CameraState, galleryApps: () -> List<Pair<String, String>>) {
    Toggle(stringResource(R.string.settings_sound), stringResource(R.string.settings_sound_desc), state.shutterSound) {
        controller.setShutterSound(it)
    }
    Toggle(stringResource(R.string.settings_accent), stringResource(R.string.settings_accent_desc), state.systemAccent) {
        controller.setSystemAccent(it)
    }
    val apps = remember { galleryApps() }
    val options = listOf<Pair<String?, String>>(null to stringResource(R.string.settings_gallery_default)) + apps
    Choice(
        title = stringResource(R.string.settings_gallery_title),
        desc = stringResource(R.string.settings_gallery_desc),
        options = options,
        selected = state.galleryPackage?.takeIf { p -> apps.any { it.first == p } },
        onSelect = { controller.setGallery(it) },
    )
    Group(stringResource(R.string.group_about))
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", style = TitleStyle)
        BasicText(stringResource(R.string.about_desc), style = DescStyle)
        BasicText(
            "github.com/giannoccarol/cityman-camera",
            style = TextStyle(color = Accent, fontSize = 15.sp),
            modifier = Modifier.clickable { uri.openUri("https://github.com/giannoccarol/cityman-camera") }.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun Group(text: String) {
    BasicText(text.lowercase(Locale.getDefault()), style = GroupStyle, modifier = Modifier.padding(top = 6.dp))
}

/** A setting with an on/off switch; the switch shows its state in words, as on a phone of the time. */
@Composable
internal fun Toggle(
    title: String,
    desc: String?,
    on: Boolean,
    enabled: Boolean = true,
    titleStyle: TextStyle = TitleStyle,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(title, style = titleStyle)
            if (desc != null) BasicText(desc, style = DescStyle)
        }
        Spacer(Modifier.width(14.dp))
        BasicText(
            stringResource(if (on) R.string.switch_on else R.string.switch_off),
            style = TextStyle(color = Color.White, fontSize = 16.sp),
            modifier = Modifier.width(34.dp),
        )
        // Rectangular switch: outlined track, accent fill when on, solid thumb.
        Box(
            Modifier.size(width = 54.dp, height = 22.dp).border(2.dp, Color.White),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            if (on) {
                Box(Modifier.fillMaxSize().padding(4.dp).padding(end = 12.dp).background(Accent))
            }
            Box(Modifier.width(12.dp).fillMaxHeight().background(Color.White))
        }
    }
}

/** A setting with a few choices, the chosen one in the accent colour. */
@Composable
internal fun <T> Choice(
    title: String,
    desc: String?,
    options: List<Pair<T, String>>,
    selected: T?,
    enabled: Boolean = true,
    horizontal: Boolean = false,
    titleStyle: TextStyle = TitleStyle,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.alpha(if (enabled) 1f else 0.35f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(title, style = titleStyle)
        if (desc != null) BasicText(desc, style = DescStyle)
        val item: @Composable (Pair<T, String>) -> Unit = { (value, label) ->
            val isSelected = value == selected
            BasicText(
                label,
                style = TextStyle(
                    color = if (isSelected) Accent else Color.White.copy(alpha = 0.8f),
                    fontSize = 18.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 6.dp, horizontal = if (horizontal) 0.dp else 0.dp)
                    .padding(end = if (horizontal) 22.dp else 0.dp),
            )
        }
        if (horizontal) {
            Row { options.forEach { item(it) } }
        } else {
            Column(Modifier.padding(start = 2.dp)) { options.forEach { item(it) } }
        }
        Spacer(Modifier.height(2.dp))
    }
}
