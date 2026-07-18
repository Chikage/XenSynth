class TempoEvent {
  const TempoEvent({required this.tick, required this.usPerQuarter});

  final int tick;
  final double usPerQuarter;

  double get bpm => 60000000 / usPerQuarter;
}

class MeterEvent {
  const MeterEvent({
    required this.tick,
    required this.numerator,
    required this.denominator,
  });

  final int tick;
  final int numerator;
  final int denominator;
}

class TempoPoint {
  const TempoPoint({
    required this.tick,
    required this.second,
    required this.usPerQuarter,
  });

  final int tick;
  final double second;
  final double usPerQuarter;
}

class RawNoteEvent {
  const RawNoteEvent({
    required this.tick,
    required this.pitch,
    required this.midiPitch,
    required this.cents,
    required this.velocity,
    required this.track,
    required this.channel,
    required this.program,
    required this.bankMsb,
    required this.bankLsb,
    required this.order,
    this.pitchFloat,
  });

  final int tick;
  final int pitch;
  final double? pitchFloat;
  final int midiPitch;
  final double cents;
  final int velocity;
  final int track;
  final int channel;
  final int program;
  final int bankMsb;
  final int bankLsb;
  final int order;
}

class WaterfallNote {
  const WaterfallNote({
    required this.startTick,
    required this.endTick,
    required this.start,
    required this.end,
    required this.pitch,
    required this.midiPitch,
    required this.cents,
    required this.velocity,
    required this.channel,
    required this.track,
    required this.program,
    required this.bankMsb,
    required this.bankLsb,
  });

  final int startTick;
  final int endTick;
  final double start;
  final double end;
  final double pitch;
  final int midiPitch;
  final double cents;
  final int velocity;
  final int channel;
  final int track;
  final int program;
  final int bankMsb;
  final int bankLsb;

  double get duration => end - start;

  Map<String, Object?> toNativeMap({double? playbackPitch}) {
    final renderedPitch = playbackPitch ?? pitch;
    final renderedMidiPitch = playbackPitch == null
        ? midiPitch
        : renderedPitch.round();
    final renderedCents = playbackPitch == null
        ? cents
        : (renderedPitch - renderedMidiPitch) * 100;
    return <String, Object?>{
      'startTick': startTick,
      'endTick': endTick,
      'start': start,
      'end': end,
      'pitch': renderedPitch,
      'midiPitch': renderedMidiPitch,
      'cents': renderedCents,
      'velocity': velocity,
      'channel': channel,
      'track': track,
      'program': program,
      'bankMsb': bankMsb,
      'bankLsb': bankLsb,
    };
  }
}

class ParsedScore {
  const ParsedScore({
    required this.title,
    required this.format,
    required this.ticksPerQuarter,
    required this.tempos,
    required this.meters,
    required this.tempoMap,
    required this.rawEvents,
    required this.notes,
    required this.longNotes,
    required this.duration,
  });

  final String title;
  final String format;
  final int ticksPerQuarter;
  final List<TempoEvent> tempos;
  final List<MeterEvent> meters;
  final List<TempoPoint> tempoMap;
  final List<RawNoteEvent> rawEvents;
  final List<WaterfallNote> notes;
  final List<WaterfallNote> longNotes;
  final double duration;

  double get initialBpm => tempos.isEmpty ? 120 : tempos.first.bpm;
  MeterEvent get initialMeter => meters.isEmpty
      ? const MeterEvent(tick: 0, numerator: 4, denominator: 4)
      : meters.first;

  Map<String, Object?> toNativeMap({
    double Function(double)? pitchMapper,
  }) => <String, Object?>{
    'title': title,
    'format': format,
    'ticksPerQuarter': ticksPerQuarter,
    'duration': duration,
    'notes': notes
        .map(
          (note) =>
              note.toNativeMap(playbackPitch: pitchMapper?.call(note.pitch)),
        )
        .toList(growable: false),
  };
}
