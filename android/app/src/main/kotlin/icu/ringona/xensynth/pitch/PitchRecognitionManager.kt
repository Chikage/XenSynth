package icu.ringona.xensynth.pitch

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.pow

internal enum class PitchRecognitionMode(val wireName: String) {
    HYBRID("hybrid");

    companion object {
        fun fromWireName(value: String?): PitchRecognitionMode = HYBRID
    }
}

internal fun scaleInputSample(sample: Short, sensitivity: Float): Float =
    (sample.toFloat() / Short.MAX_VALUE * sensitivity).coerceIn(-1f, 1f)

internal class PitchRecognitionManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPitchRecognitionState(state: Map<String, Any>)

        fun onContinuousPitch(
            voiced: Boolean,
            frequencyHz: Double,
            midiPitch: Double,
            confidence: Double,
            velocity: Int,
            algorithm: String,
            timeSeconds: Double,
        )

        fun onSpectrum(
            timeSeconds: Double,
            magnitudes: FloatArray,
            peaks: List<SpectrumPeak>,
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "XenSynth-PitchControl")
    }
    private val stateLock = Any()
    private val recordingPlayer = PcmRecordingPlayer(SAMPLE_RATE)

    private var phase = PHASE_IDLE
    private var message = ""
    private var selectedMode = PitchRecognitionMode.HYBRID
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
        }
        emitState()
        return state()
    }

    fun start(mode: PitchRecognitionMode): Map<String, Any> {
        recordingPlayer.stop()
        synchronized(stateLock) {
            if (closed || phase == PHASE_LISTENING || phase == PHASE_STARTING) {
                return stateLocked()
            }
            selectedMode = mode
            phase = PHASE_STARTING
            message = "Starting local FFT and YIN recognition"
        }
        emitState()
        scheduleEngineStart()
        return state()
    }

    fun stop(): Map<String, Any> {
        val engineToStop: PitchEngine?
        synchronized(stateLock) {
            if (closed) return stateLocked()
            generation++
            engineToStop = engine
            engine = null
            phase = PHASE_IDLE
            message = ""
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
            generation++
            engineToStop = engine
            engine = null
            phase = PHASE_IDLE
        }
        engineToStop?.stop()
        recordingPlayer.stop()
        worker.shutdownNow()
    }

    private fun scheduleEngineStart() {
        val startGeneration = synchronized(stateLock) {
            if (closed || phase != PHASE_STARTING) return
            ++generation
        }
        worker.execute {
            val nextRecording = PcmRecording(SAMPLE_RATE)
            val outcome = runCatching {
                HybridRecognitionEngine.start(
                    recording = nextRecording,
                    sensitivity = { inputSensitivity },
                    onPitch = ::emitContinuousPitch,
                    onSpectrum = ::emitSpectrum,
                    onFailure = { error -> handleEngineFailure(startGeneration, error) },
                )
            }
            val created = outcome.getOrNull()
            var accepted = false
            synchronized(stateLock) {
                if (!closed && generation == startGeneration && phase == PHASE_STARTING) {
                    if (created != null) {
                        engine = created
                        recording = nextRecording
                        phase = PHASE_LISTENING
                        message = "Listening with local FFT and YIN fusion"
                        accepted = true
                    } else {
                        phase = PHASE_ERROR
                        message = outcome.exceptionOrNull()?.message
                            ?: "Could not start microphone recognition"
                    }
                }
            }
            if (!accepted) created?.stop()
            emitState()
        }
    }

    private fun handleEngineFailure(engineGeneration: Long, error: Throwable) {
        Log.e(LOG_TAG, "Hybrid pitch recognition engine failed", error)
        var failedEngine: PitchEngine? = null
        synchronized(stateLock) {
            if (closed || generation != engineGeneration) return
            failedEngine = engine
            engine = null
            generation++
            phase = PHASE_ERROR
            message = error.message ?: "Microphone recognition stopped unexpectedly"
        }
        failedEngine?.stop()
        emitState()
    }

    private fun emitContinuousPitch(
        voiced: Boolean,
        frequencyHz: Double,
        midiPitch: Double,
        confidence: Double,
        velocity: Int,
        algorithm: String,
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
                    algorithm = algorithm,
                    timeSeconds = timeSeconds,
                )
            }
        }
    }

    private fun emitSpectrum(
        timeSeconds: Double,
        magnitudes: FloatArray,
        peaks: List<SpectrumPeak>,
    ) {
        mainHandler.post {
            if (!closed) listener.onSpectrum(timeSeconds, magnitudes, peaks)
        }
    }

    private fun emitState() {
        val payload = state()
        mainHandler.post {
            if (!closed) listener.onPitchRecognitionState(payload)
        }
    }

    private fun stateLocked(): Map<String, Any> {
        val duration = recording?.durationSeconds ?: 0.0
        return mapOf(
            "type" to EVENT_STATE,
            "source" to EVENT_SOURCE,
            "supported" to true,
            "mode" to selectedMode.wireName,
            "phase" to phase,
            "modelReady" to true,
            "recognizing" to (phase == PHASE_LISTENING),
            "downloading" to false,
            "busy" to (phase == PHASE_PERMISSION || phase == PHASE_STARTING),
            "progress" to 1.0,
            "message" to message,
            "recordingDuration" to duration,
            "hasRecording" to (duration > 0.0),
        )
    }

    private interface PitchEngine {
        fun stop()
    }

    private data class TimedAudioFrame(
        val samples: FloatArray,
        val timeSeconds: Double,
    )

    private class MicrophoneInput private constructor(val audioRecord: AudioRecord) {
        fun stop() {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
        }

        companion object {
            @SuppressLint("MissingPermission")
            fun start(readBufferSamples: Int): MicrophoneInput {
                val minimumBufferBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minimumBufferBytes > 0) { "16 kHz microphone recording is unavailable" }
                val bufferBytes = maxOf(
                    minimumBufferBytes * 2,
                    readBufferSamples * Short.SIZE_BYTES * 4,
                )
                var lastError: Throwable? = null
                var audioRecord: AudioRecord? = null
                for (source in AUDIO_SOURCES) {
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
                try {
                    audioRecord.startRecording()
                    check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "The microphone did not enter the recording state"
                    }
                    return MicrophoneInput(audioRecord)
                } catch (error: Throwable) {
                    runCatching { audioRecord.release() }
                    throw error
                }
            }
        }
    }

    private class HybridRecognitionEngine private constructor(
        private val input: MicrophoneInput,
        private val recording: PcmRecording,
        private val sensitivity: () -> Float,
        private val onPitch: (
            voiced: Boolean,
            frequencyHz: Double,
            midiPitch: Double,
            confidence: Double,
            velocity: Int,
            algorithm: String,
            timeSeconds: Double,
        ) -> Unit,
        private val onSpectrum: (
            timeSeconds: Double,
            magnitudes: FloatArray,
            peaks: List<SpectrumPeak>,
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
            analysisThread = Thread(::analysisLoop, "XenSynth-HybridAnalysis").apply { start() }
            captureThread = Thread(::captureLoop, "XenSynth-HybridCapture").apply { start() }
        }

        private fun captureLoop() {
            val ringBuffer = FloatArray(ANALYSIS_FRAME_SIZE)
            val readBuffer = ShortArray(AUDIO_READ_BUFFER_SAMPLES)
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
                        writeIndex = (writeIndex + 1) % ANALYSIS_FRAME_SIZE
                        if (capturedSamples < ANALYSIS_FRAME_SIZE) capturedSamples++
                        samplesSinceAnalysis++
                    }
                    if (
                        capturedSamples == ANALYSIS_FRAME_SIZE &&
                        samplesSinceAnalysis >= ANALYSIS_HOP_SAMPLES
                    ) {
                        samplesSinceAnalysis %= ANALYSIS_HOP_SAMPLES
                        val frame = FloatArray(ANALYSIS_FRAME_SIZE)
                        val tailLength = ANALYSIS_FRAME_SIZE - writeIndex
                        ringBuffer.copyInto(frame, 0, writeIndex, ANALYSIS_FRAME_SIZE)
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
            val yinDetector = YinPitchDetector(
                sampleRate = SAMPLE_RATE,
                frameSize = ANALYSIS_FRAME_SIZE,
            )
            val spectrumAnalyzer = FftSpectrumAnalyzer(
                sampleRate = SAMPLE_RATE,
                frameSize = ANALYSIS_FRAME_SIZE,
            )
            val pitchSmoother = YinPitchSmoother(
                referenceIntervalSeconds = ANALYSIS_HOP_SAMPLES.toDouble() / SAMPLE_RATE,
            )
            var yinAvailable = true
            var voiced = false
            var unvoicedFrames = 0
            try {
                while (running.get()) {
                    val frame = audioQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    val spectrum = spectrumAnalyzer.analyzeFrame(frame.samples)
                    onSpectrum(frame.timeSeconds, spectrum.magnitudes, spectrum.peaks)
                    val yinEstimate = if (yinAvailable) {
                        try {
                            yinDetector.detect(frame.samples)
                        } catch (error: Throwable) {
                            yinAvailable = false
                            Log.w(LOG_TAG, "YIN disabled; continuing with FFT fallback", error)
                            null
                        }
                    } else {
                        null
                    }
                    val estimate = HybridPitchFusion.fuse(
                        yin = yinEstimate,
                        fft = spectrum.pitchEstimate,
                    )
                    if (estimate == null) {
                        unvoicedFrames++
                        if (voiced && unvoicedFrames >= UNVOICED_FRAME_COUNT) {
                            voiced = false
                            pitchSmoother.reset()
                            onPitch(
                                false,
                                0.0,
                                0.0,
                                0.0,
                                0,
                                if (yinAvailable) {
                                    HybridPitchFusion.ALGORITHM_FUSED
                                } else {
                                    HybridPitchFusion.ALGORITHM_FFT
                                },
                                frame.timeSeconds,
                            )
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
                        estimate.algorithm,
                        frame.timeSeconds,
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (running.get()) reportFailure(error)
            } finally {
                if (voiced) {
                    onPitch(
                        false,
                        0.0,
                        0.0,
                        0.0,
                        0,
                        if (yinAvailable) {
                            HybridPitchFusion.ALGORITHM_FUSED
                        } else {
                            HybridPitchFusion.ALGORITHM_FFT
                        },
                        recording.durationSeconds,
                    )
                }
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
                    algorithm: String,
                    timeSeconds: Double,
                ) -> Unit,
                onSpectrum: (
                    timeSeconds: Double,
                    magnitudes: FloatArray,
                    peaks: List<SpectrumPeak>,
                ) -> Unit,
                onFailure: (Throwable) -> Unit,
            ): HybridRecognitionEngine {
                val input = MicrophoneInput.start(AUDIO_READ_BUFFER_SAMPLES)
                return try {
                    HybridRecognitionEngine(
                        input = input,
                        recording = recording,
                        sensitivity = sensitivity,
                        onPitch = onPitch,
                        onSpectrum = onSpectrum,
                        onFailure = onFailure,
                    ).also { it.startThreads() }
                } catch (error: Throwable) {
                    input.stop()
                    throw error
                }
            }

            private fun midiToFrequency(midiPitch: Double): Double =
                440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)

            private fun velocityFromRms(rms: Double): Int {
                val decibels = 20.0 * log10(rms.coerceAtLeast(1e-9))
                val normalized = ((decibels - MINIMUM_DECIBELS) /
                    (MAXIMUM_DECIBELS - MINIMUM_DECIBELS)).coerceIn(0.0, 1.0)
                return (1.0 + normalized * 126.0).toInt().coerceIn(1, 127)
            }

            private const val MINIMUM_DECIBELS = -60.0
            private const val MAXIMUM_DECIBELS = -12.0
        }
    }

    private companion object {
        const val LOG_TAG = "PitchRecognition"
        const val EVENT_STATE = "pitchRecognitionState"
        const val EVENT_SOURCE = "microphone"
        const val PHASE_IDLE = "idle"
        const val PHASE_PERMISSION = "permission"
        const val PHASE_STARTING = "starting"
        const val PHASE_LISTENING = "listening"
        const val PHASE_ERROR = "error"
        const val SAMPLE_RATE = 16_000
        const val MINIMUM_INPUT_SENSITIVITY = 0.5f
        const val MAXIMUM_INPUT_SENSITIVITY = 2.0f
        const val ANALYSIS_FRAME_SIZE = 2_048
        const val ANALYSIS_HOP_SAMPLES = 256
        const val AUDIO_READ_BUFFER_SAMPLES = 256
        const val UNVOICED_FRAME_COUNT = 6
        val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
        )
    }
}
