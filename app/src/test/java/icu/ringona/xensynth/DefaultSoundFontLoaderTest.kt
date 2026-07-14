package icu.ringona.xensynth

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSoundFontLoaderTest {
    @Test
    fun loadRemovesLegacyPlaintextCacheBeforeNativeLoad() {
        val cacheDir = Files.createTempDirectory("xensynth-sf-cache").toFile()
        try {
            val legacyDir = cacheDir.resolve("blob").apply { mkdirs() }
            legacyDir.resolve("7f3a9c1d.dat").writeText("plaintext")
            legacyDir.resolve("7f3a9c1d.dat.sha256").writeText("marker")
            val nativeAudio = FakeNativeAudio()

            val result = DefaultSoundFontLoader(cacheDir, nativeAudio).load()

            assertTrue(result.getOrThrow())
            assertFalse(legacyDir.exists())
            assertEquals(1, nativeAudio.loadBuiltinSf2Calls)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
