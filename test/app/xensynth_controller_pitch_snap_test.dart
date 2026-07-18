import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/core/hex_keyboard.dart';
import 'package:xensynth/core/score.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('snaps live and serialized score pitch to the highlighted hex key', () {
    const originalPitch = 60.2;
    const settings = XenSynthSettings(
      layoutMode: KeyboardLayoutMode.hexagonal,
      edo: 19,
      pitchSnapEnabled: true,
    );
    final layout = HexaKeyboardLayoutEngine.build(
      settings.hexKeyboardConfiguration,
    );
    final expectedPitch = layout.snapPitch(originalPitch);
    expect(expectedPitch, isNot(closeTo(originalPitch, 0.000001)));

    final nativeScore = _scoreWithPitch(
      originalPitch,
    ).toNativeMap(pitchMapper: layout.snapPitch);
    final notes = (nativeScore['notes']! as List).cast<Map>();
    expect(notes.single['pitch'], closeTo(expectedPitch, 0.000001));
    final expectedMidiPitch = expectedPitch.round();
    expect(notes.single['midiPitch'], expectedMidiPitch);
    expect(
      notes.single['cents'],
      closeTo((expectedPitch - expectedMidiPitch) * 100, 0.000001),
    );

    final controller = XenSynthController()..settings = settings;
    controller.noteDown(7, originalPitch, 96);
    expect(controller.activePitches[7], closeTo(expectedPitch, 0.000001));
    controller.dispose();
  });

  test('keeps the original score pitch when snapping is disabled', () {
    const settings = XenSynthSettings(
      layoutMode: KeyboardLayoutMode.hexagonal,
      edo: 19,
      pitchSnapEnabled: false,
    );
    const originalPitch = 60.2;

    expect(settings.shouldSnapPlaybackPitch, isFalse);
    final nativeScore = _scoreWithPitch(originalPitch).toNativeMap();
    final notes = (nativeScore['notes']! as List).cast<Map>();
    expect(notes.single['pitch'], closeTo(originalPitch, 0.000001));
  });
}

ParsedScore _scoreWithPitch(double pitch) {
  return ParsedScore(
    title: 'Pitch snap test',
    format: 'TEST',
    ticksPerQuarter: 480,
    tempos: const <TempoEvent>[],
    meters: const <MeterEvent>[],
    tempoMap: const <TempoPoint>[],
    rawEvents: const <RawNoteEvent>[],
    notes: <WaterfallNote>[
      WaterfallNote(
        startTick: 0,
        endTick: 480,
        start: 0,
        end: 1,
        pitch: pitch,
        midiPitch: pitch.round(),
        cents: (pitch - pitch.round()) * 100,
        velocity: 96,
        channel: 0,
        track: 0,
        program: 0,
        bankMsb: 0,
        bankLsb: 0,
      ),
    ],
    longNotes: const <WaterfallNote>[],
    duration: 1,
  );
}
