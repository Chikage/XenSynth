import 'dart:math' as math;

const _degreeNames = <String, String>{
  '1': 'C',
  '2': 'D',
  '3': 'E',
  '4': 'F',
  '5': 'G',
  '6': 'A',
  '7': 'B',
};

const _diatonicNames = <String>['C', 'D', 'E', 'F', 'G', 'A', 'B'];

// Chordle's 7n spellings divide each natural-note interval into n steps.
// The first half uses sharps on the lower degree; the remainder uses flats
// on the next degree.  At the 14-EDO midpoint the established spelling is
// the upper (flat) degree, while the 28-EDO midpoint stays on the lower one.
String? _sevenNedoName(int octaveStep, int edo) {
  if (edo % 7 != 0) return null;
  final subdivisions = edo ~/ 7;
  if (subdivisions < 1 || subdivisions > 5) return null;

  final degree = octaveStep ~/ subdivisions;
  final offset = octaveStep % subdivisions;
  final lowerName = _diatonicNames[degree];
  if (offset == 0) return lowerName;

  final sharpLimit = subdivisions == 2 ? 0 : subdivisions ~/ 2;
  if (offset <= sharpLimit) {
    return '${_repeat('^', offset)}$lowerName';
  }

  final upperName = _diatonicNames[(degree + 1) % _diatonicNames.length];
  return '${_repeat('v', subdivisions - offset)}$upperName';
}

String potdNoteNameForPitch({required double pitch, required int edo}) {
  final normalizedEdo = edo > 0 ? edo : 12;
  final step = (pitch * normalizedEdo / 12).round();
  final octave = _floorDiv(step, normalizedEdo) - 1;
  final octaveStep = _floorMod(step, normalizedEdo);
  final simpleName = _sevenNedoName(octaveStep, normalizedEdo);
  if (simpleName != null) {
    return '$simpleName$octave';
  }
  final numericName = _potdNumericName(octaveStep, normalizedEdo);
  final parsed = _parseNumericName(numericName);
  if (parsed == null) {
    return octaveStep == 0
        ? 'C$octave'
        : 'C$octave+$octaveStep\\$normalizedEdo';
  }
  return '${parsed.name}${octave + parsed.octaveOffset}';
}

({String name, int octaveOffset})? _parseNumericName(String? numericName) {
  if (numericName == null || numericName.isEmpty) return null;
  var octaveOffset = 0;
  var index = 0;
  while (index < numericName.length) {
    final marker = numericName[index];
    if (marker == "'") {
      octaveOffset += 1;
    } else if (marker.codeUnitAt(0) == 96) {
      octaveOffset -= 1;
    } else {
      break;
    }
    index += 1;
  }
  final body = numericName.substring(index);
  if (body.isEmpty) return null;
  final degree = body.substring(body.length - 1);
  final name = _degreeNames[degree];
  if (name == null) return null;
  return (
    name: '${body.substring(0, body.length - 1)}$name',
    octaveOffset: octaveOffset,
  );
}

String? _potdNumericName(int step, int edo) {
  if (edo <= 0) return null;
  final octave = edo;
  final third = _roundHalfToEven(edo * _log2(3));
  final harmonicSeven = _roundHalfToEven(edo * _log2(7));
  final diatonicStep = 2 * third - 3 * octave;
  final chromaticStep = 8 * octave - 5 * third;
  final sharpStep = 7 * third - 11 * octave;
  final maximumStep = math.max(diatonicStep, chromaticStep);

  late final int maximumAccidentalVariation;
  late final Map<String, int> naturalSteps;
  late final Map<String, int> nextSteps;
  late final Map<String, int> wrapsToRoot;
  if (chromaticStep > 0) {
    maximumAccidentalVariation = math.max(
      3 * octave - harmonicSeven - chromaticStep,
      _floorDiv(maximumStep, 2),
    );
    naturalSteps = <String, int>{
      '1': 0,
      '2': diatonicStep,
      '3': 2 * diatonicStep,
      '4': 2 * diatonicStep + chromaticStep,
      '5': 3 * diatonicStep + chromaticStep,
      '6': edo - diatonicStep - chromaticStep,
      '7': edo - chromaticStep,
    };
    nextSteps = <String, int>{
      '1': diatonicStep,
      '2': diatonicStep,
      '3': chromaticStep,
      '4': diatonicStep,
      '5': diatonicStep,
      '6': diatonicStep,
      '7': chromaticStep,
    };
    wrapsToRoot = <String, int>{
      '1': 0,
      '2': 0,
      '3': 0,
      '4': 0,
      '5': 0,
      '6': 0,
      '7': 1,
    };
  } else {
    maximumAccidentalVariation = math.max(
      3 * octave - harmonicSeven - (chromaticStep + diatonicStep),
      _floorDiv(maximumStep, 2),
    );
    naturalSteps = <String, int>{
      '1': 0,
      '2': diatonicStep,
      '3': 2 * diatonicStep,
      '5': 3 * diatonicStep + chromaticStep,
      '6': edo - diatonicStep - chromaticStep,
    };
    nextSteps = <String, int>{
      '1': diatonicStep,
      '2': diatonicStep,
      '3': chromaticStep + diatonicStep,
      '5': diatonicStep,
      '6': diatonicStep + chromaticStep,
    };
    wrapsToRoot = <String, int>{'1': 0, '2': 0, '3': 0, '5': 0, '6': 1};
  }

  var namePrefix = '';
  String? degree;
  var distance = edo;
  String? previousDegree;
  for (final entry in naturalSteps.entries) {
    if (entry.value == step) {
      degree = entry.key;
      distance = 0;
      break;
    }
    final candidateDistance = step - entry.value;
    if (candidateDistance >= 1 && candidateDistance < distance) {
      previousDegree = entry.key;
      distance = candidateDistance;
    }
  }

  if (degree == null) {
    if (previousDegree == null) return null;
    final nextDistance = nextSteps[previousDegree];
    if (nextDistance == null) return null;
    final upperDistance = nextDistance - distance;
    final useUpperDegree = nextDistance == maximumStep
        ? upperDistance <= maximumAccidentalVariation
        : upperDistance <=
              maximumAccidentalVariation +
                  _floorDiv(nextDistance - maximumStep, 2);
    if (useUpperDegree) {
      distance = -upperDistance;
      if (wrapsToRoot[previousDegree] == 1) {
        degree = '1';
        namePrefix = "'";
      } else {
        degree = _nextDegree(previousDegree, nextSteps);
      }
    } else {
      degree = previousDegree;
    }
  }

  final accidental = _potdAccidental(distance, sharpStep, chromaticStep);
  return accidental == null ? null : '$namePrefix$accidental$degree';
}

String _nextDegree(String degree, Map<String, int> nextSteps) {
  final next = int.parse(degree) + 1;
  return nextSteps.containsKey('$next') ? '$next' : '${next + 1}';
}

String? _potdAccidental(int distance, int sharpStep, int chromaticStep) {
  if (chromaticStep <= 0) {
    return distance >= 0 ? _repeat('^', distance) : _repeat('v', -distance);
  }
  if (sharpStep == 0) return null;
  if (_floorMod(sharpStep, 2) == 1) {
    final sharps = _roundRatioHalfToEven(distance, sharpStep);
    final arrows = distance - sharps * sharpStep;
    return '${_upDown(arrows)}${_sharpFlat(sharps)}';
  }
  final halfSharps = _roundRatioHalfToEven(2 * distance, sharpStep);
  final arrows = distance - halfSharps * (sharpStep ~/ 2);
  return '${_upDown(arrows)}${_halfSharpFlat(halfSharps)}';
}

String _sharpFlat(int amount) {
  if (amount <= 0) return _repeat('b', -amount);
  return '${_repeat('#', _floorMod(amount, 2))}${_repeat('x', amount ~/ 2)}';
}

String _halfSharpFlat(int twiceAmount) {
  if (twiceAmount <= 0) {
    final amount = -twiceAmount;
    return '${_repeat('d', _floorMod(amount, 2))}${_repeat('b', amount ~/ 2)}';
  }
  return "${_repeat('#', _floorMod(twiceAmount, 4) >= 2 ? 1 : 0)}"
      "${_repeat('+', _floorMod(twiceAmount, 2))}"
      "${_repeat('x', twiceAmount ~/ 4)}";
}

String _upDown(int amount) =>
    amount <= 0 ? _repeat('v', -amount) : _repeat('^', amount);

String _repeat(String value, int count) =>
    count <= 0 ? '' : List<String>.filled(count, value).join();

int _roundRatioHalfToEven(int numerator, int denominator) {
  var normalizedNumerator = numerator;
  var normalizedDenominator = denominator;
  if (normalizedDenominator < 0) {
    normalizedNumerator = -normalizedNumerator;
    normalizedDenominator = -normalizedDenominator;
  }
  final floorValue = _floorDiv(normalizedNumerator, normalizedDenominator);
  final remainder = normalizedNumerator - floorValue * normalizedDenominator;
  final twice = remainder * 2;
  if (twice < normalizedDenominator) return floorValue;
  if (twice > normalizedDenominator) return floorValue + 1;
  return _floorMod(floorValue, 2) == 0 ? floorValue : floorValue + 1;
}

int _roundHalfToEven(double value) {
  final floorValue = value.floor();
  final remainder = value - floorValue;
  if (remainder < 0.5) return floorValue;
  if (remainder > 0.5) return floorValue + 1;
  return _floorMod(floorValue, 2) == 0 ? floorValue : floorValue + 1;
}

int _floorDiv(int value, int divisor) {
  final quotient = value ~/ divisor;
  final remainder = value % divisor;
  return remainder != 0 && (remainder < 0) != (divisor < 0)
      ? quotient - 1
      : quotient;
}

int _floorMod(int value, int modulus) {
  final result = value % modulus;
  return result < 0 ? result + modulus.abs() : result;
}

double _log2(num value) => math.log(value) / math.ln2;
