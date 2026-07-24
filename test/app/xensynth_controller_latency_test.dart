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

  test(
    'keeps the visual clock running until the final effects clear',
    () async {
      final controller = XenSynthController()
        ..settings = const XenSynthSettings(playbackSpeed: 2)
        ..score = _shortScoreWithFinalNote();
      final playbackEnded = Completer<void>();
      final waterfallAdvanced = Completer<void>();
      final waterfallEnded = Completer<void>();
      double? tailStart;
      controller.addListener(() {
        if (!controller.playing &&
            controller.waterfallAnimating &&
            controller.playhead == controller.duration) {
          tailStart ??= controller.visualPlayhead;
          if (!playbackEnded.isCompleted) playbackEnded.complete();
        }
        final start = tailStart;
        if (start != null && controller.visualPlayhead > start) {
          if (!waterfallAdvanced.isCompleted) waterfallAdvanced.complete();
        }
        if (!controller.waterfallAnimating &&
            controller.visualPlayhead > controller.duration) {
          if (!waterfallEnded.isCompleted) waterfallEnded.complete();
        }
      });

      await controller.play();
      await playbackEnded.future.timeout(const Duration(seconds: 1));

      await waterfallAdvanced.future.timeout(const Duration(seconds: 1));
      expect(controller.playhead, controller.duration);
      expect(controller.visualPlayhead, greaterThan(tailStart!));

      await waterfallEnded.future.timeout(const Duration(seconds: 2));
      expect(controller.playing, isFalse);
      expect(controller.visualPlayhead, greaterThan(controller.duration));

      controller.dispose();
      await pumpEventQueue();
    },
  );

  test('continues the waterfall when native score audio fails', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'play') {
        throw PlatformException(
          code: 'ios_audio_unavailable',
          message: 'Audio session activation failed',
        );
      }
      return true;
    });
    final controller = XenSynthController()
      ..audioReady = true
      ..score = _shortScoreWithFinalNote();

    await controller.play();

    expect(controller.playing, isTrue);
    expect(controller.waterfallAnimating, isTrue);
    expect(controller.audioReady, isFalse);
    expect(controller.status, 'VISUAL MODE · AUDIO UNAVAILABLE');
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(controller.visualPlayhead, greaterThan(0));

    controller.dispose();
    await pumpEventQueue();
  });
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

ParsedScore _shortScoreWithFinalNote() {
  const note = WaterfallNote(
    startTick: 0,
    endTick: 20,
    start: 0,
    end: 0.02,
    pitch: 60,
    midiPitch: 60,
    cents: 0,
    velocity: 96,
    channel: 0,
    track: 0,
    program: 0,
    bankMsb: 0,
    bankLsb: 0,
  );
  return const ParsedScore(
    title: 'Waterfall tail test',
    format: 'TEST',
    ticksPerQuarter: 480,
    tempos: <TempoEvent>[],
    meters: <MeterEvent>[],
    tempoMap: <TempoPoint>[],
    rawEvents: <RawNoteEvent>[],
    notes: <WaterfallNote>[note],
    longNotes: <WaterfallNote>[],
    duration: 0.02,
  );
}
