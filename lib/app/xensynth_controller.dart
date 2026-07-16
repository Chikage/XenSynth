import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

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

  XenSynthSettings settings = const XenSynthSettings();
  TuningDefinition tuning = TuningDefinition.standard;
  ParsedScore? score;
  String status = 'INITIALIZING';
  bool initialized = false;
  bool loading = false;
  bool audioReady = false;
  bool playing = false;
  double playhead = 0;
  Map<int, double> activePitches = const {};
  double _clockBase = 0;

  double get duration => score?.duration ?? 0;
  String get scoreTitle => score?.title ?? 'XEN SYNTH';
  String get tuningLabel => tuning.profile.isEmpty ? 'TUN' : tuning.profile;

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
        extensions: const [
          'mid',
          'midi',
          'midx',
          'midix',
          'midi2',
          'kar',
          'mscz',
          'mscx',
          'json',
        ],
      );
      if (document == null) return;
      if (_looksLikeTuning(document.name, document.bytes)) {
        await _loadTuning(document.bytes);
      } else {
        await loadScoreBytes(document.bytes, document.name);
      }
    } on PlatformException catch (error) {
      _setStatus(error.message ?? error.code);
    } catch (error) {
      _setStatus('OPEN FAILED · $error');
    }
  }

  Future<void> importTuning() async {
    try {
      final document = await _native.pickDocument(
        kind: 'tuning',
        extensions: const ['json'],
      );
      if (document != null) await _loadTuning(document.bytes);
    } catch (error) {
      _setStatus('TUNING FAILED · $error');
    }
  }

  Future<void> loadScoreBytes(Uint8List bytes, String title) async {
    loading = true;
    playing = false;
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
      await _native.loadScore(parsed.toNativeMap());
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
    if (playhead >= currentScore.duration - 0.001) playhead = 0;
    playing = true;
    _clockBase = playhead;
    _clock
      ..reset()
      ..start();
    _clockTimer?.cancel();
    _clockTimer = Timer.periodic(const Duration(milliseconds: 16), (_) {
      final next =
          _clockBase +
          _clock.elapsedMicroseconds / 1000000 * settings.playbackSpeed;
      if (next >= duration) {
        playhead = duration;
        playing = false;
        _stopClock();
        unawaited(_native.stop());
      } else {
        playhead = next;
      }
      notifyListeners();
    });
    notifyListeners();
    try {
      await _native.play(
        from: playhead,
        speed: settings.playbackSpeed,
        offsetCents: settings.pitchOffsetCents,
        audioStartDelaySeconds: settings.audioLatencyMs / 1000,
      );
    } catch (error) {
      playing = false;
      _stopClock();
      _setStatus('PLAYBACK FAILED · $error');
    }
  }

  Future<void> pause() async {
    _syncClockPosition();
    playing = false;
    _stopClock();
    notifyListeners();
    await _native.pause();
  }

  Future<void> seek(double position) async {
    playhead = position.clamp(0, duration).toDouble();
    if (playing) {
      _clockBase = playhead;
      _clock
        ..reset()
        ..start();
    }
    notifyListeners();
    await _native.seek(playhead);
  }

  Future<void> resetPlayback() async {
    playing = false;
    _stopClock();
    playhead = 0;
    notifyListeners();
    await _native.seek(0);
    await _native.pause();
  }

  Future<void> stop() async {
    playing = false;
    _stopClock();
    playhead = 0;
    await releaseAllNotes();
    await _native.stop();
    notifyListeners();
  }

  Future<void> updateSettings(XenSynthSettings next) async {
    final previous = settings;
    settings = next;
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
    if (next.playbackSpeed != previous.playbackSpeed && playing) {
      _syncClockPosition();
      _clockBase = playhead;
      _clock
        ..reset()
        ..start();
      await _native.play(
        from: playhead,
        speed: next.playbackSpeed,
        offsetCents: next.pitchOffsetCents,
        audioStartDelaySeconds: next.audioLatencyMs / 1000,
      );
    }
    if (next.pitchOffsetCents != previous.pitchOffsetCents && playing) {
      await _native.play(
        from: playhead,
        speed: next.playbackSpeed,
        offsetCents: next.pitchOffsetCents,
        audioStartDelaySeconds: next.audioLatencyMs / 1000,
      );
    }
  }

  Future<void> resetSettings() async {
    await updateSettings(const XenSynthSettings());
    _setStatus('DEFAULTS RESTORED');
  }

  void noteDown(int pointer, double pitch, int velocity) {
    final targetPitch = pitch + settings.pitchOffsetCents / 100;
    final nextActive = Map<int, double>.from(activePitches)..[pointer] = pitch;
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
    final previous = activePitches[pointer];
    if (previous != null && (previous - pitch).abs() < 0.001) return;
    noteUp(pointer);
    noteDown(pointer, pitch, velocity);
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
    final next = settings.copyWith(
      pitchOffsetCents: definition.displayOffsetCents,
    );
    await updateSettings(next);
    status = 'TUNING · ${definition.profile}';
    notifyListeners();
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
          if (_looksLikeTuning(name, bytes)) {
            unawaited(_loadTuning(bytes));
          } else {
            unawaited(loadScoreBytes(bytes, name));
          }
        }
    }
  }

  void _syncClockPosition() {
    if (!_clock.isRunning) return;
    playhead =
        (_clockBase +
                _clock.elapsedMicroseconds / 1000000 * settings.playbackSpeed)
            .clamp(0, duration)
            .toDouble();
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

  static double _initialPlayhead(ParsedScore score) {
    final first = score.notes.isEmpty ? null : score.notes.first.start;
    if (first == null || first * 160 >= 36) return 0;
    return first - 36 / 160;
  }

  @override
  void dispose() {
    _stopClock();
    _settingsSaveTimer?.cancel();
    _midiSubscription?.cancel();
    unawaited(_native.stop());
    super.dispose();
  }
}
