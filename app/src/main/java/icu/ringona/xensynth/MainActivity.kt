package icu.ringona.xensynth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import icu.ringona.xensynth.audio.NativeAudioController
import icu.ringona.xensynth.midi.MidiDeviceInputManager
import icu.ringona.xensynth.midi.MidiInputDevice
import icu.ringona.xensynth.midi.MidiInputEvent
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.view.CanvasWaterfallView
import icu.ringona.xensynth.view.FrostedGlassFrameLayout
import icu.ringona.xensynth.view.FrostedRulerOverlayView
import icu.ringona.xensynth.view.HighRefreshRatePolicy
import icu.ringona.xensynth.view.RenderFramePacer
import icu.ringona.xensynth.view.ScaleGuide
import icu.ringona.xensynth.view.WaterfallBackdropView
import icu.ringona.xensynth.view.WaterfallDisplayState
import icu.ringona.xensynth.view.WaterfallLayout
import icu.ringona.xensynth.view.WaterfallMetrics
import icu.ringona.xensynth.view.WaterfallPreviewNote
import icu.ringona.xensynth.view.WaterfallSurface
import icu.ringona.xensynth.view.WaterfallVolumeGestureFeedback
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val COMPACT_SLIDER_WIDTH_DP = 184
private const val TOOLBAR_TITLE_MAX_WIDTH_DP = 192
private const val TOOLBAR_PLAYHEAD_UPDATE_NANOS = 100_000_000L
private const val SHOW_REFRESH_DIAGNOSTIC_CONTROLS = false

class MainActivity : ComponentActivity(), RenderFramePacer.InteractionListener {
    private lateinit var waterfallView: WaterfallSurface
    private lateinit var stageRoot: LinearLayout
    private lateinit var shellRootFrame: FrameLayout
    private lateinit var waterfallHost: FrameLayout
    private var appBackgroundView: View? = null
    private var waterfallBackdropView: View? = null
    private var toolbarView: View? = null
    private var rulerGlassOverlayView: FrostedRulerOverlayView? = null
    private var volumeGestureOverlayView: View? = null
    private val shellUiState = ShellUiState()
    private val playbackUi = PlaybackUiStateController(shellUiState)
    private var lastToolbarPlayheadUpdateNanos = 0L
    private var lastToolbarPlayheadSnapshot: PlaybackToolbarSnapshot? = null
    private var requestedDisplayModeId = 0
    private var requestedWindowRefreshRate = 0f
    private var highRefreshRateKeepAlivePosted = false
    private var highRefreshUiPulsePosted = false
    private var lastHighRefreshUiRequestNanos = 0L
    private var lastHighRefreshUiStatsNanos = 0L
    private var lastHighRefreshUiPulseFrameNanos = 0L
    private var highRefreshUiStatsFrameCount = 0
    private val highRefreshRateHandler = Handler(Looper.getMainLooper())
    private var displayDiagnosticsRegistered = false
    private var lastDisplayModeDiagnosticKey: String? = null
    private lateinit var scoreLoader: ScoreLoader
    private val scoreContentParser = ScoreContentParser()
    private val nativeAudioController = NativeAudioController()
    private var sourceParsedScore: ParsedScore? = null
    private var midiInputManager: MidiDeviceInputManager? = null
    private val activeMidiNotes = linkedMapOf<MidiNoteKey, MutableList<MidiTrackedNote>>()
    private val sustainedMidiNotes = linkedMapOf<MidiNoteKey, MutableList<MidiTrackedNote>>()
    private val connectedMidiDevices = linkedMapOf<Int, MidiInputDevice>()
    private var midiSustainPedalDown = false
    private var midiInputDetail: String? = null
    private var nextMidiPreviewPointerId = MIDI_PREVIEW_POINTER_BASE
    private lateinit var defaultSoundFontLoader: DefaultSoundFontLoader
    private lateinit var defaultScaleGuide: ScaleGuide
    private lateinit var currentScaleGuide: ScaleGuide
    private var clearToolbarFocus: (() -> Unit)? = null

    private var isUserEditingEdo = false
    private var pendingNativeEdo: Int? = null
    private var pendingNativeEdoUntilMs = 0L
    private val nativePlayback = NativePlaybackCoordinator(
        object : NativePlaybackCoordinator.Callbacks {
            override fun requestDefaultSoundFont() {
                prepareDefaultSoundFont()
            }

            override fun onPlayheadChanged(score: ParsedScore?, playheadSeconds: Double) {
                updateNativePlayheadUi()
            }

            override fun onStateChanged() {
                if (nativePlaying) {
                    RenderFramePacer.notifyContentActive()
                }
                if (::waterfallView.isInitialized) {
                    waterfallView.setPlaybackActive(nativePlaying)
                }
                refreshHighRefreshRateRequest(force = true)
                updatePlayButtonIcon()
                updateStageControls()
                updateToolbarPlayheadIfNeeded(force = true)
            }

            override fun onPlaybackFinished() {
                refreshHighRefreshRateRequest(force = true)
                updateStageControls()
                updateToolbarPlayheadIfNeeded(force = true)
            }

            override fun onStatusText(text: String) {
                setStatusText(text)
            }
        }
    )
    private var nativeParsedScore: ParsedScore?
        get() = nativePlayback.score
        set(value) {
            nativePlayback.score = value
        }
    private var nativePlayheadSeconds: Double
        get() = nativePlayback.playheadSeconds
        set(value) {
            nativePlayback.playheadSeconds = value
        }
    private var nativeSpeed: Double
        get() = nativePlayback.speed
        set(value) {
            nativePlayback.speed = value
        }
    private var nativeEdo = EDO_DEFAULT
    private var touchKeyboardProgram = GM_PROGRAM_DEFAULT
    private var touchKeyboardProgramControlsMidi = GM_PROGRAM_CONTROLS_MIDI_DEFAULT
    private val nativePlaying: Boolean
        get() = nativePlayback.playing
    private var nativeLoading: Boolean
        get() = nativePlayback.loading
        set(value) {
            nativePlayback.loading = value
        }
    private var nativeFinished: Boolean
        get() = nativePlayback.finished
        set(value) {
            nativePlayback.finished = value
        }
    private var nativeSf2Loading = false
    private var playbackStartSuppressedUntil = 0L
    private var volumeGain = VOLUME_GAIN_DEFAULT
    private var reverbValue = REVERB_DEFAULT
    private var audioLatencyMs = AUDIO_LATENCY_DEFAULT_MS
    private val sampleLoaderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private lateinit var waterfallGestureController: WaterfallGestureController
    private var waterfallGestureActive = false
    private var refreshRateExperimentMode = DEFAULT_REFRESH_RATE_EXPERIMENT_MODE

    private val highRefreshRateKeepAlive = object : Runnable {
        override fun run() {
            highRefreshRateKeepAlivePosted = false
            refreshHighRefreshRateRequest(force = true)
        }
    }
    private val highRefreshUiPulse = Choreographer.FrameCallback { frameTimeNanos ->
        highRefreshUiPulsePosted = false
        if (!shouldRunHighRefreshUiPulse()) {
            refreshHighRefreshRateRequest(force = true)
            return@FrameCallback
        }
        pulseHighRefreshUiFrame(frameTimeNanos)
        startHighRefreshUiPulse()
    }
    private val displayModeDiagnosticsListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            logDisplayModeDiagnostic("displayAdded id=$displayId", force = true)
        }

        override fun onDisplayChanged(displayId: Int) {
            val activeDisplay = displayForRefreshMode()
            if (activeDisplay == null || activeDisplay.displayId == displayId) {
                logDisplayModeDiagnostic("displayChanged id=$displayId", force = true)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            logDisplayModeDiagnostic("displayRemoved id=$displayId", force = true)
        }
    }

    private val androidFilePicker =
        registerForActivityResult(OpenScoreFileContract()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            loadSelectedUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scoreLoader = ScoreLoader(contentResolver)
        defaultSoundFontLoader = DefaultSoundFontLoader(cacheDir, assets)
        defaultScaleGuide = ScaleGuide.fromResources(this)
        currentScaleGuide = defaultScaleGuide
        loadPersistedSettings()
        refreshRateExperimentMode = DEFAULT_REFRESH_RATE_EXPERIMENT_MODE
        shellUiState.refreshRateExperimentLabel = refreshRateExperimentMode.toolbarLabel
        playbackUi.updateEdo(nativeEdo, updateProgress = true)
        playbackUi.updateTouchKeyboardProgram(touchKeyboardProgram)
        playbackUi.updateTouchKeyboardProgramMidiOverride(touchKeyboardProgramControlsMidi)
        applyAudioLatency(audioLatencyMs, resetScheduler = false)
        configureFullscreen()
        RenderFramePacer.addInteractionListener(this)
        setKeepScreenAwake(true)
        refreshHighRefreshRateRequest(force = true)
        val nativeAudioReady = nativeAudioController.start()
        nativePlayback.setAudioReady(nativeAudioReady)
        if (!nativeAudioReady) {
            Toast.makeText(this, "Native audio init failed; built-in SoundFont unavailable", Toast.LENGTH_LONG)
                .show()
        }
        applyVolumeGain(volumeGain)
        applyReverbMix(reverbValue)
        setContentView(createNativeShell())
        registerDisplayModeDiagnostics()
        logDisplayModeDiagnostic("onCreate", force = true)
        startHighRefreshUiPulse()
        prepareDefaultSoundFont()
        startMidiInput()
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        registerDisplayModeDiagnostics()
        logDisplayModeDiagnostic("onResume", force = true)
        setKeepScreenAwake(true)
        refreshHighRefreshRateRequest(force = true)
        startHighRefreshRateKeepAlive()
        startHighRefreshUiPulse()
        nativePlayback.setAudioReady(nativeAudioController.recoverStartedStream())
        midiInputManager?.start()
        if (::waterfallView.isInitialized) {
            waterfallView.onHostResume()
        }
    }

    override fun onPause() {
        if (::waterfallGestureController.isInitialized) {
            waterfallGestureController.cancelTouchState()
        }
        setWaterfallGestureActive(false)
        if (::waterfallView.isInitialized) {
            waterfallView.onHostPause()
        }
        midiInputManager?.stop()
        connectedMidiDevices.clear()
        stopTrackedMidiNotes()
        updateMidiInputStatus(detail = null)
        stopHighRefreshRateKeepAlive()
        stopHighRefreshUiPulse()
        clearPreferredDisplayMode()
        logDisplayModeDiagnostic("onPause", force = true)
        unregisterDisplayModeDiagnostics()
        setKeepScreenAwake(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onDestroy() {
        destroyed.set(true)
        RenderFramePacer.removeInteractionListener(this)
        stopNativeClock()
        waterfallGestureController.cancelTouchState()
        setWaterfallGestureActive(false)
        if (::waterfallView.isInitialized) {
            waterfallView.onHostDestroy()
        }
        midiInputManager?.close()
        midiInputManager = null
        connectedMidiDevices.clear()
        stopTrackedMidiNotes()
        updateMidiInputStatus(detail = null)
        stopHighRefreshRateKeepAlive()
        stopHighRefreshUiPulse()
        clearPreferredDisplayMode()
        unregisterDisplayModeDiagnostics()
        stopNativeAudio()
        sampleLoaderExecutor.shutdownNow()
        nativeAudioController.teardown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        logDisplayModeDiagnostic("windowFocus=$hasFocus", force = true)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        RenderFramePacer.notifyInteraction()
        refreshHighRefreshRateRequest(force = true)
        startHighRefreshRateKeepAlive()
    }

    override fun onRenderFramePacerInteraction() {
        highRefreshRateHandler.post {
            if (destroyed.get()) {
                return@post
            }
            refreshHighRefreshRateRequest(force = true)
            startHighRefreshRateKeepAlive()
        }
    }

    private fun configureFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setKeepScreenAwake(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshHighRefreshRateRequest(force: Boolean = false) {
        val demandActive = highRefreshDemandActive()
        requestHighestRefreshDisplayMode(force = force, demandActive = demandActive)
        if (::waterfallView.isInitialized) {
            waterfallView.setRefreshRateHints(
                surfaceFrameRateEnabled = refreshRateExperimentMode.surfaceFrameRateEnabled,
                viewFrameRateEnabled = refreshRateExperimentMode.viewRequestedFrameRateEnabled
            )
            waterfallView.requestHighRefreshRate(force = force)
        }
        if (demandActive) {
            startHighRefreshRateKeepAlive()
            startHighRefreshUiPulse()
        } else {
            stopHighRefreshRateKeepAlive()
            stopHighRefreshUiPulse()
        }
    }

    private fun requestHighestRefreshDisplayMode(force: Boolean = false, demandActive: Boolean) {
        val displayView = if (::waterfallView.isInitialized) {
            waterfallView.view
        } else {
            window.decorView
        }
        val rateMode = refreshRateExperimentMode
        val requestedFrameRate = RenderFramePacer.applyWindowPreferredFrameRate(
            window = window,
            displayView = displayView,
            contentActive = demandActive,
            force = force,
            applyWindowRefreshRate = demandActive && rateMode.windowRefreshRateEnabled,
            applyViewRequestedFrameRate = demandActive && rateMode.viewRequestedFrameRateEnabled,
            tag = TAG
        )
        requestedWindowRefreshRate = if (demandActive && rateMode.windowRefreshRateEnabled) {
            requestedFrameRate
        } else {
            0f
        }
        applyUiRequestedFrameRate(if (demandActive) requestedFrameRate else 0f, force = force)
        if (!demandActive || !rateMode.displayModeEnabled) {
            clearPreferredDisplayModeId()
            return
        }
        val display = displayForRefreshMode() ?: return
        val targetRefreshRate = HighRefreshRatePolicy.preferredSupportedRefreshRate(display)
        val bestMode = HighRefreshRatePolicy.bestDisplayMode(display) ?: run {
            clearPreferredDisplayModeId()
            return
        }
        if (!force && bestMode.modeId == requestedDisplayModeId) {
            return
        }
        requestedDisplayModeId = bestMode.modeId
        val params = window.attributes
        if (force || params.preferredDisplayModeId != bestMode.modeId) {
            params.preferredDisplayModeId = bestMode.modeId
            window.attributes = params
        }
        Log.i(
            TAG,
            "Requested display mode id=${bestMode.modeId} " +
                "${bestMode.physicalWidth}x${bestMode.physicalHeight}@${bestMode.refreshRate}Hz " +
                "target=${HighRefreshRatePolicy.formatFrameRate(targetRefreshRate)}Hz " +
                "window=${windowRefreshRateLabel()} " +
                "rateMode=${rateMode.toolbarLabel} ${rateMode.logSummary()} " +
                "active=$demandActive demand=${highRefreshDemandSummary()} " +
                "supported=${HighRefreshRatePolicy.supportedRefreshSummary(display)}"
        )
    }

    private fun highRefreshDemandActive(): Boolean {
        return FORCE_WINDOW_REFRESH_ACTIVE ||
            nativePlaying ||
            waterfallGestureActive ||
            (::waterfallView.isInitialized && waterfallView.hasHighRefreshDemand())
    }

    private fun highRefreshDemandSummary(): String {
        val waterfallDemand = if (::waterfallView.isInitialized) {
            waterfallView.hasHighRefreshDemand()
        } else {
            false
        }
        return "forced=$FORCE_WINDOW_REFRESH_ACTIVE " +
            "playing=$nativePlaying " +
            "gesture=$waterfallGestureActive " +
            "waterfall=$waterfallDemand"
    }

    private fun applyUiRequestedFrameRate(frameRate: Float, force: Boolean) {
        val viewFrameRate = if (refreshRateExperimentMode.viewRequestedFrameRateEnabled) {
            frameRate
        } else {
            0f
        }
        val viewForce = force || !refreshRateExperimentMode.viewRequestedFrameRateEnabled
        RenderFramePacer.applyRequestedFrameRate(window.decorView, viewFrameRate, force = viewForce)
        if (::shellRootFrame.isInitialized) {
            RenderFramePacer.applyRequestedFrameRateTree(shellRootFrame, viewFrameRate, force = viewForce)
        }
        if (::stageRoot.isInitialized) {
            RenderFramePacer.applyRequestedFrameRate(stageRoot, viewFrameRate, force = viewForce)
        }
        toolbarView?.let { toolbar ->
            RenderFramePacer.applyRequestedFrameRate(toolbar, viewFrameRate, force = viewForce)
        }
        rulerGlassOverlayView?.let { overlay ->
            RenderFramePacer.applyRequestedFrameRate(overlay, viewFrameRate, force = viewForce)
        }
        volumeGestureOverlayView?.let { overlay ->
            RenderFramePacer.applyRequestedFrameRate(overlay, viewFrameRate, force = viewForce)
        }
        if (::waterfallView.isInitialized) {
            RenderFramePacer.applyRequestedFrameRate(waterfallView.view, viewFrameRate, force = viewForce)
        }
    }

    private fun clearPreferredDisplayMode() {
        if (requestedDisplayModeId == 0 && requestedWindowRefreshRate == 0f) {
            return
        }
        requestedDisplayModeId = 0
        requestedWindowRefreshRate = 0f
        val params = window.attributes
        var changed = false
        if (params.preferredDisplayModeId != 0) {
            params.preferredDisplayModeId = 0
            changed = true
        }
        if (params.preferredRefreshRate != 0f) {
            params.preferredRefreshRate = 0f
            changed = true
        }
        if (changed) {
            window.attributes = params
        }
        RenderFramePacer.resetWindowFrameRatePolicy(window)
    }

    private fun clearPreferredDisplayModeId() {
        if (requestedDisplayModeId == 0) {
            return
        }
        requestedDisplayModeId = 0
        val params = window.attributes
        if (params.preferredDisplayModeId != 0) {
            params.preferredDisplayModeId = 0
            window.attributes = params
        }
    }

    private fun windowRefreshRateLabel(): String {
        return if (refreshRateExperimentMode.windowRefreshRateEnabled) {
            "${HighRefreshRatePolicy.formatFrameRate(requestedWindowRefreshRate)}Hz"
        } else {
            "off"
        }
    }

    private fun startHighRefreshRateKeepAlive() {
        if (highRefreshRateKeepAlivePosted || destroyed.get() || !highRefreshDemandActive()) {
            return
        }
        highRefreshRateKeepAlivePosted = true
        highRefreshRateHandler.postDelayed(highRefreshRateKeepAlive, HIGH_REFRESH_KEEPALIVE_MS)
    }

    private fun stopHighRefreshRateKeepAlive() {
        highRefreshRateKeepAlivePosted = false
        highRefreshRateHandler.removeCallbacks(highRefreshRateKeepAlive)
    }

    private fun startHighRefreshUiPulse() {
        if (highRefreshUiPulsePosted ||
            destroyed.get() ||
            !highRefreshDemandActive() ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            return
        }
        highRefreshUiPulsePosted = true
        Choreographer.getInstance().postFrameCallback(highRefreshUiPulse)
    }

    private fun stopHighRefreshUiPulse() {
        highRefreshUiPulsePosted = false
        lastHighRefreshUiStatsNanos = 0L
        lastHighRefreshUiPulseFrameNanos = 0L
        highRefreshUiStatsFrameCount = 0
        Choreographer.getInstance().removeFrameCallback(highRefreshUiPulse)
    }

    private fun shouldRunHighRefreshUiPulse(): Boolean {
        return !destroyed.get() &&
            highRefreshDemandActive() &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    private fun pulseHighRefreshUiFrame(frameTimeNanos: Long) {
        logHighRefreshUiPulseStats(frameTimeNanos)
        if (frameTimeNanos - lastHighRefreshUiRequestNanos >= HIGH_REFRESH_UI_REQUEST_NANOS) {
            lastHighRefreshUiRequestNanos = frameTimeNanos
            refreshHighRefreshRateRequest(force = true)
        }
        if (::waterfallView.isInitialized) {
            waterfallView.requestHighRefreshFrame(frameTimeNanos)
        }
    }

    private fun logHighRefreshUiPulseStats(frameTimeNanos: Long) {
        val previousFrameNanos = lastHighRefreshUiPulseFrameNanos
        lastHighRefreshUiPulseFrameNanos = frameTimeNanos
        if (lastHighRefreshUiStatsNanos <= 0L) {
            lastHighRefreshUiStatsNanos = frameTimeNanos
            return
        }
        highRefreshUiStatsFrameCount += 1
        if (highRefreshUiStatsFrameCount < UI_PULSE_LOG_FRAME_INTERVAL) {
            return
        }
        val elapsedNanos = frameTimeNanos - lastHighRefreshUiStatsNanos
        if (elapsedNanos <= 0L) {
            highRefreshUiStatsFrameCount = 0
            lastHighRefreshUiStatsNanos = frameTimeNanos
            return
        }
        val display = displayForRefreshMode()
        val activeMode = display?.mode?.let { mode ->
            "${mode.physicalWidth}x${mode.physicalHeight}@" +
                "${HighRefreshRatePolicy.formatFrameRate(mode.refreshRate)}Hz"
        } ?: "unknown"
        val lastDeltaNanos = if (previousFrameNanos > 0L) {
            frameTimeNanos - previousFrameNanos
        } else {
            0L
        }
        val fps = highRefreshUiStatsFrameCount * 1_000_000_000.0 / elapsedNanos.toDouble()
        Log.i(
            TAG,
            "UI pulse fps=${formatFps(fps)} " +
                "lastDtMs=${formatMillis(lastDeltaNanos)} " +
                "display=${display?.let { HighRefreshRatePolicy.formatFrameRate(it.refreshRate) } ?: "unknown"}Hz " +
                "mode=$activeMode " +
                "window=${windowRefreshRateLabel()} " +
                "rateMode=${refreshRateExperimentMode.toolbarLabel}"
        )
        highRefreshUiStatsFrameCount = 0
        lastHighRefreshUiStatsNanos = frameTimeNanos
    }

    @Suppress("DEPRECATION")
    private fun displayForRefreshMode(): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.let { return it }
        }
        return windowManager.defaultDisplay
    }

    private fun registerDisplayModeDiagnostics() {
        if (displayDiagnosticsRegistered) {
            return
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager.registerDisplayListener(displayModeDiagnosticsListener, highRefreshRateHandler)
        displayDiagnosticsRegistered = true
        logDisplayModeDiagnostic("displayListenerRegistered", force = true)
    }

    private fun unregisterDisplayModeDiagnostics() {
        if (!displayDiagnosticsRegistered) {
            return
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager.unregisterDisplayListener(displayModeDiagnosticsListener)
        displayDiagnosticsRegistered = false
    }

    private fun logDisplayModeDiagnostic(reason: String, force: Boolean = false) {
        val display = displayForRefreshMode()
        val currentKey = displayModeKey(display)
        val previousKey = lastDisplayModeDiagnosticKey
        val changed = previousKey != currentKey
        if (!force && !changed) {
            return
        }
        lastDisplayModeDiagnosticKey = currentKey
        Log.i(
            TAG,
            "Display mode diagnostic reason=$reason changed=$changed " +
                "previous=${previousKey ?: "unknown"} " +
                "current=$currentKey " +
                "displayId=${display?.displayId ?: -1} " +
                "refresh=${display?.let { HighRefreshRatePolicy.formatFrameRate(it.refreshRate) } ?: "unknown"}Hz " +
                "requestedModeId=$requestedDisplayModeId " +
                "window=${windowRefreshRateLabel()} " +
                "rateMode=${refreshRateExperimentMode.toolbarLabel} " +
                "focus=${window.decorView.hasWindowFocus()} " +
                "decor=${viewDiagnostic(window.decorView)} " +
                "root=${if (::shellRootFrame.isInitialized) viewDiagnostic(shellRootFrame) else "uninit"} " +
                "waterfall=${if (::waterfallView.isInitialized) viewDiagnostic(waterfallView.view) else "uninit"} " +
                "supported=${HighRefreshRatePolicy.supportedRefreshSummary(display)}"
        )
    }

    private fun displayModeKey(display: Display?): String {
        if (display == null) {
            return "unknown"
        }
        val mode = display.mode ?: return "unknown"
        return "id=${mode.modeId} " +
            "${mode.physicalWidth}x${mode.physicalHeight}@" +
            "${HighRefreshRatePolicy.formatFrameRate(mode.refreshRate)}Hz"
    }

    private fun viewDiagnostic(view: View): String {
        return "${view.width}x${view.height}" +
            "@${view.left},${view.top}" +
            " vis=${visibilityLabel(view.visibility)}" +
            " winVis=${visibilityLabel(view.windowVisibility)}" +
            " attached=${view.isAttachedToWindow}"
    }

    private fun logLayoutDiagnostic(
        reason: String,
        view: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        Log.i(
            TAG,
            "Layout diagnostic reason=$reason " +
                "new=${right - left}x${bottom - top}@${left},$top " +
                "old=${oldRight - oldLeft}x${oldBottom - oldTop}@${oldLeft},$oldTop " +
                "view=${view.javaClass.simpleName} " +
                "display=${displayModeKey(displayForRefreshMode())} " +
                "window=${windowRefreshRateLabel()} " +
                "rateMode=${refreshRateExperimentMode.toolbarLabel} " +
                "focus=${window.decorView.hasWindowFocus()} " +
                "waterfall=${if (::waterfallView.isInitialized) viewDiagnostic(waterfallView.view) else "uninit"}"
        )
    }

    private fun visibilityLabel(visibility: Int): String {
        return when (visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            View.GONE -> "gone"
            else -> visibility.toString()
        }
    }

    private fun createNativeShell(): View {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(COLOR_STAGE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        shellRootFrame = rootFrame
        rootFrame.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                logLayoutDiagnostic(
                    reason = "rootLayout",
                    view = view,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    oldLeft = oldLeft,
                    oldTop = oldTop,
                    oldRight = oldRight,
                    oldBottom = oldBottom
                )
            }
        }
        val appBackground = createAppBackgroundView()
        appBackgroundView = appBackground
        rootFrame.addView(appBackground)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        stageRoot = root
        rootFrame.addView(root)

        val toolbarContent = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                XenSynthMaterialTheme {
                    XenToolbar(
                        state = shellUiState,
                        onOpen = ::openScorePicker,
                        onPlay = ::toggleNativePlayback,
                        onReset = ::resetPlaybackFromUi,
                        onTerminate = ::terminatePlaybackFromUi,
                        onEdoChanged = ::onEdoProgressChanged,
                        onEdoTextChanged = ::onEdoTextChanged,
                        onSpeedChanged = ::onSpeedProgressChanged,
                        onSpeedTextChanged = ::onSpeedTextChanged,
                        onOffsetChanged = ::onOffsetCentsChanged,
                        onOffsetTextChanged = ::onOffsetTextChanged,
                        onTouchKeyboardProgramChanged = ::onTouchKeyboardProgramProgressChanged,
                        onTouchKeyboardProgramTextChanged = ::onTouchKeyboardProgramTextChanged,
                        onTouchKeyboardProgramMidiOverrideChanged = ::onTouchKeyboardProgramMidiOverrideChanged,
                        onAudioLatencyChanged = ::onAudioLatencyChanged,
                        onReverbChanged = ::onReverbChanged,
                        onRefreshRateExperimentSelected = ::onRefreshRateExperimentSelected,
                        onFocusClearerChanged = { clearToolbarFocus = it }
                    )
                }
            }
        }
        val toolbar = FrostedGlassFrameLayout(this).apply {
            setBackdropSource(appBackground)
            addView(
                toolbarContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        toolbarView = toolbar
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            )
        )

        waterfallView = createWaterfallSurface()
        waterfallGestureController = createWaterfallGestureController(waterfallView)
        val waterfallBackdrop = WaterfallBackdropView(this@MainActivity)
        waterfallBackdropView = waterfallBackdrop
        val waterfallContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    logLayoutDiagnostic(
                        reason = "waterfallHostLayout",
                        view = view,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        oldLeft = oldLeft,
                        oldTop = oldTop,
                        oldRight = oldRight,
                        oldBottom = oldBottom
                    )
                }
            }
            addView(
                waterfallBackdrop,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                waterfallView.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        waterfallHost = waterfallContainer
        root.addView(waterfallContainer)

        bindWaterfallTouchListener(waterfallView)
        val rulerGlassOverlay = createRulerGlassOverlay(waterfallView, appBackground)
        rulerGlassOverlayView = rulerGlassOverlay
        rootFrame.addView(rulerGlassOverlay)
        val volumeOverlay = createVolumeGestureOverlay()
        volumeGestureOverlayView = volumeOverlay
        rootFrame.addView(volumeOverlay)

        updateReadyState()
        return rootFrame
    }

    private fun createAppBackgroundView(): View {
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_STAGE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val bitmap = runCatching {
                assets.open(BACKGROUND_ASSET_PATH).use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.onFailure { error ->
                Log.w(TAG, "Unable to load app background asset: $BACKGROUND_ASSET_PATH", error)
            }.getOrNull()
            if (bitmap != null) {
                setImageBitmap(bitmap)
            }
        }
    }

    private fun createVolumeGestureOverlay(): View {
        return ComposeView(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                XenSynthMaterialTheme {
                    VolumeGestureOverlay(
                        state = shellUiState,
                        onExpired = ::hideVolumeGestureFeedback
                    )
                }
            }
        }
    }

    private fun createRulerGlassOverlay(surface: WaterfallSurface, backdropSource: View): FrostedRulerOverlayView {
        return FrostedRulerOverlayView(this).apply {
            setBackdropSource(backdropSource)
            setRulerTopProvider {
                val currentSurface = waterfallView
                val surfaceLocation = IntArray(2)
                val overlayLocation = IntArray(2)
                currentSurface.view.getLocationInWindow(surfaceLocation)
                getLocationInWindow(overlayLocation)
                surfaceLocation[1] - overlayLocation[1] + currentSurface.waterfallGestureHeight()
            }
            setRulerContentProviders(
                displayStateProvider = { waterfallView.displayState() },
                scaleGuideProvider = { currentScaleGuide },
                octaveDivisionsProvider = { nativeEdo }
            )
            setRulerImpactProvider { waterfallView.rulerImpacts() }
            setRulerParticleProvider { waterfallView.rulerParticles() }
            visibility = if (surface is CanvasWaterfallView) View.GONE else View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun updateRulerGlassOverlay(surface: WaterfallSurface = waterfallView) {
        rulerGlassOverlayView?.apply {
            appBackgroundView?.let(::setBackdropSource)
            visibility = if (surface is CanvasWaterfallView) {
                View.GONE
            } else {
                View.VISIBLE
            }
            invalidate()
        }
    }

    private fun openScorePicker() {
        androidFilePicker.launch(SCORE_MIME_TYPES)
    }

    private fun onEdoProgressChanged(progress: Float) {
        val next = progress.roundToInt().coerceIn(0, EDO_MAX)
        clearCustomTuningIfNeeded()
        shellUiState.edoProgress = next.toFloat()
        isUserEditingEdo = true
        applyNativeEdo(
            next,
            updateSeek = false,
            protectFromStatus = true
        )
        isUserEditingEdo = false
    }

    private fun onEdoTextChanged(value: Int) {
        clearCustomTuningIfNeeded()
        isUserEditingEdo = true
        applyNativeEdo(
            value,
            updateSeek = true,
            protectFromStatus = true
        )
        isUserEditingEdo = false
    }

    private fun onSpeedProgressChanged(progress: Float) {
        val nextProgress = progress.roundToInt().coerceIn(0, SPEED_STEPS)
        val speed = progressToSpeed(nextProgress)
        nativeSpeed = speed
        playbackUi.updateSpeed(nextProgress, speed)
        updateToolbarPlayheadIfNeeded(force = true)
    }

    private fun onSpeedTextChanged(value: Double) {
        onSpeedProgressChanged(speedToProgress(value).toFloat())
    }

    private fun resetPlaybackFromUi() {
        suppressPlaybackStartBriefly()
        resetNativePlayback()
    }

    private fun terminatePlaybackFromUi() {
        suppressPlaybackStartBriefly()
        waterfallGestureController.cancelTouchState()
        setWaterfallGestureActive(false)
        nativeParsedScore = null
        sourceParsedScore = null
        nativePlayheadSeconds = 0.0
        nativeFinished = false
        nativeLoading = false
        nativePlayback.clearPendingDefaultSoundFontPlayback()
        waterfallView.setScore(null)
        resetToolbarPlayheadUpdateCache()
        resetNativePlayback()
        playbackUi.resetScoreSummary()
        updatePlayButtonIcon()
        updateStageControls()
    }

    private fun setStatusText(text: String) {
        playbackUi.setStatusText(text)
    }

    private fun applyNativeSeekGesture(seconds: Double) {
        val parsed = nativeParsedScore ?: return
        val duration = parsed.duration.coerceAtLeast(0.0)
        nativePlayheadSeconds = seconds.coerceIn(0.0, duration)
        nativeFinished = duration > 0.0 && nativePlayheadSeconds >= duration - 0.01
        updateNativePlayheadUi()
        updateStageControls()
    }

    private fun beginNativeSeekGesture() {
        nativePlayback.beginSeekGesture()
    }

    private fun endNativeSeekGesture() {
        nativePlayback.endSeekGesture()
        updateToolbarPlayheadIfNeeded(force = true)
    }

    private fun updateNativeOffsetDisplay(cents: Double) {
        playbackUi.updateOffsetDisplay(cents)
    }

    private fun applyVolumeGain(gain: Float, showGestureFeedback: Boolean = false) {
        volumeGain = gain.coerceIn(VOLUME_GAIN_MIN, VOLUME_GAIN_MAX)
        playbackUi.updateVolume(volumeGain)
        if (showGestureFeedback) {
            showVolumeGestureFeedback()
        } else if (shellUiState.volumeGestureVisible) {
            updateWaterfallVolumeGestureFeedback()
        }
        nativeAudioController.setGain(volumeGain)
    }

    private fun applyReverbMix(value: Int) {
        reverbValue = value.coerceIn(REVERB_MIN, REVERB_MAX)
        playbackUi.updateReverb(reverbValue)
        nativeAudioController.setReverb(reverbValue)
    }

    private fun showVolumeGestureFeedback() {
        playbackUi.showVolumeGesture()
        updateWaterfallVolumeGestureFeedback()
    }

    private fun hideVolumeGestureFeedback() {
        playbackUi.hideVolumeGesture()
        if (::waterfallView.isInitialized) {
            waterfallView.setVolumeGestureFeedback(null)
        }
    }

    private fun updateWaterfallVolumeGestureFeedback() {
        if (::waterfallView.isInitialized) {
            waterfallView.setVolumeGestureFeedback(currentVolumeGestureFeedback())
        }
    }

    private fun currentVolumeGestureFeedback(): WaterfallVolumeGestureFeedback {
        val range = VOLUME_GAIN_MAX - VOLUME_GAIN_MIN
        val fraction = if (range > 0f) {
            (volumeGain - VOLUME_GAIN_MIN) / range
        } else {
            0f
        }
        return WaterfallVolumeGestureFeedback(
            fraction = fraction.coerceIn(0f, 1f),
            label = shellUiState.volume
        )
    }

    private fun onOffsetCentsChanged(value: Float) {
        waterfallGestureController.setOffsetCents(value)
    }

    private fun onOffsetTextChanged(value: Int) {
        onOffsetCentsChanged(value.toFloat())
    }

    private fun onTouchKeyboardProgramProgressChanged(progress: Float) {
        updateTouchKeyboardProgram(progress.roundToInt())
    }

    private fun onTouchKeyboardProgramTextChanged(value: Int) {
        updateTouchKeyboardProgram(value)
    }

    private fun updateTouchKeyboardProgram(value: Int) {
        val next = value.coerceIn(GM_PROGRAM_MIN, GM_PROGRAM_MAX)
        if (touchKeyboardProgram == next) {
            playbackUi.updateTouchKeyboardProgram(next)
            return
        }
        touchKeyboardProgram = next
        playbackUi.updateTouchKeyboardProgram(next)
        persistTouchKeyboardProgram(next)
    }

    private fun onTouchKeyboardProgramMidiOverrideChanged(enabled: Boolean) {
        if (touchKeyboardProgramControlsMidi == enabled) {
            playbackUi.updateTouchKeyboardProgramMidiOverride(enabled)
            return
        }
        touchKeyboardProgramControlsMidi = enabled
        playbackUi.updateTouchKeyboardProgramMidiOverride(enabled)
        persistTouchKeyboardProgramMidiOverride(enabled)
    }

    private fun onAudioLatencyChanged(value: Float) {
        val next = roundedAudioLatencyMs(value)
        if (audioLatencyMs == next) {
            return
        }
        applyAudioLatency(next, resetScheduler = true)
        persistAudioLatency(next)
    }

    private fun onReverbChanged(progress: Float) {
        val next = progress.roundToInt().coerceIn(REVERB_MIN, REVERB_MAX)
        if (reverbValue == next) {
            return
        }
        applyReverbMix(next)
        persistReverb(next)
    }

    private fun applyAudioLatency(value: Int, resetScheduler: Boolean) {
        val next = roundedAudioLatencyMs(value.toFloat())
        val changed = audioLatencyMs != next
        audioLatencyMs = next
        nativePlayback.audioScheduleOffsetSeconds = next / 1_000.0
        playbackUi.updateAudioLatency(next)
        if (!resetScheduler || !changed) {
            return
        }
        if (nativePlaying) {
            nativePlayback.stopAudio()
        }
        val leadInChanged = applyInitialWaterfallLeadIn()
        resetNativeAudioScheduler()
        if (!leadInChanged) {
            updateWaterfallPlayheadFrame()
        }
    }

    private fun roundedAudioLatencyMs(value: Float): Int {
        return ((value / AUDIO_LATENCY_STEP_MS).roundToInt() * AUDIO_LATENCY_STEP_MS)
            .coerceIn(AUDIO_LATENCY_MIN_MS, AUDIO_LATENCY_MAX_MS)
    }

    private fun onRefreshRateExperimentSelected(label: String) {
        val nextMode = RefreshRateExperimentMode.fromToolbarLabel(label) ?: return
        if (refreshRateExperimentMode == nextMode) {
            shellUiState.refreshRateExperimentLabel = nextMode.toolbarLabel
            return
        }
        refreshRateExperimentMode = nextMode
        shellUiState.refreshRateExperimentLabel = nextMode.toolbarLabel
        Log.i(TAG, "Refresh rate experiment ${nextMode.logSummary()}")
        if (::waterfallView.isInitialized) {
            waterfallView.setRefreshRateHints(
                surfaceFrameRateEnabled = nextMode.surfaceFrameRateEnabled,
                viewFrameRateEnabled = nextMode.viewRequestedFrameRateEnabled
            )
        }
        refreshHighRefreshRateRequest(force = true)
    }

    private fun bindWaterfallTouchListener(surface: WaterfallSurface) {
        surface.view.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                clearToolbarFocus?.invoke()
            }
            val wasDemandActive = highRefreshDemandActive()
            val handled = waterfallGestureController.handleTouch(event)
            val gestureChanged = setWaterfallGestureActive(waterfallGestureController.hasActiveGesture())
            rulerGlassOverlayView?.postInvalidateOnAnimation()
            if (gestureChanged || wasDemandActive != highRefreshDemandActive()) {
                refreshHighRefreshRateRequest(force = true)
            }
            handled
        }
    }

    private fun setWaterfallGestureActive(active: Boolean): Boolean {
        if (waterfallGestureActive == active) {
            return false
        }
        waterfallGestureActive = active
        if (::waterfallView.isInitialized) {
            waterfallView.setInteractionActive(active)
        }
        if (active) {
            RenderFramePacer.notifyInteraction()
        }
        return true
    }

    private fun createWaterfallSurface(): WaterfallSurface {
        val surface = createCanvasWaterfallSurface()
        configureWaterfallSurface(surface)
        return surface
    }

    private fun createCanvasWaterfallSurface(): WaterfallSurface {
        return CanvasWaterfallView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun configureWaterfallSurface(surface: WaterfallSurface) {
        surface.setScaleGuide(currentScaleGuide)
        surface.setOctaveDivisions(nativeEdo)
        if (shellUiState.volumeGestureVisible) {
            surface.setVolumeGestureFeedback(currentVolumeGestureFeedback())
        }
    }

    private fun createWaterfallGestureController(surface: WaterfallSurface): WaterfallGestureController {
        return WaterfallGestureController(
            surface,
            object : WaterfallGestureController.Callbacks {
                override fun playheadSeconds(): Double = nativePlayheadSeconds

                override fun beginSeekGesture() {
                    beginNativeSeekGesture()
                }

                override fun seekTo(seconds: Double) {
                    applyNativeSeekGesture(seconds)
                }

                override fun endSeekGesture() {
                    endNativeSeekGesture()
                }

                override fun canTogglePlayback(): Boolean {
                    return nativeParsedScore != null && !nativeLoading && !nativeFinished
                }

                override fun togglePlayback() {
                    this@MainActivity.toggleNativePlayback()
                }

                override fun canUseNativeAudio(): Boolean = this@MainActivity.canUseNativeAudio()

                override fun onAudioReadyChanged(ready: Boolean) {
                    nativePlayback.setAudioReady(ready)
                }

                override fun onDisplayStateChanged(displayState: WaterfallDisplayState) {
                    updateNativeOffsetDisplay(displayState.waterfallOffsetCents)
                }

                override fun previewProgram(): Int = touchKeyboardProgram

                override fun volumeGain(): Float = this@MainActivity.volumeGain

                override fun setVolumeGain(gain: Float) {
                    applyVolumeGain(gain, showGestureFeedback = true)
                }
            }
        )
    }

    private fun handleViewIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) {
            loadSelectedUri(uri)
        }
    }

    private fun loadSelectedUri(uri: Uri) {
        scoreLoader.readUriBytes(uri).onSuccess { bytes ->
            val name = scoreLoader.displayName(uri)
            if (scoreContentParser.classify(name, bytes) == ScoreContentType.Tuning) {
                loadTuningJson(name, bytes)
            } else {
                parseNativeScore(name, bytes)
            }
        }.onFailure { error ->
            Toast.makeText(this, error.localizedMessage ?: "Could not read file", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun loadTuningJson(name: String, bytes: ByteArray) {
        runCatching {
            scoreContentParser.parseTuning(bytes)
        }.onSuccess { guide ->
            currentScaleGuide = guide
            waterfallView.setScaleGuide(guide)
            rulerGlassOverlayView?.invalidate()
            applyCurrentKeybindToScore()
            val profileName = guide.profileName ?: CUSTOM_TUNING_FALLBACK_PROFILE
            playbackUi.updateCustomTuning(profileName)
            setStatusText("Tuning profile loaded: $profileName")
        }.onFailure { error ->
            Log.e(TAG, "Tuning JSON parse failed for $name", error)
            val message = error.message ?: "unknown error"
            setStatusText("Tuning JSON failed: $message")
            Toast.makeText(this, shellUiState.status, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearCustomTuningIfNeeded() {
        if (!::currentScaleGuide.isInitialized || !currentScaleGuide.isCustom) {
            return
        }
        currentScaleGuide = defaultScaleGuide
        if (::waterfallView.isInitialized) {
            waterfallView.setScaleGuide(defaultScaleGuide)
            rulerGlassOverlayView?.invalidate()
        }
        applyCurrentKeybindToScore()
        playbackUi.clearCustomTuning()
    }

    private fun prepareDefaultSoundFont() {
        if (!nativePlayback.shouldLoadDefaultSoundFont(nativeSf2Loading)) {
            return
        }
        nativeSf2Loading = true
        setStatusText("Loading built-in SoundFont")
        sampleLoaderExecutor.execute {
            val result = defaultSoundFontLoader.load()
            if (destroyed.get()) {
                return@execute
            }
            runOnUiThread {
                nativeSf2Loading = false
                val soundFontReady = result.getOrDefault(false)
                if (soundFontReady) {
                    applyVolumeGain(volumeGain)
                    setStatusText("Built-in SoundFont ready")
                } else {
                    val message = result.exceptionOrNull()?.message ?: "unknown error"
                    setStatusText("Built-in SoundFont failed: $message")
                }
                nativePlayback.onDefaultSoundFontLoadFinished(soundFontReady)
            }
        }
    }

    private fun parseNativeScore(name: String, bytes: ByteArray) {
        runCatching {
            scoreContentParser.parseScore(name, bytes)
        }.onSuccess { parsed ->
            sourceParsedScore = parsed
            val playbackScore = parsed.withKeybind(currentScaleGuide)
            nativeParsedScore = playbackScore
            nativePlayheadSeconds = 0.0
            nativeFinished = false
            setNativePlaying(false)
            waterfallView.setScore(playbackScore)
            if (!applyInitialWaterfallLeadIn()) {
                updateWaterfallPlayheadFrame()
            }
            resetNativeAudioScheduler()
            rulerGlassOverlayView?.invalidate()
            applyNativeParsedScore(playbackScore)
            updateStageControls()
        }.onFailure { error ->
            sourceParsedScore = null
            nativeParsedScore = null
            resetNativePlayback()
            waterfallView.setScore(null)
            rulerGlassOverlayView?.invalidate()
            updateStageControls()
            Log.e(TAG, "Native parse failed for $name", error)
            setStatusText("Native parse failed: ${error.message ?: "unknown error"}")
            Toast.makeText(this, shellUiState.status, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyNativeEdo(
        value: Int,
        updateSeek: Boolean,
        protectFromStatus: Boolean = false
    ) {
        val next = value.coerceIn(0, EDO_MAX)
        nativeEdo = next
        persistEdo(next)
        waterfallView.setOctaveDivisions(next)
        rulerGlassOverlayView?.invalidate()
        playbackUi.updateEdo(next, updateSeek)
        if (protectFromStatus) {
            pendingNativeEdo = next
            pendingNativeEdoUntilMs = SystemClock.uptimeMillis() + EDO_STATUS_PROTECTION_MS
        }
    }

    private fun settingsPreferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadPersistedSettings() {
        val prefs = settingsPreferences()
        nativeEdo = prefs.getInt(PREF_EDO, EDO_DEFAULT)
            .coerceIn(0, EDO_MAX)
        touchKeyboardProgram = prefs.getInt(PREF_TOUCH_KEYBOARD_PROGRAM, GM_PROGRAM_DEFAULT)
            .coerceIn(GM_PROGRAM_MIN, GM_PROGRAM_MAX)
        touchKeyboardProgramControlsMidi = prefs.getBoolean(
            PREF_TOUCH_KEYBOARD_PROGRAM_CONTROLS_MIDI,
            GM_PROGRAM_CONTROLS_MIDI_DEFAULT
        )
        reverbValue = prefs.getInt(PREF_REVERB, REVERB_DEFAULT)
            .coerceIn(REVERB_MIN, REVERB_MAX)
        audioLatencyMs = roundedAudioLatencyMs(
            prefs.getInt(PREF_AUDIO_LATENCY_MS, AUDIO_LATENCY_DEFAULT_MS).toFloat()
        )
    }

    private fun persistEdo(value: Int) {
        settingsPreferences().edit()
            .putInt(PREF_EDO, value.coerceIn(0, EDO_MAX))
            .apply()
    }

    private fun persistTouchKeyboardProgram(value: Int) {
        settingsPreferences().edit()
            .putInt(PREF_TOUCH_KEYBOARD_PROGRAM, value.coerceIn(GM_PROGRAM_MIN, GM_PROGRAM_MAX))
            .apply()
    }

    private fun persistTouchKeyboardProgramMidiOverride(enabled: Boolean) {
        settingsPreferences().edit()
            .putBoolean(PREF_TOUCH_KEYBOARD_PROGRAM_CONTROLS_MIDI, enabled)
            .apply()
    }

    private fun persistReverb(value: Int) {
        settingsPreferences().edit()
            .putInt(PREF_REVERB, value.coerceIn(REVERB_MIN, REVERB_MAX))
            .apply()
    }

    private fun persistAudioLatency(value: Int) {
        settingsPreferences().edit()
            .putInt(PREF_AUDIO_LATENCY_MS, roundedAudioLatencyMs(value.toFloat()))
            .apply()
    }

    private fun updateReadyState() {
        playbackUi.updateReadyState(
            hasScore = nativeParsedScore != null,
            loading = nativeLoading,
            playing = nativePlaying
        )
    }

    private fun applyNativeParsedScore(parsed: ParsedScore) {
        playbackUi.updateParsedScore(
            parsed = parsed,
            playheadSeconds = nativePlayheadSeconds,
            offsetCents = waterfallView.displayState().waterfallOffsetCents
        )
        resetToolbarPlayheadUpdateCache()
        updateToolbarPlayheadIfNeeded(force = true)
    }

    private fun applyCurrentKeybindToScore() {
        val source = sourceParsedScore ?: return
        val playbackScore = source.withKeybind(currentScaleGuide)
        nativeParsedScore = playbackScore
        waterfallView.setScore(playbackScore)
        if (!applyInitialWaterfallLeadIn()) {
            updateWaterfallPlayheadFrame()
        }
        resetNativeAudioScheduler()
        applyNativeParsedScore(playbackScore)
    }

    private fun stopNativeClock() {
        nativePlayback.stopClock()
    }

    private fun setNativePlaying(playing: Boolean) {
        nativePlayback.playOrPause(playing)
    }

    private fun resetNativePlayback() {
        resetToolbarPlayheadUpdateCache()
        nativePlayback.reset()
        applyInitialWaterfallLeadIn()
        resetNativeAudioScheduler()
        updateToolbarPlayheadIfNeeded(force = true)
    }

    private fun toggleNativePlayback() {
        if (isPlaybackStartSuppressed()) {
            return
        }
        applyInitialWaterfallLeadIn()
        nativePlayback.togglePlayback()
    }

    private fun applyInitialWaterfallLeadIn(): Boolean {
        val parsed = nativeParsedScore ?: return false
        if (
            nativePlaying ||
            nativeLoading ||
            !::waterfallView.isInitialized ||
            nativePlayheadSeconds > WaterfallMetrics.INITIAL_NOTE_GAP_PLAYHEAD_EPSILON
        ) {
            return false
        }
        val state = waterfallView.displayState()
        val visualPlayhead = WaterfallLayout(
            playheadSeconds = 0.0,
            pixelsPerSecond = state.pixelsPerSecond,
            pitchZoomScale = state.pitchZoomScale,
            pitchPanSemitones = state.pitchPanSemitones,
            waterfallOffsetCents = state.waterfallOffsetCents,
            density = resources.displayMetrics.density
        ).initialNoteDisplayPlayhead(parsed.notes) - positiveAudioLatencySecondsForLeadIn()
        if (abs(visualPlayhead - nativePlayheadSeconds) < 0.001) {
            return false
        }
        nativePlayheadSeconds = visualPlayhead
        updateWaterfallPlayheadFrame()
        updateToolbarPlayheadIfNeeded(force = true)
        return true
    }

    private fun positiveAudioLatencySecondsForLeadIn(): Double {
        return audioLatencyMs.coerceAtLeast(0) / 1_000.0
    }

    private fun suppressPlaybackStartBriefly() {
        playbackStartSuppressedUntil = SystemClock.uptimeMillis() + RESET_PLAY_SUPPRESSION_MS
    }

    private fun isPlaybackStartSuppressed(): Boolean {
        return SystemClock.uptimeMillis() < playbackStartSuppressedUntil
    }

    private fun isNativePaused(): Boolean {
        return nativePlayback.isPaused()
    }

    private fun updatePlayButtonIcon() {
        playbackUi.updatePlayButton(playing = nativePlaying, loading = nativeLoading)
    }

    private fun updateStageControls() {
        playbackUi.updateStageControls(
            hasScore = nativeParsedScore != null,
            playing = nativePlaying,
            loading = nativeLoading
        )
    }

    private fun updateNativePlayheadUi() {
        if (nativePlaying) {
            RenderFramePacer.notifyContentActive()
        }
        updateWaterfallPlayheadFrame()
        updateToolbarPlayheadIfNeeded()
    }

    private fun updateWaterfallPlayheadFrame() {
        if (!::waterfallView.isInitialized) {
            return
        }
        waterfallView.setPlayhead(nativePlayheadSeconds)
        rulerGlassOverlayView?.postInvalidateOnAnimation()
    }

    private fun updateToolbarPlayheadIfNeeded(force: Boolean = false) {
        val parsed = nativeParsedScore ?: return
        val now = System.nanoTime()
        if (!force &&
            lastToolbarPlayheadUpdateNanos > 0L &&
            now - lastToolbarPlayheadUpdateNanos < TOOLBAR_PLAYHEAD_UPDATE_NANOS
        ) {
            return
        }
        val snapshot = playbackUi.playheadSnapshot(
            parsed,
            nativePlayheadSeconds.coerceAtLeast(0.0)
        )
        lastToolbarPlayheadUpdateNanos = now
        if (!force && snapshot == lastToolbarPlayheadSnapshot) {
            return
        }
        lastToolbarPlayheadSnapshot = snapshot
        playbackUi.applyPlayheadSnapshot(snapshot)
    }

    private fun resetToolbarPlayheadUpdateCache() {
        lastToolbarPlayheadUpdateNanos = 0L
        lastToolbarPlayheadSnapshot = null
    }

    private fun canUseNativeAudio(): Boolean {
        return nativePlayback.canUseNativeAudio()
    }

    private fun startMidiInput() {
        val manager = MidiDeviceInputManager(
            this,
            object : MidiDeviceInputManager.Listener {
                override fun onDeviceConnected(device: MidiInputDevice) {
                    connectedMidiDevices[device.id] = device
                    updateMidiInputStatus(detail = null)
                    Toast.makeText(
                        this@MainActivity,
                        "MIDI device connected: ${device.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onDeviceDisconnected(device: MidiInputDevice) {
                    connectedMidiDevices.remove(device.id)
                    stopTrackedMidiNotes()
                    updateMidiInputStatus(detail = null)
                    Toast.makeText(
                        this@MainActivity,
                        "MIDI device disconnected: ${device.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onMidiEvent(event: MidiInputEvent) {
                    handleMidiInputEvent(event)
                }
            }
        )
        midiInputManager = manager
        if (manager.isSupported) {
            manager.start()
        } else {
            Log.i(TAG, "Android MIDI input is not supported on this device")
        }
    }

    private fun handleMidiInputEvent(event: MidiInputEvent) {
        when (event) {
            is MidiInputEvent.NoteOn -> startMidiNote(event)
            is MidiInputEvent.NoteOff -> releaseMidiNote(
                MidiNoteKey(
                    pitch = event.pitch,
                    channel = event.channel
                )
            )
            is MidiInputEvent.SustainPedal -> setMidiSustainPedal(event.down)
        }
    }

    private fun startMidiNote(event: MidiInputEvent.NoteOn) {
        val key = MidiNoteKey(
            pitch = event.pitch.coerceIn(MIDI_PITCH_MIN, MIDI_PITCH_MAX),
            channel = event.channel.coerceIn(MIDI_CHANNEL_MIN, MIDI_CHANNEL_MAX)
        )
        val velocity = event.velocity.coerceIn(MIDI_VELOCITY_MIN, MIDI_VELOCITY_MAX)
        stopSustainedMidiNotes(key)
        val previewPointerId = nextMidiPreviewPointerId()
        waterfallView.beginManualPreview(previewPointerId, midiPreviewNote(key, velocity))
        rulerGlassOverlayView?.postInvalidateOnAnimation()
        refreshHighRefreshRateRequest(force = true)
        val noteId = if (ensureMidiAudioReady()) {
            nativeAudioController.noteOn(
                key = playbackKeyForMidiInput(key.pitch),
                velocity = velocity,
                cents = playbackCentsForMidiInput(key.pitch),
                channel = key.channel,
                program = if (touchKeyboardProgramControlsMidi) {
                    touchKeyboardProgram
                } else {
                    GM_PROGRAM_DEFAULT
                }
            )
        } else {
            null
        }
        if (noteId != null && midiInputDetail != null) {
            updateMidiInputStatus(detail = null)
        }
        activeMidiNotes.getOrPut(key) { mutableListOf() } += MidiTrackedNote(
            noteId = noteId,
            previewPointerId = previewPointerId
        )
    }

    private fun releaseMidiNote(key: MidiNoteKey) {
        val note = removeLastMidiNote(activeMidiNotes, key) ?: return
        if (midiSustainPedalDown) {
            sustainedMidiNotes.getOrPut(key) { mutableListOf() } += note
        } else {
            stopMidiNote(note)
        }
    }

    private fun midiPreviewNote(key: MidiNoteKey, velocity: Int): WaterfallPreviewNote {
        val pitch = playbackPitchForMidiInput(key.pitch)
        val midiPitch = pitch.roundToInt().coerceIn(MIDI_PITCH_MIN, MIDI_PITCH_MAX)
        return WaterfallPreviewNote(
            pitch = pitch,
            visualPitch = pitch,
            midiPitch = midiPitch,
            cents = (pitch - midiPitch) * 100.0,
            velocity = velocity,
            track = key.channel
        )
    }

    private fun playbackPitchForMidiInput(midiPitch: Int): Double {
        return if (::currentScaleGuide.isInitialized) {
            currentScaleGuide.playbackPitchForMidiPitch(midiPitch)
        } else {
            midiPitch.toDouble()
        }
    }

    private fun playbackKeyForMidiInput(midiPitch: Int): Int {
        return playbackPitchForMidiInput(midiPitch)
            .roundToInt()
            .coerceIn(MIDI_PITCH_MIN, MIDI_PITCH_MAX)
    }

    private fun playbackCentsForMidiInput(midiPitch: Int): Float {
        val pitch = playbackPitchForMidiInput(midiPitch)
        val key = pitch.roundToInt().coerceIn(MIDI_PITCH_MIN, MIDI_PITCH_MAX)
        return ((pitch - key) * 100.0).toFloat()
    }

    private fun setMidiSustainPedal(down: Boolean) {
        if (midiSustainPedalDown == down) {
            return
        }
        midiSustainPedalDown = down
        updateMidiInputStatus()
        if (!down) {
            stopAllSustainedMidiNotes()
        }
    }

    private fun ensureMidiAudioReady(): Boolean {
        val audioReady = nativeAudioController.recoverStartedStream()
        nativePlayback.setAudioReady(audioReady)
        if (!audioReady) {
            updateMidiInputStatus(detail = "audio unavailable")
            return false
        }
        if (!canUseNativeAudio()) {
            prepareDefaultSoundFont()
            updateMidiInputStatus(detail = "loading SoundFont")
            return false
        }
        return true
    }

    private fun stopTrackedMidiNotes() {
        val notes = activeMidiNotes.values.flatten() + sustainedMidiNotes.values.flatten()
        activeMidiNotes.clear()
        sustainedMidiNotes.clear()
        midiSustainPedalDown = false
        notes.forEach { note -> stopMidiNote(note) }
    }

    private fun updateMidiInputStatus(detail: String? = midiInputDetail) {
        midiInputDetail = detail
        val connectedText = when (connectedMidiDevices.size) {
            0 -> ""
            1 -> "MIDI: ${connectedMidiDevices.values.first().name}"
            else -> "MIDI: ${connectedMidiDevices.size} devices"
        }
        val extras = mutableListOf<String>()
        detail?.let { extras += it }
        if (midiSustainPedalDown) {
            extras += "sustain"
        }
        val text = when {
            connectedText.isBlank() && extras.isEmpty() -> ""
            connectedText.isBlank() -> "MIDI: ${extras.joinToString(", ")}"
            extras.isEmpty() -> connectedText
            else -> "$connectedText (${extras.joinToString(", ")})"
        }
        playbackUi.setMidiStatusText(text)
    }

    private fun stopSustainedMidiNotes(key: MidiNoteKey) {
        sustainedMidiNotes.remove(key)?.forEach { note -> stopMidiNote(note) }
    }

    private fun stopAllSustainedMidiNotes() {
        val notes = sustainedMidiNotes.values.flatten()
        sustainedMidiNotes.clear()
        notes.forEach { note -> stopMidiNote(note) }
    }

    private fun removeLastMidiNote(
        notes: MutableMap<MidiNoteKey, MutableList<MidiTrackedNote>>,
        key: MidiNoteKey
    ): MidiTrackedNote? {
        val trackedNotes = notes[key] ?: return null
        if (trackedNotes.isEmpty()) {
            notes.remove(key)
            return null
        }
        val note = trackedNotes.removeAt(trackedNotes.lastIndex)
        if (trackedNotes.isEmpty()) {
            notes.remove(key)
        }
        return note
    }

    private fun stopMidiNote(note: MidiTrackedNote) {
        note.noteId?.let { noteId ->
            nativeAudioController.noteOff(noteId)
        }
        if (::waterfallView.isInitialized) {
            waterfallView.releaseManualPreview(note.previewPointerId)
            rulerGlassOverlayView?.postInvalidateOnAnimation()
            refreshHighRefreshRateRequest(force = true)
        }
    }

    private fun nextMidiPreviewPointerId(): Int {
        val pointerId = nextMidiPreviewPointerId
        nextMidiPreviewPointerId = if (pointerId >= MIDI_PREVIEW_POINTER_MAX) {
            MIDI_PREVIEW_POINTER_BASE
        } else {
            pointerId + 1
        }
        return pointerId
    }

    private fun resetNativeAudioScheduler() {
        nativePlayback.resetAudioScheduler(nativeParsedScore, nativePlayheadSeconds)
    }

    private fun stopNativeAudio() {
        nativePlayback.stopAudio()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun formatFps(value: Double): String {
        return String.format(Locale.US, "%.1f", value)
    }

    private fun formatMillis(nanos: Long): String {
        return String.format(Locale.US, "%.2f", nanos / 1_000_000.0)
    }

    companion object {
        private val DEFAULT_REFRESH_RATE_EXPERIMENT_MODE = RefreshRateExperimentMode.Full
        const val EDO_DEFAULT = 12
        const val EDO_MAX = 72
        const val PREFS_NAME = "xen_player_settings"
        const val PREF_EDO = "edo"
        const val PREF_TOUCH_KEYBOARD_PROGRAM = "touch_keyboard_program"
        const val PREF_TOUCH_KEYBOARD_PROGRAM_CONTROLS_MIDI = "touch_keyboard_program_controls_midi"
        const val PREF_REVERB = "reverb"
        const val PREF_AUDIO_LATENCY_MS = "audio_latency_ms"
        const val SPEED_MIN = 0.2
        const val SPEED_MAX = 4.0
        const val SPEED_STEP = 0.05
        const val SPEED_STEPS = 76
        const val VOLUME_GAIN_MIN = 0f
        const val VOLUME_GAIN_MAX = 6f
        const val VOLUME_GAIN_DEFAULT = 2.05f
        const val REVERB_MIN = 0
        const val REVERB_MAX = 100
        const val REVERB_DEFAULT = 54
        const val AUDIO_LATENCY_MIN_MS = -100
        const val AUDIO_LATENCY_MAX_MS = 700
        const val AUDIO_LATENCY_STEP_MS = 5
        const val AUDIO_LATENCY_DEFAULT_MS = 0
        const val VOLUME_GESTURE_VISIBLE_MS = 900L
        const val RESET_PLAY_SUPPRESSION_MS = 350L
        const val HIGH_REFRESH_KEEPALIVE_MS = 1_000L
        const val FORCE_WINDOW_REFRESH_ACTIVE = false
        const val HIGH_REFRESH_UI_REQUEST_NANOS = 250_000_000L
        const val UI_PULSE_LOG_FRAME_INTERVAL = 120
        const val EDO_STATUS_PROTECTION_MS = 700L
        const val CUSTOM_TUNING_FALLBACK_PROFILE = "TUN"
        const val MIDI_PITCH_MIN = 0
        const val MIDI_PITCH_MAX = 127
        const val MIDI_CHANNEL_MIN = 0
        const val MIDI_CHANNEL_MAX = 15
        const val MIDI_VELOCITY_MIN = 1
        const val MIDI_VELOCITY_MAX = 127
        const val GM_PROGRAM_MIN = 0
        const val GM_PROGRAM_MAX = 127
        const val GM_PROGRAM_DEFAULT = 0
        const val GM_PROGRAM_CONTROLS_MIDI_DEFAULT = false
        const val MIDI_PREVIEW_POINTER_BASE = 20_000
        const val MIDI_PREVIEW_POINTER_MAX = 120_000
        const val TAG = "XenSynth"
        const val BACKGROUND_ASSET_PATH = "drawable/waterfall.webp"
        val COLOR_STAGE: Int = Color.rgb(12, 10, 8)
        val COLOR_BUTTON: Int = Color.rgb(38, 43, 52)
        val COLOR_PANEL: Int = Color.rgb(32, 37, 45)
        val COLOR_METRIC_STROKE: Int = Color.rgb(58, 67, 80)
        val COLOR_ACTIVE: Int = Color.rgb(27, 102, 114)
        val COLOR_DIVIDER: Int = Color.rgb(88, 96, 110)
        val COLOR_TOOLBAR_TEXT: Int = Color.rgb(222, 230, 238)
        val COLOR_MUTED: Int = Color.rgb(166, 176, 190)

        val SCORE_MIME_TYPES = arrayOf(
            "audio/midi",
            "audio/x-midi",
            "audio/sp-midi",
            "application/x-musescore",
            "application/json",
            "text/json",
            "application/vnd.recordare.musicxml",
            "application/vnd.recordare.musicxml+xml",
            "application/octet-stream",
            "*/*"
        )

        fun speedToProgress(speed: Double): Int {
            return (((speed.coerceIn(SPEED_MIN, SPEED_MAX) - SPEED_MIN) / SPEED_STEP).roundToInt())
                .coerceIn(0, SPEED_STEPS)
        }

        fun progressToSpeed(progress: Int): Double {
            return SPEED_MIN + progress.coerceIn(0, SPEED_STEPS) * SPEED_STEP
        }
    }

    private enum class RefreshRateExperimentMode(
        val toolbarLabel: String,
        val displayModeEnabled: Boolean,
        val windowRefreshRateEnabled: Boolean,
        val surfaceFrameRateEnabled: Boolean,
        val viewRequestedFrameRateEnabled: Boolean
    ) {
        Full("FULL", true, true, true, true),
        ModeOnly("MODE", true, false, false, false),
        ModeSurface("M+SURF", true, false, true, false),
        ModeView("M+VIEW", true, false, false, true),
        ModeSurfaceView("M+S+V", true, false, true, true);

        fun logSummary(): String {
            return "displayMode=$displayModeEnabled " +
                "window=$windowRefreshRateEnabled " +
                "surface=$surfaceFrameRateEnabled " +
                "view=$viewRequestedFrameRateEnabled"
        }

        companion object {
            fun fromToolbarLabel(label: String): RefreshRateExperimentMode? {
                return entries.firstOrNull { it.toolbarLabel == label }
            }
        }
    }

    private data class MidiNoteKey(
        val pitch: Int,
        val channel: Int
    )

    private data class MidiTrackedNote(
        val noteId: Int?,
        val previewPointerId: Int
    )
}

@Composable
private fun XenSynthMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = XenActive,
            onPrimary = ComposeColor.White,
            surface = XenPanel,
            onSurface = XenToolbarText,
            surfaceVariant = XenButton,
            onSurfaceVariant = XenToolbarText,
            background = XenStage,
            onBackground = XenToolbarText
        ),
        content = content
    )
}

@Composable
private fun XenToolbar(
    state: ShellUiState,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onReset: () -> Unit,
    onTerminate: () -> Unit,
    onEdoChanged: (Float) -> Unit,
    onEdoTextChanged: (Int) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onSpeedTextChanged: (Double) -> Unit,
    onOffsetChanged: (Float) -> Unit,
    onOffsetTextChanged: (Int) -> Unit,
    onTouchKeyboardProgramChanged: (Float) -> Unit,
    onTouchKeyboardProgramTextChanged: (Int) -> Unit,
    onTouchKeyboardProgramMidiOverrideChanged: (Boolean) -> Unit,
    onAudioLatencyChanged: (Float) -> Unit,
    onReverbChanged: (Float) -> Unit,
    onRefreshRateExperimentSelected: (String) -> Unit,
    onFocusClearerChanged: ((() -> Unit)?) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var speedTextFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var edoTextFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var offsetTextFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var programTextFieldBounds by remember { mutableStateOf<Rect?>(null) }
    val metricControlWidth = 64
    DisposableEffect(focusManager) {
        onFocusClearerChanged { focusManager.clearFocus() }
        onDispose { onFocusClearerChanged(null) }
    }
    LaunchedEffect(state.tuningValueEditable) {
        if (!state.tuningValueEditable) {
            edoTextFieldBounds = null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(
                focusManager,
                speedTextFieldBounds,
                edoTextFieldBounds,
                offsetTextFieldBounds,
                programTextFieldBounds
            ) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val downPosition = event.changes
                            .firstOrNull { it.changedToDownIgnoreConsumed() }
                            ?.position
                        val textFieldBounds = listOfNotNull(
                            speedTextFieldBounds,
                            edoTextFieldBounds,
                            offsetTextFieldBounds,
                            programTextFieldBounds
                        )
                        if (downPosition != null && textFieldBounds.none { it.contains(downPosition) }) {
                            focusManager.clearFocus()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconControlButton(
                icon = painterResource(R.drawable.ic_folder_24),
                description = "Open",
                active = false,
                enabled = state.openEnabled,
                onClick = onOpen
            )
            ToolbarDivider()
            IconControlButton(
                icon = painterResource(state.playIconResId),
                description = state.playDescription,
                active = state.playDescription != "Play",
                enabled = state.playEnabled,
                onClick = onPlay
            )
            IconControlButton(
                icon = painterResource(R.drawable.ic_reset_24),
                description = "Reset",
                active = false,
                enabled = state.resetEnabled,
                onClick = onReset
            )
            IconControlButton(
                icon = painterResource(R.drawable.ic_terminate_24),
                description = "Terminate",
                active = false,
                enabled = state.resetEnabled,
                onClick = onTerminate
            )
            ToolbarDivider()
            EditableMetricSliderTile(
                label = "SPEED",
                displayValue = state.speed.speedMultiplierLabel(),
                inputValue = state.speed.speedMultiplierLabel(),
                valueEditable = true,
                width = metricControlWidth,
                sliderValue = state.speedProgress,
                range = 0f..MainActivity.SPEED_STEPS.toFloat(),
                steps = MainActivity.SPEED_STEPS - 1,
                enabled = state.speedEnabled,
                textFieldWidth = 44.dp,
                keyboardType = KeyboardType.Decimal,
                onValueChange = onSpeedChanged,
                onTextValueChange = { text ->
                    text.toDoubleOrNull()?.let(onSpeedTextChanged)
                },
                sanitizeTextInput = ::sanitizeSpeedInput,
                onTextFieldBoundsChanged = { speedTextFieldBounds = it }
            )
            EditableMetricSliderTile(
                label = state.tuningLabel,
                displayValue = state.tuningValue,
                inputValue = state.edo.toString(),
                valueEditable = state.tuningValueEditable,
                marqueeDisplayValue = !state.tuningValueEditable,
                width = metricControlWidth,
                sliderValue = state.edoProgress,
                range = 0f..MainActivity.EDO_MAX.toFloat(),
                steps = MainActivity.EDO_MAX - 1,
                enabled = state.edoEnabled,
                onValueChange = onEdoChanged,
                onTextValueChange = { text ->
                    text.toIntOrNull()
                        ?.coerceIn(0, MainActivity.EDO_MAX)
                        ?.let(onEdoTextChanged)
                },
                sanitizeTextInput = { text ->
                    sanitizeUnsignedIntInput(text, MainActivity.EDO_MAX)
                },
                onTextFieldBoundsChanged = { edoTextFieldBounds = it }
            )
            EditableMetricSliderTile(
                label = "OFFSET",
                displayValue = state.offset,
                inputValue = state.offset,
                valueEditable = true,
                width = metricControlWidth,
                sliderValue = state.offsetCents,
                range = -WaterfallMetrics.OFFSET_CENT_RANGE.toFloat()..WaterfallMetrics.OFFSET_CENT_RANGE.toFloat(),
                steps = 0,
                enabled = state.audioControlsEnabled,
                textFieldWidth = 48.dp,
                keyboardType = KeyboardType.Text,
                onValueChange = onOffsetChanged,
                onTextValueChange = { text ->
                    text.toIntOrNull()?.let(onOffsetTextChanged)
                },
                sanitizeTextInput = { text ->
                    sanitizeSignedIntInput(
                        text,
                        WaterfallMetrics.OFFSET_CENT_RANGE.roundToInt()
                    )
                },
                onTextFieldBoundsChanged = { offsetTextFieldBounds = it },
                showProgressTrack = false
            )
            ToolbarDivider()
            ToolbarSettingsMenu(
                state = state,
                onTouchKeyboardProgramChanged = onTouchKeyboardProgramChanged,
                onTouchKeyboardProgramTextChanged = onTouchKeyboardProgramTextChanged,
                onTouchKeyboardProgramMidiOverrideChanged = onTouchKeyboardProgramMidiOverrideChanged,
                onAudioLatencyChanged = onAudioLatencyChanged,
                onReverbChanged = onReverbChanged,
                onProgramTextFieldBoundsChanged = { programTextFieldBounds = it }
            )
            if (SHOW_REFRESH_DIAGNOSTIC_CONTROLS) {
                RefreshRateExperimentMenu(
                    selectedLabel = state.refreshRateExperimentLabel,
                    onSelected = onRefreshRateExperimentSelected
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .padding(start = 2.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.End
            ) {
                val statusText = listOf(state.status, state.midiStatus)
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
                if (statusText.isNotBlank()) {
                    ToolbarTitleText(statusText)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                MeterProgressText(
                    bpm = state.bpm.adjustedBpm(state.speed),
                    meter = state.meter,
                    progress = state.progress
                )
            }
        }
    }
}

private class OpenScoreFileContract : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val mimeTypes = input.ifEmpty { arrayOf("*/*") }
        return Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return null
        }
        return intent.data ?: intent.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.uri
    }
}

@Composable
private fun RefreshRateExperimentMenu(
    selectedLabel: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .padding(end = 6.dp)
            .width(82.dp)
            .height(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = XenPanel,
        border = BorderStroke(1.dp, XenMetricStroke)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CompactMetricText(
                    text = "RATE",
                    color = XenMuted,
                    fontSize = 10.sp,
                    lineHeight = 11.sp
                )
                CompactMetricText(
                    text = selectedLabel,
                    color = XenToolbarText,
                    fontSize = 12.sp,
                    lineHeight = 13.sp
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(XenPanel)
            ) {
                listOf("FULL", "MODE", "M+SURF", "M+VIEW", "M+S+V").forEach { label ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (label == selectedLabel) XenToolbarText else XenMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(label)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarTitleText(text: String) {
    Text(
        modifier = Modifier
            .widthIn(max = TOOLBAR_TITLE_MAX_WIDTH_DP.dp)
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                repeatDelayMillis = 1_200,
                initialDelayMillis = 800
            ),
        text = text,
        color = XenMuted,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.End
    )
}

@Composable
private fun MeterProgressText(bpm: String, meter: String, progress: String) {
    ToolbarStatusText(text = "$bpm BPM | $meter | $progress")
}

@Composable
private fun ToolbarStatusText(text: String) {
    Text(
        text = text,
        color = XenMuted,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.End
    )
}

@Composable
private fun VolumeGestureOverlay(
    state: ShellUiState,
    onExpired: () -> Unit
) {
    val visible = state.volumeGestureVisible
    val revision = state.volumeGestureRevision
    LaunchedEffect(visible, revision) {
        if (visible) {
            delay(MainActivity.VOLUME_GESTURE_VISIBLE_MS)
            onExpired()
        }
    }
    if (!visible) {
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 76.dp, end = 20.dp, bottom = 32.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            modifier = Modifier
                .width(58.dp)
                .height(176.dp),
            shape = RoundedCornerShape(8.dp),
            color = XenPanel.copy(alpha = 0.86f),
            border = BorderStroke(1.dp, XenMetricStroke.copy(alpha = 0.92f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                CompactMetricText(
                    text = "VOL",
                    color = XenMuted,
                    fontSize = 10.sp,
                    lineHeight = 11.sp
                )
                VolumeLevelBar(
                    fraction = ((state.volumeGain - MainActivity.VOLUME_GAIN_MIN) /
                        (MainActivity.VOLUME_GAIN_MAX - MainActivity.VOLUME_GAIN_MIN))
                        .coerceIn(0f, 1f)
                )
                CompactMetricText(
                    text = state.volume,
                    color = XenToolbarText,
                    fontSize = 13.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun VolumeLevelBar(fraction: Float) {
    Box(
        modifier = Modifier
            .width(12.dp)
            .height(104.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(XenMetricStroke),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction)
                .clip(RoundedCornerShape(6.dp))
                .background(XenActive)
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, width: Int) {
    Surface(
        modifier = Modifier
            .padding(end = 6.dp)
            .width(width.dp)
            .height(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = XenPanel,
        border = BorderStroke(1.dp, XenMetricStroke)
    ) {
        MetricText(label = label, value = value)
    }
}

@Composable
private fun MetricButtonTile(
    label: String,
    value: String,
    width: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(end = 6.dp)
            .width(width.dp)
            .height(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = XenPanel,
        border = BorderStroke(1.dp, XenMetricStroke),
        enabled = enabled,
        onClick = onClick
    ) {
        MetricText(label = label, value = value)
    }
}

@Composable
private fun MetricSliderTile(
    label: String,
    value: String,
    width: Int,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    showProgressTrack: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val popupOffsetX = ((width - COMPACT_SLIDER_WIDTH_DP) / 2).dp
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(width.dp)
                .height(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = XenPanel,
            border = BorderStroke(1.dp, XenMetricStroke),
            enabled = enabled,
            onClick = { expanded = true }
        ) {
            MetricText(label = label, value = value)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = popupOffsetX, y = 0.dp),
            modifier = Modifier
                .background(XenPanel)
                .width(COMPACT_SLIDER_WIDTH_DP.dp)
        ) {
            CompactSlider(
                value = sliderValue,
                onValueChange = onValueChange,
                range = range,
                steps = steps,
                enabled = enabled,
                showProgressTrack = showProgressTrack
            )
        }
    }
}

private fun sanitizeUnsignedIntInput(text: String, maxValue: Int): String {
    val digits = text
        .filter { it in '0'..'9' }
        .take(maxValue.toString().length)
    return digits.toIntOrNull()
        ?.coerceIn(0, maxValue)
        ?.toString()
        ?: digits
}

private fun sanitizeSpeedInput(text: String): String {
    var dotSeen = false
    val filtered = buildString {
        for (char in text) {
            when {
                char in '0'..'9' -> append(char)
                char == '.' && !dotSeen -> {
                    append(char)
                    dotSeen = true
                }
            }
        }
    }
    val dotIndex = filtered.indexOf('.')
    if (dotIndex < 0) {
        return filtered.take(1)
    }
    val whole = filtered.substring(0, dotIndex).take(1)
    val decimal = filtered.substring(dotIndex + 1).take(2)
    return "$whole.$decimal"
}

private fun sanitizeSignedIntInput(text: String, maxAbsoluteValue: Int): String {
    var sign: Char? = null
    val digits = StringBuilder()
    for (char in text) {
        when {
            (char == '-' || char == '+') && sign == null && digits.isEmpty() -> sign = char
            char in '0'..'9' -> digits.append(char)
        }
    }
    val limitedDigits = digits.toString().take(maxAbsoluteValue.toString().length)
    val value = limitedDigits.toIntOrNull() ?: return sign?.toString() ?: ""
    val clamped = value.coerceAtMost(maxAbsoluteValue)
    val prefix = sign?.toString().orEmpty()
    return "$prefix$clamped"
}

@Composable
private fun EditableMetricSliderTile(
    label: String,
    displayValue: String,
    inputValue: String,
    valueEditable: Boolean,
    marqueeDisplayValue: Boolean = false,
    width: Int,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    textFieldWidth: Dp = 38.dp,
    keyboardType: KeyboardType = KeyboardType.Number,
    onValueChange: (Float) -> Unit,
    onTextValueChange: (String) -> Unit,
    sanitizeTextInput: (String) -> String,
    onTextFieldBoundsChanged: (Rect?) -> Unit,
    showProgressTrack: Boolean = true,
    popupContent: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val popupOffsetX = ((width - COMPACT_SLIDER_WIDTH_DP) / 2).dp
    var inputFocused by remember { mutableStateOf(false) }
    var textValue by remember {
        val text = displayValue
        mutableStateOf(TextFieldValue(text, selection = TextRange(text.length)))
    }
    val focusManager = LocalFocusManager.current

    DisposableEffect(valueEditable) {
        if (!valueEditable) {
            onTextFieldBoundsChanged(null)
        }
        onDispose { onTextFieldBoundsChanged(null) }
    }

    LaunchedEffect(inputValue, displayValue, inputFocused, valueEditable) {
        if (!inputFocused) {
            val text = if (valueEditable) inputValue else displayValue
            textValue = TextFieldValue(text, selection = TextRange(text.length))
        }
    }

    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(width.dp)
                .height(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = XenPanel,
            border = BorderStroke(1.dp, XenMetricStroke)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(
                    space = 1.dp,
                    alignment = Alignment.CenterVertically
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(11.dp)
                        .clickable(enabled = enabled) { expanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    CompactMetricText(
                        text = label,
                        color = XenMuted,
                        fontSize = 10.sp,
                        lineHeight = 11.sp
                    )
                }
                if (valueEditable) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = { nextValue ->
                            val nextText = sanitizeTextInput(nextValue.text)
                            textValue = TextFieldValue(
                                text = nextText,
                                selection = TextRange(nextText.length)
                            )
                            onTextValueChange(nextText)
                        },
                        modifier = Modifier
                            .width(textFieldWidth)
                            .height(17.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .onGloballyPositioned { coordinates ->
                                onTextFieldBoundsChanged(coordinates.boundsInRoot())
                            }
                            .onFocusChanged { focusState ->
                                val nowFocused = focusState.isFocused
                                if (nowFocused && !inputFocused) {
                                    textValue = textValue.copy(
                                        selection = TextRange(0, textValue.text.length)
                                    )
                                } else if (!nowFocused && inputFocused) {
                                    val text = inputValue
                                    textValue = TextFieldValue(
                                        text,
                                        selection = TextRange(text.length)
                                    )
                                }
                                inputFocused = nowFocused
                            },
                        enabled = enabled,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (enabled) XenToolbarText else XenMuted,
                            fontSize = 12.sp,
                            lineHeight = 13.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(XenActive),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                innerTextField()
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(17.dp)
                            .clickable(enabled = enabled) { expanded = true },
                        contentAlignment = Alignment.Center
                    ) {
                        CompactMetricText(
                            text = displayValue,
                            color = if (enabled) XenToolbarText else XenMuted,
                            fontSize = 12.sp,
                            lineHeight = 13.sp,
                            marquee = marqueeDisplayValue
                        )
                    }
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = popupOffsetX, y = 0.dp),
            modifier = Modifier
                .background(XenPanel)
                .width(COMPACT_SLIDER_WIDTH_DP.dp)
        ) {
            CompactSlider(
                value = sliderValue,
                onValueChange = onValueChange,
                range = range,
                steps = steps,
                enabled = enabled,
                showProgressTrack = showProgressTrack
            )
            popupContent?.invoke()
        }
    }
}

@Composable
private fun ToolbarSettingsMenu(
    state: ShellUiState,
    onTouchKeyboardProgramChanged: (Float) -> Unit,
    onTouchKeyboardProgramTextChanged: (Int) -> Unit,
    onTouchKeyboardProgramMidiOverrideChanged: (Boolean) -> Unit,
    onAudioLatencyChanged: (Float) -> Unit,
    onReverbChanged: (Float) -> Unit,
    onProgramTextFieldBoundsChanged: (Rect?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val popupOffsetX = ((48 - COMPACT_SLIDER_WIDTH_DP) / 2).dp

    LaunchedEffect(expanded) {
        if (!expanded) {
            onProgramTextFieldBoundsChanged(null)
        }
    }

    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        FilledIconButton(
            onClick = {
                if (expanded) {
                    focusManager.clearFocus()
                }
                expanded = !expanded
            },
            modifier = Modifier.size(width = 48.dp, height = 44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (expanded) XenActive else XenButton,
                contentColor = ComposeColor.White,
                disabledContainerColor = XenPanel,
                disabledContentColor = XenMuted
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings_24),
                contentDescription = "Settings",
                modifier = Modifier.size(22.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusManager.clearFocus()
                expanded = false
            },
            offset = DpOffset(x = popupOffsetX, y = 0.dp),
            modifier = Modifier
                .background(XenPanel)
                .width(COMPACT_SLIDER_WIDTH_DP.dp)
        ) {
            SettingsTextFieldRow(
                label = "PROG NUM",
                inputValue = state.touchKeyboardProgram.toString(),
                enabled = state.audioControlsEnabled,
                keyboardType = KeyboardType.Number,
                onTextValueChange = { text ->
                    text.toIntOrNull()?.let(onTouchKeyboardProgramTextChanged)
                },
                sanitizeTextInput = { text ->
                    sanitizeUnsignedIntInput(text, MainActivity.GM_PROGRAM_MAX)
                },
                onTextFieldBoundsChanged = onProgramTextFieldBoundsChanged
            )
            CompactSlider(
                value = state.touchKeyboardProgram.toFloat(),
                onValueChange = onTouchKeyboardProgramChanged,
                range = MainActivity.GM_PROGRAM_MIN.toFloat()..MainActivity.GM_PROGRAM_MAX.toFloat(),
                steps = MainActivity.GM_PROGRAM_MAX - MainActivity.GM_PROGRAM_MIN - 1,
                enabled = state.audioControlsEnabled
            )
            MidiProgramOverrideToggle(
                checked = state.touchKeyboardProgramControlsMidi,
                enabled = state.audioControlsEnabled,
                onCheckedChange = onTouchKeyboardProgramMidiOverrideChanged
            )
            SettingsValueRow(
                label = "LATENCY",
                value = "${state.audioLatencyMs} ms",
                enabled = true
            )
            CompactSlider(
                value = state.audioLatencyMs.toFloat(),
                onValueChange = onAudioLatencyChanged,
                range = MainActivity.AUDIO_LATENCY_MIN_MS.toFloat()..
                    MainActivity.AUDIO_LATENCY_MAX_MS.toFloat(),
                steps = (MainActivity.AUDIO_LATENCY_MAX_MS - MainActivity.AUDIO_LATENCY_MIN_MS) /
                    MainActivity.AUDIO_LATENCY_STEP_MS - 1,
                enabled = true
            )
            SettingsValueRow(
                label = "REVERB",
                value = "${state.reverb}%",
                enabled = state.audioControlsEnabled
            )
            CompactSlider(
                value = state.reverb.toFloat(),
                onValueChange = onReverbChanged,
                range = MainActivity.REVERB_MIN.toFloat()..MainActivity.REVERB_MAX.toFloat(),
                steps = MainActivity.REVERB_MAX - MainActivity.REVERB_MIN - 1,
                enabled = state.audioControlsEnabled
            )
        }
    }
}

@Composable
private fun SettingsTextFieldRow(
    label: String,
    inputValue: String,
    enabled: Boolean,
    keyboardType: KeyboardType,
    onTextValueChange: (String) -> Unit,
    sanitizeTextInput: (String) -> String,
    onTextFieldBoundsChanged: (Rect?) -> Unit
) {
    var inputFocused by remember { mutableStateOf(false) }
    var textValue by remember {
        mutableStateOf(TextFieldValue(inputValue, selection = TextRange(inputValue.length)))
    }
    val focusManager = LocalFocusManager.current

    DisposableEffect(Unit) {
        onDispose { onTextFieldBoundsChanged(null) }
    }

    LaunchedEffect(inputValue, inputFocused) {
        if (!inputFocused) {
            textValue = TextFieldValue(inputValue, selection = TextRange(inputValue.length))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            color = if (enabled) XenToolbarText else XenMuted,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace
        )
        BasicTextField(
            value = textValue,
            onValueChange = { nextValue ->
                val nextText = sanitizeTextInput(nextValue.text)
                textValue = TextFieldValue(
                    text = nextText,
                    selection = TextRange(nextText.length)
                )
                onTextValueChange(nextText)
            },
            modifier = Modifier
                .width(46.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(XenMetricStroke.copy(alpha = 0.32f))
                .onGloballyPositioned { coordinates ->
                    onTextFieldBoundsChanged(coordinates.boundsInRoot())
                }
                .onFocusChanged { focusState ->
                    val nowFocused = focusState.isFocused
                    if (nowFocused && !inputFocused) {
                        textValue = textValue.copy(
                            selection = TextRange(0, textValue.text.length)
                        )
                    } else if (!nowFocused && inputFocused) {
                        textValue = TextFieldValue(
                            inputValue,
                            selection = TextRange(inputValue.length)
                        )
                    }
                    inputFocused = nowFocused
                },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = if (enabled) XenToolbarText else XenMuted,
                fontSize = 12.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(XenActive),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            color = if (enabled) XenToolbarText else XenMuted,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = if (enabled) XenToolbarText else XenMuted,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun MidiProgramOverrideToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = "EXT MIDI",
            color = if (enabled) XenToolbarText else XenMuted,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.scale(0.78f)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    showProgressTrack: Boolean = true
) {
    Box(
        modifier = Modifier
            .width(COMPACT_SLIDER_WIDTH_DP.dp)
            .height(32.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            thumb = {
                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (enabled) XenActive else XenMuted)
                    )
                }
            },
            track = {
                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(XenMetricStroke)
                    )
                    if (showProgressTrack) {
                        val fraction = ((value - range.start) / (range.endInclusive - range.start))
                            .coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (enabled) XenActive else XenMuted)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun MetricText(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(
            space = 1.dp,
            alignment = Alignment.CenterVertically
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CompactMetricText(
            text = label,
            color = XenMuted,
            fontSize = 10.sp,
            lineHeight = 11.sp
        )
        CompactMetricText(
            text = value,
            color = XenToolbarText,
            fontSize = 12.sp,
            lineHeight = 13.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactMetricText(
    text: String,
    color: ComposeColor,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    marquee: Boolean = false
) {
    val modifier = if (marquee) {
        Modifier
            .fillMaxWidth()
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                repeatDelayMillis = 1_200,
                initialDelayMillis = 800
            )
    } else {
        Modifier
    }
    Text(
        modifier = modifier,
        text = text,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = 1,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun ToolbarDivider() {
    Spacer(
        modifier = Modifier
            .padding(start = 4.dp, end = 10.dp)
            .width(1.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(XenDivider)
    )
}

@Composable
private fun IconControlButton(
    icon: Painter,
    description: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(end = 6.dp)
            .size(width = 48.dp, height = 44.dp),
        shape = RoundedCornerShape(8.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) XenActive else XenButton,
            contentColor = ComposeColor.White,
            disabledContainerColor = XenPanel,
            disabledContentColor = XenMuted
        )
    ) {
        Icon(
            painter = icon,
            contentDescription = description,
            modifier = Modifier.size(22.dp)
        )
    }
}

private val XenStage = colorInt(MainActivity.COLOR_STAGE)
private val XenButton = colorInt(MainActivity.COLOR_BUTTON).copy(alpha = 0.42f)
private val XenPanel = colorInt(MainActivity.COLOR_PANEL).copy(alpha = 0.32f)
private val XenMetricStroke = colorInt(MainActivity.COLOR_METRIC_STROKE).copy(alpha = 0.52f)
private val XenActive = colorInt(MainActivity.COLOR_ACTIVE).copy(alpha = 0.74f)
private val XenDivider = colorInt(MainActivity.COLOR_DIVIDER).copy(alpha = 0.34f)
private val XenToolbarText = colorInt(MainActivity.COLOR_TOOLBAR_TEXT)
private val XenMuted = colorInt(MainActivity.COLOR_MUTED)

private fun colorInt(value: Int): ComposeColor = ComposeColor(value)
