package icu.ringona.xensynth.view

import android.content.Context
import android.util.Log
import icu.ringona.xensynth.R
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

class ScaleGuide private constructor(
    private val markRatios: Map<Char, Float>,
    private val scaleMarks: Map<Int, String>,
    private val customScale: CustomScale?
) {
    internal constructor(
        markRatios: Map<Char, Float>,
        scaleMarks: Map<Int, String>
    ) : this(markRatios, scaleMarks, null)

    val isCustom: Boolean
        get() = customScale != null

    val profileName: String?
        get() = customScale?.profileName

    val hasKeybind: Boolean
        get() = customScale?.keybind?.isNotEmpty() == true

    val usesFullMidiRange: Boolean
        get() = customScale?.type == CustomScaleType.Full

    fun keyboundPitchForMidiPitch(midiPitch: Int): Double? {
        val scale = customScale ?: return null
        val keybind = scale.keybind
        return when (scale.type) {
            CustomScaleType.Octave -> {
                val pitchClass = positiveModulo(midiPitch, OCTAVE_SEMITONES.toInt())
                val cents = keybind[pitchClass] ?: return null
                midiPitch - pitchClass + cents / 100.0
            }
            CustomScaleType.Full -> keybind[midiPitch]
        }
    }

    fun playbackPitchForMidiPitch(midiPitch: Int): Double {
        return keyboundPitchForMidiPitch(midiPitch) ?: midiPitch.toDouble()
    }

    fun hasScale(edo: Int): Boolean {
        if (customScale != null) {
            return true
        }
        return scaleMarks.containsKey(edo.coerceAtLeast(0))
    }

    fun labelForPitch(pitch: Double, isC: Boolean): String? {
        if (!isC) {
            return null
        }
        customScale?.let { scale ->
            if (scale.type == CustomScaleType.Full) {
                return if (abs(pitch - scale.referencePitch) <= STEP_EPSILON) {
                    FULL_REFERENCE_LABEL
                } else {
                    null
                }
            }
        }
        val midiPitch = kotlin.math.round(pitch).toInt()
        return if (midiPitch == C4_MIDI_PITCH) C4_LABEL else null
    }

    fun linesForVisibleRange(
        edo: Int,
        minPitch: Double,
        maxPitch: Double,
        minPitchSpacing: Double = 0.0
    ): List<ScaleLine> {
        val result = ArrayList<ScaleLine>()
        forEachLineInVisibleRange(edo, minPitch, maxPitch, minPitchSpacing) { pitch, ratio, strokeRatio, hasStrokeRatio, isC ->
            result += ScaleLine(
                pitch = pitch,
                ratio = ratio,
                strokeRatio = if (hasStrokeRatio) strokeRatio else null,
                isC = isC
            )
        }
        return result
    }

    fun forEachLineInVisibleRange(
        edo: Int,
        minPitch: Double,
        maxPitch: Double,
        minPitchSpacing: Double = 0.0,
        consumer: ScaleLineConsumer
    ) {
        customScale?.let { scale ->
            forEachCustomLineInVisibleRange(scale, minPitch, maxPitch, minPitchSpacing, consumer)
            return
        }
        val normalizedEdo = edo.coerceAtLeast(0)
        val pattern = scaleMarks[normalizedEdo].orEmpty()
        if (pattern.isBlank()) {
            return
        }
        val stepCount = if (normalizedEdo > 0) normalizedEdo else 1
        val step = OCTAVE_SEMITONES / stepCount
        val firstStep = floor(minPitch / step).toInt() - 1
        val lastStep = floor(maxPitch / step).toInt() + 1
        if (lastStep < firstStep) {
            return
        }
        for (stepIndex in firstStep..lastStep) {
            val pitch = stepIndex * step
            if (pitch < minPitch - step || pitch > maxPitch + step) {
                continue
            }
            val octaveStep = positiveModulo(stepIndex, stepCount)
            val marker = markerForStep(pattern, octaveStep)
            val ratio = markRatios[marker] ?: 0f
            if (ratio <= 0f) {
                continue
            }
            val isC = octaveStep == 0
            val visibilityRatio = DenseLineVisibility.ratioForStep(stepIndex, step, minPitchSpacing, isC)
            if (visibilityRatio <= 0f) {
                continue
            }
            consumer.onLine(pitch, ratio.coerceIn(0f, 1f) * visibilityRatio, 0f, false, isC)
        }
    }

    fun tickForPitch(edo: Int, pitch: Double, keyHeight: Float): KeyboardTickStyle {
        customScale?.let { scale ->
            return customTickForPitch(scale, pitch, keyHeight)
        }
        val normalizedEdo = edo.coerceAtLeast(0)
        val hasConfiguredScale = scaleMarks.containsKey(normalizedEdo)
        val pattern = scaleMarks[normalizedEdo].orEmpty()
        if (!hasConfiguredScale) {
            return legacyTickForPitch(pitch, keyHeight)
        }
        if (pattern.isBlank()) {
            return KeyboardTickStyle.Hidden
        }
        val stepCount = if (normalizedEdo > 0) normalizedEdo else 1
        val step = OCTAVE_SEMITONES / stepCount
        val nearestStep = floor(pitch / step + 0.5).toInt()
        val snappedPitch = nearestStep * step
        if (kotlin.math.abs(pitch - snappedPitch) > STEP_EPSILON) {
            return KeyboardTickStyle.Hidden
        }
        val octaveStep = positiveModulo(nearestStep, stepCount)
        val marker = markerForStep(pattern, octaveStep)
        val ratio = markRatios[marker] ?: 0f
        if (ratio <= 0f) {
            return KeyboardTickStyle.Hidden
        }
        val midiPitch = kotlin.math.round(pitch).toInt()
        val safeRatio = ratio.coerceIn(0f, 1f)
        return KeyboardTickStyle(
            length = keyHeight * C_TICK_HEIGHT_RATIO * safeRatio,
            alpha = (C_TICK_ALPHA * safeRatio).roundToInt().coerceIn(0, C_TICK_ALPHA),
            strokeWidth = if (octaveStep == 0) C_TICK_STROKE_WIDTH else MINOR_TICK_STROKE_WIDTH,
            midiPitch = midiPitch,
            isC = octaveStep == 0,
            isVisible = true
        )
    }

    fun impactTickForPitch(edo: Int, pitch: Double, keyHeight: Float): KeyboardTickStyle {
        val configured = tickForPitch(edo, pitch, keyHeight)
        return if (configured.isVisible) configured else legacyTickForPitch(pitch, keyHeight)
    }

    fun touchPitchForRaw(edo: Int, rawPitch: Double): Double? {
        customScale?.let { scale ->
            return customTouchPitchForRaw(scale, rawPitch)
        }
        val normalizedEdo = edo.coerceAtLeast(0)
        if (normalizedEdo <= 0) {
            return rawPitch
        }
        val pattern = scaleMarks[normalizedEdo]
        if (pattern.isNullOrBlank() || !pattern.contains(MUTE_TOUCH_MARK)) {
            return snapPitchToEdo(normalizedEdo, rawPitch)
        }
        val step = OCTAVE_SEMITONES / normalizedEdo
        val nearestStep = floor(rawPitch / step + 0.5).toInt()
        if (!isTouchMutedStep(pattern, normalizedEdo, nearestStep)) {
            return nearestStep * step
        }
        var leftStep = nearestStep
        var rightStep = nearestStep
        var leftPitch: Double? = null
        var rightPitch: Double? = null
        for (offset in 1..normalizedEdo) {
            if (leftPitch == null) {
                val candidate = nearestStep - offset
                if (!isTouchMutedStep(pattern, normalizedEdo, candidate)) {
                    leftStep = candidate
                    leftPitch = candidate * step
                }
            }
            if (rightPitch == null) {
                val candidate = nearestStep + offset
                if (!isTouchMutedStep(pattern, normalizedEdo, candidate)) {
                    rightStep = candidate
                    rightPitch = candidate * step
                }
            }
            if (leftPitch != null && rightPitch != null) {
                break
            }
        }
        val left = leftPitch
        val right = rightPitch
        return when {
            left == null && right == null -> null
            left == null -> right
            right == null -> left
            rawPitch - left < right - rawPitch -> leftStep * step
            else -> rightStep * step
        }
    }

    fun touchSlotWidth(edo: Int, rawPitch: Double): Double? {
        customScale?.let { scale ->
            return customTouchSlotWidth(scale, rawPitch)
        }
        val normalizedEdo = edo.coerceAtLeast(0)
        if (normalizedEdo <= 0) {
            return null
        }
        return OCTAVE_SEMITONES / normalizedEdo
    }

    private fun markerForStep(pattern: String, octaveStep: Int): Char {
        if (pattern.isEmpty()) {
            return HIDDEN_MARK
        }
        return pattern.getOrNull(octaveStep) ?: HIDDEN_MARK
    }

    private fun isTouchMutedStep(pattern: String, stepCount: Int, stepIndex: Int): Boolean {
        val octaveStep = positiveModulo(stepIndex, stepCount)
        return markerForStep(pattern, octaveStep) == MUTE_TOUCH_MARK
    }

    private fun snapPitchToEdo(edo: Int, rawPitch: Double): Double {
        val step = OCTAVE_SEMITONES / edo
        return floor(rawPitch / step + 0.5) * step
    }

    private fun forEachCustomLineInVisibleRange(
        scale: CustomScale,
        minPitch: Double,
        maxPitch: Double,
        minPitchSpacing: Double,
        consumer: ScaleLineConsumer
    ) {
        if (scale.type == CustomScaleType.Full) {
            forEachFullCustomLineInVisibleRange(scale, minPitch, maxPitch, minPitchSpacing, consumer)
            return
        }
        val firstOctave = floor(minPitch / OCTAVE_SEMITONES).toInt() - 1
        val lastOctave = floor(maxPitch / OCTAVE_SEMITONES).toInt() + 1
        if (lastOctave < firstOctave) {
            return
        }
        val spacing = sanitizedMinPitchSpacing(minPitchSpacing)
        var lastDrawnPitch = Double.NEGATIVE_INFINITY
        for (octave in firstOctave..lastOctave) {
            val basePitch = octave * OCTAVE_SEMITONES
            scale.marks.forEach { mark ->
                val pitch = basePitch + mark.pitchOffset
                if (pitch < minPitch - OCTAVE_SEMITONES || pitch > maxPitch + OCTAVE_SEMITONES) {
                    return@forEach
                }
                if (mark.ratio <= 0f) {
                    return@forEach
                }
                if (!mark.isC && pitch - lastDrawnPitch < spacing) {
                    return@forEach
                }
                consumer.onLine(pitch, mark.ratio, mark.ratio, true, mark.isC)
                lastDrawnPitch = pitch
            }
        }
    }

    private fun forEachFullCustomLineInVisibleRange(
        scale: CustomScale,
        minPitch: Double,
        maxPitch: Double,
        minPitchSpacing: Double,
        consumer: ScaleLineConsumer
    ) {
        val spacing = sanitizedMinPitchSpacing(minPitchSpacing)
        var lastDrawnPitch = Double.NEGATIVE_INFINITY
        scale.marks.forEach { mark ->
            val pitch = scale.referencePitch + mark.pitchOffset
            if (pitch < MIDI_PITCH_MIN.toDouble() || pitch > MIDI_PITCH_MAX.toDouble()) {
                return@forEach
            }
            if (pitch < minPitch - OCTAVE_SEMITONES || pitch > maxPitch + OCTAVE_SEMITONES) {
                return@forEach
            }
            if (mark.ratio <= 0f) {
                return@forEach
            }
            if (!mark.isC && pitch - lastDrawnPitch < spacing) {
                return@forEach
            }
            consumer.onLine(pitch, mark.ratio, mark.ratio, true, mark.isC)
            lastDrawnPitch = pitch
        }
    }

    private fun sanitizedMinPitchSpacing(value: Double): Double {
        return if (value.isFinite() && value > STEP_EPSILON) value else 0.0
    }

    private fun customTickForPitch(scale: CustomScale, pitch: Double, keyHeight: Float): KeyboardTickStyle {
        val mark = customMarkForPitch(scale, pitch) ?: return KeyboardTickStyle.Hidden
        if (mark.ratio <= 0f) {
            return KeyboardTickStyle.Hidden
        }
        val safeRatio = mark.ratio.coerceIn(0f, 1f)
        return KeyboardTickStyle(
            length = keyHeight * C_TICK_HEIGHT_RATIO * safeRatio,
            alpha = (C_TICK_ALPHA * safeRatio).roundToInt().coerceIn(0, C_TICK_ALPHA),
            strokeWidth = C_TICK_STROKE_WIDTH * safeRatio,
            midiPitch = kotlin.math.round(pitch).toInt(),
            isC = mark.isC,
            isVisible = true
        )
    }

    private fun customTouchPitchForRaw(scale: CustomScale, rawPitch: Double): Double? {
        return nearestCustomPitch(scale, rawPitch)?.pitch
    }

    private fun customTouchSlotWidth(scale: CustomScale, rawPitch: Double): Double? {
        val nearest = nearestCustomPitch(scale, rawPitch) ?: return null
        val previous = neighborCustomPitch(scale, nearest.index, nearest.octave, -1)
        val next = neighborCustomPitch(scale, nearest.index, nearest.octave, 1)
        return when {
            previous == null && next == null -> OCTAVE_SEMITONES
            previous == null -> (next!! - nearest.pitch) * 2.0
            next == null -> (nearest.pitch - previous) * 2.0
            else -> minOf(nearest.pitch - previous, next - nearest.pitch) * 2.0
        }.coerceAtLeast(STEP_EPSILON)
    }

    private fun customMarkForPitch(scale: CustomScale, pitch: Double): CustomMark? {
        if (scale.type == CustomScaleType.Full) {
            scale.marks.forEach { mark ->
                if (abs(pitch - (scale.referencePitch + mark.pitchOffset)) <= STEP_EPSILON) {
                    return mark
                }
            }
            return null
        }
        val octave = floor(pitch / OCTAVE_SEMITONES).toInt()
        for (candidateOctave in (octave - 1)..(octave + 1)) {
            val basePitch = candidateOctave * OCTAVE_SEMITONES
            scale.marks.forEach { mark ->
                if (abs(pitch - (basePitch + mark.pitchOffset)) <= STEP_EPSILON) {
                    return mark
                }
            }
        }
        return null
    }

    private fun nearestCustomPitch(scale: CustomScale, rawPitch: Double): CustomPitch? {
        if (scale.type == CustomScaleType.Full) {
            return nearestFullCustomPitch(scale, rawPitch)
        }
        var best: CustomPitch? = null
        val octave = floor(rawPitch / OCTAVE_SEMITONES).toInt()
        for (candidateOctave in (octave - 1)..(octave + 1)) {
            val basePitch = candidateOctave * OCTAVE_SEMITONES
            scale.marks.forEachIndexed { index, mark ->
                val pitch = basePitch + mark.pitchOffset
                val distance = abs(rawPitch - pitch)
                val current = best
                if (current == null ||
                    distance < current.distance - STEP_EPSILON ||
                    (abs(distance - current.distance) <= STEP_EPSILON && pitch > current.pitch)
                ) {
                    best = CustomPitch(
                        pitch = pitch,
                        distance = distance,
                        index = index,
                        octave = candidateOctave
                    )
                }
            }
        }
        return best
    }

    private fun nearestFullCustomPitch(scale: CustomScale, rawPitch: Double): CustomPitch? {
        var best: CustomPitch? = null
        scale.marks.forEachIndexed { index, mark ->
            val pitch = scale.referencePitch + mark.pitchOffset
            if (pitch < MIDI_PITCH_MIN.toDouble() || pitch > MIDI_PITCH_MAX.toDouble()) {
                return@forEachIndexed
            }
            val distance = abs(rawPitch - pitch)
            val current = best
            if (current == null ||
                distance < current.distance - STEP_EPSILON ||
                (abs(distance - current.distance) <= STEP_EPSILON && pitch > current.pitch)
            ) {
                best = CustomPitch(
                    pitch = pitch,
                    distance = distance,
                    index = index,
                    octave = 0
                )
            }
        }
        return best
    }

    private fun neighborCustomPitch(
        scale: CustomScale,
        index: Int,
        octave: Int,
        direction: Int
    ): Double? {
        if (scale.marks.isEmpty()) {
            return null
        }
        val nextIndex = index + direction
        if (scale.type == CustomScaleType.Full) {
            return if (nextIndex in scale.marks.indices) {
                scale.referencePitch + scale.marks[nextIndex].pitchOffset
            } else {
                null
            }
        }
        return when {
            nextIndex in scale.marks.indices -> {
                octave * OCTAVE_SEMITONES + scale.marks[nextIndex].pitchOffset
            }
            direction < 0 -> {
                (octave - 1) * OCTAVE_SEMITONES + scale.marks.last().pitchOffset
            }
            else -> {
                (octave + 1) * OCTAVE_SEMITONES + scale.marks.first().pitchOffset
            }
        }
    }

    private fun legacyTickForPitch(pitch: Double, keyHeight: Float): KeyboardTickStyle {
        val isSemitone = kotlin.math.round(pitch * 2.0).toInt() % 2 == 0
        val midiPitch = kotlin.math.round(pitch).toInt()
        val pc = positiveModulo(midiPitch, OCTAVE_SEMITONES.toInt())
        val natural = pc in NATURAL_PITCH_CLASSES
        val isC = pc == 0 && isSemitone
        return when {
            isC -> KeyboardTickStyle(
                length = keyHeight * C_TICK_HEIGHT_RATIO,
                alpha = C_TICK_ALPHA,
                strokeWidth = C_TICK_STROKE_WIDTH,
                midiPitch = midiPitch,
                isC = true,
                isVisible = true
            )
            isSemitone && natural -> KeyboardTickStyle(
                length = keyHeight * 0.66f,
                alpha = 140,
                strokeWidth = 1.2f,
                midiPitch = midiPitch,
                isC = false,
                isVisible = true
            )
            isSemitone -> KeyboardTickStyle(
                length = keyHeight * 0.49f,
                alpha = 97,
                strokeWidth = 1f,
                midiPitch = midiPitch,
                isC = false,
                isVisible = true
            )
            else -> KeyboardTickStyle(
                length = keyHeight * 0.25f,
                alpha = 61,
                strokeWidth = 1f,
                midiPitch = midiPitch,
                isC = false,
                isVisible = true
            )
        }
    }

    data class ScaleLine(
        val pitch: Double,
        val ratio: Float,
        val strokeRatio: Float?,
        val isC: Boolean
    )

    fun interface ScaleLineConsumer {
        fun onLine(pitch: Double, ratio: Float, strokeRatio: Float, hasStrokeRatio: Boolean, isC: Boolean)
    }

    data class KeyboardTickStyle(
        val length: Float,
        val alpha: Int,
        val strokeWidth: Float,
        val midiPitch: Int,
        val isC: Boolean,
        val isVisible: Boolean
    ) {
        companion object {
            val Hidden = KeyboardTickStyle(
                length = 0f,
                alpha = 0,
                strokeWidth = 0f,
                midiPitch = 0,
                isC = false,
                isVisible = false
            )
        }
    }

    private data class CustomScale(
        val profileName: String,
        val type: CustomScaleType,
        val referencePitch: Double,
        val marks: List<CustomMark>,
        val keybind: Map<Int, Double>
    )

    private enum class CustomScaleType {
        Octave,
        Full
    }

    private data class CustomMark(
        val cents: Double,
        val ratio: Float,
        val isC: Boolean
    ) {
        val pitchOffset: Double = cents / 100.0
    }

    private data class CustomPitch(
        val pitch: Double,
        val distance: Double,
        val index: Int,
        val octave: Int
    )

    companion object {
        private const val TAG = "ScaleGuide"
        private const val OCTAVE_SEMITONES = 12.0
        private const val HIDDEN_MARK = 'N'
        private const val MUTE_TOUCH_MARK = 'S'
        private const val STEP_EPSILON = 0.0001
        private const val C_TICK_HEIGHT_RATIO = 0.84f
        private const val C_TICK_ALPHA = 184
        private const val C_TICK_STROKE_WIDTH = 1.4f
        private const val MINOR_TICK_STROKE_WIDTH = 1f
        private const val DEFAULT_PROFILE_NAME = "TUN"
        private const val C4_LABEL = "C4"
        private const val FULL_REFERENCE_LABEL = "O"
        private const val C4_MIDI_PITCH = 60
        private const val A4_MIDI_PITCH = 69.0
        private const val A4_FREQUENCY_HZ = 440.0
        private const val MIDI_PITCH_MIN = 0
        private const val MIDI_PITCH_MAX = 127
        private val NATURAL_PITCH_CLASSES = setOf(0, 2, 4, 5, 7, 9, 11)
        private val NOTE_NAME_PATTERN = Regex(
            pattern = """^([A-Ga-g])([#bB]?)(-?\d+)(?:([+-])(\d+(?:\.\d+)?)(?:[cC]|\([cC]\))?)?$"""
        )
        private val NOTE_NAME_SEMITONES = mapOf(
            "C" to 0,
            "D" to 2,
            "E" to 4,
            "F" to 5,
            "G" to 7,
            "A" to 9,
            "B" to 11
        )

        fun fromResources(context: Context): ScaleGuide {
            return runCatching {
                val parser = context.resources.getXml(R.xml.scale)
                try {
                    parse(parser)
                } finally {
                    parser.close()
                }
            }.getOrElse { error ->
                Log.w(TAG, "Unable to parse built-in scale guide; using built-in ticks", error)
                ScaleGuide(emptyMap(), emptyMap())
            }
        }

        fun fromStream(input: InputStream): ScaleGuide {
            return input.use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(stream, null)
                parse(parser)
            }
        }

        fun fromString(xml: String): ScaleGuide {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            return parse(parser)
        }

        fun fromJsonStream(input: InputStream): ScaleGuide {
            return input.use { stream ->
                fromJsonString(stream.bufferedReader().use { it.readText() })
            }
        }

        fun fromJsonString(json: String): ScaleGuide {
            val root = JSONObject(json)
            val profileName = normalizedProfileName(root.optString("profile", DEFAULT_PROFILE_NAME))
            val type = parseCustomScaleType(root)
            val referencePitch = when (type) {
                CustomScaleType.Octave -> C4_MIDI_PITCH.toDouble()
                CustomScaleType.Full -> parseFullReferencePitch(root)
            }
            val scaleObject = when (val rawScale = root.opt("Scale") ?: root.opt("scale")) {
                is JSONObject -> rawScale
                else -> throw IllegalArgumentException("JSON tuning must contain a Scale object")
            }
            val keybind = when (val rawKeybind = root.opt("Keybind") ?: root.opt("keybind")) {
                null -> emptyMap()
                is JSONObject -> parseCustomKeybind(rawKeybind, type, referencePitch)
                else -> throw IllegalArgumentException("JSON tuning Keybind must be an object")
            }
            return fromCustomProfile(profileName, parseCustomMarks(scaleObject), keybind, type, referencePitch)
        }

        internal fun fromCustomProfile(
            profileName: String,
            marks: Map<Double, Float>,
            keybind: Map<Int, Double> = emptyMap()
        ): ScaleGuide {
            return fromCustomProfile(
                profileName = profileName,
                marks = marks,
                keybind = keybind,
                type = CustomScaleType.Octave,
                referencePitch = C4_MIDI_PITCH.toDouble()
            )
        }

        internal fun fromFullCustomProfile(
            profileName: String,
            referencePitch: Double,
            marks: Map<Double, Float>,
            keybindCents: Map<Int, Double> = emptyMap()
        ): ScaleGuide {
            val keybind = keybindCents.mapValues { (midiPitch, cents) ->
                require(midiPitch in MIDI_PITCH_MIN..MIDI_PITCH_MAX) {
                    "Keybind key '$midiPitch' must be between 0 and 127"
                }
                val targetPitch = referencePitch + cents / 100.0
                require(targetPitch >= MIDI_PITCH_MIN.toDouble() && targetPitch <= MIDI_PITCH_MAX.toDouble()) {
                    "Keybind value for '$midiPitch' maps outside MIDI pitch range 0..127"
                }
                targetPitch
            }
            return fromCustomProfile(
                profileName = profileName,
                marks = marks,
                keybind = keybind,
                type = CustomScaleType.Full,
                referencePitch = referencePitch
            )
        }

        private fun fromCustomProfile(
            profileName: String,
            marks: Map<Double, Float>,
            keybind: Map<Int, Double>,
            type: CustomScaleType,
            referencePitch: Double
        ): ScaleGuide {
            val customMarks = buildCustomMarks(marks, type, referencePitch)
            return ScaleGuide(
                markRatios = mapOf('0' to 1f, HIDDEN_MARK to 0f, MUTE_TOUCH_MARK to 0f),
                scaleMarks = emptyMap(),
                customScale = CustomScale(
                    profileName = normalizedProfileName(profileName),
                    type = type,
                    referencePitch = referencePitch,
                    marks = customMarks,
                    keybind = keybind.toSortedMap()
                )
            )
        }

        private fun parse(parser: XmlPullParser): ScaleGuide {
            val marks = mutableMapOf<Char, Float>()
            val scales = mutableMapOf<Int, String>()
            var section: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "Mark", "Scale" -> section = parser.name
                            "item" -> {
                                val key = parser.getAttributeValue(null, "key").orEmpty()
                                val value = parser.nextText().trim()
                                when (section) {
                                    "Mark" -> key.firstOrNull()?.let { mark ->
                                        marks[mark] = value.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                                    }
                                    "Scale" -> key.toIntOrNull()?.let { edo ->
                                        scales[edo] = value
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == section) {
                            section = null
                        }
                    }
                }
            }
            val finalMarks = mutableMapOf('0' to 1f)
            finalMarks.putAll(marks)
            finalMarks[HIDDEN_MARK] = 0f
            finalMarks[MUTE_TOUCH_MARK] = 0f
            return ScaleGuide(markRatios = finalMarks, scaleMarks = scales)
        }

        private fun parseCustomMarks(scaleObject: JSONObject): Map<Double, Float> {
            val marks = linkedMapOf<Double, Float>()
            val keys = scaleObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val cents = key.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Scale key '$key' is not a cents value")
                val ratio = customRatio(scaleObject.get(key), key)
                marks[cents] = ratio
            }
            return marks
        }

        private fun parseCustomKeybind(
            keybindObject: JSONObject,
            type: CustomScaleType,
            referencePitch: Double
        ): Map<Int, Double> {
            return when (type) {
                CustomScaleType.Octave -> parseOctaveCustomKeybind(keybindObject)
                CustomScaleType.Full -> parseFullCustomKeybind(keybindObject, referencePitch)
            }
        }

        private fun parseOctaveCustomKeybind(keybindObject: JSONObject): Map<Int, Double> {
            val bindings = linkedMapOf<Int, Double>()
            val keys = keybindObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val pitchClass = key.toIntOrNull()
                    ?: throw IllegalArgumentException("Keybind key '$key' is not a MIDI pitch class")
                require(pitchClass in 0 until OCTAVE_SEMITONES.toInt()) {
                    "Keybind key '$key' must be between 0 and 11"
                }
                val cents = customKeybindCents(keybindObject.get(key), key)
                require(cents >= 0.0 && cents < 1200.0) {
                    "Keybind value for '$key' must be greater than or equal to 0 and less than 1200 cents"
                }
                bindings[pitchClass] = cents
            }
            return bindings
        }

        private fun parseFullCustomKeybind(
            keybindObject: JSONObject,
            referencePitch: Double
        ): Map<Int, Double> {
            val bindings = linkedMapOf<Int, Double>()
            val keys = keybindObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val midiPitch = key.toIntOrNull()
                    ?: throw IllegalArgumentException("Keybind key '$key' is not a MIDI pitch")
                require(midiPitch in MIDI_PITCH_MIN..MIDI_PITCH_MAX) {
                    "Keybind key '$key' must be between 0 and 127"
                }
                val cents = customKeybindCents(keybindObject.get(key), key)
                val targetPitch = referencePitch + cents / 100.0
                require(targetPitch >= MIDI_PITCH_MIN.toDouble() && targetPitch <= MIDI_PITCH_MAX.toDouble()) {
                    "Keybind value for '$key' maps outside MIDI pitch range 0..127"
                }
                bindings[midiPitch] = targetPitch
            }
            return bindings
        }

        private fun customKeybindCents(value: Any, key: String): Double {
            val cents = when (value) {
                is Number -> value.toDouble()
                else -> value.toString().toDoubleOrNull()
            } ?: throw IllegalArgumentException("Keybind value for '$key' is not a cents value")
            require(!cents.isNaN() && !cents.isInfinite()) {
                "Keybind value for '$key' must be finite"
            }
            return cents
        }

        private fun customRatio(value: Any, key: String): Float {
            val ratio = when (value) {
                is Number -> value.toFloat()
                else -> value.toString().toFloatOrNull()
            } ?: throw IllegalArgumentException("Scale value for '$key' is not a ratio")
            require(ratio in 0f..1f) { "Scale value for '$key' must be between 0 and 1" }
            return ratio
        }

        private fun buildCustomMarks(
            marks: Map<Double, Float>,
            type: CustomScaleType,
            referencePitch: Double
        ): List<CustomMark> {
            return when (type) {
                CustomScaleType.Octave -> buildOctaveCustomMarks(marks)
                CustomScaleType.Full -> buildFullCustomMarks(marks, referencePitch)
            }
        }

        private fun buildOctaveCustomMarks(marks: Map<Double, Float>): List<CustomMark> {
            val customMarks = mutableListOf(CustomMark(cents = 0.0, ratio = 1f, isC = true))
            marks
                .asSequence()
                .filter { (cents, _) -> abs(cents) > STEP_EPSILON }
                .sortedBy { (cents, _) -> cents }
                .forEach { (cents, ratio) ->
                    require(cents > 0.0 && cents < 1200.0) {
                        "Scale cents must be greater than 0 and less than 1200"
                    }
                    customMarks += CustomMark(
                        cents = cents,
                        ratio = ratio.coerceIn(0f, 1f),
                        isC = false
                    )
                }
            return customMarks
        }

        private fun buildFullCustomMarks(marks: Map<Double, Float>, referencePitch: Double): List<CustomMark> {
            val customMarks = mutableListOf<CustomMark>()
            var hasReferenceMark = false
            marks
                .asSequence()
                .sortedBy { (cents, _) -> cents }
                .forEach { (cents, ratio) ->
                    require(cents.isFinite()) {
                        "Scale cents must be finite"
                    }
                    val normalizedCents = if (abs(cents) <= STEP_EPSILON) 0.0 else cents
                    val pitch = referencePitch + normalizedCents / 100.0
                    if (pitch < MIDI_PITCH_MIN.toDouble() || pitch > MIDI_PITCH_MAX.toDouble()) {
                        return@forEach
                    }
                    val isReference = abs(normalizedCents) <= STEP_EPSILON
                    if (isReference) {
                        hasReferenceMark = true
                    }
                    customMarks += CustomMark(
                        cents = normalizedCents,
                        ratio = ratio.coerceIn(0f, 1f),
                        isC = isReference
                    )
                }
            if (!hasReferenceMark &&
                referencePitch >= MIDI_PITCH_MIN.toDouble() &&
                referencePitch <= MIDI_PITCH_MAX.toDouble()
            ) {
                customMarks += CustomMark(cents = 0.0, ratio = 1f, isC = true)
            }
            return customMarks.sortedBy { mark -> mark.cents }
        }

        private fun parseCustomScaleType(root: JSONObject): CustomScaleType {
            val rawType = when {
                root.has("type") -> root.opt("type")
                root.has("Type") -> root.opt("Type")
                else -> null
            }
            val type = rawType
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.trim()
                ?.lowercase()
                .orEmpty()
            return when (type) {
                "", "octave" -> CustomScaleType.Octave
                "full" -> CustomScaleType.Full
                else -> throw IllegalArgumentException("JSON tuning type '$type' is not supported")
            }
        }

        internal fun fullReferencePitchFromOffset(rawOffset: Any?): Double {
            if (rawOffset == null || rawOffset == JSONObject.NULL) {
                return C4_MIDI_PITCH.toDouble()
            }
            return when (rawOffset) {
                is Number -> frequencyHzToMidiPitch(parseFiniteFrequencyHz(rawOffset.toDouble()))
                else -> parseFullReferencePitchText(rawOffset.toString())
            }
        }

        private fun parseFullReferencePitch(root: JSONObject): Double {
            return fullReferencePitchFromOffset(jsonOffsetValue(root))
        }

        private fun jsonOffsetValue(root: JSONObject): Any? {
            return when {
                root.has("offset") -> root.opt("offset")
                root.has("Offset") -> root.opt("Offset")
                else -> null
            }?.takeUnless { it == JSONObject.NULL }
        }

        private fun parseFullReferencePitchText(raw: String): Double {
            val text = raw.trim()
            if (text.isEmpty()) {
                return C4_MIDI_PITCH.toDouble()
            }
            parseNoteNamePitch(text)?.let { return it }
            parseCentsFromMidiZero(text)?.let { return it }
            return frequencyHzToMidiPitch(parseFrequencyHzText(text))
        }

        private fun parseNoteNamePitch(raw: String): Double? {
            val compact = raw.filterNot { it.isWhitespace() }
            val match = NOTE_NAME_PATTERN.matchEntire(compact) ?: return null
            val noteName = match.groupValues[1].uppercase()
            val accidental = match.groupValues[2]
            val octave = match.groupValues[3].toIntOrNull()
                ?: throw IllegalArgumentException("JSON tuning full offset note octave is not an integer")
            val baseSemitone = NOTE_NAME_SEMITONES[noteName]
                ?: throw IllegalArgumentException("JSON tuning full offset note '$noteName' is not supported")
            val accidentalOffset = when (accidental) {
                "#" -> 1
                "b", "B" -> -1
                else -> 0
            }
            val sign = match.groupValues[4]
            val centsText = match.groupValues[5]
            val cents = if (centsText.isEmpty()) {
                0.0
            } else {
                centsText.toDoubleOrNull()
                    ?: throw IllegalArgumentException("JSON tuning full offset note cents are not a number")
            }
            require(cents.isFinite()) {
                "JSON tuning full offset note cents must be finite"
            }
            val signedCents = when (sign) {
                "-" -> -cents
                else -> cents
            }
            val notePitch = (octave + 1) * OCTAVE_SEMITONES + baseSemitone + accidentalOffset
            return pitchWithNormalizedCents(notePitch.toDouble(), signedCents)
        }

        private fun pitchWithNormalizedCents(notePitch: Double, cents: Double): Double {
            val semitoneOffset = floor(cents / 100.0 + 0.5).toInt()
            val normalizedCents = cents - semitoneOffset * 100.0
            check(abs(normalizedCents) <= 50.0 + STEP_EPSILON) {
                "Normalized note-name cents must be within +/-50"
            }
            return notePitch + semitoneOffset + normalizedCents / 100.0
        }

        private fun parseCentsFromMidiZero(raw: String): Double? {
            val compact = raw.filterNot { it.isWhitespace() }
            val lower = compact.lowercase()
            if (!lower.endsWith("c")) {
                return null
            }
            val centsText = compact.dropLast(1)
            if (centsText.isEmpty()) {
                throw IllegalArgumentException("JSON tuning full offset cents value is empty")
            }
            val cents = centsText.toDoubleOrNull()
                ?: throw IllegalArgumentException("JSON tuning full offset cents value is not a number")
            require(cents.isFinite()) {
                "JSON tuning full offset cents value must be finite"
            }
            require(cents >= 0.0) {
                "JSON tuning full offset cents value must be non-negative"
            }
            return cents / 100.0
        }

        private fun parseFrequencyHzText(raw: String): Double {
            val text = raw.trim()
            val normalized = if (text.lowercase().endsWith("hz")) {
                text.dropLast(2).trim()
            } else {
                text
            }
            val frequency = normalized.toDoubleOrNull()
                ?: throw IllegalArgumentException("JSON tuning full offset is not a Hz frequency")
            return parseFiniteFrequencyHz(frequency)
        }

        private fun parseFiniteFrequencyHz(frequency: Double): Double {
            require(!frequency.isNaN() && !frequency.isInfinite()) {
                "JSON tuning full offset frequency must be finite"
            }
            require(frequency > 0.0) {
                "JSON tuning full offset frequency must be greater than 0 Hz"
            }
            return frequency
        }

        private fun frequencyHzToMidiPitch(frequencyHz: Double): Double {
            val pitch = A4_MIDI_PITCH + OCTAVE_SEMITONES * (ln(frequencyHz / A4_FREQUENCY_HZ) / ln(2.0))
            require(!pitch.isNaN() && !pitch.isInfinite()) {
                "JSON tuning full offset frequency must map to a finite MIDI pitch"
            }
            return pitch
        }

        private fun normalizedProfileName(raw: String): String {
            val ascii = raw
                .asSequence()
                .filter { it.code in 0x21..0x7E }
                .joinToString("")
            return ascii.ifBlank { DEFAULT_PROFILE_NAME }
        }

        private fun positiveModulo(value: Int, mod: Int): Int {
            return if (mod == 0) 0 else ((value % mod) + mod) % mod
        }
    }
}
