import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/platform/native_bridge.dart';
import 'package:xensynth/ui/app_palette.dart';
import 'package:xensynth/ui/widgets/settings_panel.dart';

void main() {
  testWidgets('microphone input exposes one local hybrid sensitivity control', (
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
      find.byKey(const ValueKey('microphone-sensitivity-slider')),
      60,
      scrollable: scrollable,
    );
    expect(find.text('Mic sensitivity'), findsOneWidget);
    expect(find.text('100%'), findsOneWidget);
    expect(find.text('MIC INPUT'), findsOneWidget);
    expect(find.text('PIANO'), findsNothing);
    expect(find.text('YIN'), findsNothing);
    expect(find.text('FFT'), findsNothing);
    expect(settings.pitchRecognitionMode, PitchRecognitionMode.hybrid);
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

  testWidgets(
    'MIDI panel exposes per-device input output and network controls',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(874, 402));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      var settings = const XenSynthSettings(networkMidiEnabled: true);
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
                    bluetoothMidiOutputs: const [
                      NativeMidiOutput(id: 'bluetooth:9:0', name: 'Controller'),
                    ],
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
        find.text('MIDI'),
        100,
        scrollable: scrollable,
      );
      expect(find.text('MIDI'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('RTP-MIDI / AppleMIDI'),
        100,
        scrollable: scrollable,
      );
      expect(find.text('RTP-MIDI / AppleMIDI'), findsOneWidget);
      expect(find.text('MIDI input', skipOffstage: false), findsNothing);
      expect(find.text('MIDI output', skipOffstage: false), findsNothing);
      expect(
        find.byKey(
          const ValueKey('midi-input-enabled-switch'),
          skipOffstage: false,
        ),
        findsNothing,
      );
      expect(
        find.byKey(
          const ValueKey('midi-output-enabled-switch'),
          skipOffstage: false,
        ),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('network-midi-host-input')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('network-midi-port-input')),
        findsNothing,
      );
      await tester.scrollUntilVisible(
        find.text('Controller'),
        100,
        scrollable: scrollable,
      );
      expect(find.text('Controller'), findsOneWidget);

      final outputSwitch = find.byKey(
        const ValueKey('midi-output-toggle-android-midi:9'),
      );
      expect(outputSwitch, findsOneWidget);
      expect(tester.widget<Switch>(outputSwitch).onChanged, isNotNull);
      await tester.tap(outputSwitch);
      await tester.pump();
      expect(settings.midiOutputEnabled, isTrue);
      expect(settings.bluetoothMidiOutputIds, ['bluetooth:9:0']);
    },
  );

  testWidgets('disabled RTP-MIDI hides LAN devices and skips LAN refresh', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 700));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var localRefreshes = 0;
    var networkRefreshes = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 650,
              child: SettingsPanel(
                settings: const XenSynthSettings(networkMidiEnabled: false),
                midiInputDevices: const <NativeMidiOutput>[
                  NativeMidiOutput(
                    id: 'android-midi-input:7:0',
                    name: 'USB Keyboard',
                    transport: 'usb',
                    isInput: true,
                  ),
                  NativeMidiOutput(
                    id: 'applemidi:lan-input',
                    name: 'Stale LAN Keyboard',
                    transport: 'network',
                    isInput: true,
                  ),
                ],
                bluetoothMidiOutputs: const <NativeMidiOutput>[
                  NativeMidiOutput(
                    id: 'bluetooth:9:0',
                    name: 'Bluetooth Synth',
                    transport: 'bluetooth',
                  ),
                ],
                networkMidiOutputs: const <NativeMidiOutput>[
                  NativeMidiOutput(
                    id: 'applemidi:lan-output',
                    name: 'Stale LAN Synth',
                    transport: 'network',
                  ),
                ],
                onRefreshBluetoothMidiOutputs: () => localRefreshes++,
                onRefreshNetworkMidiOutputs: () => networkRefreshes++,
                onChanged: (_) {},
                onReset: () {},
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
      find.textContaining('USB Keyboard'),
      100,
      scrollable: scrollable,
    );

    expect(find.textContaining('USB Keyboard'), findsOneWidget);
    expect(find.textContaining('Bluetooth Synth'), findsOneWidget);
    expect(
      find.textContaining('Stale LAN Keyboard', skipOffstage: false),
      findsNothing,
    );
    expect(
      find.textContaining('Stale LAN Synth', skipOffstage: false),
      findsNothing,
    );

    await tester.tap(find.byKey(const ValueKey('scan-network-midi-outputs')));
    expect(localRefreshes, 1);
    expect(networkRefreshes, 0);
  });

  testWidgets('MIDI settings omit standalone input and output switches', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 800,
              child: SettingsPanel(
                settings: const XenSynthSettings(),
                onChanged: (_) {},
                onReset: () {},
              ),
            ),
          ),
        ),
      ),
    );

    final midiHeading = find.text('MIDI');
    final networkMidiToggle = find.text('RTP-MIDI / AppleMIDI');
    final deviceHeader = find.text('AVAILABLE MIDI DEVICES');

    expect(find.text('MIDI INPUT'), findsNothing);
    expect(find.text('MIDI OUTPUT'), findsNothing);
    expect(find.text('MIDI input'), findsNothing);
    expect(find.text('MIDI output'), findsNothing);
    expect(
      find.byKey(const ValueKey('midi-input-enabled-switch')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('midi-output-enabled-switch')),
      findsNothing,
    );
    expect(
      tester.getTopLeft(networkMidiToggle).dy,
      greaterThan(tester.getTopLeft(midiHeading).dy),
    );
    expect(
      tester.getTopLeft(networkMidiToggle).dy,
      lessThan(tester.getTopLeft(deviceHeader).dy),
    );
  });

  testWidgets(
    'local MIDI directions remain separate while LAN devices use one link',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(874, 500));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      var settings = const XenSynthSettings(
        midiInputEnabled: false,
        midiOutputEnabled: false,
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: AppPalette.theme(),
          home: Scaffold(
            body: Align(
              alignment: Alignment.topRight,
              child: SizedBox(
                height: 430,
                child: StatefulBuilder(
                  builder: (context, setState) => SettingsPanel(
                    settings: settings,
                    midiInputDevices: const [
                      NativeMidiOutput(
                        id: 'coremidi:12',
                        name: 'USB Keyboard',
                        transport: 'usb',
                        isInput: true,
                      ),
                    ],
                    midiOutputDevices: const [
                      NativeMidiOutput(
                        id: 'applemidi:peer',
                        name: 'Studio iPad',
                        hostAddress: '192.168.1.12',
                        port: 5004,
                        transport: 'network',
                      ),
                    ],
                    onChanged: (value) => setState(() => settings = value),
                    onReset: () {},
                  ),
                ),
              ),
            ),
          ),
        ),
      );

      final scrollable = find
          .byWidgetPredicate(
            (widget) =>
                widget is Scrollable &&
                widget.axisDirection == AxisDirection.down,
          )
          .first;
      await tester.scrollUntilVisible(
        find.textContaining('USB Keyboard'),
        100,
        scrollable: scrollable,
      );
      expect(find.textContaining('USB Keyboard'), findsOneWidget);
      expect(find.textContaining('Studio iPad'), findsOneWidget);

      final inputSwitch = find.byKey(
        const ValueKey('midi-input-toggle-coremidi:12'),
      );
      await tester.ensureVisible(inputSwitch);
      await tester.pump();
      await tester.tap(inputSwitch);
      await tester.pump();
      expect(settings.midiInputEnabled, isTrue);
      expect(settings.midiInputDeviceSelectionConfigured, isTrue);
      expect(settings.midiInputDeviceIds, ['coremidi:12']);

      final connectionSwitch = find.byKey(
        const ValueKey('midi-connection-toggle-applemidi:peer'),
      );
      await tester.ensureVisible(connectionSwitch);
      await tester.pump();
      await tester.tap(connectionSwitch);
      await tester.pump();
      expect(settings.midiOutputEnabled, isTrue);
      expect(settings.midiInputDeviceIds, ['applemidi:peer']);
      expect(settings.midiOutputDeviceIds, ['applemidi:peer']);
      expect(settings.networkMidiDestinationIds, ['applemidi:peer']);
    },
  );

  testWidgets('local software MIDI receivers appear as destinations', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 500));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings();
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 430,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  midiOutputDevices: const [
                    NativeMidiOutput(
                      id: 'android-midi-output:41:0',
                      name: 'Local Synth Host',
                      transport: 'software',
                    ),
                  ],
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
      find.text('Local Synth Host [Software]'),
      100,
      scrollable: scrollable,
    );
    expect(
      find.text('AVAILABLE MIDI DEVICES', skipOffstage: false),
      findsOneWidget,
    );

    final outputSwitch = find.byKey(
      const ValueKey('midi-output-toggle-android-midi:41'),
    );
    await tester.ensureVisible(outputSwitch);
    await tester.pump();
    await tester.tap(outputSwitch);
    await tester.pump();

    expect(settings.midiOutputDeviceIds, ['android-midi-output:41:0']);
  });

  testWidgets('network scan results use one duplex connection control', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 500));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings();
    var scans = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 430,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  networkMidiOutputs: const [
                    NativeMidiOutput(
                      id: 'applemidi:WGVuU3ludGg',
                      name: 'XenSynth iPhone',
                    ),
                  ],
                  onRefreshNetworkMidiOutputs: () => scans++,
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
      find.byKey(const ValueKey('scan-network-midi-outputs')),
      100,
      scrollable: scrollable,
    );
    await tester.scrollUntilVisible(
      find.text('XenSynth iPhone'),
      100,
      scrollable: scrollable,
    );
    expect(find.text('XenSynth iPhone'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('scan-network-midi-outputs')));
    expect(scans, 1);
    await tester.scrollUntilVisible(
      find.text('XenSynth iPhone'),
      100,
      scrollable: scrollable,
    );
    final connectionSwitch = find.byKey(
      const ValueKey('midi-connection-toggle-applemidi:WGVuU3ludGg'),
    );
    await tester.ensureVisible(connectionSwitch);
    await tester.pump();
    await tester.tap(connectionSwitch);
    await tester.pump();
    expect(settings.midiInputDeviceIds, ['applemidi:WGVuU3ludGg']);
    expect(settings.networkMidiDestinationIds, ['applemidi:WGVuU3ludGg']);
  });

  testWidgets('one RTP-MIDI peer can be enabled for input and output', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 500));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    const peerId = 'applemidi:peer';
    const inputTargetId = 'network:192.168.1.12:5004';
    const outputTargetId = 'network:192.168.1.12:5005';
    var settings = const XenSynthSettings(
      midiInputDeviceIds: [peerId],
      midiInputDeviceSelectionConfigured: true,
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 430,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  midiInputDevices: const [
                    NativeMidiOutput(
                      id: peerId,
                      name: 'Input iPad',
                      targetId: inputTargetId,
                      transport: 'network',
                      isInput: true,
                    ),
                  ],
                  midiOutputDevices: const [
                    NativeMidiOutput(
                      id: peerId,
                      name: 'Output iPad',
                      targetId: outputTargetId,
                      transport: 'network',
                    ),
                  ],
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
      find.textContaining('Output iPad'),
      100,
      scrollable: scrollable,
    );
    expect(find.textContaining('Output iPad'), findsOneWidget);
    expect(find.textContaining('Input iPad'), findsNothing);
    final connectionSwitch = find.byKey(
      const ValueKey('midi-connection-toggle-192.168.1.12'),
    );
    expect(tester.widget<Switch>(connectionSwitch).onChanged, isNotNull);
    expect(
      find.byKey(const ValueKey('midi-input-toggle-192.168.1.12')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('midi-output-toggle-192.168.1.12')),
      findsNothing,
    );
    await tester.ensureVisible(connectionSwitch);
    await tester.pump();
    await tester.tap(connectionSwitch);
    await tester.pump();
    expect(settings.midiInputDeviceIds, [peerId]);
    expect(settings.midiOutputDeviceIds, [peerId]);
    expect(tester.widget<Switch>(connectionSwitch).value, isTrue);
  });

  testWidgets('RTP-MIDI link mirrors one canonical ID across both scans', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 500));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings();
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 430,
              child: StatefulBuilder(
                builder: (context, setState) => SettingsPanel(
                  settings: settings,
                  midiInputDevices: const [
                    NativeMidiOutput(
                      id: 'applemidi:input-alias',
                      name: 'Studio iPhone',
                      targetId: 'network:192.168.1.24:5004',
                      transport: 'network',
                      isInput: true,
                    ),
                  ],
                  networkMidiOutputs: const [
                    NativeMidiOutput(
                      id: 'applemidi:output-alias',
                      name: 'Studio iPhone',
                      targetId: 'network:192.168.1.24:5005',
                      transport: 'network',
                    ),
                  ],
                  onChanged: (value) => setState(() => settings = value),
                  onReset: () {},
                ),
              ),
            ),
          ),
        ),
      ),
    );

    final connectionSwitch = find.byKey(
      const ValueKey('midi-connection-toggle-192.168.1.24'),
    );
    await tester.ensureVisible(connectionSwitch);
    await tester.pump();
    await tester.tap(connectionSwitch);
    await tester.pump();

    expect(settings.midiInputDeviceIds, ['applemidi:output-alias']);
    expect(settings.midiOutputDeviceIds, ['applemidi:output-alias']);
    expect(tester.widget<Switch>(connectionSwitch).value, isTrue);
  });

  testWidgets('RTP-MIDI peers on different addresses remain separate rows', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 500));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 430,
              child: SettingsPanel(
                settings: const XenSynthSettings(),
                networkMidiOutputs: const [
                  NativeMidiOutput(
                    id: 'applemidi:first',
                    name: 'Studio iPad',
                    targetId: 'network:192.168.1.12',
                    transport: 'network',
                  ),
                  NativeMidiOutput(
                    id: 'applemidi:second',
                    name: 'Stage iPhone',
                    targetId: 'network:192.168.1.13',
                    transport: 'network',
                  ),
                ],
                onChanged: (_) {},
                onReset: () {},
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
      find.textContaining('Studio iPad'),
      100,
      scrollable: scrollable,
    );

    expect(find.textContaining('Studio iPad'), findsOneWidget);
    expect(find.textContaining('Stage iPhone'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('midi-connection-toggle-192.168.1.12')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('midi-connection-toggle-192.168.1.13')),
      findsOneWidget,
    );
  });

  testWidgets('unsupported MIDI directions have disabled switches', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 700));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 650,
              child: SettingsPanel(
                settings: const XenSynthSettings(),
                midiInputDevices: const [
                  NativeMidiOutput(
                    id: 'input-only',
                    name: 'Input only keyboard',
                    targetId: 'coremidi-device:7',
                    isInput: true,
                  ),
                ],
                midiOutputDevices: const [
                  NativeMidiOutput(
                    id: 'output-only',
                    name: 'Output only synth',
                    targetId: 'coremidi-device:9',
                  ),
                ],
                onChanged: (_) {},
                onReset: () {},
              ),
            ),
          ),
        ),
      ),
    );

    final inputOnlyInput = find.byKey(
      const ValueKey('midi-input-toggle-coremidi-device:7'),
    );
    final inputOnlyOutput = find.byKey(
      const ValueKey('midi-output-toggle-coremidi-device:7'),
    );
    final outputOnlyInput = find.byKey(
      const ValueKey('midi-input-toggle-coremidi-device:9'),
    );
    final outputOnlyOutput = find.byKey(
      const ValueKey('midi-output-toggle-coremidi-device:9'),
    );

    expect(tester.widget<Switch>(inputOnlyInput).onChanged, isNotNull);
    expect(tester.widget<Switch>(inputOnlyOutput).onChanged, isNull);
    expect(tester.widget<Switch>(outputOnlyInput).onChanged, isNull);
    expect(tester.widget<Switch>(outputOnlyOutput).onChanged, isNotNull);
  });

  testWidgets('settings controls keep clear vertical separation', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 600));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 540,
              child: SettingsPanel(
                settings: const XenSynthSettings(),
                onChanged: (_) {},
                onReset: () {},
              ),
            ),
          ),
        ),
      ),
    );

    final layoutSelector = find.byType(SegmentedButton<KeyboardLayoutMode>);
    expect(
      tester.getTopLeft(find.text('Touch vibration')).dy -
          tester.getBottomLeft(layoutSelector).dy,
      greaterThanOrEqualTo(4),
    );
    expect(
      tester.getTopLeft(find.text('Reverb')).dy -
          tester.getBottomLeft(find.byTooltip('Increase Volume')).dy,
      greaterThanOrEqualTo(4),
    );
    expect(
      tester.getTopLeft(find.text('AUDIO')).dy -
          tester.getBottomLeft(find.byTooltip('Increase Touch vibration')).dy,
      greaterThanOrEqualTo(10),
    );
  });

  testWidgets('slider step buttons nudge one division and stop at limits', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(874, 402));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var settings = const XenSynthSettings(volumeGain: 0.5);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: Scaffold(
          body: Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: 390,
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

    final volumeSlider = find.byType(Slider).at(1);
    expect(tester.getSize(volumeSlider).width, greaterThan(200));
    expect(
      tester.getRect(find.byTooltip('Decrease Volume')).right,
      lessThanOrEqualTo(tester.getRect(volumeSlider).left),
    );
    expect(
      tester.getRect(find.byTooltip('Increase Volume')).left,
      greaterThanOrEqualTo(tester.getRect(volumeSlider).right),
    );

    await tester.tap(find.byTooltip('Decrease Volume'));
    await tester.pump();
    expect(settings.volumeGain, closeTo(0.49, 0.000001));

    await tester.tap(find.byTooltip('Increase Volume'));
    await tester.pump();
    expect(settings.volumeGain, closeTo(0.5, 0.000001));

    await tester.tap(find.byTooltip('Increase Touch vibration'));
    await tester.pump();
    expect(settings.hapticFeedbackStrength, 1);
    final upperLimitButton = tester.widget<IconButton>(
      find.byKey(const ValueKey('settings-Touch vibration-slider-increase')),
    );
    expect(upperLimitButton.onPressed, isNull);
  });

  testWidgets('GM program uses a slider with step buttons', (tester) async {
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

    final slider = find.byKey(const ValueKey('gm-program-slider'));
    expect(slider, findsOneWidget);
    expect(find.text('SETTINGS'), findsNothing);
    expect(find.byTooltip('Close settings'), findsNothing);

    tester.widget<Slider>(slider).onChanged!(42);
    await tester.pump();
    expect(settings.program, 42);

    await tester.tap(find.byTooltip('Increase GM program'));
    await tester.pump();
    expect(settings.program, 43);

    await tester.tap(find.byTooltip('Decrease GM program'));
    await tester.pump();
    expect(settings.program, 42);

    tester.widget<Slider>(slider).onChanged!(127);
    await tester.pump();
    expect(settings.program, 127);
    expect(find.text('127'), findsOneWidget);
    final increaseButton = tester.widget<IconButton>(
      find.byKey(const ValueKey('settings-GM program-slider-increase')),
    );
    expect(increaseButton.onPressed, isNull);
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
    await tester.scrollUntilVisible(
      find.text('CABINET\nPROJECTION'),
      40,
      scrollable: settingsScrollable,
    );
    expect(find.text('3D WATERFALL'), findsOneWidget);
    expect(find.text('CABINET\nPROJECTION'), findsOneWidget);
    expect(find.text('OBLIQUE\nPERSPECTIVE'), findsOneWidget);
    expect(
      find.byTooltip('Cabinet projection (1:2 oblique dimetric)'),
      findsOneWidget,
    );
    await tester.scrollUntilVisible(
      find.text('HEX KEYBOARD'),
      40,
      scrollable: settingsScrollable,
    );
    expect(find.text('HEX KEYBOARD'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Q step'),
      40,
      scrollable: settingsScrollable,
    );
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
    for (final entry in <String, Key>{
      'Columns': const ValueKey('hex-columns-input'),
      'Rows': const ValueKey('hex-rows-input'),
      'Q step': const ValueKey('hex-q-step-input'),
      'R step': const ValueKey('hex-r-step-input'),
    }.entries) {
      expect(
        tester.getBottomLeft(find.text(entry.key)).dy,
        lessThanOrEqualTo(tester.getTopLeft(find.byKey(entry.value)).dy),
      );

      final frame = tester.getRect(
        find.byKey(ValueKey('settings-${entry.key}-input-frame')),
      );
      final field = tester.getRect(find.byKey(entry.value));
      final decrease = tester.getRect(
        find.byKey(ValueKey('settings-${entry.key}-input-decrease')),
      );
      final increase = tester.getRect(
        find.byKey(ValueKey('settings-${entry.key}-input-increase')),
      );
      expect(decrease.center.dx, closeTo((frame.left + field.left) / 2, 0.5));
      expect(increase.center.dx, closeTo((field.right + frame.right) / 2, 0.5));
    }

    for (final entry in <Key, String>{
      const ValueKey('hex-columns-input'): '42',
      const ValueKey('hex-rows-input'): '12',
      const ValueKey('hex-q-step-input'): '-5',
      const ValueKey('hex-r-step-input'): '-99',
    }.entries) {
      await tester.scrollUntilVisible(
        find.byKey(entry.key),
        80,
        scrollable: scrollable,
      );
      await tester.enterText(find.byKey(entry.key), entry.value);
    }
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();

    expect(settings.hexColumns, 42);
    expect(settings.hexRows, 12);
    expect(settings.hexStepQ, -5);
    expect(settings.hexStepR, -6);

    await tester.enterText(find.byKey(const ValueKey('hex-q-step-input')), '0');
    await tester.enterText(find.byKey(const ValueKey('hex-r-step-input')), '0');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();
    expect(settings.hexStepQ, 0);
    expect(settings.hexStepR, 0);
    expect(
      tester
          .widget<TextField>(find.byKey(const ValueKey('hex-q-step-input')))
          .controller
          ?.text,
      '0',
    );
    expect(
      tester
          .widget<TextField>(find.byKey(const ValueKey('hex-r-step-input')))
          .controller
          ?.text,
      '0',
    );

    final decreaseQ = find.byKey(
      const ValueKey('settings-Q step-input-decrease'),
    );
    final increaseQ = find.byKey(
      const ValueKey('settings-Q step-input-increase'),
    );
    await tester.ensureVisible(decreaseQ);
    await tester.pumpAndSettle();
    await tester.tap(decreaseQ);
    await tester.pump();
    expect(settings.hexStepQ, -1);
    await tester.tap(increaseQ);
    await tester.pump();
    expect(settings.hexStepQ, 0);
  });
}
