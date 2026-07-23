package icu.ringona.xensynth.pitch

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

internal class PitchRecordingStore(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver

    fun save(
        snapshot: PitchRecordingSnapshot,
        noteMaps: List<Map<*, *>>,
        durationSeconds: Double,
        suggestedName: String,
    ): Map<String, Any> {
        require(snapshot.samples.isNotEmpty()) { "Microphone recording is empty" }
        val notes = noteMaps.mapNotNull(RecognizedPitchNote::fromMap)
        val duration = maxOf(
            durationSeconds.takeIf(Double::isFinite) ?: 0.0,
            snapshot.samples.size.toDouble() / snapshot.sampleRate,
        )
        val safeStem = suggestedName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .ifBlank { "XenSynth_microphone" }
        val recordingName = "${safeStem}_recording.wav"
        val recognizedName = "${safeStem}_recognized.wav"
        val recordingWave = PitchRecordingAudio.encodeWave(snapshot.samples, snapshot.sampleRate)
        val recognizedSamples = PitchRecordingAudio.renderRecognizedPitch(
            notes = notes,
            durationSeconds = duration,
            sampleRate = snapshot.sampleRate,
        )
        val recognizedWave = PitchRecordingAudio.encodeWave(
            recognizedSamples,
            snapshot.sampleRate,
        )

        var recordingUri: Uri? = null
        var recognizedUri: Uri? = null
        try {
            recordingUri = writeWave(recordingName, recordingWave)
            recognizedUri = writeWave(recognizedName, recognizedWave)
        } catch (error: Throwable) {
            recordingUri?.let { contentResolver.deleteQuietly(it) }
            recognizedUri?.let { contentResolver.deleteQuietly(it) }
            throw error
        }
        return mapOf(
            "saved" to true,
            "directory" to SAVE_DIRECTORY.removeSuffix("/"),
            "recordingName" to recordingName,
            "recognizedName" to recognizedName,
            "recordingUri" to recordingUri.toString(),
            "recognizedUri" to recognizedUri.toString(),
        )
    }

    private fun writeWave(displayName: String, bytes: ByteArray): Uri {
        val collection = MediaStore.Audio.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, WAVE_MIME_TYPE)
            put(MediaStore.Audio.Media.RELATIVE_PATH, SAVE_DIRECTORY)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(contentResolver.insert(collection, values)) {
            "Could not create $displayName"
        }
        try {
            contentResolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "Could not open $displayName" }
                output.write(bytes)
            }
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            contentResolver.deleteQuietly(uri)
            throw error
        }
    }

    private fun android.content.ContentResolver.deleteQuietly(uri: Uri) {
        runCatching { delete(uri, null, null) }
    }

    private companion object {
        const val WAVE_MIME_TYPE = "audio/wav"
        val SAVE_DIRECTORY = "${Environment.DIRECTORY_MUSIC}/XenSynth/"
    }
}
