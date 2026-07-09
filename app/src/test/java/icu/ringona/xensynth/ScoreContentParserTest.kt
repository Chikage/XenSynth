package icu.ringona.xensynth

import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.view.ScaleGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreContentParserTest {
    @Test
    fun classifiesJsonByExtensionAndLeadingBrace() {
        val parser = parser()

        assertEquals(ScoreContentType.Tuning, parser.classify("tuning.json", byteArrayOf()))
        assertEquals(ScoreContentType.Tuning, parser.classify("tuning.txt", " \n { }".encodeToByteArray()))
        assertEquals(ScoreContentType.Score, parser.classify("song.mid", byteArrayOf(0x4D, 0x54)))
    }

    @Test
    fun convertsMuseScoreBeforeMidiParsing() {
        val converted = byteArrayOf(0x4D, 0x54, 0x68, 0x64)
        var convertedName = ""
        var parsedBytes: ByteArray? = null
        val parser = parser(
            converter = { _, name ->
                convertedName = name
                converted
            },
            midiParser = { bytes, _ ->
                parsedBytes = bytes
                score()
            }
        )

        parser.parseScore("piece.mscz", byteArrayOf(1, 2, 3))

        assertEquals("piece.mscz", convertedName)
        assertSame(converted, parsedBytes)
    }

    @Test
    fun treatsZipHeaderAsMuseScoreContent() {
        var converted = false
        val parser = parser(
            converter = { _, _ ->
                converted = true
                byteArrayOf(1)
            }
        )

        parser.parseScore("unknown.bin", byteArrayOf(0x50, 0x4B, 0x03, 0x04))

        assertTrue(converted)
    }

    @Test
    fun parsesPlainMidiWithoutConversion() {
        var converted = false
        var parsedName = ""
        val input = byteArrayOf(0x4D, 0x54)
        val parser = parser(
            converter = { _, _ ->
                converted = true
                byteArrayOf()
            },
            midiParser = { bytes, name ->
                assertSame(input, bytes)
                parsedName = name
                score()
            }
        )

        parser.parseScore("plain.mid", input)

        assertFalse(converted)
        assertEquals("plain.mid", parsedName)
    }

    @Test
    fun parsesTuningJsonThroughInjectedParser() {
        val guide = ScaleGuide.fromCustomProfile("demo", mapOf(0.0 to 1f))
        val parser = parser(tuningParser = { guide })

        val result = parser.parseTuning("{}".encodeToByteArray())

        assertSame(guide, result.scaleGuide)
        assertEquals(0.0, result.offsetCents, 0.0001)
    }

    @Test
    fun parsesTuningOffsetFromJson() {
        var parsedJson = ""
        val parser = parser(
            tuningOffsetParser = { json ->
                parsedJson = json
                30.0
            }
        )

        val result = parser.parseTuning(
            """
            {
              "profile": "demo",
              "offset": "+30",
              "Scale": {
                "100": 0.8
              }
            }
            """.trimIndent().encodeToByteArray()
        )

        assertTrue(parsedJson.contains("\"offset\""))
        assertEquals(30.0, result.offsetCents, 0.0001)
    }

    private fun parser(
        converter: (ByteArray, String) -> ByteArray = { bytes, _ -> bytes },
        midiParser: (ByteArray, String) -> ParsedScore = { _, _ -> score() },
        tuningParser: (String) -> ScaleGuide = { ScaleGuide.fromCustomProfile("test", mapOf(0.0 to 1f)) },
        tuningOffsetParser: (String) -> Double = { 0.0 }
    ): ScoreContentParser {
        return ScoreContentParser(
            museScoreConverter = converter,
            midiParser = midiParser,
            tuningParser = tuningParser,
            tuningOffsetParser = tuningOffsetParser
        )
    }

    private companion object {
        fun score(): ParsedScore {
            return ParsedScore(
                title = "test",
                format = "MIDX",
                ticksPerQuarter = 480,
                tempos = emptyList(),
                meters = emptyList(),
                tempoMap = emptyList(),
                rawEvents = emptyList(),
                notes = emptyList(),
                longNotes = emptyList(),
                duration = 0.0
            )
        }
    }
}
