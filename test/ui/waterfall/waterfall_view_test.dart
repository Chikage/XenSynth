import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/midi_parser.dart';
import 'package:xensynth/ui/waterfall/waterfall_view.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('renders the Android reference viewport without paint errors', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final score = MidiWaterfallParser.detectAndParse(
      File('assets/scores/demo_26edo.midx').readAsBytesSync(),
      title: 'UwU Funk in 26edo.midx',
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: WaterfallView(
            score: score,
            playhead: 0,
            edo: 53,
            pitchOffsetCents: 0,
            activePitches: const {},
            onPitchDown: (_, _, _) {},
            onPitchMove: (_, _, _) {},
            onPitchUp: (_) {},
          ),
        ),
      ),
    );

    await tester.pump();
    expect(find.byType(WaterfallView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('ruler touch snaps to EDO and preserves initial velocity', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final downs = <(double, int)>[];
    final moves = <(double, int)>[];
    var released = false;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: WaterfallView(
            score: null,
            playhead: 0,
            edo: 53,
            pitchOffsetCents: 0,
            activePitches: const {},
            onPitchDown: (_, pitch, velocity) => downs.add((pitch, velocity)),
            onPitchMove: (_, pitch, velocity) => moves.add((pitch, velocity)),
            onPitchUp: (_) => released = true,
          ),
        ),
      ),
    );

    final c4X = (60 - 21) / 87 * 874;
    final gesture = await tester.startGesture(Offset(c4X, 280));
    await gesture.moveTo(Offset(c4X + 24, 325));
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 50));

    expect(downs, hasLength(1));
    expect(downs.single.$1, closeTo(60, 0.0001));
    expect(moves, isNotEmpty);
    expect(moves.last.$1, closeTo(60 + 11 * 12 / 53, 0.0001));
    expect(moves.last.$2, downs.single.$2);
    expect(released, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('waterfall tap toggles playback', (tester) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var toggles = 0;

    await tester.pumpWidget(
      _waterfallHarness(onTogglePlayback: () => toggles++),
    );

    final gesture = await tester.startGesture(const Offset(220, 120));
    await gesture.up();
    await tester.pump();

    expect(toggles, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('left vertical drag seeks and brackets the gesture', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var starts = 0;
    var ends = 0;
    final seeks = <double>[];

    await tester.pumpWidget(
      _waterfallHarness(
        playhead: 2,
        duration: 10,
        onSeekStart: () => starts++,
        onSeek: seeks.add,
        onSeekEnd: () => ends++,
      ),
    );

    final gesture = await tester.startGesture(const Offset(160, 90));
    await gesture.moveTo(const Offset(164, 190));
    await gesture.up();
    await tester.pump();

    expect(starts, 1);
    expect(ends, 1);
    expect(seeks, isNotEmpty);
    expect(seeks.last, greaterThan(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('right vertical drag adjusts volume', (tester) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final volumes = <double>[];

    await tester.pumpWidget(
      _waterfallHarness(volumeGain: 0.5, onVolumeChanged: volumes.add),
    );

    final gesture = await tester.startGesture(const Offset(700, 190));
    await gesture.moveTo(const Offset(704, 80));
    await gesture.up();
    await tester.pump();

    expect(volumes, isNotEmpty);
    expect(volumes.last, greaterThan(0.5));
    expect(volumes.last, lessThanOrEqualTo(1));
    expect(tester.takeException(), isNull);
  });

  testWidgets('horizontal drag pans the visible pitch range', (tester) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(_waterfallHarness());
    final initial = _waterfallLayout(tester);

    final gesture = await tester.startGesture(const Offset(220, 110));
    await gesture.moveTo(const Offset(420, 114));
    await tester.pump();
    final moved = _waterfallLayout(tester);
    await gesture.up();

    expect(moved.pitchPan, lessThan(initial.pitchPan));
    expect(moved.pitchZoom, initial.pitchZoom);
    expect(tester.takeException(), isNull);
  });

  testWidgets('two-finger gesture locks zoom to its dominant axis', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(_waterfallHarness());
    final initial = _waterfallLayout(tester);
    final first = await tester.startGesture(const Offset(240, 100));
    final second = await tester.startGesture(const Offset(440, 100));

    await second.moveTo(const Offset(590, 104));
    await tester.pump();
    final zoomed = _waterfallLayout(tester);
    await second.up();
    await first.up();

    expect(zoomed.pitchZoom, greaterThan(initial.pitchZoom));
    expect(zoomed.pixelsPerSecond, initial.pixelsPerSecond);
    expect(tester.takeException(), isNull);
  });

  testWidgets('active ruler preview releases after leaving its touch slop', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 330));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var releases = 0;

    await tester.pumpWidget(_waterfallHarness(onPitchUp: (_) => releases++));
    final rulerTop = _waterfallLayout(tester).keyboardTop;
    final gesture = await tester.startGesture(const Offset(390, 285));

    await gesture.moveTo(Offset(390, rulerTop - 20));
    expect(releases, 0);
    await gesture.moveTo(Offset(390, rulerTop - 31));
    expect(releases, 1);
    await gesture.up();

    expect(releases, 1);
    expect(tester.takeException(), isNull);
  });
}

Widget _waterfallHarness({
  double playhead = 0,
  double duration = 0,
  double volumeGain = 0.85,
  VoidCallback? onTogglePlayback,
  VoidCallback? onSeekStart,
  ValueChanged<double>? onSeek,
  VoidCallback? onSeekEnd,
  ValueChanged<double>? onVolumeChanged,
  ValueChanged<int>? onPitchUp,
}) {
  return MaterialApp(
    home: Scaffold(
      body: WaterfallView(
        score: null,
        playhead: playhead,
        duration: duration,
        volumeGain: volumeGain,
        edo: 53,
        pitchOffsetCents: 0,
        activePitches: const {},
        onPitchDown: (_, _, _) {},
        onPitchMove: (_, _, _) {},
        onPitchUp: onPitchUp ?? (_) {},
        onTogglePlayback: onTogglePlayback,
        onSeekStart: onSeekStart,
        onSeek: onSeek,
        onSeekEnd: onSeekEnd,
        onVolumeChanged: onVolumeChanged,
      ),
    ),
  );
}

WaterfallLayout _waterfallLayout(WidgetTester tester) {
  final paints = tester.widgetList<CustomPaint>(
    find.descendant(
      of: find.byType(WaterfallView),
      matching: find.byType(CustomPaint),
    ),
  );
  return paints
      .map((paint) => paint.painter)
      .whereType<WaterfallPainter>()
      .single
      .layout;
}
