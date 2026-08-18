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

  test('migrates Chordle 7n EDO spellings', () {
    const expected = <int, List<String>>{
      7: <String>['C4', 'D4', 'E4', 'F4', 'G4', 'A4', 'B4'],
      14: <String>[
        'C4',
        'vD4',
        'D4',
        'vE4',
        'E4',
        'vF4',
        'F4',
        'vG4',
        'G4',
        'vA4',
        'A4',
        'vB4',
        'B4',
        'vC4',
      ],
      21: <String>[
        'C4',
        '^C4',
        'vD4',
        'D4',
        '^D4',
        'vE4',
        'E4',
        '^E4',
        'vF4',
        'F4',
        '^F4',
        'vG4',
        'G4',
        '^G4',
        'vA4',
        'A4',
        '^A4',
        'vB4',
        'B4',
        '^B4',
        'vC4',
      ],
      28: <String>[
        'C4',
        '^C4',
        '^^C4',
        'vD4',
        'D4',
        '^D4',
        '^^D4',
        'vE4',
        'E4',
        '^E4',
        '^^E4',
        'vF4',
        'F4',
        '^F4',
        '^^F4',
        'vG4',
        'G4',
        '^G4',
        '^^G4',
        'vA4',
        'A4',
        '^A4',
        '^^A4',
        'vB4',
        'B4',
        '^B4',
        '^^B4',
        'vC4',
      ],
      35: <String>[
        'C4',
        '^C4',
        '^^C4',
        'vvD4',
        'vD4',
        'D4',
        '^D4',
        '^^D4',
        'vvE4',
        'vE4',
        'E4',
        '^E4',
        '^^E4',
        'vvF4',
        'vF4',
        'F4',
        '^F4',
        '^^F4',
        'vvG4',
        'vG4',
        'G4',
        '^G4',
        '^^G4',
        'vvA4',
        'vA4',
        'A4',
        '^A4',
        '^^A4',
        'vvB4',
        'vB4',
        'B4',
        '^B4',
        '^^B4',
        'vvC4',
        'vC4',
      ],
    };

    for (final entry in expected.entries) {
      final names = [
        for (var step = 0; step < entry.key; step += 1)
          potdNoteNameForPitch(
            pitch: (step + entry.key * 5) * 12 / entry.key,
            edo: entry.key,
          ),
      ];
      expect(names, entry.value, reason: '${entry.key} EDO');
    }
  });
}
