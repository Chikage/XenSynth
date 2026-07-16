import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/score.dart';
import '../app_palette.dart';

typedef PitchPointerCallback =
    void Function(int pointer, double pitch, int velocity);

class WaterfallView extends StatefulWidget {
  const WaterfallView({
    required this.score,
    required this.playhead,
    required this.edo,
    required this.pitchOffsetCents,
    required this.activePitches,
    required this.onPitchDown,
    required this.onPitchMove,
    required this.onPitchUp,
    super.key,
  });

  final ParsedScore? score;
  final double playhead;
  final int edo;
  final double pitchOffsetCents;
  final Map<int, double> activePitches;
  final PitchPointerCallback onPitchDown;
  final PitchPointerCallback onPitchMove;
  final ValueChanged<int> onPitchUp;

  @override
  State<WaterfallView> createState() => _WaterfallViewState();
}

class _WaterfallViewState extends State<WaterfallView> {
  static const _minPitchZoom = 87 / 127;
  static const _maxPitchZoom = 7.25;

  double _pixelsPerSecond = 160;
  double _pitchZoom = 1;
  double _pitchPan = 0;
  double _startPixelsPerSecond = 160;
  double _startPitchZoom = 1;
  double _startPitchPan = 0;
  Offset _startFocalPoint = Offset.zero;
  final Map<int, double> _rulerPointers = {};

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final size = constraints.biggest;
        final layout = WaterfallLayout(
          size: size,
          playhead: widget.playhead,
          pixelsPerSecond: _pixelsPerSecond,
          pitchZoom: _pitchZoom,
          pitchPan: _pitchPan,
          offsetCents: widget.pitchOffsetCents,
        );
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onDoubleTap: () => setState(() {
            _pixelsPerSecond = 160;
            _pitchZoom = 1;
            _pitchPan = 0;
          }),
          onScaleStart: (details) {
            _startPixelsPerSecond = _pixelsPerSecond;
            _startPitchZoom = _pitchZoom;
            _startPitchPan = _pitchPan;
            _startFocalPoint = details.localFocalPoint;
          },
          onScaleUpdate: (details) {
            if (_rulerPointers.isNotEmpty) return;
            final horizontalScale = details.horizontalScale.isFinite
                ? details.horizontalScale
                : details.scale;
            final verticalScale = details.verticalScale.isFinite
                ? details.verticalScale
                : details.scale;
            final nextPitchZoom = (_startPitchZoom * horizontalScale)
                .clamp(_minPitchZoom, _maxPitchZoom)
                .toDouble();
            final nextTimeZoom = (_startPixelsPerSecond * verticalScale)
                .clamp(60, 420)
                .toDouble();
            final pitchDelta =
                (details.localFocalPoint.dx - _startFocalPoint.dx) /
                math.max(1, size.width) *
                (87 / nextPitchZoom);
            setState(() {
              _pitchZoom = nextPitchZoom;
              _pixelsPerSecond = nextTimeZoom;
              _pitchPan = _coercePitchPan(
                nextPitchZoom,
                _startPitchPan - pitchDelta,
              );
            });
          },
          child: Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: (event) => _handlePointer(event, layout, true),
            onPointerMove: (event) => _handlePointer(event, layout, false),
            onPointerUp: (event) => _releasePointer(event.pointer),
            onPointerCancel: (event) => _releasePointer(event.pointer),
            child: RepaintBoundary(
              child: CustomPaint(
                painter: WaterfallPainter(
                  score: widget.score,
                  layout: layout,
                  edo: widget.edo,
                  activePitches: widget.activePitches.values.toSet(),
                ),
                size: Size.infinite,
              ),
            ),
          ),
        );
      },
    );
  }

  void _handlePointer(PointerEvent event, WaterfallLayout layout, bool isDown) {
    if (event.localPosition.dy < layout.keyboardTop) return;
    final rawPitch = layout.xToPitch(event.localPosition.dx);
    final pitch = _quantize(rawPitch, widget.edo);
    final depth =
        ((event.localPosition.dy - layout.keyboardTop) /
                math.max(1, layout.keyboardHeight))
            .clamp(0.0, 1.0);
    final velocity = (24 + 103 * depth).round().clamp(1, 127);
    final previous = _rulerPointers[event.pointer];
    if (previous != null && (previous - pitch).abs() < 0.001) return;
    _rulerPointers[event.pointer] = pitch;
    if (isDown || previous == null) {
      widget.onPitchDown(event.pointer, pitch, velocity);
    } else {
      widget.onPitchMove(event.pointer, pitch, velocity);
    }
  }

  void _releasePointer(int pointer) {
    if (_rulerPointers.remove(pointer) != null) {
      widget.onPitchUp(pointer);
    }
  }

  static double _quantize(double pitch, int edo) {
    if (edo <= 0) return (pitch * 100).round() / 100;
    final step = ((pitch - 60) * edo / 12).round();
    return 60 + step * 12 / edo;
  }

  static double _coercePitchPan(double zoom, double pan) {
    final visibleRange = math.min(127.0, 87 / zoom);
    final minPan = visibleRange / 2 - 64.5;
    final maxPan = 127 - visibleRange / 2 - 64.5;
    return pan.clamp(minPan, maxPan).toDouble();
  }
}

class WaterfallLayout {
  const WaterfallLayout({
    required this.size,
    required this.playhead,
    required this.pixelsPerSecond,
    required this.pitchZoom,
    required this.pitchPan,
    required this.offsetCents,
  });

  final Size size;
  final double playhead;
  final double pixelsPerSecond;
  final double pitchZoom;
  final double pitchPan;
  final double offsetCents;

  double get keyboardHeight => math.min(118, math.max(72, size.height * 0.118));
  double get keyboardTop => size.height - keyboardHeight;
  double get visiblePitchRange => math.min(127, 87 / pitchZoom);
  double get visiblePitchMin => 64.5 + pitchPan - visiblePitchRange / 2;
  double get visiblePitchMax => visiblePitchMin + visiblePitchRange;
  double get noteWidth => math.max(2, math.min(7, size.width / 87 * 0.16));

  double timeToY(double time) {
    return keyboardTop - (time - playhead) * pixelsPerSecond;
  }

  double pitchToX(double pitch) {
    return (pitch - visiblePitchMin) / visiblePitchRange * size.width;
  }

  double xToPitch(double x) {
    return visiblePitchMin + x / math.max(1, size.width) * visiblePitchRange;
  }
}

class WaterfallPainter extends CustomPainter {
  const WaterfallPainter({
    required this.score,
    required this.layout,
    required this.edo,
    required this.activePitches,
  });

  final ParsedScore? score;
  final WaterfallLayout layout;
  final int edo;
  final Set<double> activePitches;

  @override
  void paint(Canvas canvas, Size size) {
    _paintBackground(canvas, size);
    _paintPitchGrid(canvas, size);
    _paintTimeGrid(canvas, size);
    _paintNotes(canvas, size);
    _paintKeyboard(canvas, size);
    _paintEmptyState(canvas, size);
  }

  void _paintBackground(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(rect, Paint()..color = AppPalette.background);
    canvas.drawRect(
      Rect.fromLTWH(0, 0, size.width, layout.keyboardTop),
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x151D777B), Color(0x0A40C7CC), Color(0x22235E61)],
          stops: [0, 0.55, 1],
        ).createShader(Rect.fromLTWH(0, 0, size.width, layout.keyboardTop)),
    );
  }

  void _paintPitchGrid(Canvas canvas, Size size) {
    final top = 0.0;
    final bottom = layout.keyboardTop;
    final minPitch = layout.visiblePitchMin.floor();
    final maxPitch = layout.visiblePitchMax.ceil();
    for (var pitch = minPitch; pitch <= maxPitch; pitch++) {
      final x = layout.pitchToX(pitch.toDouble());
      final isC = pitch % 12 == 0;
      final isBlack = const {1, 3, 6, 8, 10}.contains((pitch % 12 + 12) % 12);
      canvas.drawLine(
        Offset(x, top),
        Offset(x, bottom),
        Paint()
          ..color = isC
              ? AppPalette.accent.withValues(alpha: 0.24)
              : AppPalette.line.withValues(alpha: isBlack ? 0.12 : 0.19)
          ..strokeWidth = isC ? 1.1 : 0.55,
      );
    }
    if (edo > 0 && edo != 12) {
      final step = 12 / edo;
      final first = ((layout.visiblePitchMin - 60) / step).floor();
      final last = ((layout.visiblePitchMax - 60) / step).ceil();
      final density = size.width / layout.visiblePitchRange * step;
      if (density >= 3.2) {
        for (var index = first; index <= last; index++) {
          final pitch = 60 + index * step;
          if ((pitch - pitch.round()).abs() < 0.0001) continue;
          final x = layout.pitchToX(pitch);
          canvas.drawLine(
            Offset(x, top),
            Offset(x, bottom),
            Paint()
              ..color = AppPalette.outline.withValues(alpha: 0.12)
              ..strokeWidth = 0.5,
          );
        }
      }
    }
  }

  void _paintTimeGrid(Canvas canvas, Size size) {
    final secondsPerLine = switch (layout.pixelsPerSecond) {
      < 90 => 2.0,
      > 300 => 0.25,
      > 180 => 0.5,
      _ => 1.0,
    };
    final visibleAhead = layout.keyboardTop / layout.pixelsPerSecond;
    final first = (layout.playhead / secondsPerLine).floor() - 2;
    final last = ((layout.playhead + visibleAhead) / secondsPerLine).ceil() + 1;
    final textPainter = TextPainter(textDirection: TextDirection.ltr);
    for (var index = first; index <= last; index++) {
      final second = index * secondsPerLine;
      final y = layout.timeToY(second);
      if (y < 0 || y > layout.keyboardTop) continue;
      final major = (second % 4).abs() < 0.001;
      canvas.drawLine(
        Offset(0, y),
        Offset(size.width, y),
        Paint()
          ..color = AppPalette.line.withValues(alpha: major ? 0.34 : 0.16)
          ..strokeWidth = major ? 1.1 : 0.55,
      );
      if (major && y > 12) {
        textPainter.text = TextSpan(
          text: '${second.toStringAsFixed(0)}s',
          style: const TextStyle(color: AppPalette.secondaryText, fontSize: 8),
        );
        textPainter.layout();
        textPainter.paint(canvas, Offset(5, y - 11));
      }
    }
  }

  void _paintNotes(Canvas canvas, Size size) {
    final notes = score?.notes ?? const <WaterfallNote>[];
    final visibleEnd =
        layout.playhead + layout.keyboardTop / layout.pixelsPerSecond + 0.2;
    final visibleStart = layout.playhead - 1.5;
    for (final note in notes) {
      if (note.end < visibleStart || note.start > visibleEnd) continue;
      final pitch = note.pitch + layout.offsetCents / 100;
      if (pitch < layout.visiblePitchMin - 1 ||
          pitch > layout.visiblePitchMax + 1) {
        continue;
      }
      final x = layout.pitchToX(pitch);
      final yStart = layout.timeToY(note.start);
      final yEnd = layout.timeToY(note.end);
      final top = math.min(yStart, yEnd);
      final bottom = math.max(yStart, yEnd);
      final height = math.max(4.0, bottom - top);
      final velocity = note.velocity.clamp(1, 127) / 127;
      final color = _trackColor(note.track, 0.52 + velocity * 0.40);
      final rect = RRect.fromRectAndRadius(
        Rect.fromCenter(
          center: Offset(x, top + height / 2),
          width: layout.noteWidth + 2.4,
          height: height,
        ),
        const Radius.circular(2),
      );
      canvas.drawRRect(
        rect.inflate(2.2),
        Paint()
          ..color = color.withValues(alpha: 0.14)
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 4),
      );
      canvas.drawRRect(rect, Paint()..color = color);
      canvas.drawLine(
        Offset(x, top + 2),
        Offset(x, top + height - 2),
        Paint()
          ..color = Colors.white.withValues(alpha: 0.44)
          ..strokeWidth = 0.65,
      );
      final active =
          note.start <= layout.playhead && note.end > layout.playhead;
      if (active) {
        _paintImpact(canvas, x, layout.keyboardTop, color, velocity);
      }
    }
  }

  void _paintImpact(
    Canvas canvas,
    double x,
    double y,
    Color color,
    double velocity,
  ) {
    canvas.drawCircle(
      Offset(x, y),
      9 + velocity * 10,
      Paint()
        ..color = color.withValues(alpha: 0.22)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 8),
    );
    canvas.drawCircle(
      Offset(x, y),
      2.2 + velocity * 1.8,
      Paint()..color = color.withValues(alpha: 0.92),
    );
  }

  void _paintKeyboard(Canvas canvas, Size size) {
    final keyboardRect = Rect.fromLTWH(
      0,
      layout.keyboardTop,
      size.width,
      layout.keyboardHeight,
    );
    canvas.drawRect(
      keyboardRect,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xE517201F), Color(0xF20B0F0F)],
        ).createShader(keyboardRect),
    );
    canvas.drawLine(
      Offset(0, layout.keyboardTop),
      Offset(size.width, layout.keyboardTop),
      Paint()
        ..color = AppPalette.primaryText.withValues(alpha: 0.82)
        ..strokeWidth = 1.3,
    );

    final minPitch = layout.visiblePitchMin.floor() - 1;
    final maxPitch = layout.visiblePitchMax.ceil() + 1;
    final textPainter = TextPainter(textDirection: TextDirection.ltr);
    for (var pitch = minPitch; pitch <= maxPitch; pitch++) {
      final x0 = layout.pitchToX(pitch.toDouble());
      final x1 = layout.pitchToX(pitch + 1.0);
      final pitchClass = (pitch % 12 + 12) % 12;
      final black = const {1, 3, 6, 8, 10}.contains(pitchClass);
      final keyRect = Rect.fromLTRB(
        x0,
        layout.keyboardTop,
        x1,
        layout.keyboardTop + layout.keyboardHeight * (black ? 0.63 : 1),
      );
      canvas.drawRect(
        keyRect,
        Paint()
          ..color = black
              ? const Color(0xFF111817)
              : const Color(0xFF24302E).withValues(alpha: 0.72),
      );
      canvas.drawLine(
        Offset(x0, layout.keyboardTop),
        Offset(x0, layout.keyboardTop + layout.keyboardHeight),
        Paint()
          ..color = AppPalette.line.withValues(alpha: 0.62)
          ..strokeWidth = 0.7,
      );
      final active = activePitches.any((value) => (value - pitch).abs() < 0.51);
      if (active) {
        canvas.drawRect(
          keyRect,
          Paint()..color = AppPalette.selection.withValues(alpha: 0.48),
        );
      }
      if (pitchClass == 0 && x1 - x0 >= 15) {
        textPainter.text = TextSpan(
          text: 'C${pitch ~/ 12 - 1}',
          style: const TextStyle(
            color: AppPalette.primaryText,
            fontSize: 8,
            fontWeight: FontWeight.w700,
          ),
        );
        textPainter.layout();
        textPainter.paint(
          canvas,
          Offset(
            (x0 + x1 - textPainter.width) / 2,
            layout.keyboardTop + layout.keyboardHeight - 16,
          ),
        );
      }
    }
  }

  void _paintEmptyState(Canvas canvas, Size size) {
    if (score != null && score!.notes.isNotEmpty) return;
    final painter = TextPainter(
      text: const TextSpan(
        text: 'OPEN A MIDX / MIDI SCORE',
        style: TextStyle(
          color: AppPalette.secondaryText,
          fontSize: 13,
          fontWeight: FontWeight.w700,
          letterSpacing: 1.8,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(
      canvas,
      Offset(
        (size.width - painter.width) / 2,
        (layout.keyboardTop - painter.height) / 2,
      ),
    );
  }

  static Color _trackColor(int track, double opacity) {
    final hue = (track * 57 + 178) % 360;
    return HSVColor.fromAHSV(opacity, hue.toDouble(), 0.68, 0.95).toColor();
  }

  @override
  bool shouldRepaint(covariant WaterfallPainter oldDelegate) {
    return oldDelegate.score != score ||
        oldDelegate.layout.playhead != layout.playhead ||
        oldDelegate.layout.pixelsPerSecond != layout.pixelsPerSecond ||
        oldDelegate.layout.pitchZoom != layout.pitchZoom ||
        oldDelegate.layout.pitchPan != layout.pitchPan ||
        oldDelegate.layout.offsetCents != layout.offsetCents ||
        oldDelegate.edo != edo ||
        oldDelegate.activePitches != activePitches;
  }
}
