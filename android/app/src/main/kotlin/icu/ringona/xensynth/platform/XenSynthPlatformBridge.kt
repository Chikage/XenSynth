package icu.ringona.xensynth.platform

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import icu.ringona.xensynth.MsczToMidx
import icu.ringona.xensynth.audio.NativeAudio
import icu.ringona.xensynth.audio.NativeAudioEngine
import icu.ringona.xensynth.midi.MidiDeviceInputManager
import icu.ringona.xensynth.midi.MidiInputDevice
import icu.ringona.xensynth.midi.MidiInputEvent
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
    private val scheduler = NativeScoreScheduler(nativeAudio)
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Activity.MODE_PRIVATE)
    private val midiInputManager = MidiDeviceInputManager(activity, this)
    private val pitchRecognitionManager = PitchRecognitionManager(
        activity.applicationContext,
        object : PitchRecognitionManager.Listener {
            override fun onPitchRecognitionState(state: Map<String, Any>) {
                midiEventSink?.success(state)
            }

            override fun onPitchNote(
                pitch: Int,
                velocity: Int,
                down: Boolean,
                timeSeconds: Double,
            ) {
                midiEventSink?.success(
                    mapOf(
                        "type" to if (down) "noteOn" else "noteOff",
                        "source" to "microphone",
                        "channel" to 0,
                        "pitch" to pitch,
                        "note" to pitch,
                        "noteNumber" to pitch,
                        "velocity" to if (down) velocity else 0,
                        "time" to timeSeconds,
                    ),
                )
            }

            override fun onContinuousPitch(
                voiced: Boolean,
                frequencyHz: Double,
                midiPitch: Double,
                confidence: Double,
                velocity: Int,
                timeSeconds: Double,
            ) {
                midiEventSink?.success(
                    mapOf(
                        "type" to "pitch",
                        "source" to "microphone",
                        "mode" to PitchRecognitionMode.YIN.wireName,
                        "voiced" to voiced,
                        "frequencyHz" to frequencyHz,
                        "pitch" to midiPitch,
                        "confidence" to confidence,
                        "velocity" to velocity,
                        "time" to timeSeconds,
                    ),
                )
            }

            override fun onSpectrum(timeSeconds: Double, magnitudes: FloatArray) {
                midiEventSink?.success(
                    mapOf(
                        "type" to "spectrum",
                        "source" to "microphone",
                        "mode" to PitchRecognitionMode.FFT.wireName,
                        "time" to timeSeconds,
                        "magnitudes" to magnitudes,
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
    private var audioInitialized = false
    private var audioInitializing = false
    private var closed = false
    private var gain = DEFAULT_GAIN
    private var reverb = DEFAULT_REVERB
    private var latencyMilliseconds = 0.0
    private var pendingPitchRecognitionStart = false
    private var pendingPitchRecognitionDownload = false
    private var pendingPitchRecognitionMode = PitchRecognitionMode.PIANO

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
                    scheduler.setLatency(latencyMilliseconds)
                    result.success(true)
                }
                "loadScore" -> result.success(scheduler.loadScore(arguments))
                "convertMuseScore" -> convertMuseScore(arguments, result)
                "play" -> {
                    val explicitDelay = if (arguments.containsKey("audioStartDelaySeconds")) {
                        number(arguments, "audioStartDelaySeconds")
                    } else {
                        null
                    }
                    result.success(
                        scheduler.play(
                            fromSeconds = number(arguments, "from"),
                            speed = number(arguments, "speed") ?: 1.0,
                            offsetCents = number(arguments, "offsetCents") ?: 0.0,
                            audioDelayOverrideSeconds = explicitDelay,
                        )
                    )
                }
                "pause" -> result.success(mapOf("position" to scheduler.pause()))
                "seek" -> {
                    val position = number(arguments, "position") ?: 0.0
                    result.success(mapOf("position" to scheduler.seek(position)))
                }
                "stop" -> {
                    scheduler.stop()
                    result.success(true)
                }
                "noteOn" -> noteOn(arguments, result)
                "noteOff" -> {
                    val token = integer(arguments, "token", -1)
                    if (token >= 0) nativeAudio.noteOff(token)
                    result.success(true)
                }
                "allNotesOff" -> {
                    scheduler.allNotesOff()
                    result.success(true)
                }
                "getPitchRecognitionState" -> result.success(pitchRecognitionManager.state())
                "setPitchRecognitionSensitivity" -> {
                    pitchRecognitionManager.setSensitivity(
                        number(arguments, "sensitivity") ?: 1.0,
                    )
                    result.success(true)
                }
                "startPitchRecognition" -> startPitchRecognition(
                    mode = PitchRecognitionMode.fromWireName(arguments["mode"]?.toString()),
                    downloadIfNeeded = boolean(arguments, "downloadIfNeeded"),
                    result = result,
                )
                "stopPitchRecognition" -> {
                    pendingPitchRecognitionStart = false
                    pendingPitchRecognitionDownload = false
                    pendingPitchRecognitionMode = PitchRecognitionMode.PIANO
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
        val token = nativeAudio.noteOn(
            key = key,
            velocity = integer(arguments, "velocity", 100).coerceIn(1, 127),
            cents = ((pitch - key) * 100.0).toFloat(),
            channel = integer(arguments, "channel", 0).coerceIn(0, 15),
            program = integer(arguments, "program", 0).coerceIn(0, 127),
            bankMsb = integer(arguments, "bankMsb", 0).coerceIn(0, 127),
            bankLsb = integer(arguments, "bankLsb", 0).coerceIn(0, 127),
        )
        if (token == null) {
            result.error("audio_unavailable", "Native audio is not ready", null)
        } else {
            result.success(token)
        }
    }

    private fun startPitchRecognition(
        mode: PitchRecognitionMode,
        downloadIfNeeded: Boolean,
        result: MethodChannel.Result,
    ) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            result.success(pitchRecognitionManager.start(mode, downloadIfNeeded))
            return
        }
        pendingPitchRecognitionStart = true
        pendingPitchRecognitionDownload = downloadIfNeeded
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
        if (requestCode != MICROPHONE_PERMISSION_REQUEST_CODE) return false
        val requestedMicrophone = permissions.any { it == Manifest.permission.RECORD_AUDIO }
        val granted = requestedMicrophone && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        val shouldStart = pendingPitchRecognitionStart
        val downloadIfNeeded = pendingPitchRecognitionDownload
        val mode = pendingPitchRecognitionMode
        pendingPitchRecognitionStart = false
        pendingPitchRecognitionDownload = false
        pendingPitchRecognitionMode = PitchRecognitionMode.PIANO
        if (granted && shouldStart) {
            pitchRecognitionManager.start(mode, downloadIfNeeded)
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
        if (hostResumed && midiInputManager.isSupported) midiInputManager.start()
    }

    override fun onCancel(arguments: Any?) {
        midiEventSink = null
        midiInputManager.stop()
    }

    override fun onDeviceConnected(device: MidiInputDevice) = Unit

    override fun onDeviceDisconnected(device: MidiInputDevice) = Unit

    override fun onMidiEvent(event: MidiInputEvent) {
        val payload: Map<String, Any> = when (event) {
            is MidiInputEvent.NoteOn -> mapOf(
                "type" to "noteOn",
                "channel" to event.channel,
                "pitch" to event.pitch,
                "note" to event.pitch,
                "noteNumber" to event.pitch,
                "velocity" to event.velocity,
            )
            is MidiInputEvent.NoteOff -> mapOf(
                "type" to "noteOff",
                "channel" to event.channel,
                "pitch" to event.pitch,
                "note" to event.pitch,
                "noteNumber" to event.pitch,
                "velocity" to 0,
            )
            is MidiInputEvent.SustainPedal -> mapOf(
                "type" to "sustain",
                "channel" to event.channel,
                "down" to event.down,
                "enabled" to event.down,
            )
            is MidiInputEvent.ProgramChange -> mapOf(
                "type" to "program",
                "channel" to event.channel,
                "program" to event.program,
            )
            is MidiInputEvent.AllNotesOff -> mapOf(
                "type" to "allNotesOff",
                "channel" to event.channel,
            )
        }
        midiEventSink?.success(payload)
    }

    fun onHostResume() {
        hostResumed = true
        if (midiEventSink != null && midiInputManager.isSupported) midiInputManager.start()
        if (audioInitialized) {
            worker.execute {
                if (!nativeAudio.isStarted()) nativeAudio.restart()
            }
        }
    }

    fun onHostPause() {
        hostResumed = false
        midiInputManager.stop()
        pendingPitchRecognitionStart = false
        pendingPitchRecognitionDownload = false
        pendingPitchRecognitionMode = PitchRecognitionMode.PIANO
        pitchRecognitionManager.stop()
        scheduler.pause()
    }

    fun close() {
        if (closed) return
        closed = true
        pendingDocumentResult?.error("activity_closed", "Activity was closed", null)
        pendingDocumentResult = null
        midiEventSink = null
        midiInputManager.close()
        pitchRecognitionManager.close()
        scheduler.dispose()
        nativeAudio.allSoundOff()
        nativeAudio.teardown()
        worker.shutdownNow()
        initializeWaiters.clear()
    }

    private companion object {
        const val PREFERENCES_NAME = "xensynth_flutter_settings"
        const val DOCUMENT_REQUEST_CODE = 0x5845
        const val MICROPHONE_PERMISSION_REQUEST_CODE = 0x5846
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
    }
}
