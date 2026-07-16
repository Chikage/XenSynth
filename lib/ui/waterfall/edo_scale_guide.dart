import 'dart:math' as math;

import '../../core/tuning.dart';

/// One visible line in the built-in EDO scale guide.
class EdoScaleLine {
  const EdoScaleLine({
    required this.pitch,
    required this.ratio,
    required this.isAnchor,
    this.label,
  });

  final double pitch;
  final double ratio;
  final bool isAnchor;
  final String? label;
}

class EdoImpactTickStyle {
  const EdoImpactTickStyle({
    required this.length,
    required this.alpha,
    required this.strokeWidth,
    required this.isAnchor,
  });

  final double length;
  final int alpha;
  final double strokeWidth;
  final bool isAnchor;
}

/// Smoothly thins dense EDO lines without letting octave anchors disappear.
abstract final class DenseLineVisibility {
  static const _epsilon = 0.0001;
  static const _minimumVisibleRatio = 0.02;

  static double ratioForStep({
    required int stepIndex,
    required double step,
    required double minimumPitchSpacing,
    bool isAnchor = false,
  }) {
    if (isAnchor) return 1;
    if (step <= _epsilon || minimumPitchSpacing <= _epsilon) return 1;
    final desiredStride = minimumPitchSpacing / step;
    if (!desiredStride.isFinite || desiredStride <= 1) return 1;

    final fineStride = math.max(1, desiredStride.floor());
    final coarseStride = math.max(1, desiredStride.ceil());
    if (fineStride == coarseStride) {
      return _positiveModulo(stepIndex, coarseStride) == 0 ? 1 : 0;
    }

    final fineWeight = _smoothStep(
      (coarseStride - desiredStride).clamp(0.0, 1.0),
    );
    final coarseWeight = 1 - fineWeight;
    var ratio = 0.0;
    if (_positiveModulo(stepIndex, fineStride) == 0) {
      ratio = math.max(ratio, fineWeight);
    }
    if (_positiveModulo(stepIndex, coarseStride) == 0) {
      ratio = math.max(ratio, coarseWeight);
    }
    return ratio >= _minimumVisibleRatio ? ratio : 0;
  }

  static double _smoothStep(double value) => value * value * (3 - 2 * value);

  static int _positiveModulo(int value, int modulus) {
    final result = value % modulus;
    return result < 0 ? result + modulus : result;
  }
}

/// Built-in 0–72 EDO ruler patterns shared with the Android implementation.
abstract final class EdoScaleGuide {
  static const _octaveSemitones = 12.0;
  static const _stepEpsilon = 0.0001;
  static const _tickHeightRatio = 0.84;
  static const _tickAlpha = 184;
  static const _anchorTickStrokeWidth = 1.4;
  static const _minorTickStrokeWidth = 1.0;
  static const _hiddenMark = 'N';
  static const _muteTouchMark = 'S';
  static const _markRatios = <String, double>{
    '0': 1,
    '1': 0.8,
    '2': 0.6,
    '3': 0.4,
    '4': 0.2,
    _hiddenMark: 0,
    _muteTouchMark: 0,
  };

  static final Map<int, String> _patterns = _buildPatterns();
  static const _naturalPitchClasses = <int>{0, 2, 4, 5, 7, 9, 11};

  static bool hasScale(int edo) => _patterns.containsKey(math.max(0, edo));

  static EdoImpactTickStyle impactTickForPitch({
    required int edo,
    required double pitch,
    required double keyHeight,
    TuningDefinition? tuning,
  }) {
    if (tuning != null) {
      final ratio = _customImpactRatio(tuning, pitch);
      if (ratio != null && ratio > 0) {
        final safeRatio = ratio.clamp(0.0, 1.0);
        return EdoImpactTickStyle(
          length: keyHeight * _tickHeightRatio * safeRatio,
          alpha: (_tickAlpha * safeRatio).round().clamp(0, _tickAlpha),
          strokeWidth: _anchorTickStrokeWidth * safeRatio,
          isAnchor: _isCustomAnchor(tuning, pitch),
        );
      }
      return _legacyImpactTickForPitch(pitch, keyHeight);
    }

    final normalizedEdo = math.max(0, edo);
    final pattern = _patterns[normalizedEdo];
    if (pattern == null || pattern.isEmpty) {
      return _legacyImpactTickForPitch(pitch, keyHeight);
    }
    final stepCount = normalizedEdo > 0 ? normalizedEdo : 1;
    final step = _octaveSemitones / stepCount;
    final nearestStep = (pitch / step + 0.5).floor();
    final snappedPitch = nearestStep * step;
    if ((pitch - snappedPitch).abs() > _stepEpsilon) {
      return _legacyImpactTickForPitch(pitch, keyHeight);
    }
    final octaveStep = _positiveModulo(nearestStep, stepCount);
    final marker = octaveStep < pattern.length
        ? pattern[octaveStep]
        : _hiddenMark;
    final ratio = _markRatios[marker] ?? 0;
    if (ratio <= 0) return _legacyImpactTickForPitch(pitch, keyHeight);
    final safeRatio = ratio.clamp(0.0, 1.0);
    final isAnchor = octaveStep == 0;
    return EdoImpactTickStyle(
      length: keyHeight * _tickHeightRatio * safeRatio,
      alpha: (_tickAlpha * safeRatio).round().clamp(0, _tickAlpha),
      strokeWidth: isAnchor ? _anchorTickStrokeWidth : _minorTickStrokeWidth,
      isAnchor: isAnchor,
    );
  }

  static Iterable<EdoScaleLine> linesForRange({
    required int edo,
    required double minimumPitch,
    required double maximumPitch,
    double minimumPitchSpacing = 0,
  }) sync* {
    final normalizedEdo = math.max(0, edo);
    final pattern = _patterns[normalizedEdo];
    if (pattern == null || pattern.isEmpty || maximumPitch < minimumPitch) {
      return;
    }
    final stepCount = normalizedEdo > 0 ? normalizedEdo : 1;
    final step = _octaveSemitones / stepCount;
    final firstStep = (minimumPitch / step).floor() - 1;
    final lastStep = (maximumPitch / step).floor() + 1;
    for (var stepIndex = firstStep; stepIndex <= lastStep; stepIndex++) {
      final pitch = stepIndex * step;
      if (pitch < minimumPitch - step || pitch > maximumPitch + step) {
        continue;
      }
      final octaveStep = _positiveModulo(stepIndex, stepCount);
      final marker = octaveStep < pattern.length
          ? pattern[octaveStep]
          : _hiddenMark;
      final markRatio = _markRatios[marker] ?? 0;
      if (markRatio <= 0) continue;
      final isAnchor = octaveStep == 0;
      final visibilityRatio = DenseLineVisibility.ratioForStep(
        stepIndex: stepIndex,
        step: step,
        minimumPitchSpacing: minimumPitchSpacing,
        isAnchor: isAnchor,
      );
      final ratio = markRatio * visibilityRatio;
      if (ratio <= 0) continue;
      yield EdoScaleLine(
        pitch: pitch,
        ratio: ratio.clamp(0.0, 1.0),
        isAnchor: isAnchor,
        label: isAnchor && (pitch - 60).abs() <= 0.0001 ? 'C4' : null,
      );
    }
  }

  static Iterable<EdoScaleLine> customLinesForRange({
    required TuningDefinition tuning,
    required double minimumPitch,
    required double maximumPitch,
    double minimumPitchSpacing = 0,
  }) sync* {
    if (maximumPitch < minimumPitch) return;
    final spacing = minimumPitchSpacing.isFinite
        ? math.max(0.0, minimumPitchSpacing)
        : 0.0;
    var lastDrawnPitch = double.negativeInfinity;

    if (tuning.type == TuningType.full) {
      for (final mark in tuning.marks) {
        final pitch = tuning.referencePitch + mark.cents / 100;
        if (pitch < 0 || pitch > 127) continue;
        if (pitch < minimumPitch - 12 || pitch > maximumPitch + 12) continue;
        final ratio = mark.ratio.clamp(0.0, 1.0);
        if (ratio <= 0) continue;
        final isAnchor = mark.cents.abs() <= 0.0001;
        if (!isAnchor && pitch - lastDrawnPitch < spacing) continue;
        yield EdoScaleLine(
          pitch: pitch,
          ratio: ratio,
          isAnchor: isAnchor,
          label: isAnchor ? 'O' : null,
        );
        lastDrawnPitch = pitch;
      }
      return;
    }

    final firstOctave = (minimumPitch / 12).floor() - 1;
    final lastOctave = (maximumPitch / 12).floor() + 1;
    for (var octave = firstOctave; octave <= lastOctave; octave++) {
      final basePitch = octave * 12.0;
      for (final mark in tuning.marks) {
        final pitch = basePitch + mark.cents / 100;
        if (pitch < minimumPitch - 12 || pitch > maximumPitch + 12) continue;
        final ratio = mark.ratio.clamp(0.0, 1.0);
        if (ratio <= 0) continue;
        final isAnchor = mark.cents.abs() <= 0.0001;
        if (!isAnchor && pitch - lastDrawnPitch < spacing) continue;
        yield EdoScaleLine(
          pitch: pitch,
          ratio: ratio,
          isAnchor: isAnchor,
          label: isAnchor && (pitch - 60).abs() <= 0.0001 ? 'C4' : null,
        );
        lastDrawnPitch = pitch;
      }
    }
  }

  static double snapPitch(int edo, double rawPitch) {
    final normalizedEdo = math.max(0, edo);
    if (normalizedEdo <= 0) return rawPitch;
    final step = _octaveSemitones / normalizedEdo;
    final nearestStep = (rawPitch / step + 0.5).floor();
    final pattern = _patterns[normalizedEdo];
    if (pattern == null ||
        pattern.isEmpty ||
        !pattern.contains(_muteTouchMark) ||
        !_isMuted(pattern, normalizedEdo, nearestStep)) {
      return nearestStep * step;
    }

    double? left;
    double? right;
    for (var offset = 1; offset <= normalizedEdo; offset++) {
      final leftStep = nearestStep - offset;
      if (left == null && !_isMuted(pattern, normalizedEdo, leftStep)) {
        left = leftStep * step;
      }
      final rightStep = nearestStep + offset;
      if (right == null && !_isMuted(pattern, normalizedEdo, rightStep)) {
        right = rightStep * step;
      }
      if (left != null && right != null) break;
    }
    if (left == null) return right ?? rawPitch;
    if (right == null) return left;
    return rawPitch - left < right - rawPitch ? left : right;
  }

  static double stabilizeEdoPitch({
    required int edo,
    required double rawPitch,
    required double snappedPitch,
    double? previousPitch,
  }) {
    if (previousPitch == null || edo <= 0) return snappedPitch;
    return _applyHysteresis(
      rawPitch: rawPitch,
      snappedPitch: snappedPitch,
      previousPitch: previousPitch,
      slotWidth: 12 / edo,
    );
  }

  static double snapCustomPitch(TuningDefinition tuning, double rawPitch) {
    double? bestPitch;
    var bestDistance = double.infinity;

    void consider(double pitch) {
      if (pitch < 0 || pitch > 127) return;
      final distance = (rawPitch - pitch).abs();
      if (bestPitch == null ||
          distance < bestDistance - 0.0001 ||
          ((distance - bestDistance).abs() <= 0.0001 && pitch > bestPitch!)) {
        bestPitch = pitch;
        bestDistance = distance;
      }
    }

    if (tuning.type == TuningType.full) {
      for (final mark in tuning.marks) {
        consider(tuning.referencePitch + mark.cents / 100);
      }
    } else {
      final octave = (rawPitch / 12).floor();
      for (
        var candidateOctave = octave - 1;
        candidateOctave <= octave + 1;
        candidateOctave++
      ) {
        for (final mark in tuning.marks) {
          consider(candidateOctave * 12 + mark.cents / 100);
        }
      }
    }
    return bestPitch ?? rawPitch;
  }

  static double stabilizeCustomPitch({
    required double rawPitch,
    required double snappedPitch,
    double? previousPitch,
  }) {
    if (previousPitch == null) return snappedPitch;
    return _applyHysteresis(
      rawPitch: rawPitch,
      snappedPitch: snappedPitch,
      previousPitch: previousPitch,
      slotWidth: (snappedPitch - previousPitch).abs(),
    );
  }

  static String? labelForPitch(EdoScaleLine line) {
    return line.label;
  }

  static double? _customImpactRatio(TuningDefinition tuning, double pitch) {
    if (tuning.type == TuningType.full) {
      for (final mark in tuning.marks) {
        final markPitch = tuning.referencePitch + mark.cents / 100;
        if ((pitch - markPitch).abs() <= _stepEpsilon) return mark.ratio;
      }
      return null;
    }
    final octave = (pitch / _octaveSemitones).floor();
    for (
      var candidateOctave = octave - 1;
      candidateOctave <= octave + 1;
      candidateOctave++
    ) {
      final basePitch = candidateOctave * _octaveSemitones;
      for (final mark in tuning.marks) {
        if ((pitch - (basePitch + mark.cents / 100)).abs() <= _stepEpsilon) {
          return mark.ratio;
        }
      }
    }
    return null;
  }

  static bool _isCustomAnchor(TuningDefinition tuning, double pitch) {
    if (tuning.type == TuningType.full) {
      return (pitch - tuning.referencePitch).abs() <= _stepEpsilon;
    }
    final octavePitch = pitch % _octaveSemitones;
    return octavePitch.abs() <= _stepEpsilon ||
        (octavePitch - _octaveSemitones).abs() <= _stepEpsilon;
  }

  static EdoImpactTickStyle _legacyImpactTickForPitch(
    double pitch,
    double keyHeight,
  ) {
    final isSemitone = (pitch * 2).round() % 2 == 0;
    final midiPitch = pitch.round();
    final pitchClass = _positiveModulo(midiPitch, 12);
    final isNatural = _naturalPitchClasses.contains(pitchClass);
    final isAnchor = pitchClass == 0 && isSemitone;
    if (isAnchor) {
      return EdoImpactTickStyle(
        length: keyHeight * _tickHeightRatio,
        alpha: _tickAlpha,
        strokeWidth: _anchorTickStrokeWidth,
        isAnchor: true,
      );
    }
    if (isSemitone && isNatural) {
      return EdoImpactTickStyle(
        length: keyHeight * 0.66,
        alpha: 140,
        strokeWidth: 1.2,
        isAnchor: false,
      );
    }
    if (isSemitone) {
      return EdoImpactTickStyle(
        length: keyHeight * 0.49,
        alpha: 97,
        strokeWidth: 1,
        isAnchor: false,
      );
    }
    return EdoImpactTickStyle(
      length: keyHeight * 0.25,
      alpha: 61,
      strokeWidth: 1,
      isAnchor: false,
    );
  }

  static bool _isMuted(String pattern, int stepCount, int stepIndex) {
    final octaveStep = _positiveModulo(stepIndex, stepCount);
    return octaveStep < pattern.length && pattern[octaveStep] == _muteTouchMark;
  }

  static int _positiveModulo(int value, int modulus) {
    final result = value % modulus;
    return result < 0 ? result + modulus : result;
  }

  static double _applyHysteresis({
    required double rawPitch,
    required double snappedPitch,
    required double previousPitch,
    required double slotWidth,
  }) {
    if ((snappedPitch - previousPitch).abs() <= 0.0001 || slotWidth <= 0.0001) {
      return previousPitch;
    }
    if ((snappedPitch - previousPitch).abs() > slotWidth * 1.5) {
      return snappedPitch;
    }
    final boundary = (snappedPitch + previousPitch) / 2;
    final margin = math.min(slotWidth * 0.30, 0.18);
    if (snappedPitch > previousPitch && rawPitch < boundary + margin) {
      return previousPitch;
    }
    if (snappedPitch < previousPitch && rawPitch > boundary - margin) {
      return previousPitch;
    }
    return snappedPitch;
  }

  static Map<int, String> _buildPatterns() {
    final result = <int, String>{};
    for (final row in _patternTable.trim().split('\n')) {
      final separator = row.indexOf('|');
      if (separator <= 0) continue;
      final edo = int.tryParse(row.substring(0, separator));
      if (edo != null) result[edo] = row.substring(separator + 1);
    }
    return Map.unmodifiable(result);
  }

  static const _patternTable = '''
0|0N
1|0N
2|01
3|011
4|0111
5|01111
6|011111
7|0111111
8|02121212
9|022122122
10|0212121212
11|02121121121
12|021211212121
13|0212112121121
14|02121212121212
15|022122122122122
16|0323132313231323
17|02212211221221221
18|021212121212121212
19|0221221212212212212
20|03231331132313313231
21|022122122122122122122
22|0323132311323132313231
23|03323323332331332332333
24|032313231313231323132313
25|0332332332323321323323323
26|03231323133132313231323133
27|033332333322333313333233332
28|0323132313231323132313231323
29|03333233332323333133332333323
30|033332333233323333133323332333
31|0333323333233233331333323333233
32|03231323132313231323132313231323
33|033332333323332333313333233332333
34|0323231323231313232313232313232313
35|03333233332333323333133332333323333
36|033233233233133133233233233133233133
37|0332331332331133233133233133233133233
38|03232313232313231323231323231323231323
39|033333323333332323333331333333233333323
40|0332331332331333313323313323313323313333
41|03333332333333233233333313333332333333233
42|033323331333233311333233313332333133323331
43|0333333233333323332333333133333323333332333
44|03332333133323331313332333133323331333233313
45|033333323333332333323333331333333233333323333
46|0333233313332333133133323331333233313332333133
47|03333332333333233333233333313333332333333233333
48|033323331333233313331333233313332333133323331333
49|0332332331332332331313323323313323323313323323313
50|03332333133323331333313332333133323331333233313333
51|033233233133233233133133233233133233233133233233133
52|0333233313332333133333133323331333233313332333133333
53|03333333323333333323332333333331333333332333333332333
54|033323331333233313333331333233313332333133323331333333
55|0332332331332332331333313323323313323323313323323313333
56|03333233331333323333133133332333313333233331333323333133
57|033233233133233233133233133233233133233233133233233133233
58|0333323333133332333313331333323333133332333313333233331333
59|04424344144243441442434414424434244144342441443424414434244
60|044443444424444344442444424444344441444434444244443444424444
61|0333333333323333333333233233333333331333333333323333333333233
62|04343434342434343434243434243434343414343434342434343434243434
63|033333333332333333333323332333333333313333333333233333333332333
64|0434243414342434143424341434243414342434143424341434243414342434
65|03333333333233333333332333323333333333133333333332333333333323333
66|043434343424343434342434343424343434143434343424343434342434343424
67|0333333333323333333333233333233333333331333333333323333333333233333
68|04342434243414342434243414341434243424341434243424341434243424341434
69|033333333332333333333323333332333333333313333333333233333333332333333
70|0444443444442444443444442444424444434444414444434444424444434444424444
71|03333333333332333333333333233233333333333313333333333332333333333333233
72|044344244344144344244344144344144344244344144344244344144344244344144344
''';
}
