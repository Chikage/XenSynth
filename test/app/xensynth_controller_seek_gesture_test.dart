import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';
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
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('seek gesture suspends and resumes active playback', () async {
    final controller = XenSynthController()
      ..score = _score()
      ..playhead = 1;

    await controller.play();
    calls.clear();

    controller.beginSeekGesture();
    controller.updateSeekGesture(4);
    await controller.endSeekGesture();

    expect(calls.map((call) => call.method), <String>['pause', 'seek', 'play']);
    final seekArguments = Map<Object?, Object?>.from(
      calls[1].arguments! as Map,
    );
    final playArguments = Map<Object?, Object?>.from(
      calls[2].arguments! as Map,
    );
    expect(seekArguments['position'], 4);
    expect(playArguments['from'], 4);
    expect(controller.playing, isTrue);

    controller.dispose();
    await pumpEventQueue();
  });
}

ParsedScore _score() {
  return const ParsedScore(
    title: 'Seek gesture test',
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
}
