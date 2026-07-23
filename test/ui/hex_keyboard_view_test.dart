import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/core/hex_keyboard.dart';
import 'package:xensynth/ui/hex/hex_keyboard_view.dart';

void main() {
  testWidgets('uses the current EDO instead of the legacy hex period', (
    tester,
  ) async {
    const settings = XenSynthSettings(
      edo: 19,
      hexPeriod: 53,
      touchSensitivity: 0,
    );
    final result = await _tapConcreteKey(tester, settings);

    expect(
      find.bySemanticsLabel('Hexagonal microtonal keyboard with 280 keys'),
      findsOneWidget,
    );
    expect(result.pitches, hasLength(1));
    expect(
      result.pitches.single,
      closeTo(60 + result.target.step * 12 / 19, 0.000001),
    );
    expect(result.released, hasLength(1));
    expect(tester.takeException(), isNull);
  });

  testWidgets('uses 12 EDO as the free-mode fallback period', (tester) async {
    const settings = XenSynthSettings(
      edo: 0,
      hexPeriod: 53,
      touchSensitivity: 0,
    );
    final result = await _tapConcreteKey(tester, settings);

    expect(result.pitches, hasLength(1));
    expect(
      result.pitches.single,
      closeTo(60 + result.target.step * 12 / 12, 0.000001),
    );
    expect(result.released, hasLength(1));
    expect(tester.takeException(), isNull);
  });

  testWidgets('toolbar viewport state changes rendering and hit testing', (
    tester,
  ) async {
    const settings = XenSynthSettings(edo: 19, touchSensitivity: 0);
    final controller = HexKeyboardViewportController();
    final result = await _tapConcreteKey(
      tester,
      settings,
      viewportController: controller,
      zoomFactor: 2,
      viewportPan: const Offset(80, -60),
    );

    expect(controller.scaleMultiplier, closeTo(1.68, 0.000001));
    expect(controller.pan, isNot(Offset.zero));
    expect(result.pitches, hasLength(1));
    expect(
      result.pitches.single,
      closeTo(60 + result.target.step * 12 / 19, 0.000001),
    );
    expect(result.released, hasLength(1));
    expect(tester.takeException(), isNull);

    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets(
    'corner controls wheel-zoom, move freely, and rotate without playing keys',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(1000, 640));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final controller = HexKeyboardViewportController();
      final playedPitches = <double>[];
      var controlInteractions = 0;
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: HexKeyboardView(
              score: null,
              playhead: 0,
              settings: const XenSynthSettings(
                layoutMode: KeyboardLayoutMode.hexagonal,
              ),
              activePitches: const {},
              viewportController: controller,
              onControlInteraction: () => controlInteractions++,
              onPitchDown: (_, pitch, _) => playedPitches.add(pitch),
              onPitchMove: (_, pitch, _) => playedPitches.add(pitch),
              onPitchUp: (_) {},
            ),
          ),
        ),
      );

      final viewRect = tester.getRect(find.byType(HexKeyboardView));
      final zoomRect = tester.getRect(
        find.byKey(const ValueKey('hex-zoom-control')),
      );
      final panRotationRect = tester.getRect(
        find.byKey(const ValueKey('hex-pan-rotation-control')),
      );
      expect(zoomRect.top, closeTo(panRotationRect.top, 0.001));
      expect(
        zoomRect.left - viewRect.left,
        closeTo(viewRect.right - panRotationRect.right, 0.001),
      );

      final zoomGesture = await tester.startGesture(zoomRect.center);
      await zoomGesture.moveBy(const Offset(0, -40));
      await tester.pump();
      final firstScale = controller.scaleMultiplier;
      await zoomGesture.moveBy(const Offset(0, -40));
      await tester.pump();
      final secondScale = controller.scaleMultiplier;
      await zoomGesture.up();
      await tester.pump();
      expect(
        firstScale,
        greaterThan(HexKeyboardViewportController.minimumScale),
      );
      expect(secondScale, greaterThan(firstScale));
      expect(controlInteractions, 2);

      final reverseZoomGesture = await tester.startGesture(zoomRect.center);
      await reverseZoomGesture.moveBy(const Offset(0, 40));
      await tester.pump();
      final reversedScale = controller.scaleMultiplier;
      await reverseZoomGesture.up();
      await tester.pump();
      expect(reversedScale, lessThan(secondScale));
      expect(controlInteractions, 3);

      final innerGesture = await tester.startGesture(panRotationRect.center);
      await innerGesture.moveBy(const Offset(-20, 14));
      await tester.pump();
      expect(controlInteractions, 4);
      await innerGesture.moveBy(const Offset(-16, 14));
      await tester.pump();
      expect(controlInteractions, 5);
      await innerGesture.up();
      await tester.pump();
      await tester.pump();
      expect(controller.pan.dx, lessThan(0));
      expect(controller.pan.dy, greaterThan(0));

      final outerGesture = await tester.startGesture(
        panRotationRect.center + Offset(0, -panRotationRect.height * 0.43),
      );
      await outerGesture.moveTo(
        panRotationRect.center +
            Offset(
              panRotationRect.width * 0.43 * 0.707,
              -panRotationRect.height * 0.43 * 0.707,
            ),
      );
      await tester.pump();
      expect(controlInteractions, 6);
      await outerGesture.moveTo(
        panRotationRect.center + Offset(panRotationRect.width * 0.43, 0),
      );
      await tester.pump();
      await outerGesture.up();
      await tester.pump();
      expect(controller.rotationDegrees.abs(), greaterThan(20));
      expect(controlInteractions, 7);
      expect(playedPitches, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('2D Hex maps input velocity to key brightness without trails', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1000, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    Future<double> pumpAtVelocity(int velocity) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: HexKeyboardView(
              score: null,
              playhead: 0,
              settings: const XenSynthSettings(
                layoutMode: KeyboardLayoutMode.hexagonal,
              ),
              activePitches: const {9001: 60},
              activePitchVelocities: {9001: velocity},
              onPitchDown: (_, _, _) {},
              onPitchMove: (_, _, _) {},
              onPitchUp: (_) {},
            ),
          ),
        ),
      );
      await tester.pump();
      final painters = tester
          .widgetList<CustomPaint>(find.byType(CustomPaint))
          .toList();
      expect(
        painters.map((paint) => paint.painter.runtimeType.toString()),
        isNot(contains('_HexInputTracePainter')),
      );
      final keyboard = painters.singleWhere(
        (paint) =>
            paint.painter.runtimeType.toString() == '_HexKeyboardPainter',
      );
      final forces = (keyboard.painter as dynamic).activeForces as Map;
      return forces.values.single as double;
    }

    final quiet = await pumpAtVelocity(1);
    final loud = await pumpAtVelocity(127);

    expect(quiet, 0);
    expect(loud, 1);
  });

  testWidgets('rotated Hex keyboard keeps touch hit testing aligned', (
    tester,
  ) async {
    const settings = XenSynthSettings(edo: 19, touchSensitivity: 0);
    final controller = HexKeyboardViewportController();
    final result = await _tapConcreteKey(
      tester,
      settings,
      viewportController: controller,
      rotationDegrees: 37,
    );

    expect(controller.rotationDegrees, closeTo(37, 0.000001));
    expect(result.pitches, hasLength(1));
    expect(
      result.pitches.single,
      closeTo(60 + result.target.step * 12 / 19, 0.000001),
    );
    expect(result.released, hasLength(1));
    expect(tester.takeException(), isNull);

    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  test('viewport controller clamps zoom to the Android bounds', () {
    final controller = HexKeyboardViewportController();
    addTearDown(controller.dispose);

    controller.zoomBy(100);
    expect(
      controller.scaleMultiplier,
      HexKeyboardViewportController.maximumScale,
    );

    controller.zoomBy(0.0001);
    expect(
      controller.scaleMultiplier,
      HexKeyboardViewportController.minimumScale,
    );
  });

  test('viewport controller stores and resets the spatial transform', () {
    final controller = HexKeyboardViewportController();
    addTearDown(controller.dispose);

    controller.setSpatialTransform(
      scaleMultiplier: 1.7,
      pan: const Offset(42, -18),
      rotationXDegrees: 80,
      rotationYDegrees: -90,
      rotationDegrees: 205,
    );

    expect(controller.scaleMultiplier, 1.7);
    expect(controller.pan, const Offset(42, -18));
    expect(
      controller.rotationXDegrees,
      HexKeyboardViewportController.maximumTiltDegrees,
    );
    expect(
      controller.rotationYDegrees,
      -HexKeyboardViewportController.maximumTiltDegrees,
    );
    expect(controller.rotationZDegrees, -155);
    expect(controller.rotationDegrees, -155);

    controller.reset();
    expect(
      controller.scaleMultiplier,
      HexKeyboardViewportController.minimumScale,
    );
    expect(controller.pan, Offset.zero);
    expect(controller.rotationXDegrees, 0);
    expect(controller.rotationYDegrees, 0);
    expect(controller.rotationZDegrees, 0);
    expect(controller.rotationDegrees, 0);
  });
}

Future<_TapResult> _tapConcreteKey(
  WidgetTester tester,
  XenSynthSettings settings, {
  HexKeyboardViewportController? viewportController,
  double? zoomFactor,
  Offset viewportPan = Offset.zero,
  double rotationDegrees = 0,
}) async {
  await tester.binding.setSurfaceSize(const Size(1200, 700));
  addTearDown(() => tester.binding.setSurfaceSize(null));

  final pitches = <double>[];
  final released = <int>[];
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Center(
          child: SizedBox(
            width: 1000,
            height: 500,
            child: HexKeyboardView(
              score: null,
              playhead: 0,
              settings: settings,
              activePitches: const {},
              viewportController: viewportController,
              onPitchDown: (pointer, pitch, velocity) => pitches.add(pitch),
              onPitchMove: (pointer, pitch, velocity) {},
              onPitchUp: released.add,
            ),
          ),
        ),
      ),
    ),
  );

  if (zoomFactor != null) viewportController?.zoomBy(zoomFactor);
  if (viewportPan != Offset.zero) viewportController?.panBy(viewportPan);
  if (rotationDegrees != 0) viewportController?.rotateBy(rotationDegrees);
  if (zoomFactor != null ||
      viewportPan != Offset.zero ||
      rotationDegrees != 0) {
    await tester.pump();
    await tester.pump();
  }

  final layout = HexaKeyboardLayoutEngine.build(
    settings.hexKeyboardConfiguration,
  );
  final candidates = layout.cells.where((cell) => cell.step != 0).toList()
    ..sort((first, second) {
      final distance = HexGeometry.distance(
        first.coordinate,
      ).compareTo(HexGeometry.distance(second.coordinate));
      return distance != 0
          ? distance
          : first.step.abs().compareTo(second.step.abs());
    });
  final target = candidates.first;
  final bounds = layout.modelBounds;
  final scaleMultiplier =
      viewportController?.scaleMultiplier ??
      HexKeyboardViewportController.minimumScale;
  final scale =
      math.min(1000 / bounds.width, 500 / bounds.height) * scaleMultiplier;
  final pan = viewportController?.pan ?? Offset.zero;
  final rotationRadians =
      (viewportController?.rotationDegrees ?? 0) * math.pi / 180;
  final cosine = math.cos(rotationRadians);
  final sine = math.sin(rotationRadians);
  final scaledTarget = Offset(target.center.x * scale, target.center.y * scale);
  final localPosition =
      Offset(
        1000 / 2 - bounds.center.x * scale + pan.dx,
        500 / 2 - bounds.center.y * scale + pan.dy,
      ) +
      Offset(
        scaledTarget.dx * cosine - scaledTarget.dy * sine,
        scaledTarget.dx * sine + scaledTarget.dy * cosine,
      );
  final view = find.byType(HexKeyboardView);
  await tester.tapAt(tester.getTopLeft(view) + localPosition);
  await tester.pump();

  return _TapResult(target: target, pitches: pitches, released: released);
}

class _TapResult {
  const _TapResult({
    required this.target,
    required this.pitches,
    required this.released,
  });

  final HexKey target;
  final List<double> pitches;
  final List<int> released;
}
