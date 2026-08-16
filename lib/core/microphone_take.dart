import 'dart:typed_data';

class SpectrumFrame {
  SpectrumFrame({
    required this.time,
    required Float32List magnitudes,
    List<SpectrumPeak> peaks = const <SpectrumPeak>[],
  }) : magnitudes = Float32List.fromList(magnitudes),
       peaks = List<SpectrumPeak>.unmodifiable(peaks);

  final double time;
  final Float32List magnitudes;
  final List<SpectrumPeak> peaks;
}

class SpectrumPeak {
  const SpectrumPeak({required this.pitch, required this.magnitude});

  final double pitch;
  final double magnitude;
}

class PitchInputEvent {
  const PitchInputEvent({
    required this.sequence,
    required this.pointer,
    required this.pitch,
    required this.velocity,
    required this.down,
  });

  final int sequence;
  final int pointer;
  final double pitch;
  final int velocity;
  final bool down;
}
