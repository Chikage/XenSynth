import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('icu.ringona.xensynth/platform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
    'failed microphone playback keeps live-input visuals suppressed',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        return switch (call.method) {
          'startPitchRecognition' => <String, Object?>{
            'supported': true,
            'phase': 'listening',
            'recognizing': true,
            'busy': false,
          },
          'stopPitchRecognition' => <String, Object?>{
            'supported': true,
            'phase': 'idle',
            'recognizing': false,
            'busy': false,
            'recordingDuration': 0.8,
          },
          'playPitchRecording' => false,
          'noteOn' => (call.arguments! as Map<Object?, Object?>)['id'],
          _ => true,
        };
      });
      final controller = XenSynthController()..pitchRecognitionAvailable = true;

      expect(await controller.startPitchRecognition(), isTrue);
      await controller.stopPitchRecognition();
      expect(controller.hasMicrophoneTake, isTrue);

      controller.noteDown(1, 60, 96);
      controller.noteUp(1);
      expect(controller.scoreVisualizationSuppressed, isTrue);

      await controller.play();

      expect(controller.playing, isFalse);
      expect(controller.waterfallAnimating, isFalse);
      expect(controller.scoreVisualizationSuppressed, isTrue);

      controller.dispose();
      await pumpEventQueue();
    },
  );
}
