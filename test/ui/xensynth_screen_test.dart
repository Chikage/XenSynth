import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/core/score.dart';
import 'package:xensynth/ui/app_palette.dart';
import 'package:xensynth/ui/hex/hex_keyboard_view.dart';
import 'package:xensynth/ui/spatial/spatial_waterfall_view.dart';
import 'package:xensynth/ui/waterfall/waterfall_view.dart';
import 'package:xensynth/ui/xensynth_screen.dart';

void main() {
  testWidgets('stopping microphone playback offers to save both WAV files', (
    tester,
  ) async {
    const nativeChannel = MethodChannel('icu.ringona.xensynth/platform');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(nativeChannel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'startPitchRecognition' => <String, Object?>{
          'supported': true,
          'phase': 'listening',
          'modelReady': true,
          'recognizing': true,
          'busy': false,
          'progress': 1.0,
          'message': 'Listening for piano notes',
        },
        'stopPitchRecognition' => <String, Object?>{
          'supported': true,
          'phase': 'idle',
          'modelReady': true,
          'recognizing': false,
          'busy': false,
          'progress': 1.0,
          'message': '',
          'recordingDuration': 0.8,
        },
        'playPitchRecording' => true,
        'savePitchRecording' => <String, Object?>{
          'saved': true,
          'directory': 'Music/XenSynth',
        },
        _ => true,
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(nativeChannel, null));
    final controller = XenSynthController()
      ..pitchRecognitionAvailable = true
      ..initialized = true;
    addTearDown(controller.dispose);
    expect(await controller.startPitchRecognition(), isTrue);
    await controller.stopPitchRecognition();

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );
    expect(
      find.byKey(const ValueKey('toolbar-save-microphone-take-button')),
      findsOneWidget,
    );
    await controller.play();
    await tester.pump();

    await tester.tap(find.byTooltip('Stop and release notes'));
    await tester.pumpAndSettle();

    expect(find.text('Save microphone recording?'), findsOneWidget);
    expect(controller.playing, isFalse);
    final dialogLeft = tester.getTopLeft(find.byType(AlertDialog)).dx;
    final cancelLeft = tester
        .getTopLeft(find.widgetWithText(TextButton, 'Cancel'))
        .dx;
    expect(cancelLeft - dialogLeft, lessThan(80));
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(
      calls.where((call) => call.method == 'stopPitchRecording'),
      isNotEmpty,
    );
    expect(
      calls.where((call) => call.method == 'savePitchRecording'),
      hasLength(1),
    );
    expect(find.text('Saved two WAV files to Music/XenSynth.'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('toolbar-save-microphone-take-button')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('toolbar-pitch-recognition-button')),
      findsOneWidget,
    );
  });

  testWidgets('resolved microphone takes restore the recording action', (
    tester,
  ) async {
    const nativeChannel = MethodChannel('icu.ringona.xensynth/platform');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(nativeChannel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'startPitchRecognition' => <String, Object?>{
          'supported': true,
          'phase': 'listening',
          'modelReady': true,
          'recognizing': true,
          'busy': false,
          'progress': 1.0,
          'message': 'Listening',
        },
        'stopPitchRecognition' => <String, Object?>{
          'supported': true,
          'phase': 'idle',
          'modelReady': true,
          'recognizing': false,
          'busy': false,
          'progress': 1.0,
          'message': '',
          'recordingDuration': 0.8,
        },
        'savePitchRecording' => <String, Object?>{
          'saved': true,
          'directory': 'Music/XenSynth',
        },
        _ => true,
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(nativeChannel, null));

    for (final action in <String>['direct-save', 'Cancel', 'Discard']) {
      calls.clear();
      final controller = XenSynthController()
        ..pitchRecognitionAvailable = true
        ..initialized = true;
      expect(await controller.startPitchRecognition(), isTrue);
      await controller.stopPitchRecognition();
      await tester.pumpWidget(
        MaterialApp(
          theme: AppPalette.theme(),
          home: XenSynthScreen(controller: controller),
        ),
      );
      await tester.pump();

      final saveButton = find.byKey(
        const ValueKey('toolbar-save-microphone-take-button'),
      );
      expect(saveButton, findsOneWidget);
      if (action == 'direct-save') {
        await tester.tap(saveButton);
      } else {
        await tester.tap(find.byTooltip('Stop and release notes'));
        await tester.pumpAndSettle();
        await tester.tap(find.widgetWithText(TextButton, action));
      }
      await tester.pumpAndSettle();

      expect(saveButton, findsNothing, reason: action);
      expect(
        find.byKey(const ValueKey('toolbar-pitch-recognition-button')),
        findsOneWidget,
        reason: action,
      );
      expect(
        controller.hasMicrophoneTake,
        action == 'Discard' ? isFalse : isTrue,
        reason: action,
      );
      expect(
        calls.where((call) => call.method == 'discardPitchRecording'),
        action == 'Discard' ? hasLength(1) : isEmpty,
        reason: action,
      );

      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      await tester.pump();
    }
  });

  testWidgets('YIN microphone mode starts without a model download dialog', (
    tester,
  ) async {
    const nativeChannel = MethodChannel('icu.ringona.xensynth/platform');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(nativeChannel, (call) async {
      calls.add(call);
      if (call.method == 'startPitchRecognition') {
        return <String, Object?>{
          'supported': true,
          'mode': 'yin',
          'phase': 'listening',
          'modelReady': false,
          'recognizing': true,
          'busy': false,
          'progress': 0.0,
          'message': 'Listening for continuous pitch',
        };
      }
      return true;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(nativeChannel, null));
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(
        pitchRecognitionMode: PitchRecognitionMode.yin,
      )
      ..pitchRecognitionAvailable = true
      ..initialized = true;
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );
    await tester.tap(
      find.byKey(const ValueKey('toolbar-pitch-recognition-button')),
    );
    await tester.pump();

    expect(find.byType(AlertDialog), findsNothing);
    final startCall = calls.singleWhere(
      (call) => call.method == 'startPitchRecognition',
    );
    final arguments = Map<Object?, Object?>.from(startCall.arguments! as Map);
    expect(arguments['mode'], 'yin');
    expect(arguments['downloadIfNeeded'], isFalse);

    await controller.stopPitchRecognition();
    await tester.pump();
  });

  testWidgets(
    'configured touch vibration strength applies to pitch and hex controls',
    (tester) async {
      const nativeChannel = MethodChannel('icu.ringona.xensynth/platform');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final hapticCalls = <MethodCall>[];
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'HapticFeedback.vibrate') hapticCalls.add(call);
        return null;
      });
      messenger.setMockMethodCallHandler(nativeChannel, (call) async {
        return call.method == 'noteOn' ? 1 : null;
      });
      addTearDown(() {
        messenger.setMockMethodCallHandler(SystemChannels.platform, null);
        messenger.setMockMethodCallHandler(nativeChannel, null);
      });

      const expectedTypes = <String>[
        'HapticFeedbackType.lightImpact',
        'HapticFeedbackType.mediumImpact',
        'HapticFeedbackType.heavyImpact',
      ];
      var expectedCallCount = 0;
      for (final mode in KeyboardLayoutMode.values) {
        final controller = XenSynthController()
          ..settings = XenSynthSettings(
            layoutMode: mode,
            hapticFeedbackStrength: (mode.index + 1) / 3,
          )
          ..initialized = true;
        await tester.pumpWidget(
          MaterialApp(
            theme: AppPalette.theme(),
            home: XenSynthScreen(controller: controller),
          ),
        );
        await tester.pump();

        switch (mode) {
          case KeyboardLayoutMode.linear:
            tester
                .widget<WaterfallView>(find.byType(WaterfallView))
                .onPitchDown(7, 60, 96);
            expectedCallCount++;
          case KeyboardLayoutMode.hexagonal:
            final view = tester.widget<HexKeyboardView>(
              find.byType(HexKeyboardView),
            );
            view.onPitchDown(7, 60, 96);
            view.onControlInteraction?.call();
            expectedCallCount += 2;
          case KeyboardLayoutMode.spatial:
            final view = tester.widget<SpatialWaterfallView>(
              find.byType(SpatialWaterfallView),
            );
            view.onPitchDown(7, 60, 96);
            view.onControlInteraction?.call();
            expectedCallCount += 2;
        }
        await tester.pump();

        expect(
          hapticCalls,
          hasLength(expectedCallCount),
          reason: '${mode.name} should trigger touch vibration',
        );
        expect(hapticCalls.last.arguments, expectedTypes[mode.index]);

        controller.noteUp(7);
        await tester.pumpWidget(const SizedBox());
        controller.dispose();
        await tester.pump();
      }
    },
  );

  testWidgets('touch vibration stays silent when disabled', (tester) async {
    const nativeChannel = MethodChannel('icu.ringona.xensynth/platform');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final hapticCalls = <MethodCall>[];
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'HapticFeedback.vibrate') hapticCalls.add(call);
      return null;
    });
    messenger.setMockMethodCallHandler(nativeChannel, (call) async {
      return call.method == 'noteOn' ? 1 : null;
    });
    addTearDown(() {
      messenger.setMockMethodCallHandler(SystemChannels.platform, null);
      messenger.setMockMethodCallHandler(nativeChannel, null);
    });
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
        hapticFeedbackStrength: 0,
      )
      ..initialized = true;
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );
    final view = tester.widget<HexKeyboardView>(find.byType(HexKeyboardView));
    view.onPitchDown(8, 62, 96);
    view.onControlInteraction?.call();
    await tester.pump();

    expect(hapticCalls, isEmpty);
    controller.noteUp(8);
  });

  testWidgets('opening settings in Hex mode exposes the basis editor', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1000, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
      )
      ..initialized = true;
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );
    await tester.tap(find.byTooltip('Settings · hold to reset'));
    await tester.pump();

    expect(
      find.byKey(const ValueKey('hex-basis-vector-editor')),
      findsOneWidget,
    );
    expect(
      tester
          .widget<HexKeyboardView>(find.byType(HexKeyboardView))
          .basisEditorVisible,
      isTrue,
    );

    final keyboardRect = tester.getRect(find.byType(HexKeyboardView));
    await tester.tapAt(Offset(keyboardRect.left + 18, keyboardRect.center.dy));
    await tester.pump();
    expect(find.byKey(const ValueKey('hex-basis-vector-editor')), findsNothing);
  });

  testWidgets(
    'live notes hide score visuals in every layout until a new score loads',
    (tester) async {
      const nativeChannel = MethodChannel('icu.ringona.xensynth/platform');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(nativeChannel, (call) async {
        if (call.method != 'noteOn') return null;
        final arguments = Map<Object?, Object?>.from(call.arguments! as Map);
        return arguments['id'];
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(nativeChannel, null),
      );

      for (final mode in KeyboardLayoutMode.values) {
        final controller = XenSynthController()
          ..settings = XenSynthSettings(layoutMode: mode)
          ..score = _loadedScore
          ..initialized = true;
        ParsedScore? displayedScore() => switch (mode) {
          KeyboardLayoutMode.linear =>
            tester.widget<WaterfallView>(find.byType(WaterfallView)).score,
          KeyboardLayoutMode.hexagonal =>
            tester.widget<HexKeyboardView>(find.byType(HexKeyboardView)).score,
          KeyboardLayoutMode.spatial =>
            tester
                .widget<SpatialWaterfallView>(find.byType(SpatialWaterfallView))
                .score,
        };

        await tester.pumpWidget(
          MaterialApp(
            theme: AppPalette.theme(),
            home: XenSynthScreen(controller: controller),
          ),
        );
        await tester.pump();
        expect(displayedScore(), same(_loadedScore), reason: mode.name);
        expect(controller.scoreVisualizationSuppressed, isFalse);

        controller.noteDown(100, 60, 96);
        controller.noteDown(101, 64, 88);
        await tester.pump();
        expect(displayedScore(), isNull, reason: mode.name);
        expect(controller.scoreVisualizationSuppressed, isTrue);

        controller.noteUp(100);
        await tester.pump();
        expect(displayedScore(), isNull, reason: mode.name);

        controller.noteUp(101);
        await tester.pump();
        expect(displayedScore(), isNull, reason: mode.name);
        expect(controller.scoreVisualizationSuppressed, isTrue);

        final replacement = await rootBundle.load(
          'assets/scores/demo_26edo.midx',
        );
        await controller.loadScoreBytes(
          replacement.buffer.asUint8List(
            replacement.offsetInBytes,
            replacement.lengthInBytes,
          ),
          'replacement.midx',
        );
        await tester.pump();
        expect(displayedScore(), isNotNull, reason: mode.name);
        expect(displayedScore(), isNot(same(_loadedScore)), reason: mode.name);
        expect(controller.scoreVisualizationSuppressed, isFalse);

        await tester.pumpWidget(const SizedBox());
        controller.dispose();
        await tester.pump();
      }
    },
  );

  testWidgets('hexagonal keyboard keeps the app texture background', (
    tester,
  ) async {
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
      );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );

    expect(
      find.image(const AssetImage('assets/images/waterfall.webp')),
      findsOneWidget,
    );
    expect(find.byType(HexKeyboardView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('spatial mode mounts the 3D waterfall over the app texture', (
    tester,
  ) async {
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
      );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );

    expect(
      find.image(const AssetImage('assets/images/waterfall.webp')),
      findsOneWidget,
    );
    expect(find.byType(SpatialWaterfallView), findsOneWidget);
    expect(find.byType(HexKeyboardView), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

const _loadedScore = ParsedScore(
  title: 'Loaded score',
  format: 'TEST',
  ticksPerQuarter: 480,
  tempos: <TempoEvent>[],
  meters: <MeterEvent>[],
  tempoMap: <TempoPoint>[],
  rawEvents: <RawNoteEvent>[],
  notes: <WaterfallNote>[
    WaterfallNote(
      startTick: 0,
      endTick: 480,
      start: 0,
      end: 1,
      pitch: 60,
      midiPitch: 60,
      cents: 0,
      velocity: 96,
      channel: 0,
      track: 0,
      program: 0,
      bankMsb: 0,
      bankLsb: 0,
    ),
  ],
  longNotes: <WaterfallNote>[],
  duration: 1,
);
