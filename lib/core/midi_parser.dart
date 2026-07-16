import 'dart:math' as math;
import 'dart:typed_data';

import 'score.dart';

abstract final class MidiWaterfallParser {
  static const _defaultTempoUsPerQuarter = 500000.0;
  static const _midxMetaType = 0x7f;
  static const _midxPayloadLength = 7;
  static const _midxManufacturer = 0x7d;
  static const _midxRecordType = 0x03;

  static ParsedScore detectAndParse(Uint8List bytes, {required String title}) {
    if (bytes.length >= 8 &&
        String.fromCharCodes(bytes.take(8)) == 'SMF2CLIP') {
      return _parseMidi2Clip(bytes, title);
    }
    return _parseSmfMidx(bytes, title);
  }

  static List<TempoEvent> normalizeTempos(Iterable<TempoEvent> tempos) {
    final byTick = <int, TempoEvent>{};
    for (final tempo in tempos) {
      if (tempo.usPerQuarter.isFinite && tempo.usPerQuarter > 0) {
        byTick[tempo.tick] = tempo;
      }
    }
    final result = byTick.values.toList()
      ..sort((a, b) => a.tick.compareTo(b.tick));
    if (result.isEmpty || result.first.tick != 0) {
      result.insert(
        0,
        const TempoEvent(tick: 0, usPerQuarter: _defaultTempoUsPerQuarter),
      );
    }
    return List.unmodifiable(result);
  }

  static List<MeterEvent> normalizeMeters(Iterable<MeterEvent> meters) {
    final byTick = <int, MeterEvent>{};
    for (final meter in meters) {
      final tick = math.max(0, meter.tick);
      byTick[tick] = MeterEvent(
        tick: tick,
        numerator: math.max(1, meter.numerator),
        denominator: math.max(1, meter.denominator),
      );
    }
    final result = byTick.values.toList()
      ..sort((a, b) => a.tick.compareTo(b.tick));
    if (result.isEmpty || result.first.tick != 0) {
      result.insert(0, const MeterEvent(tick: 0, numerator: 4, denominator: 4));
    }
    return List.unmodifiable(result);
  }

  static List<TempoPoint> makeTempoMap(
    Iterable<TempoEvent> tempos,
    int ticksPerQuarter,
  ) {
    final normalized = normalizeTempos(tempos);
    final result = <TempoPoint>[];
    var currentSecond = 0.0;
    var previousTick = 0;
    var previousUs = _defaultTempoUsPerQuarter;
    for (final tempo in normalized) {
      currentSecond +=
          (tempo.tick - previousTick) * previousUs / 1000000 / ticksPerQuarter;
      result.add(
        TempoPoint(
          tick: tempo.tick,
          second: currentSecond,
          usPerQuarter: tempo.usPerQuarter,
        ),
      );
      previousTick = tempo.tick;
      previousUs = tempo.usPerQuarter;
    }
    return List.unmodifiable(result);
  }

  static double tickToSeconds(
    int tick,
    List<TempoPoint> tempoMap,
    int ticksPerQuarter,
  ) {
    var low = 0;
    var high = tempoMap.length - 1;
    while (low <= high) {
      final middle = (low + high) >> 1;
      if (tempoMap[middle].tick <= tick) {
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    final point = tempoMap[math.max(0, high)];
    return point.second +
        (tick - point.tick) * point.usPerQuarter / 1000000 / ticksPerQuarter;
  }

  static double secondsToTick(
    double second,
    List<TempoPoint> tempoMap,
    int ticksPerQuarter,
  ) {
    final safeSecond = math.max(0.0, second);
    var low = 0;
    var high = tempoMap.length - 1;
    while (low <= high) {
      final middle = (low + high) >> 1;
      if (tempoMap[middle].second <= safeSecond) {
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    final point = tempoMap[math.max(0, high)];
    return point.tick +
        (safeSecond - point.second) *
            1000000 *
            ticksPerQuarter /
            point.usPerQuarter;
  }

  static double measureTicks(MeterEvent meter, int ticksPerQuarter) {
    return math.max(
      1.0,
      ticksPerQuarter * 4.0 * meter.numerator / meter.denominator,
    );
  }

  static ParsedScore _parseSmfMidx(Uint8List bytes, String title) {
    final reader = _ByteReader(bytes, title);
    if (reader.readAscii(4) != 'MThd') {
      throw const FormatException('Not a MIDI/MIDX file: missing MThd');
    }
    final headerLength = reader.readU32();
    if (headerLength < 6) {
      throw const FormatException('Invalid MIDI header length');
    }
    final header = _ByteReader(reader.read(headerLength), 'MThd');
    final midiFormat = header.readU16();
    final trackCount = header.readU16();
    final division = header.readU16();
    if ((division & 0x8000) != 0) {
      throw const FormatException('SMPTE time division is not supported');
    }
    if (division <= 0) throw const FormatException('Invalid MIDI division');
    if (midiFormat != 0 && midiFormat != 1) {
      throw FormatException('Unsupported MIDI format $midiFormat');
    }

    final tempos = <TempoEvent>[
      const TempoEvent(tick: 0, usPerQuarter: _defaultTempoUsPerQuarter),
    ];
    final meters = <MeterEvent>[
      const MeterEvent(tick: 0, numerator: 4, denominator: 4),
    ];
    final raw = <RawNoteEvent>[];
    var order = 0;

    for (var track = 0; track < trackCount && reader.remaining > 0; track++) {
      if (reader.remaining < 8) {
        throw FormatException('$title: truncated track header');
      }
      final chunkType = reader.readAscii(4);
      final chunkLength = reader.readU32();
      final chunk = reader.read(chunkLength);
      if (chunkType != 'MTrk') continue;
      final trackReader = _ByteReader(chunk, 'MTrk[$track]');
      var tick = 0;
      int? runningStatus;
      final programs = <int, int>{};
      final bankMsb = <int, int>{};
      final bankLsb = <int, int>{};
      final inlineOffsets = <_InlineOffset>[];

      while (trackReader.remaining > 0) {
        tick += trackReader.readVlq();
        final statusOrData = trackReader.readByte();
        if (statusOrData == 0xff) {
          final metaType = trackReader.readByte();
          final payloadLength = trackReader.readVlq();
          final payload = trackReader.read(payloadLength);
          if (metaType == 0x2f) break;
          if (metaType == 0x51 && payloadLength == 3) {
            final usPerQuarter =
                (payload[0] << 16) | (payload[1] << 8) | payload[2];
            tempos.add(
              TempoEvent(tick: tick, usPerQuarter: usPerQuarter.toDouble()),
            );
          } else if (metaType == 0x58 && payloadLength >= 2) {
            final exponent = payload[1].clamp(0, 30);
            meters.add(
              MeterEvent(
                tick: tick,
                numerator: payload[0],
                denominator: 1 << exponent,
              ),
            );
          } else if (metaType == _midxMetaType &&
              payloadLength == _midxPayloadLength) {
            final decoded = _decodeInlineOffset(payload);
            if (decoded != null) {
              if (inlineOffsets.isNotEmpty && inlineOffsets.last.tick != tick) {
                inlineOffsets.clear();
              }
              inlineOffsets.add(
                _InlineOffset(tick: tick, pitch: decoded.$1, cents: decoded.$2),
              );
            } else {
              inlineOffsets.clear();
            }
          } else {
            inlineOffsets.clear();
          }
          continue;
        }

        if (statusOrData == 0xf0 || statusOrData == 0xf7) {
          trackReader.skip(trackReader.readVlq());
          runningStatus = null;
          inlineOffsets.clear();
          continue;
        }
        if (statusOrData >= 0xf0) {
          trackReader.skip(_systemDataLength(statusOrData));
          runningStatus = null;
          inlineOffsets.clear();
          continue;
        }

        late final int status;
        int? firstData;
        if ((statusOrData & 0x80) != 0) {
          status = statusOrData;
          runningStatus = status;
        } else {
          status =
              runningStatus ??
              (throw const FormatException(
                'Running status without a previous channel message',
              ));
          firstData = statusOrData;
        }
        final eventType = status & 0xf0;
        final channel = status & 0x0f;
        if (eventType == 0xc0 || eventType == 0xd0) {
          final data = firstData ?? trackReader.readByte();
          if (eventType == 0xc0) programs[channel] = data & 0x7f;
          inlineOffsets.clear();
          continue;
        }

        final data1 = (firstData ?? trackReader.readByte()) & 0x7f;
        final data2 = trackReader.readByte() & 0x7f;
        if (eventType == 0xb0) {
          if (data1 == 0) bankMsb[channel] = data2;
          if (data1 == 32) bankLsb[channel] = data2;
        }
        if (eventType == 0x80 || eventType == 0x90) {
          final velocity = eventType == 0x90 ? data2 : 0;
          var effectivePitch = data1;
          var cents = 0.0;
          if (velocity > 0) {
            if (inlineOffsets.isNotEmpty && inlineOffsets.last.tick != tick) {
              inlineOffsets.clear();
            }
            final inline = _popInline(inlineOffsets, data1, tick);
            if (inline != null) {
              effectivePitch = inline.pitch;
              cents = inline.cents;
            }
          } else {
            inlineOffsets.clear();
          }
          raw.add(
            RawNoteEvent(
              tick: tick,
              pitch: effectivePitch,
              midiPitch: data1,
              cents: cents,
              velocity: velocity,
              track: track,
              channel: channel,
              program: programs[channel] ?? 0,
              bankMsb: bankMsb[channel] ?? 0,
              bankLsb: bankLsb[channel] ?? 0,
              order: order++,
            ),
          );
        } else {
          inlineOffsets.clear();
        }
      }
    }
    return _finalize(
      title: title,
      format: 'MIDX',
      ticksPerQuarter: division,
      tempos: tempos,
      meters: meters,
      rawEvents: raw,
    );
  }

  static ParsedScore _parseMidi2Clip(Uint8List bytes, String title) {
    final reader = _ByteReader(bytes, title);
    if (reader.readAscii(8) != 'SMF2CLIP') {
      throw const FormatException('Not a MIDI 2.0 Clip file');
    }
    var ticksPerQuarter = 480;
    var tick = 0;
    final tempos = <TempoEvent>[
      const TempoEvent(tick: 0, usPerQuarter: _defaultTempoUsPerQuarter),
    ];
    final meters = <MeterEvent>[
      const MeterEvent(tick: 0, numerator: 4, denominator: 4),
    ];
    final raw = <RawNoteEvent>[];
    final programs = <int, int>{};
    final bankMsb = <int, int>{};
    final bankLsb = <int, int>{};
    var order = 0;

    while (reader.remaining > 0) {
      final messageType = reader.peekByte() >> 4;
      final packetSize = switch (messageType) {
        0x0 || 0x1 || 0x2 => 4,
        0x3 || 0x4 => 8,
        0x5 || 0xd || 0xf => 16,
        _ => 4,
      };
      if (reader.remaining < packetSize) break;
      final packet = reader.read(packetSize);
      if (messageType == 0x0) {
        final utilityStatus = (packet[1] >> 4) & 0x0f;
        if (utilityStatus == 0x3) {
          final value = (packet[2] << 8) | packet[3];
          if (value > 0) ticksPerQuarter = value;
        } else if (utilityStatus == 0x4) {
          tick += ((packet[1] & 0x0f) << 16) | (packet[2] << 8) | packet[3];
        }
        continue;
      }
      if (messageType == 0xd && packet.length >= 16) {
        if (packet[1] == 0x10 && packet[2] == 0 && packet[3] == 0) {
          final tenNanoseconds = _readU32(packet, 4);
          if (tenNanoseconds > 0) {
            tempos.add(
              TempoEvent(tick: tick, usPerQuarter: tenNanoseconds / 100),
            );
          }
        }
        continue;
      }
      if (messageType != 0x4 || packet.length < 8) continue;

      final status = packet[1];
      final eventType = status & 0xf0;
      final channel = status & 0x0f;
      final note = packet[2] & 0x7f;
      final attributeType = packet[3];
      final velocity16 = (packet[4] << 8) | packet[5];
      final attribute = (packet[6] << 8) | packet[7];
      if (eventType == 0xb0) {
        final controller = note;
        final value = _scaleU32To7(_readU32(packet, 4));
        if (controller == 0) bankMsb[channel] = value;
        if (controller == 32) bankLsb[channel] = value;
        continue;
      }
      if (eventType == 0xc0) {
        programs[channel] = packet[4] & 0x7f;
        if ((packet[3] & 0x01) != 0) {
          bankMsb[channel] = packet[6] & 0x7f;
          bankLsb[channel] = packet[7] & 0x7f;
        }
        continue;
      }
      if (eventType != 0x80 && eventType != 0x90) continue;
      final pitchFloat = eventType == 0x90 && attributeType == 0x03
          ? attribute / 512
          : note.toDouble();
      final velocity = eventType == 0x90 && velocity16 > 0
          ? math.max(1, (velocity16 / 65535 * 127).round())
          : 0;
      final pitchFloor = pitchFloat.floor();
      raw.add(
        RawNoteEvent(
          tick: tick,
          pitch: pitchFloor,
          pitchFloat: pitchFloat,
          midiPitch: note,
          cents: (pitchFloat - pitchFloor) * 100,
          velocity: velocity,
          track: 0,
          channel: channel,
          program: programs[channel] ?? 0,
          bankMsb: bankMsb[channel] ?? 0,
          bankLsb: bankLsb[channel] ?? 0,
          order: order++,
        ),
      );
    }
    return _finalize(
      title: title,
      format: 'MIDI 2.0 Clip',
      ticksPerQuarter: ticksPerQuarter,
      tempos: tempos,
      meters: meters,
      rawEvents: raw,
    );
  }

  static ParsedScore _finalize({
    required String title,
    required String format,
    required int ticksPerQuarter,
    required List<TempoEvent> tempos,
    required List<MeterEvent> meters,
    required List<RawNoteEvent> rawEvents,
  }) {
    if (rawEvents.isEmpty) throw const FormatException('No note events found');
    final normalizedTempos = normalizeTempos(tempos);
    final normalizedMeters = normalizeMeters(meters);
    final tempoMap = makeTempoMap(normalizedTempos, ticksPerQuarter);
    final notes = _pairNotes(rawEvents, tempoMap, ticksPerQuarter);
    if (notes.isEmpty) throw const FormatException('No playable notes found');
    final longNotes = notes
        .where((note) => note.duration > 8)
        .toList(growable: false);
    final duration = notes.fold(
      0.0,
      (value, note) => math.max(value, note.end),
    );
    return ParsedScore(
      title: title,
      format: format,
      ticksPerQuarter: ticksPerQuarter,
      tempos: normalizedTempos,
      meters: normalizedMeters,
      tempoMap: tempoMap,
      rawEvents: List.unmodifiable(rawEvents),
      notes: List.unmodifiable(notes),
      longNotes: List.unmodifiable(longNotes),
      duration: duration,
    );
  }

  static List<WaterfallNote> _pairNotes(
    List<RawNoteEvent> rawEvents,
    List<TempoPoint> tempoMap,
    int ticksPerQuarter,
  ) {
    final sorted = List<RawNoteEvent>.from(rawEvents)
      ..sort((a, b) {
        final tickOrder = a.tick.compareTo(b.tick);
        if (tickOrder != 0) return tickOrder;
        final aOff = a.velocity == 0;
        final bOff = b.velocity == 0;
        if (aOff != bOff) return aOff ? -1 : 1;
        return a.order.compareTo(b.order);
      });
    final active = <String, List<RawNoteEvent>>{};
    final notes = <WaterfallNote>[];
    for (final event in sorted) {
      final key = '${event.track}:${event.channel}:${event.midiPitch}';
      if (event.velocity > 0) {
        (active[key] ??= []).add(event);
      } else {
        final queue = active[key];
        if (queue == null || queue.isEmpty) continue;
        final start = queue.removeAt(0);
        notes.add(
          _makeNote(
            start,
            math.max(event.tick, start.tick),
            tempoMap,
            ticksPerQuarter,
          ),
        );
      }
    }
    for (final queue in active.values) {
      for (final start in queue) {
        notes.add(
          _makeNote(
            start,
            start.tick + ticksPerQuarter,
            tempoMap,
            ticksPerQuarter,
          ),
        );
      }
    }
    notes.sort((a, b) {
      final time = a.start.compareTo(b.start);
      return time != 0 ? time : a.pitch.compareTo(b.pitch);
    });
    return notes
        .where((note) => note.pitch >= 20 && note.pitch <= 109)
        .toList();
  }

  static WaterfallNote _makeNote(
    RawNoteEvent start,
    int endTick,
    List<TempoPoint> tempoMap,
    int ticksPerQuarter,
  ) {
    final pitch = start.pitchFloat ?? start.pitch + start.cents / 100;
    return WaterfallNote(
      startTick: start.tick,
      endTick: endTick,
      start: tickToSeconds(start.tick, tempoMap, ticksPerQuarter),
      end: tickToSeconds(endTick, tempoMap, ticksPerQuarter),
      pitch: pitch,
      midiPitch: start.midiPitch,
      cents: (pitch - pitch.round()) * 100,
      velocity: start.velocity,
      channel: start.channel,
      track: start.track,
      program: start.program,
      bankMsb: start.bankMsb,
      bankLsb: start.bankLsb,
    );
  }

  static _InlineOffset? _popInline(
    List<_InlineOffset> offsets,
    int midiPitch,
    int tick,
  ) {
    final exact = offsets.indexWhere(
      (offset) => offset.tick == tick && offset.pitch == midiPitch,
    );
    if (exact >= 0) return offsets.removeAt(exact);
    final matches = <int>[];
    for (var index = 0; index < offsets.length; index++) {
      if (offsets[index].tick == tick) matches.add(index);
    }
    return matches.length == 1 ? offsets.removeAt(matches.single) : null;
  }

  static (int, double)? _decodeInlineOffset(Uint8List payload) {
    if (payload.length != _midxPayloadLength ||
        payload[0] != _midxManufacturer ||
        payload[1] != 0x58 ||
        payload[2] != 0x54 ||
        payload[3] != _midxRecordType) {
      return null;
    }
    final raw = (payload[5] << 8) | payload[6];
    final sign = (raw & 0x8000) == 0 ? 1.0 : -1.0;
    final magnitude = raw & 0x7fff;
    return (payload[4], sign * magnitude / 32768 * 64);
  }

  static int _systemDataLength(int status) => switch (status) {
    0xf1 || 0xf3 => 1,
    0xf2 => 2,
    _ => 0,
  };

  static int _readU32(Uint8List bytes, int offset) {
    return (bytes[offset] << 24) |
        (bytes[offset + 1] << 16) |
        (bytes[offset + 2] << 8) |
        bytes[offset + 3];
  }

  static int _scaleU32To7(int value) {
    return (value * 127 / 0xffffffff).round().clamp(0, 127);
  }
}

class _InlineOffset {
  const _InlineOffset({
    required this.tick,
    required this.pitch,
    required this.cents,
  });

  final int tick;
  final int pitch;
  final double cents;
}

class _ByteReader {
  _ByteReader(this.data, this.source);

  final Uint8List data;
  final String source;
  int _position = 0;

  int get remaining => data.length - _position;

  Uint8List read(int count) {
    if (count < 0 || _position + count > data.length) {
      throw FormatException(
        '$source: unexpected end of file at byte $_position',
      );
    }
    final result = Uint8List.sublistView(data, _position, _position + count);
    _position += count;
    return result;
  }

  void skip(int count) => read(count);

  int readByte() => read(1)[0];

  int peekByte() {
    if (remaining <= 0) {
      throw FormatException('$source: unexpected end of file');
    }
    return data[_position];
  }

  int readU16() {
    final bytes = read(2);
    return (bytes[0] << 8) | bytes[1];
  }

  int readU32() {
    final bytes = read(4);
    return (bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3];
  }

  String readAscii(int count) => String.fromCharCodes(read(count));

  int readVlq() {
    var value = 0;
    for (var index = 0; index < 4; index++) {
      final byte = readByte();
      value = (value << 7) | (byte & 0x7f);
      if (byte < 0x80) return value;
    }
    throw FormatException('$source: invalid variable-length quantity');
  }
}
