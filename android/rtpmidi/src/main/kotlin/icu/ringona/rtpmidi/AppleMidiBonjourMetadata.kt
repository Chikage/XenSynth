package icu.ringona.rtpmidi

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object AppleMidiBonjourMetadata {
    const val MODEL_KEY = "model"
    private const val MAX_MODEL_BYTES = 96

    fun modelForPublishing(value: String?): String? = normalizeModel(value, truncate = true)

    fun parseModel(attributes: Map<String, ByteArray>): String? {
        val encoded = attributes.entries
            .firstOrNull { it.key.equals(MODEL_KEY, ignoreCase = true) }
            ?.value
            ?: return null
        val decoded = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
        }.getOrNull() ?: return null
        return normalizeModel(decoded, truncate = false)
    }

    private fun normalizeModel(value: String?, truncate: Boolean): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val result = StringBuilder(normalized.length)
        var encodedBytes = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = Character.codePointAt(normalized, index)
            if (codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code ||
                Character.isISOControl(codePoint)
            ) {
                return null
            }
            val characters = String(Character.toChars(codePoint))
            val characterBytes = characters.toByteArray(Charsets.UTF_8).size
            if (encodedBytes + characterBytes > MAX_MODEL_BYTES) {
                return if (truncate && result.isNotEmpty()) result.toString() else null
            }
            result.append(characters)
            encodedBytes += characterBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}
