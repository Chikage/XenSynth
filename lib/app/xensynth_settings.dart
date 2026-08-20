// ignore_for_file: prefer_initializing_formals

import '../core/hex_keyboard.dart';
import '../core/midi_device_selection.dart';

enum KeyboardLayoutMode { linear, hexagonal, spatial }

enum SpatialProjectionMode { cabinet, obliquePerspective }

enum PitchRecognitionMode { hybrid }

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
    this.midiInputEnabled = true,
    this.midiOutputEnabled = true,
    this.midiInputDeviceIds = const <String>[],
    this.midiInputDeviceSelectionConfigured = true,
    this.midiOutputDeviceIds = const <String>[],
    this.networkMidiEnabled = true,
    this.networkMidiDestinationIds = const <String>[],
    this.bluetoothMidiOutputIds = const <String>[],
    this.pitchRecognitionMode = PitchRecognitionMode.hybrid,
    this.microphoneSensitivity = 1,
    this.hapticFeedbackStrength = defaultHapticFeedbackStrength,
    this.hexColumns = 35,
    this.hexRows = 8,
    int? hexPeriod,
    int hexStepQ = 9,
    int hexStepR = 4,
    HexNeighborDirection hexQDirection =
        HexNeighborDirection.positiveQNegativeR,
    HexNeighborDirection hexRDirection = HexNeighborDirection.negativeR,
    this.hexGroupByOctave = false,
    this.hexRotationDegrees = 12,
    this.touchSensitivity = 0.4,
    this.pseudoPressureEnabled = true,
    this.playbackPreviewSeconds = 1.8,
    this.pitchSnapEnabled = false,
    this.spatialProjection = SpatialProjectionMode.obliquePerspective,
  }) : _hexStepQ = hexStepQ,
       _hexStepR = hexStepR,
       _hexQDirection = hexQDirection,
       _hexRDirection = hexRDirection;

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
  final bool midiInputEnabled;
  final bool midiOutputEnabled;

  /// Stable native IDs selected for input. An empty list intentionally keeps
  /// every input disconnected until the user chooses one source.
  final List<String> midiInputDeviceIds;
  final bool midiInputDeviceSelectionConfigured;

  /// Unified output IDs. Network/Bluetooth legacy fields remain persisted as
  /// routing-specific mirrors for the native transports.
  final List<String> midiOutputDeviceIds;
  final bool networkMidiEnabled;
  final List<String> networkMidiDestinationIds;
  final List<String> bluetoothMidiOutputIds;
  final PitchRecognitionMode pitchRecognitionMode;
  final double microphoneSensitivity;
  final double hapticFeedbackStrength;
  final int hexColumns;
  final int hexRows;
  final int _hexStepQ;
  final int _hexStepR;
  final HexNeighborDirection _hexQDirection;
  final HexNeighborDirection _hexRDirection;
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
  HexNeighborDirection get hexQDirection => _hexQDirection;
  HexNeighborDirection get hexRDirection =>
      _hexQDirection.isParallelTo(_hexRDirection)
      ? HexNeighborDirection.firstNonParallelTo(_hexQDirection)
      : _hexRDirection;
  HexKeyboardConfiguration get hexKeyboardConfiguration {
    final mapping = HexBasisStepMapping.resolve(
      qDirection: hexQDirection,
      rDirection: hexRDirection,
      qStep: hexStepQ,
      rStep: hexStepR,
    );
    return HexKeyboardConfiguration(
      columns: hexColumns,
      rows: hexRows,
      period: hexPeriod,
      stepQ: mapping.nativeStepQ,
      stepR: mapping.nativeStepR,
      groupByOctave: hexGroupByOctave,
      radius: 24,
      rotationDegrees: hexRotationDegrees,
      frameAcuteAngleDegrees: 72,
    ).normalized();
  }

  bool get shouldSnapPlaybackPitch =>
      layoutMode.usesHexKeyboard && pitchSnapEnabled;
  bool get hapticFeedbackEnabled => hapticFeedbackStrength > 0;
  double get appliedPitchOffsetCents => -pitchOffsetCents;
  double get touchSensitivityPercent =>
      touchSensitivityPercentMin +
      touchSensitivity.clamp(0.0, 1.0) *
          (touchSensitivityPercentMax - touchSensitivityPercentMin);

  static int hexStepMaximumForEdo(int edo) {
    final period = edo > 0 ? edo : 12;
    return period > 1 ? period - 1 : 1;
  }

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
      'hybrid' || 'fft' || 'yin' || 'piano' => PitchRecognitionMode.hybrid,
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
    final storedInputIds = singleMidiDeviceId(
      _stringList(map['midiInputDeviceIds']),
    );
    final midiInputEnabled = _bool(
      map['midiInputEnabled'],
      defaults.midiInputEnabled,
    );
    final midiOutputEnabled = _bool(
      map['midiOutputEnabled'],
      defaults.midiOutputEnabled,
    );
    final rawOutputIds = _stringList(map['midiOutputDeviceIds']).isNotEmpty
        ? _stringList(map['midiOutputDeviceIds'])
        : _mergeStringLists(
            _stringList(map['networkMidiDestinationIds']),
            _stringList(map['bluetoothMidiOutputIds']),
          );
    final storedOutputIds = singleMidiDeviceId(rawOutputIds);
    final midiSelections = _normalizeNetworkLinkSelections(
      inputIds: storedInputIds,
      outputIds: storedOutputIds,
      mirrorUnpairedNetworkSelection: true,
    );
    final inputIds = midiSelections.inputIds;
    final outputIds = midiSelections.outputIds;
    final networkOutputIds = outputIds
        .where((id) => id.startsWith('applemidi:'))
        .toList(growable: false);
    final localOutputIds = outputIds
        .where((id) => !id.startsWith('applemidi:'))
        .toList(growable: false);
    return XenSynthSettings(
      layoutMode: requestedLayoutMode,
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
      midiInputEnabled: midiInputEnabled,
      midiOutputEnabled: midiOutputEnabled,
      midiInputDeviceIds: inputIds,
      midiInputDeviceSelectionConfigured: true,
      midiOutputDeviceIds: outputIds,
      networkMidiEnabled: _bool(
        map['networkMidiEnabled'],
        defaults.networkMidiEnabled,
      ),
      networkMidiDestinationIds: networkOutputIds,
      bluetoothMidiOutputIds: localOutputIds,
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
      hexQDirection: _hexDirection(
        map['hexQDirection'],
        defaults.hexQDirection,
      ),
      hexRDirection: _hexDirection(
        map['hexRDirection'],
        defaults.hexRDirection,
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
    'midiInputEnabled': midiInputEnabled,
    'midiOutputEnabled': midiOutputEnabled,
    'midiInputDeviceIds': midiInputDeviceIds,
    'midiInputDeviceSelectionConfigured': midiInputDeviceSelectionConfigured,
    'midiOutputDeviceIds': midiOutputDeviceIds,
    'networkMidiEnabled': networkMidiEnabled,
    'networkMidiDestinationIds': networkMidiDestinationIds,
    'bluetoothMidiOutputIds': bluetoothMidiOutputIds,
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
    'hexQDirection': hexQDirection.index,
    'hexRDirection': hexRDirection.index,
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

  XenSynthSettings withEdo(int value) {
    final nextEdo = value.clamp(0, 72);
    if (!layoutMode.usesHexKeyboard || nextEdo == edo) {
      return copyWith(edo: nextEdo);
    }
    final steps = _recommendedHexStepsForEdo(nextEdo);
    return copyWith(edo: nextEdo, hexStepQ: steps.q, hexStepR: steps.r);
  }

  /// Applies the single-device MIDI invariant to settings supplied by callers
  /// that constructed this immutable value directly instead of using
  /// [copyWith] or [fromMap].
  XenSynthSettings normalizedMidiSelections() => copyWith();

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
    bool? midiInputEnabled,
    bool? midiOutputEnabled,
    List<String>? midiInputDeviceIds,
    bool? midiInputDeviceSelectionConfigured,
    List<String>? midiOutputDeviceIds,
    bool? networkMidiEnabled,
    List<String>? networkMidiDestinationIds,
    List<String>? bluetoothMidiOutputIds,
    PitchRecognitionMode? pitchRecognitionMode,
    double? microphoneSensitivity,
    double? hapticFeedbackStrength,
    int? hexColumns,
    int? hexRows,
    int? hexPeriod,
    int? hexStepQ,
    int? hexStepR,
    HexNeighborDirection? hexQDirection,
    HexNeighborDirection? hexRDirection,
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
    final requestedLayoutMode = layoutMode ?? this.layoutMode;
    final nextMidiInputEnabled = midiInputEnabled ?? this.midiInputEnabled;
    final nextMidiOutputEnabled = midiOutputEnabled ?? this.midiOutputEnabled;
    final requestedInputIds = singleMidiDeviceId(
      midiInputDeviceIds ?? this.midiInputDeviceIds,
    );
    final requestedOutputIds =
        midiOutputDeviceIds ??
        ((networkMidiDestinationIds != null || bluetoothMidiOutputIds != null)
            ? _mergeStringLists(
                networkMidiDestinationIds ?? this.networkMidiDestinationIds,
                bluetoothMidiOutputIds ?? this.bluetoothMidiOutputIds,
              )
            : this.midiOutputDeviceIds);
    final requestedSingleOutputIds = singleMidiDeviceId(requestedOutputIds);
    final midiSelections = _normalizeNetworkLinkSelections(
      inputIds: requestedInputIds,
      outputIds: requestedSingleOutputIds,
    );
    final nextInputIds = midiSelections.inputIds;
    final nextOutputIds = midiSelections.outputIds;
    final nextNetworkOutputIds = nextOutputIds
        .where((id) => id.startsWith('applemidi:'))
        .toList(growable: false);
    final nextLocalOutputIds = nextOutputIds
        .where((id) => !id.startsWith('applemidi:'))
        .toList(growable: false);
    return XenSynthSettings(
      layoutMode: requestedLayoutMode,
      playbackSpeed: playbackSpeed ?? this.playbackSpeed,
      edo: nextEdo,
      pitchOffsetCents: pitchOffsetCents ?? this.pitchOffsetCents,
      volumeGain: volumeGain ?? this.volumeGain,
      reverbMix: reverbMix ?? this.reverbMix,
      audioLatencyMs: audioLatencyMs ?? this.audioLatencyMs,
      program: program ?? this.program,
      externalMidiControlsProgram:
          externalMidiControlsProgram ?? this.externalMidiControlsProgram,
      midiInputEnabled: nextMidiInputEnabled,
      midiOutputEnabled: nextMidiOutputEnabled,
      midiInputDeviceIds: nextInputIds,
      midiInputDeviceSelectionConfigured: true,
      midiOutputDeviceIds: nextOutputIds,
      networkMidiEnabled: networkMidiEnabled ?? this.networkMidiEnabled,
      networkMidiDestinationIds: nextNetworkOutputIds,
      bluetoothMidiOutputIds: nextLocalOutputIds,
      pitchRecognitionMode: pitchRecognitionMode ?? this.pitchRecognitionMode,
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
      hexQDirection: hexQDirection ?? this.hexQDirection,
      hexRDirection: hexRDirection ?? this.hexRDirection,
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
    return value.clamp(-maximum, maximum).toInt();
  }

  static ({int q, int r}) _recommendedHexStepsForEdo(int edo) {
    final period = edo > 0 ? edo : 12;
    final maximum = hexStepMaximumForEdo(edo);
    final primes = <int>[
      for (var candidate = 2; candidate <= maximum; candidate++)
        if (_isPrime(candidate)) candidate,
    ];

    if (primes.length < 2) {
      return switch (primes) {
        [final only] => (q: only, r: 1),
        _ => (q: 1, r: -1),
      };
    }

    // Scale the established 26-EDO 9/4 layout before snapping to primes.
    final q = _closestValue(primes, period * 9 / 26);
    final r = _closestValue(
      primes.where((candidate) => candidate != q),
      period * 4 / 26,
    );
    return (q: q, r: r);
  }

  static int _closestValue(Iterable<int> values, double target) {
    int? closest;
    var closestDistance = double.infinity;
    for (final value in values) {
      final distance = (value - target).abs();
      if (closest == null ||
          distance < closestDistance ||
          (distance == closestDistance && value > closest)) {
        closest = value;
        closestDistance = distance;
      }
    }
    return closest!;
  }

  static bool _isPrime(int value) {
    if (value < 2) return false;
    for (var divisor = 2; divisor * divisor <= value; divisor++) {
      if (value % divisor == 0) return false;
    }
    return true;
  }

  static double _double(Object? value, double fallback) {
    return value is num
        ? value.toDouble()
        : double.tryParse('$value') ?? fallback;
  }

  static HexNeighborDirection _hexDirection(
    Object? value,
    HexNeighborDirection fallback,
  ) {
    final index = value is num ? value.toInt() : int.tryParse('$value');
    return index == null
        ? fallback
        : HexNeighborDirection.fromIndex(index, fallback: fallback);
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

  static List<String> _stringList(Object? value) {
    if (value is! List) return const <String>[];
    return value
        .map((item) => item.toString().trim())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList(growable: false);
  }

  static List<String> _mergeStringLists(
    List<String> first,
    List<String> second,
  ) {
    return <String>{...first, ...second}.toList(growable: false)..sort();
  }

  static ({List<String> inputIds, List<String> outputIds})
  _normalizeNetworkLinkSelections({
    required List<String> inputIds,
    required List<String> outputIds,
    bool mirrorUnpairedNetworkSelection = false,
  }) {
    final inputId = inputIds.isEmpty ? null : inputIds.first;
    final outputId = outputIds.isEmpty ? null : outputIds.first;
    final inputIsNetwork = inputId?.startsWith('applemidi:') == true;
    final outputIsNetwork = outputId?.startsWith('applemidi:') == true;

    if (inputIsNetwork && outputIsNetwork) {
      // Prefer the output scan's ID because it is the identity used to create
      // the native AppleMIDI connection, then mirror it to the input gate.
      final peerId = outputId!;
      return (inputIds: <String>[peerId], outputIds: <String>[peerId]);
    }
    if (mirrorUnpairedNetworkSelection && inputIsNetwork && outputId == null) {
      return (inputIds: <String>[inputId!], outputIds: <String>[inputId]);
    }
    if (mirrorUnpairedNetworkSelection && outputIsNetwork && inputId == null) {
      return (inputIds: <String>[outputId!], outputIds: <String>[outputId]);
    }
    return (inputIds: inputIds, outputIds: outputIds);
  }
}
