package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMidiBonjourMetadataTest {
    @Test
    fun parsesModelCaseInsensitively() {
        assertEquals(
            "Pixel 10 Pro",
            AppleMidiBonjourMetadata.parseModel(
                mapOf("MoDeL" to " Pixel 10 Pro ".toByteArray()),
            ),
        )
    }

    @Test
    fun rejectsMissingBlankControlAndMalformedModels() {
        assertNull(AppleMidiBonjourMetadata.parseModel(emptyMap()))
        assertNull(AppleMidiBonjourMetadata.parseModel(mapOf("model" to "  ".toByteArray())))
        assertNull(AppleMidiBonjourMetadata.parseModel(mapOf("model" to byteArrayOf(0x41, 0))))
        assertNull(
            AppleMidiBonjourMetadata.parseModel(
                mapOf("model" to byteArrayOf(0xC3.toByte(), 0x28)),
            ),
        )
    }

    @Test
    fun publishingModelIsUtf8BoundedWithoutSplittingCodePoints() {
        val published = AppleMidiBonjourMetadata.modelForPublishing("钢".repeat(100))

        assertTrue(published != null)
        assertTrue(published!!.toByteArray(Charsets.UTF_8).size <= 96)
        assertTrue(published.all { !it.isSurrogate() })
    }
}
