import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/tuning.dart';

void main() {
  group('TuningDefinition', () {
    test('parses octave marks, offset and repeating keybind', () {
      final tuning = TuningDefinition.fromJson('''
      {
        "profile": "JUST",
        "offset": "+30c",
        "Scale": {"203.91": 0.7, "701.96": 0.9},
        "Keybind": {"0": 0, "2": 203.91, "7": 701.96}
      }
      ''');

      expect(tuning.profile, 'JUST');
      expect(tuning.displayOffsetCents, 30);
      expect(tuning.mapMidiPitch(62), closeTo(62.0391, 0.00001));
      expect(tuning.mapMidiPitch(74), closeTo(74.0391, 0.00001));
    });

    test('parses full tuning reference notes and keybind', () {
      final tuning = TuningDefinition.fromJson('''
      {
        "profile": "FULL",
        "type": "full",
        "offset": "C#4+29.1c",
        "Scale": {"0": 1, "386.31": 0.7},
        "Keybind": {"60": 0, "64": 386.31}
      }
      ''');

      expect(tuning.referencePitch, closeTo(61.291, 0.00001));
      expect(tuning.mapMidiPitch(64), closeTo(65.1541, 0.00001));
      expect(tuning.displayOffsetCents, 0);
    });

    test('converts full tuning frequency to MIDI pitch', () {
      final tuning = TuningDefinition.fromJson('''
      {"type":"full","offset":"440Hz","Scale":{"0":1}}
      ''');
      expect(tuning.referencePitch, closeTo(69, 0.00001));
    });
  });
}
