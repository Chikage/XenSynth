import 'dart:async';
import 'package:flutter/services.dart';

class NativeDocument {
  const NativeDocument({required this.name, required this.bytes, this.path});

  final String name;
  final Uint8List bytes;
  final String? path;

  static NativeDocument? fromMessage(Object? message) {
    if (message is! Map) return null;
    final map = Map<Object?, Object?>.from(message);
    final rawBytes = map['bytes'];
    final bytes = switch (rawBytes) {
      Uint8List value => value,
      ByteData value => value.buffer.asUint8List(),
      List value => Uint8List.fromList(value.cast<int>()),
      _ => null,
    };
    if (bytes == null) return null;
    return NativeDocument(
      name: map['name']?.toString() ?? 'Untitled',
      path: map['path']?.toString(),
      bytes: bytes,
    );
  }
}

class NativeMidiEvent {
  const NativeMidiEvent({required this.type, required this.payload});

  final String type;
  final Map<String, Object?> payload;

  static NativeMidiEvent? fromMessage(Object? message) {
    if (message is! Map) return null;
    final map = Map<Object?, Object?>.from(message);
    final type = map['type']?.toString();
    if (type == null || type.isEmpty) return null;
    return NativeMidiEvent(
      type: type,
      payload: map.map((key, value) => MapEntry(key.toString(), value)),
    );
  }

  int intValue(String key, [int fallback = 0]) {
    final value = payload[key];
    return value is num ? value.toInt() : int.tryParse('$value') ?? fallback;
  }

  bool boolValue(String key, [bool fallback = false]) {
    final value = payload[key];
    if (value is bool) return value;
    if (value is num) return value != 0;
    return switch (value?.toString().toLowerCase()) {
      'true' || 'yes' || '1' => true,
      'false' || 'no' || '0' => false,
      _ => fallback,
    };
  }
}

class XenSynthNativeBridge {
  XenSynthNativeBridge._() {
    _methods.setMethodCallHandler(_handleNativeMethodCall);
  }

  static final XenSynthNativeBridge instance = XenSynthNativeBridge._();

  static const MethodChannel _methods = MethodChannel(
    'icu.ringona.xensynth/platform',
  );
  static const EventChannel _midiEvents = EventChannel(
    'icu.ringona.xensynth/platform/midi',
  );
  final StreamController<NativeDocument> _openedDocuments =
      StreamController<NativeDocument>.broadcast();

  Stream<NativeMidiEvent> get midiEvents => _midiEvents
      .receiveBroadcastStream()
      .map(NativeMidiEvent.fromMessage)
      .where((event) => event != null)
      .cast<NativeMidiEvent>();

  Stream<NativeDocument> get openedDocuments => _openedDocuments.stream;

  Future<bool> initializeAudio() async {
    return _boolResult(await _invoke('initializeAudio'), fallback: false);
  }

  Future<NativeDocument?> pickDocument({
    required String kind,
    required List<String> extensions,
  }) async {
    final result = await _invoke('pickDocument', <String, Object?>{
      'kind': kind,
      'extensions': extensions,
    });
    return NativeDocument.fromMessage(result);
  }

  Future<Map<String, Object?>> loadSettings() async {
    final result = await _invoke('loadSettings');
    if (result is! Map) return const {};
    return result.map((key, value) => MapEntry(key.toString(), value));
  }

  Future<void> saveSettings(Map<String, Object?> settings) async {
    await _invoke('saveSettings', settings);
  }

  Future<void> loadScore(Map<String, Object?> score) async {
    await _invoke('loadScore', score);
  }

  Future<Uint8List?> convertMuseScore({
    required String name,
    required Uint8List bytes,
  }) async {
    final result = await _invoke('convertMuseScore', <String, Object?>{
      'name': name,
      'bytes': bytes,
    });
    if (result is Uint8List) return result;
    if (result is ByteData) return result.buffer.asUint8List();
    if (result is List) return Uint8List.fromList(result.cast<int>());
    if (result is Map) {
      return NativeDocument.fromMessage(result)?.bytes;
    }
    return null;
  }

  Future<void> play({
    required double from,
    required double speed,
    required double offsetCents,
    required double audioStartDelaySeconds,
  }) async {
    await _invoke('play', <String, Object?>{
      'from': from,
      'speed': speed,
      'offsetCents': offsetCents,
      'audioStartDelaySeconds': audioStartDelaySeconds,
    });
  }

  Future<void> pause() => _invokeVoid('pause');

  Future<void> seek(double position) {
    return _invokeVoid('seek', <String, Object?>{'position': position});
  }

  Future<void> stop() => _invokeVoid('stop');

  Future<int?> noteOn({
    int? id,
    required double pitch,
    required int velocity,
    int channel = 0,
    int program = 0,
    int bankMsb = 0,
    int bankLsb = 0,
  }) async {
    final result = await _invoke('noteOn', <String, Object?>{
      'id': ?id,
      'pitch': pitch,
      'velocity': velocity,
      'channel': channel,
      'program': program,
      'bankMsb': bankMsb,
      'bankLsb': bankLsb,
    });
    return result is num ? result.toInt() : int.tryParse('$result');
  }

  Future<void> noteOff(int token) {
    return _invokeVoid('noteOff', <String, Object?>{'token': token});
  }

  Future<void> allNotesOff() => _invokeVoid('allNotesOff');

  Future<Map<String, Object?>> getPitchRecognitionState() async {
    return _stringKeyedMap(await _invoke('getPitchRecognitionState'));
  }

  Future<Map<String, Object?>> startPitchRecognition({
    required String mode,
    bool downloadIfNeeded = false,
  }) async {
    return _stringKeyedMap(
      await _invoke('startPitchRecognition', <String, Object?>{
        'mode': mode,
        'downloadIfNeeded': downloadIfNeeded,
      }),
    );
  }

  Future<Map<String, Object?>> stopPitchRecognition() async {
    return _stringKeyedMap(await _invoke('stopPitchRecognition'));
  }

  Future<void> setPitchRecognitionSensitivity(double sensitivity) {
    return _invokeVoid('setPitchRecognitionSensitivity', <String, Object?>{
      'sensitivity': sensitivity,
    });
  }

  Future<bool> playPitchRecording({double from = 0}) async {
    return _boolResult(
      await _invoke('playPitchRecording', <String, Object?>{'from': from}),
      fallback: false,
    );
  }

  Future<void> pausePitchRecording() => _invokeVoid('pausePitchRecording');

  Future<void> stopPitchRecording() => _invokeVoid('stopPitchRecording');

  Future<Map<String, Object?>> savePitchRecording({
    required String suggestedName,
    required double duration,
    required List<Map<String, Object?>> notes,
  }) async {
    return _stringKeyedMap(
      await _invoke('savePitchRecording', <String, Object?>{
        'suggestedName': suggestedName,
        'duration': duration,
        'notes': notes,
      }),
    );
  }

  Future<void> setGain(double gain) {
    return _invokeVoid('setGain', <String, Object?>{'gain': gain});
  }

  Future<void> setReverb(double mix) {
    return _invokeVoid('setReverb', <String, Object?>{'mix': mix});
  }

  Future<void> setLatency(double milliseconds) {
    return _invokeVoid('setLatency', <String, Object?>{
      'milliseconds': milliseconds,
    });
  }

  Future<void> setProgram({required int program, int channel = 0}) {
    return _invokeVoid('setProgram', <String, Object?>{
      'program': program,
      'channel': channel,
    });
  }

  Future<Object?> _handleNativeMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'documentOpened':
        final document = NativeDocument.fromMessage(call.arguments);
        if (document == null) return false;
        _openedDocuments.add(document);
        return true;
      case 'onPlaybackComplete':
        return true;
      default:
        throw MissingPluginException(
          'Unsupported native callback: ${call.method}',
        );
    }
  }

  Future<Object?> _invoke(String method, [Object? arguments]) async {
    try {
      return await _methods.invokeMethod<Object?>(method, arguments);
    } on MissingPluginException {
      return null;
    } on PlatformException {
      rethrow;
    }
  }

  Future<void> _invokeVoid(String method, [Object? arguments]) async {
    await _invoke(method, arguments);
  }

  static bool _boolResult(Object? value, {required bool fallback}) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    return switch (value?.toString().toLowerCase()) {
      'true' || 'yes' || '1' => true,
      'false' || 'no' || '0' => false,
      _ => fallback,
    };
  }

  static Map<String, Object?> _stringKeyedMap(Object? value) {
    if (value is! Map) return const {};
    return value.map((key, value) => MapEntry(key.toString(), value));
  }
}
