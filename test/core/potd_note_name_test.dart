import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/core/potd_note_name.dart';

void main() {
  test('uses conventional names and octaves in 12 EDO', () {
    expect(potdNoteNameForPitch(pitch: 60, edo: 12), 'C4');
    expect(potdNoteNameForPitch(pitch: 69, edo: 12), 'A4');
  });

  test('uses POTD accidentals for non-12 EDO steps', () {
    final firstStepAboveC4 = 60 + 12 / 26;

    expect(potdNoteNameForPitch(pitch: firstStepAboveC4, edo: 26), '#C4');
  });
}
