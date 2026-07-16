import Foundation

@_silgen_name("new_fluid_settings")
private func fluidNewSettings() -> OpaquePointer?

@_silgen_name("delete_fluid_settings")
private func fluidDeleteSettings(_ settings: OpaquePointer?)

@_silgen_name("fluid_settings_setnum")
private func fluidSettingsSetNumber(
  _ settings: OpaquePointer?,
  _ name: UnsafePointer<CChar>,
  _ value: Double
) -> Int32

@_silgen_name("fluid_settings_setint")
private func fluidSettingsSetInteger(
  _ settings: OpaquePointer?,
  _ name: UnsafePointer<CChar>,
  _ value: Int32
) -> Int32

@_silgen_name("new_fluid_synth")
private func fluidNewSynth(_ settings: OpaquePointer?) -> OpaquePointer?

@_silgen_name("delete_fluid_synth")
private func fluidDeleteSynth(_ synth: OpaquePointer?)

@_silgen_name("new_fluid_audio_driver")
private func fluidNewAudioDriver(_ settings: OpaquePointer?, _ synth: OpaquePointer?) -> OpaquePointer?

@_silgen_name("delete_fluid_audio_driver")
private func fluidDeleteAudioDriver(_ driver: OpaquePointer?)

@_silgen_name("fluid_synth_sfload")
private func fluidSynthLoadSoundFont(
  _ synth: OpaquePointer?,
  _ filename: UnsafePointer<CChar>,
  _ resetPresets: Int32
) -> Int32

@_silgen_name("fluid_synth_sfunload")
private func fluidSynthUnloadSoundFont(
  _ synth: OpaquePointer?,
  _ soundFontID: Int32,
  _ resetPresets: Int32
) -> Int32

@_silgen_name("fluid_synth_noteon")
private func fluidSynthNoteOn(
  _ synth: OpaquePointer?,
  _ channel: Int32,
  _ key: Int32,
  _ velocity: Int32
) -> Int32

@_silgen_name("fluid_synth_noteoff")
private func fluidSynthNoteOff(_ synth: OpaquePointer?, _ channel: Int32, _ key: Int32) -> Int32

@_silgen_name("fluid_synth_all_notes_off")
private func fluidSynthAllNotesOff(_ synth: OpaquePointer?, _ channel: Int32) -> Int32

@_silgen_name("fluid_synth_all_sounds_off")
private func fluidSynthAllSoundsOff(_ synth: OpaquePointer?, _ channel: Int32) -> Int32

@_silgen_name("fluid_synth_program_select")
private func fluidSynthProgramSelect(
  _ synth: OpaquePointer?,
  _ channel: Int32,
  _ soundFontID: Int32,
  _ bank: Int32,
  _ program: Int32
) -> Int32

@_silgen_name("fluid_synth_pitch_bend")
private func fluidSynthPitchBend(_ synth: OpaquePointer?, _ channel: Int32, _ value: Int32) -> Int32

@_silgen_name("fluid_synth_cc")
private func fluidSynthControlChange(
  _ synth: OpaquePointer?,
  _ channel: Int32,
  _ controller: Int32,
  _ value: Int32
) -> Int32

@_silgen_name("fluid_synth_pitch_wheel_sens")
private func fluidSynthPitchWheelSensitivity(
  _ synth: OpaquePointer?,
  _ channel: Int32,
  _ value: Int32
) -> Int32

@_silgen_name("fluid_synth_reverb_on")
private func fluidSynthReverbOn(_ synth: OpaquePointer?, _ fxGroup: Int32, _ enabled: Int32) -> Int32

@_silgen_name("fluid_synth_set_reverb_group_roomsize")
private func fluidSynthSetReverbRoomSize(
  _ synth: OpaquePointer?,
  _ fxGroup: Int32,
  _ roomSize: Double
) -> Int32

@_silgen_name("fluid_synth_set_reverb_group_damp")
private func fluidSynthSetReverbDamp(_ synth: OpaquePointer?, _ fxGroup: Int32, _ damp: Double) -> Int32

@_silgen_name("fluid_synth_set_reverb_group_width")
private func fluidSynthSetReverbWidth(_ synth: OpaquePointer?, _ fxGroup: Int32, _ width: Double) -> Int32

@_silgen_name("fluid_synth_set_reverb_group_level")
private func fluidSynthSetReverbLevel(_ synth: OpaquePointer?, _ fxGroup: Int32, _ level: Double) -> Int32

@_silgen_name("fluid_synth_set_gain")
private func fluidSynthSetGain(_ synth: OpaquePointer?, _ gain: Float)

struct FluidSynthPreset: Hashable {
  let bank: Int
  let program: Int
}

struct FluidSynthInstrument: Hashable {
  let presets: [FluidSynthPreset]
}

final class FluidSynthEngine {
  static let shared = FluidSynthEngine()

  enum EngineError: LocalizedError {
    case missingBundledSoundFont
    case missingSettings
    case missingSynth
    case loadSoundFontFailed(URL)
    case missingAudioDriver

    var errorDescription: String? {
      switch self {
      case .missingBundledSoundFont:
        return "Bundled DefaultSoundFont.sf2 is missing."
      case .missingSettings:
        return "Could not create FluidSynth settings."
      case .missingSynth:
        return "Could not create FluidSynth synthesizer."
      case let .loadSoundFontFailed(url):
        return "Could not load SoundFont: \(url.lastPathComponent)."
      case .missingAudioDriver:
        return "Could not start FluidSynth audio driver."
      }
    }
  }

  private struct ChannelState {
    var instrument: FluidSynthInstrument?
    var pitchBend: UInt16?
    var didSetPitchWheelSensitivity = false
  }

  private let lock = NSRecursiveLock()
  private var settings: OpaquePointer?
  private var synth: OpaquePointer?
  private var audioDriver: OpaquePointer?
  private var soundFontID: Int32 = -1
  private var channelStates: [Int: ChannelState] = [:]
  private var gain: Float = 1
  private var reverbMixIntensity: Float = 0.54

  private init() {}

  var isLoaded: Bool {
    withLock { synth != nil && audioDriver != nil && soundFontID >= 0 }
  }

  func loadDefaultSoundFontIfNeeded() throws {
    if isLoaded { return }
    guard let url = Bundle.main.url(forResource: "DefaultSoundFont", withExtension: "sf2") else {
      throw EngineError.missingBundledSoundFont
    }
    try loadSoundFont(at: url)
  }

  func loadSoundFont(at url: URL) throws {
    try withLock {
      cleanupLocked()

      guard let settings = fluidNewSettings() else {
        throw EngineError.missingSettings
      }
      configure(settings)
      guard let synth = fluidNewSynth(settings) else {
        fluidDeleteSettings(settings)
        throw EngineError.missingSynth
      }
      self.settings = settings
      self.synth = synth

      let loadedID = url.path.withCString { path in
        fluidSynthLoadSoundFont(synth, path, 1)
      }
      guard loadedID >= 0 else {
        cleanupLocked()
        throw EngineError.loadSoundFontFailed(url)
      }
      soundFontID = loadedID

      guard let driver = fluidNewAudioDriver(settings, synth) else {
        cleanupLocked()
        throw EngineError.missingAudioDriver
      }
      audioDriver = driver
      channelStates.removeAll()
      applyGainLocked()
      applyReverbLocked()
    }
  }

  func noteOn(
    noteNumber: UInt8,
    velocity: UInt8,
    channel: UInt8,
    instrument: FluidSynthInstrument,
    pitchBend: UInt16
  ) {
    withLock {
      guard let synth, soundFontID >= 0 else { return }
      let safeChannel = Int(channel).clamped(to: 0...15)
      prepareChannelLocked(safeChannel, instrument: instrument, pitchBend: pitchBend)
      _ = fluidSynthNoteOn(
        synth,
        Int32(safeChannel),
        Int32(noteNumber),
        Int32(max(1, velocity))
      )
    }
  }

  func noteOff(noteNumber: UInt8, channel: UInt8) {
    withLock {
      guard let synth else { return }
      _ = fluidSynthNoteOff(synth, Int32(Int(channel).clamped(to: 0...15)), Int32(noteNumber))
    }
  }

  func controlChange(channel: UInt8, controller: UInt8, value: UInt8) {
    withLock {
      guard let synth else { return }
      _ = fluidSynthControlChange(
        synth,
        Int32(Int(channel).clamped(to: 0...15)),
        Int32(controller),
        Int32(value)
      )
    }
  }

  func allNotesOff() {
    withLock {
      guard let synth else { return }
      for channel in 0..<Self.midiChannelCount {
        _ = fluidSynthAllNotesOff(synth, Int32(channel))
      }
    }
  }

  func allSoundsOff() {
    withLock {
      guard let synth else { return }
      for channel in 0..<Self.midiChannelCount {
        _ = fluidSynthAllSoundsOff(synth, Int32(channel))
      }
    }
  }

  func setGain(_ gain: Float) {
    withLock {
      self.gain = gain.clamped(to: 0...3)
      applyGainLocked()
    }
  }

  func setReverbMixIntensity(_ intensity: Float) {
    withLock {
      reverbMixIntensity = intensity.clamped(to: 0...1)
      applyReverbLocked()
    }
  }

  func unload() {
    withLock { cleanupLocked() }
  }

  private func configure(_ settings: OpaquePointer) {
    _ = fluidSettingsSetInteger(settings, "synth.polyphony", 1_024)
    _ = fluidSettingsSetInteger(settings, "synth.midi-channels", Int32(Self.midiChannelCount))
    _ = fluidSettingsSetInteger(settings, "synth.chorus.active", 0)
    _ = fluidSettingsSetNumber(settings, "synth.gain", Double(gain))
    _ = fluidSettingsSetNumber(settings, "synth.reverb.active", reverbMixIntensity > 0 ? 1 : 0)
    _ = fluidSettingsSetNumber(settings, "synth.reverb.room-size", 0.78)
    _ = fluidSettingsSetNumber(settings, "synth.reverb.damp", 0.46)
    _ = fluidSettingsSetNumber(settings, "synth.reverb.width", 0.86)
    _ = fluidSettingsSetNumber(settings, "synth.reverb.level", Double(reverbMixIntensity))
  }

  private func prepareChannelLocked(
    _ channel: Int,
    instrument: FluidSynthInstrument,
    pitchBend: UInt16
  ) {
    guard let synth else { return }
    var state = channelStates[channel] ?? ChannelState()
    if !state.didSetPitchWheelSensitivity {
      _ = fluidSynthPitchWheelSensitivity(synth, Int32(channel), Int32(Self.pitchBendRangeSemitones))
      state.didSetPitchWheelSensitivity = true
    }
    if state.instrument != instrument {
      selectInstrumentLocked(instrument, channel: channel)
      state.instrument = instrument
    }
    if state.pitchBend != pitchBend {
      _ = fluidSynthPitchBend(synth, Int32(channel), Int32(pitchBend))
      state.pitchBend = pitchBend
    }
    channelStates[channel] = state
  }

  private func selectInstrumentLocked(_ instrument: FluidSynthInstrument, channel: Int) {
    guard let synth, soundFontID >= 0 else { return }
    let presets = (instrument.presets.isEmpty
      ? [FluidSynthPreset(bank: 0, program: 0)]
      : instrument.presets
    ).uniqued()

    for preset in presets {
      let status = fluidSynthProgramSelect(
        synth,
        Int32(channel),
        soundFontID,
        Int32(preset.bank.clamped(to: 0...16_383)),
        Int32(preset.program.clamped(to: 0...127))
      )
      if status == Self.fluidOK { return }
    }
    _ = fluidSynthProgramSelect(synth, Int32(channel), soundFontID, 0, 0)
  }

  private func applyGainLocked() {
    guard let synth else { return }
    fluidSynthSetGain(synth, gain)
  }

  private func applyReverbLocked() {
    guard let synth else { return }
    _ = fluidSynthSetReverbRoomSize(synth, -1, 0.78)
    _ = fluidSynthSetReverbDamp(synth, -1, 0.46)
    _ = fluidSynthSetReverbWidth(synth, -1, 0.86)
    if reverbMixIntensity <= 0 {
      _ = fluidSynthReverbOn(synth, -1, 0)
    } else {
      _ = fluidSynthReverbOn(synth, -1, 1)
      _ = fluidSynthSetReverbLevel(synth, -1, Double(reverbMixIntensity))
    }
  }

  private func cleanupLocked() {
    if let synth {
      for channel in 0..<Self.midiChannelCount {
        _ = fluidSynthAllSoundsOff(synth, Int32(channel))
      }
    }
    if let audioDriver {
      fluidDeleteAudioDriver(audioDriver)
      self.audioDriver = nil
    }
    if let synth, soundFontID >= 0 {
      _ = fluidSynthUnloadSoundFont(synth, soundFontID, 1)
      soundFontID = -1
    }
    if let synth {
      fluidDeleteSynth(synth)
      self.synth = nil
    }
    if let settings {
      fluidDeleteSettings(settings)
      self.settings = nil
    }
    channelStates.removeAll()
  }

  private func withLock<Result>(_ body: () throws -> Result) rethrows -> Result {
    lock.lock()
    defer { lock.unlock() }
    return try body()
  }

  deinit {
    cleanupLocked()
  }

  private static let midiChannelCount = 16
  private static let pitchBendRangeSemitones = 2
  private static let fluidOK: Int32 = 0
}

extension Array where Element: Hashable {
  func uniqued() -> [Element] {
    var seen = Set<Element>()
    return filter { seen.insert($0).inserted }
  }
}
