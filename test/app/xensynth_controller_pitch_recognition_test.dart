import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';
import 'package:xensynth/app/xensynth_settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('icu.ringona.xensynth/platform');
  const midiChannelName = 'icu.ringona.xensynth/platform/midi';
  const midiChannel = MethodChannel(midiChannelName);
  const codec = StandardMethodCodec();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;
  late Map<String, Object?> startState;
  late double stopDuration;

  setUp(() {
    calls = <MethodCall>[];
    stopDuration = 0;
    startState = <String, Object?>{
      'supported': true,
      'phase': 'listening',
      'modelReady': true,
      'recognizing': true,
      'busy': false,
      'progress': 1.0,
      'message': 'Listening with local FFT and YIN fusion',
    };
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'noteOn' => calls.length,
        'startPitchRecognition' => startState,
        'stopPitchRecognition' => <String, Object?>{
          'supported': true,
          'phase': 'idle',
          'modelReady': true,
          'recognizing': false,
          'busy': false,
          'progress': 1.0,
          'message': '',
          'recordingDuration': stopDuration,
        },
        'savePitchRecording' => <String, Object?>{
          'saved': true,
          'directory': 'Music/XenSynth',
          'recordingName': 'take_recording.wav',
          'recognizedName': 'take_recognized.wav',
        },
        _ => true,
      };
    });
    messenger.setMockMethodCallHandler(midiChannel, (_) async => null);
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(midiChannel, null);
  });

  test('starts and stops the native microphone recognizer', () async {
    final controller = XenSynthController()..pitchRecognitionAvailable = true;

    final started = await controller.startPitchRecognition();

    expect(started, isTrue);
    expect(controller.pitchRecognizing, isTrue);
    final startCall = calls.singleWhere(
      (call) => call.method == 'startPitchRecognition',
    );
    expect(
      Map<Object?, Object?>.from(startCall.arguments! as Map),
      isNot(contains('downloadIfNeeded')),
    );
    expect(
      Map<Object?, Object?>.from(startCall.arguments! as Map)['mode'],
      'hybrid',
    );
    final sensitivityCall = calls.singleWhere(
      (call) => call.method == 'setPitchRecognitionSensitivity',
    );
    expect(
      Map<Object?, Object?>.from(
        sensitivityCall.arguments! as Map,
      )['sensitivity'],
      1,
    );

    await controller.stopPitchRecognition();

    expect(controller.pitchRecognizing, isFalse);
    expect(controller.pitchRecognitionPhase, 'idle');
    expect(
      calls.where((call) => call.method == 'stopPitchRecognition'),
      hasLength(1),
    );

    controller.dispose();
    await pumpEventQueue();
  });

  test('starts the local hybrid recognizer without a model download', () async {
    final controller = XenSynthController()
      ..pitchRecognitionAvailable = true
      ..settings = const XenSynthSettings(
        pitchRecognitionMode: PitchRecognitionMode.hybrid,
      );

    final started = await controller.startPitchRecognition();

    expect(started, isTrue);
    final startCall = calls.singleWhere(
      (call) => call.method == 'startPitchRecognition',
    );
    final arguments = Map<Object?, Object?>.from(startCall.arguments! as Map);
    expect(arguments['mode'], 'hybrid');
    expect(arguments, isNot(contains('downloadIfNeeded')));

    controller.dispose();
    await pumpEventQueue();
  });

  test('unifies touch and external MIDI as live pitch input events', () async {
    final controller = XenSynthController();
    await controller.initialize();

    controller.noteDown(7, 60.25, 73);
    expect(controller.activePitchVelocities[7], 73);
    controller.noteMove(7, 60.25, 41);
    expect(controller.activePitchVelocities[7], 41);
    controller.noteUp(7);
    expect(controller.activePitchVelocities, isEmpty);
    expect(controller.pitchInputEvents, hasLength(3));
    expect(controller.pitchInputEvents.first.pointer, 7);
    expect(controller.pitchInputEvents.first.down, isTrue);
    expect(controller.pitchInputEvents.first.velocity, 73);
    expect(controller.pitchInputEvents.last.down, isFalse);

    Future<void> emit(Map<String, Object?> payload) async {
      await messenger.handlePlatformMessage(
        midiChannelName,
        codec.encodeSuccessEnvelope(payload),
        (_) {},
      );
      await pumpEventQueue();
    }

    await emit(<String, Object?>{
      'type': 'noteOn',
      'channel': 2,
      'pitch': 64,
      'velocity': 101,
    });
    expect(controller.activePitchVelocities.values.single, 101);
    await emit(<String, Object?>{'type': 'noteOff', 'channel': 2, 'pitch': 64});
    expect(controller.activePitchVelocities, isEmpty);

    final midiEvents = controller.pitchInputEvents.skip(3).toList();
    expect(midiEvents, hasLength(2));
    expect(midiEvents.first.down, isTrue);
    expect(midiEvents.first.velocity, 101);
    expect(midiEvents.last.down, isFalse);
    expect(midiEvents.first.pointer, midiEvents.last.pointer);

    controller.dispose();
    await pumpEventQueue();
  });

  test('does not echo received network MIDI back to network outputs', () async {
    final controller = XenSynthController();
    await controller.initialize();
    calls.clear();

    Future<void> emit(Map<String, Object?> payload) async {
      await messenger.handlePlatformMessage(
        midiChannelName,
        codec.encodeSuccessEnvelope(payload),
        (_) {},
      );
      await pumpEventQueue();
    }

    await emit(<String, Object?>{
      'type': 'noteOn',
      'source': 'network',
      'channel': 0,
      'pitch': 60,
      'velocity': 100,
      'targetTimeNanos': 1234567890,
    });
    final noteOn = calls.lastWhere((call) => call.method == 'noteOn');
    final noteOnArguments = Map<Object?, Object?>.from(
      noteOn.arguments! as Map,
    );
    expect(noteOnArguments['networkOutput'], isFalse);
    expect(noteOnArguments['audioTargetTimeNanos'], 1234567890);

    await emit(<String, Object?>{
      'type': 'noteOff',
      'source': 'network',
      'channel': 0,
      'pitch': 60,
      'targetTimeNanos': 1235567890,
    });
    final noteOff = calls.lastWhere((call) => call.method == 'noteOff');
    expect(
      Map<Object?, Object?>.from(
        noteOff.arguments! as Map,
      )['audioTargetTimeNanos'],
      1235567890,
    );
    await emit(<String, Object?>{
      'type': 'allNotesOff',
      'source': 'network',
      'channel': 0,
      'targetTimeNanos': 1236567890,
    });
    final allNotesOff = calls.lastWhere((call) => call.method == 'allNotesOff');
    expect(
      Map<Object?, Object?>.from(
        allNotesOff.arguments! as Map,
      )['networkOutput'],
      isFalse,
    );
    expect(
      Map<Object?, Object?>.from(
        allNotesOff.arguments! as Map,
      )['audioTargetTimeNanos'],
      1236567890,
    );

    controller.dispose();
    await pumpEventQueue();
  });

  test('keeps a pending fallback note-off on its network deadline', () async {
    final noteOnResult = Completer<int?>();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'noteOn') return noteOnResult.future;
      return true;
    });
    final controller = XenSynthController();
    await controller.initialize();
    calls.clear();

    controller.noteDown(
      7,
      60,
      100,
      networkOutput: false,
      audioTargetTimeNanos: 2_000_000_000,
    );
    await pumpEventQueue();
    controller.noteUp(7, audioTargetTimeNanos: 2_004_000_000);
    noteOnResult.complete(73);
    await pumpEventQueue();

    final noteOff = calls.singleWhere((call) => call.method == 'noteOff');
    final arguments = Map<Object?, Object?>.from(noteOff.arguments! as Map);
    expect(arguments['token'], 73);
    expect(arguments['audioTargetTimeNanos'], 2_004_000_000);

    controller.dispose();
    await pumpEventQueue();
  });

  test(
    'uses the pedal-up target when releasing sustained network notes',
    () async {
      final controller = XenSynthController();
      await controller.initialize();
      calls.clear();

      Future<void> emit(Map<String, Object?> payload) async {
        await messenger.handlePlatformMessage(
          midiChannelName,
          codec.encodeSuccessEnvelope(payload),
          (_) {},
        );
        await pumpEventQueue();
      }

      await emit(<String, Object?>{
        'type': 'noteOn',
        'source': 'network',
        'channel': 2,
        'pitch': 64,
        'velocity': 96,
        'targetTimeNanos': 2000000000,
      });
      await emit(<String, Object?>{
        'type': 'sustain',
        'source': 'network',
        'channel': 2,
        'down': true,
        'targetTimeNanos': 2001000000,
      });
      await emit(<String, Object?>{
        'type': 'noteOff',
        'source': 'network',
        'channel': 2,
        'pitch': 64,
        'targetTimeNanos': 2002000000,
      });
      expect(calls.where((call) => call.method == 'noteOff'), isEmpty);

      await emit(<String, Object?>{
        'type': 'sustain',
        'source': 'network',
        'channel': 2,
        'down': false,
        'targetTimeNanos': 2008000000,
      });

      final noteOff = calls.singleWhere((call) => call.method == 'noteOff');
      expect(
        Map<Object?, Object?>.from(
          noteOff.arguments! as Map,
        )['audioTargetTimeNanos'],
        2008000000,
      );

      controller.dispose();
      await pumpEventQueue();
    },
  );

  test(
    'keeps native-scheduled network audio out of the Flutter loop',
    () async {
      final controller = XenSynthController();
      await controller.initialize();
      calls.clear();

      Future<void> emit(Map<String, Object?> payload) async {
        await messenger.handlePlatformMessage(
          midiChannelName,
          codec.encodeSuccessEnvelope(payload),
          (_) {},
        );
        await pumpEventQueue();
      }

      await emit(<String, Object?>{
        'type': 'noteOn',
        'source': 'network',
        'nativeAudioHandled': true,
        'channel': 1,
        'pitch': 65,
        'velocity': 99,
        'targetTimeNanos': 3000000000,
      });
      expect(calls.where((call) => call.method == 'noteOn'), isEmpty);
      expect(controller.activePitches, isNotEmpty);

      await emit(<String, Object?>{
        'type': 'noteOff',
        'source': 'network',
        'nativeAudioHandled': true,
        'channel': 1,
        'pitch': 65,
        'targetTimeNanos': 3005000000,
      });
      expect(calls.where((call) => call.method == 'noteOff'), isEmpty);

      await emit(<String, Object?>{
        'type': 'allNotesOff',
        'source': 'network',
        'nativeAudioHandled': true,
        'channel': 1,
        'targetTimeNanos': 3010000000,
      });
      expect(calls.where((call) => call.method == 'allNotesOff'), isEmpty);
      expect(controller.activePitches, isEmpty);

      controller.dispose();
      await pumpEventQueue();
    },
  );

  test('records microphone note timing and replays captured audio', () async {
    stopDuration = 0.8;
    final controller = XenSynthController()..pitchRecognitionAvailable = true;
    await controller.initialize();

    expect(await controller.startPitchRecognition(), isTrue);
    expect(controller.recordingTransportLocked, isTrue);
    expect(controller.score?.notes, isEmpty);

    Future<void> emit(Map<String, Object?> payload) async {
      await messenger.handlePlatformMessage(
        midiChannelName,
        codec.encodeSuccessEnvelope(payload),
        (_) {},
      );
      await pumpEventQueue();
    }

    await emit(<String, Object?>{
      'type': 'noteOn',
      'source': 'microphone',
      'pitch': 69,
      'velocity': 91,
      'time': 0.1,
    });
    await emit(<String, Object?>{
      'type': 'noteOff',
      'source': 'microphone',
      'pitch': 69,
      'velocity': 0,
      'time': 0.55,
    });
    await controller.stopPitchRecognition();

    expect(controller.recordingTransportLocked, isFalse);
    expect(controller.duration, closeTo(0.8, 0.000001));
    expect(controller.score?.notes, hasLength(1));
    expect(controller.score!.notes.single.start, closeTo(0.1, 0.000001));
    expect(controller.score!.notes.single.end, closeTo(0.55, 0.000001));
    expect(controller.microphoneTakeReadyForSave, isTrue);
    expect(controller.microphoneTakeNeedsSaving, isTrue);
    expect(
      controller.pitchInputEvents.last.pitch,
      closeTo(controller.score!.notes.single.pitch, 0.000001),
    );
    expect(controller.pitchInputEvents.first.down, isTrue);
    expect(controller.pitchInputEvents.first.velocity, 91);
    expect(controller.pitchInputEvents.last.down, isFalse);

    await controller.play();
    expect(
      calls.where((call) => call.method == 'playPitchRecording'),
      hasLength(1),
    );
    await controller.pause();

    expect(await controller.saveMicrophoneTake(), isTrue);
    expect(controller.microphoneTakeNeedsSaving, isFalse);
    final saveCall = calls.singleWhere(
      (call) => call.method == 'savePitchRecording',
    );
    final saveArguments = Map<Object?, Object?>.from(
      saveCall.arguments! as Map,
    );
    expect(saveArguments['duration'], closeTo(0.8, 0.000001));
    expect(saveArguments['suggestedName'], startsWith('XenSynth_hybrid_'));
    final savedNotes = saveArguments['notes']! as List<Object?>;
    expect(savedNotes, hasLength(1));
    final savedNote = Map<Object?, Object?>.from(savedNotes.single! as Map);
    expect(savedNote['start'], closeTo(0.1, 0.000001));
    expect(savedNote['end'], closeTo(0.55, 0.000001));
    expect(
      savedNote['pitch'],
      closeTo(controller.score!.notes.single.pitch, 0.000001),
    );

    controller.dispose();
    await pumpEventQueue();
  });

  test('accepts FFT peak frames in the hybrid microphone take', () async {
    stopDuration = 0.4;
    startState = <String, Object?>{
      'supported': true,
      'phase': 'listening',
      'modelReady': false,
      'recognizing': true,
      'busy': false,
      'progress': 0.0,
      'message': 'Listening with local FFT and YIN fusion',
    };
    final controller = XenSynthController()
      ..pitchRecognitionAvailable = true
      ..settings = const XenSynthSettings(
        pitchRecognitionMode: PitchRecognitionMode.hybrid,
      );
    await controller.initialize();

    expect(await controller.startPitchRecognition(), isTrue);
    final startCall = calls.singleWhere(
      (call) => call.method == 'startPitchRecognition',
    );
    expect(
      Map<Object?, Object?>.from(startCall.arguments! as Map)['mode'],
      'hybrid',
    );

    await messenger.handlePlatformMessage(
      midiChannelName,
      codec.encodeSuccessEnvelope(<String, Object?>{
        'type': 'spectrum',
        'source': 'microphone',
        'mode': 'hybrid',
        'time': 0.25,
        'magnitudes': Float32List.fromList(<double>[0.1, 0.8, 0.2]),
        'peaks': <Map<String, double>>[
          <String, double>{'pitch': 69.1, 'magnitude': 0.8},
        ],
      }),
      (_) {},
    );
    await pumpEventQueue();

    expect(controller.settings.layoutMode, KeyboardLayoutMode.linear);
    expect(controller.showingFftSpectrum, isTrue);
    expect(controller.spectrumFrames, hasLength(1));
    expect(controller.spectrumFrames.single.magnitudes[1], closeTo(0.8, 0.001));
    expect(
      controller.spectrumFrames.single.peaks.single.pitch,
      closeTo(69.1, 0.001),
    );

    await controller.stopPitchRecognition();
    controller.dispose();
    await pumpEventQueue();
  });

  test(
    'quantizes stable YIN events and preserves free microtonal pitch',
    () async {
      final controller = XenSynthController()
        ..settings = const XenSynthSettings(
          edo: 19,
          pitchRecognitionMode: PitchRecognitionMode.hybrid,
        );
      await controller.initialize();

      Future<void> emit(Map<String, Object?> payload) async {
        await messenger.handlePlatformMessage(
          midiChannelName,
          codec.encodeSuccessEnvelope(<String, Object?>{
            'type': 'pitch',
            'source': 'microphone',
            'mode': 'yin',
            'voiced': true,
            'frequencyHz': 449.51,
            'pitch': 69.37,
            'confidence': 0.97,
            'velocity': 88,
            ...payload,
          }),
          (_) {},
        );
        await pumpEventQueue();
      }

      await emit(const {});
      expect(controller.activePitches, isEmpty);

      await emit(const {});
      final quantized = 60 + ((69.37 - 60) * 19 / 12).round() * 12 / 19;
      expect(
        controller.activePitches.values.single,
        closeTo(quantized, 0.000001),
      );
      expect(controller.pitchRecognitionFrequencyHz, closeTo(449.51, 0.001));
      expect(controller.pitchRecognitionDetectedPitch, closeTo(69.37, 0.001));
      expect(controller.pitchRecognitionConfidence, closeTo(0.97, 0.001));
      expect(controller.activePitchVelocities.values.single, 88);

      await emit(const {'velocity': 31});
      expect(controller.activePitchVelocities.values.single, 31);

      await emit(const {'voiced': false});
      expect(controller.activePitches, isEmpty);
      expect(controller.activePitchVelocities, isEmpty);
      expect(controller.pitchRecognitionFrequencyHz, isNull);

      controller.settings = const XenSynthSettings(
        edo: 0,
        pitchRecognitionMode: PitchRecognitionMode.hybrid,
      );
      await emit(const {'pitch': 69.37});
      expect(controller.activePitches.values.single, closeTo(69.37, 0.000001));

      await emit(const {'pitch': 69.40});
      expect(controller.activePitches.values.single, closeTo(69.37, 0.000001));

      await emit(const {'pitch': 69.44});
      expect(controller.activePitches.values.single, closeTo(69.44, 0.000001));

      controller.dispose();
      await pumpEventQueue();
    },
  );
}
