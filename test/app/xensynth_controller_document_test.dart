import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('icu.ringona.xensynth/platform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;
  Object? pickedDocument;

  setUp(() {
    calls = <MethodCall>[];
    pickedDocument = null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'pickDocument') return pickedDocument;
      return true;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('open document imports tuning JSON through the shared picker', () async {
    pickedDocument = <String, Object?>{
      'name': 'just-intonation.json',
      'bytes': Uint8List.fromList(
        utf8.encode('''
          {
            "profile": "JUST",
            "offset": 30,
            "Scale": {"203.91": 0.7, "701.96": 0.9}
          }
        '''),
      ),
    };
    final controller = XenSynthController();

    await controller.openDocument();

    final picker = calls.singleWhere((call) => call.method == 'pickDocument');
    final arguments = Map<Object?, Object?>.from(picker.arguments! as Map);
    expect(arguments['kind'], 'scoreOrTuning');
    expect(
      arguments['extensions'],
      containsAll(<String>['midx', 'mscz', 'json']),
    );
    expect(calls.where((call) => call.method == 'loadScore'), isEmpty);
    expect(controller.customTuningActive, isTrue);
    expect(controller.tuning.profile, 'JUST');
    expect(controller.settings.pitchOffsetCents, 30);
    expect(controller.status, 'TUNING · JUST');

    controller.dispose();
    await pumpEventQueue();
  });

  test('open document sends score files to the score loader', () async {
    pickedDocument = <String, Object?>{
      'name': 'fixture.midx',
      'bytes': _midxFixture(),
    };
    final controller = XenSynthController();

    await controller.openDocument();

    expect(calls.where((call) => call.method == 'loadScore'), hasLength(1));
    expect(controller.customTuningActive, isFalse);
    expect(controller.score?.title, 'fixture.midx');
    expect(controller.status, 'MIDX · 1 NOTES');

    controller.dispose();
    await pumpEventQueue();
  });
}

Uint8List _midxFixture() {
  final track = <int>[
    0x00,
    0xff,
    0x51,
    0x03,
    0x07,
    0xa1,
    0x20,
    0x00,
    0x90,
    0x3c,
    0x64,
    0x83,
    0x60,
    0x3c,
    0x00,
    0x00,
    0xff,
    0x2f,
    0x00,
  ];
  return Uint8List.fromList([
    ...'MThd'.codeUnits,
    0x00,
    0x00,
    0x00,
    0x06,
    0x00,
    0x00,
    0x00,
    0x01,
    0x01,
    0xe0,
    ...'MTrk'.codeUnits,
    ..._u32(track.length),
    ...track,
  ]);
}

List<int> _u32(int value) => [
  (value >> 24) & 0xff,
  (value >> 16) & 0xff,
  (value >> 8) & 0xff,
  value & 0xff,
];
