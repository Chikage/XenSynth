import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/core/score.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('icu.ringona.xensynth/platform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;

  setUp(() {
    calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return call.method == 'noteOn' ? 1 : null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('uses the reversed offset for live notes and playback', () async {
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(pitchOffsetCents: 3)
      ..score = const ParsedScore(
        title: 'Offset test',
        format: 'TEST',
        ticksPerQuarter: 480,
        tempos: <TempoEvent>[],
        meters: <MeterEvent>[],
        tempoMap: <TempoPoint>[],
        rawEvents: <RawNoteEvent>[],
        notes: <WaterfallNote>[],
        longNotes: <WaterfallNote>[],
        duration: 10,
      );

    controller.noteDown(7, 60, 96);
    await pumpEventQueue();

    final noteOn = calls.firstWhere((call) => call.method == 'noteOn');
    final noteArguments = Map<Object?, Object?>.from(noteOn.arguments! as Map);
    expect(noteArguments['pitch'], closeTo(59.97, 0.000001));

    calls.clear();
    await controller.play();

    final initialPlay = calls.firstWhere((call) => call.method == 'play');
    final initialArguments = Map<Object?, Object?>.from(
      initialPlay.arguments! as Map,
    );
    expect(initialArguments['offsetCents'], -3);

    calls.clear();
    await controller.updateSettings(
      controller.settings.copyWith(pitchOffsetCents: 4),
    );

    final updatedPlay = calls.firstWhere((call) => call.method == 'play');
    final updatedArguments = Map<Object?, Object?>.from(
      updatedPlay.arguments! as Map,
    );
    expect(updatedArguments['offsetCents'], -4);

    controller.dispose();
    await pumpEventQueue();
  });
}
