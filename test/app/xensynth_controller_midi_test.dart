import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/platform/native_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('icu.ringona.xensynth/platform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
    'device refresh preserves duplex selection for one network peer',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return switch (call.method) {
          'getMidiInputDevices' => <Map<String, Object?>>[
            <String, Object?>{
              'id': 'applemidi:input-service',
              'targetId': 'network:192.168.1.12:5004',
              'name': 'Input peer',
              'isInput': true,
            },
          ],
          'getMidiOutputDevices' => <Map<String, Object?>>[],
          'scanNetworkMidiOutputs' => <Map<String, Object?>>[
            <String, Object?>{
              'id': 'applemidi:output-service',
              'targetId': 'network:192.168.1.12:5005',
              'name': 'Output peer',
            },
          ],
          _ => true,
        };
      });
      final controller = XenSynthController()
        ..settings = const XenSynthSettings(
          midiInputDeviceIds: <String>['applemidi:input-service'],
          midiOutputDeviceIds: <String>['applemidi:output-service'],
        );

      await controller.refreshMidiDevices(notify: false);

      expect(controller.settings.midiInputDeviceIds, [
        'applemidi:input-service',
      ]);
      expect(controller.settings.midiOutputDeviceIds, [
        'applemidi:output-service',
      ]);
      expect(
        calls.where((call) => call.method == 'setMidiOutputDeviceIds'),
        isEmpty,
      );

      controller.dispose();
      await pumpEventQueue();
    },
  );

  test('disabled network MIDI refresh skips LAN discovery', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'getMidiInputDevices' => <Map<String, Object?>>[
          <String, Object?>{
            'id': 'android-midi-input:7:0',
            'name': 'USB Keyboard',
            'transport': 'usb',
          },
          <String, Object?>{
            'id': 'applemidi:ignored-peer',
            'name': 'Ignored LAN input',
            'transport': 'network',
          },
        ],
        'getMidiOutputDevices' => <Map<String, Object?>>[
          <String, Object?>{
            'id': 'android-midi-output:8:0',
            'name': 'Local Synth',
            'transport': 'software',
          },
        ],
        'scanNetworkMidiOutputs' => <Map<String, Object?>>[
          <String, Object?>{
            'id': 'applemidi:unexpected-peer',
            'name': 'Unexpected LAN output',
          },
        ],
        _ => true,
      };
    });
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(networkMidiEnabled: false)
      ..midiInputDevices = const <NativeMidiOutput>[
        NativeMidiOutput(
          id: 'android-midi-input:7:0',
          name: 'Cached USB Keyboard',
          transport: 'usb',
          isInput: true,
        ),
        NativeMidiOutput(
          id: 'applemidi-session:stale',
          name: 'Stale LAN input',
          transport: 'network',
          isInput: true,
        ),
      ]
      ..networkMidiOutputs = const <NativeMidiOutput>[
        NativeMidiOutput(
          id: 'applemidi:stale-output',
          name: 'Stale LAN output',
        ),
      ];

    await controller.refreshMidiDevices(notify: false);

    final inputCall = calls.singleWhere(
      (call) => call.method == 'getMidiInputDevices',
    );
    expect(
      (inputCall.arguments! as Map<Object?, Object?>)['includeNetwork'],
      isFalse,
    );
    expect(
      calls.where((call) => call.method == 'scanNetworkMidiOutputs'),
      isEmpty,
    );
    expect(controller.midiInputDevices.map((device) => device.id), [
      'android-midi-input:7:0',
    ]);
    expect(controller.networkMidiOutputs, isEmpty);
    expect(controller.midiOutputDevices.map((device) => device.id), [
      'android-midi-output:8:0',
    ]);

    controller.dispose();
    await pumpEventQueue();
  });

  test('network MIDI toggle clears stale peers before a fresh scan', () async {
    final calls = <MethodCall>[];
    final inputScan = Completer<Object?>();
    final outputScan = Completer<Object?>();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'getMidiInputDevices') {
        return await inputScan.future;
      }
      if (call.method == 'scanNetworkMidiOutputs') {
        return await outputScan.future;
      }
      return true;
    });
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(networkMidiEnabled: true)
      ..midiInputDevices = const <NativeMidiOutput>[
        NativeMidiOutput(
          id: 'android-midi-input:7:0',
          name: 'USB Keyboard',
          transport: 'usb',
          isInput: true,
        ),
        NativeMidiOutput(
          id: 'applemidi:stale-input',
          name: 'Stale LAN input',
          transport: 'network',
          isInput: true,
        ),
      ]
      ..networkMidiOutputs = const <NativeMidiOutput>[
        NativeMidiOutput(
          id: 'applemidi:stale-output',
          name: 'Stale LAN output',
        ),
      ];

    await controller.updateSettings(
      controller.settings.copyWith(networkMidiEnabled: false),
    );

    expect(controller.midiInputDevices.map((device) => device.id), [
      'android-midi-input:7:0',
    ]);
    expect(controller.networkMidiOutputs, isEmpty);
    expect(
      calls.where((call) => call.method == 'scanNetworkMidiOutputs'),
      isEmpty,
    );

    final enabling = controller.updateSettings(
      controller.settings.copyWith(networkMidiEnabled: true),
    );
    expect(controller.midiInputDevices.map((device) => device.id), [
      'android-midi-input:7:0',
    ]);
    expect(controller.networkMidiOutputs, isEmpty);

    await pumpEventQueue();
    expect(
      calls.where((call) => call.method == 'scanNetworkMidiOutputs'),
      hasLength(1),
    );
    final inputCall = calls.lastWhere(
      (call) => call.method == 'getMidiInputDevices',
    );
    expect(
      (inputCall.arguments! as Map<Object?, Object?>)['includeNetwork'],
      isTrue,
    );

    inputScan.complete(<Map<String, Object?>>[
      <String, Object?>{
        'id': 'android-midi-input:7:0',
        'name': 'USB Keyboard',
        'transport': 'usb',
      },
      <String, Object?>{
        'id': 'applemidi:fresh-input',
        'name': 'Fresh LAN input',
        'transport': 'network',
      },
    ]);
    outputScan.complete(<Map<String, Object?>>[
      <String, Object?>{
        'id': 'applemidi:fresh-output',
        'name': 'Fresh LAN output',
      },
    ]);
    await enabling;

    expect(controller.midiInputDevices.map((device) => device.id), [
      'applemidi:fresh-input',
      'android-midi-input:7:0',
    ]);
    expect(controller.networkMidiOutputs.map((device) => device.id), [
      'applemidi:fresh-output',
    ]);
    expect(
      controller.midiInputDevices.map((device) => device.id),
      isNot(contains('applemidi:stale-input')),
    );

    controller.dispose();
    await pumpEventQueue();
  });

  test('disabling network MIDI discards an in-flight scan result', () async {
    final outputScan = Completer<Object?>();
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'scanNetworkMidiOutputs') {
        return await outputScan.future;
      }
      return true;
    });
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(networkMidiEnabled: true);

    final refresh = controller.refreshNetworkMidiOutputs(notify: false);
    await pumpEventQueue();
    expect(controller.networkMidiScanning, isTrue);

    await controller.updateSettings(
      controller.settings.copyWith(networkMidiEnabled: false),
    );
    expect(controller.networkMidiScanning, isFalse);
    expect(controller.networkMidiOutputs, isEmpty);

    outputScan.complete(<Map<String, Object?>>[
      <String, Object?>{'id': 'applemidi:late-peer', 'name': 'Late LAN output'},
    ]);
    await refresh;

    expect(controller.networkMidiOutputs, isEmpty);
    expect(controller.networkMidiScanning, isFalse);

    controller.dispose();
    await pumpEventQueue();
  });
}
