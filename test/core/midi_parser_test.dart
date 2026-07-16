import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/midi_parser.dart';
import 'package:xensynth/core/score.dart';

void main() {
  group('MidiWaterfallParser', () {
    test('parses SMF running status and inline MIDX cents', () {
      final score = MidiWaterfallParser.detectAndParse(
        _midxFixture(),
        title: 'fixture.midx',
      );

      expect(score.ticksPerQuarter, 480);
      expect(score.notes, hasLength(1));
      expect(score.notes.single.pitch, closeTo(60.32, 0.0001));
      expect(score.notes.single.program, 5);
      expect(score.notes.single.start, closeTo(0, 0.0001));
      expect(score.notes.single.end, closeTo(0.5, 0.0001));
    });

    test('parses a MIDI 2.0 Clip note pair', () {
      final bytes = Uint8List.fromList([
        ...'SMF2CLIP'.codeUnits,
        0x00,
        0x30,
        0x01,
        0xe0,
        0x40,
        0x90,
        60,
        0,
        0xff,
        0xff,
        0,
        0,
        0x00,
        0x40,
        0x01,
        0xe0,
        0x40,
        0x80,
        60,
        0,
        0,
        0,
        0,
        0,
      ]);

      final score = MidiWaterfallParser.detectAndParse(
        bytes,
        title: 'clip.midi2',
      );

      expect(score.format, 'MIDI 2.0 Clip');
      expect(score.notes, hasLength(1));
      expect(score.notes.single.pitch, 60);
      expect(score.notes.single.end, closeTo(0.5, 0.0001));
    });

    test('parses the bundled 26 EDO demo', () {
      final bytes = File('assets/scores/demo_26edo.midx').readAsBytesSync();
      final score = MidiWaterfallParser.detectAndParse(
        bytes,
        title: 'demo_26edo.midx',
      );

      expect(score.notes.length, greaterThan(20));
      expect(score.duration, greaterThan(1));
      expect(score.notes.any((note) => note.cents.abs() > 0.01), isTrue);
    });

    test('rejects unknown data', () {
      expect(
        () => MidiWaterfallParser.detectAndParse(
          Uint8List.fromList([1, 2, 3]),
          title: 'bad.bin',
        ),
        throwsFormatException,
      );
    });

    test('converts seconds back to ticks and computes measure length', () {
      const tempoMap = <TempoPoint>[
        TempoPoint(tick: 0, second: 0, usPerQuarter: 500000),
        TempoPoint(tick: 960, second: 1, usPerQuarter: 1000000),
      ];

      expect(MidiWaterfallParser.secondsToTick(0.5, tempoMap, 480), 480);
      expect(MidiWaterfallParser.secondsToTick(2, tempoMap, 480), 1440);
      expect(
        MidiWaterfallParser.measureTicks(
          const MeterEvent(tick: 0, numerator: 3, denominator: 4),
          480,
        ),
        1440,
      );
    });
  });
}

Uint8List _midxFixture() {
  final track = <int>[
    0x00,
    0xff,
    0x51,
    0x03,
    0x07,
    0xa1,
    0x20,
    0x00,
    0xc0,
    0x05,
    0x00,
    0xff,
    0x7f,
    0x07,
    0x7d,
    0x58,
    0x54,
    0x03,
    0x3c,
    0x40,
    0x00,
    0x00,
    0x90,
    0x3c,
    0x64,
    0x83,
    0x60,
    0x3c,
    0x00,
    0x00,
    0xff,
    0x2f,
    0x00,
  ];
  return Uint8List.fromList([
    ...'MThd'.codeUnits,
    0x00,
    0x00,
    0x00,
    0x06,
    0x00,
    0x00,
    0x00,
    0x01,
    0x01,
    0xe0,
    ...'MTrk'.codeUnits,
    ..._u32(track.length),
    ...track,
  ]);
}

List<int> _u32(int value) => [
  (value >> 24) & 0xff,
  (value >> 16) & 0xff,
  (value >> 8) & 0xff,
  value & 0xff,
];
