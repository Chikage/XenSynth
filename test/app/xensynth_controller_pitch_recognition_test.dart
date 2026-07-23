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
      'message': 'Listening for piano notes',
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

    final started = await controller.startPitchRecognition(
      downloadIfNeeded: true,
    );

    expect(started, isTrue);
    expect(controller.pitchRecognizing, isTrue);
    expect(controller.pitchRecognitionModelReady, isTrue);
    final startCall = calls.singleWhere(
      (call) => call.method == 'startPitchRecognition',
    );
    expect(
      Map<Object?, Object?>.from(
        startCall.arguments! as Map,
      )['downloadIfNeeded'],
      isTrue,
    );
    expect(
      Map<Object?, Object?>.from(startCall.arguments! as Map)['mode'],
      'yin',
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

  test('starts YIN mode without requesting a model download', () async {
    final controller = XenSynthController()
      ..pitchRecognitionAvailable = true
      ..settings = const XenSynthSettings(
        pitchRecognitionMode: PitchRecognitionMode.yin,
      );

    final started = await controller.startPitchRecognition();

    expect(started, isTrue);
    final startCall = calls.singleWhere(
      (call) => call.method == 'startPitchRecognition',
    );
    final arguments = Map<Object?, Object?>.from(startCall.arguments! as Map);
    expect(arguments['mode'], 'yin');
    expect(arguments['downloadIfNeeded'], isFalse);

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
    expect(saveArguments['suggestedName'], startsWith('XenSynth_yin_'));
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

  test('accepts FFT frames and keeps FFT on the linear ruler', () async {
    stopDuration = 0.4;
    startState = <String, Object?>{
      'supported': true,
      'phase': 'listening',
      'modelReady': false,
      'recognizing': true,
      'busy': false,
      'progress': 0.0,
      'message': 'Listening for FFT spectrum',
    };
    final controller = XenSynthController()
      ..pitchRecognitionAvailable = true
      ..settings = const XenSynthSettings(
        pitchRecognitionMode: PitchRecognitionMode.fft,
      );
    await controller.initialize();

    expect(await controller.startPitchRecognition(), isTrue);
    final startCall = calls.singleWhere(
      (call) => call.method == 'startPitchRecognition',
    );
    expect(
      Map<Object?, Object?>.from(startCall.arguments! as Map)['mode'],
      'fft',
    );

    await messenger.handlePlatformMessage(
      midiChannelName,
      codec.encodeSuccessEnvelope(<String, Object?>{
        'type': 'spectrum',
        'source': 'microphone',
        'mode': 'fft',
        'time': 0.25,
        'magnitudes': Float32List.fromList(<double>[0.1, 0.8, 0.2]),
      }),
      (_) {},
    );
    await pumpEventQueue();

    expect(controller.settings.layoutMode, KeyboardLayoutMode.linear);
    expect(controller.showingFftSpectrum, isTrue);
    expect(controller.spectrumFrames, hasLength(1));
    expect(controller.spectrumFrames.single.magnitudes[1], closeTo(0.8, 0.001));

    await controller.stopPitchRecognition();
    controller.dispose();
    await pumpEventQueue();
  });

  test('reports that a model download is required before starting', () async {
    startState = <String, Object?>{
      'supported': true,
      'phase': 'needsDownload',
      'modelReady': false,
      'recognizing': false,
      'busy': false,
      'progress': 0.0,
      'message': 'Pitch recognition model is not downloaded',
    };
    final controller = XenSynthController()..pitchRecognitionAvailable = true;

    final started = await controller.startPitchRecognition();

    expect(started, isFalse);
    expect(controller.pitchRecognitionPhase, 'needsDownload');
    expect(controller.pitchRecognitionModelReady, isFalse);

    controller.dispose();
    await pumpEventQueue();
  });

  test(
    'quantizes stable YIN events and preserves free microtonal pitch',
    () async {
      final controller = XenSynthController()
        ..settings = const XenSynthSettings(
          edo: 19,
          pitchRecognitionMode: PitchRecognitionMode.yin,
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
        pitchRecognitionMode: PitchRecognitionMode.yin,
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
