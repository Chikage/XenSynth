import AVFAudio
import Foundation

final class XenAudioSession {
  static let shared = XenAudioSession()

  enum SessionError: LocalizedError {
    case microphoneUnavailable

    var errorDescription: String? {
      switch self {
      case .microphoneUnavailable:
        return "No microphone input is available."
      }
    }
  }

  private let lock = NSRecursiveLock()
  private let session = AVAudioSession.sharedInstance()
  private var captureCount = 0

  private init() {}

  func activateForPlayback() throws {
    try withLock {
      try configureLocked(wantsInput: captureCount > 0)
    }
  }

  func beginCapture() throws {
    try withLock {
      captureCount += 1
      do {
        try configureLocked(wantsInput: true)
        guard session.isInputAvailable else {
          throw SessionError.microphoneUnavailable
        }
      } catch {
        captureCount = max(0, captureCount - 1)
        try? configureLocked(wantsInput: captureCount > 0)
        throw error
      }
    }
  }

  func endCapture() {
    withLock {
      captureCount = max(0, captureCount - 1)
      try? configureLocked(wantsInput: captureCount > 0)
    }
  }

  private func configureLocked(wantsInput: Bool) throws {
    let category: AVAudioSession.Category = wantsInput ? .playAndRecord : .playback
    let options: AVAudioSession.CategoryOptions = wantsInput
      ? [.defaultToSpeaker, .allowBluetoothHFP, .allowBluetoothA2DP]
      : [.allowAirPlay, .allowBluetoothA2DP]
    do {
      try session.setCategory(category, mode: .default, options: options)
    } catch {
      try session.setCategory(category, mode: .default)
    }

    // Hardware routes may reject latency preferences even though playback is available.
    try? session.setPreferredSampleRate(Self.preferredSampleRate)
    try? session.setPreferredIOBufferDuration(Self.preferredBufferDuration)
    try session.setActive(true)
  }

  private func withLock<Result>(_ body: () throws -> Result) rethrows -> Result {
    lock.lock()
    defer { lock.unlock() }
    return try body()
  }

  private static let preferredSampleRate = 48_000.0
  private static let preferredBufferDuration = 256.0 / preferredSampleRate
}
