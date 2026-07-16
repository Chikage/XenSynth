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
    this.hexPeriod = 53,
    this.hexStepQ = 9,
    this.hexStepR = 4,
    this.hexGroupByOctave = false,
    this.hexRotationDegrees = 12,
    this.touchSensitivity = 0.58,
    this.pseudoPressureEnabled = true,
    this.playbackPreviewSeconds = 2.8,
    this.pitchSnapEnabled = true,
  });

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
  final int hexPeriod;
  final int hexStepQ;
  final int hexStepR;
  final bool hexGroupByOctave;
  final int hexRotationDegrees;
  final double touchSensitivity;
  final bool pseudoPressureEnabled;
  final double playbackPreviewSeconds;
  final bool pitchSnapEnabled;

  factory XenSynthSettings.fromMap(Map<String, Object?> map) {
    const defaults = XenSynthSettings();
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
      edo: _int(map['edo'], defaults.edo).clamp(0, 72),
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
      hexPeriod: _int(map['hexPeriod'], defaults.hexPeriod).clamp(1, 200),
      hexStepQ: _int(map['hexStepQ'], defaults.hexStepQ).clamp(-200, 200),
      hexStepR: _int(map['hexStepR'], defaults.hexStepR).clamp(-200, 200),
      hexGroupByOctave: _bool(
        map['hexGroupByOctave'],
        defaults.hexGroupByOctave,
      ),
      hexRotationDegrees: _int(
        map['hexRotationDegrees'],
        defaults.hexRotationDegrees,
      ).clamp(-60, 60),
      touchSensitivity: _double(
        map['touchSensitivity'],
        defaults.touchSensitivity,
      ).clamp(0, 1),
      pseudoPressureEnabled: _bool(
        map['pseudoPressureEnabled'],
        defaults.pseudoPressureEnabled,
      ),
      playbackPreviewSeconds: _double(
        map['playbackPreviewSeconds'],
        defaults.playbackPreviewSeconds,
      ).clamp(0.5, 8),
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
    'hexPeriod': hexPeriod,
    'hexStepQ': hexStepQ,
    'hexStepR': hexStepR,
    'hexGroupByOctave': hexGroupByOctave,
    'hexRotationDegrees': hexRotationDegrees,
    'touchSensitivity': touchSensitivity,
    'pseudoPressureEnabled': pseudoPressureEnabled,
    'playbackPreviewSeconds': playbackPreviewSeconds,
    'pitchSnapEnabled': pitchSnapEnabled,
  };

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
    return XenSynthSettings(
      layoutMode: layoutMode ?? this.layoutMode,
      playbackSpeed: playbackSpeed ?? this.playbackSpeed,
      edo: edo ?? this.edo,
      pitchOffsetCents: pitchOffsetCents ?? this.pitchOffsetCents,
      volumeGain: volumeGain ?? this.volumeGain,
      reverbMix: reverbMix ?? this.reverbMix,
      audioLatencyMs: audioLatencyMs ?? this.audioLatencyMs,
      program: program ?? this.program,
      externalMidiControlsProgram:
          externalMidiControlsProgram ?? this.externalMidiControlsProgram,
      hexColumns: hexColumns ?? this.hexColumns,
      hexRows: hexRows ?? this.hexRows,
      hexPeriod: hexPeriod ?? this.hexPeriod,
      hexStepQ: hexStepQ ?? this.hexStepQ,
      hexStepR: hexStepR ?? this.hexStepR,
      hexGroupByOctave: hexGroupByOctave ?? this.hexGroupByOctave,
      hexRotationDegrees: hexRotationDegrees ?? this.hexRotationDegrees,
      touchSensitivity: touchSensitivity ?? this.touchSensitivity,
      pseudoPressureEnabled:
          pseudoPressureEnabled ?? this.pseudoPressureEnabled,
      playbackPreviewSeconds:
          playbackPreviewSeconds ?? this.playbackPreviewSeconds,
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
