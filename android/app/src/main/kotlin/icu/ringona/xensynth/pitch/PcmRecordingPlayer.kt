package icu.ringona.xensynth.pitch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal class PcmRecordingPlayer(private val sampleRate: Int) {
    private val lock = Any()
    private var session: PlaybackSession? = null

    fun play(samples: ShortArray, fromSeconds: Double): Boolean {
        stop()
        val startSample = (fromSeconds.coerceAtLeast(0.0) * sampleRate)
            .toLong()
            .coerceAtMost(samples.size.toLong())
            .toInt()
        if (startSample >= samples.size) return false
        val next = PlaybackSession(samples, startSample, sampleRate)
        synchronized(lock) { session = next }
        return try {
            next.start()
            true
        } catch (error: Throwable) {
            synchronized(lock) {
                if (session === next) session = null
            }
            next.stop()
            throw error
        }
    }

    fun stop() {
        val previous = synchronized(lock) {
            session.also { session = null }
        }
        previous?.stop()
    }

    private class PlaybackSession(
        private val samples: ShortArray,
        private val startSample: Int,
        sampleRate: Int,
    ) {
        private val running = AtomicBoolean(true)
        private val audioTrack: AudioTrack
        private val thread: Thread

        init {
            val minimumBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBufferBytes > 0) { "16 kHz microphone recording playback is unavailable" }
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(
                    maxOf(minimumBufferBytes * 2, PLAYBACK_CHUNK_SAMPLES * Short.SIZE_BYTES),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                "Could not initialize microphone recording playback"
            }
            thread = Thread(::run, "XenSynth-MicrophonePlayback")
        }

        fun start() {
            audioTrack.play()
            thread.start()
        }

        fun stop() {
            if (!running.getAndSet(false)) return
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
            if (thread.isAlive && Thread.currentThread() !== thread) thread.interrupt()
        }

        private fun run() {
            var offset = startSample
            try {
                while (running.get() && offset < samples.size) {
                    val count = minOf(PLAYBACK_CHUNK_SAMPLES, samples.size - offset)
                    val written = audioTrack.write(
                        samples,
                        offset,
                        count,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) error("Microphone recording playback failed with code $written")
                    offset += written
                }
            } catch (error: Throwable) {
                if (running.get()) Log.e(LOG_TAG, "microphone recording playback failed", error)
            } finally {
                stop()
            }
        }

        private companion object {
            const val LOG_TAG = "PitchRecognition"
            const val PLAYBACK_CHUNK_SAMPLES = 2_048
        }
    }
}
