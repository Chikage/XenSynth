// ignore_for_file: prefer_initializing_formals

import '../core/hex_keyboard.dart';

enum KeyboardLayoutMode { linear, hexagonal, spatial }

enum SpatialProjectionMode { cabinet, obliquePerspective }

enum PitchRecognitionMode { piano, yin, fft }

extension KeyboardLayoutModeSemantics on KeyboardLayoutMode {
  bool get usesHexKeyboard => this != KeyboardLayoutMode.linear;
}

class XenSynthSettings {
  const XenSynthSettings({
    this.layoutMode = KeyboardLayoutMode.linear,
    this.playbackSpeed = 1,
    this.edo = 26,
    this.pitchOffsetCents = 0,
    this.volumeGain = 0.85,
    this.reverbMix = 54,
    this.audioLatencyMs = 0,
    this.program = 0,
    this.externalMidiControlsProgram = false,
    this.pitchRecognitionMode = PitchRecognitionMode.yin,
    this.microphoneSensitivity = 1,
    this.hapticFeedbackStrength = defaultHapticFeedbackStrength,
    this.hexColumns = 35,
    this.hexRows = 8,
    int? hexPeriod,
    int hexStepQ = 9,
    int hexStepR = 4,
    this.hexGroupByOctave = false,
    this.hexRotationDegrees = 12,
    this.touchSensitivity = 0.4,
    this.pseudoPressureEnabled = true,
    this.playbackPreviewSeconds = 1.8,
    this.pitchSnapEnabled = false,
    this.spatialProjection = SpatialProjectionMode.obliquePerspective,
  }) : _hexStepQ = hexStepQ,
       _hexStepR = hexStepR;

  static const double touchSensitivityPercentMin = 100;
  static const double touchSensitivityPercentMax = 150;
  static const double playbackPreviewSecondsMin = 0;
  static const double playbackPreviewSecondsMax = 3;
  static const double microphoneSensitivityMin = 0.5;
  static const double microphoneSensitivityMax = 2.0;
  static const double defaultHapticFeedbackStrength = 2 / 3;

  final KeyboardLayoutMode layoutMode;
  final double playbackSpeed;
  final int edo;
  final double pitchOffsetCents;
  final double volumeGain;
  final double reverbMix;
  final double audioLatencyMs;
  final int program;
  final bool externalMidiControlsProgram;
  final PitchRecognitionMode pitchRecognitionMode;
  final double microphoneSensitivity;
  final double hapticFeedbackStrength;
  final int hexColumns;
  final int hexRows;
  final int _hexStepQ;
  final int _hexStepR;
  final bool hexGroupByOctave;
  final int hexRotationDegrees;
  final double touchSensitivity;
  final bool pseudoPressureEnabled;
  final double playbackPreviewSeconds;
  final bool pitchSnapEnabled;
  final SpatialProjectionMode spatialProjection;

  int get hexPeriod => edo > 0 ? edo : 12;
  int get hexStepMaximum => hexStepMaximumForEdo(edo);
  int get hexStepQ => _normalizeHexStep(_hexStepQ, hexStepMaximum);
  int get hexStepR => _normalizeHexStep(_hexStepR, hexStepMaximum);
  HexKeyboardConfiguration get hexKeyboardConfiguration =>
      HexKeyboardConfiguration(
        columns: hexColumns,
        rows: hexRows,
        period: hexPeriod,
        // The UI Q axis is 60° counterclockwise from native +q, while the
        // UI R axis points opposite native +r.
        stepQ: hexStepQ - hexStepR,
        stepR: -hexStepR,
        groupByOctave: hexGroupByOctave,
        radius: 24,
        rotationDegrees: hexRotationDegrees,
        frameAcuteAngleDegrees: 72,
      ).normalized();
  bool get shouldSnapPlaybackPitch =>
      layoutMode.usesHexKeyboard && pitchSnapEnabled;
  bool get hapticFeedbackEnabled => hapticFeedbackStrength > 0;
  double get appliedPitchOffsetCents => -pitchOffsetCents;
  double get touchSensitivityPercent =>
      touchSensitivityPercentMin +
      touchSensitivity.clamp(0.0, 1.0) *
          (touchSensitivityPercentMax - touchSensitivityPercentMin);

  static int hexStepMaximumForEdo(int edo) => edo > 1 ? edo - 1 : 1;

  static double touchSensitivityFromPercent(double percent) {
    return ((percent - touchSensitivityPercentMin) /
            (touchSensitivityPercentMax - touchSensitivityPercentMin))
        .clamp(0.0, 1.0);
  }

  factory XenSynthSettings.fromMap(Map<String, Object?> map) {
    const defaults = XenSynthSettings();
    final edoSource = map.containsKey('edo') ? map['edo'] : map['hexPeriod'];
    final edo = _int(edoSource, defaults.edo).clamp(0, 72);
    final hexStepMaximum = hexStepMaximumForEdo(edo);
    final pitchRecognitionMode = switch (map['pitchRecognitionMode']
        ?.toString()
        .toLowerCase()) {
      'fft' => PitchRecognitionMode.fft,
      'yin' => PitchRecognitionMode.yin,
      'piano' => PitchRecognitionMode.piano,
      _ => defaults.pitchRecognitionMode,
    };
    final requestedLayoutMode = switch (map['keyboardLayoutMode']?.toString()) {
      'hexagonal' || 'hex' => KeyboardLayoutMode.hexagonal,
      'spatial' ||
      'spatialWaterfall' ||
      'waterfall3d' => KeyboardLayoutMode.spatial,
      'linear' => KeyboardLayoutMode.linear,
      _ => defaults.layoutMode,
    };
    return XenSynthSettings(
      layoutMode: pitchRecognitionMode == PitchRecognitionMode.fft
          ? KeyboardLayoutMode.linear
          : requestedLayoutMode,
      playbackSpeed: _double(
        map['playbackSpeed'],
        defaults.playbackSpeed,
      ).clamp(0.2, 4),
      edo: edo,
      pitchOffsetCents: _double(
        map['pitchOffsetCents'],
        defaults.pitchOffsetCents,
      ).clamp(-128, 128),
      volumeGain: _double(map['volumeGain'], defaults.volumeGain).clamp(0, 1),
      reverbMix: _double(map['reverbMix'], defaults.reverbMix).clamp(0, 100),
      audioLatencyMs: _double(
        map['audioLatencyMs'],
        defaults.audioLatencyMs,
      ).clamp(-100, 700),
      program: _int(map['program'], defaults.program).clamp(0, 127),
      externalMidiControlsProgram: _bool(
        map['externalMidiControlsProgram'],
        defaults.externalMidiControlsProgram,
      ),
      pitchRecognitionMode: pitchRecognitionMode,
      microphoneSensitivity: _double(
        map['microphoneSensitivity'],
        defaults.microphoneSensitivity,
      ).clamp(microphoneSensitivityMin, microphoneSensitivityMax),
      hapticFeedbackStrength: _hapticFeedbackStrength(
        map,
        defaults.hapticFeedbackStrength,
      ),
      hexColumns: _int(map['hexColumns'], defaults.hexColumns).clamp(4, 64),
      hexRows: _int(map['hexRows'], defaults.hexRows).clamp(3, 32),
      hexStepQ: _normalizeHexStep(
        _int(map['hexStepQ'], defaults.hexStepQ),
        hexStepMaximum,
      ),
      hexStepR: _normalizeHexStep(
        _int(map['hexStepR'], defaults.hexStepR),
        hexStepMaximum,
      ),
      hexGroupByOctave: _bool(
        map['hexGroupByOctave'],
        defaults.hexGroupByOctave,
      ),
      hexRotationDegrees: _int(
        map['hexRotationDegrees'],
        defaults.hexRotationDegrees,
      ).clamp(-60, 60),
      touchSensitivity: _normalizedTouchSensitivity(
        map['touchSensitivity'],
        defaults.touchSensitivity,
      ),
      pseudoPressureEnabled: _bool(
        map['pseudoPressureEnabled'],
        defaults.pseudoPressureEnabled,
      ),
      playbackPreviewSeconds: _double(
        map['playbackPreviewSeconds'],
        defaults.playbackPreviewSeconds,
      ).clamp(playbackPreviewSecondsMin, playbackPreviewSecondsMax),
      pitchSnapEnabled: _bool(
        map['pitchSnapEnabled'],
        defaults.pitchSnapEnabled,
      ),
      spatialProjection: switch (map['spatialProjection']?.toString()) {
        'obliquePerspective' ||
        'perspective' => SpatialProjectionMode.obliquePerspective,
        'cabinet' ||
        'oblique' ||
        'axonometric' ||
        'isometric' => SpatialProjectionMode.cabinet,
        _ => defaults.spatialProjection,
      },
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'keyboardLayoutMode': layoutMode.name,
    'playbackSpeed': playbackSpeed,
    'edo': edo,
    'pitchOffsetCents': pitchOffsetCents,
    'volumeGain': volumeGain,
    'reverbMix': reverbMix,
    'audioLatencyMs': audioLatencyMs,
    'program': program,
    'externalMidiControlsProgram': externalMidiControlsProgram,
    'pitchRecognitionMode': pitchRecognitionMode.name,
    'microphoneSensitivity': microphoneSensitivity.clamp(
      microphoneSensitivityMin,
      microphoneSensitivityMax,
    ),
    'hapticFeedbackStrength': hapticFeedbackStrength.clamp(0.0, 1.0),
    // Keep writing the old switch for builds that predate strength levels.
    'hapticFeedbackEnabled': hapticFeedbackEnabled,
    'hexColumns': hexColumns,
    'hexRows': hexRows,
    // Keep writing the legacy key for older native builds and stored maps.
    'hexPeriod': hexPeriod,
    'hexStepQ': hexStepQ,
    'hexStepR': hexStepR,
    'hexGroupByOctave': hexGroupByOctave,
    'hexRotationDegrees': hexRotationDegrees,
    'touchSensitivity': touchSensitivity.clamp(0.0, 1.0),
    'pseudoPressureEnabled': pseudoPressureEnabled,
    'playbackPreviewSeconds': playbackPreviewSeconds.clamp(
      playbackPreviewSecondsMin,
      playbackPreviewSecondsMax,
    ),
    'pitchSnapEnabled': pitchSnapEnabled,
    'spatialProjection': spatialProjection.name,
  };

  XenSynthSettings withEdo(int value) => copyWith(edo: value);

  XenSynthSettings copyWith({
    KeyboardLayoutMode? layoutMode,
    double? playbackSpeed,
    int? edo,
    double? pitchOffsetCents,
    double? volumeGain,
    double? reverbMix,
    double? audioLatencyMs,
    int? program,
    bool? externalMidiControlsProgram,
    PitchRecognitionMode? pitchRecognitionMode,
    double? microphoneSensitivity,
    double? hapticFeedbackStrength,
    int? hexColumns,
    int? hexRows,
    int? hexPeriod,
    int? hexStepQ,
    int? hexStepR,
    bool? hexGroupByOctave,
    int? hexRotationDegrees,
    double? touchSensitivity,
    bool? pseudoPressureEnabled,
    double? playbackPreviewSeconds,
    bool? pitchSnapEnabled,
    SpatialProjectionMode? spatialProjection,
  }) {
    final nextEdo = (edo ?? this.edo).clamp(0, 72);
    final nextHexStepMaximum = hexStepMaximumForEdo(nextEdo);
    final nextPitchRecognitionMode =
        pitchRecognitionMode ?? this.pitchRecognitionMode;
    final requestedLayoutMode = layoutMode ?? this.layoutMode;
    return XenSynthSettings(
      layoutMode: nextPitchRecognitionMode == PitchRecognitionMode.fft
          ? KeyboardLayoutMode.linear
          : requestedLayoutMode,
      playbackSpeed: playbackSpeed ?? this.playbackSpeed,
      edo: nextEdo,
      pitchOffsetCents: pitchOffsetCents ?? this.pitchOffsetCents,
      volumeGain: volumeGain ?? this.volumeGain,
      reverbMix: reverbMix ?? this.reverbMix,
      audioLatencyMs: audioLatencyMs ?? this.audioLatencyMs,
      program: program ?? this.program,
      externalMidiControlsProgram:
          externalMidiControlsProgram ?? this.externalMidiControlsProgram,
      pitchRecognitionMode: nextPitchRecognitionMode,
      microphoneSensitivity:
          (microphoneSensitivity ?? this.microphoneSensitivity).clamp(
            microphoneSensitivityMin,
            microphoneSensitivityMax,
          ),
      hapticFeedbackStrength:
          (hapticFeedbackStrength ?? this.hapticFeedbackStrength).clamp(
            0.0,
            1.0,
          ),
      hexColumns: hexColumns ?? this.hexColumns,
      hexRows: hexRows ?? this.hexRows,
      hexStepQ: _normalizeHexStep(
        hexStepQ ?? this.hexStepQ,
        nextHexStepMaximum,
      ),
      hexStepR: _normalizeHexStep(
        hexStepR ?? this.hexStepR,
        nextHexStepMaximum,
      ),
      hexGroupByOctave: hexGroupByOctave ?? this.hexGroupByOctave,
      hexRotationDegrees: hexRotationDegrees ?? this.hexRotationDegrees,
      touchSensitivity: (touchSensitivity ?? this.touchSensitivity).clamp(
        0.0,
        1.0,
      ),
      pseudoPressureEnabled:
          pseudoPressureEnabled ?? this.pseudoPressureEnabled,
      playbackPreviewSeconds:
          (playbackPreviewSeconds ?? this.playbackPreviewSeconds).clamp(
            playbackPreviewSecondsMin,
            playbackPreviewSecondsMax,
          ),
      pitchSnapEnabled: pitchSnapEnabled ?? this.pitchSnapEnabled,
      spatialProjection: spatialProjection ?? this.spatialProjection,
    );
  }

  static int _int(Object? value, int fallback) {
    return value is num ? value.toInt() : int.tryParse('$value') ?? fallback;
  }

  static int _normalizeHexStep(int value, int maximum) {
    if (value == 0) return 1;
    final magnitude = value.abs().clamp(1, maximum).toInt();
    return value < 0 ? -magnitude : magnitude;
  }

  static double _double(Object? value, double fallback) {
    return value is num
        ? value.toDouble()
        : double.tryParse('$value') ?? fallback;
  }

  static double _normalizedTouchSensitivity(Object? value, double fallback) {
    final parsed = _double(value, fallback);
    if (parsed > 1) return touchSensitivityFromPercent(parsed);
    return parsed.clamp(0.0, 1.0);
  }

  static double _hapticFeedbackStrength(
    Map<String, Object?> map,
    double fallback,
  ) {
    if (map.containsKey('hapticFeedbackStrength')) {
      return _double(map['hapticFeedbackStrength'], fallback).clamp(0.0, 1.0);
    }
    if (map.containsKey('hapticFeedbackEnabled')) {
      return _bool(map['hapticFeedbackEnabled'], true) ? fallback : 0;
    }
    return fallback;
  }

  static bool _bool(Object? value, bool fallback) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    return switch (value?.toString().toLowerCase()) {
      'true' || 'yes' || '1' => true,
      'false' || 'no' || '0' => false,
      _ => fallback,
    };
  }
}
