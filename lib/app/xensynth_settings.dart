// ignore_for_file: prefer_initializing_formals

enum KeyboardLayoutMode { linear, hexagonal }

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
  }) : _hexStepQ = hexStepQ,
       _hexStepR = hexStepR;

  static const double touchSensitivityPercentMin = 100;
  static const double touchSensitivityPercentMax = 150;
  static const double playbackPreviewSecondsMin = 0;
  static const double playbackPreviewSecondsMax = 3;

  final KeyboardLayoutMode layoutMode;
  final double playbackSpeed;
  final int edo;
  final double pitchOffsetCents;
  final double volumeGain;
  final double reverbMix;
  final double audioLatencyMs;
  final int program;
  final bool externalMidiControlsProgram;
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

  int get hexPeriod => edo > 0 ? edo : 12;
  int get hexStepMaximum => hexStepMaximumForEdo(edo);
  int get hexStepQ => _hexStepQ.clamp(1, hexStepMaximum);
  int get hexStepR => _hexStepR.clamp(1, hexStepMaximum);
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
    return XenSynthSettings(
      layoutMode: switch (map['keyboardLayoutMode']?.toString()) {
        'hexagonal' || 'hex' => KeyboardLayoutMode.hexagonal,
        'linear' => KeyboardLayoutMode.linear,
        _ => defaults.layoutMode,
      },
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
      hexColumns: _int(map['hexColumns'], defaults.hexColumns).clamp(4, 64),
      hexRows: _int(map['hexRows'], defaults.hexRows).clamp(3, 32),
      hexStepQ: _int(
        map['hexStepQ'],
        defaults.hexStepQ,
      ).clamp(1, hexStepMaximum),
      hexStepR: _int(
        map['hexStepR'],
        defaults.hexStepR,
      ).clamp(1, hexStepMaximum),
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
  }) {
    final nextEdo = (edo ?? this.edo).clamp(0, 72);
    final nextHexStepMaximum = hexStepMaximumForEdo(nextEdo);
    return XenSynthSettings(
      layoutMode: layoutMode ?? this.layoutMode,
      playbackSpeed: playbackSpeed ?? this.playbackSpeed,
      edo: nextEdo,
      pitchOffsetCents: pitchOffsetCents ?? this.pitchOffsetCents,
      volumeGain: volumeGain ?? this.volumeGain,
      reverbMix: reverbMix ?? this.reverbMix,
      audioLatencyMs: audioLatencyMs ?? this.audioLatencyMs,
      program: program ?? this.program,
      externalMidiControlsProgram:
          externalMidiControlsProgram ?? this.externalMidiControlsProgram,
      hexColumns: hexColumns ?? this.hexColumns,
      hexRows: hexRows ?? this.hexRows,
      hexStepQ: (hexStepQ ?? this.hexStepQ).clamp(1, nextHexStepMaximum),
      hexStepR: (hexStepR ?? this.hexStepR).clamp(1, nextHexStepMaximum),
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
    );
  }

  static int _int(Object? value, int fallback) {
    return value is num ? value.toInt() : int.tryParse('$value') ?? fallback;
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
