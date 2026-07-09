package icu.ringona.xensynth

import icu.ringona.xensynth.midi.MidiWaterfallParser
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.view.ScaleGuide
import org.json.JSONObject

class ScoreContentParser(
    private val museScoreConverter: (ByteArray, String) -> ByteArray = MsczToMidx::convert,
    private val midiParser: (ByteArray, String) -> ParsedScore = MidiWaterfallParser::detectAndParse,
    private val tuningParser: (String) -> ScaleGuide = ScaleGuide::fromJsonString,
    private val tuningOffsetParser: (String) -> Double = ::parseJsonTuningOffsetCents
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

    fun parseTuning(bytes: ByteArray): TuningParseResult {
        val json = bytes.toString(Charsets.UTF_8)
        return TuningParseResult(
            scaleGuide = tuningParser(json),
            offsetCents = tuningOffsetParser(json)
        )
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

private fun parseJsonTuningOffsetCents(json: String): Double {
    val root = JSONObject(json)
    if (isFullTuningType(root)) {
        return 0.0
    }
    val rawOffset = when {
        root.has("offset") -> root.opt("offset")
        root.has("Offset") -> root.opt("Offset")
        else -> null
    }
    if (rawOffset == null || rawOffset == JSONObject.NULL) {
        return 0.0
    }
    val textOffset = rawOffset.toString().trim()
    if (textOffset.isEmpty()) {
        return 0.0
    }
    val cents = when (rawOffset) {
        is Number -> rawOffset.toDouble()
        else -> {
            val normalizedOffset = textOffset
                .removeSuffix("c")
                .removeSuffix("C")
                .trim()
            if (normalizedOffset.isEmpty()) {
                0.0
            } else {
                normalizedOffset.toDoubleOrNull()
            }
        }
    } ?: throw IllegalArgumentException("JSON tuning offset is not a cents value")
    require(!cents.isNaN() && !cents.isInfinite()) {
        "JSON tuning offset must be finite"
    }
    return cents
}

private fun isFullTuningType(root: JSONObject): Boolean {
    val rawType = when {
        root.has("type") -> root.opt("type")
        root.has("Type") -> root.opt("Type")
        else -> null
    }
    return rawType
        ?.takeUnless { it == JSONObject.NULL }
        ?.toString()
        ?.trim()
        ?.equals("full", ignoreCase = true) == true
}

data class TuningParseResult(
    val scaleGuide: ScaleGuide,
    val offsetCents: Double
)

enum class ScoreContentType {
    Score,
    Tuning
}
