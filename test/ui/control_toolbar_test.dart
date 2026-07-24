import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/ui/app_palette.dart';
import 'package:xensynth/ui/widgets/control_toolbar.dart';

void main() {
  const sizes = <String, Size>{
    'reference phone': Size(874, 402),
    'landscape smoke': Size(1180, 720),
    'iPad landscape': Size(1194, 834),
  };

  for (final entry in sizes.entries) {
    testWidgets('toolbar fits ${entry.key} with closed metric popups', (
      tester,
    ) async {
      await tester.binding.setSurfaceSize(entry.value);
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(_toolbar());

      expect(tester.takeException(), isNull);
      expect(tester.getSize(find.byType(ControlToolbar)).height, 60);
      expect(find.text('SPEED'), findsOneWidget);
      expect(find.text('EDO'), findsOneWidget);
      expect(find.text('OFFSET'), findsOneWidget);
      expect(find.byType(Slider), findsNothing);
      expect(find.byKey(const ValueKey('toolbar-metric-popup')), findsNothing);
      expect(find.byKey(const ValueKey('toolbar-speed-input')), findsOneWidget);
      expect(find.byKey(const ValueKey('toolbar-edo-input')), findsOneWidget);
      expect(
        find.byKey(const ValueKey('toolbar-offset-input')),
        findsOneWidget,
      );
      expect(find.text('126.0 BPM | 4/4 | 0:12/2:01'), findsOneWidget);

      final titleFinder = find.text('UwU Funk in 26edo');
      final progressFinder = find.text('126.0 BPM | 4/4 | 0:12/2:01');
      final titleText = tester.widget<Text>(titleFinder);
      final progressText = tester.widget<Text>(progressFinder);
      for (final text in [titleText, progressText]) {
        expect(text.textAlign, TextAlign.end);
        expect(text.maxLines, 1);
        expect(text.overflow, TextOverflow.clip);
        expect(text.style?.color, AppPalette.playbackMuted);
        expect(text.style?.fontSize, 12);
        expect(text.style?.height, closeTo(14 / 12, 0.000001));
        expect(text.style?.fontWeight, FontWeight.w400);
        expect(text.style?.fontFamily, 'Roboto');
      }
      expect(tester.getRect(titleFinder).width, lessThanOrEqualTo(192));
      expect(
        tester.getRect(titleFinder).right,
        closeTo(tester.getRect(progressFinder).right, 0.01),
      );
      expect(
        tester.getRect(progressFinder).right,
        closeTo(entry.value.width - 12, 0.01),
      );
    });
  }

  testWidgets('toolbar spans the screen while controls avoid safe insets', (
    tester,
  ) async {
    const screenSize = Size(874, 402);
    const safeInsets = EdgeInsets.only(left: 44, right: 34);
    await tester.binding.setSurfaceSize(screenSize);
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(_toolbar(safeInsets: safeInsets));

    expect(tester.getSize(find.byType(ControlToolbar)).width, screenSize.width);
    final openButton = tester.getRect(find.byTooltip('Open score or tuning'));
    final settingsButton = tester.getRect(
      find.byTooltip('Settings · hold to reset'),
    );
    expect(openButton.width, openButton.height);
    expect(openButton.left, greaterThanOrEqualTo(safeInsets.left));
    expect(
      settingsButton.right,
      lessThanOrEqualTo(screenSize.width - safeInsets.right),
    );
  });

  testWidgets('metric triggers switch one popup and outside tap dismisses it', (
    tester,
  ) async {
    const screenSize = Size(874, 402);
    await tester.binding.setSurfaceSize(screenSize);
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_toolbar());

    await tester.tap(find.byKey(const ValueKey('toolbar-speed-trigger')));
    await tester.pump();

    expect(find.byKey(const ValueKey('toolbar-speed-slider')), findsOneWidget);
    expect(find.byType(Slider), findsOneWidget);
    expect(
      tester.getSize(find.byKey(const ValueKey('toolbar-speed-slider'))).width,
      greaterThan(200),
    );
    final popup = tester.getRect(
      find.byKey(const ValueKey('toolbar-metric-popup')),
    );
    expect(popup.left, greaterThanOrEqualTo(0));
    expect(popup.right, lessThanOrEqualTo(screenSize.width));
    expect(popup.top, greaterThanOrEqualTo(ControlToolbar.height));
    expect(popup.bottom, lessThanOrEqualTo(screenSize.height));

    await tester.tap(find.byKey(const ValueKey('toolbar-edo-trigger')));
    await tester.pump();

    expect(find.byKey(const ValueKey('toolbar-speed-slider')), findsNothing);
    expect(find.byKey(const ValueKey('toolbar-edo-slider')), findsOneWidget);
    expect(find.byType(Slider), findsOneWidget);

    await tester.tapAt(const Offset(800, 360));
    await tester.pump();

    expect(find.byKey(const ValueKey('toolbar-metric-popup')), findsNothing);
    expect(find.byType(Slider), findsNothing);
  });

  testWidgets('metric text fields sanitize live and format on focus loss', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var speed = 1.0;
    var edo = 12;
    var offset = 0.0;
    var speedChanges = 0;
    var edoChanges = 0;
    var offsetChanges = 0;

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: StatefulBuilder(
          builder: (context, setState) => Scaffold(
            body: Align(
              alignment: Alignment.topCenter,
              child: _controlToolbar(
                speed: speed,
                edo: edo,
                offset: offset,
                onSpeedChanged: (value) {
                  speedChanges++;
                  setState(() => speed = value);
                },
                onEdoChanged: (value) {
                  edoChanges++;
                  setState(() => edo = value);
                },
                onOffsetChanged: (value) {
                  offsetChanges++;
                  setState(() => offset = value);
                },
              ),
            ),
          ),
        ),
      ),
    );

    final speedInput = find.byKey(const ValueKey('toolbar-speed-input'));
    await tester.tap(speedInput);
    await tester.enterText(speedInput, '1.237');
    await tester.pump();

    expect(speed, closeTo(1.25, 0.000001));
    expect(speedChanges, 1);
    expect(_fieldText(tester, speedInput), '1.23');
    expect(find.byType(Slider), findsNothing);

    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();

    expect(_fieldText(tester, speedInput), '1.25x');
    expect(_fieldHasFocus(tester, speedInput), isFalse);

    final edoInput = find.byKey(const ValueKey('toolbar-edo-input'));
    await tester.tap(edoInput);
    await tester.enterText(edoInput, '99');
    await tester.pump();

    expect(edo, 72);
    expect(edoChanges, 1);

    final offsetInput = find.byKey(const ValueKey('toolbar-offset-input'));
    await tester.tap(offsetInput);
    await tester.enterText(offsetInput, '-200');
    await tester.pump();

    expect(_fieldText(tester, edoInput), '72');
    expect(offset, -128);
    expect(offsetChanges, 1);

    await tester.tapAt(const Offset(800, 360));
    await tester.pump();

    expect(_fieldText(tester, offsetInput), '-128 c');
    expect(_fieldHasFocus(tester, offsetInput), isFalse);

    await tester.tap(speedInput);
    await tester.enterText(speedInput, '-');
    await tester.tapAt(const Offset(800, 360));
    await tester.pump();

    expect(speedChanges, 1);
    expect(_fieldText(tester, speedInput), '1.25x');
  });

  testWidgets('popup slider reports changes while dragging', (tester) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var speed = 1.0;
    var changeCount = 0;

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: StatefulBuilder(
          builder: (context, setState) => Scaffold(
            body: Align(
              alignment: Alignment.topCenter,
              child: _controlToolbar(
                speed: speed,
                onSpeedChanged: (value) {
                  changeCount++;
                  setState(() => speed = value);
                },
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.byKey(const ValueKey('toolbar-speed-trigger')));
    await tester.pump();
    final slider = find.byKey(const ValueKey('toolbar-speed-slider'));
    final rect = tester.getRect(slider);
    final gesture = await tester.startGesture(
      Offset(rect.left + rect.width * 0.25, rect.center.dy),
    );
    await gesture.moveTo(Offset(rect.left + rect.width * 0.75, rect.center.dy));
    await tester.pump();

    expect(changeCount, greaterThan(0));
    expect(speed, inInclusiveRange(0.2, 4.0));
    final step = (speed - 0.2) / 0.05;
    expect(step, closeTo(step.roundToDouble(), 0.000001));

    await gesture.up();
    await tester.pump();
  });

  testWidgets('popup slider buttons fine-tune every metric', (tester) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var speed = 1.0;
    var edo = 12;
    var offset = 0.0;

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: StatefulBuilder(
          builder: (context, setState) => Scaffold(
            body: Align(
              alignment: Alignment.topCenter,
              child: _controlToolbar(
                speed: speed,
                edo: edo,
                offset: offset,
                onSpeedChanged: (value) => setState(() => speed = value),
                onEdoChanged: (value) => setState(() => edo = value),
                onOffsetChanged: (value) => setState(() => offset = value),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.byKey(const ValueKey('toolbar-speed-trigger')));
    await tester.pump();
    await tester.tap(find.byTooltip('Increase Playback speed'));
    await tester.pump();
    expect(speed, closeTo(1.05, 0.000001));

    await tester.tap(find.byKey(const ValueKey('toolbar-edo-trigger')));
    await tester.pump();
    await tester.tap(find.byTooltip('Decrease Equal divisions of the octave'));
    await tester.pump();
    expect(edo, 11);

    await tester.tap(find.byKey(const ValueKey('toolbar-offset-trigger')));
    await tester.pump();
    await tester.tap(find.byTooltip('Increase Pitch offset'));
    await tester.pump();
    expect(offset, 1);

    final slider = tester.getRect(
      find.byKey(const ValueKey('toolbar-offset-slider')),
    );
    expect(
      tester.getRect(find.byTooltip('Decrease Pitch offset')).right,
      lessThanOrEqualTo(slider.left),
    );
    expect(
      tester.getRect(find.byTooltip('Increase Pitch offset')).left,
      greaterThanOrEqualTo(slider.right),
    );
  });

  testWidgets('recording locks file and transport controls', (tester) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var invocations = 0;

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topCenter,
            child: _controlToolbar(
              transportLocked: true,
              onOpen: () => invocations++,
              onTogglePlayback: () => invocations++,
              onReset: () => invocations++,
              onStop: () => invocations++,
            ),
          ),
        ),
      ),
    );

    for (final tooltip in <String>[
      'Open score or tuning',
      'Play',
      'Back to start',
      'Stop and release notes',
    ]) {
      await tester.tap(find.byTooltip(tooltip), warnIfMissed: false);
    }
    await tester.pump();

    expect(invocations, 0);
  });

  testWidgets(
    'microphone recognition control exposes download and active states',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(874, 402));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      var taps = 0;

      await tester.pumpWidget(
        MaterialApp(
          theme: AppPalette.theme(),
          home: Scaffold(
            body: Align(
              alignment: Alignment.topCenter,
              child: _controlToolbar(
                pitchRecognitionAvailable: true,
                onPitchRecognition: () => taps++,
              ),
            ),
          ),
        ),
      );

      final button = find.byKey(
        const ValueKey('toolbar-pitch-recognition-button'),
      );
      expect(button, findsOneWidget);
      expect(
        find.byTooltip('Download model and recognize piano notes'),
        findsOneWidget,
      );

      await tester.tap(button);
      expect(taps, 1);

      await tester.pumpWidget(
        MaterialApp(
          theme: AppPalette.theme(),
          home: Scaffold(
            body: Align(
              alignment: Alignment.topCenter,
              child: _controlToolbar(
                pitchRecognitionAvailable: true,
                pitchRecognizing: true,
                pitchRecognitionModelReady: true,
                onPitchRecognition: () => taps++,
              ),
            ),
          ),
        ),
      );

      expect(find.byTooltip('Stop piano note recognition'), findsOneWidget);
      expect(find.byIcon(Icons.mic_rounded), findsOneWidget);

      await tester.pumpWidget(
        MaterialApp(
          theme: AppPalette.theme(),
          home: Scaffold(
            body: Align(
              alignment: Alignment.topCenter,
              child: _controlToolbar(
                pitchRecognitionAvailable: true,
                pitchRecognitionMode: PitchRecognitionMode.yin,
                onPitchRecognition: () => taps++,
              ),
            ),
          ),
        ),
      );
      expect(
        find.byTooltip('Detect continuous monophonic pitch with YIN'),
        findsOneWidget,
      );
    },
  );

  testWidgets('recording playback replaces microphone control with save', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var saves = 0;
    var recordings = 0;

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topCenter,
            child: _controlToolbar(
              pitchRecognitionAvailable: true,
              microphoneTakeReadyForSave: true,
              onPitchRecognition: () => recordings++,
              onSaveMicrophoneTake: () => saves++,
            ),
          ),
        ),
      ),
    );

    expect(
      find.byKey(const ValueKey('toolbar-pitch-recognition-button')),
      findsNothing,
    );
    final saveButton = find.byKey(
      const ValueKey('toolbar-save-microphone-take-button'),
    );
    expect(saveButton, findsOneWidget);
    expect(
      find.byTooltip('Save recording and recognized-pitch audio'),
      findsOneWidget,
    );
    expect(find.byIcon(Icons.save_alt_rounded), findsOneWidget);

    await tester.tap(saveButton);
    expect(saves, 1);
    expect(recordings, 0);
  });

  testWidgets('hex viewport toolbar gesture reports one interaction per drag', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var panUpdates = 0;
    var interactions = 0;

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topCenter,
            child: _controlToolbar(
              hexKeyboardGesturesEnabled: true,
              onHexKeyboardPan: (_) => panUpdates++,
              onHexKeyboardZoom: (_) {},
              onHexKeyboardInteraction: () => interactions++,
            ),
          ),
        ),
      ),
    );

    final gestureArea = find.byKey(
      const ValueKey('toolbar-hex-viewport-gesture-area'),
    );
    await tester.drag(gestureArea, const Offset(-40, 12));
    await tester.pump();

    expect(panUpdates, greaterThan(0));
    expect(interactions, 1);

    await tester.drag(gestureArea, const Offset(32, -8));
    await tester.pump();
    expect(interactions, 2);
  });
}

Widget _toolbar({EdgeInsets? safeInsets}) {
  final app = MaterialApp(
    theme: AppPalette.theme(),
    home: Scaffold(
      body: Align(alignment: Alignment.topCenter, child: _controlToolbar()),
    ),
  );
  if (safeInsets == null) return app;
  return MediaQuery(
    data: MediaQueryData(size: const Size(874, 402), padding: safeInsets),
    child: app,
  );
}

ControlToolbar _controlToolbar({
  double speed = 1,
  int edo = 53,
  double offset = 0,
  ValueChanged<double>? onSpeedChanged,
  ValueChanged<int>? onEdoChanged,
  ValueChanged<double>? onOffsetChanged,
  bool pitchRecognitionAvailable = false,
  bool pitchRecognizing = false,
  bool pitchRecognitionModelReady = false,
  PitchRecognitionMode pitchRecognitionMode = PitchRecognitionMode.piano,
  VoidCallback? onPitchRecognition,
  bool microphoneTakeReadyForSave = false,
  VoidCallback? onSaveMicrophoneTake,
  bool transportLocked = false,
  VoidCallback? onOpen,
  VoidCallback? onTogglePlayback,
  VoidCallback? onReset,
  VoidCallback? onStop,
  bool hexKeyboardGesturesEnabled = false,
  ValueChanged<Offset>? onHexKeyboardPan,
  ValueChanged<double>? onHexKeyboardZoom,
  VoidCallback? onHexKeyboardInteraction,
}) {
  return ControlToolbar(
    title: 'UwU Funk in 26edo',
    status: 'MIDX · 128 NOTES',
    playing: false,
    loading: false,
    position: 12,
    duration: 121,
    speed: speed,
    edo: edo,
    offsetCents: offset,
    tuningLabel: '26 EDO',
    settingsOpen: false,
    bpm: 126,
    meterNumerator: 4,
    meterDenominator: 4,
    onOpen: onOpen ?? () {},
    onTogglePlayback: onTogglePlayback ?? () {},
    onReset: onReset ?? () {},
    onStop: onStop ?? () {},
    onSpeedChanged: onSpeedChanged ?? (_) {},
    onEdoChanged: onEdoChanged ?? (_) {},
    onOffsetChanged: onOffsetChanged ?? (_) {},
    onSettings: () {},
    onResetSettings: () {},
    onSeek: (_) {},
    pitchRecognitionAvailable: pitchRecognitionAvailable,
    pitchRecognizing: pitchRecognizing,
    pitchRecognitionModelReady: pitchRecognitionModelReady,
    pitchRecognitionMode: pitchRecognitionMode,
    onPitchRecognition: onPitchRecognition,
    microphoneTakeReadyForSave: microphoneTakeReadyForSave,
    onSaveMicrophoneTake: onSaveMicrophoneTake,
    transportLocked: transportLocked,
    hexKeyboardGesturesEnabled: hexKeyboardGesturesEnabled,
    onHexKeyboardPan: onHexKeyboardPan,
    onHexKeyboardZoom: onHexKeyboardZoom,
    onHexKeyboardInteraction: onHexKeyboardInteraction,
  );
}

String _fieldText(WidgetTester tester, Finder finder) {
  final editable = _editableText(finder);
  return tester.widget<EditableText>(editable).controller.text;
}

bool _fieldHasFocus(WidgetTester tester, Finder finder) {
  return tester.widget<EditableText>(_editableText(finder)).focusNode.hasFocus;
}

Finder _editableText(Finder finder) {
  return find.descendant(of: finder, matching: find.byType(EditableText));
}
