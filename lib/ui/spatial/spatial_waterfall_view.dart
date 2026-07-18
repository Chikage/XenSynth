import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../../app/xensynth_settings.dart';
import '../../core/hex_keyboard.dart';
import '../../core/midi_parser.dart';
import '../../core/score.dart';
import '../app_palette.dart';
import '../hex/hex_keyboard_view.dart';
import '../waterfall/waterfall_particle_system.dart';

typedef SpatialPitchPointerCallback =
    void Function(int pointer, double pitch, int velocity);

const double _spatialImpactMaximumPhysicalPixels = 4;
const double _spatialImpactKeySizeRatio = 0.08;
const double _spatialRepeatGapPreviewRatio = 0.012;
const double _spatialRepeatGapMinimumSeconds = 0.016;
const double _spatialRepeatGapMaximumSeconds = 0.04;
const double _spatialRepeatGapIntervalRatio = 0.22;
const double _spatialMinimumVisibleNotePreviewRatio = 0.003;
const double _spatialMinimumVisibleNoteSeconds = 0.004;

double _spatialNoteRadiusScale(int velocity) {
  final velocityRatio = velocity.clamp(1, 127) / 127;
  return 0.18 + velocityRatio * 0.055;
}

Color _spatialTrackColor(WaterfallNote note) {
  final velocityRatio = note.velocity.clamp(1, 127) / 127;
  return HSVColor.fromAHSV(
    1,
    WaterfallParticleSystem.trackHue(note.track),
    0.82,
    0.72 + velocityRatio * 0.26,
  ).toColor();
}

/// A hexagonal keyboard drawn on the x/y plane with score time rising along z.
class SpatialWaterfallView extends StatefulWidget {
  const SpatialWaterfallView({
    required this.score,
    required this.playhead,
    required this.settings,
    required this.activePitches,
    required this.onPitchDown,
    required this.onPitchMove,
    required this.onPitchUp,
    this.playing = false,
    this.viewportController,
    super.key,
  });

  final ParsedScore? score;
  final double playhead;
  final XenSynthSettings settings;
  final Map<int, double> activePitches;
  final bool playing;
  final SpatialPitchPointerCallback onPitchDown;
  final SpatialPitchPointerCallback onPitchMove;
  final ValueChanged<int> onPitchUp;
  final HexKeyboardViewportController? viewportController;

  @visibleForTesting
  static Offset? debugLandingCenterForPitch({
    required Size size,
    required XenSynthSettings settings,
    required double pitch,
    double devicePixelRatio = 1,
  }) {
    final layout = HexaKeyboardLayoutEngine.build(
      settings.hexKeyboardConfiguration,
    );
    final key = layout.keyForPitch(pitch);
    if (key == null) return null;
    final scene = _SpatialScene.fit(
      layout: layout,
      size: size,
      projection: settings.spatialProjection,
      previewSeconds: settings.playbackPreviewSeconds,
      scaleMultiplier: HexKeyboardViewportController.minimumScale,
      pan: Offset.zero,
      rotationDegrees: 0,
      devicePixelRatio: devicePixelRatio,
    );
    return scene.cellsByCoordinate[key.coordinate]?.center;
  }

  @visibleForTesting
  static Offset? debugImpactCenterForPitch({
    required Size size,
    required XenSynthSettings settings,
    required double pitch,
    required double life,
    required double maxLife,
    required double velocityRatio,
    double devicePixelRatio = 1,
  }) {
    final layout = HexaKeyboardLayoutEngine.build(
      settings.hexKeyboardConfiguration,
    );
    final key = layout.keyForPitch(pitch);
    if (key == null) return null;
    final scene = _SpatialScene.fit(
      layout: layout,
      size: size,
      projection: settings.spatialProjection,
      previewSeconds: settings.playbackPreviewSeconds,
      scaleMultiplier: HexKeyboardViewportController.minimumScale,
      pan: Offset.zero,
      rotationDegrees: 0,
      devicePixelRatio: devicePixelRatio,
    );
    final cell = scene.cellsByCoordinate[key.coordinate];
    if (cell == null) return null;
    final impact = WaterfallKeyImpact(
      pitch: pitch,
      life: life,
      maxLife: maxLife,
      velocityRatio: velocityRatio,
      hue: 0,
    );
    final maximumScreenDisplacement = math.min(
      _spatialImpactMaximumPhysicalPixels * scene.physicalPixel,
      cell.projectedRadius * 2 * _spatialImpactKeySizeRatio,
    );
    final negativeZUnit =
        (scene.project(cell.key.center, -1) - cell.center).distance;
    final depth = negativeZUnit.isFinite && negativeZUnit > 0.000001
        ? maximumScreenDisplacement / negativeZUnit * impact.animationAmount
        : 0.0;
    return scene.project(key.center, -depth);
  }

  @visibleForTesting
  static double debugProjectedDistanceForTime({
    required Size size,
    required XenSynthSettings settings,
    required double playhead,
    required double scoreTime,
    double devicePixelRatio = 1,
  }) {
    final layout = HexaKeyboardLayoutEngine.build(
      settings.hexKeyboardConfiguration,
    );
    final scene = _SpatialScene.fit(
      layout: layout,
      size: size,
      projection: settings.spatialProjection,
      previewSeconds: settings.playbackPreviewSeconds,
      scaleMultiplier: HexKeyboardViewportController.minimumScale,
      pan: Offset.zero,
      rotationDegrees: 0,
      devicePixelRatio: devicePixelRatio,
    );
    final anchor = layout.defaultSelection!.center;
    final base = scene.project(anchor, 0);
    final elevated = scene.project(
      anchor,
      scene.zForSeconds(scoreTime - playhead),
    );
    return (elevated - base).distance;
  }

  @visibleForTesting
  static int debugVisibleMeasureCount({
    required ParsedScore score,
    required XenSynthSettings settings,
    required double playhead,
  }) {
    final layout = HexaKeyboardLayoutEngine.build(
      settings.hexKeyboardConfiguration,
    );
    return _SpatialPlaybackIndex.build(
      score,
      layout,
    ).visibleMeasuresAt(playhead, settings.playbackPreviewSeconds).length;
  }

  @visibleForTesting
  static List<({int scoreIndex, double start, double end})>
  debugVisualNoteSpans({
    required ParsedScore score,
    required XenSynthSettings settings,
    required double playhead,
  }) {
    final layout = HexaKeyboardLayoutEngine.build(
      settings.hexKeyboardConfiguration,
    );
    final visualPreviewSeconds = settings.playbackPreviewSeconds > 0
        ? settings.playbackPreviewSeconds
        : 1.0;
    return _layoutSpatialNoteSpans(
          playbackIndex: _SpatialPlaybackIndex.build(score, layout),
          playhead: playhead,
          previewSeconds: settings.playbackPreviewSeconds,
          visualPreviewSeconds: visualPreviewSeconds,
        )
        .map(
          (span) => (
            scoreIndex: span.scoreIndex,
            start: playhead + span.bottomSeconds,
            end: playhead + span.topSeconds,
          ),
        )
        .toList(growable: false);
  }

  @visibleForTesting
  static ({double minimum, double maximum}) debugProjectedKeyRadiusRange({
    required Size size,
    required XenSynthSettings settings,
    double rotationXDegrees = 0,
    double rotationYDegrees = 0,
    double rotationDegrees = 0,
    double devicePixelRatio = 1,
  }) {
    final layout = HexaKeyboardLayoutEngine.build(
      settings.hexKeyboardConfiguration,
    );
    final scene = _SpatialScene.fit(
      layout: layout,
      size: size,
      projection: settings.spatialProjection,
      previewSeconds: settings.playbackPreviewSeconds,
      scaleMultiplier: HexKeyboardViewportController.minimumScale,
      pan: Offset.zero,
      rotationXDegrees: rotationXDegrees,
      rotationYDegrees: rotationYDegrees,
      rotationDegrees: rotationDegrees,
      devicePixelRatio: devicePixelRatio,
    );
    return (
      minimum: scene.cells.map((cell) => cell.projectedRadius).reduce(math.min),
      maximum: scene.cells.map((cell) => cell.projectedRadius).reduce(math.max),
    );
  }

  @override
  State<SpatialWaterfallView> createState() => _SpatialWaterfallViewState();
}

class _SpatialWaterfallViewState extends State<SpatialWaterfallView>
    with SingleTickerProviderStateMixin {
  static const _particleCursorEpsilon = 0.001;
  static const _maximumEmissionStepSeconds = 0.25;
  static const _transformPanThreshold = 9.0;
  static const _transformScaleThreshold = 0.035;
  static const _transformRotationThresholdDegrees = 3.0;

  final Map<int, HexKey> _pointerCells = {};
  final Map<int, double> _pointerForces = {};
  final Map<int, Offset> _pointerPositions = {};
  final WaterfallParticleSystem _particleSystem = WaterfallParticleSystem(
    maximumLiveParticles: 480,
  );
  final ValueNotifier<int> _particleRepaint = ValueNotifier(0);

  late HexKeyboardConfiguration _configuration;
  late HexaKeyboardLayout _layout;
  late _SpatialPlaybackIndex _playbackIndex;
  late HexKeyboardViewportController _viewportController;
  late bool _ownsViewportController;
  late final Ticker _particleTicker;
  Duration _lastParticleElapsed = Duration.zero;
  _SpatialScene? _latestScene;
  _SpatialScene? _sceneCache;
  _SpatialTransformGesture? _transformGesture;
  _PendingSpatialTransform? _pendingSpatialTransform;
  bool _spatialTransformScheduled = false;
  bool _transformConsumed = false;
  int _particleCursor = 0;

  @override
  void initState() {
    super.initState();
    _configuration = widget.settings.hexKeyboardConfiguration;
    _layout = HexaKeyboardLayoutEngine.build(_configuration);
    _playbackIndex = _SpatialPlaybackIndex.build(widget.score, _layout);
    _attachViewportController(widget.viewportController);
    _particleCursor = _findParticleCursor(widget.playhead);
    _particleTicker = createTicker(_advanceParticles);
  }

  @override
  void didUpdateWidget(covariant SpatialWaterfallView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.viewportController != oldWidget.viewportController) {
      _detachViewportController();
      _attachViewportController(widget.viewportController);
    }

    final nextConfiguration = widget.settings.hexKeyboardConfiguration;
    if (nextConfiguration != _configuration) {
      _releaseAllPointers();
      _configuration = nextConfiguration;
      _layout = HexaKeyboardLayoutEngine.build(nextConfiguration);
      _playbackIndex = _SpatialPlaybackIndex.build(widget.score, _layout);
      _latestScene = null;
      _sceneCache = null;
    }

    if (!identical(oldWidget.score, widget.score)) {
      _playbackIndex = _SpatialPlaybackIndex.build(widget.score, _layout);
      _particleSystem.clear();
      _repaintParticles();
      _particleCursor = _findParticleCursor(widget.playhead);
      _stopParticleTickerIfIdle();
      return;
    }

    final delta = widget.playhead - oldWidget.playhead;
    if (widget.playing && delta >= 0 && delta <= _maximumEmissionStepSeconds) {
      _emitScoreHits(oldWidget.playhead, widget.playhead);
    } else if (delta.abs() > _particleCursorEpsilon || !widget.playing) {
      _particleCursor = _findParticleCursor(widget.playhead);
    }
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
        final scene = _sceneFor(constraints.biggest, devicePixelRatio);
        _latestScene = scene;
        final selectedCoordinates = _selectedCoordinates();
        final activeForces = _activeForces(selectedCoordinates);
        return Semantics(
          container: true,
          label:
              'Spatial waterfall hexagonal keyboard with ${_layout.cells.length} keys',
          hint: 'Use two fingers to pan, pinch, and rotate the 3D view',
          child: Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: (event) => _handlePointerDown(event, scene),
            onPointerMove: (event) => _handlePointerMove(event, scene),
            onPointerUp: (event) => _handlePointerEnd(event.pointer),
            onPointerCancel: (event) => _handlePointerEnd(event.pointer),
            child: RepaintBoundary(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  RepaintBoundary(
                    child: CustomPaint(
                      painter: _SpatialBackdropPainter(
                        hasScore: widget.score != null,
                        scene: scene,
                      ),
                      size: Size.infinite,
                    ),
                  ),
                  RepaintBoundary(
                    key: const ValueKey('spatial-measures-layer'),
                    child: CustomPaint(
                      painter: _SpatialMeasurePainter(
                        playbackIndex: _playbackIndex,
                        playhead: widget.playhead,
                        previewSeconds: widget.settings.playbackPreviewSeconds,
                        scene: scene,
                        showLabels: !_transformConsumed,
                      ),
                      size: Size.infinite,
                    ),
                  ),
                  RepaintBoundary(
                    key: const ValueKey('spatial-keyboard-layer'),
                    child: CustomPaint(
                      painter: _SpatialKeyboardPainter(
                        scene: scene,
                        simplified: _transformConsumed,
                      ),
                      size: Size.infinite,
                    ),
                  ),
                  RepaintBoundary(
                    key: const ValueKey('spatial-impact-keyboard-layer'),
                    child: CustomPaint(
                      painter: _SpatialImpactKeyboardPainter(
                        scene: scene,
                        impacts: _particleSystem.impacts,
                        repaint: _particleRepaint,
                      ),
                      size: Size.infinite,
                    ),
                  ),
                  // Paint the complete waterfall after the opaque keyboard.
                  // Otherwise the z=0 end of each prism is covered by the key
                  // face and appears to land behind the keyboard.
                  RepaintBoundary(
                    key: const ValueKey('spatial-notes-layer'),
                    child: CustomPaint(
                      painter: _SpatialNotesPainter(
                        playbackIndex: _playbackIndex,
                        playhead: widget.playhead,
                        previewSeconds: widget.settings.playbackPreviewSeconds,
                        scene: scene,
                        impacts: _particleSystem.impacts,
                        simplified: _transformConsumed,
                        repaint: _particleRepaint,
                      ),
                      size: Size.infinite,
                    ),
                  ),
                  RepaintBoundary(
                    child: CustomPaint(
                      painter: _SpatialOverlayPainter(
                        playbackIndex: _playbackIndex,
                        playhead: widget.playhead,
                        previewSeconds: widget.settings.playbackPreviewSeconds,
                        scene: scene,
                        selectedCoordinates: selectedCoordinates,
                        activeForces: activeForces,
                        impacts: _particleSystem.impacts,
                        repaint: _particleRepaint,
                      ),
                      size: Size.infinite,
                    ),
                  ),
                  RepaintBoundary(
                    child: CustomPaint(
                      painter: _SpatialParticlePainter(
                        scene: scene,
                        particles: _particleSystem.particles,
                        impacts: _particleSystem.impacts,
                        repaint: _particleRepaint,
                      ),
                      size: Size.infinite,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  void _attachViewportController(HexKeyboardViewportController? controller) {
    _ownsViewportController = controller == null;
    _viewportController = controller ?? HexKeyboardViewportController();
    _viewportController.addListener(_handleViewportChanged);
  }

  void _detachViewportController() {
    _viewportController.removeListener(_handleViewportChanged);
    if (_ownsViewportController) _viewportController.dispose();
  }

  void _handleViewportChanged() {
    if (mounted) setState(() {});
  }

  _SpatialScene _sceneFor(Size size, double devicePixelRatio) {
    final cached = _sceneCache;
    if (cached != null &&
        cached.matches(
          layout: _layout,
          size: size,
          projection: widget.settings.spatialProjection,
          previewSeconds: widget.settings.playbackPreviewSeconds,
          scaleMultiplier: _viewportController.scaleMultiplier,
          pan: _viewportController.pan,
          rotationXDegrees: _viewportController.rotationXDegrees,
          rotationYDegrees: _viewportController.rotationYDegrees,
          rotationDegrees: _viewportController.rotationDegrees,
          devicePixelRatio: devicePixelRatio,
        )) {
      return cached;
    }
    return _sceneCache = _SpatialScene.fit(
      layout: _layout,
      size: size,
      projection: widget.settings.spatialProjection,
      previewSeconds: widget.settings.playbackPreviewSeconds,
      scaleMultiplier: _viewportController.scaleMultiplier,
      pan: _viewportController.pan,
      rotationXDegrees: _viewportController.rotationXDegrees,
      rotationYDegrees: _viewportController.rotationYDegrees,
      rotationDegrees: _viewportController.rotationDegrees,
      devicePixelRatio: devicePixelRatio,
    );
  }

  void _handlePointerDown(PointerDownEvent event, _SpatialScene scene) {
    _pointerPositions[event.pointer] = event.localPosition;
    if (_transformConsumed) return;
    _processPointer(event, scene, true);
    if (_pointerPositions.length >= 2) {
      _transformGesture ??= _SpatialTransformGesture.capture(
        _pointerPositions,
        scaleMultiplier: _viewportController.scaleMultiplier,
        pan: _viewportController.pan,
        rotationXDegrees: _viewportController.rotationXDegrees,
        rotationYDegrees: _viewportController.rotationYDegrees,
        rotationDegrees: _viewportController.rotationDegrees,
      );
    }
  }

  void _handlePointerMove(PointerMoveEvent event, _SpatialScene scene) {
    if (!_pointerPositions.containsKey(event.pointer)) return;
    _pointerPositions[event.pointer] = event.localPosition;
    final gesture = _transformGesture;
    if (gesture == null || _pointerPositions.length < 2) {
      if (!_transformConsumed) _processPointer(event, scene, false);
      return;
    }
    final metrics = gesture.measure(_pointerPositions);
    if (metrics == null) return;
    if (!_transformConsumed) {
      final moved = (metrics.focalPoint - gesture.startFocalPoint).distance;
      final scaleChanged = (metrics.scale - 1).abs();
      final rotationChanged = math.max(
        metrics.rotationZDegrees.abs(),
        math.max(
          metrics.rotationXDegrees.abs(),
          metrics.rotationYDegrees.abs(),
        ),
      );
      if (moved < _transformPanThreshold &&
          scaleChanged < _transformScaleThreshold &&
          rotationChanged < _transformRotationThresholdDegrees) {
        _processPointer(event, scene, false);
        return;
      }
      _transformConsumed = true;
      _releaseAllPointers(notify: true);
    }
    _queueSpatialTransform(
      scaleMultiplier: gesture.startScaleMultiplier * metrics.scale,
      pan:
          gesture.startPan +
          (metrics.focalPoint - gesture.startFocalPoint) * 0.22,
      rotationXDegrees:
          gesture.startRotationXDegrees + metrics.rotationXDegrees,
      rotationYDegrees:
          gesture.startRotationYDegrees + metrics.rotationYDegrees,
      rotationDegrees: gesture.startRotationZDegrees + metrics.rotationZDegrees,
    );
  }

  void _queueSpatialTransform({
    required double scaleMultiplier,
    required Offset pan,
    required double rotationXDegrees,
    required double rotationYDegrees,
    required double rotationDegrees,
  }) {
    _pendingSpatialTransform = _PendingSpatialTransform(
      scaleMultiplier: scaleMultiplier,
      pan: pan,
      rotationXDegrees: rotationXDegrees,
      rotationYDegrees: rotationYDegrees,
      rotationDegrees: rotationDegrees,
    );
    if (_spatialTransformScheduled) return;
    _spatialTransformScheduled = true;
    SchedulerBinding.instance.scheduleFrameCallback((_) {
      _spatialTransformScheduled = false;
      final pending = _pendingSpatialTransform;
      _pendingSpatialTransform = null;
      if (!mounted || pending == null) return;
      _viewportController.setSpatialTransform(
        scaleMultiplier: pending.scaleMultiplier,
        pan: pending.pan,
        rotationXDegrees: pending.rotationXDegrees,
        rotationYDegrees: pending.rotationYDegrees,
        rotationDegrees: pending.rotationDegrees,
      );
    });
  }

  void _handlePointerEnd(int pointer) {
    _pointerPositions.remove(pointer);
    if (!_transformConsumed) _releasePointer(pointer);
    if (_pointerPositions.length >= 2) {
      _transformGesture = _SpatialTransformGesture.capture(
        _pointerPositions,
        scaleMultiplier: _viewportController.scaleMultiplier,
        pan: _viewportController.pan,
        rotationXDegrees: _viewportController.rotationXDegrees,
        rotationYDegrees: _viewportController.rotationYDegrees,
        rotationDegrees: _viewportController.rotationDegrees,
      );
    } else {
      _transformGesture = null;
    }
    if (_pointerPositions.isEmpty && _transformConsumed) {
      _transformConsumed = false;
      if (mounted) setState(() {});
    }
  }

  void _processPointer(PointerEvent event, _SpatialScene scene, bool isDown) {
    final previous = _pointerCells[event.pointer];
    final next = scene.keyAt(
      event.localPosition,
      previousCoordinate: previous?.coordinate,
      sensitivity: 1 + widget.settings.touchSensitivity * 0.5,
    );
    final force = _normalizedForce(event);

    if (next == null) {
      if (previous != null) _releasePointer(event.pointer);
      return;
    }
    if (next.coordinate == previous?.coordinate) {
      final oldForce = _pointerForces[event.pointer];
      if (oldForce == null || (oldForce - force).abs() >= 0.02) {
        setState(() => _pointerForces[event.pointer] = force);
      }
      return;
    }

    setState(() {
      _pointerCells[event.pointer] = next;
      _pointerForces[event.pointer] = force;
    });
    final velocity = widget.settings.pseudoPressureEnabled
        ? (24 + force * 103).round().clamp(1, 127)
        : 104;
    _spawnHitParticles(next, velocity: velocity, track: 0, scene: scene);
    final pitch = next.audioPitch.midiPitch;
    if (isDown || previous == null) {
      widget.onPitchDown(event.pointer, pitch, velocity);
    } else {
      widget.onPitchMove(event.pointer, pitch, velocity);
    }
  }

  double _normalizedForce(PointerEvent event) {
    if (!widget.settings.pseudoPressureEnabled) return 0.76;
    if (event.pressureMax > event.pressureMin) {
      return ((event.pressure - event.pressureMin) /
              (event.pressureMax - event.pressureMin))
          .clamp(0.0, 1.0)
          .toDouble();
    }
    return 0.76;
  }

  void _releasePointer(int pointer) {
    if (!_pointerCells.containsKey(pointer)) return;
    setState(() {
      _pointerCells.remove(pointer);
      _pointerForces.remove(pointer);
    });
    widget.onPitchUp(pointer);
  }

  void _releaseAllPointers({bool notify = false}) {
    final pointers = _pointerCells.keys.toList(growable: false);
    _pointerCells.clear();
    _pointerForces.clear();
    if (notify && pointers.isNotEmpty && mounted) setState(() {});
    for (final pointer in pointers) {
      widget.onPitchUp(pointer);
    }
  }

  Set<AxialCoordinate> _selectedCoordinates() {
    final result = <AxialCoordinate>{
      for (final cell in _pointerCells.values) cell.coordinate,
    };
    for (final entry in widget.activePitches.entries) {
      final cell = _pointerCells[entry.key] ?? _layout.keyForPitch(entry.value);
      if (cell != null) result.add(cell.coordinate);
    }
    return result;
  }

  Map<AxialCoordinate, double> _activeForces(
    Set<AxialCoordinate> selectedCoordinates,
  ) {
    final result = <AxialCoordinate, double>{
      for (final coordinate in selectedCoordinates) coordinate: 0.76,
    };
    for (final entry in _pointerForces.entries) {
      final cell = _pointerCells[entry.key];
      if (cell == null) continue;
      result[cell.coordinate] = math.max(
        result[cell.coordinate] ?? 0,
        entry.value,
      );
    }
    return result;
  }

  void _emitScoreHits(double previousPlayhead, double currentPlayhead) {
    final notes = widget.score?.notes;
    final scene = _latestScene;
    if (notes == null || notes.isEmpty || scene == null) return;
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
      final cell = _layout.keyForPitch(note.pitch);
      if (cell != null) {
        _spawnHitParticles(
          cell,
          velocity: note.velocity,
          track: note.track,
          noteDurationSeconds: note.duration,
          scene: scene,
          notify: false,
        );
        spawned = true;
      }
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

  void _spawnHitParticles(
    HexKey cell, {
    required int velocity,
    required int track,
    required _SpatialScene scene,
    double? noteDurationSeconds,
    bool notify = true,
  }) {
    final spatialCell = scene.cellsByCoordinate[cell.coordinate];
    if (spatialCell == null) return;
    _particleSystem.spawn(
      pitch: cell.audioPitch.midiPitch,
      x: spatialCell.center.dx,
      y: spatialCell.center.dy,
      noteWidth: spatialCell.projectedRadius,
      pixelScale: scene.physicalPixel,
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
    if (_particleSystem.advance(delta)) _repaintParticles();
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

  @override
  void dispose() {
    _releaseAllPointers();
    _detachViewportController();
    _particleTicker.dispose();
    _particleRepaint.dispose();
    super.dispose();
  }
}

class _SpatialScene {
  _SpatialScene._({
    required this.layout,
    required this.size,
    required this.projection,
    required this.previewSeconds,
    required this.scaleMultiplier,
    required this.pan,
    required this.rotationXDegrees,
    required this.rotationYDegrees,
    required this.rotationDegrees,
    required this.devicePixelRatio,
    required this.projectionTransform,
    required this.zMaximum,
    required this.scale,
    required this.offset,
    required this.physicalPixel,
    required this.cells,
  }) : cellsByCoordinate = <AxialCoordinate, _SpatialCell>{
         for (final cell in cells) cell.key.coordinate: cell,
       };

  factory _SpatialScene.fit({
    required HexaKeyboardLayout layout,
    required Size size,
    required SpatialProjectionMode projection,
    required double previewSeconds,
    required double scaleMultiplier,
    required Offset pan,
    required double rotationDegrees,
    double rotationXDegrees = 0,
    double rotationYDegrees = 0,
    required double devicePixelRatio,
  }) {
    final bounds = layout.modelBounds;
    final safePreview = previewSeconds > 0 ? previewSeconds : 1.0;
    final zMaximum = math.max(bounds.height * 1.28, bounds.width * 0.42);
    final projectionTransform = _SpatialProjectionTransform(
      bounds: bounds,
      projection: projection,
      zMaximum: zMaximum,
      rotationXDegrees: rotationXDegrees,
      rotationYDegrees: rotationYDegrees,
      rotationDegrees: rotationDegrees,
    );
    final rawPoints = <Offset>[];
    for (final x in <double>[bounds.minX, bounds.maxX]) {
      for (final y in <double>[bounds.minY, bounds.maxY]) {
        final point = HexPoint(x, y);
        rawPoints
          ..add(projectionTransform.project(point, 0))
          ..add(projectionTransform.project(point, zMaximum));
      }
    }
    final minX = rawPoints.map((point) => point.dx).reduce(math.min);
    final maxX = rawPoints.map((point) => point.dx).reduce(math.max);
    final minY = rawPoints.map((point) => point.dy).reduce(math.min);
    final maxY = rawPoints.map((point) => point.dy).reduce(math.max);
    final rawWidth = math.max(1.0, maxX - minX);
    final rawHeight = math.max(1.0, maxY - minY);
    final padding = math.max(14.0, math.min(size.width, size.height) * 0.035);
    final usableWidth = math.max(1.0, size.width - padding * 2);
    final usableHeight = math.max(1.0, size.height - padding * 2);
    final safeScaleMultiplier = scaleMultiplier.isFinite
        ? scaleMultiplier.clamp(
            HexKeyboardViewportController.minimumScale,
            HexKeyboardViewportController.maximumScale,
          )
        : HexKeyboardViewportController.minimumScale;
    final scale =
        math.min(usableWidth / rawWidth, usableHeight / rawHeight) *
        safeScaleMultiplier;
    final offset = Offset(
      (size.width - rawWidth * scale) / 2 - minX * scale + pan.dx,
      (size.height - rawHeight * scale) / 2 - minY * scale + pan.dy,
    );

    Offset project(HexPoint point, double z) {
      final raw = projectionTransform.project(point, z);
      return offset + raw * scale;
    }

    List<Offset> verticesFor(HexKey key, double z, double radiusScale) {
      final result = <Offset>[];
      final rotation = layout.configuration.rotationDegrees * math.pi / 180;
      final radius = layout.configuration.radius * radiusScale;
      for (var index = 0; index < 6; index++) {
        final angle = rotation + index * math.pi / 3;
        result.add(
          project(
            HexPoint(
              key.center.x + math.cos(angle) * radius,
              key.center.y + math.sin(angle) * radius,
            ),
            z,
          ),
        );
      }
      return result;
    }

    final thickness = layout.configuration.radius * 0.14;
    final cells = <_SpatialCell>[];
    for (final key in layout.cells) {
      final topVertices = verticesFor(key, 0, 0.92);
      final baseVertices = verticesFor(key, -thickness, 0.92);
      final center = project(key.center, 0);
      final projectedRadius =
          topVertices
              .map((point) => (point - center).distance)
              .reduce((a, b) => a + b) /
          topVertices.length;
      cells.add(
        _SpatialCell(
          key: key,
          center: center,
          topVertices: topVertices,
          baseVertices: baseVertices,
          topPath: _pathFrom(topVertices),
          basePath: _pathFrom(baseVertices),
          projectedRadius: projectedRadius,
        ),
      );
    }
    cells.sort((first, second) => first.center.dy.compareTo(second.center.dy));

    return _SpatialScene._(
      layout: layout,
      size: size,
      projection: projection,
      previewSeconds: safePreview,
      scaleMultiplier: safeScaleMultiplier,
      pan: pan,
      rotationXDegrees: rotationXDegrees,
      rotationYDegrees: rotationYDegrees,
      rotationDegrees: rotationDegrees,
      devicePixelRatio: devicePixelRatio,
      projectionTransform: projectionTransform,
      zMaximum: zMaximum,
      scale: scale,
      offset: offset,
      physicalPixel: 1 / math.max(1, devicePixelRatio),
      cells: List.unmodifiable(cells),
    );
  }

  final HexaKeyboardLayout layout;
  final Size size;
  final SpatialProjectionMode projection;
  final double previewSeconds;
  final double scaleMultiplier;
  final Offset pan;
  final double rotationXDegrees;
  final double rotationYDegrees;
  final double rotationDegrees;
  final double devicePixelRatio;
  final _SpatialProjectionTransform projectionTransform;
  final double zMaximum;
  final double scale;
  final Offset offset;
  final double physicalPixel;
  final List<_SpatialCell> cells;
  final Map<AxialCoordinate, _SpatialCell> cellsByCoordinate;

  Offset project(HexPoint point, double z) {
    final raw = projectionTransform.project(point, z);
    return offset + raw * scale;
  }

  bool matches({
    required HexaKeyboardLayout layout,
    required Size size,
    required SpatialProjectionMode projection,
    required double previewSeconds,
    required double scaleMultiplier,
    required Offset pan,
    required double rotationXDegrees,
    required double rotationYDegrees,
    required double rotationDegrees,
    required double devicePixelRatio,
  }) {
    final safePreview = previewSeconds > 0 ? previewSeconds : 1.0;
    return identical(this.layout, layout) &&
        this.size == size &&
        this.projection == projection &&
        this.previewSeconds == safePreview &&
        this.scaleMultiplier == scaleMultiplier &&
        this.pan == pan &&
        this.rotationXDegrees == rotationXDegrees &&
        this.rotationYDegrees == rotationYDegrees &&
        this.rotationDegrees == rotationDegrees &&
        this.devicePixelRatio == devicePixelRatio;
  }

  double zForSeconds(double seconds) {
    return (seconds / previewSeconds).clamp(0.0, 1.0) * zMaximum;
  }

  List<Offset> verticesFor(
    HexKey key, {
    required double z,
    required double radiusScale,
  }) {
    final result = <Offset>[];
    final rotation = layout.configuration.rotationDegrees * math.pi / 180;
    final radius = layout.configuration.radius * radiusScale;
    for (var index = 0; index < 6; index++) {
      final angle = rotation + index * math.pi / 3;
      result.add(
        project(
          HexPoint(
            key.center.x + math.cos(angle) * radius,
            key.center.y + math.sin(angle) * radius,
          ),
          z,
        ),
      );
    }
    return result;
  }

  Path outlineAt(double z) {
    return _pathFrom(
      layout.windowOutline.points.map((point) => project(point, z)).toList(),
    );
  }

  HexKey? keyAt(
    Offset position, {
    AxialCoordinate? previousCoordinate,
    double sensitivity = 1.2,
  }) {
    final previous = previousCoordinate == null
        ? null
        : cellsByCoordinate[previousCoordinate];
    if (previous != null &&
        (position - previous.center).distance <=
            previous.projectedRadius * (sensitivity + 0.15)) {
      final direct = _containingCell(position);
      if (direct == null ||
          direct.key.coordinate == previous.key.coordinate ||
          (position - direct.center).distance +
                  previous.projectedRadius * 0.1 >=
              (position - previous.center).distance) {
        return previous.key;
      }
      return direct.key;
    }
    final direct = _containingCell(position);
    if (direct != null) return direct.key;
    _SpatialCell? nearest;
    var distance = double.infinity;
    for (final cell in cells) {
      final candidate = (position - cell.center).distance;
      if (candidate < distance &&
          candidate <= cell.projectedRadius * sensitivity) {
        nearest = cell;
        distance = candidate;
      }
    }
    return nearest?.key;
  }

  _SpatialCell? _containingCell(Offset position) {
    for (final cell in cells.reversed) {
      if (cell.topPath.contains(position)) return cell;
    }
    return null;
  }
}

class _SpatialProjectionTransform {
  _SpatialProjectionTransform({
    required HexBounds bounds,
    required this.projection,
    required double zMaximum,
    required double rotationXDegrees,
    required double rotationYDegrees,
    required double rotationDegrees,
  }) : centerX = bounds.center.x,
       centerY = bounds.center.y,
       cosineX = math.cos(rotationXDegrees * math.pi / 180),
       sineX = math.sin(rotationXDegrees * math.pi / 180),
       cosineY = math.cos(rotationYDegrees * math.pi / 180),
       sineY = math.sin(rotationYDegrees * math.pi / 180),
       cosineZ = math.cos(rotationDegrees * math.pi / 180),
       sineZ = math.sin(rotationDegrees * math.pi / 180) {
    halfDepthExtent =
        (sineZ * cosineX).abs() * bounds.width / 2 +
        (cosineZ * cosineX).abs() * bounds.height / 2 +
        sineX.abs() * zMaximum / 2;
    depthCenter = -sineX * zMaximum / 2;
  }

  final SpatialProjectionMode projection;
  final double centerX;
  final double centerY;
  final double cosineX;
  final double sineX;
  final double cosineY;
  final double sineY;
  final double cosineZ;
  final double sineZ;
  late final double halfDepthExtent;
  late final double depthCenter;

  Offset project(HexPoint point, double z) {
    final sourceX = point.x - centerX;
    final sourceY = point.y - centerY;
    final zRotationX = sourceX * cosineZ - sourceY * sineZ;
    final zRotationY = sourceX * sineZ + sourceY * cosineZ;
    final xRotationY = zRotationY * cosineX - z * sineX;
    final xRotationZ = zRotationY * sineX + z * cosineX;
    final dx = zRotationX * cosineY + xRotationZ * sineY;
    final dy = xRotationY;
    final dz = -zRotationX * sineY + xRotationZ * cosineY;

    switch (projection) {
      case SpatialProjectionMode.oblique:
        return Offset(dx + dy * 0.42, dy * 0.50 - dz);
      case SpatialProjectionMode.perspective:
        final normalizedDepth =
            ((dy - depthCenter) / math.max(1.0, halfDepthExtent)).clamp(
              -1.0,
              1.0,
            );
        final perspectiveDepth = 1 - normalizedDepth * 0.12;
        final timeDepth = 1 + (perspectiveDepth - 1) * 0.45;
        return Offset(
          dx / perspectiveDepth + dy * 0.055,
          dy * 0.52 / perspectiveDepth - dz / timeDepth,
        );
    }
  }
}

class _SpatialCell {
  const _SpatialCell({
    required this.key,
    required this.center,
    required this.topVertices,
    required this.baseVertices,
    required this.topPath,
    required this.basePath,
    required this.projectedRadius,
  });

  final HexKey key;
  final Offset center;
  final List<Offset> topVertices;
  final List<Offset> baseVertices;
  final Path topPath;
  final Path basePath;
  final double projectedRadius;
}

Map<AxialCoordinate, double> _spatialImpactDepths(
  _SpatialScene scene,
  Iterable<WaterfallKeyImpact> impacts,
) {
  final result = <AxialCoordinate, double>{};
  for (final impact in impacts) {
    if (impact.maxLife <= 0) continue;
    final key = scene.layout.keyForPitch(impact.pitch);
    final cell = key == null ? null : scene.cellsByCoordinate[key.coordinate];
    if (cell == null) continue;
    final depth = _spatialImpactDepthFor(scene, cell, impact);
    if (depth <= 0) continue;
    result[cell.key.coordinate] = math.max(
      result[cell.key.coordinate] ?? 0,
      depth,
    );
  }
  return result;
}

double _spatialImpactDepthFor(
  _SpatialScene scene,
  _SpatialCell cell,
  WaterfallKeyImpact impact,
) {
  final amount = impact.animationAmount;
  if (amount <= 0) return 0;
  final maximumScreenDisplacement = math.min(
    _spatialImpactMaximumPhysicalPixels * scene.physicalPixel,
    cell.projectedRadius * 2 * _spatialImpactKeySizeRatio,
  );
  final negativeZUnit =
      (scene.project(cell.key.center, -1) - cell.center).distance;
  if (!negativeZUnit.isFinite || negativeZUnit <= 0.000001) return 0;
  return maximumScreenDisplacement / negativeZUnit * amount;
}

class _SpatialPlaybackIndex {
  _SpatialPlaybackIndex({
    required this.buckets,
    required this.longNotes,
    required this.measures,
  });

  factory _SpatialPlaybackIndex.build(
    ParsedScore? score,
    HexaKeyboardLayout layout,
  ) {
    final measures = _buildMeasures(score);
    final source = score?.notes;
    if (source == null || source.isEmpty) {
      return _SpatialPlaybackIndex(
        buckets: const <int, List<_SpatialPlaybackNote>>{},
        longNotes: const <_SpatialPlaybackNote>[],
        measures: measures,
      );
    }
    final buckets = <int, List<_SpatialPlaybackNote>>{};
    final longNotes = <_SpatialPlaybackNote>[];
    for (var index = 0; index < source.length; index++) {
      final note = source[index];
      final cell = layout.keyForPitch(note.pitch);
      if (cell == null) continue;
      final mapped = _SpatialPlaybackNote(
        scoreIndex: index,
        note: note,
        cell: cell,
      );
      final startBucket = (note.start / _playbackBucketSeconds).floor();
      final visibleEnd = math.max(note.start, note.end - 0.000001);
      final endBucket = (visibleEnd / _playbackBucketSeconds).floor();
      if (endBucket - startBucket > _maximumBucketsPerNote) {
        longNotes.add(mapped);
        continue;
      }
      for (var bucket = startBucket; bucket <= endBucket; bucket++) {
        buckets.putIfAbsent(bucket, () => []).add(mapped);
      }
    }
    return _SpatialPlaybackIndex(
      buckets: {
        for (final entry in buckets.entries)
          entry.key: List.unmodifiable(entry.value),
      },
      longNotes: List.unmodifiable(longNotes),
      measures: measures,
    );
  }

  static const double _playbackBucketSeconds = 0.5;
  static const int _maximumBucketsPerNote = 120;

  final Map<int, List<_SpatialPlaybackNote>> buckets;
  final List<_SpatialPlaybackNote> longNotes;
  final List<_SpatialMeasure> measures;
  double? _cachedPlayhead;
  double? _cachedPreviewSeconds;
  List<_SpatialPlaybackNote> _cachedVisible = const [];

  List<_SpatialPlaybackNote> visibleAt(double playhead, double previewSeconds) {
    if (_cachedPlayhead == playhead &&
        _cachedPreviewSeconds == previewSeconds) {
      return _cachedVisible;
    }
    final horizon = math.max(0.0, previewSeconds);
    final visibleEnd = playhead + horizon;
    final firstBucket = (playhead / _playbackBucketSeconds).floor();
    final lastBucket = (visibleEnd / _playbackBucketSeconds).floor();
    final seen = <int>{};
    final result = <_SpatialPlaybackNote>[];
    void consider(_SpatialPlaybackNote mapped) {
      if (!seen.add(mapped.scoreIndex)) return;
      final note = mapped.note;
      if (note.start <= visibleEnd && note.end >= playhead) result.add(mapped);
    }

    for (var bucket = firstBucket; bucket <= lastBucket; bucket++) {
      for (final mapped in buckets[bucket] ?? const []) {
        consider(mapped);
      }
    }
    for (final mapped in longNotes) {
      consider(mapped);
    }
    _cachedPlayhead = playhead;
    _cachedPreviewSeconds = previewSeconds;
    return _cachedVisible = List.unmodifiable(result);
  }

  List<_SpatialMeasure> visibleMeasuresAt(
    double playhead,
    double previewSeconds,
  ) {
    if (measures.isEmpty || previewSeconds < 0) return const [];
    final visibleEnd = playhead + math.max(0.0, previewSeconds);
    var low = 0;
    var high = measures.length;
    while (low < high) {
      final middle = (low + high) >> 1;
      if (measures[middle].second < playhead) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    final result = <_SpatialMeasure>[];
    for (var index = low; index < measures.length; index++) {
      final measure = measures[index];
      if (measure.second > visibleEnd) break;
      result.add(measure);
    }
    return result;
  }

  static List<_SpatialMeasure> _buildMeasures(ParsedScore? score) {
    if (score == null ||
        score.tempoMap.isEmpty ||
        score.meters.isEmpty ||
        score.ticksPerQuarter <= 0) {
      return const [];
    }
    final durationTick = MidiWaterfallParser.secondsToTick(
      math.max(0.0, score.duration),
      score.tempoMap,
      score.ticksPerQuarter,
    );
    final result = <_SpatialMeasure>[];
    var measureNumber = 1;
    for (var meterIndex = 0; meterIndex < score.meters.length; meterIndex++) {
      final meter = score.meters[meterIndex];
      final stepTicks = MidiWaterfallParser.measureTicks(
        meter,
        score.ticksPerQuarter,
      );
      if (!stepTicks.isFinite || stepTicks <= 0) continue;
      final segmentStart = math.max(0.0, meter.tick.toDouble());
      final segmentEnd = meterIndex + 1 < score.meters.length
          ? score.meters[meterIndex + 1].tick.toDouble()
          : durationTick + stepTicks;
      if (segmentEnd <= segmentStart) continue;
      final count = math.max(
        1,
        ((segmentEnd - segmentStart) / stepTicks).ceil(),
      );
      for (var localIndex = 0; localIndex < count; localIndex++) {
        final tick = segmentStart + localIndex * stepTicks;
        if (tick > durationTick + 0.0001) break;
        result.add(
          _SpatialMeasure(
            number: measureNumber + localIndex,
            second: MidiWaterfallParser.tickToSeconds(
              tick.round(),
              score.tempoMap,
              score.ticksPerQuarter,
            ),
            numerator: meter.numerator,
            denominator: meter.denominator,
          ),
        );
      }
      measureNumber += count;
    }
    result.sort((first, second) => first.second.compareTo(second.second));
    return List.unmodifiable(result);
  }
}

class _SpatialMeasure {
  const _SpatialMeasure({
    required this.number,
    required this.second,
    required this.numerator,
    required this.denominator,
  });

  final int number;
  final double second;
  final int numerator;
  final int denominator;
}

class _SpatialPlaybackNote {
  const _SpatialPlaybackNote({
    required this.scoreIndex,
    required this.note,
    required this.cell,
  });

  final int scoreIndex;
  final WaterfallNote note;
  final HexKey cell;
}

class _SpatialTransformGesture {
  const _SpatialTransformGesture({
    required this.firstPointer,
    required this.secondPointer,
    required this.startDistance,
    required this.startAngle,
    required this.startFocalPoint,
    required this.startScaleMultiplier,
    required this.startPan,
    required this.startRotationXDegrees,
    required this.startRotationYDegrees,
    required this.startRotationZDegrees,
  });

  factory _SpatialTransformGesture.capture(
    Map<int, Offset> pointers, {
    required double scaleMultiplier,
    required Offset pan,
    required double rotationXDegrees,
    required double rotationYDegrees,
    required double rotationDegrees,
  }) {
    final entries = pointers.entries.take(2).toList(growable: false);
    final first = entries[0];
    final second = entries[1];
    final delta = second.value - first.value;
    return _SpatialTransformGesture(
      firstPointer: first.key,
      secondPointer: second.key,
      startDistance: math.max(1.0, delta.distance),
      startAngle: math.atan2(delta.dy, delta.dx),
      startFocalPoint: (first.value + second.value) / 2,
      startScaleMultiplier: scaleMultiplier,
      startPan: pan,
      startRotationXDegrees: rotationXDegrees,
      startRotationYDegrees: rotationYDegrees,
      startRotationZDegrees: rotationDegrees,
    );
  }

  final int firstPointer;
  final int secondPointer;
  final double startDistance;
  final double startAngle;
  final Offset startFocalPoint;
  final double startScaleMultiplier;
  final Offset startPan;
  final double startRotationXDegrees;
  final double startRotationYDegrees;
  final double startRotationZDegrees;

  _SpatialTransformMetrics? measure(Map<int, Offset> pointers) {
    final first = pointers[firstPointer];
    final second = pointers[secondPointer];
    if (first == null || second == null) return null;
    final delta = second - first;
    var angleDelta = math.atan2(delta.dy, delta.dx) - startAngle;
    if (angleDelta > math.pi) angleDelta -= math.pi * 2;
    if (angleDelta < -math.pi) angleDelta += math.pi * 2;
    final focalPoint = (first + second) / 2;
    final focalDelta = focalPoint - startFocalPoint;
    return _SpatialTransformMetrics(
      focalPoint: focalPoint,
      scale: math.max(0.01, delta.distance / startDistance),
      rotationXDegrees: -focalDelta.dy * 0.18,
      rotationYDegrees: focalDelta.dx * 0.18,
      rotationZDegrees: angleDelta * 180 / math.pi,
    );
  }
}

class _SpatialTransformMetrics {
  const _SpatialTransformMetrics({
    required this.focalPoint,
    required this.scale,
    required this.rotationXDegrees,
    required this.rotationYDegrees,
    required this.rotationZDegrees,
  });

  final Offset focalPoint;
  final double scale;
  final double rotationXDegrees;
  final double rotationYDegrees;
  final double rotationZDegrees;
}

class _PendingSpatialTransform {
  const _PendingSpatialTransform({
    required this.scaleMultiplier,
    required this.pan,
    required this.rotationXDegrees,
    required this.rotationYDegrees,
    required this.rotationDegrees,
  });

  final double scaleMultiplier;
  final Offset pan;
  final double rotationXDegrees;
  final double rotationYDegrees;
  final double rotationDegrees;
}

class _SpatialBackdropPainter extends CustomPainter {
  const _SpatialBackdropPainter({required this.hasScore, required this.scene});

  final bool hasScore;
  final _SpatialScene scene;

  @override
  void paint(Canvas canvas, Size size) {
    final bounds = Offset.zero & size;
    canvas.drawRect(
      bounds,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x26030A0B), Color(0x18131D1B), Color(0x38030909)],
        ).createShader(bounds),
    );
    canvas.drawRect(
      bounds,
      Paint()
        ..shader = RadialGradient(
          center: const Alignment(0, 0.72),
          radius: 0.95,
          colors: [
            AppPalette.accent.withValues(alpha: 0.10),
            Colors.transparent,
          ],
        ).createShader(bounds),
    );
    canvas.drawPath(
      scene.outlineAt(0),
      Paint()..color = AppPalette.surface.withValues(alpha: 0.54),
    );
    if (!hasScore) {
      final painter = TextPainter(
        text: TextSpan(
          text: 'OPEN MIDI / JSON TO START THE 3D WATERFALL',
          style: TextStyle(
            color: AppPalette.secondaryText.withValues(alpha: 0.64),
            fontSize: 10,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.7,
          ),
        ),
        textDirection: TextDirection.ltr,
        textAlign: TextAlign.center,
      )..layout(maxWidth: size.width * 0.72);
      painter.paint(
        canvas,
        Offset((size.width - painter.width) / 2, 10 * scene.physicalPixel),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _SpatialBackdropPainter oldDelegate) {
    return oldDelegate.hasScore != hasScore || oldDelegate.scene != scene;
  }
}

class _SpatialMeasurePainter extends CustomPainter {
  const _SpatialMeasurePainter({
    required this.playbackIndex,
    required this.playhead,
    required this.previewSeconds,
    required this.scene,
    required this.showLabels,
  });

  final _SpatialPlaybackIndex playbackIndex;
  final double playhead;
  final double previewSeconds;
  final _SpatialScene scene;
  final bool showLabels;

  @override
  void paint(Canvas canvas, Size size) {
    final measures = playbackIndex.visibleMeasuresAt(playhead, previewSeconds);
    if (measures.isEmpty) return;
    final labelPainter = TextPainter(textDirection: TextDirection.ltr);
    for (final measure in measures.reversed) {
      // Keep the negative lead-in: at a negative playhead the first measure
      // remains above the keyboard instead of being clamped to time zero.
      final distanceSeconds = measure.second - playhead;
      if (distanceSeconds < 0) continue;
      final z = scene.zForSeconds(distanceSeconds);
      final path = scene.outlineAt(z);
      final ratio = (distanceSeconds / scene.previewSeconds).clamp(0.0, 1.0);
      canvas.drawPath(
        path,
        Paint()
          ..style = PaintingStyle.fill
          ..color = AppPalette.accent.withValues(
            alpha: 0.018 + (1 - ratio) * 0.022,
          ),
      );
      canvas.drawPath(
        path,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = math.max(scene.physicalPixel, scene.scale * 0.015)
          ..color = AppPalette.accent.withValues(
            alpha: 0.16 + (1 - ratio) * 0.18,
          ),
      );
      if (!showLabels) continue;
      final bounds = path.getBounds();
      labelPainter.text = TextSpan(
        text:
            'M${measure.number} · ${measure.numerator}/${measure.denominator}',
        style: TextStyle(
          color: AppPalette.primaryText.withValues(alpha: 0.56),
          fontSize: math.max(7 * scene.physicalPixel, 8),
          fontWeight: FontWeight.w700,
          fontFamily: 'monospace',
        ),
      );
      labelPainter.layout();
      labelPainter.paint(
        canvas,
        Offset(
          bounds.left + 5 * scene.physicalPixel,
          bounds.top + 3 * scene.physicalPixel,
        ),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _SpatialMeasurePainter oldDelegate) {
    return oldDelegate.playbackIndex != playbackIndex ||
        oldDelegate.playhead != playhead ||
        oldDelegate.previewSeconds != previewSeconds ||
        oldDelegate.scene != scene ||
        oldDelegate.showLabels != showLabels;
  }
}

class _SpatialNotesPainter extends CustomPainter {
  _SpatialNotesPainter({
    required this.playbackIndex,
    required this.playhead,
    required this.previewSeconds,
    required this.scene,
    required this.impacts,
    required this.simplified,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final _SpatialPlaybackIndex playbackIndex;
  final double playhead;
  final double previewSeconds;
  final _SpatialScene scene;
  final Iterable<WaterfallKeyImpact> impacts;
  final bool simplified;

  @override
  void paint(Canvas canvas, Size size) {
    final visible = <_VisibleSpatialNote>[];
    final impactDepths = _spatialImpactDepths(scene, impacts);
    for (final span in _layoutSpatialNoteSpans(
      playbackIndex: playbackIndex,
      playhead: playhead,
      previewSeconds: previewSeconds,
      visualPreviewSeconds: scene.previewSeconds,
    )) {
      var bottomZ = scene.zForSeconds(span.bottomSeconds);
      if (span.bottomSeconds <= 0 && span.note.end > playhead) {
        bottomZ = -(impactDepths[span.cell.coordinate] ?? 0);
      }
      final topZ = scene.zForSeconds(span.topSeconds);
      if (topZ <= bottomZ + 0.001) continue;
      visible.add(
        _VisibleSpatialNote(
          note: span.note,
          cell: span.cell,
          bottomZ: bottomZ,
          topZ: topZ,
          depthOrder: scene.project(span.cell.center, bottomZ).dy,
        ),
      );
    }
    visible.sort(
      (first, second) => first.depthOrder.compareTo(second.depthOrder),
    );
    final simplifyDenseScore = visible.length > 96;
    for (final note in visible) {
      if (simplified ||
          simplifyDenseScore ||
          note.bottomZ > scene.zMaximum * 0.58) {
        _paintSimplifiedNote(canvas, note);
      } else {
        _paintNotePrism(canvas, note);
      }
    }
  }

  void _paintSimplifiedNote(Canvas canvas, _VisibleSpatialNote visible) {
    final velocityRatio = visible.note.velocity.clamp(1, 127) / 127;
    final color = _spatialTrackColor(visible.note);
    final cell = scene.cellsByCoordinate[visible.cell.coordinate]!;
    final bottom = scene.project(visible.cell.center, visible.bottomZ);
    final top = scene.project(visible.cell.center, visible.topZ);
    final width = math.max(
      scene.physicalPixel,
      cell.projectedRadius * (0.12 + velocityRatio * 0.055),
    );
    canvas.drawLine(
      bottom,
      top,
      Paint()
        ..strokeCap = StrokeCap.square
        ..strokeWidth = width * 1.9
        ..color = color.withValues(alpha: 0.22 + velocityRatio * 0.14),
    );
    canvas.drawLine(
      bottom,
      top,
      Paint()
        ..strokeCap = StrokeCap.square
        ..strokeWidth = width
        ..color = color.withValues(alpha: 0.72 + velocityRatio * 0.22),
    );
  }

  void _paintNotePrism(Canvas canvas, _VisibleSpatialNote visible) {
    final note = visible.note;
    final velocityRatio = note.velocity.clamp(1, 127) / 127;
    final radiusScale = _spatialNoteRadiusScale(note.velocity);
    final bottom = scene.verticesFor(
      visible.cell,
      z: visible.bottomZ,
      radiusScale: radiusScale,
    );
    final top = scene.verticesFor(
      visible.cell,
      z: visible.topZ,
      radiusScale: radiusScale,
    );
    final color = _spatialTrackColor(note);
    final cell = scene.cellsByCoordinate[visible.cell.coordinate]!;
    canvas.drawLine(
      scene.project(visible.cell.center, visible.bottomZ),
      scene.project(visible.cell.center, visible.topZ),
      Paint()
        ..strokeWidth = math.max(
          scene.physicalPixel,
          cell.projectedRadius * 0.075,
        )
        ..color = color.withValues(alpha: 0.16),
    );
    for (var index = 0; index < 6; index++) {
      final next = (index + 1) % 6;
      canvas.drawPath(
        _pathFrom([bottom[index], bottom[next], top[next], top[index]]),
        Paint()
          ..color = color.withValues(
            alpha: (0.24 + velocityRatio * 0.28) * (index.isEven ? 1 : 0.72),
          ),
      );
    }
    canvas.drawPath(
      _pathFrom(top),
      Paint()..color = color.withValues(alpha: 0.68 + velocityRatio * 0.24),
    );
    canvas.drawPath(
      _pathFrom(bottom),
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = math.max(scene.physicalPixel, scene.scale * 0.01)
        ..color = color.withValues(alpha: 0.88),
    );
  }

  @override
  bool shouldRepaint(covariant _SpatialNotesPainter oldDelegate) {
    return oldDelegate.playbackIndex != playbackIndex ||
        oldDelegate.playhead != playhead ||
        oldDelegate.previewSeconds != previewSeconds ||
        oldDelegate.scene != scene ||
        oldDelegate.impacts != impacts ||
        oldDelegate.simplified != simplified;
  }
}

class _SpatialKeyboardPainter extends CustomPainter {
  const _SpatialKeyboardPainter({
    required this.scene,
    required this.simplified,
  });

  final _SpatialScene scene;
  final bool simplified;

  @override
  void paint(Canvas canvas, Size size) {
    final labelPainter = TextPainter(textDirection: TextDirection.ltr);
    final linePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = math.max(scene.physicalPixel, scene.scale * 0.022)
      ..color = AppPalette.line.withValues(alpha: 0.92);
    for (final cell in scene.cells) {
      final key = cell.key;
      final hue =
          positiveModulo(key.pitchClass, scene.layout.configuration.period) /
          scene.layout.configuration.period *
          360;
      if (!simplified) {
        canvas.drawPath(
          cell.basePath,
          Paint()..color = const Color(0xFF07100F).withValues(alpha: 0.94),
        );
        for (var index = 0; index < 6; index++) {
          final next = (index + 1) % 6;
          canvas.drawPath(
            _pathFrom([
              cell.baseVertices[index],
              cell.baseVertices[next],
              cell.topVertices[next],
              cell.topVertices[index],
            ]),
            Paint()
              ..color = HSVColor.fromAHSV(
                0.42,
                hue,
                0.66,
                index.isEven ? 0.25 : 0.16,
              ).toColor(),
          );
        }
      }
      canvas.drawPath(
        cell.topPath,
        Paint()..color = HSVColor.fromAHSV(0.96, hue, 0.57, 0.38).toColor(),
      );
      canvas.drawPath(cell.topPath, linePaint);
      if (simplified || cell.projectedRadius < 6 * scene.physicalPixel) {
        continue;
      }
      labelPainter.text = TextSpan(
        text: key.labelForPeriod(scene.layout.configuration.period),
        style: TextStyle(
          color: AppPalette.primaryText.withValues(alpha: 0.86),
          fontSize: math.max(
            6.5 * scene.physicalPixel,
            cell.projectedRadius * 0.42,
          ),
          fontWeight: FontWeight.w700,
          fontFamily: 'monospace',
        ),
      );
      labelPainter.layout(maxWidth: cell.projectedRadius * 1.6);
      labelPainter.paint(
        canvas,
        cell.center - Offset(labelPainter.width / 2, labelPainter.height / 2),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _SpatialKeyboardPainter oldDelegate) {
    return oldDelegate.scene != scene || oldDelegate.simplified != simplified;
  }
}

class _SpatialImpactKeyboardPainter extends CustomPainter {
  _SpatialImpactKeyboardPainter({
    required this.scene,
    required this.impacts,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final _SpatialScene scene;
  final Iterable<WaterfallKeyImpact> impacts;

  @override
  void paint(Canvas canvas, Size size) {
    final depths = _spatialImpactDepths(scene, impacts);
    if (depths.isEmpty) return;
    final thickness = scene.layout.configuration.radius * 0.14;
    final borderPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = math.max(scene.physicalPixel, scene.scale * 0.022)
      ..color = AppPalette.line;
    for (final entry in depths.entries) {
      final cell = scene.cellsByCoordinate[entry.key];
      if (cell == null || entry.value <= 0) continue;
      final key = cell.key;
      final hue =
          positiveModulo(key.pitchClass, scene.layout.configuration.period) /
          scene.layout.configuration.period *
          360;
      canvas.drawPath(
        cell.topPath,
        Paint()..color = HSVColor.fromAHSV(0.96, hue, 0.46, 0.16).toColor(),
      );
      canvas.drawPath(cell.topPath, borderPaint);

      final topVertices = scene.verticesFor(
        key,
        z: -entry.value,
        radiusScale: 0.92,
      );
      final baseVertices = scene.verticesFor(
        key,
        z: -entry.value - thickness,
        radiusScale: 0.92,
      );
      canvas.drawPath(
        _pathFrom(baseVertices),
        Paint()..color = const Color(0xFF07100F).withValues(alpha: 0.96),
      );
      for (var index = 0; index < 6; index++) {
        final next = (index + 1) % 6;
        canvas.drawPath(
          _pathFrom([
            baseVertices[index],
            baseVertices[next],
            topVertices[next],
            topVertices[index],
          ]),
          Paint()
            ..color = HSVColor.fromAHSV(
              0.50,
              hue,
              0.66,
              index.isEven ? 0.25 : 0.16,
            ).toColor(),
        );
      }
      final topPath = _pathFrom(topVertices);
      canvas.drawPath(
        topPath,
        Paint()..color = HSVColor.fromAHSV(1, hue, 0.57, 0.40).toColor(),
      );
      canvas.drawPath(topPath, borderPaint);
    }
  }

  @override
  bool shouldRepaint(covariant _SpatialImpactKeyboardPainter oldDelegate) {
    return oldDelegate.scene != scene || oldDelegate.impacts != impacts;
  }
}

class _SpatialOverlayPainter extends CustomPainter {
  _SpatialOverlayPainter({
    required this.playbackIndex,
    required this.playhead,
    required this.previewSeconds,
    required this.scene,
    required this.selectedCoordinates,
    required this.activeForces,
    required this.impacts,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final _SpatialPlaybackIndex playbackIndex;
  final double playhead;
  final double previewSeconds;
  final _SpatialScene scene;
  final Set<AxialCoordinate> selectedCoordinates;
  final Map<AxialCoordinate, double> activeForces;
  final Iterable<WaterfallKeyImpact> impacts;

  @override
  void paint(Canvas canvas, Size size) {
    final impactDepths = _spatialImpactDepths(scene, impacts);
    final activePlayback = <AxialCoordinate, List<WaterfallNote>>{};
    for (final mapped in playbackIndex.visibleAt(playhead, previewSeconds)) {
      final note = mapped.note;
      if (note.start <= playhead && note.end > playhead) {
        activePlayback
            .putIfAbsent(mapped.cell.coordinate, () => <WaterfallNote>[])
            .add(note);
      }
    }
    for (final entry in activePlayback.entries) {
      final cell = scene.cellsByCoordinate[entry.key];
      if (cell == null) continue;
      final notes = entry.value
        ..sort((first, second) => second.velocity.compareTo(first.velocity));
      final strongest = notes.first;
      final playbackForce = strongest.velocity / 127;
      final period = math.max(1, scene.layout.configuration.period);
      final hue = positiveModulo(cell.key.pitchClass, period) / period * 360;
      final depth = impactDepths[cell.key.coordinate] ?? 0;
      final topPath = _topPathFor(cell, depth);
      canvas.drawPath(
        topPath,
        Paint()
          ..color = HSVColor.fromAHSV(
            0.17 + playbackForce * 0.25,
            hue,
            0.42,
            1,
          ).toColor(),
      );
      _paintLandingCap(canvas, cell, notes, depth);
    }
    for (final coordinate in selectedCoordinates) {
      final cell = scene.cellsByCoordinate[coordinate];
      if (cell == null) continue;
      final force = activeForces[coordinate] ?? 0.76;
      final topPath = _topPathFor(cell, impactDepths[cell.key.coordinate] ?? 0);
      canvas.drawPath(
        topPath,
        Paint()
          ..color = AppPalette.selection.withValues(alpha: 0.10 + force * 0.22),
      );
      canvas.drawPath(
        topPath,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = math.max(
            2 * scene.physicalPixel,
            cell.projectedRadius * (0.10 + force * 0.06),
          )
          ..color = AppPalette.selection,
      );
    }
  }

  void _paintLandingCap(
    Canvas canvas,
    _SpatialCell cell,
    List<WaterfallNote> notes,
    double depth,
  ) {
    final strongest = notes.first;
    final radiusScale = _spatialNoteRadiusScale(strongest.velocity);
    final footprint = _pathFrom(
      scene.verticesFor(cell.key, z: -depth, radiusScale: radiusScale),
    );
    final color = _spatialTrackColor(strongest);
    canvas.drawPath(
      footprint,
      Paint()
        ..color = color.withValues(alpha: 0.68)
        ..style = PaintingStyle.fill,
    );
    canvas.drawPath(
      footprint,
      Paint()
        ..color = color.withValues(alpha: 0.96)
        ..style = PaintingStyle.stroke
        ..strokeWidth = math.max(
          scene.physicalPixel,
          cell.projectedRadius * 0.075,
        ),
    );

    final tracks = <int>{for (final note in notes) note.track}.take(3).toList();
    for (var index = 1; index < tracks.length; index++) {
      final nestedScale = radiusScale * (1 - index * 0.20);
      final nested = _pathFrom(
        scene.verticesFor(cell.key, z: -depth, radiusScale: nestedScale),
      );
      final trackColor = HSVColor.fromAHSV(
        0.94,
        WaterfallParticleSystem.trackHue(tracks[index]),
        0.82,
        0.98,
      ).toColor();
      canvas.drawPath(
        nested,
        Paint()
          ..color = trackColor
          ..style = PaintingStyle.stroke
          ..strokeWidth = math.max(
            scene.physicalPixel,
            cell.projectedRadius * 0.055,
          ),
      );
    }
  }

  Path _topPathFor(_SpatialCell cell, double depth) {
    if (depth <= 0) return cell.topPath;
    return _pathFrom(scene.verticesFor(cell.key, z: -depth, radiusScale: 0.92));
  }

  @override
  bool shouldRepaint(covariant _SpatialOverlayPainter oldDelegate) {
    return oldDelegate.playbackIndex != playbackIndex ||
        oldDelegate.playhead != playhead ||
        oldDelegate.previewSeconds != previewSeconds ||
        oldDelegate.scene != scene ||
        oldDelegate.impacts != impacts ||
        !setEquals(oldDelegate.selectedCoordinates, selectedCoordinates) ||
        !mapEquals(oldDelegate.activeForces, activeForces);
  }
}

class _VisibleSpatialNote {
  const _VisibleSpatialNote({
    required this.note,
    required this.cell,
    required this.bottomZ,
    required this.topZ,
    required this.depthOrder,
  });

  final WaterfallNote note;
  final HexKey cell;
  final double bottomZ;
  final double topZ;
  final double depthOrder;
}

class _SpatialNoteSpan {
  _SpatialNoteSpan({
    required this.scoreIndex,
    required this.note,
    required this.cell,
    required this.bottomSeconds,
    required this.topSeconds,
  });

  final int scoreIndex;
  final WaterfallNote note;
  final HexKey cell;
  final double bottomSeconds;
  double topSeconds;
}

List<_SpatialNoteSpan> _layoutSpatialNoteSpans({
  required _SpatialPlaybackIndex playbackIndex,
  required double playhead,
  required double previewSeconds,
  required double visualPreviewSeconds,
}) {
  final spans = <_SpatialNoteSpan>[];
  final groups = <AxialCoordinate, List<_SpatialNoteSpan>>{};
  final minimumLength = math.min(0.16, visualPreviewSeconds * 0.12);
  for (final mapped in playbackIndex.visibleAt(playhead, previewSeconds)) {
    final note = mapped.note;
    final bottomSeconds = math.max(0.0, note.start - playhead);
    final remaining = math.max(0.0, note.end - playhead);
    final span = _SpatialNoteSpan(
      scoreIndex: mapped.scoreIndex,
      note: note,
      cell: mapped.cell,
      bottomSeconds: bottomSeconds,
      topSeconds: math.max(
        bottomSeconds + minimumLength,
        math.min(visualPreviewSeconds, remaining),
      ),
    );
    spans.add(span);
    groups.putIfAbsent(mapped.cell.coordinate, () => []).add(span);
  }

  final desiredGap = (visualPreviewSeconds * _spatialRepeatGapPreviewRatio)
      .clamp(_spatialRepeatGapMinimumSeconds, _spatialRepeatGapMaximumSeconds);
  final minimumVisibleLength = math.max(
    _spatialMinimumVisibleNoteSeconds,
    visualPreviewSeconds * _spatialMinimumVisibleNotePreviewRatio,
  );
  for (final group in groups.values) {
    if (group.length < 2) continue;
    group.sort((first, second) {
      final start = first.note.start.compareTo(second.note.start);
      if (start != 0) return start;
      final end = first.note.end.compareTo(second.note.end);
      return end != 0 ? end : first.scoreIndex.compareTo(second.scoreIndex);
    });
    for (var index = 1; index < group.length; index++) {
      final previous = group[index - 1];
      final current = group[index];
      final interval = current.bottomSeconds - previous.bottomSeconds;
      if (interval <= minimumVisibleLength) continue;
      final gap = math.min(
        desiredGap,
        interval * _spatialRepeatGapIntervalRatio,
      );
      if (current.bottomSeconds - previous.topSeconds >= gap) continue;
      final separatedTop = math.max(
        previous.bottomSeconds + minimumVisibleLength,
        current.bottomSeconds - gap,
      );
      if (separatedTop < previous.topSeconds) {
        previous.topSeconds = separatedTop;
      }
    }
  }
  return spans;
}

class _SpatialParticlePainter extends CustomPainter {
  _SpatialParticlePainter({
    required this.scene,
    required this.particles,
    required this.impacts,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final _SpatialScene scene;
  final List<WaterfallHitParticle> particles;
  final Iterable<WaterfallKeyImpact> impacts;
  static const int _maximumRenderedParticles = 360;

  @override
  void paint(Canvas canvas, Size size) {
    _paintImpacts(canvas);
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
    final firstParticle = math.max(
      0,
      particles.length - _maximumRenderedParticles,
    );
    final drawEveryGlow = particles.length <= 160;
    for (var index = firstParticle; index < particles.length; index++) {
      final particle = particles[index];
      if (particle.maxLife <= 0 ||
          particle.x < -20 ||
          particle.x > size.width + 20 ||
          particle.y < -28 ||
          particle.y > size.height + 28) {
        continue;
      }
      final lifeRatio = (particle.life / particle.maxLife).clamp(0.0, 1.0);
      final alpha = math.pow(lifeRatio, 1.05).toDouble();
      final particleSize = particle.size * (1 + (1 - alpha) * 0.9);
      final position = Offset(particle.x, particle.y);
      trailPaint
        ..strokeWidth = math.max(1.2 * scene.physicalPixel, particleSize * 0.9)
        ..color = _hsv(
          particle.hue,
          0.94,
          math.min(0.96, particle.lightness + 0.15),
          alpha * 0.66,
        );
      canvas.drawLine(
        position,
        Offset(
          particle.x - particle.vx * 0.024,
          particle.y - particle.vy * 0.024,
        ),
        trailPaint,
      );
      if (drawEveryGlow || index.isEven) {
        glowPaint
          ..color = _hsv(particle.hue, 0.96, 0.88, alpha * 0.30)
          ..maskFilter = null;
        canvas.drawCircle(position, particleSize * 1.85, glowPaint);
      }
      corePaint.color = _hsv(particle.hue, 0.58, 1, alpha * 0.94);
      canvas.drawCircle(
        position,
        math.max(scene.physicalPixel, particleSize * 0.5),
        corePaint,
      );
    }
  }

  void _paintImpacts(Canvas canvas) {
    for (final impact in impacts) {
      if (impact.maxLife <= 0) continue;
      final key = scene.layout.keyForPitch(impact.pitch);
      final cell = key == null ? null : scene.cellsByCoordinate[key.coordinate];
      if (cell == null) continue;
      final lifeRatio = impact.progress;
      final depth = _spatialImpactDepthFor(scene, cell, impact);
      final center = depth > 0
          ? scene.project(cell.key.center, -depth)
          : cell.center;
      final topPath = depth > 0
          ? _pathFrom(scene.verticesFor(cell.key, z: -depth, radiusScale: 0.92))
          : cell.topPath;
      final expansion =
          1 + (1 - lifeRatio) * (0.32 + impact.velocityRatio * 0.42);
      canvas.save();
      canvas.translate(center.dx, center.dy);
      canvas.scale(expansion);
      canvas.translate(-center.dx, -center.dy);
      canvas.drawPath(
        topPath,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = math.max(
            scene.physicalPixel,
            cell.projectedRadius * 0.08 / expansion,
          )
          ..color = _hsv(
            impact.hue,
            0.88,
            1,
            lifeRatio * (0.42 + impact.velocityRatio * 0.46),
          ),
      );
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _SpatialParticlePainter oldDelegate) {
    return oldDelegate.scene != scene ||
        oldDelegate.particles != particles ||
        oldDelegate.impacts != impacts;
  }
}

Path _pathFrom(List<Offset> points) {
  final path = Path();
  if (points.isEmpty) return path;
  path.moveTo(points.first.dx, points.first.dy);
  for (final point in points.skip(1)) {
    path.lineTo(point.dx, point.dy);
  }
  return path..close();
}

Color _hsv(double hue, double saturation, double value, double alpha) {
  return HSVColor.fromAHSV(
    alpha.clamp(0.0, 1.0),
    hue % 360,
    saturation.clamp(0.0, 1.0),
    value.clamp(0.0, 1.0),
  ).toColor();
}
