import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../../core/midi_parser.dart';
import '../../core/score.dart';
import '../../core/tuning.dart';
import '../app_palette.dart';
import 'edo_scale_guide.dart';
import 'waterfall_particle_system.dart';

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
    this.tuning,
    this.playing = false,
    this.duration = 0,
    this.volumeGain = 0.85,
    this.onTogglePlayback,
    this.onSeekStart,
    this.onSeek,
    this.onSeekEnd,
    this.onVolumeChanged,
    super.key,
  });

  final ParsedScore? score;
  final double playhead;
  final int edo;
  final double pitchOffsetCents;
  final Map<int, double> activePitches;
  final TuningDefinition? tuning;
  final bool playing;
  final double duration;
  final double volumeGain;
  final PitchPointerCallback onPitchDown;
  final PitchPointerCallback onPitchMove;
  final ValueChanged<int> onPitchUp;
  final VoidCallback? onTogglePlayback;
  final VoidCallback? onSeekStart;
  final ValueChanged<double>? onSeek;
  final VoidCallback? onSeekEnd;
  final ValueChanged<double>? onVolumeChanged;

  @override
  State<WaterfallView> createState() => _WaterfallViewState();
}

class _WaterfallViewState extends State<WaterfallView>
    with SingleTickerProviderStateMixin {
  static const _minPitchZoom = 87 / 127;
  static const _maxPitchZoom = 7.25;
  static const _gestureThresholdPixels = 8.0;
  static const _gestureReferenceMinPixels = 80.0;
  static const _pinchReferenceRatio = 0.42;
  static const _timeZoomStep = 10.0;
  static const _pitchZoomStep = 0.01;
  static const _rulerTouchSlop = 18.0;
  static const _rulerActiveTouchSlop = 30.0;
  static const _volumeGestureRange = 1.0;
  static const _particleCursorEpsilon = 0.001;
  static const _maximumEmissionStepSeconds = 0.25;

  double _pixelsPerSecond = 160;
  double _pitchZoom = 1;
  double _pitchPan = 0;
  final Map<int, Offset> _pointerPositions = {};
  final Map<int, double> _rulerPointers = {};
  final Map<int, int> _rulerVelocities = {};
  _WaterfallGesture? _waterfallGesture;
  _WaterfallTap? _rulerPreviewTap;
  final WaterfallParticleSystem _particleSystem = WaterfallParticleSystem();
  final ValueNotifier<int> _particleRepaint = ValueNotifier(0);
  late final Ticker _particleTicker;
  Duration _lastParticleElapsed = Duration.zero;
  WaterfallLayout? _latestLayout;
  int _particleCursor = 0;

  @override
  void initState() {
    super.initState();
    _particleCursor = _findParticleCursor(widget.playhead);
    _particleTicker = createTicker(_advanceParticles);
  }

  @override
  void didUpdateWidget(covariant WaterfallView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.score, widget.score)) {
      _particleSystem.clear();
      _repaintParticles();
      _particleCursor = _findParticleCursor(widget.playhead);
      _stopParticleTickerIfIdle();
      return;
    }

    final delta = widget.playhead - oldWidget.playhead;
    if (widget.playing && delta >= 0 && delta <= _maximumEmissionStepSeconds) {
      _emitHitParticles(oldWidget.playhead, widget.playhead);
    } else if (delta.abs() > _particleCursorEpsilon || !widget.playing) {
      _particleCursor = _findParticleCursor(widget.playhead);
    }
  }

  @override
  void dispose() {
    for (final pointer in _rulerPointers.keys.toList()) {
      widget.onPitchUp(pointer);
    }
    _rulerPointers.clear();
    _rulerVelocities.clear();
    _pointerPositions.clear();
    _particleTicker.dispose();
    _particleRepaint.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final size = constraints.biggest;
        final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
        final layout = WaterfallLayout(
          size: size,
          playhead: widget.playhead,
          pixelsPerSecond: _pixelsPerSecond / math.max(1, devicePixelRatio),
          pitchZoom: _pitchZoom,
          pitchPan: _pitchPan,
          offsetCents: widget.pitchOffsetCents,
          devicePixelRatio: devicePixelRatio,
        );
        _latestLayout = layout;
        return Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: (event) => _handlePointerDown(event, layout),
          onPointerMove: (event) => _handlePointerMove(event, layout),
          onPointerUp: (event) => _handlePointerUp(event, layout),
          onPointerCancel: (_) => _cancelTouchState(),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                height: layout.keyboardHeight,
                child: ClipRect(
                  child: BackdropFilter(
                    filter: ui.ImageFilter.blur(
                      sigmaX: 42 * layout.physicalPixel,
                      sigmaY: 42 * layout.physicalPixel,
                    ),
                    child: const ColoredBox(color: Color(0x01000000)),
                  ),
                ),
              ),
              RepaintBoundary(
                child: CustomPaint(
                  painter: WaterfallPainter(
                    score: widget.score,
                    layout: layout,
                    edo: widget.edo,
                    tuning: widget.tuning,
                    activePitches: widget.activePitches.values.toSet(),
                  ),
                  size: Size.infinite,
                ),
              ),
              RepaintBoundary(
                child: CustomPaint(
                  painter: _WaterfallParticlePainter(
                    layout: layout,
                    edo: widget.edo,
                    tuning: widget.tuning,
                    particles: _particleSystem.particles,
                    impacts: _particleSystem.impacts,
                    repaint: _particleRepaint,
                  ),
                  size: Size.infinite,
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  void _handlePointerDown(PointerDownEvent event, WaterfallLayout layout) {
    _pointerPositions[event.pointer] = event.localPosition;
    final firstPointer = _pointerPositions.length == 1;
    if (_startRulerPreview(event, layout)) {
      _waterfallGesture?.tapActive = false;
      _rulerPreviewTap?.active = false;
      if (firstPointer) {
        _waterfallGesture = null;
        _rulerPreviewTap = _beginWaterfallTap(
          event.pointer,
          event.localPosition,
          layout,
        );
      }
      return;
    }

    if (firstPointer) {
      _rulerPreviewTap = null;
      _waterfallGesture = _beginWaterfallGesture(
        event.pointer,
        event.localPosition,
        layout,
      );
      return;
    }

    final previousGesture = _waterfallGesture;
    previousGesture?.tapActive = false;
    _rulerPreviewTap?.active = false;
    final pinch = _beginWaterfallPinch(layout);
    if (pinch != null) {
      _endSeekGestureIfNeeded(previousGesture);
      _waterfallGesture = pinch;
    }
  }

  void _handlePointerMove(PointerMoveEvent event, WaterfallLayout layout) {
    if (!_pointerPositions.containsKey(event.pointer)) return;
    _pointerPositions[event.pointer] = event.localPosition;
    if (_rulerPointers.containsKey(event.pointer)) {
      _updateRulerPreview(event, layout);
    }
    _updateRulerPreviewTap(layout);
    final gesture = _waterfallGesture;
    if (gesture == null) return;
    if (gesture.pinch != null) {
      _updateWaterfallPinch(gesture, layout);
    } else {
      _updateWaterfallDrag(gesture, layout);
    }
  }

  void _handlePointerUp(PointerUpEvent event, WaterfallLayout layout) {
    if (!_pointerPositions.containsKey(event.pointer)) return;
    _pointerPositions[event.pointer] = event.localPosition;
    final lastPointer = _pointerPositions.length == 1;
    _releaseRulerPointer(event.pointer);
    if (lastPointer) {
      _finishWaterfallTouch(event.pointer, event.localPosition, layout);
    } else {
      if (_rulerPreviewTap?.pointerId == event.pointer) {
        _rulerPreviewTap = null;
      }
      final gesture = _waterfallGesture;
      if (gesture?.pinch != null || gesture?.pointerId == event.pointer) {
        _endSeekGestureIfNeeded(gesture);
        _waterfallGesture = null;
      } else {
        gesture?.tapActive = false;
      }
    }
    _pointerPositions.remove(event.pointer);
  }

  bool _startRulerPreview(PointerDownEvent event, WaterfallLayout layout) {
    final sample = _sampleRuler(event.localPosition, layout, active: false);
    if (sample == null) return false;
    _releaseRulerPointer(event.pointer);
    _rulerPointers[event.pointer] = sample.pitch;
    _rulerVelocities[event.pointer] = sample.velocity;
    _spawnHitParticles(
      pitch: sample.pitch,
      velocity: sample.velocity,
      track: 0,
      layout: layout,
    );
    widget.onPitchDown(event.pointer, sample.pitch, sample.velocity);
    return true;
  }

  void _updateRulerPreview(PointerMoveEvent event, WaterfallLayout layout) {
    final previous = _rulerPointers[event.pointer];
    if (previous == null) return;
    final sample = _sampleRuler(
      event.localPosition,
      layout,
      active: true,
      previousPitch: previous,
    );
    if (sample == null) {
      _releaseRulerPointer(event.pointer);
      return;
    }
    if ((previous - sample.pitch).abs() < 0.06) return;
    final velocity = _rulerVelocities[event.pointer] ?? sample.velocity;
    _rulerPointers[event.pointer] = sample.pitch;
    _spawnHitParticles(
      pitch: sample.pitch,
      velocity: velocity,
      track: 0,
      layout: layout,
    );
    widget.onPitchMove(event.pointer, sample.pitch, velocity);
  }

  _RulerSample? _sampleRuler(
    Offset position,
    WaterfallLayout layout, {
    required bool active,
    double? previousPitch,
  }) {
    final slop = active ? _rulerActiveTouchSlop : _rulerTouchSlop;
    if (position.dx < -slop ||
        position.dx > layout.size.width + slop ||
        position.dy < layout.keyboardTop - slop ||
        position.dy > layout.size.height + slop) {
      return null;
    }
    final clampedX = position.dx.clamp(0.0, layout.size.width).toDouble();
    final clampedY = position.dy
        .clamp(layout.keyboardTop, layout.size.height)
        .toDouble();
    final rawPitch = layout.xToPitch(clampedX);
    final snappedPitch = widget.tuning == null
        ? EdoScaleGuide.snapPitch(widget.edo, rawPitch)
        : EdoScaleGuide.snapCustomPitch(widget.tuning!, rawPitch);
    final pitch =
        (widget.tuning == null
                ? EdoScaleGuide.stabilizeEdoPitch(
                    edo: widget.edo,
                    rawPitch: rawPitch,
                    snappedPitch: snappedPitch,
                    previousPitch: previousPitch,
                  )
                : EdoScaleGuide.stabilizeCustomPitch(
                    rawPitch: rawPitch,
                    snappedPitch: snappedPitch,
                    previousPitch: previousPitch,
                  ))
            .clamp(0.0, 127.0)
            .toDouble();
    final velocityDepth = math.max(1, layout.keyboardHeight * 0.8);
    final depth = ((clampedY - layout.keyboardTop) / velocityDepth).clamp(
      0.0,
      1.0,
    );
    final velocity = (24 + 103 * math.sqrt(depth)).round().clamp(1, 127);
    return _RulerSample(pitch: pitch, velocity: velocity);
  }

  void _releaseRulerPointer(int pointer) {
    _rulerVelocities.remove(pointer);
    if (_rulerPointers.remove(pointer) != null) widget.onPitchUp(pointer);
  }

  _WaterfallTap? _beginWaterfallTap(
    int pointerId,
    Offset position,
    WaterfallLayout layout,
  ) {
    if (!_isInsideWaterfall(position, layout)) return null;
    return _WaterfallTap(pointerId: pointerId, start: position);
  }

  void _updateRulerPreviewTap(WaterfallLayout layout) {
    final tap = _rulerPreviewTap;
    if (tap == null) return;
    final position = _pointerPositions[tap.pointerId];
    if (position == null) {
      tap.active = false;
      return;
    }
    final delta = position - tap.start;
    if (math.max(delta.dx.abs(), delta.dy.abs()) > _gestureThreshold(layout)) {
      tap.active = false;
    }
  }

  _WaterfallGesture? _beginWaterfallGesture(
    int pointerId,
    Offset position,
    WaterfallLayout layout,
  ) {
    if (!_isInsideWaterfall(position, layout)) return null;
    return _WaterfallGesture(
      pointerId: pointerId,
      start: position,
      startPitchZoom: _pitchZoom,
      startPitchPan: _pitchPan,
      startPlayhead: widget.playhead,
      startVolumeGain: widget.volumeGain,
      tapActive: true,
      rightHalf: position.dx >= layout.size.width / 2,
      moveReference: math.max(_gestureReferenceMin(layout), layout.size.width),
      volumeReference: math.max(
        _gestureReferenceMin(layout),
        layout.keyboardTop,
      ),
      seekReference: math.max(
        _gestureReferenceMin(layout),
        layout.pixelsPerSecond,
      ),
    );
  }

  _WaterfallGesture? _beginWaterfallPinch(WaterfallLayout layout) {
    final points = _pointerPositions.entries
        .where((entry) => _isInsideWaterfall(entry.value, layout))
        .take(2)
        .toList();
    if (points.length < 2) return null;
    final first = points[0];
    final second = points[1];
    final reference = math.max(
      _gestureReferenceMin(layout),
      math.min(layout.size.width, layout.size.height) * _pinchReferenceRatio,
    );
    return _WaterfallGesture(
      pointerId: first.key,
      start: first.value,
      startPitchZoom: _pitchZoom,
      startPitchPan: _pitchPan,
      startPlayhead: widget.playhead,
      startVolumeGain: widget.volumeGain,
      tapActive: false,
      rightHalf: false,
      moveReference: math.max(_gestureReferenceMin(layout), layout.size.width),
      volumeReference: math.max(
        _gestureReferenceMin(layout),
        layout.keyboardTop,
      ),
      seekReference: math.max(
        _gestureReferenceMin(layout),
        layout.pixelsPerSecond,
      ),
      handled: true,
      pinch: _WaterfallPinch(
        firstPointerId: first.key,
        secondPointerId: second.key,
        startAbsDx: (second.value.dx - first.value.dx).abs(),
        startAbsDy: (second.value.dy - first.value.dy).abs(),
        startPitchAxis: _axisFromPitchZoom(_pitchZoom),
        startTimeAxis: _zoomAxisFromPixelsPerSecond(_pixelsPerSecond),
        reference: reference,
      ),
    );
  }

  void _updateWaterfallPinch(
    _WaterfallGesture gesture,
    WaterfallLayout layout,
  ) {
    final pinch = gesture.pinch;
    if (pinch == null) return;
    final first = _pointerPositions[pinch.firstPointerId];
    final second = _pointerPositions[pinch.secondPointerId];
    if (first == null ||
        second == null ||
        !_isInsideWaterfall(first, layout) ||
        !_isInsideWaterfall(second, layout)) {
      return;
    }
    final dxDelta = (second.dx - first.dx).abs() - pinch.startAbsDx;
    final dyDelta = (second.dy - first.dy).abs() - pinch.startAbsDy;
    if (pinch.axis == null) {
      if (math.max(dxDelta.abs(), dyDelta.abs()) < _gestureThreshold(layout)) {
        return;
      }
      pinch.axis = dxDelta.abs() >= dyDelta.abs()
          ? _WaterfallPinchAxis.pitch
          : _WaterfallPinchAxis.time;
    }
    switch (pinch.axis) {
      case _WaterfallPinchAxis.pitch:
        final nextPitchZoom = _pitchZoomFromAxis(
          (pinch.startPitchAxis + dxDelta / pinch.reference).clamp(-1.0, 1.0),
        );
        setState(() {
          _pitchZoom = nextPitchZoom;
          _pitchPan = _coercePitchPan(nextPitchZoom, gesture.startPitchPan);
        });
        return;
      case _WaterfallPinchAxis.time:
        final nextPixelsPerSecond = _pixelsPerSecondFromZoomAxis(
          (pinch.startTimeAxis + dyDelta / pinch.reference).clamp(-1.0, 1.0),
        );
        setState(() => _pixelsPerSecond = nextPixelsPerSecond);
        return;
      case null:
        return;
    }
  }

  void _updateWaterfallDrag(_WaterfallGesture gesture, WaterfallLayout layout) {
    final position = _pointerPositions[gesture.pointerId];
    if (position == null) return;
    final delta = position - gesture.start;
    if (math.max(delta.dx.abs(), delta.dy.abs()) > _gestureThreshold(layout)) {
      gesture.tapActive = false;
    }
    if (!_isInsideWaterfall(position, layout)) return;
    if (gesture.mode == null) {
      if (math.max(delta.dx.abs(), delta.dy.abs()) <
          _gestureThreshold(layout)) {
        return;
      }
      gesture.mode = delta.dy.abs() > delta.dx.abs()
          ? (gesture.rightHalf
                ? _WaterfallDragMode.volume
                : _WaterfallDragMode.seek)
          : _WaterfallDragMode.move;
      if (gesture.mode == _WaterfallDragMode.seek) {
        widget.onSeekStart?.call();
      }
      gesture.handled = true;
    }
    switch (gesture.mode) {
      case _WaterfallDragMode.volume:
        final nextVolume =
            (gesture.startVolumeGain -
                    delta.dy / gesture.volumeReference * _volumeGestureRange)
                .clamp(0.0, 1.0)
                .toDouble();
        widget.onVolumeChanged?.call(nextVolume);
        return;
      case _WaterfallDragMode.move:
        final panRange = _pitchPanRange(gesture.startPitchZoom);
        final nextPitchPan = _coercePitchPan(
          gesture.startPitchZoom,
          gesture.startPitchPan - delta.dx / gesture.moveReference * panRange,
        );
        setState(() => _pitchPan = nextPitchPan);
        return;
      case _WaterfallDragMode.seek:
        widget.onSeek?.call(
          (gesture.startPlayhead + delta.dy / gesture.seekReference)
              .clamp(0.0, widget.duration)
              .toDouble(),
        );
        return;
      case null:
        return;
    }
  }

  void _finishWaterfallTouch(
    int pointerId,
    Offset position,
    WaterfallLayout layout,
  ) {
    final gesture = _waterfallGesture;
    final tap = _rulerPreviewTap;
    _endSeekGestureIfNeeded(gesture);
    _waterfallGesture = null;
    _rulerPreviewTap = null;
    if (gesture?.pinch != null) return;
    final waterfallTap =
        (gesture != null &&
            gesture.pointerId == pointerId &&
            gesture.tapActive &&
            !gesture.handled &&
            _isInsideWaterfall(position, layout)) ||
        (tap != null &&
            tap.pointerId == pointerId &&
            tap.active &&
            _isInsideWaterfall(position, layout));
    if (waterfallTap) widget.onTogglePlayback?.call();
  }

  void _endSeekGestureIfNeeded(_WaterfallGesture? gesture) {
    if (gesture?.mode == _WaterfallDragMode.seek) {
      widget.onSeekEnd?.call();
    }
  }

  void _cancelTouchState() {
    for (final pointer in _rulerPointers.keys.toList()) {
      _releaseRulerPointer(pointer);
    }
    _endSeekGestureIfNeeded(_waterfallGesture);
    _waterfallGesture = null;
    _rulerPreviewTap = null;
    _pointerPositions.clear();
  }

  bool _isInsideWaterfall(Offset position, WaterfallLayout layout) {
    return position.dx >= 0 &&
        position.dx <= layout.size.width &&
        position.dy >= 0 &&
        position.dy <= layout.keyboardTop;
  }

  double _gestureThreshold(WaterfallLayout layout) {
    return _gestureThresholdPixels * layout.physicalPixel;
  }

  double _gestureReferenceMin(WaterfallLayout layout) {
    return _gestureReferenceMinPixels * layout.physicalPixel;
  }

  static double _pitchPanRange(double zoom) {
    return math.max(0, 127 - math.min(127.0, 87 / zoom));
  }

  static double _pixelsPerSecondFromZoomAxis(double axis) {
    final value = axis < 0 ? 160 + axis * (160 - 60) : 160 + axis * (420 - 160);
    return _roundToStep(value.clamp(60.0, 420.0), _timeZoomStep);
  }

  static double _zoomAxisFromPixelsPerSecond(double value) {
    final pixels = value.clamp(60.0, 420.0);
    return pixels < 160
        ? (pixels - 160) / (160 - 60)
        : (pixels - 160) / (420 - 160);
  }

  static double _pitchZoomFromAxis(double axis) {
    final value = axis < 0
        ? 1 + axis * (1 - _minPitchZoom)
        : 1 + axis * (_maxPitchZoom - 1);
    return _roundToStep(
      value.clamp(_minPitchZoom, _maxPitchZoom),
      _pitchZoomStep,
    );
  }

  static double _axisFromPitchZoom(double value) {
    final zoom = value.clamp(_minPitchZoom, _maxPitchZoom);
    return zoom < 1
        ? (zoom - 1) / (1 - _minPitchZoom)
        : (zoom - 1) / (_maxPitchZoom - 1);
  }

  static double _roundToStep(num value, double step) {
    return (value / step).round() * step;
  }

  static double _coercePitchPan(double zoom, double pan) {
    final visibleRange = math.min(127.0, 87 / zoom);
    final minPan = visibleRange / 2 - 64.5;
    final maxPan = 127 - visibleRange / 2 - 64.5;
    return pan.clamp(minPan, maxPan).toDouble();
  }

  void _emitHitParticles(double previousPlayhead, double currentPlayhead) {
    final notes = widget.score?.notes;
    final layout = _latestLayout;
    if (notes == null || notes.isEmpty || layout == null) return;
    if (currentPlayhead < previousPlayhead) {
      _particleCursor = _findParticleCursor(currentPlayhead);
      return;
    }
    while (_particleCursor < notes.length &&
        notes[_particleCursor].start <
            previousPlayhead - _particleCursorEpsilon) {
      _particleCursor++;
    }
    var spawned = false;
    while (_particleCursor < notes.length &&
        notes[_particleCursor].start <=
            currentPlayhead + _particleCursorEpsilon) {
      final note = notes[_particleCursor];
      _spawnHitParticles(
        pitch: note.pitch + widget.pitchOffsetCents / 100,
        velocity: note.velocity,
        track: note.track,
        noteDurationSeconds: note.duration,
        layout: layout,
        notify: false,
      );
      spawned = true;
      _particleCursor++;
    }
    if (spawned) {
      _repaintParticles();
      _startParticleTicker();
    }
  }

  int _findParticleCursor(double playhead) {
    final notes = widget.score?.notes;
    if (notes == null || notes.isEmpty) return 0;
    var low = 0;
    var high = notes.length;
    final target = playhead - _particleCursorEpsilon;
    while (low < high) {
      final middle = (low + high) >> 1;
      if (notes[middle].start < target) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  void _spawnHitParticles({
    required double pitch,
    required int velocity,
    required int track,
    required WaterfallLayout layout,
    double? noteDurationSeconds,
    bool notify = true,
  }) {
    _particleSystem.spawn(
      pitch: pitch,
      x: layout.pitchToX(pitch),
      y: layout.keyboardTop,
      noteWidth: layout.noteWidth,
      pixelScale: layout.physicalPixel,
      velocity: velocity,
      track: track,
      noteDurationSeconds: noteDurationSeconds,
    );
    if (notify) {
      _repaintParticles();
      _startParticleTicker();
    }
  }

  void _startParticleTicker() {
    if (_particleTicker.isActive) return;
    _lastParticleElapsed = Duration.zero;
    _particleTicker.start();
  }

  void _advanceParticles(Duration elapsed) {
    final delta = (elapsed - _lastParticleElapsed).inMicroseconds / 1000000;
    _lastParticleElapsed = elapsed;
    if (_particleSystem.advance(delta)) {
      _repaintParticles();
    }
    _stopParticleTickerIfIdle();
  }

  void _repaintParticles() {
    _particleRepaint.value++;
  }

  void _stopParticleTickerIfIdle() {
    if (!_particleSystem.isEmpty || !_particleTicker.isActive) return;
    _particleTicker.stop();
    _lastParticleElapsed = Duration.zero;
  }
}

class _RulerSample {
  const _RulerSample({required this.pitch, required this.velocity});

  final double pitch;
  final int velocity;
}

class _WaterfallTap {
  _WaterfallTap({required this.pointerId, required this.start});

  final int pointerId;
  final Offset start;
  bool active = true;
}

class _WaterfallGesture {
  _WaterfallGesture({
    required this.pointerId,
    required this.start,
    required this.startPitchZoom,
    required this.startPitchPan,
    required this.startPlayhead,
    required this.startVolumeGain,
    required this.tapActive,
    required this.rightHalf,
    required this.moveReference,
    required this.volumeReference,
    required this.seekReference,
    this.handled = false,
    this.pinch,
  });

  final int pointerId;
  final Offset start;
  final double startPitchZoom;
  final double startPitchPan;
  final double startPlayhead;
  final double startVolumeGain;
  bool tapActive;
  final bool rightHalf;
  final double moveReference;
  final double volumeReference;
  final double seekReference;
  _WaterfallDragMode? mode;
  bool handled;
  final _WaterfallPinch? pinch;
}

class _WaterfallPinch {
  _WaterfallPinch({
    required this.firstPointerId,
    required this.secondPointerId,
    required this.startAbsDx,
    required this.startAbsDy,
    required this.startPitchAxis,
    required this.startTimeAxis,
    required this.reference,
  });

  final int firstPointerId;
  final int secondPointerId;
  final double startAbsDx;
  final double startAbsDy;
  final double startPitchAxis;
  final double startTimeAxis;
  final double reference;
  _WaterfallPinchAxis? axis;
}

enum _WaterfallDragMode { move, volume, seek }

enum _WaterfallPinchAxis { pitch, time }

class WaterfallLayout {
  const WaterfallLayout({
    required this.size,
    required this.playhead,
    required this.pixelsPerSecond,
    required this.pitchZoom,
    required this.pitchPan,
    required this.offsetCents,
    this.devicePixelRatio = 1,
  });

  final Size size;
  final double playhead;
  final double pixelsPerSecond;
  final double pitchZoom;
  final double pitchPan;
  final double offsetCents;
  final double devicePixelRatio;

  double get keyboardHeight => math.min(118, math.max(72, size.height * 0.118));
  double get keyboardTop => size.height - keyboardHeight;
  double get visiblePitchRange => math.min(127, 87 / pitchZoom);
  double get visiblePitchMin => 64.5 + pitchPan - visiblePitchRange / 2;
  double get visiblePitchMax => visiblePitchMin + visiblePitchRange;
  double get physicalPixel => 1 / math.max(1, devicePixelRatio);
  double get noteWidth => math.max(
    2 * physicalPixel,
    math.min(7 * physicalPixel, size.width / 87 * 0.16),
  );

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
    required this.tuning,
    required this.activePitches,
  });

  static const _trackHues = <double>[190, 28, 132, 48, 264, 158, 330, 88];
  static const _rulerGold = Color(0xFFFFDE6F);
  static final List<List<_NoteTone>> _noteTones = [
    for (final hue in _trackHues)
      [
        for (var velocity = 0; velocity <= 127; velocity++)
          _tone(hue, velocity),
      ],
  ];

  final ParsedScore? score;
  final WaterfallLayout layout;
  final int edo;
  final TuningDefinition? tuning;
  final Set<double> activePitches;

  @override
  void paint(Canvas canvas, Size size) {
    _paintBackground(canvas, size);
    _paintPitchGrid(canvas, size);
    _paintMeasures(canvas, size);
    _paintNotes(canvas, size);
    _paintPlayhead(canvas, size);
    _paintRulerGlass(canvas, size);
    _paintRulerTicks(canvas, size);
    _paintEmptyState(canvas, size);
  }

  void _paintBackground(Canvas canvas, Size size) {
    final waterfallRect = Rect.fromLTWH(0, 0, size.width, layout.keyboardTop);
    canvas.drawRect(
      waterfallRect,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x24040909), Color(0x1E141E1D), Color(0x2E050A0A)],
          stops: [0, 0.55, 1],
        ).createShader(waterfallRect),
    );
    canvas.drawRect(
      waterfallRect,
      Paint()
        ..shader = RadialGradient(
          center: const Alignment(0, 1.2),
          radius: 1.25,
          colors: [
            AppPalette.accent.withValues(alpha: 0.08),
            Colors.transparent,
          ],
        ).createShader(waterfallRect),
    );
  }

  void _paintPitchGrid(Canvas canvas, Size size) {
    final minimumSpacing =
        layout.visiblePitchRange *
        1.35 *
        layout.physicalPixel /
        math.max(1, size.width);
    final gridPaint = Paint()..isAntiAlias = false;
    for (final line in _scaleLines(minimumSpacing)) {
      final x = layout.pitchToX(line.pitch);
      if (x < -1 || x > size.width + 1) continue;
      final alpha = (96 * line.ratio).round().clamp(0, 96);
      gridPaint
        ..color = Colors.white.withAlpha(alpha)
        ..strokeWidth =
            (tuning != null ? 1.5 * line.ratio : (line.isAnchor ? 1.5 : 1)) *
            layout.physicalPixel;
      _drawPixelAlignedVerticalLine(
        canvas,
        x,
        0,
        layout.keyboardTop,
        gridPaint,
      );
    }
  }

  void _paintMeasures(Canvas canvas, Size size) {
    final parsed = score;
    if (parsed == null || parsed.tempoMap.isEmpty || parsed.meters.isEmpty) {
      return;
    }
    final visibleStart = math.max(0.0, layout.playhead - 1.5);
    final visibleEnd =
        layout.playhead +
        math.max(6.5, layout.keyboardTop / layout.pixelsPerSecond + 0.1);
    final startTick = MidiWaterfallParser.secondsToTick(
      visibleStart,
      parsed.tempoMap,
      parsed.ticksPerQuarter,
    );
    final endTick = MidiWaterfallParser.secondsToTick(
      visibleEnd,
      parsed.tempoMap,
      parsed.ticksPerQuarter,
    );
    final labelPainter = TextPainter(textDirection: TextDirection.ltr);
    final measurePaint = Paint()
      ..color = Colors.white.withAlpha(54)
      ..strokeWidth = layout.physicalPixel;
    for (var index = 0; index < parsed.meters.length; index++) {
      final meter = parsed.meters[index];
      final stepTicks = MidiWaterfallParser.measureTicks(
        meter,
        parsed.ticksPerQuarter,
      );
      final segmentStart = math.max(0.0, meter.tick.toDouble());
      final segmentEnd = index + 1 < parsed.meters.length
          ? parsed.meters[index + 1].tick.toDouble()
          : endTick + stepTicks;
      if (segmentEnd <= segmentStart) continue;
      final firstIndex = math
          .max(0, ((startTick - segmentStart) / stepTicks).ceil())
          .toInt();
      final lastIndex =
          ((math.min(endTick, segmentEnd - 0.0001) - segmentStart) / stepTicks)
              .floor();
      if (lastIndex < firstIndex) continue;

      var measureNumber = 1;
      for (var previousIndex = 0; previousIndex < index; previousIndex++) {
        final previous = parsed.meters[previousIndex];
        final previousStep = MidiWaterfallParser.measureTicks(
          previous,
          parsed.ticksPerQuarter,
        );
        final previousEnd = previousIndex + 1 < parsed.meters.length
            ? parsed.meters[previousIndex + 1].tick.toDouble()
            : segmentStart;
        measureNumber += ((previousEnd - previous.tick) / previousStep).ceil();
      }
      for (var localIndex = firstIndex; localIndex <= lastIndex; localIndex++) {
        final tick = segmentStart + localIndex * stepTicks;
        final second = MidiWaterfallParser.tickToSeconds(
          tick.round(),
          parsed.tempoMap,
          parsed.ticksPerQuarter,
        );
        final y = layout.timeToY(second);
        if (y < 0 || y > layout.keyboardTop) continue;
        canvas.drawLine(Offset(0, y), Offset(size.width, y), measurePaint);
        labelPainter.text = TextSpan(
          text: '${measureNumber + localIndex}',
          style: TextStyle(
            color: Colors.white.withAlpha(120),
            fontSize: 11,
            fontFamily: 'monospace',
          ),
        );
        labelPainter.layout();
        labelPainter.paint(
          canvas,
          Offset(
            8 * layout.physicalPixel,
            y - labelPainter.height - 4 * layout.physicalPixel,
          ),
        );
      }
    }
  }

  void _paintNotes(Canvas canvas, Size size) {
    final parsed = score;
    if (parsed == null || parsed.notes.isEmpty) return;
    final notes = parsed.notes;
    final visibleEnd =
        layout.playhead +
        math.max(6.5, layout.keyboardTop / layout.pixelsPerSecond + 0.1);
    final visibleStart = layout.playhead - 1.5;
    final scanStart = visibleStart - 8;
    final halfWidth = layout.noteWidth / 2;
    final fillPaint = Paint();
    final highlightPaint = Paint();
    final strokePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = layout.physicalPixel;

    void drawNote(WaterfallNote note) {
      if (note.end < visibleStart) return;
      final pitch = note.pitch + layout.offsetCents / 100;
      if (pitch < layout.visiblePitchMin - 1 ||
          pitch > layout.visiblePitchMax + 1) {
        return;
      }
      final x = layout.pitchToX(pitch);
      final yStart = layout.timeToY(note.start);
      final yEnd = layout.timeToY(note.end);
      final edgeMargin = 8 * layout.physicalPixel;
      var top = math.max(-edgeMargin, math.min(yStart, yEnd));
      var bottom = math.min(
        layout.keyboardTop + edgeMargin,
        math.max(yStart, yEnd),
      );
      if (bottom < 0 || top > layout.keyboardTop) return;
      final minimumHeight = 4 * layout.physicalPixel;
      if (bottom - top < minimumHeight) {
        bottom = math.min(layout.keyboardTop + edgeMargin, top + minimumHeight);
      }

      final velocityIndex = note.velocity.clamp(0, 127).toInt();
      final tone =
          _noteTones[note.track.abs() % _noteTones.length][velocityIndex];
      final rect = Rect.fromLTRB(x - halfWidth, top, x + halfWidth, bottom);
      fillPaint.color = tone.fill;
      canvas.drawRect(rect, fillPaint);
      if (rect.height > 6 * layout.physicalPixel &&
          rect.width > 2.5 * layout.physicalPixel) {
        final inset = 0.6 * layout.physicalPixel;
        highlightPaint.color = tone.highlight;
        canvas.drawRect(
          Rect.fromLTRB(
            rect.left + inset,
            rect.top + inset,
            rect.right - inset,
            math.min(rect.bottom, rect.top + 2 * layout.physicalPixel),
          ),
          highlightPaint,
        );
      }
      strokePaint.color = tone.stroke;
      canvas.drawRect(rect, strokePaint);
    }

    for (final note in parsed.longNotes) {
      if (note.start >= scanStart || note.start > visibleEnd) continue;
      if (note.end >= visibleStart) drawNote(note);
    }
    final first = _lowerBoundNoteStart(notes, scanStart);
    for (var index = first; index < notes.length; index++) {
      final note = notes[index];
      if (note.start > visibleEnd) break;
      drawNote(note);
    }
  }

  void _paintRulerGlass(Canvas canvas, Size size) {
    final rect = Rect.fromLTWH(
      0,
      layout.keyboardTop,
      size.width,
      layout.keyboardHeight,
    );
    canvas.drawRect(
      rect,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x2A151D1C), Color(0x46101615), Color(0x68080D0D)],
          stops: [0, 0.45, 1],
        ).createShader(rect),
    );
    canvas.drawRect(
      Rect.fromLTWH(0, layout.keyboardTop, size.width, 12),
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [_rulerGold.withValues(alpha: 0.10), Colors.transparent],
        ).createShader(Rect.fromLTWH(0, layout.keyboardTop, size.width, 12)),
    );
    canvas.drawLine(
      Offset(0, layout.keyboardTop + 0.5 * layout.physicalPixel),
      Offset(size.width, layout.keyboardTop + 0.5 * layout.physicalPixel),
      Paint()
        ..color = _rulerGold.withValues(alpha: 0.66)
        ..strokeWidth = layout.physicalPixel,
    );
  }

  void _paintRulerTicks(Canvas canvas, Size size) {
    final minimumSpacing =
        layout.visiblePitchRange *
        1.1 *
        layout.physicalPixel /
        math.max(1, size.width);
    final textPainter = TextPainter(textDirection: TextDirection.ltr);
    final tickPaint = Paint()..isAntiAlias = false;
    for (final line in _scaleLines(minimumSpacing)) {
      final x = layout.pitchToX(line.pitch);
      if (x < -1 || x > size.width + 1) continue;
      final label = EdoScaleGuide.labelForPitch(line);
      final fullLength = layout.keyboardHeight * 0.84 * line.ratio;
      final tickLength = label == null
          ? fullLength
          : math.min(fullLength, layout.keyboardHeight - 21);
      final alpha = (184 * line.ratio).round().clamp(0, 184);
      tickPaint
        ..color = _rulerGold.withAlpha(alpha)
        ..strokeWidth =
            (tuning != null ? 1.4 * line.ratio : (line.isAnchor ? 1.4 : 1)) *
            layout.physicalPixel;
      final alignedX = _drawPixelAlignedVerticalLine(
        canvas,
        x,
        layout.keyboardTop,
        layout.keyboardTop + tickLength,
        tickPaint,
      );
      if (label != null) {
        textPainter.text = TextSpan(
          text: label,
          style: TextStyle(
            color: _rulerGold.withAlpha(184),
            fontSize: 10,
            fontFamily: 'monospace',
          ),
        );
        textPainter.layout();
        textPainter.paint(
          canvas,
          Offset(
            alignedX - textPainter.width / 2,
            size.height - textPainter.height - 5,
          ),
        );
      }
    }

    final glowPaint = Paint()
      ..color = AppPalette.selection.withValues(alpha: 0.28)
      ..strokeWidth = 7 * layout.physicalPixel
      ..maskFilter = MaskFilter.blur(
        BlurStyle.normal,
        6 * layout.physicalPixel,
      );
    final activePaint = Paint()
      ..color = AppPalette.selection.withValues(alpha: 0.92)
      ..strokeWidth = 1.8 * layout.physicalPixel;
    for (final pitch in activePitches) {
      final x = layout.pitchToX(pitch);
      if (x < -4 || x > size.width + 4) continue;
      canvas.drawLine(
        Offset(x, layout.keyboardTop),
        Offset(x, layout.keyboardTop + layout.keyboardHeight * 0.64),
        glowPaint,
      );
      canvas.drawLine(
        Offset(x, layout.keyboardTop),
        Offset(x, layout.keyboardTop + layout.keyboardHeight * 0.64),
        activePaint,
      );
    }
  }

  void _paintPlayhead(Canvas canvas, Size size) {
    canvas.drawLine(
      Offset(0, layout.keyboardTop),
      Offset(size.width, layout.keyboardTop),
      Paint()
        ..color = _rulerGold
        ..strokeWidth = 2 * layout.physicalPixel,
    );
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

  static Color _hsv(double hue, double saturation, double value, double alpha) {
    return HSVColor.fromAHSV(
      alpha.clamp(0.0, 1.0),
      hue,
      saturation.clamp(0.0, 1.0),
      value.clamp(0.0, 1.0),
    ).toColor();
  }

  static _NoteTone _tone(double hue, int velocity) {
    final ratio = velocity / 127;
    final emphasis = math.pow(ratio, 0.72).toDouble();
    final value = 0.58 + emphasis * 0.40;
    final lowContrastOutline = value < 0.74;
    return _NoteTone(
      fill: _hsv(hue, 0.74 + ratio * 0.12, value, 0.76 + ratio * 0.20),
      highlight: _hsv(
        hue,
        0.70,
        math.min(1, value + 0.18),
        lowContrastOutline ? 0.22 : 0.14,
      ),
      stroke: _hsv(
        hue,
        0.82 + ratio * 0.10,
        lowContrastOutline
            ? math.min(0.98, value + 0.24)
            : math.max(0.30, value - 0.52),
        lowContrastOutline ? 0.62 : 0.74,
      ),
    );
  }

  static int _lowerBoundNoteStart(List<WaterfallNote> notes, double second) {
    var low = 0;
    var high = notes.length;
    while (low < high) {
      final middle = (low + high) >> 1;
      if (notes[middle].start < second) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  Iterable<EdoScaleLine> _scaleLines(double minimumPitchSpacing) {
    final customTuning = tuning;
    if (customTuning != null) {
      return EdoScaleGuide.customLinesForRange(
        tuning: customTuning,
        minimumPitch: layout.visiblePitchMin,
        maximumPitch: layout.visiblePitchMax,
        minimumPitchSpacing: minimumPitchSpacing,
      );
    }
    return EdoScaleGuide.linesForRange(
      edo: edo,
      minimumPitch: layout.visiblePitchMin,
      maximumPitch: layout.visiblePitchMax,
      minimumPitchSpacing: minimumPitchSpacing,
    );
  }

  double _drawPixelAlignedVerticalLine(
    Canvas canvas,
    double x,
    double top,
    double bottom,
    Paint paint,
  ) {
    final pixelRatio = math.max(1, layout.devicePixelRatio);
    final physicalX = x * pixelRatio;
    final alignedX = ((physicalX - 0.5).roundToDouble() + 0.5) / pixelRatio;
    canvas.drawLine(Offset(alignedX, top), Offset(alignedX, bottom), paint);
    return alignedX;
  }

  @override
  bool shouldRepaint(covariant WaterfallPainter oldDelegate) {
    return oldDelegate.score != score ||
        oldDelegate.layout.playhead != layout.playhead ||
        oldDelegate.layout.pixelsPerSecond != layout.pixelsPerSecond ||
        oldDelegate.layout.pitchZoom != layout.pitchZoom ||
        oldDelegate.layout.pitchPan != layout.pitchPan ||
        oldDelegate.layout.offsetCents != layout.offsetCents ||
        oldDelegate.layout.devicePixelRatio != layout.devicePixelRatio ||
        oldDelegate.edo != edo ||
        oldDelegate.tuning != tuning ||
        oldDelegate.activePitches != activePitches;
  }
}

class _WaterfallParticlePainter extends CustomPainter {
  _WaterfallParticlePainter({
    required this.layout,
    required this.edo,
    required this.tuning,
    required this.particles,
    required this.impacts,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final WaterfallLayout layout;
  final int edo;
  final TuningDefinition? tuning;
  final List<WaterfallHitParticle> particles;
  final Iterable<WaterfallKeyImpact> impacts;

  @override
  void paint(Canvas canvas, Size size) {
    if (particles.isEmpty && impacts.isEmpty) return;
    _paintImpacts(canvas, size);
    if (particles.isEmpty) return;
    final trailPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..blendMode = BlendMode.plus;
    final glowPaint = Paint()
      ..style = PaintingStyle.fill
      ..blendMode = BlendMode.plus;
    final corePaint = Paint()
      ..style = PaintingStyle.fill
      ..blendMode = BlendMode.plus;
    for (final particle in particles) {
      if (particle.maxLife <= 0 ||
          particle.x < -12 ||
          particle.x > size.width + 12 ||
          particle.y < -24 ||
          particle.y > size.height + 24) {
        continue;
      }
      final lifeRatio = (particle.life / particle.maxLife).clamp(0.0, 1.0);
      final alpha = math.pow(lifeRatio, 1.05).toDouble();
      final particleSize = particle.size * (1 + (1 - alpha) * 0.92);
      final glowSize = particleSize * 2.45;
      trailPaint
        ..strokeWidth = math.max(
          1.5 * layout.physicalPixel,
          particleSize * 0.95,
        )
        ..color = _hsv(
          particle.hue,
          0.94,
          math.min(0.95, particle.lightness + 0.15),
          alpha * 0.68,
        );
      canvas.drawLine(
        Offset(particle.x, particle.y),
        Offset(
          particle.x - particle.vx * 0.024,
          particle.y - particle.vy * 0.024,
        ),
        trailPaint,
      );
      glowPaint
        ..color = _hsv(particle.hue, 0.96, 0.86, alpha * 0.48)
        ..maskFilter = MaskFilter.blur(
          BlurStyle.normal,
          math.max(layout.physicalPixel, glowSize * 0.52),
        );
      canvas.drawCircle(Offset(particle.x, particle.y), glowSize, glowPaint);
      corePaint.color = _hsv(particle.hue, 0.64, 1, alpha * 0.92);
      canvas.drawCircle(
        Offset(particle.x, particle.y),
        math.max(1.2 * layout.physicalPixel, particleSize * 0.54),
        corePaint,
      );
    }
  }

  void _paintImpacts(Canvas canvas, Size size) {
    final impactPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.butt;
    final keyHeight = layout.keyboardHeight;
    final pixel = layout.physicalPixel;
    for (final impact in impacts) {
      if (impact.maxLife <= 0) continue;
      final progress = (impact.life / impact.maxLife).clamp(0.0, 1.0);
      final velocityRatio = impact.velocityRatio.clamp(0.0, 1.0);
      final amount = math.sin(progress * math.pi) * velocityRatio;
      final fade = math.pow(progress, 0.72).toDouble();
      final tick = EdoScaleGuide.impactTickForPitch(
        edo: edo,
        pitch: impact.pitch,
        keyHeight: keyHeight,
        tuning: tuning,
      );
      final maximumLength = math.max(0.0, keyHeight - 2 * pixel);
      final maximumAmplitude =
          math.max(0.0, maximumLength - tick.length) * velocityRatio;
      final tickLength = math.min(
        maximumLength,
        tick.length + maximumAmplitude * amount,
      );
      final yOffset = -math.min(4 * pixel, keyHeight * 0.08) * amount;
      final x = layout.pitchToX(impact.pitch);
      if (x < -1 || x > size.width + 1) continue;
      final alpha = (0.54 + fade * (0.30 + velocityRatio * 0.14)).clamp(
        0.0,
        0.98,
      );
      impactPaint
        ..color = _hsv(impact.hue, 0.94, 0.98, alpha)
        ..strokeWidth = math.max(
          layout.noteWidth,
          tick.strokeWidth * pixel + 3 * pixel * (0.35 + amount),
        );
      canvas.drawLine(
        Offset(x, layout.keyboardTop + yOffset),
        Offset(x, layout.keyboardTop + yOffset + tickLength),
        impactPaint,
      );
    }
  }

  static Color _hsv(double hue, double saturation, double value, double alpha) {
    return HSVColor.fromAHSV(
      alpha.clamp(0.0, 1.0),
      hue,
      saturation.clamp(0.0, 1.0),
      value.clamp(0.0, 1.0),
    ).toColor();
  }

  @override
  bool shouldRepaint(covariant _WaterfallParticlePainter oldDelegate) {
    return oldDelegate.layout != layout ||
        oldDelegate.edo != edo ||
        oldDelegate.tuning != tuning ||
        !identical(oldDelegate.particles, particles);
  }
}

class _NoteTone {
  const _NoteTone({
    required this.fill,
    required this.highlight,
    required this.stroke,
  });

  final Color fill;
  final Color highlight;
  final Color stroke;
}
