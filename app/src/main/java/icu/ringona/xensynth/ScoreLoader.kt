package icu.ringona.xensynth

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

class ScoreLoader(
    private val contentResolver: ContentResolver
) {
    fun readUriBytes(uri: Uri): Result<ByteArray> {
        return runCatching {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open file" }
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
        }
    }

    fun displayName(uri: Uri): String {
        val resolverName = queryDisplayName(uri)
        if (resolverName != null) {
            return resolverName
        }

        val lastPath = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
        return lastPath ?: DEFAULT_SCORE_NAME
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val DEFAULT_SCORE_NAME = "selected-file"
    }
}
