package icu.ringona.xensynth.pitch

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import org.tensorflow.lite.Interpreter

internal enum class PitchRecognitionMode(val wireName: String) {
    PIANO("piano"),
    YIN("yin"),
    FFT("fft");

    companion object {
        fun fromWireName(value: String?): PitchRecognitionMode {
            return entries.firstOrNull { it.wireName == value?.lowercase() } ?: PIANO
        }
    }
}

internal fun scaleInputSample(sample: Short, sensitivity: Float): Float =
    (sample.toFloat() / Short.MAX_VALUE * sensitivity).coerceIn(-1f, 1f)

internal class PitchRecognitionManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPitchRecognitionState(state: Map<String, Any>)

        fun onPitchNote(pitch: Int, velocity: Int, down: Boolean, timeSeconds: Double)

        fun onContinuousPitch(
            voiced: Boolean,
            frequencyHz: Double,
            midiPitch: Double,
            confidence: Double,
            velocity: Int,
            timeSeconds: Double,
        )

        fun onSpectrum(timeSeconds: Double, magnitudes: FloatArray)
    }

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "XenSynth-PitchControl")
    }
    private val stateLock = Any()
    private val modelDirectory = File(applicationContext.filesDir, MODEL_DIRECTORY_NAME)
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val recordingPlayer = PcmRecordingPlayer(SAMPLE_RATE)

    private var phase = PHASE_IDLE
    private var message = ""
    private var downloadProgress = 0.0
    private var startAfterDownload = false
    private var selectedMode = PitchRecognitionMode.PIANO
    private var engine: PitchEngine? = null
    private var recording: PcmRecording? = null
    private var generation = 0L
    @Volatile
    private var inputSensitivity = 1f
    @Volatile
    private var closed = false

    fun state(): Map<String, Any> = synchronized(stateLock) { stateLocked() }

    fun setSensitivity(value: Double) {
        inputSensitivity = value.toFloat().coerceIn(
            MINIMUM_INPUT_SENSITIVITY,
            MAXIMUM_INPUT_SENSITIVITY,
        )
    }

    fun waitingForPermission(mode: PitchRecognitionMode): Map<String, Any> {
        synchronized(stateLock) {
            if (closed) return stateLocked()
            selectedMode = mode
            phase = PHASE_PERMISSION
            message = "Microphone permission is required"
        }
        emitState()
        return state()
    }

    fun permissionDenied(): Map<String, Any> {
        synchronized(stateLock) {
            if (closed) return stateLocked()
            phase = PHASE_ERROR
            message = "Microphone permission was denied"
            startAfterDownload = false
        }
        emitState()
        return state()
    }

    fun start(
        mode: PitchRecognitionMode,
        downloadIfNeeded: Boolean,
    ): Map<String, Any> {
        recordingPlayer.stop()
        var shouldDownload = false
        var shouldStart = false
        synchronized(stateLock) {
            if (closed || phase == PHASE_LISTENING || phase == PHASE_STARTING) {
                return stateLocked()
            }
            selectedMode = mode
            startAfterDownload = true
            if (mode == PitchRecognitionMode.PIANO && !isModelReady()) {
                if (!downloadIfNeeded) {
                    phase = PHASE_NEEDS_DOWNLOAD
                    message = "Pitch recognition model is not downloaded"
                } else if (phase != PHASE_DOWNLOADING) {
                    phase = PHASE_DOWNLOADING
                    message = "Downloading pitch recognition model"
                    downloadProgress = 0.0
                    shouldDownload = true
                }
            } else {
                phase = PHASE_STARTING
                message = "Starting microphone recognition"
                shouldStart = true
            }
        }
        emitState()
        when {
            shouldDownload -> worker.execute(::downloadModelAndStart)
            shouldStart -> scheduleEngineStart()
        }
        return state()
    }

    fun stop(): Map<String, Any> {
        val engineToStop: PitchEngine?
        synchronized(stateLock) {
            if (closed) return stateLocked()
            startAfterDownload = false
            generation++
            engineToStop = engine
            engine = null
            if (phase != PHASE_DOWNLOADING) {
                phase = PHASE_IDLE
                message = ""
            }
        }
        engineToStop?.stop()
        emitState()
        return state()
    }

    fun playRecording(fromSeconds: Double): Boolean {
        val samples = synchronized(stateLock) { recording?.snapshot() } ?: return false
        return recordingPlayer.play(samples, fromSeconds)
    }

    fun pauseRecordingPlayback() {
        recordingPlayer.stop()
    }

    fun stopRecordingPlayback() {
        recordingPlayer.stop()
    }

    fun discardRecording() {
        recordingPlayer.stop()
        synchronized(stateLock) {
            recording = null
        }
        emitState()
    }

    fun recordingSnapshot(): PitchRecordingSnapshot? {
        return synchronized(stateLock) {
            recording?.let { value ->
                PitchRecordingSnapshot(
                    sampleRate = value.sampleRate,
                    samples = value.snapshot(),
                )
            }
        }
    }

    fun emitCurrentState() {
        emitState()
    }

    fun close() {
        val engineToStop: PitchEngine?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            startAfterDownload = false
            generation++
            engineToStop = engine
            engine = null
            phase = PHASE_IDLE
        }
        engineToStop?.stop()
        recordingPlayer.stop()
        worker.shutdownNow()
    }

    private fun downloadModelAndStart() {
        val outcome = runCatching { downloadModel() }
        var shouldStart = false
        synchronized(stateLock) {
            if (closed) return
            outcome.fold(
                onSuccess = {
                    downloadProgress = 1.0
                    if (startAfterDownload) {
                        phase = PHASE_STARTING
                        message = "Starting microphone recognition"
                        shouldStart = true
                    } else {
                        phase = PHASE_IDLE
                        message = ""
                    }
                },
                onFailure = { error ->
                    phase = PHASE_ERROR
                    message = error.message ?: "Pitch recognition model download failed"
                    startAfterDownload = false
                },
            )
        }
        emitState()
        if (shouldStart) scheduleEngineStart()
    }

    private fun scheduleEngineStart() {
        val (startGeneration, mode) = synchronized(stateLock) {
            if (closed || phase != PHASE_STARTING) return
            Pair(++generation, selectedMode)
        }
        worker.execute {
            val nextRecording = PcmRecording(SAMPLE_RATE)
            val outcome = runCatching {
                when (mode) {
                    PitchRecognitionMode.PIANO -> PianoRecognitionEngine.start(
                        modelFile = modelFile,
                        recording = nextRecording,
                        sensitivity = { inputSensitivity },
                        onNote = ::emitNote,
                        onFailure = { error -> handleEngineFailure(startGeneration, error) },
                    )
                    PitchRecognitionMode.YIN -> YinRecognitionEngine.start(
                        recording = nextRecording,
                        sensitivity = { inputSensitivity },
                        onPitch = ::emitContinuousPitch,
                        onFailure = { error -> handleEngineFailure(startGeneration, error) },
                    )
                    PitchRecognitionMode.FFT -> FftRecognitionEngine.start(
                        recording = nextRecording,
                        sensitivity = { inputSensitivity },
                        onSpectrum = ::emitSpectrum,
                        onFailure = { error -> handleEngineFailure(startGeneration, error) },
                    )
                }
            }
            val created = outcome.getOrNull()
            var accepted = false
            synchronized(stateLock) {
                if (!closed && generation == startGeneration && phase == PHASE_STARTING) {
                    if (created != null) {
                        engine = created
                        recording = nextRecording
                        phase = PHASE_LISTENING
                        message = when (mode) {
                            PitchRecognitionMode.PIANO -> "Listening for piano notes"
                            PitchRecognitionMode.YIN -> "Listening for continuous pitch"
                            PitchRecognitionMode.FFT -> "Listening for FFT spectrum"
                        }
                        accepted = true
                    } else {
                        phase = PHASE_ERROR
                        message = outcome.exceptionOrNull()?.message
                            ?: "Could not start microphone recognition"
                        startAfterDownload = false
                    }
                }
            }
            if (!accepted) created?.stop()
            emitState()
        }
    }

    private fun handleEngineFailure(engineGeneration: Long, error: Throwable) {
        Log.e(LOG_TAG, "${selectedMode.wireName} recognition engine failed", error)
        var failedEngine: PitchEngine? = null
        synchronized(stateLock) {
            if (closed || generation != engineGeneration) return
            failedEngine = engine
            engine = null
            generation++
            phase = PHASE_ERROR
            message = error.message ?: "Microphone recognition stopped unexpectedly"
            startAfterDownload = false
        }
        failedEngine?.stop()
        emitState()
    }

    private fun emitNote(pitch: Int, velocity: Int, down: Boolean, timeSeconds: Double) {
        mainHandler.post {
            if (!closed) listener.onPitchNote(pitch, velocity, down, timeSeconds)
        }
    }

    private fun emitContinuousPitch(
        voiced: Boolean,
        frequencyHz: Double,
        midiPitch: Double,
        confidence: Double,
        velocity: Int,
        timeSeconds: Double,
    ) {
        mainHandler.post {
            if (!closed) {
                listener.onContinuousPitch(
                    voiced = voiced,
                    frequencyHz = frequencyHz,
                    midiPitch = midiPitch,
                    confidence = confidence,
                    velocity = velocity,
                    timeSeconds = timeSeconds,
                )
            }
        }
    }

    private fun emitSpectrum(timeSeconds: Double, magnitudes: FloatArray) {
        mainHandler.post {
            if (!closed) listener.onSpectrum(timeSeconds, magnitudes)
        }
    }

    private fun emitState() {
        val payload = state()
        mainHandler.post {
            if (!closed) listener.onPitchRecognitionState(payload)
        }
    }

    private fun stateLocked(): Map<String, Any> {
        val modelReady = isModelReady()
        return mapOf(
            "type" to EVENT_STATE,
            "source" to EVENT_SOURCE,
            "supported" to true,
            "mode" to selectedMode.wireName,
            "phase" to phase,
            "modelReady" to modelReady,
            "recognizing" to (phase == PHASE_LISTENING),
            "downloading" to (phase == PHASE_DOWNLOADING),
            "busy" to (phase == PHASE_PERMISSION || phase == PHASE_DOWNLOADING || phase == PHASE_STARTING),
            "progress" to downloadProgress.coerceIn(0.0, 1.0),
            "message" to message,
            "recordingDuration" to (recording?.durationSeconds ?: 0.0),
            "hasRecording" to ((recording?.durationSeconds ?: 0.0) > 0.0),
        )
    }

    private fun isModelReady(): Boolean {
        return modelFile.isFile && modelFile.length() == MODEL_FILE_SIZE_BYTES
    }

    private fun downloadModel() {
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            error("Could not create the pitch recognition model directory")
        }
        val temporaryFile = File(modelDirectory, "$MODEL_FILE_NAME.download")
        if (temporaryFile.exists()) temporaryFile.delete()

        val connection = (URL(MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MILLIS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Model download failed with HTTP ${connection.responseCode}")
            }
            val expectedLength = connection.contentLengthLong
            if (expectedLength > 0 && expectedLength != MODEL_FILE_SIZE_BYTES) {
                error("Unexpected pitch model size: $expectedLength bytes")
            }

            var downloaded = 0L
            var lastUpdateNanos = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        if (closed || Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("Pitch model download was interrupted")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastUpdateNanos >= DOWNLOAD_PROGRESS_INTERVAL_NANOS) {
                            lastUpdateNanos = now
                            updateDownloadProgress(downloaded)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (temporaryFile.length() != MODEL_FILE_SIZE_BYTES) {
                error(
                    "Pitch model download is incomplete: ${temporaryFile.length()} / $MODEL_FILE_SIZE_BYTES bytes",
                )
            }
            moveDownloadedModel(temporaryFile)
            updateDownloadProgress(MODEL_FILE_SIZE_BYTES)
        } finally {
            connection.disconnect()
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    private fun moveDownloadedModel(temporaryFile: File) {
        runCatching {
            Files.move(
                temporaryFile.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(
                temporaryFile.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }

    private fun updateDownloadProgress(downloadedBytes: Long) {
        synchronized(stateLock) {
            if (closed || phase != PHASE_DOWNLOADING) return
            downloadProgress = downloadedBytes.toDouble() / MODEL_FILE_SIZE_BYTES
        }
        emitState()
    }

    private interface PitchEngine {
        fun stop()
    }

    private data class TimedAudioFrame(
        val samples: FloatArray,
        val timeSeconds: Double,
    )

    private class MicrophoneInput private constructor(
        val audioRecord: AudioRecord,
        private val echoCanceler: AcousticEchoCanceler?,
    ) {
        fun stop() {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            runCatching { echoCanceler?.release() }
        }

        companion object {
            @SuppressLint("MissingPermission")
            fun start(
                readBufferSamples: Int,
                audioSources: IntArray = AUDIO_SOURCES,
                enableEchoCanceler: Boolean = true,
            ): MicrophoneInput {
                val minimumBufferBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minimumBufferBytes > 0) { "16 kHz microphone recording is unavailable" }
                val bufferBytes = maxOf(minimumBufferBytes * 2, readBufferSamples * Short.SIZE_BYTES * 4)
                var lastError: Throwable? = null
                var audioRecord: AudioRecord? = null
                for (source in audioSources) {
                    val candidate = runCatching {
                        AudioRecord(
                            source,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferBytes,
                        )
                    }.onFailure { lastError = it }.getOrNull() ?: continue
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = candidate
                        break
                    }
                    candidate.release()
                }
                if (audioRecord == null) {
                    throw IllegalStateException(
                        "No compatible microphone input source was found",
                        lastError,
                    )
                }

                var echoCanceler: AcousticEchoCanceler? = null
                try {
                    audioRecord.startRecording()
                    check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "The microphone did not enter the recording state"
                    }
                    if (enableEchoCanceler && AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = runCatching {
                            AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
                        }.getOrNull()
                    }
                    return MicrophoneInput(audioRecord, echoCanceler)
                } catch (error: Throwable) {
                    runCatching { echoCanceler?.release() }
                    runCatching { audioRecord.release() }
                    throw error
                }
            }
        }
    }

    private class PianoRecognitionEngine private constructor(
        private val input: MicrophoneInput,
        private val interpreter: Interpreter,
        private val recording: PcmRecording,
        private val sensitivity: () -> Float,
        private val onNote: (pitch: Int, velocity: Int, down: Boolean, timeSeconds: Double) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) : PitchEngine {
        private val running = AtomicBoolean(true)
        private val failureReported = AtomicBoolean(false)
        private val interpreterClosed = AtomicBoolean(false)
        private val audioQueue = ArrayBlockingQueue<TimedAudioFrame>(1)
        private var currentFrameTimeSeconds = 0.0
        private val outputProcessor = PianoOutputProcessor { pitch, velocity, down ->
            onNote(pitch, velocity, down, currentFrameTimeSeconds)
        }
        private lateinit var captureThread: Thread
        private lateinit var inferenceThread: Thread

        override fun stop() {
            if (!running.getAndSet(false)) return
            input.stop()
            if (::captureThread.isInitialized) captureThread.interrupt()
            if (::inferenceThread.isInitialized) inferenceThread.interrupt()
        }

        private fun startThreads() {
            inferenceThread = Thread(::inferenceLoop, "XenSynth-PitchInference").apply { start() }
            captureThread = Thread(::captureLoop, "XenSynth-PitchCapture").apply { start() }
        }

        private fun captureLoop() {
            val ringBuffer = FloatArray(INPUT_SAMPLE_COUNT)
            val readBuffer = ShortArray(AUDIO_READ_BUFFER_SAMPLES)
            var writeIndex = 0
            var capturedSamples = 0
            var samplesSinceInference = 0
            try {
                while (running.get()) {
                    val count = input.audioRecord.read(
                        readBuffer,
                        0,
                        readBuffer.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (count <= 0) error("Microphone read failed with code $count")
                    recording.append(readBuffer, count)
                    val inputGain = sensitivity()
                    for (index in 0 until count) {
                        ringBuffer[writeIndex] = scaleInputSample(readBuffer[index], inputGain)
                        writeIndex = (writeIndex + 1) % INPUT_SAMPLE_COUNT
                        if (capturedSamples < INPUT_SAMPLE_COUNT) capturedSamples++
                        samplesSinceInference++
                    }
                    if (capturedSamples == INPUT_SAMPLE_COUNT && samplesSinceInference >= INFERENCE_HOP_SAMPLES) {
                        samplesSinceInference %= INFERENCE_HOP_SAMPLES
                        val window = FloatArray(INPUT_SAMPLE_COUNT)
                        val tailLength = INPUT_SAMPLE_COUNT - writeIndex
                        ringBuffer.copyInto(window, 0, writeIndex, INPUT_SAMPLE_COUNT)
                        if (writeIndex > 0) ringBuffer.copyInto(window, tailLength, 0, writeIndex)
                        audioQueue.poll()
                        audioQueue.offer(TimedAudioFrame(window, recording.durationSeconds))
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) reportFailure(error)
            }
        }

        private fun inferenceLoop() {
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_SAMPLE_COUNT * Float.SIZE_BYTES).apply {
                order(ByteOrder.nativeOrder())
            }
            val onsetsBuffer = ByteBuffer.allocateDirect(
                PianoOutputProcessor.OUTPUT_VALUE_COUNT * Float.SIZE_BYTES,
            ).apply {
                order(ByteOrder.nativeOrder())
            }
            val framesBuffer = ByteBuffer.allocateDirect(
                PianoOutputProcessor.OUTPUT_VALUE_COUNT * Float.SIZE_BYTES,
            ).apply {
                order(ByteOrder.nativeOrder())
            }
            try {
                while (running.get()) {
                    val input = audioQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    inputBuffer.clear()
                    inputBuffer.asFloatBuffer().put(input.samples)
                    onsetsBuffer.clear()
                    framesBuffer.clear()
                    interpreter.runForMultipleInputsOutputs(
                        arrayOf<Any>(inputBuffer),
                        mapOf(0 to onsetsBuffer, 1 to framesBuffer),
                    )
                    currentFrameTimeSeconds = input.timeSeconds
                    outputProcessor.processLatestFrame(onsetsBuffer, framesBuffer)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (running.get()) reportFailure(error)
            } finally {
                outputProcessor.releaseActiveNotes()
                closeInterpreter()
            }
        }

        private fun reportFailure(error: Throwable) {
            if (failureReported.compareAndSet(false, true)) onFailure(error)
        }

        private fun closeInterpreter() {
            if (interpreterClosed.compareAndSet(false, true)) runCatching { interpreter.close() }
        }

        companion object {
            @SuppressLint("MissingPermission")
            fun start(
                modelFile: File,
                recording: PcmRecording,
                sensitivity: () -> Float,
                onNote: (pitch: Int, velocity: Int, down: Boolean, timeSeconds: Double) -> Unit,
                onFailure: (Throwable) -> Unit,
            ): PianoRecognitionEngine {
                val interpreter = Interpreter(
                    modelFile,
                    Interpreter.Options().apply { setNumThreads(INTERPRETER_THREAD_COUNT) },
                )
                var input: MicrophoneInput? = null
                try {
                    input = MicrophoneInput.start(AUDIO_READ_BUFFER_SAMPLES)
                    return PianoRecognitionEngine(
                        input = input,
                        interpreter = interpreter,
                        recording = recording,
                        sensitivity = sensitivity,
                        onNote = onNote,
                        onFailure = onFailure,
                    ).also { it.startThreads() }
                } catch (error: Throwable) {
                    input?.stop()
                    runCatching { interpreter.close() }
                    throw error
                }
            }
        }
    }

    private class FftRecognitionEngine private constructor(
        private val input: MicrophoneInput,
        private val recording: PcmRecording,
        private val sensitivity: () -> Float,
        private val onSpectrum: (timeSeconds: Double, magnitudes: FloatArray) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) : PitchEngine {
        private val running = AtomicBoolean(true)
        private val failureReported = AtomicBoolean(false)
        private val audioQueue = ArrayBlockingQueue<TimedAudioFrame>(1)
        private lateinit var captureThread: Thread
        private lateinit var analysisThread: Thread

        override fun stop() {
            if (!running.getAndSet(false)) return
            input.stop()
            if (::captureThread.isInitialized) captureThread.interrupt()
            if (::analysisThread.isInitialized) analysisThread.interrupt()
        }

        private fun startThreads() {
            analysisThread = Thread(::analysisLoop, "XenSynth-FftAnalysis").apply { start() }
            captureThread = Thread(::captureLoop, "XenSynth-FftCapture").apply { start() }
        }

        private fun captureLoop() {
            val ringBuffer = FloatArray(FFT_FRAME_SIZE)
            val readBuffer = ShortArray(FFT_READ_BUFFER_SAMPLES)
            var writeIndex = 0
            var capturedSamples = 0
            var samplesSinceAnalysis = 0
            try {
                while (running.get()) {
                    val count = input.audioRecord.read(
                        readBuffer,
                        0,
                        readBuffer.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (count <= 0) error("Microphone read failed with code $count")
                    recording.append(readBuffer, count)
                    val inputGain = sensitivity()
                    for (index in 0 until count) {
                        ringBuffer[writeIndex] = scaleInputSample(readBuffer[index], inputGain)
                        writeIndex = (writeIndex + 1) % FFT_FRAME_SIZE
                        if (capturedSamples < FFT_FRAME_SIZE) capturedSamples++
                        samplesSinceAnalysis++
                    }
                    if (capturedSamples == FFT_FRAME_SIZE && samplesSinceAnalysis >= FFT_HOP_SAMPLES) {
                        samplesSinceAnalysis %= FFT_HOP_SAMPLES
                        val frame = FloatArray(FFT_FRAME_SIZE)
                        val tailLength = FFT_FRAME_SIZE - writeIndex
                        ringBuffer.copyInto(frame, 0, writeIndex, FFT_FRAME_SIZE)
                        if (writeIndex > 0) ringBuffer.copyInto(frame, tailLength, 0, writeIndex)
                        audioQueue.poll()
                        audioQueue.offer(TimedAudioFrame(frame, recording.durationSeconds))
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) reportFailure(error)
            }
        }

        private fun analysisLoop() {
            val analyzer = FftSpectrumAnalyzer(sampleRate = SAMPLE_RATE, frameSize = FFT_FRAME_SIZE)
            try {
                while (running.get()) {
                    val frame = audioQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    onSpectrum(frame.timeSeconds, analyzer.analyze(frame.samples))
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (running.get()) reportFailure(error)
            }
        }

        private fun reportFailure(error: Throwable) {
            if (failureReported.compareAndSet(false, true)) onFailure(error)
        }

        companion object {
            fun start(
                recording: PcmRecording,
                sensitivity: () -> Float,
                onSpectrum: (timeSeconds: Double, magnitudes: FloatArray) -> Unit,
                onFailure: (Throwable) -> Unit,
            ): FftRecognitionEngine {
                val input = MicrophoneInput.start(
                    readBufferSamples = FFT_READ_BUFFER_SAMPLES,
                    audioSources = FFT_AUDIO_SOURCES,
                    enableEchoCanceler = false,
                )
                return try {
                    FftRecognitionEngine(
                        input = input,
                        recording = recording,
                        sensitivity = sensitivity,
                        onSpectrum = onSpectrum,
                        onFailure = onFailure,
                    ).also { it.startThreads() }
                } catch (error: Throwable) {
                    input.stop()
                    throw error
                }
            }
        }
    }

    private class YinRecognitionEngine private constructor(
        private val input: MicrophoneInput,
        private val recording: PcmRecording,
        private val sensitivity: () -> Float,
        private val onPitch: (
            voiced: Boolean,
            frequencyHz: Double,
            midiPitch: Double,
            confidence: Double,
            velocity: Int,
            timeSeconds: Double,
        ) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) : PitchEngine {
        private val running = AtomicBoolean(true)
        private val failureReported = AtomicBoolean(false)
        private val audioQueue = ArrayBlockingQueue<TimedAudioFrame>(1)
        private lateinit var captureThread: Thread
        private lateinit var analysisThread: Thread

        override fun stop() {
            if (!running.getAndSet(false)) return
            input.stop()
            if (::captureThread.isInitialized) captureThread.interrupt()
            if (::analysisThread.isInitialized) analysisThread.interrupt()
        }

        private fun startThreads() {
            analysisThread = Thread(::analysisLoop, "XenSynth-YinAnalysis").apply { start() }
            captureThread = Thread(::captureLoop, "XenSynth-YinCapture").apply { start() }
        }

        private fun captureLoop() {
            val ringBuffer = FloatArray(YIN_FRAME_SIZE)
            val readBuffer = ShortArray(YIN_READ_BUFFER_SAMPLES)
            var writeIndex = 0
            var capturedSamples = 0
            var samplesSinceAnalysis = 0
            try {
                while (running.get()) {
                    val count = input.audioRecord.read(
                        readBuffer,
                        0,
                        readBuffer.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (count <= 0) error("Microphone read failed with code $count")
                    recording.append(readBuffer, count)
                    val inputGain = sensitivity()
                    for (index in 0 until count) {
                        ringBuffer[writeIndex] = scaleInputSample(readBuffer[index], inputGain)
                        writeIndex = (writeIndex + 1) % YIN_FRAME_SIZE
                        if (capturedSamples < YIN_FRAME_SIZE) capturedSamples++
                        samplesSinceAnalysis++
                    }
                    if (capturedSamples == YIN_FRAME_SIZE && samplesSinceAnalysis >= YIN_HOP_SAMPLES) {
                        samplesSinceAnalysis %= YIN_HOP_SAMPLES
                        val frame = FloatArray(YIN_FRAME_SIZE)
                        val tailLength = YIN_FRAME_SIZE - writeIndex
                        ringBuffer.copyInto(frame, 0, writeIndex, YIN_FRAME_SIZE)
                        if (writeIndex > 0) ringBuffer.copyInto(frame, tailLength, 0, writeIndex)
                        audioQueue.poll()
                        audioQueue.offer(TimedAudioFrame(frame, recording.durationSeconds))
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) reportFailure(error)
            }
        }

        private fun analysisLoop() {
            val detector = YinPitchDetector(sampleRate = SAMPLE_RATE, frameSize = YIN_FRAME_SIZE)
            val pitchSmoother = YinPitchSmoother()
            var voiced = false
            var unvoicedFrames = 0
            try {
                while (running.get()) {
                    val frame = audioQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    val estimate = detector.detect(frame.samples)
                    if (estimate == null) {
                        unvoicedFrames++
                        if (voiced && unvoicedFrames >= YIN_UNVOICED_FRAME_COUNT) {
                            voiced = false
                            pitchSmoother.reset()
                            onPitch(false, 0.0, 0.0, 0.0, 0, frame.timeSeconds)
                        }
                        continue
                    }

                    unvoicedFrames = 0
                    val nextMidiPitch = pitchSmoother.update(
                        estimate.midiPitch,
                        frame.timeSeconds,
                    )
                    voiced = true
                    onPitch(
                        true,
                        midiToFrequency(nextMidiPitch),
                        nextMidiPitch,
                        estimate.confidence,
                        velocityFromRms(estimate.rms),
                        frame.timeSeconds,
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (running.get()) reportFailure(error)
            } finally {
                if (voiced) onPitch(false, 0.0, 0.0, 0.0, 0, recording.durationSeconds)
            }
        }

        private fun reportFailure(error: Throwable) {
            if (failureReported.compareAndSet(false, true)) onFailure(error)
        }

        companion object {
            fun start(
                recording: PcmRecording,
                sensitivity: () -> Float,
                onPitch: (
                    voiced: Boolean,
                    frequencyHz: Double,
                    midiPitch: Double,
                    confidence: Double,
                    velocity: Int,
                    timeSeconds: Double,
                ) -> Unit,
                onFailure: (Throwable) -> Unit,
            ): YinRecognitionEngine {
                val input = MicrophoneInput.start(YIN_READ_BUFFER_SAMPLES)
                return try {
                    YinRecognitionEngine(
                        input = input,
                        recording = recording,
                        sensitivity = sensitivity,
                        onPitch = onPitch,
                        onFailure = onFailure,
                    ).also { it.startThreads() }
                } catch (error: Throwable) {
                    input.stop()
                    throw error
                }
            }

            private fun midiToFrequency(midiPitch: Double): Double {
                return 440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)
            }

            private fun velocityFromRms(rms: Double): Int {
                val decibels = 20.0 * log10(rms.coerceAtLeast(1e-9))
                val normalized = ((decibels - YIN_MINIMUM_DECIBELS) /
                    (YIN_MAXIMUM_DECIBELS - YIN_MINIMUM_DECIBELS)).coerceIn(0.0, 1.0)
                return (1.0 + normalized * 126.0).toInt().coerceIn(1, 127)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "PitchRecognition"
        const val EVENT_STATE = "pitchRecognitionState"
        const val EVENT_SOURCE = "microphone"
        const val PHASE_IDLE = "idle"
        const val PHASE_PERMISSION = "permission"
        const val PHASE_NEEDS_DOWNLOAD = "needsDownload"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_STARTING = "starting"
        const val PHASE_LISTENING = "listening"
        const val PHASE_ERROR = "error"

        const val MODEL_DIRECTORY_NAME = "models"
        const val MODEL_FILE_NAME = "onsets_frames_wavinput_no_offset_uni.tflite"
        const val MODEL_DOWNLOAD_URL =
            "https://storage.googleapis.com/magentadata/models/onsets_frames_transcription/tflite/$MODEL_FILE_NAME"
        const val MODEL_FILE_SIZE_BYTES = 75_860_620L

        const val DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000
        const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
        const val DOWNLOAD_PROGRESS_INTERVAL_NANOS = 200_000_000L

        const val SAMPLE_RATE = 16_000
        const val MINIMUM_INPUT_SENSITIVITY = 0.5f
        const val MAXIMUM_INPUT_SENSITIVITY = 2.0f
        const val INPUT_SAMPLE_COUNT = 17_920
        const val INFERENCE_HOP_SAMPLES = 512
        const val AUDIO_READ_BUFFER_SAMPLES = 512
        const val YIN_FRAME_SIZE = 2_048
        const val YIN_HOP_SAMPLES = 256
        const val YIN_READ_BUFFER_SAMPLES = 256
        const val FFT_FRAME_SIZE = 2_048
        const val FFT_HOP_SAMPLES = 512
        const val FFT_READ_BUFFER_SAMPLES = 512
        const val YIN_UNVOICED_FRAME_COUNT = 6
        const val YIN_MINIMUM_DECIBELS = -60.0
        const val YIN_MAXIMUM_DECIBELS = -12.0
        const val INTERPRETER_THREAD_COUNT = 4
        val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )
        val FFT_AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )
    }
}
