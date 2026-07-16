import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../app/xensynth_settings.dart';
import '../../core/hex_keyboard.dart';
import '../../core/score.dart';
import '../app_palette.dart';

typedef HexPitchPointerCallback =
    void Function(int pointer, double pitch, int velocity);

class HexKeyboardViewportController extends ChangeNotifier {
  static const double minimumScale = 0.84;
  static const double maximumScale = 3;

  double _scaleMultiplier = minimumScale;
  Offset _pan = Offset.zero;

  double get scaleMultiplier => _scaleMultiplier;
  Offset get pan => _pan;

  void panBy(Offset delta) {
    if (!delta.dx.isFinite || !delta.dy.isFinite || delta == Offset.zero) {
      return;
    }
    _setPan(_pan + delta);
  }

  void zoomBy(double factor) {
    if (!factor.isFinite || factor <= 0 || factor == 1) return;
    final next = (_scaleMultiplier * factor)
        .clamp(minimumScale, maximumScale)
        .toDouble();
    if (next == _scaleMultiplier) return;
    _scaleMultiplier = next;
    notifyListeners();
  }

  void reset() {
    final changed = _scaleMultiplier != minimumScale || _pan != Offset.zero;
    _scaleMultiplier = minimumScale;
    _pan = Offset.zero;
    if (changed) notifyListeners();
  }

  void resetPan() => _setPan(Offset.zero);

  void _resetPanSilently() {
    _pan = Offset.zero;
  }

  void _setConstrainedPan(Offset pan) {
    _setPan(pan);
  }

  void _setPan(Offset pan) {
    if (_pan == pan) return;
    _pan = pan;
    notifyListeners();
  }
}

class HexKeyboardView extends StatefulWidget {
  const HexKeyboardView({
    required this.score,
    required this.playhead,
    required this.settings,
    required this.activePitches,
    required this.onPitchDown,
    required this.onPitchMove,
    required this.onPitchUp,
    this.viewportController,
    super.key,
  });

  final ParsedScore? score;
  final double playhead;
  final XenSynthSettings settings;
  final Map<int, double> activePitches;
  final HexPitchPointerCallback onPitchDown;
  final HexPitchPointerCallback onPitchMove;
  final ValueChanged<int> onPitchUp;
  final HexKeyboardViewportController? viewportController;

  @override
  State<HexKeyboardView> createState() => _HexKeyboardViewState();
}

class _HexKeyboardViewState extends State<HexKeyboardView> {
  final Map<int, HexKey> _pointerCells = {};
  final Map<int, double> _pointerForces = {};
  late HexKeyboardConfiguration _configuration;
  late HexaKeyboardLayout _layout;
  late _HexPlaybackIndex _playbackIndex;
  late HexKeyboardViewportController _viewportController;
  late bool _ownsViewportController;
  _HexRenderCache? _renderCache;
  Offset? _scheduledConstrainedPan;

  @override
  void initState() {
    super.initState();
    _configuration = _configurationFor(widget.settings);
    _layout = HexaKeyboardLayoutEngine.build(_configuration);
    _playbackIndex = _HexPlaybackIndex.build(widget.score, _layout);
    _attachViewportController(widget.viewportController);
  }

  @override
  void didUpdateWidget(covariant HexKeyboardView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.viewportController != oldWidget.viewportController) {
      _scheduledConstrainedPan = null;
      _detachViewportController();
      _attachViewportController(widget.viewportController);
    }
    final next = _configurationFor(widget.settings);
    final configurationChanged = next != _configuration;
    if (configurationChanged) {
      _pointerCells.clear();
      _pointerForces.clear();
      _configuration = next;
      _layout = HexaKeyboardLayoutEngine.build(next);
      _renderCache = null;
      _scheduledConstrainedPan = null;
      _viewportController._resetPanSilently();
    }
    if (configurationChanged || !identical(oldWidget.score, widget.score)) {
      _playbackIndex = _HexPlaybackIndex.build(widget.score, _layout);
    }
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final transform = _HexTransform.fit(
          _layout,
          constraints.biggest,
          scaleMultiplier: _viewportController.scaleMultiplier,
          requestedPan: _viewportController.pan,
        );
        _synchronizeConstrainedPan(transform.viewportPan);
        final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
        final renderCache = _renderCacheFor(transform, devicePixelRatio);
        final selectedCoordinates = _selectedCoordinates();
        final activeForces = _activeForces(selectedCoordinates);
        return Semantics(
          container: true,
          label:
              'Hexagonal microtonal keyboard with ${_layout.cells.length} keys',
          child: Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: (event) => _processPointer(event, transform, true),
            onPointerMove: (event) => _processPointer(event, transform, false),
            onPointerUp: (event) => _release(event.pointer),
            onPointerCancel: (event) => _release(event.pointer),
            child: RepaintBoundary(
              child: CustomPaint(
                painter: _HexKeyboardPainter(
                  layout: _layout,
                  transform: transform,
                  renderCache: renderCache,
                  playbackIndex: _playbackIndex,
                  playhead: widget.playhead,
                  previewSeconds: widget.settings.playbackPreviewSeconds,
                  selectedCoordinates: selectedCoordinates,
                  activeForces: activeForces,
                  devicePixelRatio: devicePixelRatio,
                ),
                size: Size.infinite,
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
    if (!mounted) return;
    setState(() => _renderCache = null);
  }

  void _synchronizeConstrainedPan(Offset constrainedPan) {
    if (constrainedPan == _viewportController.pan ||
        constrainedPan == _scheduledConstrainedPan) {
      return;
    }
    _scheduledConstrainedPan = constrainedPan;
    final controller = _viewportController;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final pending = _scheduledConstrainedPan;
      _scheduledConstrainedPan = null;
      if (!mounted || pending == null || controller != _viewportController) {
        return;
      }
      controller._setConstrainedPan(pending);
    });
  }

  _HexRenderCache _renderCacheFor(
    _HexTransform transform,
    double devicePixelRatio,
  ) {
    final cached = _renderCache;
    if (cached != null &&
        identical(cached.layout, _layout) &&
        cached.transform.scale == transform.scale &&
        cached.transform.offset == transform.offset &&
        cached.devicePixelRatio == devicePixelRatio) {
      return cached;
    }
    return _renderCache = _HexRenderCache.build(
      layout: _layout,
      transform: transform,
      devicePixelRatio: devicePixelRatio,
    );
  }

  void _processPointer(PointerEvent event, _HexTransform transform, bool down) {
    final point = transform.toModel(event.localPosition);
    final previous = _pointerCells[event.pointer];
    final force = _normalizedForce(event);
    final nearest = HexTouchHitTester.keyAt(
      point: point,
      layout: _layout,
      previousCoordinate: previous?.coordinate,
      sensitivity: _touchSensitivity(widget.settings.touchSensitivity),
    );

    if (nearest == null) {
      if (previous != null) {
        setState(() {
          _pointerCells.remove(event.pointer);
          _pointerForces.remove(event.pointer);
        });
        widget.onPitchUp(event.pointer);
      }
      return;
    }

    if (nearest.coordinate == previous?.coordinate) {
      final oldForce = _pointerForces[event.pointer];
      if (oldForce == null || (oldForce - force).abs() >= 0.02) {
        setState(() => _pointerForces[event.pointer] = force);
      }
      return;
    }

    setState(() {
      _pointerCells[event.pointer] = nearest;
      _pointerForces[event.pointer] = force;
    });
    final pitch = nearest.audioPitch.midiPitch;
    final velocity = widget.settings.pseudoPressureEnabled
        ? (24 + force * 103).round().clamp(1, 127)
        : 104;
    if (down || previous == null) {
      widget.onPitchDown(event.pointer, pitch, velocity);
    } else {
      widget.onPitchMove(event.pointer, pitch, velocity);
    }
  }

  void _release(int pointer) {
    if (!_pointerCells.containsKey(pointer)) return;
    setState(() {
      _pointerCells.remove(pointer);
      _pointerForces.remove(pointer);
    });
    widget.onPitchUp(pointer);
  }

  Set<AxialCoordinate> _selectedCoordinates() {
    final result = <AxialCoordinate>{
      for (final cell in _pointerCells.values) cell.coordinate,
    };
    for (final entry in widget.activePitches.entries) {
      final direct = _pointerCells[entry.key];
      final targetStep = ((entry.value - 60) * _configuration.period / 12)
          .round();
      final cell = direct ?? _layout.keyForStep(targetStep);
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
      final previous = result[cell.coordinate];
      result[cell.coordinate] = math.max(previous ?? 0, entry.value);
    }
    return result;
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

  static double _touchSensitivity(double setting) {
    return 1 + setting.clamp(0.0, 1.0).toDouble() * 0.5;
  }

  static HexKeyboardConfiguration _configurationFor(XenSynthSettings settings) {
    final effectivePeriod = settings.edo > 0 ? settings.edo : 12;
    return HexKeyboardConfiguration(
      columns: settings.hexColumns,
      rows: settings.hexRows,
      period: effectivePeriod,
      stepQ: settings.hexStepQ,
      stepR: settings.hexStepR,
      groupByOctave: settings.hexGroupByOctave,
      radius: 24,
      rotationDegrees: settings.hexRotationDegrees,
      frameAcuteAngleDegrees: 72,
    ).normalized();
  }

  @override
  void dispose() {
    _detachViewportController();
    super.dispose();
  }
}

class _HexTransform {
  const _HexTransform({
    required this.scale,
    required this.offset,
    required this.viewportPan,
  });

  final double scale;
  final Offset offset;
  final Offset viewportPan;

  Offset toScreen(HexPoint point) {
    return Offset(point.x * scale + offset.dx, point.y * scale + offset.dy);
  }

  HexPoint toModel(Offset point) {
    return HexPoint(
      (point.dx - offset.dx) / scale,
      (point.dy - offset.dy) / scale,
    );
  }

  static _HexTransform fit(
    HexaKeyboardLayout layout,
    Size size, {
    required double scaleMultiplier,
    required Offset requestedPan,
  }) {
    final bounds = layout.modelBounds;
    final width = math.max(1, bounds.width);
    final height = math.max(1, bounds.height);
    final fittedScale = math.min(
      math.max(1, size.width) / width,
      math.max(1, size.height) / height,
    );
    final safeMultiplier = scaleMultiplier
        .clamp(
          HexKeyboardViewportController.minimumScale,
          HexKeyboardViewportController.maximumScale,
        )
        .toDouble();
    final scale = math.max(0.0001, fittedScale * safeMultiplier);
    final contentWidth = width * scale;
    final contentHeight = height * scale;
    final viewportPan = _constrainKeyboardPan(
      requested: requestedPan,
      contentSize: Size(contentWidth, contentHeight),
      viewportSize: size,
      edgeMargin: 24,
    );
    return _HexTransform(
      scale: scale,
      offset: Offset(
        (size.width - contentWidth) / 2 - bounds.minX * scale + viewportPan.dx,
        (size.height - contentHeight) / 2 -
            bounds.minY * scale +
            viewportPan.dy,
      ),
      viewportPan: viewportPan,
    );
  }

  static Offset _constrainKeyboardPan({
    required Offset requested,
    required Size contentSize,
    required Size viewportSize,
    required double edgeMargin,
  }) {
    return Offset(
      _constrainKeyboardPanAxis(
        requested: requested.dx,
        content: contentSize.width,
        viewport: viewportSize.width,
        edgeMargin: edgeMargin,
      ),
      _constrainKeyboardPanAxis(
        requested: requested.dy,
        content: contentSize.height,
        viewport: viewportSize.height,
        edgeMargin: edgeMargin,
      ),
    );
  }

  static double _constrainKeyboardPanAxis({
    required double requested,
    required double content,
    required double viewport,
    required double edgeMargin,
  }) {
    if (!requested.isFinite || !content.isFinite || !viewport.isFinite) {
      return 0;
    }
    if (content <= viewport + 0.5) return 0;
    final overflowFromCenter = (content - viewport) / 2;
    final limit = overflowFromCenter + math.max(0, edgeMargin);
    return requested.clamp(-limit, limit).toDouble();
  }
}

class _HexRenderCache {
  const _HexRenderCache({
    required this.layout,
    required this.transform,
    required this.devicePixelRatio,
    required this.radius,
    required this.cells,
  });

  factory _HexRenderCache.build({
    required HexaKeyboardLayout layout,
    required _HexTransform transform,
    required double devicePixelRatio,
  }) {
    final period = layout.configuration.period;
    final radius = (layout.configuration.radius - 1.5) * transform.scale;
    final colors = <Color>[
      for (var pitchClass = 0; pitchClass < period; pitchClass++)
        _HexKeyboardPainter._cellColor(pitchClass, period),
    ];
    final rotation = layout.configuration.rotationDegrees.toDouble();
    return _HexRenderCache(
      layout: layout,
      transform: transform,
      devicePixelRatio: devicePixelRatio,
      radius: radius,
      cells: List.unmodifiable([
        for (final cell in layout.cells)
          _HexCellRenderData(
            cell: cell,
            center: transform.toScreen(cell.center),
            path: _HexKeyboardPainter._hexPath(
              transform.toScreen(cell.center),
              radius,
              rotation,
            ),
            baseColor: colors[cell.pitchClass],
          ),
      ]),
    );
  }

  final HexaKeyboardLayout layout;
  final _HexTransform transform;
  final double devicePixelRatio;
  final double radius;
  final List<_HexCellRenderData> cells;
}

class _HexCellRenderData {
  const _HexCellRenderData({
    required this.cell,
    required this.center,
    required this.path,
    required this.baseColor,
  });

  final HexKey cell;
  final Offset center;
  final Path path;
  final Color baseColor;
}

class _HexPlaybackIndex {
  const _HexPlaybackIndex(this.notes);

  factory _HexPlaybackIndex.build(
    ParsedScore? score,
    HexaKeyboardLayout layout,
  ) {
    final source = score?.notes;
    if (source == null || source.isEmpty) {
      return const _HexPlaybackIndex(<_KeyboardPlaybackNote>[]);
    }
    final period = layout.configuration.period;
    final notes = <_KeyboardPlaybackNote>[];
    final previousByCoordinate = <AxialCoordinate, _KeyboardPlaybackNote>{};
    for (var scoreIndex = 0; scoreIndex < source.length; scoreIndex++) {
      final note = source[scoreIndex];
      final step = ((note.pitch - 60) * period / 12).round();
      final cell = layout.keyForStep(step);
      if (cell == null) continue;
      final previous = previousByCoordinate[cell.coordinate];
      final mapped = _KeyboardPlaybackNote(
        scoreIndex: scoreIndex,
        coordinate: cell.coordinate,
        start: note.start,
        end: math.max(note.start, note.end),
        velocity: note.velocity,
        track: note.track,
        repeatedHit:
            previous != null &&
            (note.start - previous.start <= _playbackRepeatWindowSeconds ||
                note.start - previous.end <= _playbackRepeatGapSeconds),
      );
      notes.add(mapped);
      previousByCoordinate[cell.coordinate] = mapped;
    }
    return _HexPlaybackIndex(List.unmodifiable(notes));
  }

  final List<_KeyboardPlaybackNote> notes;
}

class _HexKeyboardPainter extends CustomPainter {
  const _HexKeyboardPainter({
    required this.layout,
    required this.transform,
    required this.renderCache,
    required this.playbackIndex,
    required this.playhead,
    required this.previewSeconds,
    required this.selectedCoordinates,
    required this.activeForces,
    required this.devicePixelRatio,
  });

  final HexaKeyboardLayout layout;
  final _HexTransform transform;
  final _HexRenderCache renderCache;
  final _HexPlaybackIndex playbackIndex;
  final double playhead;
  final double previewSeconds;
  final Set<AxialCoordinate> selectedCoordinates;
  final Map<AxialCoordinate, double> activeForces;
  final double devicePixelRatio;

  int get period => layout.configuration.period;

  @override
  void paint(Canvas canvas, Size size) {
    final physicalPixel = 1 / math.max(1, devicePixelRatio);
    _paintOrigin(canvas);
    final playback = _playbackState();
    final radius = renderCache.radius;
    final labelPainter = TextPainter(textDirection: TextDirection.ltr);
    final selections = <AxialCoordinate>{...selectedCoordinates};
    final fillPaint = Paint();
    final overlayPaint = Paint();
    final borderPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = math.max(physicalPixel, radius * 0.045)
      ..color = AppPalette.line;

    for (final renderData in renderCache.cells) {
      final cell = renderData.cell;
      final center = renderData.center;
      final path = renderData.path;
      final visual = playback[cell.coordinate];
      final activeForce = activeForces[cell.coordinate];
      fillPaint.color = renderData.baseColor;
      canvas.drawPath(path, fillPaint);

      if (visual != null) {
        _paintPlaybackFill(canvas, path, center, radius, cell, visual);
      }

      if (activeForce != null) {
        overlayPaint.color = AppPalette.selection.withValues(
          alpha: 0.08 + activeForce.clamp(0.0, 1.0) * 0.20,
        );
        canvas.drawPath(path, overlayPaint);
      }

      canvas.drawPath(path, borderPaint);
      _paintLabel(canvas, labelPainter, cell, center, radius, physicalPixel);
    }

    _paintSelectionOutlines(canvas, selections, radius, physicalPixel);
    _paintPlaybackEffects(canvas, playback, radius, physicalPixel);
  }

  void _paintPlaybackFill(
    Canvas canvas,
    Path path,
    Offset center,
    double radius,
    HexKey cell,
    _PlaybackVisual visual,
  ) {
    final flash = visual.flash.clamp(0.0, 1.0);
    if (visual.isActive) {
      canvas.drawPath(
        path,
        Paint()
          ..color = _playbackToneColor(
            cell,
            _playbackActiveValue + flash * _playbackFlashValueBoost,
          ),
      );
      return;
    }

    final upcoming = visual.upcoming;
    if (upcoming != null) {
      final progress = visual.upcomingProgress.clamp(0.0, 1.0);
      final sweep = progress * math.pi * 2;
      if (sweep > 0.001) {
        final arcRadius = radius * 1.08;
        final outerValue =
            _playbackDimValue +
            (_playbackPreviewValue - _playbackDimValue) * progress;
        final middleValue =
            _playbackDimValue + (outerValue - _playbackDimValue) * 0.58;
        final rect = Rect.fromCircle(center: center, radius: arcRadius);
        canvas.save();
        canvas.clipPath(path);
        canvas.drawArc(
          rect,
          -math.pi / 2 - sweep / 2,
          sweep,
          true,
          Paint()
            ..shader = RadialGradient(
              colors: [
                _playbackToneColor(cell, _playbackDimValue),
                _playbackToneColor(cell, middleValue),
                _playbackToneColor(cell, outerValue),
              ],
            ).createShader(rect),
        );
        canvas.restore();
      }
    }

    if (flash > 0.01) {
      canvas.drawPath(
        path,
        Paint()
          ..color = _playbackToneColor(
            cell,
            _playbackActiveValue + flash * _playbackFlashValueBoost,
            flash * 0.34,
          ),
      );
    }
  }

  void _paintOrigin(Canvas canvas) {
    final physicalPixel = 1 / math.max(1, devicePixelRatio);
    final origin = transform.toScreen(const HexPoint(0, 0));
    final arm = 8 * physicalPixel;
    final line = Paint()
      ..color = AppPalette.accent.withValues(alpha: 0.72)
      ..strokeWidth = 1.4 * physicalPixel;
    canvas.drawLine(origin - Offset(arm, 0), origin + Offset(arm, 0), line);
    canvas.drawLine(origin - Offset(0, arm), origin + Offset(0, arm), line);
    canvas.drawCircle(
      origin,
      2.5 * physicalPixel,
      Paint()..color = AppPalette.accent,
    );
  }

  void _paintLabel(
    Canvas canvas,
    TextPainter painter,
    HexKey cell,
    Offset center,
    double radius,
    double physicalPixel,
  ) {
    if (radius < 5 * physicalPixel) return;
    painter.text = TextSpan(
      text: cell.labelForPeriod(period),
      style: TextStyle(
        color: AppPalette.primaryText,
        fontSize: math.max(7 * physicalPixel, radius * 0.43),
        fontWeight: FontWeight.w700,
        fontFamily: 'monospace',
      ),
    );
    painter
      ..textAlign = TextAlign.center
      ..layout(maxWidth: radius * 1.65);
    painter.paint(
      canvas,
      center - Offset(painter.width / 2, painter.height / 2),
    );
  }

  void _paintSelectionOutlines(
    Canvas canvas,
    Set<AxialCoordinate> selections,
    double radius,
    double physicalPixel,
  ) {
    final outerPaint = Paint()..style = PaintingStyle.stroke;
    final innerPaint = Paint()..style = PaintingStyle.stroke;
    for (final coordinate in selections) {
      final cell = layout.cellAt(coordinate);
      if (cell == null) continue;
      final force = activeForces[coordinate];
      final strokeWidth = math.max(
        2.4 * physicalPixel,
        radius * (force == null ? 0.12 : 0.12 + force * 0.08),
      );
      final path = _hexPath(
        transform.toScreen(cell.center),
        radius,
        layout.configuration.rotationDegrees.toDouble(),
      );
      outerPaint
        ..strokeWidth = strokeWidth + 2 * physicalPixel
        ..color = AppPalette.selection.withValues(alpha: 0.32);
      innerPaint
        ..strokeWidth = strokeWidth
        ..color = AppPalette.selection;
      canvas.drawPath(path, outerPaint);
      canvas.drawPath(path, innerPaint);
    }
  }

  void _paintPlaybackEffects(
    Canvas canvas,
    Map<AxialCoordinate, _PlaybackVisual> playback,
    double radius,
    double physicalPixel,
  ) {
    for (final entry in playback.entries) {
      final cell = layout.cellAt(entry.key);
      if (cell == null) continue;
      final visual = entry.value;
      final center = transform.toScreen(cell.center);
      if (visual.isActive) {
        _paintPlaybackTrackOutlines(
          canvas,
          center,
          radius,
          physicalPixel,
          visual,
        );
        _paintActivePlaybackParticles(canvas, center, radius, cell, visual);
      }
      if (visual.completedNotes.isNotEmpty) {
        _paintCompletedPlaybackParticles(canvas, center, radius, cell, visual);
      }
    }
  }

  void _paintPlaybackTrackOutlines(
    Canvas canvas,
    Offset center,
    double radius,
    double physicalPixel,
    _PlaybackVisual visual,
  ) {
    final strokeWidth = math.max(1.35 * physicalPixel, radius * 0.052);
    final layerSpacing = math.max(strokeWidth * 1.62, radius * 0.068);
    final tracks = visual.activeTracks.take(_maximumPlaybackTrackLayers);
    var index = 0;
    for (final track in tracks) {
      final layerRadius = math.max(
        radius * 0.42,
        radius * 1.01 - index * layerSpacing,
      );
      final path = _hexPath(
        center,
        layerRadius,
        layout.configuration.rotationDegrees.toDouble(),
      );
      final color = _trackColor(track);
      canvas.drawPath(
        path,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = strokeWidth * 2.45
          ..color = color.withValues(
            alpha: 0.20 + visual.flash.clamp(0.0, 1.0) * 0.18,
          ),
      );
      canvas.drawPath(
        path,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = strokeWidth
          ..color = color.withValues(
            alpha: 0.86 + visual.flash.clamp(0.0, 1.0) * 0.14,
          ),
      );
      index++;
    }
  }

  void _paintActivePlaybackParticles(
    Canvas canvas,
    Offset center,
    double radius,
    HexKey cell,
    _PlaybackVisual visual,
  ) {
    final flash = visual.flash.clamp(0.0, 1.0);
    for (final note in visual.activeNotes.take(_maximumActiveParticleNotes)) {
      final emphasized = note.repeatedHit || flash >= 0.34;
      final particleCount = emphasized ? 4 : 1;
      final elapsed = math.max(0.0, playhead - note.start);
      final velocityRatio = note.velocity.clamp(1, 127) / 127;
      for (
        var particleIndex = 0;
        particleIndex < particleCount;
        particleIndex++
      ) {
        final seed = _playbackParticleSeed(
          note,
          particleIndex,
          _activeParticleSalt,
        );
        final phaseSeed = _deterministicUnit(seed);
        final rate = 0.50 + _deterministicUnit(seed ^ 0x13579BDF) * 0.38;
        final phase = (elapsed * rate + phaseSeed) % 1.0;
        final spread =
            (_deterministicUnit(seed ^ 0x02468ACE) - 0.5) * math.pi * 0.74;
        final flutter = math.sin((phase + phaseSeed) * math.pi * 2) * 0.08;
        final angle = -math.pi / 2 + spread + flutter;
        final distance = radius * (0.08 + phase * 0.58);
        final position = Offset(
          center.dx + math.cos(angle) * distance,
          center.dy + math.sin(angle) * distance - radius * phase * 0.05,
        );
        final alpha =
            (1 - phase) * (0.24 + velocityRatio * 0.24 + flash * 0.38);
        final particleRadius = math.max(
          0.85,
          radius *
              (0.022 + _deterministicUnit(seed ^ 0x01020304) * 0.030) *
              (1 - phase * 0.42),
        );
        canvas.drawCircle(
          position,
          particleRadius,
          Paint()..color = _playbackToneColor(cell, 0.82 + flash * 0.16, alpha),
        );
      }
    }
  }

  void _paintCompletedPlaybackParticles(
    Canvas canvas,
    Offset center,
    double radius,
    HexKey cell,
    _PlaybackVisual visual,
  ) {
    for (final completed in visual.completedNotes) {
      final note = completed.note;
      final progress = completed.progress.clamp(0.0, 1.0);
      final fade = (1 - progress) * (1 - progress);
      final expansion = progress * (2 - progress);
      final particleCount = note.repeatedHit || visual.flash >= 0.34 ? 12 : 7;
      final velocityRatio = note.velocity.clamp(1, 127) / 127;
      for (
        var particleIndex = 0;
        particleIndex < particleCount;
        particleIndex++
      ) {
        final seed = _playbackParticleSeed(
          note,
          particleIndex,
          _completedParticleSalt,
        );
        final direction = _deterministicUnit(seed) * math.pi * 2;
        final speed = 0.52 + _deterministicUnit(seed ^ 0x03141592) * 0.48;
        final distance = radius * (0.10 + expansion * speed);
        final gravity =
            radius *
            progress *
            progress *
            (0.04 + _deterministicUnit(seed ^ 0x02718281) * 0.14);
        final particleRadius = math.max(
          0.75,
          radius *
              (0.022 + _deterministicUnit(seed ^ 0x055AA55A) * 0.036) *
              (1 - progress * 0.48),
        );
        canvas.drawCircle(
          Offset(
            center.dx + math.cos(direction) * distance,
            center.dy + math.sin(direction) * distance + gravity,
          ),
          particleRadius,
          Paint()
            ..color = _playbackToneColor(
              cell,
              0.84 + velocityRatio * 0.14,
              fade * (0.58 + velocityRatio * 0.34),
            ),
        );
      }
    }
  }

  Map<AxialCoordinate, _PlaybackVisual> _playbackState() {
    final result = <AxialCoordinate, _PlaybackVisual>{};
    final recentHitCounts = <AxialCoordinate, int>{};
    for (final note in playbackIndex.notes) {
      final visual = result.putIfAbsent(note.coordinate, _PlaybackVisual.new);
      if (previewSeconds > 0 &&
          note.start > playhead &&
          note.start <= playhead + previewSeconds) {
        final progress =
            (1 - math.max(0.0, note.start - playhead) / previewSeconds).clamp(
              0.0,
              1.0,
            );
        if (visual.upcoming == null || note.start < visual.upcoming!.start) {
          visual
            ..upcoming = note
            ..upcomingProgress = progress;
        }
      }
      if (note.start <= playhead && note.end > playhead) {
        visual.activeNotes.add(note);
      }
      if (note.end <= playhead &&
          note.end >= playhead - _playbackCompletionBurstSeconds &&
          visual.completedNotes.length < _maximumCompletedNotesPerKey) {
        final age = math.max(0.0, playhead - note.end);
        visual.completedNotes.add(
          _CompletedPlaybackNote(
            note: note,
            progress: (age / _playbackCompletionBurstSeconds).clamp(0.0, 1.0),
          ),
        );
      }
      if (note.start <= playhead &&
          note.start >= playhead - _playbackRepeatWindowSeconds) {
        recentHitCounts[note.coordinate] =
            (recentHitCounts[note.coordinate] ?? 0) + 1;
        final age = math.max(0.0, playhead - note.start);
        visual.flash = math.max(visual.flash, math.exp(-age * 12));
      }
    }

    for (final entry in recentHitCounts.entries) {
      final visual = result[entry.key];
      if (visual == null) continue;
      final repeatedActive = visual.activeNotes.any((note) => note.repeatedHit);
      if (entry.value >= 2 || repeatedActive) {
        final pulse = 0.5 + 0.5 * math.sin(playhead * math.pi * 18);
        visual.flash = math.max(visual.flash, 0.34 + pulse * 0.66);
      }
    }

    result.removeWhere((_, visual) => visual.isEmpty);
    for (final visual in result.values) {
      visual.activeNotes.sort(
        (first, second) => first.track != second.track
            ? first.track.compareTo(second.track)
            : first.scoreIndex.compareTo(second.scoreIndex),
      );
      visual.flash = visual.flash.clamp(0.0, 1.0);
    }
    return result;
  }

  Color _playbackToneColor(HexKey cell, double value, [double alpha = 1]) {
    final hue =
        positiveModulo(cell.pitchClass, math.max(1, period)) /
        math.max(1, period) *
        360;
    return HSVColor.fromAHSV(
      alpha.clamp(0.0, 1.0),
      hue,
      0.62,
      value.clamp(0.0, 1.0),
    ).toColor();
  }

  static Color _trackColor(int track) {
    final hue = track >= 0 && track < _playbackTrackHues.length
        ? _playbackTrackHues[track]
        : (_playbackTrackHues.first + track * _playbackTrackGoldenAngle) % 360;
    return HSVColor.fromAHSV(
      1,
      hue < 0 ? hue + 360 : hue,
      0.86,
      0.98,
    ).toColor();
  }

  static int _playbackParticleSeed(
    _KeyboardPlaybackNote note,
    int particleIndex,
    int salt,
  ) {
    return (note.scoreIndex * 73856093 ^
            note.track * 19349663 ^
            note.coordinate.q * 83492791 ^
            note.coordinate.r * 49979687 ^
            particleIndex * 961748927 ^
            salt)
        .toSigned(32);
  }

  static double _deterministicUnit(int seed) {
    var value = seed.toSigned(32);
    value = (value ^ (value.toUnsigned(32) >>> 16)).toSigned(32);
    value = (value * -2048144789).toSigned(32);
    value = (value ^ (value.toUnsigned(32) >>> 13)).toSigned(32);
    value = (value * -1028477387).toSigned(32);
    value = (value ^ (value.toUnsigned(32) >>> 16)).toSigned(32);
    return ((value.toUnsigned(32) >>> 8) & 0x00FFFFFF) / 16777215;
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
    final safePeriod = math.max(1, period);
    final hue = positiveModulo(pitchClass, safePeriod) / safePeriod * 360;
    return HSVColor.fromAHSV(1, hue, 0.62, 0.43).toColor();
  }

  @override
  bool shouldRepaint(covariant _HexKeyboardPainter oldDelegate) {
    return oldDelegate.layout != layout ||
        oldDelegate.transform.scale != transform.scale ||
        oldDelegate.transform.offset != transform.offset ||
        oldDelegate.renderCache != renderCache ||
        oldDelegate.playbackIndex != playbackIndex ||
        oldDelegate.playhead != playhead ||
        oldDelegate.previewSeconds != previewSeconds ||
        oldDelegate.devicePixelRatio != devicePixelRatio ||
        !setEquals(oldDelegate.selectedCoordinates, selectedCoordinates) ||
        !mapEquals(oldDelegate.activeForces, activeForces);
  }
}

class _KeyboardPlaybackNote {
  const _KeyboardPlaybackNote({
    required this.scoreIndex,
    required this.coordinate,
    required this.start,
    required this.end,
    required this.velocity,
    required this.track,
    required this.repeatedHit,
  });

  final int scoreIndex;
  final AxialCoordinate coordinate;
  final double start;
  final double end;
  final int velocity;
  final int track;
  final bool repeatedHit;
}

class _CompletedPlaybackNote {
  const _CompletedPlaybackNote({required this.note, required this.progress});

  final _KeyboardPlaybackNote note;
  final double progress;
}

class _PlaybackVisual {
  _KeyboardPlaybackNote? upcoming;
  double upcomingProgress = 0;
  final List<_KeyboardPlaybackNote> activeNotes = [];
  final List<_CompletedPlaybackNote> completedNotes = [];
  double flash = 0;

  bool get isActive => activeNotes.isNotEmpty;
  bool get isEmpty =>
      upcoming == null &&
      activeNotes.isEmpty &&
      completedNotes.isEmpty &&
      flash <= 0.01;
  List<int> get activeTracks =>
      activeNotes.map((note) => note.track).toSet().toList()..sort();
}

const _playbackCompletionBurstSeconds = 0.34;
const _playbackRepeatWindowSeconds = 0.42;
const _playbackRepeatGapSeconds = 0.18;
const _playbackTrackGoldenAngle = 137.508;
const _playbackDimValue = 0.22;
const _playbackPreviewValue = 0.76;
const _playbackActiveValue = 0.88;
const _playbackFlashValueBoost = 0.26;
const _maximumPlaybackTrackLayers = 8;
const _maximumActiveParticleNotes = 3;
const _maximumCompletedNotesPerKey = 4;
const _activeParticleSalt = 0x1A2B3C4D;
const _completedParticleSalt = 0x4D3C2B1A;
const _playbackTrackHues = <double>[190, 28, 132, 48, 264, 158, 330, 88];
