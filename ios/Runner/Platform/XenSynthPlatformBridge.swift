import Flutter
import UIKit
import UniformTypeIdentifiers

final class XenSynthPlatformBridge: NSObject, FlutterStreamHandler, UIDocumentPickerDelegate {
  static let methodChannelName = "icu.ringona.xensynth/platform"
  static let midiEventChannelName = "icu.ringona.xensynth/platform/midi"

  private let playbackController = ScorePlaybackController()
  private let midiController = MIDIKeyboardController()
  private let methodChannel: FlutterMethodChannel
  private let eventChannel: FlutterEventChannel
  private var midiEventSink: FlutterEventSink?
  private var pendingDocumentResult: FlutterResult?
  private var score: NativeScore?
  private var basePosition = 0.0
  private var playbackSpeed = 1.0
  private var playbackOffsetCents = 0.0
  private var playbackAudioStartDelaySeconds = 0.0
  private var playbackStartedAt = Date()
  private var isPlaying = false
  private var latencyMilliseconds = 0.0
  private var previewPrograms = Array(repeating: 0, count: 16)

  init(messenger: FlutterBinaryMessenger) {
    methodChannel = FlutterMethodChannel(
      name: Self.methodChannelName,
      binaryMessenger: messenger
    )
    eventChannel = FlutterEventChannel(
      name: Self.midiEventChannelName,
      binaryMessenger: messenger
    )
    super.init()

    methodChannel.setMethodCallHandler { [weak self] call, result in
      self?.handle(call, result: result)
    }
    eventChannel.setStreamHandler(self)
    midiController.onEvent = { [weak self] event in
      self?.midiEventSink?(event)
    }
    restoreNativeSettings()
  }

  private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let arguments = dictionary(from: call.arguments)
    do {
      switch call.method {
      case "initializeAudio":
        try playbackController.initializeAudio()
        result(true)

      case "setGain":
        let gain = (arguments.double(forAnyKey: ["gain", "value", "volumeGain"]) ?? 0.85)
          .clamped(to: 0...1)
        playbackController.setVolumeGain(Float(gain * 2.05))
        result(true)

      case "setReverb":
        let rawMix = arguments.double(forAnyKey: ["mix", "value", "reverb", "reverbMix"]) ?? 0.54
        playbackController.setReverbMixIntensity(Float(normalizedReverb(rawMix)))
        result(true)

      case "setLatency":
        latencyMilliseconds = (arguments.double(forAnyKey: ["milliseconds", "latency", "value"]) ?? 0)
          .clamped(to: -100...700)
        result(true)

      case "setProgram":
        let channel = (arguments.int(forAnyKey: ["channel", "midiChannel"]) ?? 0).clamped(to: 0...15)
        previewPrograms[channel] = (arguments.int(forAnyKey: ["program", "programNumber", "value"]) ?? 0)
          .clamped(to: 0...127)
        result(true)

      case "loadScore":
        let payload = dictionary(from: arguments["score"] ?? call.arguments)
        guard let noteMaps = noteMaps(from: payload["notes"] ?? arguments["notes"]) else {
          throw NativeScoreError.missingNotes
        }
        let loaded = try NativeScore(
          noteMaps: noteMaps,
          declaredDuration: payload.double(forAnyKey: ["duration", "scoreDuration"])
            ?? arguments.double(forAnyKey: ["duration", "scoreDuration"])
        )
        playbackController.stop()
        score = loaded
        basePosition = 0
        playbackStartedAt = Date()
        isPlaying = false
        result(["noteCount": loaded.notes.count, "duration": loaded.duration])

      case "play":
        guard let score else { throw BridgeError.scoreNotLoaded }
        let start = (arguments.double(forAnyKey: ["from", "position", "start", "startTime"])
          ?? currentPosition()).clamped(to: Self.minimumPlaybackPosition...max(0, score.duration))
        let speed = max(0.01, arguments.double(forAnyKey: ["speed", "playbackSpeed"]) ?? playbackSpeed)
        let offsetCents = arguments.double(forAnyKey: ["offsetCents", "pitchOffsetCents", "offset"]) ?? 0
        let explicitDelay = arguments.double(forAnyKey: ["audioStartDelaySeconds", "startDelaySeconds"])
        let audioDelay = explicitDelay ?? max(0, -latencyMilliseconds) / 1_000.0
        try startPlayback(
          score: score,
          from: start,
          speed: speed,
          offsetCents: offsetCents,
          audioStartDelaySeconds: audioDelay
        )
        result(true)

      case "pause":
        let position = currentPosition()
        playbackController.pause()
        basePosition = position
        playbackStartedAt = Date()
        isPlaying = false
        result(["position": position])

      case "seek":
        guard let score else { throw BridgeError.scoreNotLoaded }
        let position = (arguments.double(forAnyKey: ["position", "seconds", "to"]) ?? 0)
          .clamped(to: 0...max(0, score.duration))
        if isPlaying {
          try startPlayback(
            score: score,
            from: position,
            speed: playbackSpeed,
            offsetCents: playbackOffsetCents,
            audioStartDelaySeconds: playbackAudioStartDelaySeconds
          )
        } else {
          basePosition = position
          playbackStartedAt = Date()
        }
        result(["position": position])

      case "stop":
        playbackController.stop()
        basePosition = 0
        playbackStartedAt = Date()
        isPlaying = false
        result(true)

      case "noteOn":
        guard let pitch = arguments.double(forAnyKey: ["pitch", "audioPitch", "midiPitch", "note", "noteNumber"]) else {
          throw BridgeError.invalidArguments("noteOn requires pitch")
        }
        let channel = (arguments.int(forAnyKey: ["channel", "midiChannel"]) ?? 0).clamped(to: 0...15)
        let token = try playbackController.startPreviewNote(
          pitch: pitch,
          velocity: arguments.int(forAnyKey: ["velocity", "vel"]) ?? 96,
          channel: channel,
          program: arguments.int(forAnyKey: ["program", "programNumber"]) ?? previewPrograms[channel],
          bankMsb: arguments.int(forAnyKey: ["bankMsb", "bankMSB"]) ?? 0,
          bankLsb: arguments.int(forAnyKey: ["bankLsb", "bankLSB"]) ?? 0
        )
        result(token)

      case "noteOff":
        guard let token = arguments.int(forAnyKey: ["token", "id", "noteId"]) else {
          throw BridgeError.invalidArguments("noteOff requires token")
        }
        playbackController.stopPreviewNote(token)
        result(true)

      case "allNotesOff":
        playbackController.allNotesOff()
        result(true)

      case "convertMuseScore":
        guard let name = arguments["name"] as? String, !name.isEmpty else {
          throw BridgeError.invalidArguments("convertMuseScore requires name")
        }
        guard let inputData = data(from: arguments["bytes"]), !inputData.isEmpty else {
          throw BridgeError.invalidArguments("convertMuseScore requires non-empty bytes")
        }
        DispatchQueue.global(qos: .userInitiated).async { [self] in
          do {
            let converted = try MsczToMidx.convert([UInt8](inputData), fileName: name)
            DispatchQueue.main.async {
              result(FlutterStandardTypedData(bytes: Data(converted)))
            }
          } catch {
            DispatchQueue.main.async {
              result(self.flutterError(error))
            }
          }
        }

      case "pickDocument":
        presentDocumentPicker(result: result)

      case "loadSettings":
        let keys = stringList(from: arguments["keys"])
        result(loadSettings(keys: keys))

      case "saveSettings":
        let settings = dictionary(from: arguments["settings"] ?? call.arguments)
        saveSettings(settings)
        result(true)

      default:
        result(FlutterMethodNotImplemented)
      }
    } catch {
      result(flutterError(error))
    }
  }

  private func startPlayback(
    score: NativeScore,
    from position: Double,
    speed: Double,
    offsetCents: Double,
    audioStartDelaySeconds: Double
  ) throws {
    let previousPosition = basePosition
    let previousSpeed = playbackSpeed
    let previousOffsetCents = playbackOffsetCents
    let previousAudioDelay = playbackAudioStartDelaySeconds
    let previousStartedAt = playbackStartedAt
    let previousPlaying = isPlaying

    basePosition = position
    playbackSpeed = speed
    playbackOffsetCents = offsetCents
    playbackAudioStartDelaySeconds = max(0, audioStartDelaySeconds)
    playbackStartedAt = Date()
    isPlaying = true

    do {
      try playbackController.play(
        score: score,
        from: position,
        speed: speed,
        offsetCents: offsetCents,
        audioStartDelaySeconds: audioStartDelaySeconds
      ) { [weak self] in
        guard let self else { return }
        self.basePosition = score.duration
        self.playbackStartedAt = Date()
        self.isPlaying = false
        self.methodChannel.invokeMethod(
          "onPlaybackComplete",
          arguments: ["position": score.duration]
        )
      }
    } catch {
      basePosition = previousPosition
      playbackSpeed = previousSpeed
      playbackOffsetCents = previousOffsetCents
      playbackAudioStartDelaySeconds = previousAudioDelay
      playbackStartedAt = previousStartedAt
      isPlaying = previousPlaying
      throw error
    }
  }

  private func currentPosition() -> Double {
    guard let score else { return 0 }
    guard isPlaying else {
      return basePosition.clamped(to: Self.minimumPlaybackPosition...max(0, score.duration))
    }
    let elapsed = max(0, Date().timeIntervalSince(playbackStartedAt)) * playbackSpeed
    return (basePosition + elapsed)
      .clamped(to: Self.minimumPlaybackPosition...max(0, score.duration))
  }

  private func normalizedReverb(_ rawValue: Double) -> Double {
    let normalized = rawValue > 1 ? rawValue / 100.0 : rawValue
    return normalized.clamped(to: 0...1)
  }

  private func presentDocumentPicker(result: @escaping FlutterResult) {
    guard pendingDocumentResult == nil else {
      result(flutterError(BridgeError.documentPickerBusy))
      return
    }
    guard let presenter = topViewController() else {
      result(flutterError(BridgeError.noPresentationContext))
      return
    }

    pendingDocumentResult = result
    let picker = UIDocumentPickerViewController(
      forOpeningContentTypes: [.midi, .json, .data],
      asCopy: true
    )
    picker.delegate = self
    picker.allowsMultipleSelection = false
    presenter.present(picker, animated: true)
  }

  func documentPicker(
    _ controller: UIDocumentPickerViewController,
    didPickDocumentsAt urls: [URL]
  ) {
    guard let result = takeDocumentResult() else { return }
    guard let sourceURL = urls.first else {
      result(nil)
      return
    }

    let accessing = sourceURL.startAccessingSecurityScopedResource()
    defer {
      if accessing { sourceURL.stopAccessingSecurityScopedResource() }
    }

    do {
      let data = try Data(contentsOf: sourceURL)
      let cacheDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("PickedDocuments", isDirectory: true)
      try FileManager.default.createDirectory(
        at: cacheDirectory,
        withIntermediateDirectories: true,
        attributes: nil
      )
      let fileName = sourceURL.lastPathComponent.isEmpty ? "selected-file" : sourceURL.lastPathComponent
      let cachedURL = cacheDirectory.appendingPathComponent("\(UUID().uuidString)-\(fileName)")
      try data.write(to: cachedURL, options: .atomic)

      result([
        "name": fileName,
        "path": cachedURL.path,
        "size": data.count,
        "bytes": FlutterStandardTypedData(bytes: data),
      ])
    } catch {
      result(flutterError(error))
    }
  }

  func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
    takeDocumentResult()?(nil)
  }

  private func takeDocumentResult() -> FlutterResult? {
    let result = pendingDocumentResult
    pendingDocumentResult = nil
    return result
  }

  private func topViewController() -> UIViewController? {
    let windows = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap(\.windows)
    let root = windows.first(where: \.isKeyWindow)?.rootViewController
      ?? windows.first?.rootViewController
    return topViewController(from: root)
  }

  private func topViewController(from viewController: UIViewController?) -> UIViewController? {
    if let presented = viewController?.presentedViewController {
      return topViewController(from: presented)
    }
    if let navigation = viewController as? UINavigationController {
      return topViewController(from: navigation.visibleViewController)
    }
    if let tab = viewController as? UITabBarController {
      return topViewController(from: tab.selectedViewController)
    }
    return viewController
  }

  private func restoreNativeSettings() {
    let settings = loadSettings(keys: nil)
    if let gain = (settings["volumeGain"] as? NSNumber)?.doubleValue {
      playbackController.setVolumeGain(Float(gain.clamped(to: 0...1) * 2.05))
    }
    if let mix = (settings["reverbMix"] as? NSNumber)?.doubleValue {
      playbackController.setReverbMixIntensity(Float(normalizedReverb(mix)))
    }
    if let latency = (settings["audioLatencyMs"] as? NSNumber)?.doubleValue {
      latencyMilliseconds = latency.clamped(to: -100...700)
    }
    if let program = (settings["program"] as? NSNumber)?.intValue {
      previewPrograms = Array(repeating: program.clamped(to: 0...127), count: 16)
    }
  }

  private func loadSettings(keys: [String]?) -> [String: Any] {
    let defaults = UserDefaults.standard
    let requestedKeys = keys ?? Array(Set(Self.settingDefaults.keys).union(storedSettingKeys()))
    var output: [String: Any] = [:]
    for key in requestedKeys {
      if let value = defaults.object(forKey: key) {
        output[key] = normalizedSettingValue(value, for: key)
      } else if let legacyValue = legacySettingValue(for: key, defaults: defaults) {
        output[key] = legacyValue
      } else if let fallback = Self.settingDefaults[key] {
        output[key] = fallback
      }
    }
    return output
  }

  private func normalizedSettingValue(_ value: Any, for key: String) -> Any {
    if key == "volumeGain", let number = value as? NSNumber, number.doubleValue > 1 {
      return (number.doubleValue / 2.05).clamped(to: 0...1)
    }
    return value
  }

  private func legacySettingValue(for key: String, defaults: UserDefaults) -> Any? {
    let aliases: [String: String] = [
      "pitchOffsetCents": "waterfallOffsetCents",
      "audioLatencyMs": "audioLatencyMilliseconds",
      "program": "touchKeyboardProgram",
      "externalMidiControlsProgram": "touchKeyboardProgramControlsMIDI",
      "hexColumns": "hexKeyboardColumns",
      "hexRows": "hexKeyboardRows",
      "hexStepQ": "hexKeyboardStepQ",
      "hexStepR": "hexKeyboardStepR",
      "hexGroupByOctave": "hexKeyboardOctaveGrouping",
      "pseudoPressureEnabled": "hexKeyboardPseudoPressure",
    ]
    if key == "touchSensitivity",
       let legacy = defaults.object(forKey: "hexKeyboardTouchSensitivity") as? NSNumber {
      return (legacy.doubleValue / 200.0).clamped(to: 0...1)
    }
    guard let alias = aliases[key] else { return nil }
    return defaults.object(forKey: alias)
  }

  private func saveSettings(_ settings: [String: Any]) {
    guard !settings.isEmpty else { return }
    let defaults = UserDefaults.standard
    var keys = storedSettingKeys()
    for (key, value) in settings where key != Self.storedSettingsKeysKey {
      if value is NSNull {
        defaults.removeObject(forKey: key)
        keys.remove(key)
      } else if isUserDefaultsValue(value) {
        defaults.set(value, forKey: key)
        keys.insert(key)
      }
    }
    defaults.set(Array(keys).sorted(), forKey: Self.storedSettingsKeysKey)
    restoreNativeSettings()
  }

  private func storedSettingKeys() -> Set<String> {
    Set(UserDefaults.standard.stringArray(forKey: Self.storedSettingsKeysKey) ?? [])
  }

  private func isUserDefaultsValue(_ value: Any) -> Bool {
    switch value {
    case is String, is NSNumber, is Data, is Date:
      return true
    case let array as [Any]:
      return array.allSatisfy(isUserDefaultsValue)
    case let dictionary as [String: Any]:
      return dictionary.values.allSatisfy(isUserDefaultsValue)
    default:
      return false
    }
  }

  private func dictionary(from value: Any?) -> [String: Any] {
    if let dictionary = value as? [String: Any] { return dictionary }
    guard let dictionary = value as? [AnyHashable: Any] else { return [:] }
    return dictionary.reduce(into: [:]) { output, entry in
      if let key = entry.key as? String { output[key] = entry.value }
    }
  }

  private func noteMaps(from value: Any?) -> [[String: Any]]? {
    guard let values = value as? [Any] else { return nil }
    return values.compactMap { item in
      let map = dictionary(from: item)
      return map.isEmpty ? nil : map
    }
  }

  private func data(from value: Any?) -> Data? {
    if let typedData = value as? FlutterStandardTypedData { return typedData.data }
    if let data = value as? Data { return data }
    if let bytes = value as? [UInt8] { return Data(bytes) }
    guard let values = value as? [Any] else { return nil }

    var bytes: [UInt8] = []
    bytes.reserveCapacity(values.count)
    for value in values {
      guard let number = value as? NSNumber else { return nil }
      let byte = number.intValue
      guard (0...255).contains(byte) else { return nil }
      bytes.append(UInt8(byte))
    }
    return Data(bytes)
  }

  private func stringList(from value: Any?) -> [String]? {
    (value as? [Any])?.compactMap { $0 as? String }
  }

  private func flutterError(_ error: Error) -> FlutterError {
    FlutterError(
      code: errorCode(error),
      message: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription,
      details: nil
    )
  }

  private func errorCode(_ error: Error) -> String {
    switch error {
    case is NativeScoreError: return "invalid_score"
    case BridgeError.scoreNotLoaded: return "score_not_loaded"
    case BridgeError.documentPickerBusy: return "document_picker_busy"
    case BridgeError.noPresentationContext: return "no_presentation_context"
    case is BridgeError: return "invalid_arguments"
    default: return "ios_platform_error"
    }
  }

  func onListen(
    withArguments arguments: Any?,
    eventSink events: @escaping FlutterEventSink
  ) -> FlutterError? {
    midiEventSink = events
    do {
      try midiController.start()
      return nil
    } catch {
      midiEventSink = nil
      return flutterError(error)
    }
  }

  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    midiEventSink = nil
    midiController.stop()
    return nil
  }

  deinit {
    pendingDocumentResult?(nil)
    pendingDocumentResult = nil
    midiController.stop()
    playbackController.stop()
  }

  private static let storedSettingsKeysKey = "xensynth.flutter.settings.keys"
  private static let minimumPlaybackPosition = -1.0
  private static let settingDefaults: [String: Any] = [
    "playbackSpeed": 1.0,
    "edo": 26,
    "pitchOffsetCents": 0.0,
    "volumeGain": 0.85,
    "reverbMix": 54.0,
    "audioLatencyMs": 0.0,
    "program": 0,
    "externalMidiControlsProgram": false,
    "keyboardLayoutMode": "linear",
    "hexColumns": 35,
    "hexRows": 8,
    "hexPeriod": 53,
    "hexStepQ": 9,
    "hexStepR": 4,
    "hexGroupByOctave": false,
    "hexRotationDegrees": 12,
    "touchSensitivity": 0.58,
    "pseudoPressureEnabled": true,
    "playbackPreviewSeconds": 2.8,
    "pitchSnapEnabled": true,
  ]
}

private enum BridgeError: LocalizedError {
  case scoreNotLoaded
  case invalidArguments(String)
  case documentPickerBusy
  case noPresentationContext

  var errorDescription: String? {
    switch self {
    case .scoreNotLoaded:
      return "No score is loaded."
    case let .invalidArguments(message):
      return message
    case .documentPickerBusy:
      return "A document picker is already active."
    case .noPresentationContext:
      return "No view controller is available to present the document picker."
    }
  }
}
