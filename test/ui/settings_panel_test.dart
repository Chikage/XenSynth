import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/ui/app_palette.dart';
import 'package:xensynth/ui/widgets/settings_panel.dart';

void main() {
  testWidgets('microphone input exposes piano, YIN, and FFT modes', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings();
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 330,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  pitchRecognitionAvailable: true,
                  onChanged: (value) => setState(() => settings = value),
                  onReset: () {},
                ),
              ),
            ),
          ),
        ),
      ),
    );

    final scrollable = find.byWidgetPredicate(
      (widget) =>
          widget is Scrollable && widget.axisDirection == AxisDirection.down,
    );
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('pitch-recognition-mode')),
      100,
      scrollable: scrollable,
    );
    expect(find.text('MIC INPUT'), findsOneWidget);
    expect(find.text('PIANO'), findsOneWidget);
    expect(find.text('YIN'), findsOneWidget);
    expect(find.text('FFT'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('microphone-sensitivity-slider')),
      60,
      scrollable: scrollable,
    );
    expect(find.text('Mic sensitivity'), findsOneWidget);
    expect(find.text('100%'), findsOneWidget);

    await tester.drag(scrollable, const Offset(0, -60));
    await tester.pumpAndSettle();
    await tester.tap(find.text('YIN'));
    await tester.pump();

    expect(settings.pitchRecognitionMode, PitchRecognitionMode.yin);

    await tester.tap(find.text('FFT'));
    await tester.pump();

    expect(settings.pitchRecognitionMode, PitchRecognitionMode.fft);
    expect(settings.layoutMode, KeyboardLayoutMode.linear);
  });

  testWidgets('touch vibration strength slider sits with surface settings', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings();
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 330,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  onChanged: (value) => setState(() => settings = value),
                  onReset: () {},
                ),
              ),
            ),
          ),
        ),
      ),
    );

    final hapticSlider = find.byKey(const ValueKey('haptic-feedback-slider'));
    expect(find.text('Touch vibration'), findsOneWidget);
    expect(find.text('MED'), findsOneWidget);
    expect(hapticSlider, findsOneWidget);
    expect(
      tester.getTopLeft(hapticSlider).dy,
      lessThan(tester.getTopLeft(find.text('AUDIO')).dy),
    );
    expect(
      settings.hapticFeedbackStrength,
      XenSynthSettings.defaultHapticFeedbackStrength,
    );

    tester.widget<Slider>(hapticSlider).onChanged!(0);
    await tester.pump();

    expect(settings.hapticFeedbackStrength, 0);
    expect(settings.hapticFeedbackEnabled, isFalse);
    expect(find.text('OFF'), findsOneWidget);
  });

  testWidgets('GM program accepts direct numeric input', (tester) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings(program: 0);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 330,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  onChanged: (value) => setState(() => settings = value),
                  onReset: () {},
                ),
              ),
            ),
          ),
        ),
      ),
    );

    final input = find.byKey(const ValueKey('gm-program-input'));
    expect(input, findsOneWidget);
    expect(find.text('SETTINGS'), findsNothing);
    expect(find.byTooltip('Close settings'), findsNothing);

    await tester.enterText(input, '42');
    await tester.pump();
    expect(settings.program, 42);

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pump();
    expect(settings.program, 43);

    await tester.tap(find.byIcon(Icons.remove_rounded));
    await tester.pump();
    expect(settings.program, 42);

    await tester.enterText(input, '999');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();
    expect(settings.program, 127);
    expect(find.text('127'), findsOneWidget);
  });

  testWidgets('hex settings follow EDO and show Android touch semantics', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 330,
              child: SettingsPanel(
                settings: const XenSynthSettings(
                  layoutMode: KeyboardLayoutMode.hexagonal,
                  edo: 5,
                  hexStepQ: 99,
                  hexStepR: -4,
                ),
                onChanged: (_) {},
                onReset: () {},
              ),
            ),
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
    final settingsScrollable = find.byWidgetPredicate(
      (widget) =>
          widget is Scrollable && widget.axisDirection == AxisDirection.down,
    );
    await tester.scrollUntilVisible(
      find.text('Q step'),
      120,
      scrollable: settingsScrollable,
    );
    await tester.scrollUntilVisible(
      find.text('1.8 s'),
      120,
      scrollable: settingsScrollable,
    );
    expect(find.text('Period'), findsNothing);
    expect(find.text('IMPORT TUNING JSON'), findsNothing);
    expect(find.text('Q step'), findsOneWidget);
    expect(find.text('R step'), findsOneWidget);
    expect(find.text('120%'), findsOneWidget);
    expect(find.text('1.8 s'), findsOneWidget);
  });

  testWidgets('spatial mode retains hex controls and exposes projection', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 330,
              child: SettingsPanel(
                settings: const XenSynthSettings(
                  layoutMode: KeyboardLayoutMode.spatial,
                  spatialProjection: SpatialProjectionMode.obliquePerspective,
                ),
                onChanged: (_) {},
                onReset: () {},
              ),
            ),
          ),
        ),
      ),
    );

    final settingsScrollable = find.byWidgetPredicate(
      (widget) =>
          widget is Scrollable && widget.axisDirection == AxisDirection.down,
    );
    expect(find.text('3D WATERFALL'), findsOneWidget);
    expect(find.text('CABINET\nPROJECTION'), findsOneWidget);
    expect(find.text('OBLIQUE\nPERSPECTIVE'), findsOneWidget);
    expect(
      find.byTooltip('Cabinet projection (1:2 oblique dimetric)'),
      findsOneWidget,
    );
    await tester.scrollUntilVisible(
      find.text('Q step'),
      120,
      scrollable: settingsScrollable,
    );
    expect(find.text('HEX KEYBOARD'), findsOneWidget);
    expect(find.text('Q step'), findsOneWidget);
    expect(find.text('R step'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('hex dimensions and signed steps accept direct input', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings(
      layoutMode: KeyboardLayoutMode.hexagonal,
      edo: 7,
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 330,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  onChanged: (value) => setState(() => settings = value),
                  onReset: () {},
                ),
              ),
            ),
          ),
        ),
      ),
    );

    final scrollable = find.byWidgetPredicate(
      (widget) =>
          widget is Scrollable && widget.axisDirection == AxisDirection.down,
    );
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('hex-q-step-input')),
      120,
      scrollable: scrollable,
    );

    expect(find.byKey(const ValueKey('hex-columns-input')), findsOneWidget);
    expect(find.byKey(const ValueKey('hex-rows-input')), findsOneWidget);
    expect(find.byKey(const ValueKey('hex-r-step-input')), findsOneWidget);

    await tester.enterText(
      find.byKey(const ValueKey('hex-columns-input')),
      '42',
    );
    await tester.enterText(find.byKey(const ValueKey('hex-rows-input')), '12');
    await tester.enterText(
      find.byKey(const ValueKey('hex-q-step-input')),
      '-5',
    );
    await tester.enterText(
      find.byKey(const ValueKey('hex-r-step-input')),
      '-99',
    );
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();

    expect(settings.hexColumns, 42);
    expect(settings.hexRows, 12);
    expect(settings.hexStepQ, -5);
    expect(settings.hexStepR, -6);

    await tester.enterText(find.byKey(const ValueKey('hex-r-step-input')), '0');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();
    expect(settings.hexStepR, -1);
    expect(find.text('-1'), findsOneWidget);
  });
}
