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
  if (zoomFactor != null || viewportPan != Offset.zero) {
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
  final localPosition = Offset(
    (1000 - bounds.width * scale) / 2 -
        bounds.minX * scale +
        target.center.x * scale +
        pan.dx,
    (500 - bounds.height * scale) / 2 -
        bounds.minY * scale +
        target.center.y * scale +
        pan.dy,
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
