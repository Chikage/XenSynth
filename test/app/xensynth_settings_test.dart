import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_settings.dart';

void main() {
  group('XenSynthSettings hex semantics', () {
    test('uses Android-aligned defaults', () {
      const settings = XenSynthSettings();

      expect(settings.hexPeriod, 26);
      expect(settings.hexStepQ, 9);
      expect(settings.hexStepR, 4);
      expect(settings.touchSensitivity, closeTo(0.4, 0.000001));
      expect(settings.touchSensitivityPercent, closeTo(120, 0.000001));
      expect(settings.playbackPreviewSeconds, 1.8);
      expect(settings.pitchSnapEnabled, isFalse);
      expect(
        settings.spatialProjection,
        SpatialProjectionMode.obliquePerspective,
      );
    });

    test('changing EDO synchronizes period and clamps both steps', () {
      const settings = XenSynthSettings(
        edo: 53,
        hexPeriod: 99,
        hexStepQ: 52,
        hexStepR: 20,
      );

      final sevenEdo = settings.withEdo(7);
      expect(sevenEdo.hexPeriod, 7);
      expect(sevenEdo.hexStepQ, 6);
      expect(sevenEdo.hexStepR, 6);

      final free = sevenEdo.withEdo(0);
      expect(free.hexPeriod, 12);
      expect(free.hexStepQ, 1);
      expect(free.hexStepR, 1);
    });

    test('reads legacy maps while applying current effective ranges', () {
      final settings = XenSynthSettings.fromMap(<String, Object?>{
        'edo': 19,
        'hexPeriod': 53,
        'hexStepQ': 200,
        'hexStepR': -7,
        'touchSensitivity': 0.58,
        'playbackPreviewSeconds': 2.8,
        'pitchSnapEnabled': true,
      });

      expect(settings.hexPeriod, 19);
      expect(settings.hexStepQ, 18);
      expect(settings.hexStepR, 1);
      expect(settings.touchSensitivity, closeTo(0.58, 0.000001));
      expect(settings.touchSensitivityPercent, closeTo(129, 0.000001));
      expect(settings.playbackPreviewSeconds, 2.8);
      expect(settings.pitchSnapEnabled, isTrue);
      expect(settings.toMap()['hexPeriod'], 19);
    });

    test('accepts legacy percentage-form sensitivity values', () {
      final settings = XenSynthSettings.fromMap(<String, Object?>{
        'touchSensitivity': 120,
      });

      expect(settings.touchSensitivity, closeTo(0.4, 0.000001));
      expect(settings.touchSensitivityPercent, closeTo(120, 0.000001));
    });

    test('applies pitch offset with the opposite sign', () {
      const positive = XenSynthSettings(pitchOffsetCents: 3);
      const negative = XenSynthSettings(pitchOffsetCents: -3);

      expect(positive.pitchOffsetCents, 3);
      expect(positive.appliedPitchOffsetCents, -3);
      expect(negative.appliedPitchOffsetCents, 3);
    });

    test('round-trips the spatial mode and oblique perspective', () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
        spatialProjection: SpatialProjectionMode.obliquePerspective,
        pitchSnapEnabled: true,
      );

      final restored = XenSynthSettings.fromMap(settings.toMap());

      expect(restored.layoutMode, KeyboardLayoutMode.spatial);
      expect(
        restored.spatialProjection,
        SpatialProjectionMode.obliquePerspective,
      );
      expect(restored.shouldSnapPlaybackPitch, isTrue);
    });

    test('migrates legacy projection names to the precise modes', () {
      final perspective = XenSynthSettings.fromMap(<String, Object?>{
        'spatialProjection': 'perspective',
      });
      final cabinet = XenSynthSettings.fromMap(<String, Object?>{
        'spatialProjection': 'oblique',
      });

      expect(
        perspective.spatialProjection,
        SpatialProjectionMode.obliquePerspective,
      );
      expect(cabinet.spatialProjection, SpatialProjectionMode.cabinet);
      expect(perspective.toMap()['spatialProjection'], 'obliquePerspective');
      expect(cabinet.toMap()['spatialProjection'], 'cabinet');
    });
  });
}
