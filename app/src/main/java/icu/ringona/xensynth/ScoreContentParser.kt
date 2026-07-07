package icu.ringona.xensynth

import icu.ringona.xensynth.midi.MidiWaterfallParser
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.view.ScaleGuide

class ScoreContentParser(
    private val museScoreConverter: (ByteArray, String) -> ByteArray = MsczToMidx::convert,
    private val midiParser: (ByteArray, String) -> ParsedScore = MidiWaterfallParser::detectAndParse,
    private val tuningParser: (String) -> ScaleGuide = ScaleGuide::fromJsonString
) {
    fun classify(name: String, bytes: ByteArray): ScoreContentType {
        return if (isJsonTuningFile(name, bytes)) {
            ScoreContentType.Tuning
        } else {
            ScoreContentType.Score
        }
    }

    fun parseScore(name: String, bytes: ByteArray): ParsedScore {
        val scoreBytes = if (isMuseScoreFile(name, bytes)) {
            museScoreConverter(bytes, name)
        } else {
            bytes
        }
        return midiParser(scoreBytes, name)
    }

    fun parseTuning(bytes: ByteArray): ScaleGuide {
        return tuningParser(bytes.toString(Charsets.UTF_8))
    }

    private fun isMuseScoreFile(name: String, bytes: ByteArray): Boolean {
        val lowerName = name.lowercase()
        return lowerName.endsWith(".mscz") ||
            lowerName.endsWith(".mscx") ||
            bytes.hasZipHeader()
    }

    private fun isJsonTuningFile(name: String, bytes: ByteArray): Boolean {
        val lowerName = name.lowercase()
        if (lowerName.endsWith(".json")) {
            return true
        }
        return bytes
            .asSequence()
            .map { it.toInt().toChar() }
            .firstOrNull { !it.isWhitespace() } == '{'
    }

    private fun ByteArray.hasZipHeader(): Boolean {
        return size >= 4 &&
            this[0] == 0x50.toByte() &&
            this[1] == 0x4B.toByte() &&
            this[2] == 0x03.toByte() &&
            this[3] == 0x04.toByte()
    }
}

enum class ScoreContentType {
    Score,
    Tuning
}
