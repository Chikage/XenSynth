import 'dart:typed_data';

class SpectrumFrame {
  SpectrumFrame({required this.time, required Float32List magnitudes})
    : magnitudes = Float32List.fromList(magnitudes);

  final double time;
  final Float32List magnitudes;
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
