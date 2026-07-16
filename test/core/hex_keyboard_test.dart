import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/hex_keyboard.dart';

void main() {
  group('HexaKeyboardLayoutEngine', () {
    test('builds the normalized default window', () {
      final layout = HexaKeyboardLayoutEngine.build();

      expect(layout.cells, hasLength(35 * 8));
      expect(layout.configuration.radius, 24);
      expect(layout.periodVectors, isNotEmpty);
      expect(layout.keyBounds.width, greaterThan(0));
      expect(layout.cellAt(AxialCoordinate.origin), isNotNull);
    });

    test('maps negative steps with positive modulo', () {
      expect(positiveModulo(-1, 53), 52);
      final pitch = PitchMapper.pitchForStep(-1, 53);
      expect(pitch.midiPitch, closeTo(60 - 12 / 53, 0.00001));
      expect(pitch.isPlayable, isTrue);
    });

    test('clamps invalid configuration values', () {
      const configuration = HexKeyboardConfiguration(
        columns: 2,
        rows: 80,
        period: 0,
        rotationDegrees: 90,
      );
      final normalized = configuration.normalized();
      expect(normalized.columns, 4);
      expect(normalized.rows, 32);
      expect(normalized.period, 1);
      expect(normalized.rotationDegrees, 60);
    });
  });
}
