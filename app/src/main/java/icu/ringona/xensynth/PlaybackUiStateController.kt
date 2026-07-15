package icu.ringona.xensynth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import icu.ringona.xensynth.midi.MidiWaterfallParser
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.view.WaterfallMetrics
import java.util.Locale
import kotlin.math.roundToInt

internal class PlaybackUiStateController(
    private val state: ShellUiState
) {
    fun setStatusText(text: String) {
        state.status = text
    }

    fun setMidiStatusText(text: String) {
        state.midiStatus = text
    }

    fun updateReadyState(hasScore: Boolean, loading: Boolean, playing: Boolean) {
        state.audioControlsEnabled = true
        state.speedEnabled = true
        state.edoEnabled = true
        updatePlaybackControls(hasScore, playing, loading)
    }

    fun updatePlaybackControls(hasScore: Boolean, playing: Boolean, loading: Boolean) {
        updatePlayButton(playing, loading)
        updateStageControls(hasScore, playing, loading)
    }

    fun updatePlayButton(playing: Boolean, loading: Boolean) {
        state.playIconResId = if (playing || loading) {
            R.drawable.ic_pause_24
        } else {
            R.drawable.ic_play_24
        }
        state.playDescription = when {
            loading -> "Loading"
            playing -> "Pause"
            else -> "Play"
        }
    }

    fun updateStageControls(hasScore: Boolean, playing: Boolean, loading: Boolean) {
        state.openEnabled = !playing && !loading
        state.playEnabled = hasScore && !loading
        state.resetEnabled = hasScore
    }

    fun updateParsedScore(parsed: ParsedScore, playheadSeconds: Double, offsetCents: Double) {
        applyPlayheadSnapshot(playheadSnapshot(parsed, playheadSeconds))
        updateOffsetDisplay(offsetCents)
        state.status = parsed.titleStatusText()
    }

    fun updatePlayhead(score: ParsedScore?, playheadSeconds: Double) {
        if (score == null) {
            return
        }
        applyPlayheadSnapshot(playheadSnapshot(score, playheadSeconds))
    }

    fun playheadSnapshot(score: ParsedScore, playheadSeconds: Double): PlaybackToolbarSnapshot {
        val metrics = score.metricsAt(playheadSeconds)
        return PlaybackToolbarSnapshot(
            bpm = metrics.bpm,
            meter = metrics.meter,
            progress = score.durationText(playheadSeconds)
        )
    }

    fun applyPlayheadSnapshot(snapshot: PlaybackToolbarSnapshot) {
        if (state.bpm != snapshot.bpm) {
            state.bpm = snapshot.bpm
        }
        if (state.meter != snapshot.meter) {
            state.meter = snapshot.meter
        }
        if (state.progress != snapshot.progress) {
            state.progress = snapshot.progress
        }
    }

    fun updateOffsetDisplay(cents: Double) {
        state.offsetCents = cents.toFloat()
        state.offset = cents.formatCents()
    }

    fun updateTouchKeyboardProgram(program: Int) {
        state.touchKeyboardProgram = program.coerceIn(
            MainActivity.GM_PROGRAM_MIN,
            MainActivity.GM_PROGRAM_MAX
        )
    }

    fun updateTouchKeyboardProgramMidiOverride(enabled: Boolean) {
        state.touchKeyboardProgramControlsMidi = enabled
    }

    fun updateVolume(gain: Float) {
        state.volumeGain = gain
        state.volume = gain.formatVolumePercent()
    }

    fun updateReverb(value: Int) {
        state.reverb = value.coerceIn(
            MainActivity.REVERB_MIN,
            MainActivity.REVERB_MAX
        )
    }

    fun updateAudioLatency(value: Int) {
        state.audioLatencyMs = value.coerceIn(
            MainActivity.AUDIO_LATENCY_MIN_MS,
            MainActivity.AUDIO_LATENCY_MAX_MS
        )
    }

    fun showVolumeGesture() {
        state.volumeGestureVisible = true
        state.volumeGestureRevision += 1
    }

    fun hideVolumeGesture() {
        state.volumeGestureVisible = false
    }

    fun updateSpeed(progress: Int, speed: Double) {
        state.speedProgress = progress.toFloat()
        state.speed = speed
    }

    fun updateEdo(value: Int, updateProgress: Boolean) {
        state.edo = value
        if (updateProgress) {
            state.edoProgress = value.toFloat()
        }
        if (!state.customTuningActive) {
            state.tuningLabel = "EDO"
            state.tuningValue = value.toString()
            state.tuningValueEditable = true
        }
    }

    fun updateCustomTuning(profileName: String) {
        state.customTuningActive = true
        state.tuningLabel = "TUN"
        state.tuningValue = profileName
        state.tuningValueEditable = false
    }

    fun clearCustomTuning() {
        state.customTuningActive = false
        state.tuningLabel = "EDO"
        state.tuningValue = state.edo.toString()
        state.tuningValueEditable = true
    }

    fun resetScoreSummary() {
        state.bpm = "120.0"
        state.meter = "4/4"
        state.progress = "0:00/0:00"
        state.status = "No file loaded"
    }
}

internal class ShellUiState {
    var bpm by mutableStateOf("120.0")
    var meter by mutableStateOf("4/4")
    var edo by mutableStateOf(12)
    var tuningLabel by mutableStateOf("EDO")
    var tuningValue by mutableStateOf("12")
    var tuningValueEditable by mutableStateOf(true)
    var customTuningActive by mutableStateOf(false)
    var edoProgress by mutableStateOf(12f)
    var speed by mutableStateOf(1.0)
    var speedProgress by mutableStateOf(MainActivity.speedToProgress(1.0).toFloat())
    var offsetCents by mutableStateOf(0f)
    var offset by mutableStateOf("0 c")
    var touchKeyboardProgram by mutableStateOf(MainActivity.GM_PROGRAM_DEFAULT)
    var touchKeyboardProgramControlsMidi by mutableStateOf(MainActivity.GM_PROGRAM_CONTROLS_MIDI_DEFAULT)
    var keyboardLayoutMode by mutableStateOf(KeyboardLayoutMode.Linear)
    var hexKeyboardColumns by mutableStateOf(MainActivity.HEX_COLUMNS_DEFAULT)
    var hexKeyboardRows by mutableStateOf(MainActivity.HEX_ROWS_DEFAULT)
    var hexKeyboardStepQ by mutableStateOf(MainActivity.HEX_STEP_Q_DEFAULT)
    var hexKeyboardStepR by mutableStateOf(MainActivity.HEX_STEP_R_DEFAULT)
    var hexOctaveGroupingEnabled by mutableStateOf(MainActivity.HEX_OCTAVE_GROUPING_DEFAULT)
    var hexTouchSensitivityPercent by mutableStateOf(MainActivity.HEX_TOUCH_SENSITIVITY_DEFAULT)
    var hexPreviewSeconds by mutableStateOf(MainActivity.HEX_PREVIEW_SECONDS_DEFAULT)
    var hexPseudoPressureEnabled by mutableStateOf(MainActivity.HEX_PSEUDO_PRESSURE_DEFAULT)
    var hexDisplayMode by mutableStateOf(MainActivity.HEX_DISPLAY_MODE_DEFAULT)
    var volumeGain by mutableStateOf(MainActivity.VOLUME_GAIN_DEFAULT)
    var volume by mutableStateOf(MainActivity.VOLUME_GAIN_DEFAULT.formatVolumePercent())
    var reverb by mutableStateOf(MainActivity.REVERB_DEFAULT)
    var audioLatencyMs by mutableStateOf(MainActivity.AUDIO_LATENCY_DEFAULT_MS)
    var volumeGestureVisible by mutableStateOf(false)
    var volumeGestureRevision by mutableStateOf(0)
    var progress by mutableStateOf("0:00/0:00")
    var status by mutableStateOf("No file loaded")
    var midiStatus by mutableStateOf("")
    var openEnabled by mutableStateOf(true)
    var playEnabled by mutableStateOf(false)
    var resetEnabled by mutableStateOf(false)
    var audioControlsEnabled by mutableStateOf(true)
    var speedEnabled by mutableStateOf(true)
    var edoEnabled by mutableStateOf(true)
    var playIconResId by mutableStateOf(R.drawable.ic_play_24)
    var playDescription by mutableStateOf("Play")
    var refreshRateExperimentLabel by mutableStateOf("FULL")
}

internal data class PlaybackToolbarSnapshot(
    val bpm: String,
    val meter: String,
    val progress: String
)

private data class ScoreMetrics(
    val bpm: String,
    val meter: String
)

private fun ParsedScore.metricsAt(second: Double): ScoreMetrics {
    val safeSecond = second.coerceAtLeast(0.0)
    val tempo = tempoMap.lastOrNull { it.second <= safeSecond } ?: tempoMap.firstOrNull()
    val currentTick = MidiWaterfallParser.secondsToTick(safeSecond, tempoMap, ticksPerQuarter)
    val meter = meters.lastOrNull { it.tick <= currentTick } ?: meters.firstOrNull()
    val bpm = if (tempo != null && tempo.usPerQuarter > 0.0) {
        String.format(Locale.US, "%.1f", 60_000_000.0 / tempo.usPerQuarter)
    } else {
        "120.0"
    }
    return ScoreMetrics(
        bpm = bpm,
        meter = if (meter != null) "${meter.numerator}/${meter.denominator}" else "4/4"
    )
}

private fun ParsedScore.durationText(playhead: Double): String {
    return "${playhead.formatClock()}/${duration.formatClock()}"
}

private fun ParsedScore.titleStatusText(): String {
    return title
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.', title)
        .ifBlank { "Untitled" }
}

private fun Double.formatClock(): String {
    val safe = toInt().coerceAtLeast(0)
    val minutes = safe / 60
    val seconds = safe % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun Double.formatCents(): String {
    val range = WaterfallMetrics.OFFSET_CENT_RANGE.roundToInt()
    val cents = roundToInt().coerceIn(-range, range)
    val prefix = if (cents > 0) "+" else ""
    return "$prefix$cents c"
}

private fun Float.formatVolumePercent(): String {
    val range = MainActivity.VOLUME_GAIN_MAX - MainActivity.VOLUME_GAIN_MIN
    val percent = if (range > 0f) {
        ((this - MainActivity.VOLUME_GAIN_MIN) / range * 100f)
    } else {
        0f
    }
        .roundToInt()
        .coerceIn(0, 100)
    return "$percent%"
}

internal fun String.adjustedBpm(speed: Double): String {
    val bpm = toDoubleOrNull() ?: return this
    return String.format(Locale.US, "%.1f", bpm * speed)
}

internal fun Double.speedMultiplierLabel(): String {
    return String.format(Locale.US, "%.2fx", this)
}

internal fun Int.edoLabel(): String {
    return if (this == 0) "Free" else toString()
}
