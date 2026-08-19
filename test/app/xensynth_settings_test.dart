import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/midi_device_selection.dart';
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
      expect(settings.pitchRecognitionMode, PitchRecognitionMode.hybrid);
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

    test(
      'maps arbitrary non-parallel neighbor directions to integer steps',
      () {
        const settings = XenSynthSettings(
          hexStepQ: 9,
          hexStepR: 4,
          hexQDirection: HexNeighborDirection.negativeQPositiveR,
          hexRDirection: HexNeighborDirection.negativeQ,
        );
        final configuration = settings.hexKeyboardConfiguration;

        expect(configuration.stepQ, -4);
        expect(configuration.stepR, 5);
        expect(
          PitchMapper.step(
            settings.hexQDirection.coordinate,
            stepQ: configuration.stepQ,
            stepR: configuration.stepR,
          ),
          9,
        );
        expect(
          PitchMapper.step(
            settings.hexRDirection.coordinate,
            stepQ: configuration.stepQ,
            stepR: configuration.stepR,
          ),
          4,
        );
      },
    );

    test('round-trips directions and repairs parallel stored values', () {
      const settings = XenSynthSettings(
        hexQDirection: HexNeighborDirection.positiveR,
        hexRDirection: HexNeighborDirection.negativeQPositiveR,
      );
      final restored = XenSynthSettings.fromMap(settings.toMap());

      expect(restored.hexQDirection, HexNeighborDirection.positiveR);
      expect(restored.hexRDirection, HexNeighborDirection.negativeQPositiveR);

      final repaired = XenSynthSettings.fromMap(<String, Object?>{
        'hexQDirection': HexNeighborDirection.positiveQ.index,
        'hexRDirection': HexNeighborDirection.negativeQ.index,
      });
      expect(
        repaired.hexQDirection.isParallelTo(repaired.hexRDirection),
        isFalse,
      );
      expect(repaired.toMap()['hexRDirection'], repaired.hexRDirection.index);
    });

    test('changing EDO in hex mode selects scaled coprime prime steps', () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
        edo: 53,
        hexPeriod: 99,
        hexStepQ: 52,
        hexStepR: 20,
      );

      final sevenEdo = settings.withEdo(7);
      expect(sevenEdo.hexPeriod, 7);
      expect(sevenEdo.hexStepQ, 2);
      expect(sevenEdo.hexStepR, 3);

      final free = sevenEdo.withEdo(0);
      expect(free.hexPeriod, 12);
      expect(free.hexStepQ, 5);
      expect(free.hexStepR, 2);
    });

    test('recomputes hex steps after a multi-digit EDO is typed', () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
      );

      final firstDigit = settings.withEdo(1);
      expect(firstDigit.hexStepQ, 1);
      expect(firstDigit.hexStepR, -1);

      final completedValue = firstDigit.withEdo(19);
      expect(completedValue.hexStepQ, 7);
      expect(completedValue.hexStepR, 3);
    });

    test('uses traversable fallbacks where two primes cannot fit', () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
      );

      final expected = <int, (int, int)>{1: (1, -1), 2: (1, -1), 3: (2, 1)};
      for (final entry in expected.entries) {
        final changed = settings.withEdo(entry.key);
        expect((changed.hexStepQ, changed.hexStepR), entry.value);
        final pitchClasses = <int>{
          for (var q = 0; q < entry.key; q++)
            for (var r = 0; r < entry.key; r++)
              (q * changed.hexStepQ + r * changed.hexStepR) % entry.key,
        };
        expect(pitchClasses, hasLength(entry.key), reason: 'EDO ${entry.key}');
      }
    });

    test('does not replace hidden hex steps while in linear mode', () {
      const settings = XenSynthSettings(edo: 53, hexStepQ: 52, hexStepR: 20);

      final changed = settings.withEdo(7);
      expect(changed.hexStepQ, 6);
      expect(changed.hexStepR, 6);
    });

    test('recommended hex steps scale with the effective EDO period', () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
        edo: 3,
      );

      final expected = <int, (int, int)>{
        0: (5, 2),
        12: (5, 2),
        19: (7, 3),
        26: (11, 5),
        53: (19, 7),
        72: (23, 11),
      };
      for (final entry in expected.entries) {
        final changed = settings.withEdo(entry.key);
        expect(
          (changed.hexStepQ, changed.hexStepR),
          entry.value,
          reason: 'EDO ${entry.key}',
        );
      }
    });

    test('recommended prime steps traverse every EDO pitch class', () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
        edo: 3,
      );

      for (final edo in <int>[
        0,
        for (var value = 4; value <= 72; value++) value,
      ]) {
        final changed = settings.withEdo(edo);
        final period = changed.hexPeriod;
        expect(_isPrime(changed.hexStepQ), isTrue, reason: 'Q at EDO $edo');
        expect(_isPrime(changed.hexStepR), isTrue, reason: 'R at EDO $edo');
        expect(changed.hexStepQ, isNot(changed.hexStepR), reason: 'EDO $edo');

        final pitchClasses = <int>{
          for (var q = 0; q < period; q++)
            for (var r = 0; r < period; r++)
              (q * changed.hexStepQ + r * changed.hexStepR) % period,
        };
        expect(pitchClasses, hasLength(period), reason: 'EDO $edo');
      }
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

    test('preserves signed and zero steps while limiting magnitude', () {
      final settings = XenSynthSettings.fromMap(<String, Object?>{
        'edo': 7,
        'hexStepQ': -99,
        'hexStepR': 0,
      });

      expect(settings.hexStepQ, -6);
      expect(settings.hexStepR, 0);
      expect(settings.toMap()['hexStepR'], 0);
      expect(const XenSynthSettings(edo: 7, hexStepQ: -99).hexStepQ, -6);
      expect(const XenSynthSettings(edo: 7, hexStepQ: 0).hexStepQ, 0);
      expect(
        const XenSynthSettings(
          edo: 7,
          hexStepR: -99,
        ).copyWith(hexStepR: -99).hexStepR,
        -6,
      );
    });

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

    test('migrates legacy microphone modes to local hybrid recognition', () {
      final yin = XenSynthSettings.fromMap(<String, Object?>{
        'pitchRecognitionMode': 'YIN',
      });

      expect(yin.pitchRecognitionMode, PitchRecognitionMode.hybrid);
      expect(yin.toMap()['pitchRecognitionMode'], 'hybrid');

      final fft = XenSynthSettings.fromMap(<String, Object?>{
        'pitchRecognitionMode': 'FFT',
        'keyboardLayoutMode': 'spatial',
      });
      expect(fft.pitchRecognitionMode, PitchRecognitionMode.hybrid);
      expect(fft.layoutMode, KeyboardLayoutMode.spatial);
      expect(fft.toMap()['pitchRecognitionMode'], 'hybrid');
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

    test('normalizes MIDI settings to one input and one output target', () {
      const configured = XenSynthSettings(
        midiInputEnabled: false,
        midiOutputEnabled: false,
        midiInputDeviceIds: ['coremidi:12', 'applemidi:input'],
        midiInputDeviceSelectionConfigured: true,
        midiOutputDeviceIds: ['applemidi:SnVzdFBpYW5v', 'bluetooth:42:0'],
        networkMidiEnabled: true,
        networkMidiDestinationIds: ['applemidi:SnVzdFBpYW5v'],
        bluetoothMidiOutputIds: ['bluetooth:42:0'],
      );

      final restored = XenSynthSettings.fromMap(configured.toMap());

      expect(restored.midiInputEnabled, isFalse);
      expect(restored.midiOutputEnabled, isFalse);
      expect(restored.midiInputDeviceIds, ['coremidi:12']);
      expect(restored.midiInputDeviceSelectionConfigured, isTrue);
      expect(restored.midiOutputDeviceIds, ['applemidi:SnVzdFBpYW5v']);
      expect(restored.networkMidiEnabled, isTrue);
      expect(restored.networkMidiDestinationIds, ['applemidi:SnVzdFBpYW5v']);
      expect(restored.bluetoothMidiOutputIds, isEmpty);
      expect(restored.toMap(), isNot(contains('networkMidiHost')));
      expect(restored.toMap(), isNot(contains('networkMidiPort')));
    });

    test('allows one MIDI target to be enabled in both directions', () {
      const inputId = 'android-midi-input:42:0';
      const outputId = 'android-midi-output:42:1';

      final inputDisabled = const XenSynthSettings(
        midiInputEnabled: false,
        midiInputDeviceIds: [inputId],
        midiOutputDeviceIds: [outputId],
      ).normalizedMidiSelections();
      final outputDisabled = const XenSynthSettings(
        midiOutputEnabled: false,
        midiInputDeviceIds: [inputId],
        midiOutputDeviceIds: [outputId],
      ).normalizedMidiSelections();

      expect(inputDisabled.midiInputDeviceIds, [inputId]);
      expect(inputDisabled.midiOutputDeviceIds, [outputId]);
      expect(outputDisabled.midiInputDeviceIds, [inputId]);
      expect(outputDisabled.midiOutputDeviceIds, [outputId]);
      final bothEnabled = inputDisabled.copyWith(midiInputEnabled: true);
      expect(bothEnabled.midiInputDeviceIds, [inputId]);
      expect(bothEnabled.midiOutputDeviceIds, [outputId]);
    });

    test('migrates unified MIDI output IDs from transport-specific lists', () {
      final restored = XenSynthSettings.fromMap(<String, Object?>{
        'networkMidiDestinationIds': ['applemidi:peer'],
        'bluetoothMidiOutputIds': ['coremidi:9'],
      });

      expect(restored.midiOutputDeviceIds, ['applemidi:peer']);
      expect(restored.midiInputDeviceSelectionConfigured, isTrue);
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

  test('normalizes LAN target identity by IP rather than service port', () {
    expect(
      isSameMidiTarget(
        'network:192.168.1.12:5004',
        'network:192.168.1.12:5005',
      ),
      isTrue,
    );
  });
}

bool _isPrime(int value) {
  if (value < 2) return false;
  for (var divisor = 2; divisor * divisor <= value; divisor++) {
    if (value % divisor == 0) return false;
  }
  return true;
}
