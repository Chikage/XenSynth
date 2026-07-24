import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/core/hex_keyboard.dart';

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
      expect(settings.hapticFeedbackEnabled, isTrue);
      expect(settings.pitchRecognitionMode, PitchRecognitionMode.yin);
      expect(settings.microphoneSensitivity, 1);
      expect(
        settings.hapticFeedbackStrength,
        closeTo(XenSynthSettings.defaultHapticFeedbackStrength, 0.000001),
      );
      expect(
        settings.spatialProjection,
        SpatialProjectionMode.obliquePerspective,
      );
    });

    test('rotates the Q axis counterclockwise and reverses the R axis', () {
      const settings = XenSynthSettings(hexStepQ: 9, hexStepR: 4);
      final configuration = settings.hexKeyboardConfiguration;

      expect(
        PitchMapper.step(
          const AxialCoordinate(q: 1, r: -1),
          stepQ: configuration.stepQ,
          stepR: configuration.stepR,
        ),
        9,
      );
      expect(
        PitchMapper.step(
          const AxialCoordinate(q: 0, r: -1),
          stepQ: configuration.stepQ,
          stepR: configuration.stepR,
        ),
        4,
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
      expect(settings.hexStepR, -7);
      expect(settings.touchSensitivity, closeTo(0.58, 0.000001));
      expect(settings.touchSensitivityPercent, closeTo(129, 0.000001));
      expect(settings.playbackPreviewSeconds, 2.8);
      expect(settings.pitchSnapEnabled, isTrue);
      expect(settings.toMap()['hexPeriod'], 19);
    });

    test(
      'preserves signed steps while rejecting zero and limiting magnitude',
      () {
        final settings = XenSynthSettings.fromMap(<String, Object?>{
          'edo': 7,
          'hexStepQ': -99,
          'hexStepR': 0,
        });

        expect(settings.hexStepQ, -6);
        expect(settings.hexStepR, 1);
        expect(const XenSynthSettings(edo: 7, hexStepQ: -99).hexStepQ, -6);
        expect(
          const XenSynthSettings(
            edo: 7,
            hexStepR: -99,
          ).copyWith(hexStepR: -99).hexStepR,
          -6,
        );
      },
    );

    test('accepts legacy percentage-form sensitivity values', () {
      final settings = XenSynthSettings.fromMap(<String, Object?>{
        'touchSensitivity': 120,
      });

      expect(settings.touchSensitivity, closeTo(0.4, 0.000001));
      expect(settings.touchSensitivityPercent, closeTo(120, 0.000001));
    });

    test('migrates, round-trips, and copies touch vibration strength', () {
      final legacyEnabled = XenSynthSettings.fromMap(<String, Object?>{
        'hapticFeedbackEnabled': true,
      });
      final legacyDisabled = XenSynthSettings.fromMap(<String, Object?>{
        'hapticFeedbackEnabled': false,
      });
      final strong = XenSynthSettings.fromMap(<String, Object?>{
        'hapticFeedbackStrength': 0.92,
      });

      expect(
        legacyEnabled.hapticFeedbackStrength,
        XenSynthSettings.defaultHapticFeedbackStrength,
      );
      expect(legacyDisabled.hapticFeedbackStrength, 0);
      expect(legacyDisabled.hapticFeedbackEnabled, isFalse);
      expect(strong.hapticFeedbackStrength, closeTo(0.92, 0.000001));
      expect(strong.toMap()['hapticFeedbackStrength'], closeTo(0.92, 0.000001));
      expect(strong.toMap()['hapticFeedbackEnabled'], isTrue);
      expect(
        strong.copyWith(hapticFeedbackStrength: 0).hapticFeedbackEnabled,
        isFalse,
      );
    });

    test('round-trips and copies microphone recognition mode', () {
      final yin = XenSynthSettings.fromMap(<String, Object?>{
        'pitchRecognitionMode': 'YIN',
      });

      expect(yin.pitchRecognitionMode, PitchRecognitionMode.yin);
      expect(yin.toMap()['pitchRecognitionMode'], 'yin');
      expect(
        yin
            .copyWith(pitchRecognitionMode: PitchRecognitionMode.piano)
            .pitchRecognitionMode,
        PitchRecognitionMode.piano,
      );

      final fft = XenSynthSettings.fromMap(<String, Object?>{
        'pitchRecognitionMode': 'FFT',
        'keyboardLayoutMode': 'spatial',
      });
      expect(fft.pitchRecognitionMode, PitchRecognitionMode.fft);
      expect(fft.layoutMode, KeyboardLayoutMode.linear);
      expect(fft.toMap()['pitchRecognitionMode'], 'fft');

      final forcedLinear = const XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
      ).copyWith(pitchRecognitionMode: PitchRecognitionMode.fft);
      expect(forcedLinear.layoutMode, KeyboardLayoutMode.linear);
    });

    test('round-trips and clamps microphone sensitivity', () {
      final quiet = XenSynthSettings.fromMap(<String, Object?>{
        'microphoneSensitivity': 0.1,
      });
      final sensitive = XenSynthSettings.fromMap(<String, Object?>{
        'microphoneSensitivity': 4,
      });

      expect(quiet.microphoneSensitivity, 0.5);
      expect(sensitive.microphoneSensitivity, 2);
      expect(
        const XenSynthSettings()
            .copyWith(microphoneSensitivity: 1.35)
            .toMap()['microphoneSensitivity'],
        closeTo(1.35, 0.000001),
      );
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
