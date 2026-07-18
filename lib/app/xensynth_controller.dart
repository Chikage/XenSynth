import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../core/hex_keyboard.dart';
import '../core/midi_parser.dart';
import '../core/score.dart';
import '../core/tuning.dart';
import '../platform/native_bridge.dart';
import 'xensynth_settings.dart';

class XenSynthController extends ChangeNotifier {
  XenSynthController({XenSynthNativeBridge? nativeBridge})
    : _native = nativeBridge ?? XenSynthNativeBridge.instance;

  final XenSynthNativeBridge _native;
  StreamSubscription<NativeMidiEvent>? _midiSubscription;
  Timer? _clockTimer;
  Timer? _settingsSaveTimer;
  final Stopwatch _clock = Stopwatch();
  final Map<int, int> _noteTokens = {};
  final Map<int, int> _noteEpochs = {};
  final Set<int> _sustainedMidiPointers = {};
  final Set<int> _deferredMidiOffs = {};
  final Map<int, bool> _sustainByChannel = {};
  HexKeyboardConfiguration? _pitchSnapConfiguration;
  HexaKeyboardLayout? _pitchSnapLayout;

  XenSynthSettings settings = const XenSynthSettings();
  TuningDefinition tuning = TuningDefinition.standard;
  bool customTuningActive = false;
  ParsedScore? score;
  String status = 'INITIALIZING';
  bool initialized = false;
  bool loading = false;
  bool audioReady = false;
  bool playing = false;
  // The score can finish while the waterfall still needs a short visual tail.
  bool waterfallAnimating = false;
  double playhead = 0;
  double visualPlayhead = 0;
  Map<int, double> activePitches = const {};
  double _visualClockBase = 0;
  bool _seekGestureActive = false;
  bool _resumeAfterSeekGesture = false;
  Future<void>? _seekPauseFuture;

  double get duration => score?.duration ?? 0;
  String get scoreTitle => score?.title ?? 'XEN SYNTH';
  String get tuningLabel => tuning.profile.isEmpty ? 'TUN' : tuning.profile;
  double get currentBpm {
    final currentScore = score;
    if (currentScore == null || currentScore.tempoMap.isEmpty) {
      return 120 * settings.playbackSpeed;
    }
    var tempo = currentScore.tempoMap.first;
    for (final candidate in currentScore.tempoMap) {
      if (candidate.second > playhead) break;
      tempo = candidate;
    }
    return 60000000 / tempo.usPerQuarter * settings.playbackSpeed;
  }

  MeterEvent get currentMeter {
    final currentScore = score;
    if (currentScore == null ||
        currentScore.meters.isEmpty ||
        currentScore.tempoMap.isEmpty) {
      return const MeterEvent(tick: 0, numerator: 4, denominator: 4);
    }
    final tick = MidiWaterfallParser.secondsToTick(
      playhead,
      currentScore.tempoMap,
      currentScore.ticksPerQuarter,
    );
    var meter = currentScore.meters.first;
    for (final candidate in currentScore.meters) {
      if (candidate.tick > tick) break;
      meter = candidate;
    }
    return meter;
  }

  Future<void> initialize() async {
    if (initialized || loading) return;
    loading = true;
    notifyListeners();
    try {
      final saved = await _native.loadSettings().catchError(
        (Object _) => <String, Object?>{},
      );
      if (saved.isNotEmpty) settings = XenSynthSettings.fromMap(saved);
      try {
        audioReady = await _native.initializeAudio();
        await Future.wait([
          _native.setGain(settings.volumeGain),
          _native.setReverb(settings.reverbMix),
          _native.setLatency(settings.audioLatencyMs),
          _native.setProgram(program: settings.program),
        ]);
      } catch (error) {
        debugPrint('Native audio initialization failed: $error');
        audioReady = false;
      }
      try {
        _midiSubscription = _native.midiEvents.listen(
          _handleMidiEvent,
          onError: (Object error) => _setStatus('MIDI UNAVAILABLE'),
        );
      } catch (error) {
        debugPrint('MIDI subscription failed: $error');
      }
      await _loadBundledDemo();
      initialized = true;
      status = audioReady ? 'READY' : 'VISUAL MODE';
    } catch (error, stackTrace) {
      debugPrint('XenSynth initialization failed: $error\n$stackTrace');
      status = 'INIT FAILED · $error';
      initialized = true;
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  Future<void> openDocument() async {
    if (loading) return;
    try {
      final document = await _native.pickDocument(
        kind: 'scoreOrTuning',
        extensions: _supportedDocumentExtensions,
      );
      if (document == null) return;
      await _processDocument(document);
    } on PlatformException catch (error) {
      _setStatus(error.message ?? error.code);
    } catch (error) {
      _setStatus('OPEN FAILED · $error');
    }
  }

  Future<void> loadScoreBytes(Uint8List bytes, String title) async {
    loading = true;
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    await _native.stop();
    notifyListeners();
    try {
      final lower = title.toLowerCase();
      var scoreBytes = bytes;
      var scoreTitle = title;
      if (lower.endsWith('.mscz') || lower.endsWith('.mscx')) {
        scoreBytes =
            await _native.convertMuseScore(name: title, bytes: bytes) ??
            (throw UnsupportedError('MuseScore conversion is unavailable'));
        scoreTitle = title.replaceFirst(
          RegExp(r'\.msc[zx]$', caseSensitive: false),
          '.midx',
        );
      }
      final parsed = MidiWaterfallParser.detectAndParse(
        scoreBytes,
        title: scoreTitle,
      );
      score = parsed;
      playhead = _initialPlayhead(parsed);
      visualPlayhead = playhead;
      await _native.loadScore(_nativeScoreMap(parsed, settings));
      status = '${parsed.format} · ${parsed.notes.length} NOTES';
    } catch (error, stackTrace) {
      debugPrint('Score parse failed: $error\n$stackTrace');
      status = 'PARSE FAILED · $error';
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  Future<void> togglePlayback() async {
    if (score == null || loading) return;
    if (playing) {
      await pause();
    } else {
      await play();
    }
  }

  Future<void> play() async {
    final currentScore = score;
    if (currentScore == null || currentScore.duration <= 0) return;
    _clearSeekGestureState();
    if (playhead >= currentScore.duration - 0.001) playhead = 0;
    visualPlayhead = playhead;
    playing = true;
    waterfallAnimating = true;
    _startPlaybackClock();
    notifyListeners();
    try {
      await _native.play(
        from: playhead,
        speed: settings.playbackSpeed,
        offsetCents: settings.appliedPitchOffsetCents,
        audioStartDelaySeconds: settings.audioLatencyMs / 1000,
      );
    } catch (error) {
      playing = false;
      waterfallAnimating = false;
      _stopClock();
      _setStatus('PLAYBACK FAILED · $error');
    }
  }

  Future<void> pause() async {
    _syncClockPosition();
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    notifyListeners();
    await _native.pause();
  }

  Future<void> seek(double position) async {
    if (_seekGestureActive) {
      updateSeekGesture(position);
      return;
    }
    if (waterfallAnimating && !playing) {
      waterfallAnimating = false;
      _stopClock();
    }
    playhead = position.clamp(0, duration).toDouble();
    visualPlayhead = playhead;
    if (playing) {
      _visualClockBase = visualPlayhead;
      _clock
        ..reset()
        ..start();
    }
    notifyListeners();
    await _native.seek(playhead);
  }

  void beginSeekGesture() {
    if (_seekGestureActive || duration <= 0) return;
    _seekGestureActive = true;
    _resumeAfterSeekGesture = playing;
    if (!playing && !waterfallAnimating) return;
    _syncClockPosition();
    _stopClock();
    if (!playing) {
      waterfallAnimating = false;
      visualPlayhead = playhead;
    }
    notifyListeners();
    _seekPauseFuture = playing ? _native.pause() : Future<void>.value();
  }

  void updateSeekGesture(double position) {
    if (!_seekGestureActive) {
      unawaited(seek(position));
      return;
    }
    final next = position.clamp(0, duration).toDouble();
    if ((playhead - next).abs() < 0.000001) return;
    playhead = next;
    visualPlayhead = next;
    notifyListeners();
  }

  Future<void> endSeekGesture() async {
    if (!_seekGestureActive) return;
    final resume = _resumeAfterSeekGesture;
    final pauseFuture = _seekPauseFuture;
    _clearSeekGestureState();
    if (pauseFuture != null) await pauseFuture;
    await _native.seek(playhead);
    if (!resume) return;
    if (playhead >= duration - 0.001) {
      playing = false;
      waterfallAnimating = false;
      visualPlayhead = playhead;
      notifyListeners();
      return;
    }
    playing = true;
    waterfallAnimating = true;
    visualPlayhead = playhead;
    _startPlaybackClock();
    notifyListeners();
    try {
      await _native.play(
        from: playhead,
        speed: settings.playbackSpeed,
        offsetCents: settings.appliedPitchOffsetCents,
        audioStartDelaySeconds: settings.audioLatencyMs / 1000,
      );
    } catch (error) {
      playing = false;
      waterfallAnimating = false;
      _stopClock();
      _setStatus('PLAYBACK FAILED · $error');
    }
  }

  Future<void> resetPlayback() async {
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    playhead = 0;
    visualPlayhead = 0;
    notifyListeners();
    await _native.seek(0);
    await _native.pause();
  }

  Future<void> stop() async {
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    playhead = 0;
    visualPlayhead = 0;
    await releaseAllNotes();
    await _native.stop();
    notifyListeners();
  }

  Future<void> updateSettings(XenSynthSettings next) async {
    final previous = settings;
    final snapMappingChanged = _pitchSnapMappingChanged(previous, next);
    final playbackParametersChanged =
        next.playbackSpeed != previous.playbackSpeed ||
        next.pitchOffsetCents != previous.pitchOffsetCents ||
        snapMappingChanged;
    final clockActive = playing || waterfallAnimating;
    if (clockActive && playbackParametersChanged) {
      _syncClockPosition();
    }
    settings = next;
    if (clockActive && playbackParametersChanged && waterfallAnimating) {
      _visualClockBase = visualPlayhead;
      _clock
        ..reset()
        ..start();
    }
    notifyListeners();
    _settingsSaveTimer?.cancel();
    _settingsSaveTimer = Timer(const Duration(milliseconds: 350), () {
      unawaited(_native.saveSettings(settings.toMap()));
    });

    if (next.volumeGain != previous.volumeGain) {
      await _native.setGain(next.volumeGain);
    }
    if (next.reverbMix != previous.reverbMix) {
      await _native.setReverb(next.reverbMix);
    }
    if (next.audioLatencyMs != previous.audioLatencyMs) {
      await _native.setLatency(next.audioLatencyMs);
    }
    if (next.program != previous.program) {
      await _native.setProgram(program: next.program);
    }
    final currentScore = score;
    if (snapMappingChanged && currentScore != null) {
      await _native.loadScore(_nativeScoreMap(currentScore, next));
    }
    if (playing && playbackParametersChanged) {
      await _native.play(
        from: playhead,
        speed: next.playbackSpeed,
        offsetCents: next.appliedPitchOffsetCents,
        audioStartDelaySeconds: next.audioLatencyMs / 1000,
      );
    }
  }

  void setVolumeGainFromGesture(double gain) {
    final nextGain = gain.clamp(0.0, 1.0).toDouble();
    if ((settings.volumeGain - nextGain).abs() < 0.0001) return;
    unawaited(updateSettings(settings.copyWith(volumeGain: nextGain)));
  }

  Future<void> resetSettings() async {
    await updateSettings(const XenSynthSettings());
    _setStatus('DEFAULTS RESTORED');
  }

  void noteDown(int pointer, double pitch, int velocity) {
    final playbackPitch = _playbackPitch(pitch, settings);
    final targetPitch = playbackPitch + settings.appliedPitchOffsetCents / 100;
    final nextActive = Map<int, double>.from(activePitches)
      ..[pointer] = playbackPitch;
    activePitches = nextActive;
    final epoch = (_noteEpochs[pointer] ?? 0) + 1;
    _noteEpochs[pointer] = epoch;
    notifyListeners();
    unawaited(() async {
      final oldToken = _noteTokens.remove(pointer);
      if (oldToken != null) await _native.noteOff(oldToken);
      final token = await _native.noteOn(
        id: pointer,
        pitch: targetPitch,
        velocity: velocity.clamp(1, 127),
        channel: 0,
        program: settings.program,
      );
      if (token == null) return;
      if (_noteEpochs[pointer] == epoch && activePitches.containsKey(pointer)) {
        _noteTokens[pointer] = token;
      } else {
        await _native.noteOff(token);
      }
    }());
  }

  void noteMove(int pointer, double pitch, int velocity) {
    final playbackPitch = _playbackPitch(pitch, settings);
    final previous = activePitches[pointer];
    if (previous != null && (previous - playbackPitch).abs() < 0.001) return;
    noteUp(pointer);
    noteDown(pointer, playbackPitch, velocity);
  }

  void noteUp(int pointer) {
    _noteEpochs[pointer] = (_noteEpochs[pointer] ?? 0) + 1;
    if (activePitches.containsKey(pointer)) {
      final nextActive = Map<int, double>.from(activePitches)..remove(pointer);
      activePitches = nextActive;
      notifyListeners();
    }
    final token = _noteTokens.remove(pointer);
    if (token != null) unawaited(_native.noteOff(token));
  }

  Future<void> releaseAllNotes() async {
    _noteEpochs.updateAll((key, value) => value + 1);
    _noteTokens.clear();
    activePitches = const {};
    _sustainedMidiPointers.clear();
    _deferredMidiOffs.clear();
    notifyListeners();
    await _native.allNotesOff();
  }

  Future<void> _loadBundledDemo() async {
    final data = await rootBundle.load('assets/scores/demo_26edo.midx');
    await loadScoreBytes(
      data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
      'UwU Funk in 26edo.midx',
    );
  }

  Future<void> _loadTuning(Uint8List bytes) async {
    final definition = TuningDefinition.fromJson(utf8.decode(bytes));
    tuning = definition;
    customTuningActive = true;
    final next = settings.copyWith(
      pitchOffsetCents: definition.displayOffsetCents,
    );
    await updateSettings(next);
    status = 'TUNING · ${definition.profile}';
    notifyListeners();
  }

  Future<void> _processDocument(NativeDocument document) async {
    if (_looksLikeTuning(document.name, document.bytes)) {
      try {
        await _loadTuning(document.bytes);
      } catch (error, stackTrace) {
        debugPrint('Tuning import failed: $error\n$stackTrace');
        _setStatus('TUNING FAILED · $error');
      }
      return;
    }
    await loadScoreBytes(document.bytes, document.name);
  }

  void _handleMidiEvent(NativeMidiEvent event) {
    final channel = event.intValue('channel').clamp(0, 15);
    final midiPitch = event.intValue('pitch').clamp(0, 127);
    final pointer = 100000 + channel * 128 + midiPitch;
    switch (event.type) {
      case 'noteOn':
        final velocity = event.intValue('velocity', 96).clamp(1, 127);
        _deferredMidiOffs.remove(pointer);
        _sustainedMidiPointers.add(pointer);
        final mapped = tuning.mapMidiPitch(midiPitch, edo: settings.edo);
        noteDown(pointer, mapped, velocity);
      case 'noteOff':
        if (_sustainByChannel[channel] == true) {
          _deferredMidiOffs.add(pointer);
        } else {
          _sustainedMidiPointers.remove(pointer);
          noteUp(pointer);
        }
      case 'sustain':
        final down = event.boolValue('down');
        _sustainByChannel[channel] = down;
        if (!down) {
          final release = _deferredMidiOffs
              .where((pointer) => (pointer - 100000) ~/ 128 == channel)
              .toList();
          for (final pointer in release) {
            _deferredMidiOffs.remove(pointer);
            _sustainedMidiPointers.remove(pointer);
            noteUp(pointer);
          }
        }
      case 'program':
        if (settings.externalMidiControlsProgram) {
          final program = event.intValue('program').clamp(0, 127);
          unawaited(updateSettings(settings.copyWith(program: program)));
        }
      case 'allNotesOff':
        unawaited(releaseAllNotes());
      case 'document':
        final bytes = event.payload['bytes'];
        if (bytes is Uint8List) {
          final name = event.payload['name']?.toString() ?? 'MIDI';
          unawaited(_processDocument(NativeDocument(name: name, bytes: bytes)));
        }
    }
  }

  void _syncClockPosition() {
    if (!_clock.isRunning) return;
    _applyClockPosition(
      _visualClockBase +
          _clock.elapsedMicroseconds / 1000000 * settings.playbackSpeed,
    );
  }

  void _startPlaybackClock() {
    _visualClockBase = visualPlayhead;
    _clock
      ..reset()
      ..start();
    _clockTimer?.cancel();
    _clockTimer = Timer.periodic(const Duration(milliseconds: 16), (_) {
      final next =
          _visualClockBase +
          _clock.elapsedMicroseconds / 1000000 * settings.playbackSpeed;
      _applyClockPosition(next);
      if (!waterfallAnimating) _stopClock();
      notifyListeners();
    });
  }

  void _applyClockPosition(double next) {
    final scoreEnd = duration;
    final waterfallEnd = _waterfallAnimationEnd;
    if (next >= scoreEnd) {
      playhead = scoreEnd;
      playing = false;
    } else {
      playhead = next;
    }
    if (next >= waterfallEnd) {
      visualPlayhead = waterfallEnd;
      waterfallAnimating = false;
    } else {
      visualPlayhead = next;
      waterfallAnimating = true;
    }
  }

  void _clearSeekGestureState() {
    _seekGestureActive = false;
    _resumeAfterSeekGesture = false;
    _seekPauseFuture = null;
  }

  void _stopClock() {
    _clock.stop();
    _clockTimer?.cancel();
    _clockTimer = null;
  }

  void _setStatus(String value) {
    status = value.toUpperCase();
    notifyListeners();
  }

  static bool _looksLikeTuning(String name, Uint8List bytes) {
    if (name.toLowerCase().endsWith('.json')) return true;
    for (final byte in bytes.take(128)) {
      if (const [9, 10, 13, 32].contains(byte)) continue;
      return byte == 0x7B;
    }
    return false;
  }

  static const _supportedDocumentExtensions = [
    'mid',
    'midi',
    'midx',
    'midix',
    'midi2',
    'kar',
    'mscz',
    'mscx',
    'json',
  ];

  // Covers the longest built-in completion burst/particle lifetime (0.70s).
  static const double _waterfallTailSeconds = 0.75;

  double get _waterfallAnimationEnd {
    final currentScore = score;
    if (currentScore == null || currentScore.notes.isEmpty) return duration;
    var lastNoteEnd = currentScore.notes.first.end;
    for (final note in currentScore.notes.skip(1)) {
      if (note.end > lastNoteEnd) lastNoteEnd = note.end;
    }
    final noteEffectsEnd = lastNoteEnd + _waterfallTailSeconds;
    return noteEffectsEnd > duration ? noteEffectsEnd : duration;
  }

  static double _initialPlayhead(ParsedScore score) {
    final first = score.notes.isEmpty ? null : score.notes.first.start;
    if (first == null || first * 160 >= 36) return 0;
    return first - 36 / 160;
  }

  Map<String, Object?> _nativeScoreMap(
    ParsedScore score,
    XenSynthSettings settings,
  ) {
    if (!settings.shouldSnapPlaybackPitch) return score.toNativeMap();
    return score.toNativeMap(
      pitchMapper: _pitchSnapLayoutFor(settings).snapPitch,
    );
  }

  double _playbackPitch(double pitch, XenSynthSettings settings) {
    if (!settings.shouldSnapPlaybackPitch) return pitch;
    return _pitchSnapLayoutFor(settings).snapPitch(pitch);
  }

  HexaKeyboardLayout _pitchSnapLayoutFor(XenSynthSettings settings) {
    final configuration = settings.hexKeyboardConfiguration;
    final cached = _pitchSnapLayout;
    if (cached != null && configuration == _pitchSnapConfiguration) {
      return cached;
    }
    _pitchSnapConfiguration = configuration;
    return _pitchSnapLayout = HexaKeyboardLayoutEngine.build(configuration);
  }

  static bool _pitchSnapMappingChanged(
    XenSynthSettings previous,
    XenSynthSettings next,
  ) {
    if (previous.shouldSnapPlaybackPitch != next.shouldSnapPlaybackPitch) {
      return true;
    }
    return next.shouldSnapPlaybackPitch &&
        previous.hexKeyboardConfiguration != next.hexKeyboardConfiguration;
  }

  @override
  void dispose() {
    _clearSeekGestureState();
    _stopClock();
    _settingsSaveTimer?.cancel();
    _midiSubscription?.cancel();
    unawaited(_native.stop());
    super.dispose();
  }
}
