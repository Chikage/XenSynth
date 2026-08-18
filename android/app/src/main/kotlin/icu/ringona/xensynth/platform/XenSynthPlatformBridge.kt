package icu.ringona.xensynth.platform

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import icu.ringona.xensynth.MsczToMidx
import icu.ringona.xensynth.audio.NativeAudio
import icu.ringona.xensynth.audio.NativeAudioEngine
import icu.ringona.xensynth.audio.nativeAudioEventTiming
import icu.ringona.xensynth.midi.MidiDeviceInputManager
import icu.ringona.xensynth.midi.MidiInputDevice
import icu.ringona.xensynth.midi.MidiInputEvent
import icu.ringona.xensynth.midi.MidiInputParser
import icu.ringona.xensynth.midi.MidiInputSource
import icu.ringona.xensynth.midi.MidiOutputDestinationManager
import icu.ringona.xensynth.midi.MidiOutputRouter
import icu.ringona.rtpmidi.AppleMidiConfiguration
import icu.ringona.rtpmidi.AppleMidiEvent
import icu.ringona.rtpmidi.AppleMidiManager
import icu.ringona.rtpmidi.AppleMidiPeer
import icu.ringona.rtpmidi.AppleMidiScheduledListener
import icu.ringona.xensynth.playback.XenSynthPlaybackService
import icu.ringona.xensynth.pitch.PitchRecognitionManager
import icu.ringona.xensynth.pitch.PitchRecognitionMode
import icu.ringona.xensynth.pitch.PitchRecordingStore
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

internal class XenSynthPlatformBridge(
    private val activity: Activity,
    private val nativeAudio: NativeAudio = NativeAudioEngine,
) : MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    MidiDeviceInputManager.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Activity.MODE_PRIVATE)
    private val midiInputManager = MidiDeviceInputManager(activity, this)
    private val midiOutputDestinationManager = MidiOutputDestinationManager(
        activity.applicationContext,
    )
    private val pitchRecognitionManager = PitchRecognitionManager(
        activity.applicationContext,
        object : PitchRecognitionManager.Listener {
            override fun onPitchRecognitionState(state: Map<String, Any>) {
                midiEventSink?.success(state)
            }

            override fun onContinuousPitch(
                voiced: Boolean,
                frequencyHz: Double,
                midiPitch: Double,
                confidence: Double,
                velocity: Int,
                algorithm: String,
                timeSeconds: Double,
            ) {
                midiEventSink?.success(
                    mapOf(
                        "type" to "pitch",
                        "source" to "microphone",
                        "mode" to PitchRecognitionMode.HYBRID.wireName,
                        "algorithm" to algorithm,
                        "voiced" to voiced,
                        "frequencyHz" to frequencyHz,
                        "pitch" to midiPitch,
                        "confidence" to confidence,
                        "velocity" to velocity,
                        "time" to timeSeconds,
                    ),
                )
            }

            override fun onSpectrum(
                timeSeconds: Double,
                magnitudes: FloatArray,
                peaks: List<icu.ringona.xensynth.pitch.SpectrumPeak>,
            ) {
                midiEventSink?.success(
                    mapOf(
                        "type" to "spectrum",
                        "source" to "microphone",
                        "mode" to PitchRecognitionMode.HYBRID.wireName,
                        "time" to timeSeconds,
                        "magnitudes" to magnitudes,
                        "peaks" to peaks.map { peak ->
                            mapOf(
                                "pitch" to peak.midiPitch,
                                "magnitude" to peak.magnitude,
                            )
                        },
                    ),
                )
            }
        },
    )
    private val pitchRecordingStore = PitchRecordingStore(activity.applicationContext)
    private val initializeWaiters = mutableListOf<MethodChannel.Result>()

    private var methodChannel: MethodChannel? = null
    private var midiEventSink: EventChannel.EventSink? = null
    private var pendingDocumentResult: MethodChannel.Result? = null
    private var pendingViewUri: Uri? = null
    private var hostResumed = false
    @Volatile
    private var midiInputEnabled = true
    private var audioInitialized = false
    private var audioInitializing = false
    @Volatile
    private var closed = false
    private var gain = DEFAULT_GAIN
    private var reverb = DEFAULT_REVERB
    private var latencyMilliseconds = 0.0
    private var pendingPitchRecognitionStart = false
    private var pendingPitchRecognitionMode = PitchRecognitionMode.HYBRID
    private var pendingBluetoothMidiOutputIds = emptyList<String>()
    private val manualNoteTokens = mutableMapOf<Int, ManualNoteToken>()
    private var nextManualNoteToken = 1
    private val networkMidiParsers = ConcurrentHashMap<String, TimedNetworkMidiParser>()
    private val networkSessionInputIds = ConcurrentHashMap<String, String>()
    private var midiInputDeviceSelectionConfigured = false
    private var selectedMidiInputDeviceIds = emptySet<String>()
    @Volatile
    private var selectedNetworkMidiInputIds: Set<String>? = null
    private val networkMidiAudioScheduler = NetworkMidiAudioScheduler(nativeAudio)
    private val appleMidiManager = AppleMidiManager(
        context = activity.applicationContext,
        configuration = AppleMidiConfiguration(
            serviceName = "XenSynth - ${Build.MODEL.orEmpty().ifBlank { "Android" }}",
            eventDeliveryLookaheadMillis = NETWORK_AUDIO_LOOKAHEAD_MILLIS,
        ),
        listener = object : AppleMidiScheduledListener {
            override fun onPeersChanged(peers: List<AppleMidiPeer>) {
                // A passive session may acquire its stable Bonjour peer ID
                // after the first packet; resolve it again on directory changes.
                networkSessionInputIds.clear()
            }

            override fun onMidiEvent(event: AppleMidiEvent) {
                if (!midiInputEnabled || closed || !networkMidiInputAllowed(event.sessionId)) return
                val nativeAudioHandled = networkMidiAudioScheduler.onMidiEvent(event)
                val parser = networkMidiParsers.computeIfAbsent(event.sessionId) {
                    TimedNetworkMidiParser { midiEvent, targetTimeNanos, audioHandled ->
                        if (midiInputEnabled && !closed) {
                            deliverMidiEvent(
                                midiInputEvent = midiEvent,
                                source = "network",
                                targetTimeNanos = targetTimeNanos,
                                nativeAudioHandled = audioHandled,
                            )
                        }
                    }
                }
                parser.send(event.bytes, event.targetTimeNanos, nativeAudioHandled)
            }

            override fun onSessionClosed(sessionId: String) {
                networkMidiAudioScheduler.releaseSession(sessionId)
                networkMidiParsers.remove(sessionId)?.reset()
                networkSessionInputIds.remove(sessionId)
                if (midiInputEnabled && !closed) {
                    (0 until 16).forEach { channel ->
                        deliverMidiEvent(
                            midiInputEvent = MidiInputEvent.AllNotesOff(channel),
                            source = "network",
                            targetTimeNanos = System.nanoTime(),
                            nativeAudioHandled = true,
                        )
                    }
                }
            }
        },
    )

    init {
        appleMidiManager.start()
        MidiOutputRouter.setNetworkSender(appleMidiManager::send)
    }

    fun attachMethodChannel(channel: MethodChannel) {
        methodChannel = channel
        deliverPendingViewDocument()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val arguments = call.arguments as? Map<*, *> ?: emptyMap<Any?, Any?>()
        try {
            when (call.method) {
                "initializeAudio" -> initializeAudio(result)
                "setGain" -> {
                    gain = (number(arguments, "gain") ?: DEFAULT_GAIN).toFloat().coerceIn(0f, 6f)
                    nativeAudio.setGain(gain)
                    result.success(true)
                }
                "setReverb" -> {
                    val raw = number(arguments, "mix") ?: 0.0
                    reverb = if (raw in 0.0..1.0) {
                        (raw * 100.0).roundToInt()
                    } else {
                        raw.roundToInt()
                    }.coerceIn(0, 100)
                    nativeAudio.setReverb(reverb)
                    result.success(true)
                }
                "setLatency" -> {
                    latencyMilliseconds = number(arguments, "milliseconds")
                        ?.takeIf(Double::isFinite)
                        ?: 0.0
                    XenSynthPlaybackCoordinator.setLatency(latencyMilliseconds)
                    result.success(true)
                }
                "setProgram" -> result.success(true)
                "loadScore" -> {
                    val loaded = XenSynthPlaybackCoordinator.loadScore(arguments)
                    XenSynthPlaybackService.refreshIfRunning()
                    result.success(loaded)
                }
                "convertMuseScore" -> convertMuseScore(arguments, result)
                "play" -> {
                    val explicitDelay = if (arguments.containsKey("audioStartDelaySeconds")) {
                        number(arguments, "audioStartDelaySeconds")
                    } else {
                        null
                    }
                    result.success(
                        XenSynthPlaybackCoordinator.play(
                            fromSeconds = number(arguments, "from"),
                            speed = number(arguments, "speed") ?: 1.0,
                            offsetCents = number(arguments, "offsetCents") ?: 0.0,
                            audioDelaySeconds = explicitDelay,
                        ).also { started ->
                            if (started) startPlaybackService()
                        }
                    )
                }
                "pause" -> result.success(
                    mapOf("position" to XenSynthPlaybackCoordinator.pause()).also {
                        XenSynthPlaybackService.refreshIfRunning()
                    },
                )
                "seek" -> {
                    val position = number(arguments, "position") ?: 0.0
                    result.success(
                        mapOf("position" to XenSynthPlaybackCoordinator.seek(position)).also {
                            XenSynthPlaybackService.refreshIfRunning()
                        },
                    )
                }
                "stop" -> {
                    XenSynthPlaybackCoordinator.stop()
                    XenSynthPlaybackService.stopIfRunning(activity)
                    result.success(true)
                }
                "noteOn" -> noteOn(arguments, result)
                "noteOff" -> {
                    val token = integer(arguments, "token", -1)
                    val active = if (token >= 0) manualNoteTokens.remove(token) else null
                    val audioDelaySeconds = audioDelaySeconds(arguments)
                    runCatching {
                        active?.audioToken?.let { noteId ->
                            if (audioDelaySeconds > 0.0) {
                                nativeAudio.scheduleNoteOff(noteId, audioDelaySeconds)
                            } else {
                                nativeAudio.noteOff(noteId)
                            }
                        }
                    }
                    active?.midiToken?.let(MidiOutputRouter::noteOff)
                    result.success(true)
                }
                "allNotesOff" -> {
                    XenSynthPlaybackCoordinator.allNotesOff(
                        sendToNetwork = boolean(arguments, "networkOutput", defaultValue = true),
                    )
                    releaseManualNotes()
                    result.success(true)
                }
                "setMidiInputEnabled" -> {
                    setMidiInputEnabled(boolean(arguments, "enabled", defaultValue = true))
                    result.success(true)
                }
                "getMidiInputDevices" -> getMidiInputDevices(result)
                "setMidiInputDeviceIds" -> {
                    setMidiInputDeviceIds(
                        ids = stringList(arguments["ids"]),
                        configured = boolean(arguments, "configured", defaultValue = true),
                    )
                    result.success(true)
                }
                "setMidiOutputEnabled" -> {
                    MidiOutputRouter.setOutputEnabled(
                        boolean(arguments, "enabled", defaultValue = true),
                    )
                    result.success(true)
                }
                "getMidiOutputDevices" -> result.success(
                    midiOutputDestinationManager.destinations(),
                )
                "configureNetworkMidiOutput" -> {
                    // AppleMIDI destinations come exclusively from Bonjour service identities.
                    MidiOutputRouter.setNetworkOutputEnabled(
                        boolean(arguments, "enabled"),
                    )
                    result.success(true)
                }
                "configureNetworkAudio" -> {
                    val mappedPitches = (arguments["mappedPitches"] as? List<*>)
                        ?.mapNotNull { value ->
                            when (value) {
                                is Number -> value.toDouble()
                                is String -> value.toDoubleOrNull()
                                else -> null
                            }
                        }
                        ?.toDoubleArray()
                    if (mappedPitches == null || mappedPitches.size != 128) {
                        result.error(
                            "invalid_network_audio_map",
                            "configureNetworkAudio requires 128 mapped pitches",
                            null,
                        )
                    } else {
                        networkMidiAudioScheduler.configure(
                            mappedPitches = mappedPitches,
                            program = integer(arguments, "program", 0),
                        )
                        result.success(true)
                    }
                }
                "scanNetworkMidiOutputs" -> scanNetworkMidiOutputs(result)
                "setNetworkMidiOutputIds", "setNetworkMidiDestinationIds" -> {
                    appleMidiManager.setDestinationIds(stringList(arguments["ids"]))
                    result.success(true)
                }
                "getBluetoothMidiOutputs" -> result.success(
                    midiOutputDestinationManager.bluetoothDestinations(),
                )
                "setBluetoothMidiOutputIds" -> {
                    setBluetoothMidiOutputIds(stringList(arguments["ids"]))
                    result.success(true)
                }
                "releaseInputNotes" -> {
                    releaseManualNotes()
                    result.success(true)
                }
                "getPlaybackState" -> result.success(XenSynthPlaybackCoordinator.snapshot().toMap())
                "getPitchRecognitionState" -> result.success(pitchRecognitionManager.state())
                "setPitchRecognitionSensitivity" -> {
                    pitchRecognitionManager.setSensitivity(
                        number(arguments, "sensitivity") ?: 1.0,
                    )
                    result.success(true)
                }
                "startPitchRecognition" -> startPitchRecognition(
                    mode = PitchRecognitionMode.fromWireName(arguments["mode"]?.toString()),
                    result = result,
                )
                "stopPitchRecognition" -> {
                    pendingPitchRecognitionStart = false
                    pendingPitchRecognitionMode = PitchRecognitionMode.HYBRID
                    result.success(pitchRecognitionManager.stop())
                }
                "playPitchRecording" -> result.success(
                    pitchRecognitionManager.playRecording(
                        fromSeconds = number(arguments, "from") ?: 0.0,
                    ),
                )
                "pausePitchRecording" -> {
                    pitchRecognitionManager.pauseRecordingPlayback()
                    result.success(true)
                }
                "stopPitchRecording" -> {
                    pitchRecognitionManager.stopRecordingPlayback()
                    result.success(true)
                }
                "discardPitchRecording" -> {
                    pitchRecognitionManager.discardRecording()
                    result.success(true)
                }
                "savePitchRecording" -> savePitchRecording(arguments, result)
                "pickDocument" -> pickDocument(result)
                "saveSettings" -> result.success(saveSettings(arguments))
                "loadSettings", "load" -> result.success(loadSettings(arguments))
                else -> result.notImplemented()
            }
        } catch (error: Throwable) {
            result.error("platform_error", error.message ?: error.javaClass.simpleName, null)
        }
    }

    private fun initializeAudio(result: MethodChannel.Result) {
        if (audioInitialized) {
            result.success(true)
            return
        }
        initializeWaiters += result
        if (audioInitializing) return

        audioInitializing = true
        worker.execute {
            val outcome = runCatching {
                val streamReady = nativeAudio.setup() && nativeAudio.start()
                val soundFontReady = streamReady && (
                    nativeAudio.hasSoundFont() || nativeAudio.loadBuiltinSf2()
                    )
                if (soundFontReady) {
                    nativeAudio.setGain(gain)
                    nativeAudio.setReverb(reverb)
                }
                soundFontReady
            }
            mainHandler.post {
                if (closed) return@post
                audioInitializing = false
                audioInitialized = outcome.getOrDefault(false)
                initializeWaiters.toList().forEach { waiter -> waiter.success(audioInitialized) }
                initializeWaiters.clear()
            }
        }
    }

    private fun savePitchRecording(
        arguments: Map<*, *>,
        result: MethodChannel.Result,
    ) {
        val noteMaps = (arguments["notes"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()
        val duration = number(arguments, "duration") ?: 0.0
        val suggestedName = arguments["suggestedName"]?.toString().orEmpty()
        worker.execute {
            val outcome = runCatching {
                val snapshot = requireNotNull(pitchRecognitionManager.recordingSnapshot()) {
                    "Microphone recording is unavailable"
                }
                pitchRecordingStore.save(
                    snapshot = snapshot,
                    noteMaps = noteMaps,
                    durationSeconds = duration,
                    suggestedName = suggestedName,
                )
            }
            mainHandler.post {
                if (closed) return@post
                outcome.fold(
                    onSuccess = result::success,
                    onFailure = { error ->
                        result.error(
                            "recording_save_failed",
                            error.message ?: error.javaClass.simpleName,
                            null,
                        )
                    },
                )
            }
        }
    }

    private fun noteOn(arguments: Map<*, *>, result: MethodChannel.Result) {
        val pitch = number(arguments, "pitch", "audioPitch")
            ?: number(arguments, "midiPitch")?.let { midiPitch ->
                midiPitch + (number(arguments, "cents") ?: 0.0) / 100.0
            }
        if (pitch == null || !pitch.isFinite()) {
            result.error("invalid_note", "noteOn requires a finite pitch", null)
            return
        }
        val key = pitch.roundToInt()
        if (key !in 0..127) {
            result.error("invalid_note", "Pitch is outside the MIDI range", null)
            return
        }
        val velocity = integer(arguments, "velocity", 100).coerceIn(1, 127)
        val channel = integer(arguments, "channel", 0).coerceIn(0, 15)
        val program = integer(arguments, "program", 0).coerceIn(0, 127)
        val bankMsb = integer(arguments, "bankMsb", 0).coerceIn(0, 127)
        val bankLsb = integer(arguments, "bankLsb", 0).coerceIn(0, 127)
        val midiToken = MidiOutputRouter.noteOn(
            id = integerOrNull(arguments, "id"),
            pitch = pitch,
            velocity = velocity,
            channel = channel,
            program = program,
            bankMsb = bankMsb,
            bankLsb = bankLsb,
            sendToNetwork = boolean(arguments, "networkOutput", defaultValue = true),
        )
        val audioToken = runCatching {
            nativeAudio.noteOn(
                key = key,
                velocity = velocity,
                cents = ((pitch - key) * 100.0).toFloat(),
                channel = channel,
                program = program,
                bankMsb = bankMsb,
                bankLsb = bankLsb,
                delaySeconds = audioDelaySeconds(arguments),
            )
        }.getOrNull()
        val token = allocateManualNoteToken()
        manualNoteTokens[token] = ManualNoteToken(audioToken, midiToken)
        result.success(token)
    }

    private fun audioDelaySeconds(arguments: Map<*, *>): Double =
        nativeAudioEventTiming(
            targetTimeNanos = longOrNull(arguments, "audioTargetTimeNanos"),
            nowNanos = System.nanoTime(),
        ).delaySeconds

    private fun releaseManualNotes(audioDelaySeconds: Double = 0.0) {
        val active = manualNoteTokens.values.toList()
        active.mapNotNull { it.audioToken }
            .forEach { noteId ->
                runCatching {
                    if (audioDelaySeconds > 0.0) {
                        nativeAudio.scheduleNoteOff(
                            noteId = noteId,
                            delaySeconds = audioDelaySeconds,
                            immediate = true,
                        )
                    } else {
                        nativeAudio.noteOffImmediately(noteId)
                    }
                }
            }
        active.forEach { MidiOutputRouter.noteOff(it.midiToken) }
        manualNoteTokens.clear()
    }

    private fun setMidiInputEnabled(enabled: Boolean) {
        if (midiInputEnabled == enabled) return
        midiInputEnabled = enabled
        if (!enabled) {
            networkMidiAudioScheduler.close()
            onMidiEvent(MidiInputEvent.AllNotesOff(channel = 0))
            midiInputManager.stop()
        } else if (hostResumed && midiEventSink != null && midiInputManager.isSupported) {
            midiInputManager.start()
        }
    }

    private fun scanNetworkMidiOutputs(result: MethodChannel.Result) {
        worker.execute {
            val destinations = runCatching {
                appleMidiManager.scan().map { peer ->
                    mapOf(
                        "id" to peer.id,
                        "name" to peer.name,
                        "model" to peer.model,
                        "hostAddress" to peer.hostAddress,
                        "port" to peer.controlPort,
                        "state" to peer.state.name.lowercase(),
                    )
                }
            }.getOrElse { emptyList() }
            mainHandler.post {
                if (!closed) result.success(destinations) else result.success(emptyList<Map<String, Any>>())
            }
        }
    }

    /**
     * Returns both local Android MIDI sources and discovered AppleMIDI peers.
     * Local sources are port-level entries; network peers are inherently
     * bidirectional and therefore appear as input sources as well as output
     * destinations. Discovery runs off the platform channel thread because a
     * DNS-SD scan may wait for its initial response.
     */
    private fun getMidiInputDevices(result: MethodChannel.Result) {
        worker.execute {
            val local = runCatching { midiInputManager.inputDevices() }
                .getOrElse { error ->
                    android.util.Log.w("MidiInputDevices", "Could not enumerate local MIDI inputs", error)
                    emptyList()
                }
                .map(::inputDeviceMap)
            val network = runCatching {
                val peers = appleMidiManager.scan()
                val peerIds = peers.mapTo(HashSet()) { it.id }
                val discovered = peers.map { peer ->
                    mapOf<String, Any>(
                        "id" to peer.id,
                        "name" to peer.name,
                        "model" to peer.model,
                        "hostAddress" to peer.hostAddress,
                        "port" to peer.controlPort,
                        "transport" to "network",
                        "type" to "network",
                        "state" to peer.state.name.lowercase(),
                        "isInput" to true,
                        "selected" to true,
                        "connected" to (peer.state.name == "CONNECTED"),
                    )
                }
                // A peer can invite this device directly without publishing a
                // resolvable Bonjour record. Keep those active passive-input
                // sessions visible even though address metadata is unavailable.
                val passiveSessions = appleMidiManager.sessionStatistics()
                    .filter { statistics ->
                        statistics.peerId == null || statistics.peerId !in peerIds
                    }
                    .map { statistics ->
                        mapOf<String, Any>(
                            "id" to (statistics.peerId
                                ?: "applemidi-session:${statistics.sessionId}"),
                            "name" to statistics.peerName,
                            "model" to statistics.peerName,
                            "transport" to "network",
                            "type" to "network",
                            "state" to "connected",
                            "isInput" to true,
                            "selected" to true,
                            "connected" to true,
                        )
                    }
                discovered + passiveSessions
            }.getOrElse { error ->
                android.util.Log.w("MidiInputDevices", "Could not scan network MIDI inputs", error)
                emptyList()
            }
            val merged = (local + network).distinctBy { it["id"]?.toString() }
            mainHandler.post {
                if (!closed) result.success(merged) else result.success(emptyList<Map<String, Any>>())
            }
        }
    }

    private fun inputDeviceMap(source: MidiInputSource): Map<String, Any> {
        val map = linkedMapOf<String, Any>(
            "id" to source.id,
            "name" to source.name,
            "transport" to source.transport,
            "type" to source.transport,
            "port" to source.portNumber,
            "isInput" to true,
            "selected" to source.selected,
            "connected" to source.connected,
        )
        source.model?.let { map["model"] = it }
        source.manufacturer?.let { map["manufacturer"] = it }
        return map
    }

    private fun setMidiInputDeviceIds(ids: List<String>, configured: Boolean) {
        val normalizedIds = ids.filter(String::isNotBlank).toSet()
        val selectionChanged =
            midiInputDeviceSelectionConfigured != configured ||
                selectedMidiInputDeviceIds != normalizedIds
        midiInputDeviceSelectionConfigured = configured
        selectedMidiInputDeviceIds = normalizedIds
        midiInputManager.setInputDeviceIds(ids = ids, configured = configured)
        val nextNetworkIds = if (configured) {
            normalizedIds.filterTo(LinkedHashSet()) {
                it.startsWith("applemidi:") || it.startsWith("applemidi-session:")
            }
        } else {
            null
        }
        selectedNetworkMidiInputIds = nextNetworkIds
        appleMidiManager.setInputIds(
            ids = nextNetworkIds ?: emptyList(),
            configured = configured,
        )
        if (!selectionChanged) return
        networkSessionInputIds.clear()
        // Changing any source ownership must not leave notes sounding after a
        // deselected source's later Note Off is filtered or disconnected.
        networkMidiAudioScheduler.close()
        networkMidiParsers.values.forEach(TimedNetworkMidiParser::reset)
        if (midiInputEnabled && !closed) {
            deliverMidiEvent(
                midiInputEvent = MidiInputEvent.AllNotesOff(channel = 0),
                source = "network",
                targetTimeNanos = System.nanoTime(),
                nativeAudioHandled = true,
            )
        }
    }

    private fun networkMidiInputAllowed(sessionId: String): Boolean {
        val selected = selectedNetworkMidiInputIds ?: return true
        val directId = "applemidi-session:$sessionId"
        if (directId in selected) return true
        val inputId = networkSessionInputIds.computeIfAbsent(sessionId) {
            appleMidiManager.sessionStatistics()
                .firstOrNull { it.sessionId == sessionId }
                ?.peerId
                ?: directId
        }
        return inputId in selected
    }

    private fun setBluetoothMidiOutputIds(ids: List<String>) {
        if (ids.isEmpty()) {
            pendingBluetoothMidiOutputIds = emptyList()
            midiOutputDestinationManager.selectBluetoothDestinations(ids)
            return
        }
        val needsBluetoothPermission = ids.any { it.startsWith("bluetooth:") }
        if (needsBluetoothPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingBluetoothMidiOutputIds = ids
            // USB/system targets do not depend on the Bluetooth permission and
            // should remain usable even if the user declines that permission.
            midiOutputDestinationManager.selectBluetoothDestinations(
                ids.filterNot { it.startsWith("bluetooth:") },
            )
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_PERMISSION_REQUEST_CODE,
            )
            return
        }
        pendingBluetoothMidiOutputIds = emptyList()
        midiOutputDestinationManager.selectBluetoothDestinations(ids)
    }

    private fun startPlaybackService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE,
            )
        }
        runCatching { XenSynthPlaybackService.startOrRefresh(activity) }
            .onFailure { error ->
                android.util.Log.w("XenSynthPlayback", "Could not start playback service", error)
            }
    }

    private fun allocateManualNoteToken(): Int {
        while (manualNoteTokens.containsKey(nextManualNoteToken)) {
            nextManualNoteToken = if (nextManualNoteToken == Int.MAX_VALUE) 1 else nextManualNoteToken + 1
        }
        return nextManualNoteToken.also {
            nextManualNoteToken = if (nextManualNoteToken == Int.MAX_VALUE) 1 else nextManualNoteToken + 1
        }
    }

    private fun startPitchRecognition(
        mode: PitchRecognitionMode,
        result: MethodChannel.Result,
    ) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            result.success(pitchRecognitionManager.start(mode))
            return
        }
        pendingPitchRecognitionStart = true
        pendingPitchRecognitionMode = mode
        val state = pitchRecognitionManager.waitingForPermission(mode)
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            MICROPHONE_PERMISSION_REQUEST_CODE,
        )
        result.success(state)
    }

    private fun convertMuseScore(arguments: Map<*, *>, result: MethodChannel.Result) {
        val name = arguments["name"]?.toString()?.ifBlank { "score.mscz" } ?: "score.mscz"
        val bytes = when (val value = arguments["bytes"]) {
            is ByteArray -> value
            is List<*> -> value.mapNotNull { (it as? Number)?.toInt()?.toByte() }.toByteArray()
            else -> null
        }
        if (bytes == null) {
            result.error("invalid_musescore", "convertMuseScore requires bytes", null)
            return
        }
        worker.execute {
            val outcome = runCatching { MsczToMidx.convert(bytes, name) }
            mainHandler.post {
                if (closed) return@post
                outcome.fold(
                    onSuccess = result::success,
                    onFailure = { error ->
                        result.error(
                            "musescore_conversion_failed",
                            error.message ?: error.javaClass.simpleName,
                            null,
                        )
                    },
                )
            }
        }
    }

    private fun pickDocument(result: MethodChannel.Result) {
        val viewUri = pendingViewUri
        if (viewUri != null) {
            pendingViewUri = null
            readDocumentForResult(viewUri, result)
            return
        }
        if (pendingDocumentResult != null) {
            result.error("picker_active", "A document picker request is already active", null)
            return
        }
        pendingDocumentResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, DOCUMENT_MIME_TYPES)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            activity.startActivityForResult(intent, DOCUMENT_REQUEST_CODE)
        } catch (error: ActivityNotFoundException) {
            pendingDocumentResult = null
            result.error("picker_unavailable", "No document picker is available", null)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != DOCUMENT_REQUEST_CODE) return false
        val result = pendingDocumentResult ?: return true
        pendingDocumentResult = null
        if (resultCode != Activity.RESULT_OK) {
            result.success(null)
            return true
        }
        val uri = data?.data
        if (uri == null) {
            result.success(null)
            return true
        }
        retainReadPermission(uri, data.flags)
        readDocumentForResult(uri, result)
        return true
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            val requestedBluetooth = permissions.any {
                it == Manifest.permission.BLUETOOTH_CONNECT
            }
            val granted = requestedBluetooth && grantResults.any {
                it == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                midiOutputDestinationManager.selectBluetoothDestinations(
                    pendingBluetoothMidiOutputIds,
                )
            }
            pendingBluetoothMidiOutputIds = emptyList()
            return true
        }
        if (requestCode != MICROPHONE_PERMISSION_REQUEST_CODE) return false
        val requestedMicrophone = permissions.any { it == Manifest.permission.RECORD_AUDIO }
        val granted = requestedMicrophone && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        val shouldStart = pendingPitchRecognitionStart
        val mode = pendingPitchRecognitionMode
        pendingPitchRecognitionStart = false
        pendingPitchRecognitionMode = PitchRecognitionMode.HYBRID
        if (granted && shouldStart) {
            pitchRecognitionManager.start(mode)
        } else {
            pitchRecognitionManager.permissionDenied()
        }
        return true
    }

    fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        retainReadPermission(uri, intent.flags)
        pendingViewUri = uri
        deliverPendingViewDocument()
    }

    private fun deliverPendingViewDocument() {
        val uri = pendingViewUri ?: return
        val sink = midiEventSink ?: return
        readDocument(uri) { payload, error ->
            if (payload == null) return@readDocument
            sink.success(payload + mapOf("type" to "document"))
            if (pendingViewUri == uri) pendingViewUri = null
        }
    }

    private fun readDocumentForResult(uri: Uri, result: MethodChannel.Result) {
        readDocument(uri) { payload, error ->
            if (payload != null) {
                result.success(payload)
            } else {
                result.error("document_read_failed", error?.message ?: "Could not read document", null)
            }
        }
    }

    private fun readDocument(
        uri: Uri,
        completion: (Map<String, Any>?, Throwable?) -> Unit,
    ) {
        worker.execute {
            val outcome = runCatching { documentPayload(uri) }
            mainHandler.post {
                if (!closed) completion(outcome.getOrNull(), outcome.exceptionOrNull())
            }
        }
    }

    private fun documentPayload(uri: Uri): Map<String, Any> {
        val bytes = when (uri.scheme) {
            "file" -> File(requireNotNull(uri.path) { "File URI has no path" }).readBytes()
            else -> activity.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open document" }
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
        }
        val name = displayName(uri)
        val cacheDirectory = File(activity.cacheDir, DOCUMENT_CACHE_DIRECTORY).apply {
            if (!exists() && !mkdirs()) error("Could not create document cache")
        }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
        val cachedFile = File(cacheDirectory, "${System.nanoTime()}_$safeName")
        FileOutputStream(cachedFile).use { output -> output.write(bytes) }
        return mapOf(
            "name" to name,
            "path" to cachedFile.absolutePath,
            "bytes" to bytes,
            "size" to bytes.size,
        )
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path.orEmpty()).name.ifBlank { "document" }
        }
        return runCatching {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "document"
    }

    private fun retainReadPermission(uri: Uri, intentFlags: Int) {
        if (uri.scheme != "content") return
        val flags = intentFlags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun saveSettings(arguments: Map<*, *>): Boolean {
        val settings = arguments["settings"] as? Map<*, *> ?: arguments
        val editor = preferences.edit()
        settings.forEach { (rawKey, value) ->
            val key = rawKey as? String ?: return@forEach
            putPreference(editor, key, value)
        }
        editor.apply()
        return true
    }

    private fun loadSettings(arguments: Map<*, *>): Map<String, Any?> {
        val requestedKeys = (arguments["keys"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.toSet()
        return preferences.all
            .asSequence()
            .filter { (key, _) -> requestedKeys == null || key in requestedKeys }
            .associate { (key, value) -> key to decodePreference(value) }
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Double -> editor.putString(key, DOUBLE_PREFIX + value.toString())
            is String -> editor.putString(key, STRING_PREFIX + value)
            is List<*>, is Map<*, *> -> editor.putString(
                key,
                JSON_PREFIX + (JSONObject.wrap(value)?.toString() ?: "null"),
            )
            else -> editor.putString(key, STRING_PREFIX + value.toString())
        }
    }

    private fun decodePreference(value: Any?): Any? {
        return when (value) {
            is Float -> value.toDouble()
            is Set<*> -> value.toList()
            is String -> when {
                value.startsWith(DOUBLE_PREFIX) -> value.removePrefix(DOUBLE_PREFIX).toDoubleOrNull()
                value.startsWith(STRING_PREFIX) -> value.removePrefix(STRING_PREFIX)
                value.startsWith(JSON_PREFIX) -> runCatching {
                    jsonValue(JSONTokener(value.removePrefix(JSON_PREFIX)).nextValue())
                }.getOrDefault(value.removePrefix(JSON_PREFIX))
                else -> value
            }
            else -> value
        }
    }

    private fun jsonValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { key -> jsonValue(value.opt(key)) }
            is JSONArray -> (0 until value.length()).map { index -> jsonValue(value.opt(index)) }
            is Number, is Boolean, is String -> value
            else -> value.toString()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        midiEventSink = events
        deliverPendingViewDocument()
        pitchRecognitionManager.emitCurrentState()
        if (midiInputEnabled && hostResumed && midiInputManager.isSupported) midiInputManager.start()
    }

    override fun onCancel(arguments: Any?) {
        midiEventSink = null
        midiInputManager.stop()
    }

    override fun onDeviceConnected(device: MidiInputDevice) = Unit

    override fun onDeviceDisconnected(device: MidiInputDevice) = Unit

    override fun onMidiEvent(event: MidiInputEvent) {
        deliverMidiEvent(event)
    }

    private fun deliverMidiEvent(
        midiInputEvent: MidiInputEvent,
        source: String? = null,
        targetTimeNanos: Long? = null,
        nativeAudioHandled: Boolean = false,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                deliverMidiEvent(
                    midiInputEvent,
                    source,
                    targetTimeNanos,
                    nativeAudioHandled,
                )
            }
            return
        }
        val payload: Map<String, Any> = when (midiInputEvent) {
            is MidiInputEvent.NoteOn -> mapOf(
                "type" to "noteOn",
                "channel" to midiInputEvent.channel,
                "pitch" to midiInputEvent.pitch,
                "note" to midiInputEvent.pitch,
                "noteNumber" to midiInputEvent.pitch,
                "velocity" to midiInputEvent.velocity,
            )
            is MidiInputEvent.NoteOff -> mapOf(
                "type" to "noteOff",
                "channel" to midiInputEvent.channel,
                "pitch" to midiInputEvent.pitch,
                "note" to midiInputEvent.pitch,
                "noteNumber" to midiInputEvent.pitch,
                "velocity" to 0,
            )
            is MidiInputEvent.SustainPedal -> mapOf(
                "type" to "sustain",
                "channel" to midiInputEvent.channel,
                "down" to midiInputEvent.down,
                "enabled" to midiInputEvent.down,
            )
            is MidiInputEvent.ProgramChange -> mapOf(
                "type" to "program",
                "channel" to midiInputEvent.channel,
                "program" to midiInputEvent.program,
            )
            is MidiInputEvent.AllNotesOff -> mapOf(
                "type" to "allNotesOff",
                "channel" to midiInputEvent.channel,
            )
        }
        val sourcedPayload = if (source == null) payload else payload + ("source" to source)
        val timedPayload = if (targetTimeNanos == null) {
            sourcedPayload
        } else {
            sourcedPayload + ("targetTimeNanos" to targetTimeNanos)
        }
        val scheduledPayload = if (nativeAudioHandled) {
            timedPayload + ("nativeAudioHandled" to true)
        } else {
            timedPayload
        }
        midiEventSink?.success(scheduledPayload)
    }

    fun onHostResume() {
        hostResumed = true
        if (midiInputEnabled && midiEventSink != null && midiInputManager.isSupported) {
            midiInputManager.start()
        }
        if (audioInitialized) {
            worker.execute {
                if (!nativeAudio.isStarted()) nativeAudio.restart()
            }
        }
    }

    fun onHostPause() {
        hostResumed = false
        midiInputManager.stop()
        networkMidiAudioScheduler.close()
        releaseManualNotes()
        pendingPitchRecognitionStart = false
        pendingPitchRecognitionMode = PitchRecognitionMode.HYBRID
        pitchRecognitionManager.stop()
    }

    fun close() {
        if (closed) return
        closed = true
        pendingDocumentResult?.error("activity_closed", "Activity was closed", null)
        pendingDocumentResult = null
        midiEventSink = null
        midiInputManager.close()
        MidiOutputRouter.setNetworkSender(null)
        appleMidiManager.close()
        networkMidiAudioScheduler.close()
        networkMidiParsers.clear()
        networkSessionInputIds.clear()
        midiOutputDestinationManager.close()
        pitchRecognitionManager.close()
        releaseManualNotes()
        if (!XenSynthPlaybackService.isRunning()) {
            MidiOutputRouter.close()
            XenSynthPlaybackCoordinator.dispose()
            nativeAudio.allSoundOff()
            nativeAudio.teardown()
        }
        worker.shutdownNow()
        initializeWaiters.clear()
    }

    private data class ManualNoteToken(
        val audioToken: Int?,
        val midiToken: Int,
    )

    private class TimedNetworkMidiParser(
        private val onEvent: (MidiInputEvent, Long, Boolean) -> Unit,
    ) {
        private var currentTargetTimeNanos = 0L
        private var currentNativeAudioHandled = false
        private val parser = MidiInputParser { event ->
            onEvent(event, currentTargetTimeNanos, currentNativeAudioHandled)
        }

        @Synchronized
        fun send(bytes: ByteArray, targetTimeNanos: Long, nativeAudioHandled: Boolean) {
            currentTargetTimeNanos = targetTimeNanos
            currentNativeAudioHandled = nativeAudioHandled
            parser.send(bytes)
        }

        @Synchronized
        fun reset() = parser.reset()
    }

    private companion object {
        const val NETWORK_AUDIO_LOOKAHEAD_MILLIS = 24L
        const val PREFERENCES_NAME = "xensynth_flutter_settings"
        const val DOCUMENT_REQUEST_CODE = 0x5845
        const val MICROPHONE_PERMISSION_REQUEST_CODE = 0x5846
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 0x5847
        const val BLUETOOTH_PERMISSION_REQUEST_CODE = 0x5848
        const val DOCUMENT_CACHE_DIRECTORY = "xensynth-documents"
        const val SAMPLE_SCHEDULER_MILLIS = 8
        const val DEFAULT_GAIN = 2.05f
        const val DEFAULT_REVERB = 54
        const val DOUBLE_PREFIX = "__xensynth_double__:"
        const val STRING_PREFIX = "__xensynth_string__:"
        const val JSON_PREFIX = "__xensynth_json__:"

        val DOCUMENT_MIME_TYPES = arrayOf(
            "audio/mid",
            "audio/midi",
            "audio/x-mid",
            "audio/x-midi",
            "audio/midi2",
            "audio/x-midi2",
            "application/x-musescore",
            "application/vnd.musescore",
            "application/json",
            "text/json",
            "application/octet-stream",
            "*/*",
        )

        fun number(map: Map<*, *>, vararg keys: String): Double? {
            keys.forEach { key ->
                when (val value = map[key]) {
                    is Number -> return value.toDouble()
                    is String -> value.toDoubleOrNull()?.let { return it }
                }
            }
            return null
        }

        fun integer(map: Map<*, *>, key: String, defaultValue: Int): Int {
            return when (val value = map[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: defaultValue
                else -> defaultValue
            }
        }

        fun integerOrNull(map: Map<*, *>, key: String): Int? {
            return when (val value = map[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }

        fun stringList(value: Any?): List<String> {
            return (value as? List<*>)
                ?.mapNotNull { item -> item?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                ?.distinct()
                .orEmpty()
        }

        fun boolean(map: Map<*, *>, key: String, defaultValue: Boolean = false): Boolean {
            return when (val value = map[key]) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.lowercase()) {
                    "true", "yes", "1" -> true
                    "false", "no", "0" -> false
                    else -> defaultValue
                }
                else -> defaultValue
            }
        }

        fun longOrNull(map: Map<*, *>, key: String): Long? {
            return when (val value = map[key]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        }
    }
}
