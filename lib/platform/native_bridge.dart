import 'dart:async';
import 'package:flutter/services.dart';

import '../core/midi_device_selection.dart';

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

class NativeMidiOutput {
  const NativeMidiOutput({
    required this.id,
    required this.name,
    this.targetId,
    this.hostAddress,
    this.port,
    this.model,
    this.transport,
    this.isInput = false,
  });

  final String id;
  final String name;
  final String? targetId;
  final String? hostAddress;
  final int? port;
  final String? model;
  final String? transport;
  final bool isInput;

  bool get isNetwork {
    final normalizedTransport = transport?.trim().toLowerCase();
    return id.startsWith('applemidi:') ||
        id.startsWith('applemidi-session:') ||
        normalizedTransport == 'network' ||
        normalizedTransport == 'applemidi' ||
        normalizedTransport == 'rtp-midi';
  }

  String get selectionTargetId => targetId == null || targetId!.isEmpty
      ? midiTargetIdentity(id)
      : midiTargetIdentity(targetId!);

  String get displayName {
    final identity =
        model != null &&
            model!.isNotEmpty &&
            !name.toLowerCase().contains(model!.toLowerCase())
        ? '$name ($model)'
        : name;
    final endpoint = hostAddress == null || hostAddress!.isEmpty
        ? null
        : port == null || port! <= 0
        ? hostAddress
        : '$hostAddress:$port';
    final transportLabel = switch (transport?.toLowerCase()) {
      'usb' => 'USB',
      'bluetooth' || 'bluetoothmidi' => 'Bluetooth',
      'software' || 'virtual' => 'Software',
      'coremidi' || 'system' => 'System MIDI',
      'network' || 'applemidi' || 'rtp-midi' => 'LAN',
      _ => null,
    };
    final labeledIdentity =
        transportLabel == null ||
            identity.toLowerCase().contains(transportLabel.toLowerCase())
        ? identity
        : '$identity [$transportLabel]';
    if (endpoint == null) return labeledIdentity;
    return '$labeledIdentity - $endpoint';
  }

  static NativeMidiOutput? fromMessage(Object? message) {
    if (message is! Map) return null;
    final map = Map<Object?, Object?>.from(message);
    final id = map['id']?.toString().trim();
    if (id == null || id.isEmpty) return null;
    final name = map['name']?.toString().trim();
    final targetId = map['targetId']?.toString().trim();
    final hostAddress = map['hostAddress']?.toString().trim();
    final rawPort = map['port'];
    final port = rawPort is num
        ? rawPort.toInt()
        : int.tryParse(rawPort?.toString() ?? '');
    final model = map['model']?.toString().trim();
    final transport = map['transport']?.toString().trim();
    final isInput = switch (map['isInput']) {
      bool value => value,
      num value => value != 0,
      _ => false,
    };
    return NativeMidiOutput(
      id: id,
      name: name == null || name.isEmpty
          ? (isInput ? 'MIDI input' : 'MIDI output')
          : name,
      targetId: targetId == null || targetId.isEmpty ? null : targetId,
      hostAddress: hostAddress == null || hostAddress.isEmpty
          ? null
          : hostAddress,
      port: port,
      model: model == null || model.isEmpty ? null : model,
      transport: transport == null || transport.isEmpty ? null : transport,
      isInput: isInput,
    );
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

  Future<Map<String, Object?>> getPlaybackState() async {
    final result = await _invoke('getPlaybackState');
    if (result is! Map) return const {};
    return result.map((key, value) => MapEntry(key.toString(), value));
  }

  Future<void> releaseInputNotes() => _invokeVoid('releaseInputNotes');

  Future<int?> noteOn({
    int? id,
    required double pitch,
    required int velocity,
    int channel = 0,
    int program = 0,
    int bankMsb = 0,
    int bankLsb = 0,
    bool networkOutput = true,
    int? audioTargetTimeNanos,
  }) async {
    final result = await _invoke('noteOn', <String, Object?>{
      'id': ?id,
      'pitch': pitch,
      'velocity': velocity,
      'channel': channel,
      'program': program,
      'bankMsb': bankMsb,
      'bankLsb': bankLsb,
      'networkOutput': networkOutput,
      'audioTargetTimeNanos': ?audioTargetTimeNanos,
    });
    return result is num ? result.toInt() : int.tryParse('$result');
  }

  Future<void> noteOff(int token, {int? audioTargetTimeNanos}) {
    return _invokeVoid('noteOff', <String, Object?>{
      'token': token,
      'audioTargetTimeNanos': ?audioTargetTimeNanos,
    });
  }

  Future<void> allNotesOff({
    bool networkOutput = true,
    int? audioTargetTimeNanos,
  }) {
    return _invokeVoid('allNotesOff', <String, Object?>{
      'networkOutput': networkOutput,
      'audioTargetTimeNanos': ?audioTargetTimeNanos,
    });
  }

  Future<void> setMidiInputEnabled(bool enabled) {
    return _invokeVoid('setMidiInputEnabled', <String, Object?>{
      'enabled': enabled,
    });
  }

  Future<List<NativeMidiOutput>> getMidiInputDevices({
    bool includeNetwork = true,
  }) async {
    final result = await _invoke('getMidiInputDevices', <String, Object?>{
      'includeNetwork': includeNetwork,
    });
    if (result is! List) return const <NativeMidiOutput>[];
    return result
        .map((message) {
          if (message is Map) {
            final map = Map<Object?, Object?>.from(message)..['isInput'] = true;
            return NativeMidiOutput.fromMessage(map);
          }
          return null;
        })
        .whereType<NativeMidiOutput>()
        .toList(growable: false);
  }

  Future<List<NativeMidiOutput>> getMidiOutputDevices() async {
    final result = await _invoke('getMidiOutputDevices');
    if (result is! List) return const <NativeMidiOutput>[];
    return result
        .map(NativeMidiOutput.fromMessage)
        .whereType<NativeMidiOutput>()
        .toList(growable: false);
  }

  Future<void> setMidiInputDeviceIds(
    List<String> ids, {
    bool configured = true,
  }) {
    return _invokeVoid('setMidiInputDeviceIds', <String, Object?>{
      'ids': ids,
      'configured': configured,
    });
  }

  Future<void> setMidiOutputEnabled(bool enabled) {
    return _invokeVoid('setMidiOutputEnabled', <String, Object?>{
      'enabled': enabled,
    });
  }

  Future<void> setMidiOutputDeviceIds(List<String> ids) {
    return _invokeVoid('setMidiOutputDeviceIds', <String, Object?>{'ids': ids});
  }

  Future<void> configureNetworkMidiOutput({required bool enabled}) {
    return _invokeVoid('configureNetworkMidiOutput', <String, Object?>{
      'enabled': enabled,
    });
  }

  Future<void> configureNetworkAudio({
    required List<double> mappedPitches,
    required int program,
  }) {
    return _invokeVoid('configureNetworkAudio', <String, Object?>{
      'mappedPitches': mappedPitches,
      'program': program,
    });
  }

  Future<List<NativeMidiOutput>> scanNetworkMidiOutputs() async {
    final result = await _invoke('scanNetworkMidiOutputs');
    if (result is! List) return const <NativeMidiOutput>[];
    return result
        .map(NativeMidiOutput.fromMessage)
        .whereType<NativeMidiOutput>()
        .toList(growable: false);
  }

  Future<void> setNetworkMidiOutputIds(List<String> ids) {
    return _invokeVoid('setNetworkMidiOutputIds', <String, Object?>{
      'ids': ids,
    });
  }

  Future<List<NativeMidiOutput>> getBluetoothMidiOutputs() async {
    final result = await _invoke('getBluetoothMidiOutputs');
    if (result is! List) return const <NativeMidiOutput>[];
    return result
        .map(NativeMidiOutput.fromMessage)
        .whereType<NativeMidiOutput>()
        .toList(growable: false);
  }

  Future<void> setBluetoothMidiOutputIds(List<String> ids) {
    return _invokeVoid('setBluetoothMidiOutputIds', <String, Object?>{
      'ids': ids,
    });
  }

  Future<Map<String, Object?>> getPitchRecognitionState() async {
    return _stringKeyedMap(await _invoke('getPitchRecognitionState'));
  }

  Future<Map<String, Object?>> startPitchRecognition({
    required String mode,
  }) async {
    return _stringKeyedMap(
      await _invoke('startPitchRecognition', <String, Object?>{'mode': mode}),
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

  Future<void> discardPitchRecording() => _invokeVoid('discardPitchRecording');

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
