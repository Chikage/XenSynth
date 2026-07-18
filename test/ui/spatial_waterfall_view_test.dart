import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/core/hex_keyboard.dart';
import 'package:xensynth/core/score.dart';
import 'package:xensynth/ui/hex/hex_keyboard_view.dart';
import 'package:xensynth/ui/spatial/spatial_waterfall_view.dart';
import 'package:xensynth/ui/waterfall/waterfall_particle_system.dart';

void main() {
  testWidgets('renders falling notes with both projection modes', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1000, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    const score = ParsedScore(
      title: 'Spatial test',
      format: 'TEST',
      ticksPerQuarter: 480,
      tempos: [],
      meters: [],
      tempoMap: [],
      rawEvents: [],
      notes: [
        WaterfallNote(
          startTick: 0,
          endTick: 480,
          start: 0.8,
          end: 1.8,
          pitch: 60,
          midiPitch: 60,
          cents: 0,
          velocity: 118,
          channel: 0,
          track: 2,
          program: 0,
          bankMsb: 0,
          bankLsb: 0,
        ),
      ],
      longNotes: [],
      duration: 2,
    );

    Future<void> pumpProjection(SpatialProjectionMode projection) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SpatialWaterfallView(
              score: score,
              playhead: 0.4,
              settings: XenSynthSettings(
                layoutMode: KeyboardLayoutMode.spatial,
                spatialProjection: projection,
              ),
              activePitches: const {},
              onPitchDown: (_, _, _) {},
              onPitchMove: (_, _, _) {},
              onPitchUp: (_) {},
            ),
          ),
        ),
      );
      await tester.pump();
      expect(
        find.bySemanticsLabel(
          'Spatial waterfall hexagonal keyboard with 280 keys',
        ),
        findsOneWidget,
      );
      final stack = tester.widget<Stack>(
        find
            .descendant(
              of: find.byType(SpatialWaterfallView),
              matching: find.byType(Stack),
            )
            .first,
      );
      final keyboardLayer = stack.children.indexWhere(
        (child) => child.key == const ValueKey('spatial-keyboard-layer'),
      );
      final measuresLayer = stack.children.indexWhere(
        (child) => child.key == const ValueKey('spatial-measures-layer'),
      );
      final notesLayer = stack.children.indexWhere(
        (child) => child.key == const ValueKey('spatial-notes-layer'),
      );
      expect(measuresLayer, greaterThanOrEqualTo(0));
      expect(keyboardLayer, greaterThan(measuresLayer));
      expect(keyboardLayer, greaterThanOrEqualTo(0));
      expect(notesLayer, greaterThan(keyboardLayer));
      expect(tester.takeException(), isNull);
    }

    await pumpProjection(SpatialProjectionMode.oblique);
    await pumpProjection(SpatialProjectionMode.perspective);
  });

  testWidgets('projected hex keys remain playable by touch', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1000, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final pitches = <double>[];
    final releases = <int>[];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpatialWaterfallView(
            score: null,
            playhead: 0,
            settings: const XenSynthSettings(
              layoutMode: KeyboardLayoutMode.spatial,
            ),
            activePitches: const {},
            onPitchDown: (_, pitch, _) => pitches.add(pitch),
            onPitchMove: (_, pitch, _) => pitches.add(pitch),
            onPitchUp: releases.add,
          ),
        ),
      ),
    );

    final viewRect = tester.getRect(find.byType(SpatialWaterfallView));
    for (final dx in <double>[0.35, 0.5, 0.65]) {
      for (final dy in <double>[0.62, 0.72, 0.82]) {
        await tester.tapAt(
          Offset(
            viewRect.left + viewRect.width * dx,
            viewRect.top + viewRect.height * dy,
          ),
        );
      }
    }
    await tester.pump();

    expect(pitches, isNotEmpty);
    expect(releases, isNotEmpty);
    expect(tester.takeException(), isNull);
  });

  test('negative playhead preserves the lead-in above the keyboard plane', () {
    const settings = XenSynthSettings(
      layoutMode: KeyboardLayoutMode.spatial,
      playbackPreviewSeconds: 1.8,
    );
    const score = ParsedScore(
      title: 'Lead-in test',
      format: 'TEST',
      ticksPerQuarter: 480,
      tempos: [TempoEvent(tick: 0, usPerQuarter: 500000)],
      meters: [MeterEvent(tick: 0, numerator: 4, denominator: 4)],
      tempoMap: [TempoPoint(tick: 0, second: 0, usPerQuarter: 500000)],
      rawEvents: [],
      notes: [],
      longNotes: [],
      duration: 4,
    );

    final negativeDistance = SpatialWaterfallView.debugProjectedDistanceForTime(
      size: const Size(1000, 640),
      settings: settings,
      playhead: -0.225,
      scoreTime: 0,
    );
    final zeroDistance = SpatialWaterfallView.debugProjectedDistanceForTime(
      size: const Size(1000, 640),
      settings: settings,
      playhead: 0,
      scoreTime: 0,
    );

    expect(negativeDistance, greaterThan(zeroDistance));
    expect(
      SpatialWaterfallView.debugVisibleMeasureCount(
        score: score,
        settings: settings,
        playhead: -0.225,
      ),
      1,
    );
  });

  test(
    'perspective keeps projected key deformation bounded while rotating',
    () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
        spatialProjection: SpatialProjectionMode.perspective,
      );

      for (final rotation in <({double x, double y, double z})>[
        (x: 0, y: 0, z: 0),
        (x: 18, y: -22, z: 45),
        (x: -28, y: 30, z: 90),
        (x: 36, y: 18, z: 135),
      ]) {
        final range = SpatialWaterfallView.debugProjectedKeyRadiusRange(
          size: const Size(1000, 640),
          settings: settings,
          rotationXDegrees: rotation.x,
          rotationYDegrees: rotation.y,
          rotationDegrees: rotation.z,
        );
        expect(range.maximum / range.minimum, lessThan(1.55));
      }
    },
  );

  test(
    'oblique projection keeps one shared note scale across the XOY plane',
    () {
      const settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
        spatialProjection: SpatialProjectionMode.oblique,
      );
      final range = SpatialWaterfallView.debugProjectedKeyRadiusRange(
        size: const Size(1000, 640),
        settings: settings,
        rotationXDegrees: 24,
        rotationYDegrees: -30,
        rotationDegrees: 42,
      );
      expect(range.maximum / range.minimum, closeTo(1, 0.000001));
    },
  );

  test('note line widths preserve both projection principles', () {
    const size = Size(1000, 640);
    const rotationXDegrees = 24.0;
    const rotationYDegrees = -30.0;
    const rotationDegrees = 42.0;
    const devicePixelRatio = 3.0;

    for (final projection in SpatialProjectionMode.values) {
      final settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
        spatialProjection: projection,
      );
      final radii = SpatialWaterfallView.debugProjectedKeyRadiusRange(
        size: size,
        settings: settings,
        rotationXDegrees: rotationXDegrees,
        rotationYDegrees: rotationYDegrees,
        rotationDegrees: rotationDegrees,
        devicePixelRatio: devicePixelRatio,
      );
      final quiet = SpatialWaterfallView.debugNoteStrokeWidthRange(
        size: size,
        settings: settings,
        velocity: 1,
        rotationXDegrees: rotationXDegrees,
        rotationYDegrees: rotationYDegrees,
        rotationDegrees: rotationDegrees,
        devicePixelRatio: devicePixelRatio,
      );
      final loud = SpatialWaterfallView.debugNoteStrokeWidthRange(
        size: size,
        settings: settings,
        velocity: 127,
        rotationXDegrees: rotationXDegrees,
        rotationYDegrees: rotationYDegrees,
        rotationDegrees: rotationDegrees,
        devicePixelRatio: devicePixelRatio,
      );

      expect(loud.minimum, greaterThan(quiet.minimum));
      expect(loud.maximum, lessThan(quiet.maximum * 1.3));
      switch (projection) {
        case SpatialProjectionMode.oblique:
          expect(quiet.maximum / quiet.minimum, closeTo(1, 0.000001));
          expect(loud.maximum / loud.minimum, closeTo(1, 0.000001));
        case SpatialProjectionMode.perspective:
          final projectedRadiusRatio = radii.maximum / radii.minimum;
          expect(
            quiet.maximum / quiet.minimum,
            closeTo(projectedRadiusRatio, 0.000001),
          );
          expect(
            loud.maximum / loud.minimum,
            closeTo(projectedRadiusRatio, 0.000001),
          );
      }
    }
  });

  test('same-key repeats keep a small visual gap between note lines', () {
    const settings = XenSynthSettings(
      layoutMode: KeyboardLayoutMode.spatial,
      playbackPreviewSeconds: 1.8,
    );
    const score = ParsedScore(
      title: 'Repeated-note spacing test',
      format: 'TEST',
      ticksPerQuarter: 480,
      tempos: [],
      meters: [],
      tempoMap: [],
      rawEvents: [],
      notes: [
        WaterfallNote(
          startTick: 96,
          endTick: 134,
          start: 0.20,
          end: 0.28,
          pitch: 60,
          midiPitch: 60,
          cents: 0,
          velocity: 112,
          channel: 0,
          track: 1,
          program: 0,
          bankMsb: 0,
          bankLsb: 0,
        ),
        WaterfallNote(
          startTick: 134,
          endTick: 173,
          start: 0.28,
          end: 0.36,
          pitch: 60,
          midiPitch: 60,
          cents: 0,
          velocity: 112,
          channel: 0,
          track: 1,
          program: 0,
          bankMsb: 0,
          bankLsb: 0,
        ),
        WaterfallNote(
          startTick: 173,
          endTick: 211,
          start: 0.36,
          end: 0.44,
          pitch: 60,
          midiPitch: 60,
          cents: 0,
          velocity: 112,
          channel: 0,
          track: 1,
          program: 0,
          bankMsb: 0,
          bankLsb: 0,
        ),
      ],
      longNotes: [],
      duration: 0.44,
    );

    final spans = SpatialWaterfallView.debugVisualNoteSpans(
      score: score,
      settings: settings,
      playhead: 0,
    )..sort((first, second) => first.scoreIndex.compareTo(second.scoreIndex));

    expect(spans.map((span) => span.start), [0.20, 0.28, 0.36]);
    expect(spans[1].start - spans[0].end, greaterThan(0));
    expect(spans[2].start - spans[1].end, greaterThan(0));
    expect(score.notes.map((note) => note.start), [0.20, 0.28, 0.36]);
  });

  testWidgets('active waterfall footprint is centered on its playable key', (
    tester,
  ) async {
    const surfaceSize = Size(1000, 640);
    const pitch = 60 + 7 * 12 / 26;
    const score = ParsedScore(
      title: 'Landing test',
      format: 'TEST',
      ticksPerQuarter: 480,
      tempos: [],
      meters: [],
      tempoMap: [],
      rawEvents: [],
      notes: [
        WaterfallNote(
          startTick: 0,
          endTick: 960,
          start: 0,
          end: 2,
          pitch: pitch,
          midiPitch: 63,
          cents: 23.076923,
          velocity: 127,
          channel: 0,
          track: 2,
          program: 0,
          bankMsb: 0,
          bankLsb: 0,
        ),
      ],
      longNotes: [],
      duration: 2,
    );
    final pitches = <double>[];
    await tester.binding.setSurfaceSize(surfaceSize);
    addTearDown(() => tester.binding.setSurfaceSize(null));

    for (final projection in SpatialProjectionMode.values) {
      final settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
        playbackPreviewSeconds: 3,
        spatialProjection: projection,
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SpatialWaterfallView(
              score: score,
              playhead: 0.5,
              settings: settings,
              activePitches: const {},
              onPitchDown: (_, pitch, _) => pitches.add(pitch),
              onPitchMove: (_, pitch, _) => pitches.add(pitch),
              onPitchUp: (_) {},
            ),
          ),
        ),
      );
      await tester.pump();
      final landing = SpatialWaterfallView.debugLandingCenterForPitch(
        size: surfaceSize,
        settings: settings,
        pitch: pitch,
      )!;
      expect(landing.dx, inInclusiveRange(0, surfaceSize.width));
      expect(landing.dy, inInclusiveRange(0, surfaceSize.height));
      await tester.tapAt(landing);
      await tester.pump();

      final expected = HexaKeyboardLayoutEngine.build(
        settings.hexKeyboardConfiguration,
      ).keyForPitch(pitch)!;
      expect(pitches.last, closeTo(expected.audioPitch.midiPitch, 0.000001));
      expect(tester.takeException(), isNull);
    }
  });

  test('impact moves the mapped key along negative z with the 2D envelope', () {
    const size = Size(1000, 640);
    const pitch = 60 + 7 * 12 / 26;

    for (final projection in SpatialProjectionMode.values) {
      final settings = XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
        spatialProjection: projection,
      );
      final resting = SpatialWaterfallView.debugLandingCenterForPitch(
        size: size,
        settings: settings,
        pitch: pitch,
      )!;
      Offset impacted(double life, double velocityRatio) {
        return SpatialWaterfallView.debugImpactCenterForPitch(
          size: size,
          settings: settings,
          pitch: pitch,
          life: life,
          maxLife: WaterfallParticleSystem.keyImpactLife,
          velocityRatio: velocityRatio,
        )!;
      }

      final start = impacted(WaterfallParticleSystem.keyImpactLife, 1);
      final peak = impacted(WaterfallParticleSystem.keyImpactLife / 2, 1);
      final halfVelocity = impacted(
        WaterfallParticleSystem.keyImpactLife / 2,
        0.5,
      );
      final end = impacted(0, 1);

      expect((start - resting).distance, closeTo(0, 0.000001));
      expect((end - resting).distance, closeTo(0, 0.000001));
      expect(peak.dx, closeTo(resting.dx, 0.000001));
      expect(peak.dy, greaterThan(resting.dy));
      expect((peak - resting).distance, lessThanOrEqualTo(4.000001));
      expect(
        (halfVelocity - resting).distance,
        closeTo((peak - resting).distance * 0.5, 0.000001),
      );
    }
  });

  testWidgets('two-finger gesture pans, zooms, and rotates the 3D view', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1000, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final controller = HexKeyboardViewportController();
    var transformNotifications = 0;
    controller.addListener(() => transformNotifications++);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpatialWaterfallView(
            score: null,
            playhead: 0,
            settings: const XenSynthSettings(
              layoutMode: KeyboardLayoutMode.spatial,
            ),
            activePitches: const {},
            viewportController: controller,
            onPitchDown: (_, _, _) {},
            onPitchMove: (_, _, _) {},
            onPitchUp: (_) {},
          ),
        ),
      ),
    );

    final rect = tester.getRect(find.byType(SpatialWaterfallView));
    final first = await tester.startGesture(
      rect.topLeft + const Offset(390, 390),
      pointer: 1,
    );
    final second = await tester.startGesture(
      rect.topLeft + const Offset(610, 390),
      pointer: 2,
    );
    await first.moveTo(rect.topLeft + const Offset(345, 350));
    await second.moveTo(rect.topLeft + const Offset(665, 455));
    await first.moveTo(rect.topLeft + const Offset(335, 340));
    await second.moveTo(rect.topLeft + const Offset(675, 465));
    await tester.pump();

    expect(
      controller.scaleMultiplier,
      greaterThan(HexKeyboardViewportController.minimumScale),
    );
    expect(controller.rotationXDegrees.abs(), greaterThan(0));
    expect(controller.rotationYDegrees.abs(), greaterThan(0));
    expect(controller.rotationZDegrees.abs(), greaterThan(5));
    expect(controller.rotationDegrees.abs(), greaterThan(5));
    expect(controller.pan, isNot(Offset.zero));
    expect(transformNotifications, 1);

    await first.up();
    await second.up();
    expect(tester.takeException(), isNull);
  });

  testWidgets('corner controls pan and rotate the 3D waterfall', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1000, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final controller = HexKeyboardViewportController();
    final playedPitches = <double>[];
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpatialWaterfallView(
            score: null,
            playhead: 0,
            settings: const XenSynthSettings(
              layoutMode: KeyboardLayoutMode.spatial,
            ),
            activePitches: const {},
            viewportController: controller,
            onPitchDown: (_, pitch, _) => playedPitches.add(pitch),
            onPitchMove: (_, pitch, _) => playedPitches.add(pitch),
            onPitchUp: (_) {},
          ),
        ),
      ),
    );

    final viewRect = tester.getRect(find.byType(SpatialWaterfallView));
    final panRect = tester.getRect(
      find.byKey(const ValueKey('spatial-pan-control')),
    );
    final rotationRect = tester.getRect(
      find.byKey(const ValueKey('spatial-rotation-control')),
    );
    expect(panRect.top, closeTo(rotationRect.top, 0.001));
    expect(
      panRect.left - viewRect.left,
      closeTo(viewRect.right - rotationRect.right, 0.001),
    );

    await tester.tap(find.byKey(const ValueKey('spatial-pan-up')));
    await tester.tap(find.byKey(const ValueKey('spatial-pan-right')));
    await tester.pump();
    expect(controller.pan, const Offset(32, -32));
    expect(playedPitches, isEmpty);

    final sphereCenter = rotationRect.center;
    final xyGesture = await tester.startGesture(sphereCenter);
    await xyGesture.moveBy(const Offset(14, -12));
    await xyGesture.moveBy(const Offset(12, -10));
    await tester.pump();
    await xyGesture.up();
    await tester.pump();
    expect(controller.rotationXDegrees.abs(), greaterThan(1));
    expect(controller.rotationYDegrees.abs(), greaterThan(1));
    expect(controller.rotationZDegrees, closeTo(0, 0.001));

    final outerGesture = await tester.startGesture(
      sphereCenter + Offset(0, -rotationRect.height * 0.43),
    );
    await outerGesture.moveTo(
      sphereCenter + Offset(rotationRect.width * 0.43, 0),
    );
    await tester.pump();
    await outerGesture.up();
    await tester.pump();
    expect(controller.rotationZDegrees.abs(), greaterThan(20));
    expect(playedPitches, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets('empty-space tap toggles playback but key taps still play', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1000, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    const settings = XenSynthSettings(layoutMode: KeyboardLayoutMode.spatial);
    var playbackToggles = 0;
    final playedPitches = <double>[];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpatialWaterfallView(
            score: null,
            playhead: 0,
            settings: settings,
            activePitches: const {},
            onPitchDown: (_, pitch, _) => playedPitches.add(pitch),
            onPitchMove: (_, pitch, _) => playedPitches.add(pitch),
            onPitchUp: (_) {},
            onTogglePlayback: () => playbackToggles++,
          ),
        ),
      ),
    );

    final viewRect = tester.getRect(find.byType(SpatialWaterfallView));
    await tester.tapAt(
      viewRect.topLeft + Offset(viewRect.width / 2, viewRect.height * 0.12),
    );
    await tester.pump();
    expect(playbackToggles, 1);
    expect(playedPitches, isEmpty);

    final landingCenter = SpatialWaterfallView.debugLandingCenterForPitch(
      size: viewRect.size,
      settings: settings,
      pitch: 60,
    )!;
    await tester.tapAt(viewRect.topLeft + landingCenter);
    await tester.pump();
    expect(playedPitches, isNotEmpty);
    expect(playbackToggles, 1);
    expect(tester.takeException(), isNull);
  });
}
