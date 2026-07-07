package icu.ringona.xensynth

import android.content.res.AssetManager
import icu.ringona.xensynth.audio.NativeAudio
import icu.ringona.xensynth.audio.NativeAudioEngine
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DefaultSoundFontLoader(
    private val cacheDir: File,
    private val assets: AssetManager,
    private val nativeAudio: NativeAudio = NativeAudioEngine
) {
    fun load(): Result<Boolean> {
        return runCatching {
            val file = ensureDecryptedSoundFont()
            nativeAudio.loadSf2(file.absolutePath)
        }
    }

    private fun ensureDecryptedSoundFont(): File {
        val file = File(cacheDir, "$CACHE_ASSET_DIR/$CACHE_ASSET_NAME")
        val marker = File(file.parentFile, "$CACHE_ASSET_NAME.sha256")
        val packaged = assets.open("$PACKAGED_ASSET_DIR/$PACKAGED_ASSET_NAME").use { it.readBytes() }
        val packageHash = packaged.sha256Hex()
        if (file.isFile && file.length() > 0L && marker.readHashOrNull() == packageHash) {
            return file
        }

        file.parentFile?.mkdirs()
        val key = nativeAudio.soundFontKey()
        val decrypted = try {
            decryptPackage(packaged, key)
        } finally {
            key.fill(0)
        }

        val tmp = File.createTempFile("asset-", ".tmp", file.parentFile)
        try {
            tmp.outputStream().use { it.write(decrypted) }
            if (file.exists() && !file.delete()) {
                throw IOException("Could not replace cached asset")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            marker.writeText(packageHash)
        } finally {
            decrypted.fill(0)
            if (tmp.exists()) {
                tmp.delete()
            }
        }
        return file
    }

    private fun decryptPackage(packaged: ByteArray, key: ByteArray): ByteArray {
        if (key.size != KEY_BYTES) {
            throw IOException("Invalid asset key length")
        }
        if (packaged.size <= MAGIC.size + NONCE_BYTES + GCM_TAG_BYTES) {
            throw IOException("Invalid asset package")
        }
        if (!packaged.startsWith(MAGIC)) {
            throw IOException("Invalid asset package header")
        }

        val nonceOffset = MAGIC.size
        val payloadOffset = nonceOffset + NONCE_BYTES
        val nonce = packaged.copyOfRange(nonceOffset, payloadOffset)
        val payload = packaged.copyOfRange(payloadOffset, packaged.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(payload)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) {
            return false
        }
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) {
                return false
            }
        }
        return true
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        val chars = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    private fun File.readHashOrNull(): String? {
        return takeIf { it.isFile }?.readText()?.trim()
    }

    private companion object {
        const val PACKAGED_ASSET_DIR = "blob"
        const val PACKAGED_ASSET_NAME = "7f3a9c1d.bin"
        const val CACHE_ASSET_DIR = "blob"
        const val CACHE_ASSET_NAME = "7f3a9c1d.dat"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        val MAGIC = byteArrayOf(
            0x9d.toByte(), 0x72, 0xb4.toByte(), 0x1e,
            0x43, 0xe8.toByte(), 0x0d, 0xa6.toByte()
        )
        val HEX = "0123456789abcdef".toCharArray()
    }
}
