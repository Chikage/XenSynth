import 'dart:async';

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
      return true;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
    'does not stop latency-delayed audio at the logical score end',
    () async {
      final controller = XenSynthController()
        ..settings = const XenSynthSettings(audioLatencyMs: 700)
        ..score = _shortScore();
      final playbackEnded = Completer<void>();
      controller.addListener(() {
        if (!controller.playing && controller.playhead == controller.duration) {
          if (!playbackEnded.isCompleted) playbackEnded.complete();
        }
      });

      await controller.play();
      await playbackEnded.future.timeout(const Duration(seconds: 1));

      final play = calls.singleWhere((call) => call.method == 'play');
      final arguments = Map<Object?, Object?>.from(play.arguments! as Map);
      expect(arguments['audioStartDelaySeconds'], 0.7);
      expect(calls.where((call) => call.method == 'stop'), isEmpty);

      controller.dispose();
      await pumpEventQueue();
    },
  );
}

ParsedScore _shortScore() {
  return const ParsedScore(
    title: 'Latency test',
    format: 'TEST',
    ticksPerQuarter: 480,
    tempos: <TempoEvent>[],
    meters: <MeterEvent>[],
    tempoMap: <TempoPoint>[],
    rawEvents: <RawNoteEvent>[],
    notes: <WaterfallNote>[],
    longNotes: <WaterfallNote>[],
    duration: 0.02,
  );
}
