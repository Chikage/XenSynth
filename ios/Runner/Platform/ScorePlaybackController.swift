import Foundation

final class ScorePlaybackController {
  private struct MIDIBank: Hashable {
    let msb: UInt8
    let lsb: UInt8

    var fluidBank: Int { Int(msb) * 128 + Int(lsb) }
  }

  private struct VoiceKey: Hashable {
    private static let gmMelodicBank = MIDIBank(msb: 0, lsb: 0)
    private static let gmPercussionBank = MIDIBank(msb: 1, lsb: 0)

    let channel: UInt8
    let program: UInt8
    let midiBank: MIDIBank
    let isPercussion: Bool

    init(note: NativeScoreNote) {
      self.init(
        channel: note.channel,
        program: note.program,
        bankMsb: note.bankMsb,
        bankLsb: note.bankLsb
      )
    }

    init(channel: Int, program: Int, bankMsb: Int, bankLsb: Int) {
      self.channel = UInt8(channel.clamped(to: 0...15))
      self.program = UInt8(program.clamped(to: 0...127))
      midiBank = MIDIBank(
        msb: UInt8(bankMsb.clamped(to: 0...127)),
        lsb: UInt8(bankLsb.clamped(to: 0...127))
      )
      isPercussion = channel == 9 || (bankMsb == 1 && bankLsb == 0)
    }

    var fluidInstrument: FluidSynthInstrument {
      let requested = FluidSynthPreset(bank: midiBank.fluidBank, program: Int(program))
      if isPercussion {
        var presets: [FluidSynthPreset] = []
        if midiBank != Self.gmMelodicBank { presets.append(requested) }
        presets.append(FluidSynthPreset(bank: Self.gmPercussionBank.fluidBank, program: Int(program)))
        presets.append(FluidSynthPreset(bank: Self.gmPercussionBank.fluidBank, program: 0))
        return FluidSynthInstrument(presets: presets.uniqued())
      }

      return FluidSynthInstrument(presets: [
        requested,
        FluidSynthPreset(bank: Self.gmMelodicBank.fluidBank, program: Int(program)),
        FluidSynthPreset(bank: Self.gmMelodicBank.fluidBank, program: 0),
      ].uniqued())
    }
  }

  private struct ScheduledEvent {
    enum Kind: Int {
      case noteOff = 0
      case noteOn = 1
    }

    let time: Double
    let kind: Kind
    let key: VoiceKey
    let playbackChannel: UInt8
    let noteNumber: UInt8
    let velocity: UInt8
    let pitchBend: UInt16
  }

  private struct ActiveNote: Hashable {
    let key: VoiceKey
    let playbackChannel: UInt8
    let noteNumber: UInt8
  }

  private struct PlayableNote {
    let key: VoiceKey
    let start: Double
    let stopTime: Double
    let noteNumber: UInt8
    let velocity: UInt8
    let pitchBend: UInt16
    let order: Int
  }

  private struct LaneActiveNote {
    let key: VoiceKey
    let end: Double
    let noteNumber: UInt8
    let pitchBend: UInt16
  }

  private struct PlaybackLane {
    let channel: UInt8
    var activeNotes: [LaneActiveNote] = []

    func canHost(_ note: PlayableNote) -> Bool {
      activeNotes.allSatisfy {
        $0.key == note.key &&
          $0.pitchBend == note.pitchBend &&
          $0.noteNumber != note.noteNumber
      }
    }
  }

  private let synth = FluidSynthEngine.shared
  private var events: [ScheduledEvent] = []
  private var nextEventIndex = 0
  private var activeNotes: Set<ActiveNote> = []
  private var activePreviewNotes: [Int: ActiveNote] = [:]
  private var nextPreviewToken = 1
  private var playbackTimer: DispatchSourceTimer?
  private var scoreStartTime = 0.0
  private var wallStartDate = Date()
  private var playbackSpeed = 1.0
  private var audioStartDelayScoreSeconds = 0.0
  private var onFinished: (() -> Void)?
  private var volumeGain = ScorePlaybackController.defaultVolumeGain
  private var reverbMixIntensity = ScorePlaybackController.defaultReverbMixIntensity

  func initializeAudio() throws {
    try prepareAudio()
  }

  func play(
    score: NativeScore,
    from startTime: Double,
    speed: Double,
    offsetCents: Double = 0,
    audioStartDelaySeconds: Double = 0,
    onFinished: (() -> Void)? = nil
  ) throws {
    stopScheduledPlayback()
    try prepareAudio()

    scoreStartTime = startTime.clamped(to: Self.minimumPlaybackPosition...max(0, score.duration))
    wallStartDate = Date()
    playbackSpeed = max(0.01, speed)
    audioStartDelayScoreSeconds = max(0, audioStartDelaySeconds) * playbackSpeed
    events = makeEvents(score: score, from: scoreStartTime, offsetCents: offsetCents)
    nextEventIndex = 0
    self.onFinished = onFinished

    processDueEvents()
    if !events.isEmpty, playbackTimer == nil {
      startTimer()
    }
  }

  func pause() {
    stopScheduledPlayback()
  }

  func stop() {
    stopScheduledPlayback()
    stopAllPreviewNotes()
  }

  func allNotesOff() {
    for note in activeNotes {
      synth.noteOff(noteNumber: note.noteNumber, channel: note.playbackChannel)
    }
    activeNotes.removeAll()
    stopAllPreviewNotes()
    synth.allNotesOff()
  }

  func setVolumeGain(_ gain: Float) {
    volumeGain = gain.clamped(to: Self.volumeGainMin...Self.volumeGainMax)
    synth.setGain(Self.fluidGain(for: volumeGain))
  }

  func setReverbMixIntensity(_ intensity: Float) {
    reverbMixIntensity = intensity.clamped(to: 0...1)
    synth.setReverbMixIntensity(reverbMixIntensity)
  }

  func startPreviewNote(
    pitch: Double,
    velocity: Int,
    channel: Int = 0,
    program: Int = 0,
    bankMsb: Int = 0,
    bankLsb: Int = 0
  ) throws -> Int {
    try prepareAudio()
    let key = VoiceKey(channel: channel, program: program, bankMsb: bankMsb, bankLsb: bankLsb)
    let noteNumber = UInt8(clamping: Int(pitch.rounded()))
    let pitchBend = Self.pitchBendValue(forCents: (pitch - Double(noteNumber)) * 100.0)
    let activeNote = ActiveNote(key: key, playbackChannel: key.channel, noteNumber: noteNumber)
    let token = makePreviewToken()

    synth.noteOn(
      noteNumber: noteNumber,
      velocity: UInt8(clamping: max(1, velocity)),
      channel: key.channel,
      instrument: key.fluidInstrument,
      pitchBend: pitchBend
    )
    activePreviewNotes[token] = activeNote
    return token
  }

  func stopPreviewNote(_ token: Int) {
    guard let note = activePreviewNotes.removeValue(forKey: token) else { return }
    synth.noteOff(noteNumber: note.noteNumber, channel: note.playbackChannel)
  }

  func setPreviewExpression(_ token: Int, expression: Int) {
    guard let note = activePreviewNotes[token] else { return }
    synth.controlChange(
      channel: note.playbackChannel,
      controller: 11,
      value: UInt8(clamping: expression.clamped(to: 0...127))
    )
  }

  private func prepareAudio() throws {
    try XenAudioSession.shared.activateForPlayback()
    synth.setGain(Self.fluidGain(for: volumeGain))
    synth.setReverbMixIntensity(reverbMixIntensity)
    try synth.loadDefaultSoundFontIfNeeded()
  }

  private func makeEvents(
    score: NativeScore,
    from startTime: Double,
    offsetCents: Double
  ) -> [ScheduledEvent] {
    var notes: [PlayableNote] = []
    notes.reserveCapacity(score.notes.count)

    for (order, note) in score.notes.enumerated() where note.end > startTime {
      let key = VoiceKey(note: note)
      let renderedPitch = note.pitch + offsetCents / 100.0
      let noteNumber = UInt8(clamping: Int(renderedPitch.rounded()))
      let start = max(note.start, startTime)
      let stopTime = max(note.end, start)
      notes.append(PlayableNote(
        key: key,
        start: start,
        stopTime: stopTime,
        noteNumber: noteNumber,
        velocity: UInt8(clamping: note.velocity),
        pitchBend: Self.pitchBendValue(forCents: (renderedPitch - Double(noteNumber)) * 100.0),
        order: order
      ))
    }

    var lanes: [PlaybackLane] = []
    var scheduled: [ScheduledEvent] = []
    scheduled.reserveCapacity(notes.count * 2)

    for note in notes.sorted(by: Self.sortPlayableNotes) {
      let laneIndex = Self.assignLane(for: note, lanes: &lanes)
      let playbackChannel = lanes[laneIndex].channel
      scheduled.append(ScheduledEvent(
        time: note.start,
        kind: .noteOn,
        key: note.key,
        playbackChannel: playbackChannel,
        noteNumber: note.noteNumber,
        velocity: note.velocity,
        pitchBend: note.pitchBend
      ))
      scheduled.append(ScheduledEvent(
        time: note.stopTime,
        kind: .noteOff,
        key: note.key,
        playbackChannel: playbackChannel,
        noteNumber: note.noteNumber,
        velocity: 0,
        pitchBend: Self.centerPitchBend
      ))
      lanes[laneIndex].activeNotes.append(LaneActiveNote(
        key: note.key,
        end: note.stopTime,
        noteNumber: note.noteNumber,
        pitchBend: note.pitchBend
      ))
    }

    return scheduled.sorted {
      if $0.time == $1.time { return $0.kind.rawValue < $1.kind.rawValue }
      return $0.time < $1.time
    }
  }

  private func startTimer() {
    let timer = DispatchSource.makeTimerSource(queue: .main)
    timer.schedule(deadline: .now(), repeating: .milliseconds(8), leeway: .milliseconds(2))
    timer.setEventHandler { [weak self] in self?.processDueEvents() }
    playbackTimer = timer
    timer.resume()
  }

  private func processDueEvents() {
    let currentScoreTime = scoreStartTime
      - audioStartDelayScoreSeconds
      + max(0, Date().timeIntervalSince(wallStartDate)) * playbackSpeed

    while nextEventIndex < events.count {
      let event = events[nextEventIndex]
      guard event.time <= currentScoreTime else { break }
      play(event)
      nextEventIndex += 1
    }

    if nextEventIndex >= events.count {
      playbackTimer?.cancel()
      playbackTimer = nil
      events.removeAll()
      nextEventIndex = 0
      let finish = onFinished
      onFinished = nil
      finish?()
    }
  }

  private func play(_ event: ScheduledEvent) {
    let activeNote = ActiveNote(
      key: event.key,
      playbackChannel: event.playbackChannel,
      noteNumber: event.noteNumber
    )
    switch event.kind {
    case .noteOn:
      synth.noteOn(
        noteNumber: event.noteNumber,
        velocity: event.velocity,
        channel: event.playbackChannel,
        instrument: event.key.fluidInstrument,
        pitchBend: event.pitchBend
      )
      activeNotes.insert(activeNote)
    case .noteOff:
      synth.noteOff(noteNumber: event.noteNumber, channel: event.playbackChannel)
      activeNotes.remove(activeNote)
    }
  }

  private func stopScheduledPlayback() {
    playbackTimer?.cancel()
    playbackTimer = nil
    events.removeAll()
    nextEventIndex = 0
    onFinished = nil
    for note in activeNotes {
      synth.noteOff(noteNumber: note.noteNumber, channel: note.playbackChannel)
    }
    activeNotes.removeAll()
  }

  private func makePreviewToken() -> Int {
    let token = nextPreviewToken
    nextPreviewToken = nextPreviewToken == Int.max ? 1 : nextPreviewToken + 1
    return token
  }

  private func stopAllPreviewNotes() {
    for note in activePreviewNotes.values {
      synth.noteOff(noteNumber: note.noteNumber, channel: note.playbackChannel)
    }
    activePreviewNotes.removeAll()
  }

  deinit {
    stop()
    synth.allSoundsOff()
  }

  private static let centerPitchBend = UInt16(8_192)
  private static let pitchBendRangeCents = 200.0
  private static let midiChannelCount = 16
  private static let volumeGainMin: Float = 0
  private static let volumeGainMax: Float = 6
  private static let defaultVolumeGain: Float = 2.05
  private static let defaultReverbMixIntensity: Float = 0.54
  private static let minimumPlaybackPosition = -1.0

  private static func sortPlayableNotes(_ lhs: PlayableNote, _ rhs: PlayableNote) -> Bool {
    if lhs.start != rhs.start { return lhs.start < rhs.start }
    return lhs.order < rhs.order
  }

  private static func assignLane(for note: PlayableNote, lanes: inout [PlaybackLane]) -> Int {
    for index in lanes.indices {
      lanes[index].activeNotes.removeAll { $0.end <= note.start }
    }
    if let compatible = lanes.indices.first(where: { lanes[$0].canHost(note) }) {
      return compatible
    }
    if lanes.count < midiChannelCount {
      lanes.append(PlaybackLane(channel: nextChannel(preferred: note.key.channel, lanes: lanes)))
      return lanes.count - 1
    }
    return lanes.indices.min {
      lanes[$0].activeNotes.count < lanes[$1].activeNotes.count
    } ?? 0
  }

  private static func nextChannel(preferred: UInt8, lanes: [PlaybackLane]) -> UInt8 {
    let used = Set(lanes.map(\.channel))
    if !used.contains(preferred) { return preferred }
    for channel in 0..<midiChannelCount {
      let candidate = UInt8(channel)
      if !used.contains(candidate) { return candidate }
    }
    return preferred
  }

  private static func pitchBendValue(forCents cents: Double) -> UInt16 {
    let normalized = (cents / pitchBendRangeCents).clamped(to: -1...1)
    let value = Int(centerPitchBend) + Int((normalized * 8_191).rounded())
    return UInt16(clamping: value)
  }

  private static func fluidGain(for gain: Float) -> Float {
    (gain / defaultVolumeGain).clamped(to: 0...1)
  }
}
