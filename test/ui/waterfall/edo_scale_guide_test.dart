import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/tuning.dart';
import 'package:xensynth/ui/waterfall/edo_scale_guide.dart';

void main() {
  group('EdoScaleGuide', () {
    test('contains every built-in scale from free through 72 EDO', () {
      for (var edo = 0; edo <= 72; edo++) {
        expect(EdoScaleGuide.hasScale(edo), isTrue, reason: '$edo EDO');
      }
    });

    test('defines one visible built-in mark for every non-free EDO step', () {
      for (var edo = 2; edo <= 72; edo++) {
        final octaveLines = EdoScaleGuide.linesForRange(
          edo: edo,
          minimumPitch: 0,
          maximumPitch: 12 - 0.0001,
        ).where((line) => line.pitch >= 0 && line.pitch < 12 - 0.000001);
        expect(octaveLines, hasLength(edo), reason: '$edo EDO');
      }
    });

    test('uses the Android 53 EDO mark strengths around C4', () {
      final lines = EdoScaleGuide.linesForRange(
        edo: 53,
        minimumPitch: 59.9,
        maximumPitch: 60.5,
      ).toList();

      final c4 = lines.singleWhere((line) => (line.pitch - 60).abs() < 0.0001);
      final next = lines.singleWhere(
        (line) => (line.pitch - (60 + 12 / 53)).abs() < 0.0001,
      );
      expect(c4.isAnchor, isTrue);
      expect(c4.ratio, 1);
      expect(EdoScaleGuide.labelForPitch(c4), 'C4');
      expect(next.isAnchor, isFalse);
      expect(next.ratio, closeTo(0.4, 0.0001));
    });

    test('keeps octave anchors while thinning dense lines', () {
      final full = EdoScaleGuide.linesForRange(
        edo: 72,
        minimumPitch: 48,
        maximumPitch: 72,
      ).toList();
      final thinned = EdoScaleGuide.linesForRange(
        edo: 72,
        minimumPitch: 48,
        maximumPitch: 72,
        minimumPitchSpacing: 0.6,
      ).toList();

      expect(thinned.length, lessThan(full.length));
      expect(
        thinned.where((line) => line.isAnchor).map((line) => line.pitch),
        containsAll(<double>[48, 60, 72]),
      );
    });

    test('leaves free pitch untouched and snaps positive EDO pitch', () {
      expect(EdoScaleGuide.snapPitch(0, 60.17), 60.17);
      expect(EdoScaleGuide.snapPitch(53, 60.17), closeTo(60 + 12 / 53, 0.0001));
    });

    test('retains the previous EDO slot through the boundary margin', () {
      expect(
        EdoScaleGuide.stabilizeEdoPitch(
          edo: 12,
          rawPitch: 60.55,
          snappedPitch: 61,
          previousPitch: 60,
        ),
        60,
      );
      expect(
        EdoScaleGuide.stabilizeEdoPitch(
          edo: 12,
          rawPitch: 60.72,
          snappedPitch: 61,
          previousPitch: 60,
        ),
        61,
      );
    });

    test('matches Android impact tick parameters and legacy fallback', () {
      final anchor = EdoScaleGuide.impactTickForPitch(
        edo: 53,
        pitch: 60,
        keyHeight: 100,
      );
      final minor = EdoScaleGuide.impactTickForPitch(
        edo: 53,
        pitch: 60 + 12 / 53,
        keyHeight: 100,
      );
      final fallback = EdoScaleGuide.impactTickForPitch(
        edo: 53,
        pitch: 60.5,
        keyHeight: 100,
      );

      expect(anchor.length, 84);
      expect(anchor.alpha, 184);
      expect(anchor.strokeWidth, 1.4);
      expect(anchor.isAnchor, isTrue);
      expect(minor.length, closeTo(33.6, 0.000001));
      expect(minor.alpha, 74);
      expect(minor.strokeWidth, 1);
      expect(fallback.length, 25);
      expect(fallback.alpha, 61);
      expect(fallback.strokeWidth, 1);
    });

    test('draws and snaps octave custom tuning marks', () {
      const tuning = TuningDefinition(
        profile: 'custom',
        type: TuningType.octave,
        displayOffsetCents: 0,
        referencePitch: 60,
        marks: [
          TuningMark(cents: 0, ratio: 1),
          TuningMark(cents: 700, ratio: 0.6),
        ],
        keybind: {},
      );

      final lines = EdoScaleGuide.customLinesForRange(
        tuning: tuning,
        minimumPitch: 59,
        maximumPitch: 68,
      ).toList();
      expect(
        lines.singleWhere((line) => (line.pitch - 60).abs() < 0.0001).label,
        'C4',
      );
      expect(
        lines.singleWhere((line) => (line.pitch - 67).abs() < 0.0001).ratio,
        0.6,
      );
      expect(EdoScaleGuide.snapCustomPitch(tuning, 66.8), 67);
      final impact = EdoScaleGuide.impactTickForPitch(
        edo: 53,
        pitch: 67,
        keyHeight: 100,
        tuning: tuning,
      );
      expect(impact.length, closeTo(50.4, 0.000001));
      expect(impact.alpha, 110);
      expect(impact.strokeWidth, closeTo(0.84, 0.000001));
    });

    test('labels full tuning reference and breaks snap ties upward', () {
      const tuning = TuningDefinition(
        profile: 'full',
        type: TuningType.full,
        displayOffsetCents: 0,
        referencePitch: 60.5,
        marks: [
          TuningMark(cents: 0, ratio: 1),
          TuningMark(cents: 100, ratio: 0.5),
        ],
        keybind: {},
      );

      final lines = EdoScaleGuide.customLinesForRange(
        tuning: tuning,
        minimumPitch: 60,
        maximumPitch: 62,
      ).toList();
      expect(lines.first.label, 'O');
      expect(EdoScaleGuide.snapCustomPitch(tuning, 61), 61.5);
    });
  });
}
