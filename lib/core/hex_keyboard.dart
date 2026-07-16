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

  @override
  bool operator ==(Object other) =>
      other is HexPoint && other.x == x && other.y == y;

  @override
  int get hashCode => Object.hash(x, y);
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

  static HexBounds merge(HexBounds first, Iterable<HexBounds> rest) {
    var result = first;
    for (final next in rest) {
      result = HexBounds(
        minX: math.min(result.minX, next.minX),
        maxX: math.max(result.maxX, next.maxX),
        minY: math.min(result.minY, next.minY),
        maxY: math.max(result.maxY, next.maxY),
      );
    }
    return result;
  }
}

/// Corners are ordered top-left, top-right, bottom-right, bottom-left.
class HexParallelogram {
  HexParallelogram({
    required Iterable<HexPoint> points,
    required this.bounds,
    required this.horizontalShift,
  }) : points = List.unmodifiable(points) {
    if (this.points.length != 4) {
      throw ArgumentError.value(this.points, 'points', 'Expected four corners');
    }
  }

  final List<HexPoint> points;
  final HexBounds bounds;
  final double horizontalShift;

  HexPoint get topLeft => points[0];
  HexPoint get topRight => points[1];
  HexPoint get bottomRight => points[2];
  HexPoint get bottomLeft => points[3];
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

  static int distanceBetween(AxialCoordinate start, AxialCoordinate end) {
    return distance(AxialCoordinate(q: end.q - start.q, r: end.r - start.r));
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

  static HexParallelogram parallelogram(
    HexBounds bounds, {
    double acuteAngleDegrees = 72,
  }) {
    final safeAngle = acuteAngleDegrees.isFinite
        ? acuteAngleDegrees.clamp(1, 179).toDouble()
        : 72.0;
    final shift = bounds.height / math.tan(safeAngle * math.pi / 180);
    final halfShift = shift / 2;
    final points = <HexPoint>[
      HexPoint(bounds.minX - halfShift, bounds.minY),
      HexPoint(bounds.maxX - halfShift, bounds.minY),
      HexPoint(bounds.maxX + halfShift, bounds.maxY),
      HexPoint(bounds.minX + halfShift, bounds.maxY),
    ];
    return HexParallelogram(
      points: points,
      bounds: HexBounds.from(points),
      horizontalShift: shift,
    );
  }

  /// Normalized Chebyshev-like distance in the target parallelogram basis.
  static double parallelogramScore(
    HexPoint point,
    HexBounds bounds, {
    HexParallelogram? geometry,
    double acuteAngleDegrees = 72,
  }) {
    final target =
        geometry ?? parallelogram(bounds, acuteAngleDegrees: acuteAngleDegrees);
    final width = math.max(1.0, bounds.width);
    final height = math.max(1.0, bounds.height);
    final v = (point.y - target.topLeft.y) / height;
    final u = (point.x - target.topLeft.x - v * target.horizontalShift) / width;
    return math.max((u - 0.5).abs() / 0.5, (v - 0.5).abs() / 0.5);
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
    this.frameAcuteAngleDegrees = 72,
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
  final double frameAcuteAngleDegrees;

  HexKeyboardConfiguration normalized() => HexKeyboardConfiguration(
    columns: columns.clamp(4, 64),
    rows: rows.clamp(3, 32),
    period: period.clamp(1, 200),
    stepQ: stepQ.clamp(-200, 200),
    stepR: stepR.clamp(-200, 200),
    groupByOctave: groupByOctave,
    radius: 24,
    rotationDegrees: rotationDegrees.clamp(-60, 60),
    frameAcuteAngleDegrees: frameAcuteAngleDegrees.isFinite
        ? frameAcuteAngleDegrees
        : 72,
  );

  @override
  bool operator ==(Object other) {
    return other is HexKeyboardConfiguration &&
        other.columns == columns &&
        other.rows == rows &&
        other.period == period &&
        other.stepQ == stepQ &&
        other.stepR == stepR &&
        other.groupByOctave == groupByOctave &&
        other.radius == radius &&
        other.rotationDegrees == rotationDegrees &&
        other.frameAcuteAngleDegrees == frameAcuteAngleDegrees;
  }

  @override
  int get hashCode => Object.hash(
    columns,
    rows,
    period,
    stepQ,
    stepR,
    groupByOctave,
    radius,
    rotationDegrees,
    frameAcuteAngleDegrees,
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

  String labelForPeriod(int period) {
    final safePeriod = math.max(1, period);
    if (pitchClass != 0) return '$pitchClass';
    return 'C${4 + (step / safePeriod).floor()}';
  }
}

class HexWindowSlot {
  const HexWindowSlot({
    required this.column,
    required this.row,
    required this.key,
  });

  final int column;
  final int row;
  final HexKey key;

  AxialCoordinate get coordinate => key.coordinate;
  HexPoint get center => key.center;
  int get q => coordinate.q;
  int get r => coordinate.r;
  int get s => coordinate.s;
  int get step => key.step;
  int get pitchClass => key.pitchClass;
}

class RotationStats {
  const RotationStats({required this.generated, required this.omitted});

  final int generated;
  final int omitted;
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
  AxialCoordinate get coordinate => AxialCoordinate(q: dq, r: dr);
}

class HexaKeyboardLayout {
  HexaKeyboardLayout({
    required this.configuration,
    required this.cells,
    required this.slots,
    required this.stats,
    required this.periodVectors,
    required this.slotCenterBounds,
    required this.windowBounds,
    required this.windowOutline,
    required this.keyBounds,
    required this.modelBounds,
  }) : cellsByCoordinate = _indexCellsByCoordinate(cells),
       cellsByStep = _indexCellsByStep(cells);

  final HexKeyboardConfiguration configuration;
  final List<HexKey> cells;
  final List<HexWindowSlot> slots;
  final RotationStats stats;
  final List<PeriodVector> periodVectors;
  final HexBounds slotCenterBounds;
  final HexBounds windowBounds;
  final HexParallelogram windowOutline;
  final HexBounds keyBounds;
  final HexBounds modelBounds;
  final Map<AxialCoordinate, HexKey> cellsByCoordinate;
  final Map<int, List<HexKey>> cellsByStep;

  HexKey? get defaultSelection =>
      cellAt(AxialCoordinate.origin) ??
      (cells.isEmpty ? null : cells[cells.length ~/ 2]);

  HexKey? cellAt(AxialCoordinate coordinate) => cellsByCoordinate[coordinate];

  HexKey? nearest(HexPoint point, {double maximumDistance = 32}) {
    if (!point.x.isFinite || !point.y.isFinite || !maximumDistance.isFinite) {
      return null;
    }
    HexKey? result;
    var best = math.max(0, maximumDistance) * math.max(0, maximumDistance);
    for (final cell in cells) {
      final distance = HexGeometry.squaredDistance(point, cell.center);
      if ((result == null && distance <= best) || distance < best) {
        result = cell;
        best = distance;
      }
    }
    return result;
  }

  /// Selects one concrete key for a score/externally-triggered EDO step.
  /// Exact duplicates prefer the key closest to the C4 origin.
  HexKey? keyForStep(int targetStep) {
    final exact = cellsByStep[targetStep];
    if (exact != null && exact.isNotEmpty) return _preferredCell(exact);

    HexKey? result;
    var bestStepDistance = 1 << 30;
    var bestOriginDistance = 1 << 30;
    var bestCenterDistance = double.infinity;
    for (final cell in cells) {
      final stepDistance = (cell.step - targetStep).abs();
      final originDistance = HexGeometry.distance(cell.coordinate);
      final centerDistance = HexGeometry.squaredDistance(
        cell.center,
        const HexPoint(0, 0),
      );
      if (result == null ||
          stepDistance < bestStepDistance ||
          (stepDistance == bestStepDistance &&
              (originDistance < bestOriginDistance ||
                  (originDistance == bestOriginDistance &&
                      centerDistance < bestCenterDistance)))) {
        result = cell;
        bestStepDistance = stepDistance;
        bestOriginDistance = originDistance;
        bestCenterDistance = centerDistance;
      }
    }
    return result;
  }

  static HexKey _preferredCell(List<HexKey> candidates) {
    return candidates.reduce((best, candidate) {
      final bestDistance = HexGeometry.distance(best.coordinate);
      final candidateDistance = HexGeometry.distance(candidate.coordinate);
      if (candidateDistance != bestDistance) {
        return candidateDistance < bestDistance ? candidate : best;
      }
      final bestCenter = HexGeometry.squaredDistance(
        best.center,
        const HexPoint(0, 0),
      );
      final candidateCenter = HexGeometry.squaredDistance(
        candidate.center,
        const HexPoint(0, 0),
      );
      if (candidateCenter != bestCenter) {
        return candidateCenter < bestCenter ? candidate : best;
      }
      if (candidate.coordinate.q != best.coordinate.q) {
        return candidate.coordinate.q < best.coordinate.q ? candidate : best;
      }
      return candidate.coordinate.r < best.coordinate.r ? candidate : best;
    });
  }
}

abstract final class HexTouchHitTester {
  static HexKey? keyAt({
    required HexPoint point,
    required HexaKeyboardLayout layout,
    AxialCoordinate? previousCoordinate,
    double sensitivity = 1.2,
  }) {
    if (layout.cells.isEmpty) return null;

    final safeSensitivity = sensitivity.clamp(1.0, 1.5).toDouble();
    final radius = layout.configuration.radius;
    final previous = previousCoordinate == null
        ? null
        : layout.cellAt(previousCoordinate);
    final captureRadius = radius * safeSensitivity;
    final retentionRadius = radius * (safeSensitivity + _retentionExtraScale);
    final searchRadius = previous == null ? captureRadius : retentionRadius;
    final nearest = layout.nearest(point, maximumDistance: searchRadius);
    if (nearest == null) return null;
    final nearestDistance = math.sqrt(
      HexGeometry.squaredDistance(point, nearest.center),
    );

    if (previous != null) {
      final previousDistance = math.sqrt(
        HexGeometry.squaredDistance(point, previous.center),
      );
      if (previousDistance <= retentionRadius) {
        if (nearest.coordinate == previous.coordinate) return previous;
        final switchMargin = radius * _switchMarginScale;
        if (nearestDistance + switchMargin >= previousDistance) return previous;
      }
    }

    return nearestDistance <= captureRadius ? nearest : null;
  }

  static const _retentionExtraScale = 0.12;
  static const _switchMarginScale = 0.12;
}

abstract final class HexaKeyboardLayoutEngine {
  static HexaKeyboardLayout build([
    HexKeyboardConfiguration configuration = HexKeyboardConfiguration.defaults,
  ]) {
    final normalized = configuration.normalized();
    final baseSlots = _buildWindowSlots(normalized);
    final selectionBounds = HexBounds.from(
      baseSlots.map((slot) => slot.center),
    );
    final selection = _selectCells(
      slots: baseSlots,
      centerBounds: selectionBounds,
      configuration: normalized,
    );
    final slots = _applyOctaveGroupingToSlots(
      baseSlots,
      configuration: normalized,
      rotationDegrees: 0,
    );
    final cells = _applyOctaveGroupingToKeys(
      selection.cells,
      configuration: normalized,
      rotationDegrees: normalized.rotationDegrees,
    );
    final slotCenterBounds = HexBounds.from(slots.map((slot) => slot.center));
    final windowBounds = HexBounds.from(
      slots.map((slot) => slot.center),
      radius: normalized.radius,
    );
    final windowOutline = HexGeometry.parallelogram(
      windowBounds,
      acuteAngleDegrees: normalized.frameAcuteAngleDegrees,
    );
    final keyBounds = HexBounds.from(
      cells.map((cell) => cell.center),
      radius: normalized.radius,
    );

    return HexaKeyboardLayout(
      configuration: normalized,
      cells: List.unmodifiable(cells),
      slots: List.unmodifiable(slots),
      stats: selection.stats,
      periodVectors: List.unmodifiable(periodVectors(normalized)),
      slotCenterBounds: slotCenterBounds,
      windowBounds: windowBounds,
      windowOutline: windowOutline,
      keyBounds: keyBounds,
      modelBounds: HexBounds.merge(keyBounds, [windowOutline.bounds]),
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
    candidates.sort(_comparePeriodVectors);
    final chosen = <PeriodVector>[];
    for (final candidate in candidates) {
      if (chosen.isEmpty) {
        chosen.add(candidate);
        continue;
      }
      final independent = chosen.every(
        (item) => item.dq * candidate.dr - item.dr * candidate.dq != 0,
      );
      final opposite = chosen.any(
        (item) => item.dq == -candidate.dq && item.dr == -candidate.dr,
      );
      if (independent && !opposite) chosen.add(candidate);
      if (chosen.length == 2) break;
    }
    return chosen.isEmpty ? candidates.take(2).toList() : chosen;
  }

  static List<HexWindowSlot> _buildWindowSlots(
    HexKeyboardConfiguration configuration,
  ) {
    final originColumn = (configuration.columns - 1) ~/ 2;
    final originRow = (configuration.rows - 1) ~/ 2;
    final origin = HexGeometry.oddQToAxial(originColumn, originRow);
    final slots = <HexWindowSlot>[];
    for (var column = 0; column < configuration.columns; column++) {
      for (var row = 0; row < configuration.rows; row++) {
        final axial = HexGeometry.oddQToAxial(column, row);
        final coordinate = AxialCoordinate(
          q: axial.q - origin.q,
          r: axial.r - origin.r,
        );
        slots.add(
          HexWindowSlot(
            column: column,
            row: row,
            key: _makeKey(
              coordinate,
              rotate: false,
              configuration: configuration,
            ),
          ),
        );
      }
    }
    return slots;
  }

  static _HexSelection _selectCells({
    required List<HexWindowSlot> slots,
    required HexBounds centerBounds,
    required HexKeyboardConfiguration configuration,
  }) {
    if (configuration.rotationDegrees == 0) {
      return _HexSelection(
        cells: slots.map((slot) => slot.key).toList(),
        stats: const RotationStats(generated: 0, omitted: 0),
      );
    }

    final baseSet = slots.map((slot) => slot.coordinate).toSet();
    final center = centerBounds.center;
    final range =
        (math.sqrt(
                  centerBounds.width * centerBounds.width +
                      centerBounds.height * centerBounds.height,
                ) /
                configuration.radius *
                1.35)
            .ceil() +
        6;
    final target = HexGeometry.parallelogram(
      centerBounds,
      acuteAngleDegrees: configuration.frameAcuteAngleDegrees,
    );
    final candidates = <_HexCandidate>[];
    for (var q = -range; q <= range; q++) {
      for (var r = -range; r <= range; r++) {
        final key = _makeKey(
          AxialCoordinate(q: q, r: r),
          rotate: true,
          configuration: configuration,
        );
        candidates.add(
          _HexCandidate(
            key: key,
            score: HexGeometry.parallelogramScore(
              key.center,
              centerBounds,
              geometry: target,
            ),
            centerDistance: HexGeometry.squaredDistance(key.center, center),
          ),
        );
      }
    }
    candidates.sort(_compareCandidates);
    final cells =
        candidates
            .take(configuration.columns * configuration.rows)
            .map((candidate) => candidate.key)
            .toList()
          ..sort(_compareVisualCells);
    final used = cells.map((cell) => cell.coordinate).toSet();
    return _HexSelection(
      cells: cells,
      stats: RotationStats(
        generated: cells
            .where((cell) => !baseSet.contains(cell.coordinate))
            .length,
        omitted: slots.where((slot) => !used.contains(slot.coordinate)).length,
      ),
    );
  }

  static HexKey _makeKey(
    AxialCoordinate coordinate, {
    required bool rotate,
    required HexKeyboardConfiguration configuration,
  }) {
    final step = PitchMapper.step(
      coordinate,
      stepQ: configuration.stepQ,
      stepR: configuration.stepR,
    );
    final point = HexGeometry.point(coordinate, configuration.radius);
    final center = rotate
        ? HexGeometry.rotate(point, configuration.rotationDegrees.toDouble())
        : point;
    return HexKey(
      coordinate: coordinate,
      step: step,
      pitchClass: positiveModulo(step, configuration.period),
      audioPitch: PitchMapper.pitchForStep(step, configuration.period),
      center: center,
    );
  }

  static List<HexWindowSlot> _applyOctaveGroupingToSlots(
    List<HexWindowSlot> slots, {
    required HexKeyboardConfiguration configuration,
    required int rotationDegrees,
  }) {
    if (!configuration.groupByOctave) return slots;
    final offset = _octaveGroupOffset(configuration, rotationDegrees);
    if (offset == const HexPoint(0, 0)) return slots;
    return [
      for (final slot in slots)
        HexWindowSlot(
          column: slot.column,
          row: slot.row,
          key: _withOctaveGroupOffset(
            slot.key,
            period: configuration.period,
            offset: offset,
          ),
        ),
    ];
  }

  static List<HexKey> _applyOctaveGroupingToKeys(
    List<HexKey> cells, {
    required HexKeyboardConfiguration configuration,
    required int rotationDegrees,
  }) {
    if (!configuration.groupByOctave) return cells;
    final offset = _octaveGroupOffset(configuration, rotationDegrees);
    if (offset == const HexPoint(0, 0)) return cells;
    return [
      for (final cell in cells)
        _withOctaveGroupOffset(
          cell,
          period: configuration.period,
          offset: offset,
        ),
    ]..sort(_compareVisualCells);
  }

  static HexPoint _octaveGroupOffset(
    HexKeyboardConfiguration configuration,
    int rotationDegrees,
  ) {
    final direction = HexPoint(
      (configuration.stepQ * 2 - configuration.stepR).toDouble(),
      math.sqrt(3) * configuration.stepR,
    );
    final magnitude = math.sqrt(
      direction.x * direction.x + direction.y * direction.y,
    );
    if (magnitude == 0) return const HexPoint(0, 0);
    final gap = configuration.radius * _octaveGroupGapRadiusRatio;
    return HexGeometry.rotate(
      HexPoint(direction.x / magnitude * gap, direction.y / magnitude * gap),
      rotationDegrees.toDouble(),
    );
  }

  static HexKey _withOctaveGroupOffset(
    HexKey key, {
    required int period,
    required HexPoint offset,
  }) {
    final octave = (key.step / period).floor();
    if (octave == 0) return key;
    return HexKey(
      coordinate: key.coordinate,
      step: key.step,
      pitchClass: key.pitchClass,
      audioPitch: key.audioPitch,
      center: HexPoint(
        key.center.x + offset.x * octave,
        key.center.y + offset.y * octave,
      ),
    );
  }

  static int _compareCandidates(_HexCandidate first, _HexCandidate second) {
    var result = first.score.compareTo(second.score);
    if (result != 0) return result;
    result = first.centerDistance.compareTo(second.centerDistance);
    if (result != 0) return result;
    result = first.key.coordinate.q.compareTo(second.key.coordinate.q);
    if (result != 0) return result;
    return first.key.coordinate.r.compareTo(second.key.coordinate.r);
  }

  static int _compareVisualCells(HexKey first, HexKey second) {
    final y = first.center.y.compareTo(second.center.y);
    return y != 0 ? y : first.center.x.compareTo(second.center.x);
  }

  static int _comparePeriodVectors(PeriodVector first, PeriodVector second) {
    var result = first.distance.compareTo(second.distance);
    if (result != 0) return result;
    result = (first.dq.abs() + first.dr.abs()).compareTo(
      second.dq.abs() + second.dr.abs(),
    );
    if (result != 0) return result;
    result = first.dq.compareTo(second.dq);
    return result != 0 ? result : first.dr.compareTo(second.dr);
  }

  static const _octaveGroupGapRadiusRatio = 0.5;
}

class _HexCandidate {
  const _HexCandidate({
    required this.key,
    required this.score,
    required this.centerDistance,
  });

  final HexKey key;
  final double score;
  final double centerDistance;
}

class _HexSelection {
  const _HexSelection({required this.cells, required this.stats});

  final List<HexKey> cells;
  final RotationStats stats;
}

Map<AxialCoordinate, HexKey> _indexCellsByCoordinate(List<HexKey> cells) {
  return Map<AxialCoordinate, HexKey>.unmodifiable({
    for (final cell in cells) cell.coordinate: cell,
  });
}

Map<int, List<HexKey>> _indexCellsByStep(List<HexKey> cells) {
  final result = <int, List<HexKey>>{};
  for (final cell in cells) {
    (result[cell.step] ??= <HexKey>[]).add(cell);
  }
  return Map<int, List<HexKey>>.unmodifiable({
    for (final entry in result.entries)
      entry.key: List<HexKey>.unmodifiable(entry.value),
  });
}
