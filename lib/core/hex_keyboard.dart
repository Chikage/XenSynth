import 'dart:math' as math;

int positiveModulo(int value, int modulus) {
  if (modulus <= 0) throw ArgumentError.value(modulus, 'modulus');
  final result = value % modulus;
  return result < 0 ? result + modulus : result;
}

class AxialCoordinate {
  const AxialCoordinate({required this.q, required this.r});

  static const origin = AxialCoordinate(q: 0, r: 0);

  final int q;
  final int r;
  int get s => -q - r;

  @override
  bool operator ==(Object other) =>
      other is AxialCoordinate && other.q == q && other.r == r;

  @override
  int get hashCode => Object.hash(q, r);
}

class HexPoint {
  const HexPoint(this.x, this.y);

  final double x;
  final double y;
}

class HexBounds {
  const HexBounds({
    required this.minX,
    required this.maxX,
    required this.minY,
    required this.maxY,
  });

  final double minX;
  final double maxX;
  final double minY;
  final double maxY;
  double get width => maxX - minX;
  double get height => maxY - minY;
  HexPoint get center => HexPoint((minX + maxX) / 2, (minY + maxY) / 2);

  factory HexBounds.from(Iterable<HexPoint> points, {double radius = 0}) {
    var minX = double.infinity;
    var maxX = double.negativeInfinity;
    var minY = double.infinity;
    var maxY = double.negativeInfinity;
    var count = 0;
    for (final point in points) {
      count++;
      minX = math.min(minX, point.x - radius);
      maxX = math.max(maxX, point.x + radius);
      minY = math.min(minY, point.y - radius);
      maxY = math.max(maxY, point.y + radius);
    }
    if (count == 0) throw ArgumentError('At least one point is required');
    return HexBounds(minX: minX, maxX: maxX, minY: minY, maxY: maxY);
  }
}

abstract final class HexGeometry {
  static AxialCoordinate oddQToAxial(int column, int row) {
    return AxialCoordinate(q: column, r: row - (column - (column & 1)) ~/ 2);
  }

  static int distance(AxialCoordinate coordinate) {
    return (coordinate.q.abs() +
            coordinate.r.abs() +
            (coordinate.q + coordinate.r).abs()) ~/
        2;
  }

  static HexPoint point(AxialCoordinate coordinate, double radius) {
    return HexPoint(
      radius * 1.5 * coordinate.q,
      radius * math.sqrt(3) * (coordinate.r + coordinate.q / 2),
    );
  }

  static HexPoint rotate(HexPoint point, double degrees) {
    final radians = degrees * math.pi / 180;
    final cosine = math.cos(radians);
    final sine = math.sin(radians);
    return HexPoint(
      point.x * cosine - point.y * sine,
      point.x * sine + point.y * cosine,
    );
  }

  static double squaredDistance(HexPoint first, HexPoint second) {
    final dx = first.x - second.x;
    final dy = first.y - second.y;
    return dx * dx + dy * dy;
  }
}

class EdoPitch {
  const EdoPitch({
    required this.step,
    required this.midiPitch,
    required this.midiKey,
    required this.cents,
    required this.frequency,
    required this.isPlayable,
  });

  final int step;
  final double midiPitch;
  final int? midiKey;
  final double cents;
  final double frequency;
  final bool isPlayable;
}

abstract final class PitchMapper {
  static int step(
    AxialCoordinate coordinate, {
    required int stepQ,
    required int stepR,
  }) {
    return coordinate.q * stepQ + coordinate.r * stepR;
  }

  static EdoPitch pitchForStep(int step, int edo) {
    if (edo <= 0) {
      return EdoPitch(
        step: step,
        midiPitch: double.nan,
        midiKey: null,
        cents: double.nan,
        frequency: double.nan,
        isPlayable: false,
      );
    }
    final midiPitch = 60 + step * 12 / edo;
    final frequency = 261.6255653005986 * math.pow(2, step / edo);
    final midiKey = (midiPitch + 0.5).floor();
    return EdoPitch(
      step: step,
      midiPitch: midiPitch,
      midiKey: midiKey,
      cents: (midiPitch - midiKey) * 100,
      frequency: frequency,
      isPlayable:
          midiPitch.isFinite &&
          frequency.isFinite &&
          midiKey >= 0 &&
          midiKey <= 127,
    );
  }
}

class HexKeyboardConfiguration {
  const HexKeyboardConfiguration({
    this.columns = 35,
    this.rows = 8,
    this.period = 53,
    this.stepQ = 9,
    this.stepR = 4,
    this.groupByOctave = false,
    this.radius = 24,
    this.rotationDegrees = 12,
  });

  static const defaults = HexKeyboardConfiguration();

  final int columns;
  final int rows;
  final int period;
  final int stepQ;
  final int stepR;
  final bool groupByOctave;
  final double radius;
  final int rotationDegrees;

  HexKeyboardConfiguration normalized() => HexKeyboardConfiguration(
    columns: columns.clamp(4, 64),
    rows: rows.clamp(3, 32),
    period: period.clamp(1, 200),
    stepQ: stepQ.clamp(-200, 200),
    stepR: stepR.clamp(-200, 200),
    groupByOctave: groupByOctave,
    radius: 24,
    rotationDegrees: rotationDegrees.clamp(-60, 60),
  );
}

typedef HexaKeyboardConfiguration = HexKeyboardConfiguration;

class HexKey {
  const HexKey({
    required this.coordinate,
    required this.step,
    required this.pitchClass,
    required this.audioPitch,
    required this.center,
  });

  final AxialCoordinate coordinate;
  final int step;
  final int pitchClass;
  final EdoPitch audioPitch;
  final HexPoint center;
}

class PeriodVector {
  const PeriodVector({
    required this.dq,
    required this.dr,
    required this.distance,
  });

  final int dq;
  final int dr;
  final int distance;
}

class HexaKeyboardLayout {
  const HexaKeyboardLayout({
    required this.configuration,
    required this.cells,
    required this.periodVectors,
    required this.keyBounds,
  });

  final HexKeyboardConfiguration configuration;
  final List<HexKey> cells;
  final List<PeriodVector> periodVectors;
  final HexBounds keyBounds;

  HexKey? cellAt(AxialCoordinate coordinate) {
    for (final cell in cells) {
      if (cell.coordinate == coordinate) return cell;
    }
    return null;
  }

  HexKey? nearest(HexPoint point, {double maximumDistance = 32}) {
    HexKey? result;
    var best = maximumDistance * maximumDistance;
    for (final cell in cells) {
      final distance = HexGeometry.squaredDistance(point, cell.center);
      if (distance <= best) {
        result = cell;
        best = distance;
      }
    }
    return result;
  }
}

abstract final class HexaKeyboardLayoutEngine {
  static HexaKeyboardLayout build([
    HexKeyboardConfiguration configuration = HexKeyboardConfiguration.defaults,
  ]) {
    final normalized = configuration.normalized();
    final cells = <HexKey>[];
    for (var column = 0; column < normalized.columns; column++) {
      for (var row = 0; row < normalized.rows; row++) {
        final coordinate = HexGeometry.oddQToAxial(
          column - normalized.columns ~/ 2,
          row - normalized.rows ~/ 2,
        );
        final step = PitchMapper.step(
          coordinate,
          stepQ: normalized.stepQ,
          stepR: normalized.stepR,
        );
        var center = HexGeometry.rotate(
          HexGeometry.point(coordinate, normalized.radius),
          normalized.rotationDegrees.toDouble(),
        );
        if (normalized.groupByOctave) {
          final octave = (step / normalized.period).floor();
          center = HexPoint(center.x + octave * 5.5, center.y - octave * 2.5);
        }
        cells.add(
          HexKey(
            coordinate: coordinate,
            step: step,
            pitchClass: positiveModulo(step, normalized.period),
            audioPitch: PitchMapper.pitchForStep(step, normalized.period),
            center: center,
          ),
        );
      }
    }
    return HexaKeyboardLayout(
      configuration: normalized,
      cells: List.unmodifiable(cells),
      periodVectors: List.unmodifiable(periodVectors(normalized)),
      keyBounds: HexBounds.from(
        cells.map((cell) => cell.center),
        radius: normalized.radius,
      ),
    );
  }

  static List<PeriodVector> periodVectors([
    HexKeyboardConfiguration configuration = HexKeyboardConfiguration.defaults,
  ]) {
    final normalized = configuration.normalized();
    final limit = math.min(
      24,
      math.max(8, math.sqrt(normalized.period).ceil() + 4),
    );
    final candidates = <PeriodVector>[];
    for (var dq = -limit; dq <= limit; dq++) {
      for (var dr = -limit; dr <= limit; dr++) {
        if (dq == 0 && dr == 0) continue;
        final step = dq * normalized.stepQ + dr * normalized.stepR;
        if (positiveModulo(step, normalized.period) != 0) continue;
        candidates.add(
          PeriodVector(
            dq: dq,
            dr: dr,
            distance: HexGeometry.distance(AxialCoordinate(q: dq, r: dr)),
          ),
        );
      }
    }
    candidates.sort((a, b) {
      final distance = a.distance.compareTo(b.distance);
      if (distance != 0) return distance;
      final q = a.dq.abs().compareTo(b.dq.abs());
      return q != 0 ? q : a.dr.abs().compareTo(b.dr.abs());
    });
    final chosen = <PeriodVector>[];
    for (final candidate in candidates) {
      if (chosen.isEmpty ||
          chosen.every(
            (item) => item.dq * candidate.dr - item.dr * candidate.dq != 0,
          )) {
        chosen.add(candidate);
      }
      if (chosen.length == 2) break;
    }
    return chosen.isEmpty ? candidates.take(2).toList() : chosen;
  }
}
