import 'dart:convert';
import 'dart:math' as math;

enum TuningType { octave, full }

class TuningMark {
  const TuningMark({required this.cents, required this.ratio});

  final double cents;
  final double ratio;
}

class TuningDefinition {
  const TuningDefinition({
    required this.profile,
    required this.type,
    required this.displayOffsetCents,
    required this.referencePitch,
    required this.marks,
    required this.keybind,
  });

  static const standard = TuningDefinition(
    profile: 'TUN',
    type: TuningType.octave,
    displayOffsetCents: 0,
    referencePitch: 60,
    marks: [TuningMark(cents: 0, ratio: 1)],
    keybind: {},
  );

  final String profile;
  final TuningType type;
  final double displayOffsetCents;
  final double referencePitch;
  final List<TuningMark> marks;
  final Map<int, double> keybind;

  factory TuningDefinition.fromJson(String source) {
    final decoded = jsonDecode(source);
    if (decoded is! Map) {
      throw const FormatException('Tuning JSON must contain an object');
    }
    final root = decoded.map((key, value) => MapEntry(key.toString(), value));
    final rawType = (root['type'] ?? root['Type'])
        ?.toString()
        .trim()
        .toLowerCase();
    final type = rawType == 'full' ? TuningType.full : TuningType.octave;
    final profile = _normalizeProfile(root['profile']?.toString());
    final scaleValue = root['Scale'] ?? root['scale'];
    if (scaleValue is! Map) {
      throw const FormatException('Tuning JSON requires a Scale object');
    }
    final referencePitch = type == TuningType.full
        ? _parseFullReference(root['offset'] ?? root['Offset'])
        : 60.0;
    final displayOffset = type == TuningType.octave
        ? _parseOctaveOffset(root['offset'] ?? root['Offset'])
        : 0.0;
    final marks = <TuningMark>[];
    for (final entry in scaleValue.entries) {
      final cents = double.tryParse(entry.key.toString().trim());
      final ratio = _number(entry.value);
      if (cents == null ||
          !cents.isFinite ||
          ratio == null ||
          !ratio.isFinite) {
        throw FormatException(
          'Invalid Scale entry ${entry.key}: ${entry.value}',
        );
      }
      if (ratio < 0 || ratio > 1) {
        throw FormatException(
          'Scale ratio must be within 0..1: ${entry.value}',
        );
      }
      if (type == TuningType.octave) {
        if (cents <= 0 || cents >= 1200) continue;
      } else {
        final pitch = referencePitch + cents / 100;
        if (pitch < 0 || pitch > 127) continue;
      }
      marks.add(TuningMark(cents: cents, ratio: ratio));
    }
    if (!marks.any((mark) => mark.cents.abs() < 0.000001)) {
      marks.add(const TuningMark(cents: 0, ratio: 1));
    }
    marks.sort((a, b) => a.cents.compareTo(b.cents));
    final keybind = _parseKeybind(
      root['Keybind'] ?? root['keybind'],
      type: type,
      referencePitch: referencePitch,
    );
    return TuningDefinition(
      profile: profile,
      type: type,
      displayOffsetCents: displayOffset,
      referencePitch: referencePitch,
      marks: List.unmodifiable(marks),
      keybind: Map.unmodifiable(keybind),
    );
  }

  double mapMidiPitch(int midiKey, {int? edo}) {
    final key = midiKey.clamp(0, 127);
    if (keybind.isNotEmpty) {
      if (type == TuningType.full) {
        final cents = keybind[key];
        if (cents != null) return (referencePitch + cents / 100).clamp(0, 127);
      } else {
        final pitchClass = key % 12;
        final cents = keybind[pitchClass];
        if (cents != null) {
          return (key - pitchClass + cents / 100).clamp(0, 127);
        }
      }
    }
    final divisions = edo ?? 0;
    if (divisions > 0 && divisions != 12) {
      final step = ((key - 60) * divisions / 12).round();
      return (60 + step * 12 / divisions).clamp(0, 127);
    }
    return key.toDouble();
  }

  List<double> visiblePitches({double minPitch = 0, double maxPitch = 127}) {
    if (type == TuningType.full) {
      return [
        for (final mark in marks)
          if (referencePitch + mark.cents / 100 >= minPitch &&
              referencePitch + mark.cents / 100 <= maxPitch)
            referencePitch + mark.cents / 100,
      ];
    }
    final result = <double>[];
    final firstOctave = (minPitch / 12).floor() - 1;
    final lastOctave = (maxPitch / 12).ceil() + 1;
    for (var octave = firstOctave; octave <= lastOctave; octave++) {
      for (final mark in marks) {
        final pitch = octave * 12 + mark.cents / 100;
        if (pitch >= minPitch && pitch <= maxPitch) result.add(pitch);
      }
    }
    return result..sort();
  }

  static Map<int, double> _parseKeybind(
    Object? value, {
    required TuningType type,
    required double referencePitch,
  }) {
    if (value == null) return const {};
    if (value is! Map) throw const FormatException('Keybind must be an object');
    final result = <int, double>{};
    for (final entry in value.entries) {
      final key = int.tryParse(entry.key.toString().trim());
      final cents = _number(entry.value);
      if (key == null || cents == null || !cents.isFinite) {
        throw FormatException(
          'Invalid Keybind entry ${entry.key}: ${entry.value}',
        );
      }
      if (type == TuningType.octave) {
        if (key < 0 || key > 11 || cents < 0 || cents >= 1200) continue;
      } else {
        if (key < 0 || key > 127) continue;
        final target = referencePitch + cents / 100;
        if (target < 0 || target > 127) continue;
      }
      result[key] = cents;
    }
    return result;
  }

  static double _parseOctaveOffset(Object? value) {
    if (value == null) return 0;
    if (value is num) return _finite(value.toDouble(), 'offset');
    var text = value.toString().trim();
    if (text.isEmpty) return 0;
    text = text.replaceFirst(RegExp(r'[cC]$'), '').trim();
    final parsed = double.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Tuning offset is not cents');
    }
    return _finite(parsed, 'offset');
  }

  static double _parseFullReference(Object? value) {
    if (value == null || value.toString().trim().isEmpty) return 60;
    if (value is num) return _frequencyToMidi(value.toDouble());
    final text = value.toString().trim();
    final note = RegExp(
      r'^([A-Ga-g])([#b]?)(-?\d+)(?:([+-]\d+(?:\.\d+)?)(?:\(c\)|[cC])?)?$',
    ).firstMatch(text);
    if (note != null) {
      const pitchClass = <String, int>{
        'C': 0,
        'D': 2,
        'E': 4,
        'F': 5,
        'G': 7,
        'A': 9,
        'B': 11,
      };
      var semitone = pitchClass[note.group(1)!.toUpperCase()]!;
      if (note.group(2) == '#') semitone++;
      if (note.group(2) == 'b') semitone--;
      final octave = int.parse(note.group(3)!);
      final cents = double.tryParse(note.group(4) ?? '') ?? 0;
      return _finite(
        (octave + 1) * 12 + semitone + cents / 100,
        'offset',
      ).clamp(0, 127);
    }
    final hzMatch = RegExp(
      r'^([+-]?\d+(?:\.\d+)?)\s*[hH][zZ]$',
    ).firstMatch(text);
    if (hzMatch != null) {
      return _frequencyToMidi(double.parse(hzMatch.group(1)!));
    }
    final centsMatch = RegExp(
      r'^([+-]?\d+(?:\.\d+)?)\s*[cC]$',
    ).firstMatch(text);
    if (centsMatch != null) {
      final cents = double.parse(centsMatch.group(1)!);
      if (cents < 0) {
        throw const FormatException('Absolute cents cannot be negative');
      }
      return (cents / 100).clamp(0, 127);
    }
    final frequency = double.tryParse(text);
    if (frequency != null) return _frequencyToMidi(frequency);
    throw FormatException('Unsupported full tuning offset: $text');
  }

  static double _frequencyToMidi(double frequency) {
    if (!frequency.isFinite || frequency <= 0) {
      throw const FormatException('Reference frequency must be positive');
    }
    return (69 + 12 * (math.log(frequency / 440) / math.ln2)).clamp(0, 127);
  }

  static double? _number(Object? value) {
    if (value is num) return value.toDouble();
    return double.tryParse(value?.toString().trim() ?? '');
  }

  static double _finite(double value, String label) {
    if (!value.isFinite) throw FormatException('$label must be finite');
    return value;
  }

  static String _normalizeProfile(String? value) {
    final printable = (value ?? '').runes
        .where((rune) => rune >= 0x20 && rune <= 0x7e)
        .map(String.fromCharCode)
        .join()
        .trim();
    return printable.isEmpty ? 'TUN' : printable;
  }
}
