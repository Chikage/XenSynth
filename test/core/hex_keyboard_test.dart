import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/hex_keyboard.dart';

void main() {
  group('Hex basis directions', () {
    test('contains exactly the six computable neighboring cells', () {
      expect(
        HexNeighborDirection.values.map((item) => item.coordinate).toSet(),
        <AxialCoordinate>{
          const AxialCoordinate(q: 1, r: 0),
          const AxialCoordinate(q: 0, r: 1),
          const AxialCoordinate(q: -1, r: 1),
          const AxialCoordinate(q: -1, r: 0),
          const AxialCoordinate(q: 0, r: -1),
          const AxialCoordinate(q: 1, r: -1),
        },
      );
      for (final direction in HexNeighborDirection.values) {
        expect(HexGeometry.distance(direction.coordinate), 1);
        expect(direction.isParallelTo(direction.opposite), isTrue);
      }
    });

    test('rejects parallel bases', () {
      expect(
        () => HexBasisStepMapping.resolve(
          qDirection: HexNeighborDirection.positiveQ,
          rDirection: HexNeighborDirection.negativeQ,
          qStep: 9,
          rStep: 4,
        ),
        throwsArgumentError,
      );
    });
  });

  group('HexaKeyboardLayoutEngine', () {
    test('builds the normalized default window', () {
      final layout = HexaKeyboardLayoutEngine.build();

      expect(layout.cells, hasLength(35 * 8));
      expect(layout.slots, hasLength(35 * 8));
      expect(layout.configuration.radius, 24);
      expect(layout.periodVectors, isNotEmpty);
      expect(layout.keyBounds.width, greaterThan(0));
      expect(layout.windowOutline.points, hasLength(4));
      expect(
        layout.modelBounds.width,
        greaterThanOrEqualTo(layout.keyBounds.width),
      );
      expect(
        layout.modelBounds.height,
        greaterThanOrEqualTo(layout.keyBounds.height),
      );
      expect(layout.cellAt(AxialCoordinate.origin), isNotNull);
      expect(layout.keyForStep(0)?.coordinate, AxialCoordinate.origin);
      expect(layout.stats.generated, greaterThan(0));
      expect(layout.stats.generated, layout.stats.omitted);
    });

    test('anchors the unrotated window at the Android odd-q origin', () {
      const configuration = HexKeyboardConfiguration(rotationDegrees: 0);
      final layout = HexaKeyboardLayoutEngine.build(configuration);
      final originSlot = layout.slots.singleWhere(
        (slot) => slot.column == 17 && slot.row == 3,
      );

      expect(originSlot.coordinate, AxialCoordinate.origin);
      expect(layout.stats.generated, 0);
      expect(layout.stats.omitted, 0);
      expect(layout.defaultSelection?.coordinate, AxialCoordinate.origin);
    });

    test('uses the 72 degree parallelogram to resample rotated cells', () {
      const configuration = HexKeyboardConfiguration(
        columns: 19,
        rows: 7,
        rotationDegrees: 12,
        frameAcuteAngleDegrees: 72,
      );
      final layout = HexaKeyboardLayoutEngine.build(configuration);

      expect(layout.cells, hasLength(19 * 7));
      expect(layout.stats.generated, greaterThan(0));
      expect(layout.stats.generated, layout.stats.omitted);
      expect(layout.windowOutline.horizontalShift, greaterThan(0));
      expect(
        layout.modelBounds.minX,
        lessThanOrEqualTo(layout.windowOutline.bounds.minX),
      );
      expect(
        layout.modelBounds.maxX,
        greaterThanOrEqualTo(layout.windowOutline.bounds.maxX),
      );
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

    test('formats pitch labels using the effective period', () {
      const period = 53;
      const origin = HexKey(
        coordinate: AxialCoordinate.origin,
        step: 0,
        pitchClass: 0,
        audioPitch: EdoPitch(
          step: 0,
          midiPitch: 60,
          midiKey: 60,
          cents: 0,
          frequency: 261.625565,
          isPlayable: true,
        ),
        center: HexPoint(0, 0),
      );
      const lowerC = HexKey(
        coordinate: AxialCoordinate(q: -1, r: 0),
        step: -53,
        pitchClass: 0,
        audioPitch: EdoPitch(
          step: -53,
          midiPitch: 48,
          midiKey: 48,
          cents: 0,
          frequency: 130.812782,
          isPlayable: true,
        ),
        center: HexPoint(-36, 0),
      );
      const pitchClass = HexKey(
        coordinate: AxialCoordinate(q: 1, r: 0),
        step: 17,
        pitchClass: 17,
        audioPitch: EdoPitch(
          step: 17,
          midiPitch: 63.849,
          midiKey: 64,
          cents: -15.1,
          frequency: 327,
          isPlayable: true,
        ),
        center: HexPoint(36, 0),
      );

      expect(origin.labelForPeriod(period), 'C4');
      expect(lowerC.labelForPeriod(period), 'C3');
      expect(pitchClass.labelForPeriod(period), '17');
    });

    test('retains the previous key near a boundary before switching', () {
      final layout = HexaKeyboardLayoutEngine.build(
        const HexKeyboardConfiguration(
          columns: 7,
          rows: 5,
          period: 12,
          stepQ: 7,
          stepR: 4,
          rotationDegrees: 0,
        ),
      );
      final origin = layout.cellAt(AxialCoordinate.origin)!;
      final neighbor = layout.cellAt(const AxialCoordinate(q: 1, r: 0))!;

      HexPoint pointAt(double fraction) => HexPoint(
        origin.center.x + (neighbor.center.x - origin.center.x) * fraction,
        origin.center.y + (neighbor.center.y - origin.center.y) * fraction,
      );

      final retained = HexTouchHitTester.keyAt(
        point: pointAt(0.52),
        layout: layout,
        previousCoordinate: origin.coordinate,
        sensitivity: 1,
      );
      final switched = HexTouchHitTester.keyAt(
        point: pointAt(0.64),
        layout: layout,
        previousCoordinate: origin.coordinate,
        sensitivity: 1,
      );

      expect(retained?.coordinate, origin.coordinate);
      expect(switched?.coordinate, neighbor.coordinate);
    });
  });
}
