import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../app/xensynth_settings.dart';
import '../../core/score.dart';
import '../app_palette.dart';
import '../waterfall/waterfall_view.dart';

class HexKeyboardView extends StatefulWidget {
  const HexKeyboardView({
    required this.score,
    required this.playhead,
    required this.settings,
    required this.activePitches,
    required this.onPitchDown,
    required this.onPitchMove,
    required this.onPitchUp,
    super.key,
  });

  final ParsedScore? score;
  final double playhead;
  final XenSynthSettings settings;
  final Map<int, double> activePitches;
  final PitchPointerCallback onPitchDown;
  final PitchPointerCallback onPitchMove;
  final ValueChanged<int> onPitchUp;

  @override
  State<HexKeyboardView> createState() => _HexKeyboardViewState();
}

class _HexKeyboardViewState extends State<HexKeyboardView> {
  final Map<int, _HexCell> _pointerCells = {};

  @override
  Widget build(BuildContext context) {
    final cells = _buildCells(widget.settings);
    return LayoutBuilder(
      builder: (context, constraints) {
        final transform = _HexTransform.fit(cells, constraints.biggest);
        return Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: (event) =>
              _processPointer(event, cells, transform, true),
          onPointerMove: (event) =>
              _processPointer(event, cells, transform, false),
          onPointerUp: (event) => _release(event.pointer),
          onPointerCancel: (event) => _release(event.pointer),
          child: RepaintBoundary(
            child: CustomPaint(
              painter: _HexKeyboardPainter(
                cells: cells,
                transform: transform,
                score: widget.score,
                playhead: widget.playhead,
                previewSeconds: widget.settings.playbackPreviewSeconds,
                period: widget.settings.hexPeriod,
                activePitches: widget.activePitches.values.toSet(),
              ),
              size: Size.infinite,
            ),
          ),
        );
      },
    );
  }

  void _processPointer(
    PointerEvent event,
    List<_HexCell> cells,
    _HexTransform transform,
    bool down,
  ) {
    final point = transform.toModel(event.localPosition);
    final previous = _pointerCells[event.pointer];
    final nearest = _nearestCell(point, cells, previous: previous);
    if (nearest == null || identical(nearest, previous)) return;
    _pointerCells[event.pointer] = nearest;
    final pressure = event.pressureMax > event.pressureMin
        ? ((event.pressure - event.pressureMin) /
                  (event.pressureMax - event.pressureMin))
              .clamp(0.0, 1.0)
        : 0.72;
    final velocity = (24 + pressure * 103).round().clamp(1, 127);
    final pitch = 60 + nearest.step * 12 / widget.settings.hexPeriod;
    if (down || previous == null) {
      widget.onPitchDown(event.pointer, pitch, velocity);
    } else {
      widget.onPitchMove(event.pointer, pitch, velocity);
    }
  }

  void _release(int pointer) {
    if (_pointerCells.remove(pointer) != null) widget.onPitchUp(pointer);
  }

  static _HexCell? _nearestCell(
    Offset point,
    List<_HexCell> cells, {
    _HexCell? previous,
  }) {
    _HexCell? nearest;
    var best = double.infinity;
    for (final cell in cells) {
      final distance = (cell.center - point).distanceSquared;
      if (distance < best) {
        best = distance;
        nearest = cell;
      }
    }
    final limit = previous == null ? 27.5 * 27.5 : 31.5 * 31.5;
    return best <= limit ? nearest : null;
  }

  static List<_HexCell> _buildCells(XenSynthSettings settings) {
    const radius = 24.0;
    final cells = <_HexCell>[];
    final centerColumn = (settings.hexColumns - 1) / 2;
    final centerRow = (settings.hexRows - 1) / 2;
    final angle = settings.hexRotationDegrees * math.pi / 180;
    final cosine = math.cos(angle);
    final sine = math.sin(angle);
    for (var column = 0; column < settings.hexColumns; column++) {
      for (var row = 0; row < settings.hexRows; row++) {
        final q = column - centerColumn.round();
        final r = row - ((column - (column & 1)) ~/ 2) - centerRow.round();
        var x = radius * 1.5 * q;
        var y = radius * math.sqrt(3) * (r + q / 2);
        final rotatedX = x * cosine - y * sine;
        final rotatedY = x * sine + y * cosine;
        x = rotatedX;
        y = rotatedY;
        final step = q * settings.hexStepQ + r * settings.hexStepR;
        cells.add(
          _HexCell(
            q: q,
            r: r,
            step: step,
            pitchClass: _positiveModulo(step, settings.hexPeriod),
            center: Offset(x, y),
          ),
        );
      }
    }
    if (!settings.hexGroupByOctave) return cells;
    return [
      for (final cell in cells)
        cell.copyWith(
          center:
              cell.center +
              Offset(
                (cell.step / settings.hexPeriod).floor() * 5.5,
                (cell.step / settings.hexPeriod).floor() * -2.5,
              ),
        ),
    ];
  }

  static int _positiveModulo(int value, int modulus) {
    final safeModulus = modulus <= 0 ? 1 : modulus;
    final result = value % safeModulus;
    return result < 0 ? result + safeModulus : result;
  }
}

class _HexCell {
  const _HexCell({
    required this.q,
    required this.r,
    required this.step,
    required this.pitchClass,
    required this.center,
  });

  final int q;
  final int r;
  final int step;
  final int pitchClass;
  final Offset center;

  _HexCell copyWith({Offset? center}) => _HexCell(
    q: q,
    r: r,
    step: step,
    pitchClass: pitchClass,
    center: center ?? this.center,
  );
}

class _HexTransform {
  const _HexTransform({required this.scale, required this.offset});

  final double scale;
  final Offset offset;

  Offset toScreen(Offset point) => point * scale + offset;
  Offset toModel(Offset point) => (point - offset) / scale;

  static _HexTransform fit(List<_HexCell> cells, Size size) {
    if (cells.isEmpty) {
      return _HexTransform(scale: 1, offset: size.center(Offset.zero));
    }
    var minX = double.infinity;
    var maxX = double.negativeInfinity;
    var minY = double.infinity;
    var maxY = double.negativeInfinity;
    for (final cell in cells) {
      minX = math.min(minX, cell.center.dx - 25);
      maxX = math.max(maxX, cell.center.dx + 25);
      minY = math.min(minY, cell.center.dy - 25);
      maxY = math.max(maxY, cell.center.dy + 25);
    }
    final width = math.max(1, maxX - minX);
    final height = math.max(1, maxY - minY);
    final scale = math.min(
      math.max(1, size.width - 42) / width,
      math.max(1, size.height - 36) / height,
    );
    final contentCenter = Offset((minX + maxX) / 2, (minY + maxY) / 2);
    return _HexTransform(
      scale: scale,
      offset: size.center(Offset.zero) - contentCenter * scale,
    );
  }
}

class _HexKeyboardPainter extends CustomPainter {
  const _HexKeyboardPainter({
    required this.cells,
    required this.transform,
    required this.score,
    required this.playhead,
    required this.previewSeconds,
    required this.period,
    required this.activePitches,
  });

  final List<_HexCell> cells;
  final _HexTransform transform;
  final ParsedScore? score;
  final double playhead;
  final double previewSeconds;
  final int period;
  final Set<double> activePitches;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = AppPalette.background);
    canvas.drawCircle(
      size.center(Offset.zero),
      math.min(size.width, size.height) * 0.42,
      Paint()
        ..shader = RadialGradient(
          colors: [
            AppPalette.accent.withValues(alpha: 0.08),
            AppPalette.background.withValues(alpha: 0),
          ],
        ).createShader(Offset.zero & size),
    );
    final playback = _playbackState();
    final radius = math.max(8.0, 22.6 * transform.scale);
    final labelPainter = TextPainter(textDirection: TextDirection.ltr);
    for (final cell in cells) {
      final center = transform.toScreen(cell.center);
      final path = _hexPath(center, radius, 12);
      final pitch = 60 + cell.step * 12 / math.max(1, period);
      final active = activePitches.any((value) => (value - pitch).abs() < 0.02);
      final visual = playback[cell.pitchClass];
      final base = _cellColor(cell.pitchClass, period);
      canvas.drawPath(path, Paint()..color = base);
      if (visual != null) {
        final alpha = visual.active
            ? 0.68
            : (1 - visual.secondsUntil / math.max(0.1, previewSeconds)) * 0.42;
        canvas.drawPath(
          path,
          Paint()
            ..color = AppPalette.accent.withValues(alpha: alpha.clamp(0, 0.75)),
        );
      }
      if (active) {
        canvas.drawPath(
          path,
          Paint()..color = AppPalette.selection.withValues(alpha: 0.70),
        );
        canvas.drawPath(
          path,
          Paint()
            ..color = AppPalette.selection.withValues(alpha: 0.22)
            ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 9),
        );
      }
      canvas.drawPath(
        path,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = math.max(0.7, transform.scale * 0.9)
          ..color = active ? AppPalette.primaryText : AppPalette.line,
      );
      if (radius >= 12) {
        final label = radius >= 18
            ? '${cell.pitchClass}\n${cell.step}'
            : '${cell.pitchClass}';
        labelPainter.text = TextSpan(
          text: label,
          style: TextStyle(
            color: active ? AppPalette.background : AppPalette.primaryText,
            fontSize: math.min(11, math.max(6.5, radius * 0.32)),
            fontWeight: FontWeight.w700,
            height: 1.02,
          ),
        );
        labelPainter
          ..textAlign = TextAlign.center
          ..layout(maxWidth: radius * 1.55);
        labelPainter.paint(
          canvas,
          center - Offset(labelPainter.width / 2, labelPainter.height / 2),
        );
      }
    }
    _paintLegend(canvas, size);
  }

  Map<int, _PlaybackVisual> _playbackState() {
    final result = <int, _PlaybackVisual>{};
    final notes = score?.notes ?? const <WaterfallNote>[];
    for (final note in notes) {
      if (note.end < playhead || note.start > playhead + previewSeconds) {
        continue;
      }
      final step = ((note.pitch - 60) * period / 12).round();
      final pitchClass = ((step % period) + period) % period;
      final visual = _PlaybackVisual(
        active: note.start <= playhead && note.end > playhead,
        secondsUntil: math.max(0, note.start - playhead),
      );
      final previous = result[pitchClass];
      if (previous == null ||
          visual.active ||
          visual.secondsUntil < previous.secondsUntil) {
        result[pitchClass] = visual;
      }
    }
    return result;
  }

  void _paintLegend(Canvas canvas, Size size) {
    final painter = TextPainter(
      text: TextSpan(
        text: 'HEX · $period EDO   ${cells.length} KEYS',
        style: const TextStyle(
          color: AppPalette.secondaryText,
          fontSize: 9,
          fontWeight: FontWeight.w700,
          letterSpacing: 1,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, Offset(12, size.height - painter.height - 9));
  }

  static Path _hexPath(Offset center, double radius, double rotationDegrees) {
    final path = Path();
    final rotation = rotationDegrees * math.pi / 180;
    for (var index = 0; index < 6; index++) {
      final angle = rotation + index * math.pi / 3;
      final point = center + Offset(math.cos(angle), math.sin(angle)) * radius;
      if (index == 0) {
        path.moveTo(point.dx, point.dy);
      } else {
        path.lineTo(point.dx, point.dy);
      }
    }
    return path..close();
  }

  static Color _cellColor(int pitchClass, int period) {
    final hue = (185 + pitchClass / math.max(1, period) * 118) % 360;
    final value = pitchClass == 0 ? 0.42 : 0.28 + (pitchClass % 5) * 0.012;
    return HSVColor.fromAHSV(1, hue, 0.42, value).toColor();
  }

  @override
  bool shouldRepaint(covariant _HexKeyboardPainter oldDelegate) {
    return oldDelegate.cells != cells ||
        oldDelegate.transform.scale != transform.scale ||
        oldDelegate.transform.offset != transform.offset ||
        oldDelegate.score != score ||
        oldDelegate.playhead != playhead ||
        oldDelegate.previewSeconds != previewSeconds ||
        oldDelegate.period != period ||
        oldDelegate.activePitches != activePitches;
  }
}

class _PlaybackVisual {
  const _PlaybackVisual({required this.active, required this.secondsUntil});

  final bool active;
  final double secondsUntil;
}
