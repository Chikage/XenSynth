import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../core/hex_keyboard.dart';
import '../core/midi_parser.dart';
import '../core/microphone_take.dart';
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
  Timer? _recordingTimer;
  Timer? _settingsSaveTimer;
  final Stopwatch _clock = Stopwatch();
  final Stopwatch _recordingClock = Stopwatch();
  final Map<int, int> _noteTokens = {};
  final Map<int, int> _noteEpochs = {};
  final Set<int> _sustainedMidiPointers = {};
  final Set<int> _deferredMidiOffs = {};
  final Map<int, bool> _sustainByChannel = {};
  double? _pendingContinuousPitch;
  int _pendingContinuousPitchFrames = 0;
  HexKeyboardConfiguration? _pitchSnapConfiguration;
  HexaKeyboardLayout? _pitchSnapLayout;
  final List<WaterfallNote> _recordedNotes = <WaterfallNote>[];
  final List<WaterfallNote> _recordedLongNotes = <WaterfallNote>[];
  final Map<int, _RecordedOpenNote> _recordedOpenNotes =
      <int, _RecordedOpenNote>{};
  PitchRecognitionMode? _microphoneTakeMode;
  bool _microphoneTake = false;
  bool _microphoneTakeFinalized = false;
  bool _microphoneTakeSaved = false;
  bool _microphoneTakeSaveDismissed = false;
  double _microphoneTakeDuration = 0;
  int _pitchInputSequence = 0;

  XenSynthSettings settings = const XenSynthSettings();
  TuningDefinition tuning = TuningDefinition.standard;
  bool customTuningActive = false;
  ParsedScore? score;
  String status = 'INITIALIZING';
  bool initialized = false;
  bool loading = false;
  bool audioReady = false;
  bool playing = false;
  bool pitchRecognitionAvailable = false;
  bool pitchRecognitionModelReady = false;
  bool pitchRecognizing = false;
  bool pitchRecognitionBusy = false;
  double pitchRecognitionDownloadProgress = 0;
  String pitchRecognitionPhase = 'unavailable';
  String pitchRecognitionMessage = '';
  double? pitchRecognitionFrequencyHz;
  double? pitchRecognitionDetectedPitch;
  double pitchRecognitionConfidence = 0;
  bool savingMicrophoneTake = false;
  List<SpectrumFrame> spectrumFrames = <SpectrumFrame>[];
  final List<PitchInputEvent> pitchInputEvents = <PitchInputEvent>[];
  int pitchVisualizationGeneration = 0;
  // The score can finish while the waterfall still needs a short visual tail.
  bool waterfallAnimating = false;
  double playhead = 0;
  double visualPlayhead = 0;
  Map<int, double> activePitches = const {};
  Map<int, int> activePitchVelocities = const {};
  double _visualClockBase = 0;
  bool _seekGestureActive = false;
  bool _resumeAfterSeekGesture = false;
  Future<void>? _seekPauseFuture;

  double get duration =>
      _microphoneTake ? _microphoneTakeDuration : score?.duration ?? 0;
  bool get recordingTransportLocked =>
      pitchRecognitionBusy || pitchRecognizing || savingMicrophoneTake;
  bool get hasMicrophoneTake => _microphoneTake && duration > 0;
  bool get microphoneTakeReadyForSave =>
      hasMicrophoneTake &&
      _microphoneTakeFinalized &&
      !pitchRecognizing &&
      !pitchRecognitionBusy;
  bool get microphoneTakeNeedsSaving =>
      microphoneTakeReadyForSave &&
      !_microphoneTakeSaved &&
      !_microphoneTakeSaveDismissed;
  bool get showingFftSpectrum =>
      _microphoneTake && _microphoneTakeMode == PitchRecognitionMode.fft;
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
        final pitchRecognitionState = await _native.getPitchRecognitionState();
        if (pitchRecognitionState.isNotEmpty) {
          _applyPitchRecognitionState(pitchRecognitionState, notify: false);
        }
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
    if (loading || recordingTransportLocked) return;
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
    _clearMicrophoneTake();
    pitchInputEvents.clear();
    pitchVisualizationGeneration++;
    await _native.stopPitchRecording();
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
    if (duration <= 0 || loading || recordingTransportLocked) return;
    if (playing) {
      await pause();
    } else {
      await play();
    }
  }

  Future<void> play() async {
    if (recordingTransportLocked) return;
    final currentScore = score;
    if (duration <= 0 || (!_microphoneTake && currentScore == null)) return;
    _clearSeekGestureState();
    if (playhead >= duration - 0.001) playhead = 0;
    visualPlayhead = playhead;
    playing = true;
    waterfallAnimating = true;
    _startPlaybackClock();
    notifyListeners();
    try {
      if (_microphoneTake) {
        final started = await _native.playPitchRecording(from: playhead);
        if (!started) {
          throw StateError('Recorded microphone audio is unavailable');
        }
      } else {
        await _native.play(
          from: playhead,
          speed: settings.playbackSpeed,
          offsetCents: settings.appliedPitchOffsetCents,
          audioStartDelaySeconds: settings.audioLatencyMs / 1000,
        );
      }
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
    if (_microphoneTake) {
      await _native.pausePitchRecording();
    } else {
      await _native.pause();
    }
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
    if (_microphoneTake) {
      if (playing) await _native.playPitchRecording(from: playhead);
    } else {
      await _native.seek(playhead);
    }
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
    _seekPauseFuture = playing
        ? (_microphoneTake ? _native.pausePitchRecording() : _native.pause())
        : Future<void>.value();
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
    if (!_microphoneTake) await _native.seek(playhead);
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
      if (_microphoneTake) {
        final started = await _native.playPitchRecording(from: playhead);
        if (!started) {
          throw StateError('Recorded microphone audio is unavailable');
        }
      } else {
        await _native.play(
          from: playhead,
          speed: settings.playbackSpeed,
          offsetCents: settings.appliedPitchOffsetCents,
          audioStartDelaySeconds: settings.audioLatencyMs / 1000,
        );
      }
    } catch (error) {
      playing = false;
      waterfallAnimating = false;
      _stopClock();
      _setStatus('PLAYBACK FAILED · $error');
    }
  }

  Future<void> resetPlayback() async {
    if (recordingTransportLocked) return;
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    playhead = 0;
    visualPlayhead = 0;
    notifyListeners();
    if (_microphoneTake) {
      await _native.pausePitchRecording();
    } else {
      await _native.seek(0);
      await _native.pause();
    }
  }

  Future<void> stop() async {
    if (recordingTransportLocked) return;
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    playhead = 0;
    visualPlayhead = 0;
    await releaseAllNotes();
    if (_microphoneTake) {
      await _native.stopPitchRecording();
    } else {
      await _native.stop();
    }
    notifyListeners();
  }

  Future<bool> startPitchRecognition({bool downloadIfNeeded = false}) async {
    if (!pitchRecognitionAvailable) return false;
    try {
      await _prepareMicrophoneTake(settings.pitchRecognitionMode);
      await _native.setPitchRecognitionSensitivity(
        settings.microphoneSensitivity,
      );
      final state = await _native.startPitchRecognition(
        mode: settings.pitchRecognitionMode.name,
        downloadIfNeeded: downloadIfNeeded,
      );
      if (state.isNotEmpty) _applyPitchRecognitionState(state);
      final started =
          pitchRecognitionPhase != 'error' &&
          pitchRecognitionPhase != 'needsDownload';
      if (!started) _finalizeMicrophoneTake();
      return started;
    } on PlatformException catch (error) {
      _setPitchRecognitionError(error.message ?? error.code);
      return false;
    } catch (error) {
      _setPitchRecognitionError('$error');
      return false;
    }
  }

  Future<void> stopPitchRecognition() async {
    _stopRecordingTimeline();
    try {
      final state = await _native.stopPitchRecognition();
      if (state.isNotEmpty) _applyPitchRecognitionState(state);
    } catch (error) {
      debugPrint('Pitch recognition stop failed: $error');
    } finally {
      _releaseMicrophoneNotes();
      if (!_microphoneTakeFinalized) {
        pitchRecognizing = false;
        pitchRecognitionBusy = false;
        pitchRecognitionPhase = 'idle';
        _finalizeMicrophoneTake();
      }
    }
  }

  Future<bool> saveMicrophoneTake() async {
    final currentScore = score;
    if (!microphoneTakeReadyForSave ||
        savingMicrophoneTake ||
        currentScore == null) {
      return false;
    }
    savingMicrophoneTake = true;
    status = 'SAVING MICROPHONE TAKE';
    notifyListeners();
    try {
      final result = await _native.savePitchRecording(
        suggestedName: _microphoneTakeFileStem(),
        duration: duration,
        notes: currentScore.notes
            .map((note) => note.toNativeMap())
            .toList(growable: false),
      );
      final saved = _stateBool(result['saved'], false);
      if (!saved) {
        status = 'MICROPHONE TAKE SAVE FAILED';
        return false;
      }
      _microphoneTakeSaved = true;
      final directory = result['directory']?.toString();
      status = directory == null || directory.isEmpty
          ? 'MICROPHONE TAKE SAVED'
          : 'SAVED TO ${directory.toUpperCase()}';
      return true;
    } on PlatformException catch (error) {
      status = 'SAVE FAILED · ${error.message ?? error.code}';
      return false;
    } catch (error) {
      status = 'SAVE FAILED · $error';
      return false;
    } finally {
      savingMicrophoneTake = false;
      notifyListeners();
    }
  }

  void dismissMicrophoneTakeSave() {
    if (!microphoneTakeReadyForSave || _microphoneTakeSaveDismissed) return;
    _microphoneTakeSaveDismissed = true;
    status = 'MICROPHONE TAKE RETAINED';
    notifyListeners();
  }

  Future<void> discardMicrophoneTake() async {
    if (!_microphoneTake || savingMicrophoneTake) return;
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    await releaseAllNotes();
    await _native.stopPitchRecording();
    _clearMicrophoneTake();
    pitchInputEvents.clear();
    pitchVisualizationGeneration++;
    status = 'MICROPHONE TAKE DISCARDED';
    notifyListeners();
  }

  Future<void> _prepareMicrophoneTake(PitchRecognitionMode mode) async {
    playing = false;
    waterfallAnimating = false;
    _clearSeekGestureState();
    _stopClock();
    if (_microphoneTake) {
      await _native.stopPitchRecording();
    } else {
      await _native.stop();
    }
    await releaseAllNotes();
    _beginMicrophoneTake(mode);
  }

  void _beginMicrophoneTake(PitchRecognitionMode mode) {
    _stopRecordingTimeline();
    _microphoneTake = true;
    _microphoneTakeFinalized = false;
    _microphoneTakeSaved = false;
    _microphoneTakeSaveDismissed = false;
    _microphoneTakeMode = mode;
    _microphoneTakeDuration = 0;
    _recordedNotes.clear();
    _recordedLongNotes.clear();
    _recordedOpenNotes.clear();
    spectrumFrames = <SpectrumFrame>[];
    pitchInputEvents.clear();
    pitchVisualizationGeneration++;
    score = _microphoneScore(
      notes: _recordedNotes,
      longNotes: _recordedLongNotes,
      duration: 0,
    );
    playhead = 0;
    visualPlayhead = 0;
    status = 'MIC ${mode.name.toUpperCase()} READY';
    notifyListeners();
  }

  void _startRecordingTimeline() {
    if (!_microphoneTake || _recordingClock.isRunning) return;
    _recordingClock
      ..reset()
      ..start();
    waterfallAnimating = true;
    _recordingTimer?.cancel();
    _recordingTimer = Timer.periodic(const Duration(milliseconds: 32), (_) {
      _updateMicrophoneTakeTime(
        _recordingClock.elapsedMicroseconds / 1000000,
        notify: false,
      );
      _extendRecordedNotes(_microphoneTakeDuration);
      playhead = _microphoneTakeDuration;
      visualPlayhead = _microphoneTakeDuration;
      notifyListeners();
    });
  }

  void _stopRecordingTimeline() {
    _recordingClock.stop();
    _recordingTimer?.cancel();
    _recordingTimer = null;
  }

  void _finalizeMicrophoneTake([double? nativeDuration]) {
    if (!_microphoneTake || _microphoneTakeFinalized) return;
    _microphoneTakeFinalized = true;
    _stopRecordingTimeline();
    if (nativeDuration != null && nativeDuration.isFinite) {
      _microphoneTakeDuration = math.max(
        _microphoneTakeDuration,
        math.max(0.0, nativeDuration),
      );
    }
    _extendRecordedNotes(_microphoneTakeDuration);
    for (final pointer in _recordedOpenNotes.keys.toList()) {
      _recordMicrophoneNoteUp(pointer, _microphoneTakeDuration);
    }
    final notes = List<WaterfallNote>.unmodifiable(_recordedNotes);
    final longNotes = List<WaterfallNote>.unmodifiable(
      notes.where((note) => note.duration > _recordedLongNoteSeconds),
    );
    score = _microphoneScore(
      notes: notes,
      longNotes: longNotes,
      duration: _microphoneTakeDuration,
    );
    playing = false;
    waterfallAnimating = false;
    playhead = 0;
    visualPlayhead = 0;
    status = _microphoneTakeDuration > 0
        ? 'MIC ${_microphoneTakeMode?.name.toUpperCase()} RECORDED'
        : 'MIC RECORDING EMPTY';
    notifyListeners();
  }

  void _clearMicrophoneTake() {
    _stopRecordingTimeline();
    _microphoneTake = false;
    _microphoneTakeFinalized = false;
    _microphoneTakeSaved = false;
    _microphoneTakeSaveDismissed = false;
    savingMicrophoneTake = false;
    _microphoneTakeMode = null;
    _microphoneTakeDuration = 0;
    _recordedNotes.clear();
    _recordedLongNotes.clear();
    _recordedOpenNotes.clear();
    spectrumFrames = <SpectrumFrame>[];
    score = null;
  }

  ParsedScore _microphoneScore({
    required List<WaterfallNote> notes,
    required List<WaterfallNote> longNotes,
    required double duration,
  }) {
    final mode = _microphoneTakeMode?.name.toUpperCase() ?? 'MIC';
    return ParsedScore(
      title: '$mode MICROPHONE TAKE',
      format: 'MIC',
      ticksPerQuarter: _recordingTicksPerQuarter,
      tempos: const [TempoEvent(tick: 0, usPerQuarter: 500000)],
      meters: const [MeterEvent(tick: 0, numerator: 4, denominator: 4)],
      tempoMap: const [TempoPoint(tick: 0, second: 0, usPerQuarter: 500000)],
      rawEvents: const [],
      notes: notes,
      longNotes: longNotes,
      duration: duration,
    );
  }

  String _microphoneTakeFileStem() {
    final now = DateTime.now().toLocal();
    String twoDigits(int value) => value.toString().padLeft(2, '0');
    final timestamp =
        '${now.year}${twoDigits(now.month)}${twoDigits(now.day)}_'
        '${twoDigits(now.hour)}${twoDigits(now.minute)}${twoDigits(now.second)}';
    final mode = _microphoneTakeMode?.name ?? 'microphone';
    return 'XenSynth_${mode}_$timestamp';
  }

  Future<void> updateSettings(XenSynthSettings next) async {
    final previous = settings;
    final pitchRecognitionModeChanged =
        next.pitchRecognitionMode != previous.pitchRecognitionMode;
    final snapMappingChanged = _pitchSnapMappingChanged(previous, next);
    final playbackParametersChanged =
        !_microphoneTake &&
        (next.playbackSpeed != previous.playbackSpeed ||
            next.pitchOffsetCents != previous.pitchOffsetCents ||
            snapMappingChanged);
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

    if (pitchRecognitionModeChanged &&
        (pitchRecognizing || pitchRecognitionBusy)) {
      await stopPitchRecognition();
    }

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
    if (next.microphoneSensitivity != previous.microphoneSensitivity) {
      await _native.setPitchRecognitionSensitivity(next.microphoneSensitivity);
    }
    final currentScore = score;
    if (!_microphoneTake && snapMappingChanged && currentScore != null) {
      await _native.loadScore(_nativeScoreMap(currentScore, next));
    }
    if (!_microphoneTake && playing && playbackParametersChanged) {
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
    _noteDownAtPlaybackPitch(pointer, playbackPitch, velocity);
  }

  void _noteDownAtPlaybackPitch(
    int pointer,
    double playbackPitch,
    int velocity,
  ) {
    final targetPitch = playbackPitch + settings.appliedPitchOffsetCents / 100;
    final nextActive = Map<int, double>.from(activePitches)
      ..[pointer] = playbackPitch;
    activePitches = nextActive;
    _setActivePitchVelocity(pointer, velocity);
    _emitPitchInput(
      pointer: pointer,
      pitch: playbackPitch,
      velocity: velocity,
      down: true,
    );
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
    _noteMoveAtPlaybackPitch(pointer, playbackPitch, velocity);
  }

  void _noteMoveAtPlaybackPitch(
    int pointer,
    double playbackPitch,
    int velocity,
  ) {
    final previous = activePitches[pointer];
    if (previous != null && (previous - playbackPitch).abs() < 0.001) {
      if (_setActivePitchVelocity(pointer, velocity)) {
        _emitPitchInput(
          pointer: pointer,
          pitch: previous,
          velocity: velocity,
          down: true,
        );
        notifyListeners();
      }
      return;
    }
    noteUp(pointer);
    _noteDownAtPlaybackPitch(pointer, playbackPitch, velocity);
  }

  void noteUp(int pointer) {
    _noteEpochs[pointer] = (_noteEpochs[pointer] ?? 0) + 1;
    final previousPitch = activePitches[pointer];
    final hadVelocity = activePitchVelocities.containsKey(pointer);
    if (previousPitch != null) {
      final nextActive = Map<int, double>.from(activePitches)..remove(pointer);
      activePitches = nextActive;
      _emitPitchInput(
        pointer: pointer,
        pitch: previousPitch,
        velocity: 0,
        down: false,
      );
    }
    if (hadVelocity) {
      activePitchVelocities = Map<int, int>.from(activePitchVelocities)
        ..remove(pointer);
    }
    if (previousPitch != null || hadVelocity) notifyListeners();
    final token = _noteTokens.remove(pointer);
    if (token != null) unawaited(_native.noteOff(token));
  }

  Future<void> releaseAllNotes() async {
    _noteEpochs.updateAll((key, value) => value + 1);
    _noteTokens.clear();
    for (final entry in activePitches.entries) {
      _emitPitchInput(
        pointer: entry.key,
        pitch: entry.value,
        velocity: 0,
        down: false,
      );
    }
    activePitches = const {};
    activePitchVelocities = const {};
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
    if (event.type == 'pitchRecognitionState') {
      _applyPitchRecognitionState(event.payload);
      return;
    }
    if (event.type == 'pitch') {
      _handleContinuousPitch(event);
      return;
    }
    if (event.type == 'spectrum') {
      _handleSpectrum(event);
      return;
    }
    final channel = event.intValue('channel').clamp(0, 15);
    final midiPitch = event.intValue('pitch').clamp(0, 127);
    final fromMicrophone = event.payload['source'] == 'microphone';
    final pointer = fromMicrophone
        ? _microphonePointerBase + midiPitch
        : _midiPointerBase + channel * 128 + midiPitch;
    final microphoneTime = fromMicrophone ? _microphoneEventTime(event) : 0.0;
    switch (event.type) {
      case 'noteOn':
        final velocity = event.intValue('velocity', 96).clamp(1, 127);
        if (!fromMicrophone) {
          _deferredMidiOffs.remove(pointer);
          _sustainedMidiPointers.add(pointer);
        }
        final mapped = tuning.mapMidiPitch(midiPitch, edo: settings.edo);
        if (fromMicrophone) {
          _recordMicrophoneNoteDown(pointer, mapped, velocity, microphoneTime);
        }
        noteDown(pointer, mapped, velocity);
      case 'noteOff':
        if (!fromMicrophone && _sustainByChannel[channel] == true) {
          _deferredMidiOffs.add(pointer);
        } else {
          _sustainedMidiPointers.remove(pointer);
          if (fromMicrophone) {
            _recordMicrophoneNoteUp(pointer, microphoneTime);
          }
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

  void _handleContinuousPitch(NativeMidiEvent event) {
    final eventTime = _microphoneEventTime(event);
    final voiced = event.boolValue('voiced');
    final frequencyHz = _finiteDouble(event.payload['frequencyHz']);
    final rawPitch = _finiteDouble(event.payload['pitch']);
    final confidence = _finiteDouble(event.payload['confidence']);
    pitchRecognitionFrequencyHz = voiced ? frequencyHz : null;
    pitchRecognitionDetectedPitch = voiced ? rawPitch : null;
    pitchRecognitionConfidence = voiced
        ? (confidence ?? 0).clamp(0.0, 1.0).toDouble()
        : 0;

    if (!voiced || rawPitch == null || rawPitch < 0 || rawPitch > 127) {
      _resetContinuousPitchCandidate();
      if (activePitches.containsKey(_continuousMicrophonePointer)) {
        _recordMicrophoneNoteUp(_continuousMicrophonePointer, eventTime);
        noteUp(_continuousMicrophonePointer);
      } else {
        notifyListeners();
      }
      return;
    }

    final targetPitch = _continuousPitchTarget(rawPitch);
    final velocity = event.intValue('velocity', 96).clamp(1, 127);
    final currentPitch = activePitches[_continuousMicrophonePointer];
    final quantized = customTuningActive || settings.edo > 0;
    if (!quantized) {
      _resetContinuousPitchCandidate();
      if (currentPitch == null) {
        _recordMicrophoneNoteDown(
          _continuousMicrophonePointer,
          targetPitch,
          velocity,
          eventTime,
        );
        _noteDownAtPlaybackPitch(
          _continuousMicrophonePointer,
          targetPitch,
          velocity,
        );
      } else if ((currentPitch - targetPitch).abs() >= 0.05) {
        _recordMicrophoneNoteMove(
          _continuousMicrophonePointer,
          targetPitch,
          velocity,
          eventTime,
        );
        _noteMoveAtPlaybackPitch(
          _continuousMicrophonePointer,
          targetPitch,
          velocity,
        );
      } else {
        _setActivePitchVelocity(_continuousMicrophonePointer, velocity);
        _emitPitchInput(
          pointer: _continuousMicrophonePointer,
          pitch: currentPitch,
          velocity: velocity,
          down: true,
        );
        notifyListeners();
      }
      return;
    }

    if (currentPitch != null && (currentPitch - targetPitch).abs() < 0.001) {
      _resetContinuousPitchCandidate();
      _setActivePitchVelocity(_continuousMicrophonePointer, velocity);
      _emitPitchInput(
        pointer: _continuousMicrophonePointer,
        pitch: currentPitch,
        velocity: velocity,
        down: true,
      );
      notifyListeners();
      return;
    }
    if (_pendingContinuousPitch != null &&
        (_pendingContinuousPitch! - targetPitch).abs() < 0.001) {
      _pendingContinuousPitchFrames++;
    } else {
      _pendingContinuousPitch = targetPitch;
      _pendingContinuousPitchFrames = 1;
    }
    if (_pendingContinuousPitchFrames < 2) {
      notifyListeners();
      return;
    }
    _resetContinuousPitchCandidate();
    if (currentPitch == null) {
      _recordMicrophoneNoteDown(
        _continuousMicrophonePointer,
        targetPitch,
        velocity,
        eventTime,
      );
      _noteDownAtPlaybackPitch(
        _continuousMicrophonePointer,
        targetPitch,
        velocity,
      );
    } else {
      _recordMicrophoneNoteMove(
        _continuousMicrophonePointer,
        targetPitch,
        velocity,
        eventTime,
      );
      _noteMoveAtPlaybackPitch(
        _continuousMicrophonePointer,
        targetPitch,
        velocity,
      );
    }
  }

  void _handleSpectrum(NativeMidiEvent event) {
    if (!_microphoneTake || _microphoneTakeMode != PitchRecognitionMode.fft) {
      return;
    }
    final rawMagnitudes = event.payload['magnitudes'];
    final magnitudes = switch (rawMagnitudes) {
      Float32List value => value,
      Float64List value => Float32List.fromList(value),
      List value => Float32List.fromList(
        value.whereType<num>().map((number) => number.toDouble()).toList(),
      ),
      _ => null,
    };
    if (magnitudes == null || magnitudes.isEmpty) return;
    final time = _microphoneEventTime(event);
    spectrumFrames.add(SpectrumFrame(time: time, magnitudes: magnitudes));
    notifyListeners();
  }

  double _microphoneEventTime(NativeMidiEvent event) {
    final value = _finiteDouble(event.payload['time']);
    final time = value == null || value < 0 ? _microphoneTakeDuration : value;
    _updateMicrophoneTakeTime(time, notify: false);
    return time;
  }

  void _updateMicrophoneTakeTime(double time, {bool notify = true}) {
    if (!_microphoneTake || !time.isFinite || time <= _microphoneTakeDuration) {
      return;
    }
    _microphoneTakeDuration = time;
    if (pitchRecognizing) {
      playhead = time;
      visualPlayhead = time;
    }
    if (notify) notifyListeners();
  }

  void _recordMicrophoneNoteDown(
    int pointer,
    double pitch,
    int velocity,
    double time,
  ) {
    if (!_microphoneTake || _microphoneTakeMode == PitchRecognitionMode.fft) {
      return;
    }
    _recordMicrophoneNoteUp(pointer, time);
    final safeTime = math.max(0.0, time);
    final note = _recordedWaterfallNote(
      start: safeTime,
      end: safeTime + _recordedMinimumNoteSeconds,
      pitch: pitch,
      velocity: velocity,
    );
    final index = _recordedNotes.length;
    _recordedNotes.add(note);
    _recordedOpenNotes[pointer] = _RecordedOpenNote(index: index);
    _updateMicrophoneTakeTime(safeTime, notify: false);
  }

  void _recordMicrophoneNoteMove(
    int pointer,
    double pitch,
    int velocity,
    double time,
  ) {
    _recordMicrophoneNoteUp(pointer, time);
    _recordMicrophoneNoteDown(pointer, pitch, velocity, time);
  }

  void _recordMicrophoneNoteUp(int pointer, double time) {
    final open = _recordedOpenNotes.remove(pointer);
    if (open == null) return;
    _setRecordedNoteEnd(open.index, time);
    _updateMicrophoneTakeTime(time, notify: false);
  }

  void _extendRecordedNotes(double time) {
    for (final open in _recordedOpenNotes.values) {
      _setRecordedNoteEnd(open.index, time);
    }
  }

  void _setRecordedNoteEnd(int index, double time) {
    if (index < 0 || index >= _recordedNotes.length) return;
    final previous = _recordedNotes[index];
    final end = math.max(
      previous.start + _recordedMinimumNoteSeconds,
      math.max(0.0, time),
    );
    _recordedNotes[index] = WaterfallNote(
      startTick: previous.startTick,
      endTick: _recordingTick(end),
      start: previous.start,
      end: end,
      pitch: previous.pitch,
      midiPitch: previous.midiPitch,
      cents: previous.cents,
      velocity: previous.velocity,
      channel: previous.channel,
      track: previous.track,
      program: previous.program,
      bankMsb: previous.bankMsb,
      bankLsb: previous.bankLsb,
    );
  }

  WaterfallNote _recordedWaterfallNote({
    required double start,
    required double end,
    required double pitch,
    required int velocity,
  }) {
    final midiPitch = pitch.round().clamp(0, 127);
    return WaterfallNote(
      startTick: _recordingTick(start),
      endTick: _recordingTick(end),
      start: start,
      end: end,
      pitch: pitch.clamp(0.0, 127.0).toDouble(),
      midiPitch: midiPitch,
      cents: (pitch - midiPitch) * 100,
      velocity: velocity.clamp(1, 127),
      channel: 0,
      track: 0,
      program: settings.program,
      bankMsb: 0,
      bankLsb: 0,
    );
  }

  void _emitPitchInput({
    required int pointer,
    required double pitch,
    required int velocity,
    required bool down,
  }) {
    pitchInputEvents.add(
      PitchInputEvent(
        sequence: ++_pitchInputSequence,
        pointer: pointer,
        pitch: pitch.clamp(0.0, 127.0).toDouble(),
        velocity: velocity.clamp(0, 127),
        down: down,
      ),
    );
    const retainedEventCount = 256;
    if (pitchInputEvents.length > retainedEventCount) {
      pitchInputEvents.removeRange(
        0,
        pitchInputEvents.length - retainedEventCount,
      );
    }
  }

  bool _setActivePitchVelocity(int pointer, int velocity) {
    final safeVelocity = velocity.clamp(1, 127);
    if (activePitchVelocities[pointer] == safeVelocity) return false;
    activePitchVelocities = Map<int, int>.from(activePitchVelocities)
      ..[pointer] = safeVelocity;
    return true;
  }

  static int _recordingTick(double time) =>
      (time * _recordingTicksPerSecond).round();

  double _continuousPitchTarget(double rawPitch) {
    if (customTuningActive) {
      final candidates = tuning.visiblePitches();
      if (candidates.isNotEmpty) {
        var nearest = candidates.first;
        var nearestDistance = (rawPitch - nearest).abs();
        for (final candidate in candidates.skip(1)) {
          final distance = (rawPitch - candidate).abs();
          if (distance < nearestDistance) {
            nearest = candidate;
            nearestDistance = distance;
          }
        }
        return nearest;
      }
    }
    final edo = settings.edo;
    if (edo > 0) {
      return (60 + ((rawPitch - 60) * edo / 12).round() * 12 / edo)
          .clamp(0.0, 127.0)
          .toDouble();
    }
    return rawPitch.clamp(0.0, 127.0).toDouble();
  }

  void _resetContinuousPitchCandidate() {
    _pendingContinuousPitch = null;
    _pendingContinuousPitchFrames = 0;
  }

  void _applyPitchRecognitionState(
    Map<String, Object?> state, {
    bool notify = true,
  }) {
    final wasActive = pitchRecognizing || pitchRecognitionBusy;
    pitchRecognitionAvailable = _stateBool(
      state['supported'],
      pitchRecognitionAvailable,
    );
    pitchRecognitionModelReady = _stateBool(
      state['modelReady'],
      pitchRecognitionModelReady,
    );
    pitchRecognizing = _stateBool(state['recognizing'], false);
    pitchRecognitionBusy = _stateBool(state['busy'], false);
    pitchRecognitionDownloadProgress = switch (state['progress']) {
      num value => value.toDouble().clamp(0.0, 1.0).toDouble(),
      String value =>
        (double.tryParse(value) ?? pitchRecognitionDownloadProgress)
            .clamp(0.0, 1.0)
            .toDouble(),
      _ => pitchRecognitionDownloadProgress,
    };
    pitchRecognitionPhase = state['phase']?.toString() ?? pitchRecognitionPhase;
    pitchRecognitionMessage = state['message']?.toString() ?? '';
    final recordingDuration = _finiteDouble(state['recordingDuration']);
    if (pitchRecognitionPhase == 'error' &&
        pitchRecognitionMessage.isNotEmpty) {
      status = 'MIC · ${pitchRecognitionMessage.toUpperCase()}';
    }
    if (!pitchRecognizing &&
        const {
          'idle',
          'error',
          'unavailable',
        }.contains(pitchRecognitionPhase)) {
      _releaseMicrophoneNotes(notify: false);
    }
    if (pitchRecognizing) {
      _startRecordingTimeline();
    } else if (wasActive &&
        const {
          'idle',
          'error',
          'unavailable',
        }.contains(pitchRecognitionPhase)) {
      _finalizeMicrophoneTake(recordingDuration);
    }
    if (notify) notifyListeners();
  }

  void _setPitchRecognitionError(String value) {
    pitchRecognitionPhase = 'error';
    pitchRecognitionBusy = false;
    pitchRecognizing = false;
    pitchRecognitionMessage = value;
    status = 'MIC · ${value.toUpperCase()}';
    _releaseMicrophoneNotes(notify: false);
    _finalizeMicrophoneTake();
    notifyListeners();
  }

  void _releaseMicrophoneNotes({bool notify = true}) {
    pitchRecognitionFrequencyHz = null;
    pitchRecognitionDetectedPitch = null;
    pitchRecognitionConfidence = 0;
    _resetContinuousPitchCandidate();
    final pointers = activePitches.keys
        .where(
          (pointer) =>
              pointer >= _microphonePointerBase &&
              pointer <= _continuousMicrophonePointer,
        )
        .toList();
    if (pointers.isEmpty) {
      if (notify) notifyListeners();
      return;
    }
    for (final pointer in pointers) {
      final pitch = activePitches[pointer];
      if (pitch != null) {
        _emitPitchInput(
          pointer: pointer,
          pitch: pitch,
          velocity: 0,
          down: false,
        );
      }
      _noteEpochs[pointer] = (_noteEpochs[pointer] ?? 0) + 1;
      _sustainedMidiPointers.remove(pointer);
      _deferredMidiOffs.remove(pointer);
      final token = _noteTokens.remove(pointer);
      if (token != null) unawaited(_native.noteOff(token));
    }
    final nextActive = Map<int, double>.from(activePitches)
      ..removeWhere((pointer, _) => pointers.contains(pointer));
    activePitches = nextActive;
    activePitchVelocities = Map<int, int>.from(activePitchVelocities)
      ..removeWhere((pointer, _) => pointers.contains(pointer));
    if (notify) notifyListeners();
  }

  static bool _stateBool(Object? value, bool fallback) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    return switch (value?.toString().toLowerCase()) {
      'true' || 'yes' || '1' => true,
      'false' || 'no' || '0' => false,
      _ => fallback,
    };
  }

  static double? _finiteDouble(Object? value) {
    final parsed = value is num ? value.toDouble() : double.tryParse('$value');
    return parsed != null && parsed.isFinite ? parsed : null;
  }

  void _syncClockPosition() {
    if (!_clock.isRunning) return;
    _applyClockPosition(
      _visualClockBase +
          _clock.elapsedMicroseconds / 1000000 * _playbackClockSpeed,
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
          _clock.elapsedMicroseconds / 1000000 * _playbackClockSpeed;
      _applyClockPosition(next);
      if (!waterfallAnimating) _stopClock();
      notifyListeners();
    });
  }

  double get _playbackClockSpeed =>
      _microphoneTake ? 1.0 : settings.playbackSpeed;

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

  static const int _midiPointerBase = 100000;
  static const int _microphonePointerBase = 200000;
  static const int _continuousMicrophonePointer = _microphonePointerBase + 128;
  static const int _recordingTicksPerQuarter = 480;
  static const double _recordingTicksPerSecond = 960;
  static const double _recordedMinimumNoteSeconds = 0.04;
  static const double _recordedLongNoteSeconds = 8;

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
    _stopRecordingTimeline();
    _settingsSaveTimer?.cancel();
    _midiSubscription?.cancel();
    unawaited(_native.stopPitchRecognition());
    unawaited(_native.stopPitchRecording());
    unawaited(_native.stop());
    super.dispose();
  }
}

class _RecordedOpenNote {
  const _RecordedOpenNote({required this.index});

  final int index;
}
