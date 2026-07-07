package icu.ringona.xensynth.midi

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class ParsedScore(
    val title: String,
    val format: String,
    val ticksPerQuarter: Int,
    val tempos: List<TempoEvent>,
    val meters: List<MeterEvent>,
    val tempoMap: List<TempoPoint>,
    val rawEvents: List<RawNoteEvent>,
    val notes: List<WaterfallNote>,
    val longNotes: List<WaterfallNote>,
    val duration: Double
)

data class TempoEvent(
    val tick: Long,
    val usPerQuarter: Double
)

data class MeterEvent(
    val tick: Long,
    val numerator: Int,
    val denominator: Int
)

data class TempoPoint(
    val tick: Long,
    val second: Double,
    val usPerQuarter: Double
)

data class RawNoteEvent(
    val tick: Long,
    val pitch: Int,
    val pitchFloat: Double?,
    val midiPitch: Int,
    val cents: Double,
    val velocity: Int,
    val track: Int,
    val channel: Int,
    val program: Int,
    val bankMsb: Int,
    val bankLsb: Int,
    val order: Long
)

data class WaterfallNote(
    val startTick: Long,
    val endTick: Long,
    val start: Double,
    val end: Double,
    val pitch: Double,
    val midiPitch: Int,
    val cents: Double,
    val velocity: Int,
    val channel: Int,
    val track: Int,
    val program: Int,
    val bankMsb: Int,
    val bankLsb: Int
)

object MidiWaterfallParser {
    private const val MIDX_META_TYPE = 0x7F
    private const val MIDX_PITCHED_OFFSET_PAYLOAD_LEN = 7
    private const val MIDX_EXPERIMENTAL_MANUFACTURER_ID = 0x7D
    private const val MIDX_PITCHED_OFFSET_RECORD_TYPE = 0x03
    private const val OFFSET_CENT_RANGE = 64.0
    private const val OFFSET_MAGNITUDE_STEPS = 32768.0
    private const val DEFAULT_TEMPO_US_PER_QUARTER = 500000.0
    private const val MIN_PITCH = 21
    private const val MAX_PITCH = 108
    private const val NOTE_RENDER_LOOKBACK_SECONDS = 8.0

    fun detectAndParse(bytes: ByteArray, fileName: String = "selected-file"): ParsedScore {
        val head = ByteReader(bytes, fileName).readAscii(minOf(8, bytes.size))
        return if (head.startsWith("SMF2CLIP")) {
            parseMidi2Clip(bytes, fileName)
        } else {
            parseSmfMidx(bytes, fileName)
        }
    }

    fun normalizeTempos(tempos: List<TempoEvent>): List<TempoEvent> {
        val byTick = linkedMapOf<Long, TempoEvent>()
        tempos.forEach { tempo ->
            if (tempo.usPerQuarter > 0.0) {
                byTick[tempo.tick] = tempo
            }
        }
        val out = byTick.keys.sorted().mapNotNull { byTick[it] }.toMutableList()
        if (out.isEmpty() || out.first().tick != 0L) {
            out.add(0, TempoEvent(0, DEFAULT_TEMPO_US_PER_QUARTER))
        }
        return out
    }

    fun normalizeMeters(meters: List<MeterEvent>): List<MeterEvent> {
        val byTick = linkedMapOf<Long, MeterEvent>()
        meters.forEach { meter ->
            val tick = max(0L, meter.tick)
            byTick[tick] = MeterEvent(
                tick = tick,
                numerator = max(1, meter.numerator),
                denominator = max(1, meter.denominator)
            )
        }
        val out = byTick.keys.sorted().mapNotNull { byTick[it] }.toMutableList()
        if (out.isEmpty() || out.first().tick != 0L) {
            out.add(0, MeterEvent(0, 4, 4))
        }
        return out
    }

    fun makeTempoMap(tempos: List<TempoEvent>, ticksPerQuarter: Int): List<TempoPoint> {
        val normalized = normalizeTempos(tempos)
        val map = mutableListOf<TempoPoint>()
        var currentSec = 0.0
        var prevTick = 0L
        var prevUs = DEFAULT_TEMPO_US_PER_QUARTER
        normalized.forEach { tempo ->
            currentSec += (tempo.tick - prevTick) * prevUs / 1_000_000.0 / ticksPerQuarter
            map += TempoPoint(tempo.tick, currentSec, tempo.usPerQuarter)
            prevTick = tempo.tick
            prevUs = tempo.usPerQuarter
        }
        return map
    }

    fun tickToSeconds(tick: Long, tempoMap: List<TempoPoint>, ticksPerQuarter: Int): Double {
        var lo = 0
        var hi = tempoMap.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tempoMap[mid].tick <= tick) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val item = tempoMap[max(0, hi)]
        return item.second + (tick - item.tick) * item.usPerQuarter / 1_000_000.0 / ticksPerQuarter
    }

    fun secondsToTick(second: Double, tempoMap: List<TempoPoint>, ticksPerQuarter: Int): Double {
        val sec = max(0.0, second)
        var lo = 0
        var hi = tempoMap.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tempoMap[mid].second <= sec) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val item = tempoMap[max(0, hi)]
        return item.tick + (sec - item.second) * 1_000_000.0 * ticksPerQuarter / item.usPerQuarter
    }

    fun measureTicks(meter: MeterEvent, ticksPerQuarter: Int): Double {
        return max(1.0, ticksPerQuarter * 4.0 * meter.numerator / meter.denominator)
    }

    private fun parseSmfMidx(bytes: ByteArray, fileName: String): ParsedScore {
        val reader = ByteReader(bytes, fileName)
        if (reader.readAscii(4) != "MThd") {
            throw IllegalArgumentException("Not a MIDI/MIDX file: missing MThd")
        }
        val headerLen = reader.readU32().toInt()
        val header = ByteReader(reader.read(headerLen), "MThd")
        val midiFormat = header.readU16()
        val trackCount = header.readU16()
        val division = header.readU16()
        if ((division and 0x8000) != 0) {
            throw IllegalArgumentException("SMPTE time division is not supported")
        }
        if (midiFormat != 0 && midiFormat != 1) {
            throw IllegalArgumentException("Unsupported MIDI format $midiFormat")
        }

        val tempos = mutableListOf(TempoEvent(0, DEFAULT_TEMPO_US_PER_QUARTER))
        val meters = mutableListOf(MeterEvent(0, 4, 4))
        val raw = mutableListOf<RawNoteEvent>()
        var order = 0L

        for (track in 0 until trackCount) {
            if (reader.remaining() <= 0) {
                break
            }
            val chunkType = reader.readAscii(4)
            val chunkLen = reader.readU32().toInt()
            val chunk = reader.read(chunkLen)
            if (chunkType != "MTrk") {
                continue
            }
            val tr = ByteReader(chunk, "MTrk[$track]")
            var tick = 0L
            var runningStatus: Int? = null
            val programs = mutableMapOf<Int, Int>()
            val bankMsb = mutableMapOf<Int, Int>()
            val bankLsb = mutableMapOf<Int, Int>()
            val inlineOffsets = mutableListOf<InlineOffset>()

            while (tr.remaining() > 0) {
                tick += tr.readVlq().toLong()
                val statusOrData = tr.readByte()

                if (statusOrData == 0xFF) {
                    val metaType = tr.readByte()
                    val payloadLen = tr.readVlq()
                    val payload = tr.read(payloadLen)
                    if (metaType == 0x2F) {
                        break
                    }
                    when {
                        metaType == 0x51 && payloadLen == 3 -> {
                            tempos += TempoEvent(
                                tick,
                                (((payload[0].u() shl 16) or (payload[1].u() shl 8) or payload[2].u()).toDouble())
                            )
                        }
                        metaType == 0x58 && payloadLen >= 2 -> {
                            meters += MeterEvent(tick, payload[0].u(), 2.0.pow(payload[1].u()).roundToInt())
                        }
                        metaType == MIDX_META_TYPE && payloadLen == MIDX_PITCHED_OFFSET_PAYLOAD_LEN -> {
                            val decoded = decodePitchedOffsetPayload(payload)
                            if (decoded != null) {
                                if (inlineOffsets.isNotEmpty() && inlineOffsets.last().tick != tick) {
                                    inlineOffsets.clear()
                                }
                                inlineOffsets += InlineOffset(tick, decoded.pitch, decoded.cents)
                            } else {
                                inlineOffsets.clear()
                            }
                        }
                        else -> inlineOffsets.clear()
                    }
                    continue
                }

                if (statusOrData == 0xF0 || statusOrData == 0xF7) {
                    tr.read(tr.readVlq())
                    runningStatus = null
                    inlineOffsets.clear()
                    continue
                }

                if (statusOrData >= 0xF0) {
                    skipSystemEvent(tr, statusOrData)
                    runningStatus = null
                    inlineOffsets.clear()
                    continue
                }

                val status: Int
                var firstData: Int? = null
                if ((statusOrData and 0x80) != 0) {
                    status = statusOrData
                    runningStatus = status
                } else {
                    status = runningStatus ?: throw IllegalArgumentException("Running status without prior status")
                    firstData = statusOrData
                }

                val eventType = status and 0xF0
                val channel = status and 0x0F
                if (eventType == 0xC0 || eventType == 0xD0) {
                    val data1only = firstData ?: tr.readByte()
                    if (eventType == 0xC0) {
                        programs[channel] = data1only
                    }
                    inlineOffsets.clear()
                    continue
                }

                val data1 = firstData ?: tr.readByte()
                val data2 = tr.readByte()
                if (eventType == 0xB0) {
                    if (data1 == 0) {
                        bankMsb[channel] = data2
                    } else if (data1 == 32) {
                        bankLsb[channel] = data2
                    }
                }
                if (eventType == 0x80 || eventType == 0x90) {
                    val velocity = if (eventType == 0x90) data2 else 0
                    var effectivePitch = data1
                    var noteCents = 0.0
                    if (velocity > 0) {
                        if (inlineOffsets.isNotEmpty() && inlineOffsets.last().tick != tick) {
                            inlineOffsets.clear()
                        }
                        val inline = popInline(inlineOffsets, data1, tick)
                        if (inline != null) {
                            effectivePitch = inline.pitch
                            noteCents = inline.cents
                        }
                    } else {
                        inlineOffsets.clear()
                    }
                    raw += RawNoteEvent(
                        tick = tick,
                        pitch = effectivePitch,
                        pitchFloat = null,
                        midiPitch = data1,
                        cents = noteCents,
                        velocity = velocity,
                        track = track,
                        channel = channel,
                        program = programs[channel] ?: 0,
                        bankMsb = bankMsb[channel] ?: 0,
                        bankLsb = bankLsb[channel] ?: 0,
                        order = order++
                    )
                } else {
                    inlineOffsets.clear()
                }
            }
        }
        return finalizeParsed(fileName, "MIDX", division, tempos, meters, raw)
    }

    private fun parseMidi2Clip(bytes: ByteArray, fileName: String): ParsedScore {
        val reader = ByteReader(bytes, fileName)
        if (reader.readAscii(8) != "SMF2CLIP") {
            throw IllegalArgumentException("Not a MIDI 2.0 Clip file: missing SMF2CLIP")
        }
        var ticksPerQuarter = 480
        var tick = 0L
        val tempos = mutableListOf(TempoEvent(0, DEFAULT_TEMPO_US_PER_QUARTER))
        val meters = mutableListOf(MeterEvent(0, 4, 4))
        val raw = mutableListOf<RawNoteEvent>()
        var order = 0L
        val programs = mutableMapOf<String, Int>()
        val bankMsb = mutableMapOf<String, Int>()
        val bankLsb = mutableMapOf<String, Int>()

        while (reader.remaining() > 0) {
            val first = reader.peekByte()
            val mt = first shr 4
            val packet = reader.read(umpPacketSize(mt))

            if (mt == 0x0) {
                val utilityStatus = (packet[1].u() shr 4) and 0x0F
                if (utilityStatus == 0x3) {
                    ticksPerQuarter = max(1, ((packet[2].u() shl 8) or packet[3].u()).takeIf { it != 0 } ?: ticksPerQuarter)
                } else if (utilityStatus == 0x4) {
                    val delta = ((packet[1].u() and 0x0F) shl 16) or (packet[2].u() shl 8) or packet[3].u()
                    tick += delta.toLong()
                }
                continue
            }

            if (mt == 0xD && packet.size >= 16) {
                if (packet[1].u() == 0x10 && packet[2].u() == 0x00 && packet[3].u() == 0x00) {
                    val tenNs = readU32FromBytes(packet, 4)
                    if (tenNs > 0) {
                        tempos += TempoEvent(tick, tenNs / 100.0)
                    }
                }
                continue
            }

            if (mt != 0x4 || packet.size < 8) {
                continue
            }
            val statusByte = packet[1].u()
            val eventType = statusByte and 0xF0
            val channel = statusByte and 0x0F
            val key = channel.toString()
            val note = packet[2].u() and 0x7F
            val attributeType = packet[3].u()
            val velocity16 = (packet[4].u() shl 8) or packet[5].u()
            val attribute = (packet[6].u() shl 8) or packet[7].u()

            if (eventType == 0xB0) {
                val controller = packet[2].u() and 0x7F
                val controllerValue = scaleDownU32To7(readU32FromBytes(packet, 4))
                if (controller == 0) {
                    bankMsb[key] = controllerValue
                } else if (controller == 32) {
                    bankLsb[key] = controllerValue
                }
                continue
            }

            if (eventType == 0xC0) {
                programs[key] = packet[4].u() and 0x7F
                if ((packet[3].u() and 0x01) != 0) {
                    bankMsb[key] = packet[6].u() and 0x7F
                    bankLsb[key] = packet[7].u() and 0x7F
                }
                continue
            }

            if (eventType == 0x90) {
                val pitchFloat = if (attributeType == 0x03) attribute / 512.0 else note.toDouble()
                val velocity = if (velocity16 > 0) max(1, (velocity16 / 65535.0 * 127).roundToInt()) else 0
                raw += RawNoteEvent(
                    tick = tick,
                    pitch = kotlin.math.floor(pitchFloat).toInt(),
                    pitchFloat = pitchFloat,
                    midiPitch = note,
                    cents = (pitchFloat - kotlin.math.floor(pitchFloat)) * 100.0,
                    velocity = velocity,
                    track = 0,
                    channel = channel,
                    program = programs[key] ?: 0,
                    bankMsb = bankMsb[key] ?: 0,
                    bankLsb = bankLsb[key] ?: 0,
                    order = order++
                )
            } else if (eventType == 0x80) {
                raw += RawNoteEvent(
                    tick = tick,
                    pitch = note,
                    pitchFloat = note.toDouble(),
                    midiPitch = note,
                    cents = 0.0,
                    velocity = 0,
                    track = 0,
                    channel = channel,
                    program = programs[key] ?: 0,
                    bankMsb = bankMsb[key] ?: 0,
                    bankLsb = bankLsb[key] ?: 0,
                    order = order++
                )
            }
        }
        return finalizeParsed(fileName, "MIDI 2.0 Clip", ticksPerQuarter, tempos, meters, raw)
    }

    private fun finalizeParsed(
        title: String,
        format: String,
        ticksPerQuarter: Int,
        tempos: List<TempoEvent>,
        meters: List<MeterEvent>,
        rawEvents: List<RawNoteEvent>
    ): ParsedScore {
        if (rawEvents.isEmpty()) {
            throw IllegalArgumentException("No note events found")
        }
        val tempoMap = makeTempoMap(tempos, ticksPerQuarter)
        val normalizedMeters = normalizeMeters(meters)
        val notes = pairNotes(rawEvents, tempoMap, ticksPerQuarter)
        val longNotes = notes.filter { it.end - it.start > NOTE_RENDER_LOOKBACK_SECONDS }
        return ParsedScore(
            title = title,
            format = format,
            ticksPerQuarter = ticksPerQuarter,
            tempos = normalizeTempos(tempos),
            meters = normalizedMeters,
            tempoMap = tempoMap,
            rawEvents = rawEvents,
            notes = notes,
            longNotes = longNotes,
            duration = notes.maxOfOrNull { it.end } ?: 0.0
        )
    }

    private fun pairNotes(
        rawEvents: List<RawNoteEvent>,
        tempoMap: List<TempoPoint>,
        ticksPerQuarter: Int
    ): List<WaterfallNote> {
        val sorted = rawEvents.sortedWith { a, b ->
            when {
                a.tick != b.tick -> a.tick.compareTo(b.tick)
                (a.velocity == 0) != (b.velocity == 0) -> if (a.velocity == 0) -1 else 1
                else -> a.order.compareTo(b.order)
            }
        }
        val active = mutableMapOf<String, ArrayDeque<RawNoteEvent>>()
        val notes = mutableListOf<WaterfallNote>()
        sorted.forEach { event ->
            val key = "${event.track}:${event.channel}:${event.midiPitch}"
            if (event.velocity > 0) {
                active.getOrPut(key) { ArrayDeque() }.addLast(event)
            } else {
                val queue = active[key]
                if (queue.isNullOrEmpty()) {
                    return@forEach
                }
                val start = queue.removeFirst()
                notes += makeNote(start, max(event.tick, start.tick), tempoMap, ticksPerQuarter)
            }
        }
        active.values.forEach { queue ->
            queue.forEach { start ->
                notes += makeNote(start, start.tick + ticksPerQuarter, tempoMap, ticksPerQuarter)
            }
        }
        return notes
            .sortedWith(compareBy<WaterfallNote> { it.start }.thenBy { it.pitch })
            .filter { it.pitch >= MIN_PITCH - 1 && it.pitch <= MAX_PITCH + 1 }
    }

    private fun makeNote(
        start: RawNoteEvent,
        endTick: Long,
        tempoMap: List<TempoPoint>,
        ticksPerQuarter: Int
    ): WaterfallNote {
        val startPitchFloat = start.pitchFloat ?: (start.pitch + start.cents / 100.0)
        return WaterfallNote(
            startTick = start.tick,
            endTick = endTick,
            start = tickToSeconds(start.tick, tempoMap, ticksPerQuarter),
            end = tickToSeconds(endTick, tempoMap, ticksPerQuarter),
            pitch = startPitchFloat,
            midiPitch = start.midiPitch,
            cents = (startPitchFloat - startPitchFloat.roundToInt()) * 100.0,
            velocity = start.velocity,
            channel = start.channel,
            track = start.track,
            program = start.program,
            bankMsb = start.bankMsb,
            bankLsb = start.bankLsb
        )
    }

    private fun popInline(inlineOffsets: MutableList<InlineOffset>, midiPitch: Int, tick: Long): InlineOffset? {
        val exact = inlineOffsets.indexOfFirst { it.tick == tick && it.pitch == midiPitch }
        if (exact >= 0) {
            return inlineOffsets.removeAt(exact)
        }
        var found = -1
        inlineOffsets.forEachIndexed { index, inline ->
            if (inline.tick == tick) {
                if (found != -1) {
                    return null
                }
                found = index
            }
        }
        return if (found >= 0) inlineOffsets.removeAt(found) else null
    }

    private fun decodePitchedOffsetPayload(payload: ByteArray): InlineOffsetPayload? {
        if (
            payload.size == MIDX_PITCHED_OFFSET_PAYLOAD_LEN &&
            payload[0].u() == MIDX_EXPERIMENTAL_MANUFACTURER_ID &&
            payload[1].u() == 0x58 &&
            payload[2].u() == 0x54 &&
            payload[3].u() == MIDX_PITCHED_OFFSET_RECORD_TYPE
        ) {
            val raw = (payload[5].u() shl 8) or payload[6].u()
            return InlineOffsetPayload(payload[4].u(), decodeCentOffset(raw))
        }
        return null
    }

    private fun decodeCentOffset(raw: Int): Double {
        val sign = if ((raw and 0x8000) != 0) -1.0 else 1.0
        val magnitude = raw and 0x7FFF
        return sign * (magnitude / OFFSET_MAGNITUDE_STEPS * OFFSET_CENT_RANGE)
    }

    private fun skipSystemEvent(reader: ByteReader, status: Int) {
        val length = when (status) {
            0xF1 -> 1
            0xF2 -> 2
            0xF3 -> 1
            0xF6, 0xF8, 0xFA, 0xFB, 0xFC, 0xFE -> 0
            else -> 0
        }
        reader.read(length)
    }

    private fun umpPacketSize(mt: Int): Int {
        return when (mt) {
            0x0, 0x1, 0x2 -> 4
            0x3, 0x4 -> 8
            0x5, 0xD, 0xF -> 16
            else -> 4
        }
    }

    private fun readU32FromBytes(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].u().toLong() shl 24) or
            (bytes[offset + 1].u().toLong() shl 16) or
            (bytes[offset + 2].u().toLong() shl 8) or
            bytes[offset + 3].u().toLong()) and 0xFFFF_FFFFL
    }

    private fun scaleDownU32To7(value: Long): Int {
        return max(0, min(127, (value * 127.0 / 0xFFFF_FFFFL).roundToInt()))
    }

    private data class InlineOffset(
        val tick: Long,
        val pitch: Int,
        val cents: Double
    )

    private data class InlineOffsetPayload(
        val pitch: Int,
        val cents: Double
    )

    private class ByteReader(
        private val data: ByteArray,
        private val source: String
    ) {
        private var pos = 0

        fun remaining(): Int = data.size - pos

        fun read(count: Int): ByteArray {
            if (pos + count > data.size) {
                throw IllegalArgumentException("$source: unexpected end of file at byte $pos")
            }
            val out = data.copyOfRange(pos, pos + count)
            pos += count
            return out
        }

        fun readByte(): Int = read(1)[0].u()

        fun peekByte(): Int {
            if (remaining() <= 0) {
                throw IllegalArgumentException("$source: unexpected end of file")
            }
            return data[pos].u()
        }

        fun readU16(): Int {
            val b = read(2)
            return (b[0].u() shl 8) or b[1].u()
        }

        fun readU32(): Long {
            val b = read(4)
            return ((b[0].u().toLong() shl 24) or
                (b[1].u().toLong() shl 16) or
                (b[2].u().toLong() shl 8) or
                b[3].u().toLong()) and 0xFFFF_FFFFL
        }

        fun readAscii(count: Int): String {
            val bytes = read(count)
            return buildString(bytes.size) {
                bytes.forEach { append(it.u().toChar()) }
            }
        }

        fun readVlq(): Int {
            var value = 0
            repeat(4) {
                val b = readByte()
                value = (value shl 7) or (b and 0x7F)
                if (b < 0x80) {
                    return value
                }
            }
            throw IllegalArgumentException("$source: invalid variable-length quantity")
        }
    }
}

private fun Byte.u(): Int = toInt() and 0xFF
