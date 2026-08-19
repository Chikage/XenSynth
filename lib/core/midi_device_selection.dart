/// Stable identity helpers for MIDI selection across input and output lists.
///
/// Native platforms provide a more precise `targetId` when one is available.
/// This fallback keeps persisted Android and AppleMIDI identifiers mutually
/// exclusive before device metadata has been refreshed.
String midiTargetIdentity(String id) {
  final value = id.trim();
  if (value.isEmpty) return value;

  if (value.startsWith('applemidi:')) return value;
  if (value.startsWith('network:')) {
    return _networkTargetIdentity(value.substring('network:'.length));
  }

  const inputPrefix = 'android-midi-input:';
  const outputPrefix = 'android-midi-output:';
  if (value.startsWith(inputPrefix)) {
    return _androidTargetIdentity(value.substring(inputPrefix.length));
  }
  if (value.startsWith(outputPrefix)) {
    return _androidTargetIdentity(value.substring(outputPrefix.length));
  }
  if (value.startsWith('bluetooth:')) {
    return _androidTargetIdentity(value.substring('bluetooth:'.length));
  }
  return value;
}

bool isSameMidiTarget(String left, String right) {
  final leftIdentity = midiTargetIdentity(left);
  final rightIdentity = midiTargetIdentity(right);
  return leftIdentity.isNotEmpty && leftIdentity == rightIdentity;
}

List<String> singleMidiDeviceId(Iterable<String> ids) {
  for (final id in ids) {
    final normalized = id.trim();
    if (normalized.isNotEmpty) return <String>[normalized];
  }
  return const <String>[];
}

String _androidTargetIdentity(String value) {
  final normalized = value.trim();
  if (normalized.startsWith('bluetooth:')) {
    final address = normalized.substring('bluetooth:'.length).trim();
    if (RegExp(r'^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$').hasMatch(address)) {
      return 'bluetooth:${address.toUpperCase()}';
    }
    return 'android-midi:${address.split(':').first}';
  }
  return 'android-midi:${normalized.split(':').first}';
}

String _networkTargetIdentity(String value) {
  final normalized = value.trim().toLowerCase();
  if (normalized.startsWith('[')) {
    final closing = normalized.indexOf(']');
    if (closing > 0) return normalized.substring(1, closing);
  }
  final separator = normalized.lastIndexOf(':');
  if (separator > 0 &&
      int.tryParse(normalized.substring(separator + 1)) != null) {
    return normalized.substring(0, separator);
  }
  return normalized;
}
