import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../app/xensynth_settings.dart';
import '../../core/hex_keyboard.dart';
import '../../core/score.dart';
import '../app_palette.dart';

typedef HexPitchPointerCallback =
    void Function(int pointer, double pitch, int velocity);
typedef HexBasisDirectionCallback =
    void Function(
      HexNeighborDirection qDirection,
      HexNeighborDirection rDirection,
    );

class HexKeyboardViewportController extends ChangeNotifier {
  static const double minimumScale = 0.84;
  static const double maximumScale = 3;
  static const double maximumTiltDegrees = 62;

  double _scaleMultiplier = minimumScale;
  Offset _pan = Offset.zero;
  double _rotationXDegrees = 0;
  double _rotationYDegrees = 0;
  double _rotationZDegrees = 0;

  double get scaleMultiplier => _scaleMultiplier;
  Offset get pan => _pan;
  double get rotationXDegrees => _rotationXDegrees;
  double get rotationYDegrees => _rotationYDegrees;
  double get rotationZDegrees => _rotationZDegrees;
  double get rotationDegrees => _rotationZDegrees;

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

  void rotateBy(double deltaDegrees) {
    if (!deltaDegrees.isFinite || deltaDegrees == 0) return;
    final next = _normalizeRotation(_rotationZDegrees + deltaDegrees);
    if (next == _rotationZDegrees) return;
    _rotationZDegrees = next;
    notifyListeners();
  }

  void setSpatialTransform({
    required double scaleMultiplier,
    required Offset pan,
    required double rotationDegrees,
    double? rotationXDegrees,
    double? rotationYDegrees,
  }) {
    final requestedX = rotationXDegrees ?? _rotationXDegrees;
    final requestedY = rotationYDegrees ?? _rotationYDegrees;
    if (!scaleMultiplier.isFinite ||
        !pan.dx.isFinite ||
        !pan.dy.isFinite ||
        !rotationDegrees.isFinite ||
        !requestedX.isFinite ||
        !requestedY.isFinite) {
      return;
    }
    final nextScale = scaleMultiplier
        .clamp(minimumScale, maximumScale)
        .toDouble();
    final nextRotationX = requestedX
        .clamp(-maximumTiltDegrees, maximumTiltDegrees)
        .toDouble();
    final nextRotationY = requestedY
        .clamp(-maximumTiltDegrees, maximumTiltDegrees)
        .toDouble();
    final nextRotationZ = _normalizeRotation(rotationDegrees);
    if (nextScale == _scaleMultiplier &&
        pan == _pan &&
        nextRotationX == _rotationXDegrees &&
        nextRotationY == _rotationYDegrees &&
        nextRotationZ == _rotationZDegrees) {
      return;
    }
    _scaleMultiplier = nextScale;
    _pan = pan;
    _rotationXDegrees = nextRotationX;
    _rotationYDegrees = nextRotationY;
    _rotationZDegrees = nextRotationZ;
    notifyListeners();
  }

  void reset() {
    final changed =
        _scaleMultiplier != minimumScale ||
        _pan != Offset.zero ||
        _rotationXDegrees != 0 ||
        _rotationYDegrees != 0 ||
        _rotationZDegrees != 0;
    _scaleMultiplier = minimumScale;
    _pan = Offset.zero;
    _rotationXDegrees = 0;
    _rotationYDegrees = 0;
    _rotationZDegrees = 0;
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

  static double _normalizeRotation(double value) {
    final normalized = (value + 180) % 360;
    return normalized < 0 ? normalized + 180 : normalized - 180;
  }
}

class HexKeyboardView extends StatefulWidget {
  const HexKeyboardView({
    required this.score,
    required this.playhead,
    required this.settings,
    required this.activePitches,
    this.activePitchVelocities = const {},
    required this.onPitchDown,
    required this.onPitchMove,
    required this.onPitchUp,
    this.viewportController,
    this.onControlInteraction,
    this.basisEditorVisible = false,
    this.onBasisDirectionsChanged,
    this.onBasisEditorDismissed,
    super.key,
  });

  final ParsedScore? score;
  final double playhead;
  final XenSynthSettings settings;
  final Map<int, double> activePitches;
  final Map<int, int> activePitchVelocities;
  final HexPitchPointerCallback onPitchDown;
  final HexPitchPointerCallback onPitchMove;
  final ValueChanged<int> onPitchUp;
  final HexKeyboardViewportController? viewportController;
  final VoidCallback? onControlInteraction;
  final bool basisEditorVisible;
  final HexBasisDirectionCallback? onBasisDirectionsChanged;
  final VoidCallback? onBasisEditorDismissed;

  @override
  State<HexKeyboardView> createState() => _HexKeyboardViewState();
}

class _HexKeyboardViewState extends State<HexKeyboardView> {
  static const double _controlMargin = 14;
  static const double _rotationControlHapticStepPixels = 12;

  final Map<int, HexKey> _pointerCells = {};
  final Map<int, double> _pointerForces = {};
  final Set<int> _controlPointers = {};
  late HexKeyboardConfiguration _configuration;
  late HexaKeyboardLayout _layout;
  late _HexPlaybackIndex _playbackIndex;
  late HexKeyboardViewportController _viewportController;
  late bool _ownsViewportController;
  _HexRenderCache? _renderCache;
  Offset? _scheduledConstrainedPan;
  _HexPanRotationControlMode? _panRotationControlMode;
  Offset? _panRotationDragOrigin;
  Offset _panRotationDragStartPan = Offset.zero;
  double _panRotationDragStartDegrees = 0;
  double _panRotationDragStartAngle = 0;
  double _panRotationHapticTravel = 0;
  bool _panRotationHasUpdated = false;
  int? _basisDragPointer;
  _HexBasisAxis? _basisDragAxis;
  HexNeighborDirection? _basisDragDirection;
  double _basisDragHapticTravel = 0;
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
    if (oldWidget.basisEditorVisible && !widget.basisEditorVisible) {
      _clearBasisDrag();
    }
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
      _controlPointers.clear();
      _endPanRotationControl();
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
          rotationDegrees: _viewportController.rotationDegrees,
        );
        _synchronizeConstrainedPan(transform.viewportPan);
        final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
        final renderCache = _renderCacheFor(transform, devicePixelRatio);
        final selectedCoordinates = _selectedCoordinates();
        final activeForces = _activeForces();
        final controlExtent = _controlExtentFor(constraints.biggest);
        return Semantics(
          container: true,
          label:
              'Hexagonal microtonal keyboard with ${_layout.cells.length} keys',
          hint: widget.basisEditorVisible
              ? '拖动Q或R向量选择六边形相邻方向'
              : '滚动左上角滚轮缩放，拖动右上角球体自由移动，拖动外环绕原点旋转',
          child: Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: (event) =>
                _handlePointerDown(event, transform, constraints.biggest),
            onPointerMove: (event) => _handlePointerMove(event, transform),
            onPointerUp: _handlePointerEnd,
            onPointerCancel: _handlePointerEnd,
            child: RepaintBoundary(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  RepaintBoundary(
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
                  if (widget.basisEditorVisible) ...[
                    Positioned.fill(
                      child: ColoredBox(
                        color: Colors.black.withValues(alpha: 0.22),
                      ),
                    ),
                    Positioned.fill(
                      child: Semantics(
                        key: const ValueKey('hex-basis-vector-editor'),
                        container: true,
                        label: 'Hex Q and R basis vector editor',
                        child: CustomPaint(
                          painter: _HexBasisVectorPainter(
                            geometry: _basisGeometry(transform),
                            qStep: widget.settings.hexStepQ,
                            rStep: widget.settings.hexStepR,
                            activeAxis: _basisDragAxis,
                          ),
                        ),
                      ),
                    ),
                  ] else ...[
                    Positioned(
                      left: _controlMargin,
                      top: _controlMargin,
                      width: controlExtent,
                      height: controlExtent,
                      child: _HexZoomControl(
                        onZoom: _viewportController.zoomBy,
                        onInteraction: widget.onControlInteraction,
                      ),
                    ),
                    Positioned(
                      right: _controlMargin,
                      top: _controlMargin,
                      width: controlExtent,
                      height: controlExtent,
                      child: _HexPanRotationControl(
                        rotationDegrees: _viewportController.rotationDegrees,
                        onPanStart: (details) =>
                            _startPanRotationControl(details, controlExtent),
                        onPanUpdate: (details) =>
                            _updatePanRotationControl(details, controlExtent),
                        onPanEnd: (_) => _endPanRotationControl(),
                        onPanCancel: _endPanRotationControl,
                      ),
                    ),
                  ],
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
        cached.transform.rotationDegrees == transform.rotationDegrees &&
        cached.devicePixelRatio == devicePixelRatio) {
      return cached;
    }
    return _renderCache = _HexRenderCache.build(
      layout: _layout,
      transform: transform,
      devicePixelRatio: devicePixelRatio,
    );
  }

  double _controlExtentFor(Size size) {
    return (size.shortestSide * 0.19).clamp(84.0, 108.0).toDouble();
  }

  bool _isInsideCornerControl(Offset position, Size size) {
    final extent = _controlExtentFor(size);
    final leftControl = Rect.fromLTWH(
      _controlMargin,
      _controlMargin,
      extent,
      extent,
    );
    final rightControl = Rect.fromLTWH(
      size.width - _controlMargin - extent,
      _controlMargin,
      extent,
      extent,
    );
    return leftControl.contains(position) || rightControl.contains(position);
  }

  void _handlePointerDown(
    PointerDownEvent event,
    _HexTransform transform,
    Size size,
  ) {
    if (widget.basisEditorVisible) {
      _handleBasisPointerDown(event, transform);
      return;
    }
    if (_isInsideCornerControl(event.localPosition, size)) {
      _controlPointers.add(event.pointer);
      return;
    }
    _processPointer(event, transform, true);
  }

  void _handlePointerMove(PointerMoveEvent event, _HexTransform transform) {
    if (event.pointer == _basisDragPointer) {
      _handleBasisPointerMove(event, transform);
      return;
    }
    if (_controlPointers.contains(event.pointer)) return;
    _processPointer(event, transform, false);
  }

  void _handlePointerEnd(PointerEvent event) {
    if (event.pointer == _basisDragPointer) {
      setState(_clearBasisDrag);
      return;
    }
    if (_controlPointers.remove(event.pointer)) return;
    _release(event.pointer);
  }

  void _handleBasisPointerDown(
    PointerDownEvent event,
    _HexTransform transform,
  ) {
    final geometry = _basisGeometry(transform);
    final axis = geometry.hitTest(event.localPosition);
    if (axis == null) {
      _controlPointers.add(event.pointer);
      widget.onBasisEditorDismissed?.call();
      return;
    }
    _basisDragPointer = event.pointer;
    _basisDragAxis = axis;
    _basisDragDirection = switch (axis) {
      _HexBasisAxis.q => geometry.qDirection,
      _HexBasisAxis.r => geometry.rDirection,
    };
    _basisDragHapticTravel = 0;
    widget.onControlInteraction?.call();
    setState(() {});
  }

  void _handleBasisPointerMove(
    PointerMoveEvent event,
    _HexTransform transform,
  ) {
    final axis = _basisDragAxis;
    if (axis == null) return;
    final travel = event.delta.distance;
    if (travel.isFinite && travel > 0) {
      _basisDragHapticTravel += travel;
      if (_basisDragHapticTravel >= _rotationControlHapticStepPixels) {
        widget.onControlInteraction?.call();
        _basisDragHapticTravel = _basisDragHapticTravel.remainder(
          _rotationControlHapticStepPixels,
        );
      }
    }

    final geometry = _basisGeometry(transform);
    if ((event.localPosition - geometry.origin).distance <
        _HexBasisVectorGeometry.minimumDragRadius) {
      return;
    }
    final otherDirection = switch (axis) {
      _HexBasisAxis.q => geometry.rDirection,
      _HexBasisAxis.r => geometry.qDirection,
    };
    final candidate = geometry.nearestDirection(
      event.localPosition,
      excludingParallelTo: otherDirection,
    );
    if (candidate == _basisDragDirection) return;
    setState(() => _basisDragDirection = candidate);
    final qDirection = axis == _HexBasisAxis.q
        ? candidate
        : geometry.qDirection;
    final rDirection = axis == _HexBasisAxis.r
        ? candidate
        : geometry.rDirection;
    widget.onBasisDirectionsChanged?.call(qDirection, rDirection);
  }

  _HexBasisVectorGeometry _basisGeometry(_HexTransform transform) {
    final qDirection = _basisDragAxis == _HexBasisAxis.q
        ? _basisDragDirection ?? widget.settings.hexQDirection
        : widget.settings.hexQDirection;
    final rDirection = _basisDragAxis == _HexBasisAxis.r
        ? _basisDragDirection ?? widget.settings.hexRDirection
        : widget.settings.hexRDirection;
    return _HexBasisVectorGeometry.build(
      configuration: _configuration,
      transform: transform,
      qDirection: qDirection,
      rDirection: rDirection,
    );
  }

  void _clearBasisDrag() {
    _basisDragPointer = null;
    _basisDragAxis = null;
    _basisDragDirection = null;
    _basisDragHapticTravel = 0;
  }

  void _startPanRotationControl(DragStartDetails details, double extent) {
    _panRotationHapticTravel = 0;
    _panRotationHasUpdated = false;
    widget.onControlInteraction?.call();
    final center = Offset(extent / 2, extent / 2);
    final radialOffset = details.localPosition - center;
    _panRotationControlMode = radialOffset.distance >= extent * 0.36
        ? _HexPanRotationControlMode.rotation
        : _HexPanRotationControlMode.freePan;
    _panRotationDragOrigin = details.localPosition;
    _panRotationDragStartPan = _viewportController.pan;
    _panRotationDragStartDegrees = _viewportController.rotationDegrees;
    _panRotationDragStartAngle = math.atan2(radialOffset.dy, radialOffset.dx);
  }

  void _updatePanRotationControl(DragUpdateDetails details, double extent) {
    final mode = _panRotationControlMode;
    final origin = _panRotationDragOrigin;
    if (mode == null || origin == null) return;
    if (_panRotationHasUpdated) {
      _advancePanRotationHapticScale(details.delta);
    } else {
      _panRotationHasUpdated = true;
    }

    var pan = _panRotationDragStartPan;
    var rotationDegrees = _panRotationDragStartDegrees;
    switch (mode) {
      case _HexPanRotationControlMode.freePan:
        pan += details.localPosition - origin;
      case _HexPanRotationControlMode.rotation:
        final center = Offset(extent / 2, extent / 2);
        final radialOffset = details.localPosition - center;
        var angleDelta =
            math.atan2(radialOffset.dy, radialOffset.dx) -
            _panRotationDragStartAngle;
        if (angleDelta > math.pi) angleDelta -= math.pi * 2;
        if (angleDelta < -math.pi) angleDelta += math.pi * 2;
        rotationDegrees += angleDelta * 180 / math.pi;
    }
    _viewportController.setSpatialTransform(
      scaleMultiplier: _viewportController.scaleMultiplier,
      pan: pan,
      rotationXDegrees: _viewportController.rotationXDegrees,
      rotationYDegrees: _viewportController.rotationYDegrees,
      rotationDegrees: rotationDegrees,
    );
  }

  void _endPanRotationControl() {
    _panRotationControlMode = null;
    _panRotationDragOrigin = null;
    _panRotationHapticTravel = 0;
    _panRotationHasUpdated = false;
  }

  void _advancePanRotationHapticScale(Offset delta) {
    final distance = delta.distance;
    if (!distance.isFinite || distance <= 0) return;
    _panRotationHapticTravel += distance;
    if (_panRotationHapticTravel < _rotationControlHapticStepPixels) return;
    widget.onControlInteraction?.call();
    _panRotationHapticTravel = _panRotationHapticTravel.remainder(
      _rotationControlHapticStepPixels,
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
      final cell = direct ?? _layout.keyForPitch(entry.value);
      if (cell != null) result.add(cell.coordinate);
    }
    return result;
  }

  Map<AxialCoordinate, double> _activeForces() {
    final result = <AxialCoordinate, double>{};
    for (final entry in widget.activePitches.entries) {
      final cell = _pointerCells[entry.key] ?? _layout.keyForPitch(entry.value);
      if (cell == null) continue;
      final velocity = widget.activePitchVelocities[entry.key] ?? 96;
      final force = ((velocity.clamp(1, 127) - 1) / 126).toDouble();
      result[cell.coordinate] = math.max(result[cell.coordinate] ?? 0, force);
    }
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
    return settings.hexKeyboardConfiguration;
  }

  @override
  void dispose() {
    _controlPointers.clear();
    _clearBasisDrag();
    _endPanRotationControl();
    _detachViewportController();
    super.dispose();
  }
}

enum _HexPanRotationControlMode { freePan, rotation }

enum _HexBasisAxis { q, r }

class _HexBasisVectorGeometry {
  const _HexBasisVectorGeometry({
    required this.origin,
    required this.qDirection,
    required this.rDirection,
    required this.qUnit,
    required this.rUnit,
    required this.vectorLength,
  });

  factory _HexBasisVectorGeometry.build({
    required HexKeyboardConfiguration configuration,
    required _HexTransform transform,
    required HexNeighborDirection qDirection,
    required HexNeighborDirection rDirection,
  }) {
    final origin = transform.toScreen(const HexPoint(0, 0));

    Offset directionUnit(HexNeighborDirection direction) {
      final point = HexGeometry.rotate(
        HexGeometry.point(direction.coordinate, configuration.radius),
        configuration.rotationDegrees.toDouble(),
      );
      final offset = transform.toScreen(point) - origin;
      return offset / math.max(0.0001, offset.distance);
    }

    final adjacent = HexGeometry.point(
      HexNeighborDirection.positiveQ.coordinate,
      configuration.radius,
    );
    final adjacentLength = math.sqrt(
      adjacent.x * adjacent.x + adjacent.y * adjacent.y,
    );
    final vectorLength = (adjacentLength * transform.scale * 2.35)
        .clamp(58.0, 104.0)
        .toDouble();
    return _HexBasisVectorGeometry(
      origin: origin,
      qDirection: qDirection,
      rDirection: rDirection,
      qUnit: directionUnit(qDirection),
      rUnit: directionUnit(rDirection),
      vectorLength: vectorLength,
    );
  }

  static const double minimumDragRadius = 18;
  static const double _handleHitRadius = 25;
  static const double _shaftHitRadius = 13;

  final Offset origin;
  final HexNeighborDirection qDirection;
  final HexNeighborDirection rDirection;
  final Offset qUnit;
  final Offset rUnit;
  final double vectorLength;

  Offset get qEnd => origin + qUnit * vectorLength;
  Offset get rEnd => origin + rUnit * vectorLength;
  Offset get differenceEnd => origin + (qUnit - rUnit) * vectorLength;

  _HexBasisAxis? hitTest(Offset position) {
    final qHandleDistance = (position - qEnd).distance;
    final rHandleDistance = (position - rEnd).distance;
    if (math.min(qHandleDistance, rHandleDistance) <= _handleHitRadius) {
      return qHandleDistance <= rHandleDistance
          ? _HexBasisAxis.q
          : _HexBasisAxis.r;
    }
    final qLineDistance = _distanceToSegment(position, origin, qEnd);
    final rLineDistance = _distanceToSegment(position, origin, rEnd);
    if (math.min(qLineDistance, rLineDistance) > _shaftHitRadius) return null;
    return qLineDistance <= rLineDistance ? _HexBasisAxis.q : _HexBasisAxis.r;
  }

  HexNeighborDirection nearestDirection(
    Offset position, {
    required HexNeighborDirection excludingParallelTo,
  }) {
    final offset = position - origin;
    final unit = offset / math.max(0.0001, offset.distance);
    var best = qDirection;
    var bestDot = double.negativeInfinity;
    for (final candidate in HexNeighborDirection.values) {
      if (candidate.isParallelTo(excludingParallelTo)) continue;
      final candidateUnit = _unitFor(candidate);
      final dot = unit.dx * candidateUnit.dx + unit.dy * candidateUnit.dy;
      if (dot > bestDot) {
        best = candidate;
        bestDot = dot;
      }
    }
    return best;
  }

  Offset _unitFor(HexNeighborDirection direction) {
    if (direction == qDirection) return qUnit;
    if (direction == rDirection) return rUnit;
    final sectorDelta = (direction.index - qDirection.index) * math.pi / 3;
    final cosine = math.cos(sectorDelta);
    final sine = math.sin(sectorDelta);
    return Offset(
      qUnit.dx * cosine - qUnit.dy * sine,
      qUnit.dx * sine + qUnit.dy * cosine,
    );
  }

  static double _distanceToSegment(Offset point, Offset start, Offset end) {
    final segment = end - start;
    final squaredLength = segment.dx * segment.dx + segment.dy * segment.dy;
    if (squaredLength <= 0.0001) return (point - start).distance;
    final fromStart = point - start;
    final projection =
        (fromStart.dx * segment.dx + fromStart.dy * segment.dy) / squaredLength;
    final clamped = projection.clamp(0.0, 1.0).toDouble();
    return (point - (start + segment * clamped)).distance;
  }
}

class _HexBasisVectorPainter extends CustomPainter {
  const _HexBasisVectorPainter({
    required this.geometry,
    required this.qStep,
    required this.rStep,
    required this.activeAxis,
  });

  final _HexBasisVectorGeometry geometry;
  final int qStep;
  final int rStep;
  final _HexBasisAxis? activeAxis;

  bool get reversesDifference => qStep - rStep < 0;
  Offset get displayedDifferenceEnd => reversesDifference
      ? geometry.origin +
            (geometry.rUnit - geometry.qUnit) * geometry.vectorLength
      : geometry.differenceEnd;
  String get differenceLabel => reversesDifference
      ? 'R-Q ${_signed(rStep - qStep)}'
      : 'Q-R ${_signed(qStep - rStep)}';

  @override
  void paint(Canvas canvas, Size size) {
    final differencePaint = Paint()
      ..color = AppPalette.outline.withValues(alpha: 0.92)
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeWidth = 2;
    _drawDashedArrow(
      canvas,
      geometry.origin,
      displayedDifferenceEnd,
      differencePaint,
    );
    _paintVector(
      canvas,
      axis: _HexBasisAxis.q,
      end: geometry.qEnd,
      color: AppPalette.accent,
      label: 'Q',
      step: qStep,
    );
    _paintVector(
      canvas,
      axis: _HexBasisAxis.r,
      end: geometry.rEnd,
      color: AppPalette.selection,
      label: 'R',
      step: rStep,
    );
    _paintText(
      canvas,
      differenceLabel,
      displayedDifferenceEnd,
      AppPalette.outline,
      anchor: displayedDifferenceEnd - geometry.origin,
    );
  }

  void _paintVector(
    Canvas canvas, {
    required _HexBasisAxis axis,
    required Offset end,
    required Color color,
    required String label,
    required int step,
  }) {
    final active = activeAxis == axis;
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeWidth = active ? 4 : 3;
    canvas.drawLine(geometry.origin, end, paint);
    _drawArrowHead(canvas, geometry.origin, end, paint, active ? 13 : 11);
    canvas.drawCircle(
      end,
      active ? 17 : 15,
      Paint()..color = AppPalette.background.withValues(alpha: 0.94),
    );
    canvas.drawCircle(end, active ? 17 : 15, paint);
    _paintCenteredText(canvas, label, end, color, fontSize: 12);
    _paintText(
      canvas,
      _signed(step),
      end,
      color,
      anchor: end - geometry.origin,
    );
  }

  static void _drawDashedArrow(
    Canvas canvas,
    Offset start,
    Offset end,
    Paint paint,
  ) {
    final vector = end - start;
    final distance = vector.distance;
    if (distance <= 0.001) return;
    final unit = vector / distance;
    const dash = 7.0;
    const gap = 5.0;
    for (var position = 0.0; position < distance - 10; position += dash + gap) {
      canvas.drawLine(
        start + unit * position,
        start + unit * math.min(position + dash, distance - 10),
        paint,
      );
    }
    _drawArrowHead(canvas, start, end, paint, 10);
  }

  static void _drawArrowHead(
    Canvas canvas,
    Offset start,
    Offset end,
    Paint paint,
    double length,
  ) {
    final vector = end - start;
    if (vector.distance <= 0.001) return;
    final unit = vector / vector.distance;
    final perpendicular = Offset(-unit.dy, unit.dx);
    final base = end - unit * length;
    canvas.drawLine(end, base + perpendicular * (length * 0.44), paint);
    canvas.drawLine(end, base - perpendicular * (length * 0.44), paint);
  }

  static void _paintText(
    Canvas canvas,
    String text,
    Offset end,
    Color color, {
    required Offset anchor,
  }) {
    final unit = anchor / math.max(0.0001, anchor.distance);
    final perpendicular = Offset(-unit.dy, unit.dx);
    final position = end + unit * 21 + perpendicular * 5;
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: TextStyle(
          color: color,
          fontSize: 10,
          fontWeight: FontWeight.w800,
          fontFamily: 'monospace',
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(
      canvas,
      position - Offset(painter.width / 2, painter.height / 2),
    );
  }

  static void _paintCenteredText(
    Canvas canvas,
    String text,
    Offset center,
    Color color, {
    required double fontSize,
  }) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: TextStyle(
          color: color,
          fontSize: fontSize,
          fontWeight: FontWeight.w900,
          fontFamily: 'monospace',
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(
      canvas,
      center - Offset(painter.width / 2, painter.height / 2),
    );
  }

  static String _signed(int value) => value > 0 ? '+$value' : '$value';

  @override
  bool shouldRepaint(covariant _HexBasisVectorPainter oldDelegate) {
    return oldDelegate.geometry.origin != geometry.origin ||
        oldDelegate.geometry.qEnd != geometry.qEnd ||
        oldDelegate.geometry.rEnd != geometry.rEnd ||
        oldDelegate.qStep != qStep ||
        oldDelegate.rStep != rStep ||
        oldDelegate.activeAxis != activeAxis;
  }
}

class _HexZoomControl extends StatefulWidget {
  const _HexZoomControl({required this.onZoom, this.onInteraction});

  final ValueChanged<double> onZoom;
  final VoidCallback? onInteraction;

  @override
  State<_HexZoomControl> createState() => _HexZoomControlState();
}

class _HexZoomControlState extends State<_HexZoomControl> {
  static const double _zoomExponentPerPixel = 0.012;
  static const double _semanticZoomFactor = 1.12;
  static const double _hapticStepPixels = 9;

  double _wheelOffset = 0;
  double _hapticTravel = 0;

  void _handlePanUpdate(DragUpdateDetails details) {
    final delta = details.delta.dy;
    if (!delta.isFinite || delta == 0) return;
    widget.onZoom(math.exp(-delta * _zoomExponentPerPixel));
    _hapticTravel += delta;
    if (_hapticTravel.abs() >= _hapticStepPixels) {
      widget.onInteraction?.call();
      _hapticTravel = _hapticTravel.remainder(_hapticStepPixels);
    }
    setState(() => _wheelOffset += delta);
  }

  void _resetHapticTravel() => _hapticTravel = 0;

  void _increaseSemantically() {
    widget.onInteraction?.call();
    widget.onZoom(_semanticZoomFactor);
  }

  void _decreaseSemantically() {
    widget.onInteraction?.call();
    widget.onZoom(1 / _semanticZoomFactor);
  }

  @override
  Widget build(BuildContext context) {
    return Semantics(
      key: const ValueKey('hex-zoom-control'),
      container: true,
      label: 'Hex键盘滚轮缩放控制器',
      hint: '向上滚动放大，向下滚动缩小',
      onIncrease: _increaseSemantically,
      onDecrease: _decreaseSemantically,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        excludeFromSemantics: true,
        onPanUpdate: _handlePanUpdate,
        onPanEnd: (_) => _resetHapticTravel(),
        onPanCancel: _resetHapticTravel,
        child: CustomPaint(
          painter: _HexZoomControlPainter(wheelOffset: _wheelOffset),
        ),
      ),
    );
  }
}

class _HexPanRotationControl extends StatelessWidget {
  const _HexPanRotationControl({
    required this.rotationDegrees,
    required this.onPanStart,
    required this.onPanUpdate,
    required this.onPanEnd,
    required this.onPanCancel,
  });

  final double rotationDegrees;
  final GestureDragStartCallback onPanStart;
  final GestureDragUpdateCallback onPanUpdate;
  final GestureDragEndCallback onPanEnd;
  final VoidCallback onPanCancel;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      key: const ValueKey('hex-pan-rotation-control'),
      container: true,
      label: 'Hex键盘自由平移和旋转控制器',
      hint: '在球体内向任意方向拖动键盘，在外环拖动使键盘绕原点旋转',
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        excludeFromSemantics: true,
        onPanStart: onPanStart,
        onPanUpdate: onPanUpdate,
        onPanEnd: onPanEnd,
        onPanCancel: onPanCancel,
        child: CustomPaint(
          painter: _HexPanRotationControlPainter(
            rotationDegrees: rotationDegrees,
          ),
        ),
      ),
    );
  }
}

class _HexZoomControlPainter extends CustomPainter {
  const _HexZoomControlPainter({required this.wheelOffset});

  final double wheelOffset;

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final wheelRect = Rect.fromCenter(
      center: center,
      width: size.width * 0.28,
      height: size.height * 0.82,
    );
    final wheel = RRect.fromRectAndRadius(
      wheelRect,
      Radius.circular(wheelRect.width * 0.46),
    );
    canvas.drawRRect(
      wheel,
      Paint()
        ..color = const Color(0xBB050A0B)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 5),
    );
    canvas.drawRRect(
      wheel,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.centerLeft,
          end: Alignment.centerRight,
          colors: [Color(0xFF102329), Color(0xFF5DA9B1), Color(0xFF102329)],
          stops: [0, 0.5, 1],
        ).createShader(wheelRect),
    );
    canvas.drawRRect(
      wheel,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.3
        ..color = AppPalette.primaryText.withValues(alpha: 0.52),
    );

    canvas.save();
    canvas.clipRRect(wheel);
    final grooveSpacing = math.max(7.0, size.height * 0.09);
    final phase = wheelOffset % grooveSpacing;
    for (
      var y = wheelRect.top - grooveSpacing + phase;
      y <= wheelRect.bottom + grooveSpacing;
      y += grooveSpacing
    ) {
      final distanceRatio = ((y - center.dy).abs() / (wheelRect.height / 2))
          .clamp(0.0, 1.0);
      canvas.drawLine(
        Offset(wheelRect.left, y),
        Offset(wheelRect.right, y),
        Paint()
          ..strokeWidth = 1.2 + (1 - distanceRatio) * 0.8
          ..color = AppPalette.primaryText.withValues(
            alpha: 0.16 + (1 - distanceRatio) * 0.34,
          ),
      );
    }
    canvas.drawRect(
      wheelRect,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xA6000000), Colors.transparent, Color(0xA6000000)],
          stops: [0, 0.5, 1],
        ).createShader(wheelRect),
    );
    canvas.restore();

    final markerPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeWidth = 2
      ..color = AppPalette.accent.withValues(alpha: 0.82);
    canvas.drawLine(
      Offset(wheelRect.left - size.width * 0.06, center.dy),
      Offset(wheelRect.right + size.width * 0.06, center.dy),
      markerPaint,
    );

    final arrowPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = AppPalette.primaryText.withValues(alpha: 0.86);
    _drawVerticalArrow(
      canvas,
      Offset(center.dx, size.height * 0.055),
      upward: true,
      size: size.shortestSide * 0.05,
      paint: arrowPaint,
    );
    _drawVerticalArrow(
      canvas,
      Offset(center.dx, size.height * 0.945),
      upward: false,
      size: size.shortestSide * 0.05,
      paint: arrowPaint,
    );
  }

  void _drawVerticalArrow(
    Canvas canvas,
    Offset center, {
    required bool upward,
    required double size,
    required Paint paint,
  }) {
    final direction = upward ? -1.0 : 1.0;
    canvas.drawPath(
      Path()
        ..moveTo(center.dx, center.dy + direction * size)
        ..lineTo(center.dx - size, center.dy - direction * size * 0.55)
        ..lineTo(center.dx + size, center.dy - direction * size * 0.55)
        ..close(),
      paint,
    );
  }

  @override
  bool shouldRepaint(covariant _HexZoomControlPainter oldDelegate) {
    return oldDelegate.wheelOffset != wheelOffset;
  }
}

class _HexPanRotationControlPainter extends CustomPainter {
  const _HexPanRotationControlPainter({required this.rotationDegrees});

  final double rotationDegrees;

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final outerRadius = size.shortestSide * 0.46;
    final sphereRadius = size.shortestSide * 0.34;
    canvas.drawCircle(
      center,
      outerRadius,
      Paint()
        ..color = const Color(0x99070F12)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 10),
    );
    canvas.drawCircle(
      center,
      outerRadius,
      Paint()..color = const Color(0xB9162224),
    );
    canvas.drawCircle(
      center,
      outerRadius,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.2
        ..color = AppPalette.outline.withValues(alpha: 0.68),
    );
    canvas.drawCircle(
      center,
      sphereRadius,
      Paint()
        ..shader = const RadialGradient(
          center: Alignment(-0.35, -0.42),
          radius: 1.08,
          colors: [Color(0xFFE8FFFF), Color(0xFF348D98), Color(0xFF10282E)],
          stops: [0, 0.34, 1],
        ).createShader(Rect.fromCircle(center: center, radius: sphereRadius)),
    );
    canvas.drawCircle(
      center,
      sphereRadius,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.2
        ..color = AppPalette.primaryText.withValues(alpha: 0.58),
    );

    final axisPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2
      ..color = AppPalette.primaryText.withValues(alpha: 0.34);
    canvas.drawLine(
      center - Offset(sphereRadius * 0.72, 0),
      center + Offset(sphereRadius * 0.72, 0),
      axisPaint,
    );
    canvas.drawLine(
      center - Offset(0, sphereRadius * 0.72),
      center + Offset(0, sphereRadius * 0.72),
      axisPaint,
    );
    final arrowPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = AppPalette.primaryText.withValues(alpha: 0.88);
    _drawArrow(
      canvas,
      center - Offset(sphereRadius * 0.58, 0),
      math.pi,
      sphereRadius,
      arrowPaint,
    );
    _drawArrow(
      canvas,
      center - Offset(0, sphereRadius * 0.58),
      -math.pi / 2,
      sphereRadius,
      arrowPaint,
    );
    _drawArrow(
      canvas,
      center + Offset(0, sphereRadius * 0.58),
      math.pi / 2,
      sphereRadius,
      arrowPaint,
    );
    canvas.drawCircle(
      center,
      sphereRadius * 0.12,
      Paint()..color = AppPalette.accent.withValues(alpha: 0.54),
    );
    _drawArrow(
      canvas,
      center + Offset(sphereRadius * 0.58, 0),
      0,
      sphereRadius,
      arrowPaint,
    );

    final angle = rotationDegrees * math.pi / 180 - math.pi / 2;
    final markerCenter =
        center +
        Offset(math.cos(angle), math.sin(angle)) * (outerRadius * 0.88);
    canvas.drawCircle(
      markerCenter,
      math.max(3, size.shortestSide * 0.035),
      Paint()..color = AppPalette.accent,
    );
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: outerRadius * 0.82),
      angle - 0.62,
      1.24,
      false,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeWidth = 2
        ..color = AppPalette.accent.withValues(alpha: 0.72),
    );
  }

  void _drawArrow(
    Canvas canvas,
    Offset center,
    double angle,
    double radius,
    Paint paint,
  ) {
    canvas.save();
    canvas.translate(center.dx, center.dy);
    canvas.rotate(angle);
    final length = radius * 0.18;
    final halfWidth = radius * 0.13;
    canvas.drawPath(
      Path()
        ..moveTo(length, 0)
        ..lineTo(-length, -halfWidth)
        ..lineTo(-length, halfWidth)
        ..close(),
      paint,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _HexPanRotationControlPainter oldDelegate) {
    return oldDelegate.rotationDegrees != rotationDegrees;
  }
}

class _HexTransform {
  const _HexTransform({
    required this.scale,
    required this.offset,
    required this.viewportPan,
    required this.rotationDegrees,
    required this.cosine,
    required this.sine,
  });

  final double scale;
  final Offset offset;
  final Offset viewportPan;
  final double rotationDegrees;
  final double cosine;
  final double sine;

  Offset toScreen(HexPoint point) {
    final scaledX = point.x * scale;
    final scaledY = point.y * scale;
    return offset +
        Offset(
          scaledX * cosine - scaledY * sine,
          scaledX * sine + scaledY * cosine,
        );
  }

  HexPoint toModel(Offset point) {
    final translated = point - offset;
    return HexPoint(
      (translated.dx * cosine + translated.dy * sine) / scale,
      (-translated.dx * sine + translated.dy * cosine) / scale,
    );
  }

  static _HexTransform fit(
    HexaKeyboardLayout layout,
    Size size, {
    required double scaleMultiplier,
    required Offset requestedPan,
    required double rotationDegrees,
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
    final safeRotationDegrees = rotationDegrees.isFinite
        ? rotationDegrees
        : 0.0;
    final rotationRadians = safeRotationDegrees * math.pi / 180;
    final cosine = math.cos(rotationRadians);
    final sine = math.sin(rotationRadians);
    final contentWidth = (width * cosine.abs() + height * sine.abs()) * scale;
    final contentHeight = (width * sine.abs() + height * cosine.abs()) * scale;
    final viewportPan = _constrainKeyboardPan(
      requested: requestedPan,
      contentSize: Size(contentWidth, contentHeight),
      viewportSize: size,
      edgeMargin: 24,
    );
    return _HexTransform(
      scale: scale,
      offset: Offset(
        size.width / 2 - bounds.center.x * scale + viewportPan.dx,
        size.height / 2 - bounds.center.y * scale + viewportPan.dy,
      ),
      viewportPan: viewportPan,
      rotationDegrees: safeRotationDegrees,
      cosine: cosine,
      sine: sine,
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
    final centeredSlack = (content - viewport).abs() / 2;
    final limit = centeredSlack + math.max(0, edgeMargin);
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
    final rotation =
        layout.configuration.rotationDegrees + transform.rotationDegrees;
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
    final notes = <_KeyboardPlaybackNote>[];
    final previousByCoordinate = <AxialCoordinate, _KeyboardPlaybackNote>{};
    for (var scoreIndex = 0; scoreIndex < source.length; scoreIndex++) {
      final note = source[scoreIndex];
      final cell = layout.keyForPitch(note.pitch);
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
          alpha: 0.04 + activeForce.clamp(0.0, 1.0) * 0.34,
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
      final intensity = (force ?? 0.76).clamp(0.0, 1.0);
      final strokeWidth = math.max(
        1.4 * physicalPixel,
        radius * (0.07 + intensity * 0.13),
      );
      final path = _hexPath(
        transform.toScreen(cell.center),
        radius,
        layout.configuration.rotationDegrees + transform.rotationDegrees,
      );
      outerPaint
        ..strokeWidth = strokeWidth + 2 * physicalPixel
        ..color = AppPalette.selection.withValues(
          alpha: 0.08 + intensity * 0.34,
        );
      innerPaint
        ..strokeWidth = strokeWidth
        ..color = AppPalette.selection.withValues(
          alpha: 0.20 + intensity * 0.80,
        );
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
        layout.configuration.rotationDegrees + transform.rotationDegrees,
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
        oldDelegate.transform.rotationDegrees != transform.rotationDegrees ||
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
