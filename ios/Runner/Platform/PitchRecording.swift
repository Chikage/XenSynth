import Foundation

struct PitchRecordingSnapshot {
  let sampleRate: Int
  let samples: [Int16]
}

final class PitchRecording {
  let sampleRate: Int

  private let lock = NSLock()
  private var samples: [Int16] = []

  init(sampleRate: Int) {
    precondition(sampleRate > 0)
    self.sampleRate = sampleRate
  }

  var durationSeconds: Double {
    withLock { Double(samples.count) / Double(sampleRate) }
  }

  func append(_ values: [Int16]) {
    guard !values.isEmpty else { return }
    withLock { samples.append(contentsOf: values) }
  }

  func snapshot() -> PitchRecordingSnapshot {
    withLock { PitchRecordingSnapshot(sampleRate: sampleRate, samples: samples) }
  }

  private func withLock<Result>(_ body: () -> Result) -> Result {
    lock.lock()
    defer { lock.unlock() }
    return body()
  }
}

struct RecognizedPitchNote {
  let startSeconds: Double
  let endSeconds: Double
  let pitch: Double
  let velocity: Int

  init?(map: [String: Any]) {
    guard let start = Self.number(map["start"]),
          let end = Self.number(map["end"]),
          let pitch = Self.number(map["pitch"]),
          start.isFinite, end.isFinite, pitch.isFinite else { return nil }
    startSeconds = max(0, start)
    endSeconds = max(startSeconds, end)
    self.pitch = max(0, min(127, pitch))
    velocity = max(1, min(127, Int((Self.number(map["velocity"]) ?? 96).rounded())))
  }

  private static func number(_ value: Any?) -> Double? {
    if let number = value as? NSNumber { return number.doubleValue }
    if let string = value as? String { return Double(string) }
    return nil
  }
}

enum PitchRecordingAudio {
  enum RecordingError: LocalizedError {
    case emptyRecording
    case recordingTooLong

    var errorDescription: String? {
      switch self {
      case .emptyRecording:
        return "Microphone recording is empty."
      case .recordingTooLong:
        return "Microphone recording is too long to export."
      }
    }
  }

  static func encodeWave(samples: [Int16], sampleRate: Int) throws -> Data {
    precondition(sampleRate > 0)
    let dataSize = samples.count.multipliedReportingOverflow(by: MemoryLayout<Int16>.size)
    guard !dataSize.overflow, dataSize.partialValue <= Int(UInt32.max) - waveHeaderSize else {
      throw RecordingError.recordingTooLong
    }

    var output = Data(capacity: waveHeaderSize + dataSize.partialValue)
    output.append(contentsOf: "RIFF".utf8)
    output.appendLittleEndian(UInt32(36 + dataSize.partialValue))
    output.append(contentsOf: "WAVE".utf8)
    output.append(contentsOf: "fmt ".utf8)
    output.appendLittleEndian(UInt32(16))
    output.appendLittleEndian(UInt16(1))
    output.appendLittleEndian(UInt16(1))
    output.appendLittleEndian(UInt32(sampleRate))
    output.appendLittleEndian(UInt32(sampleRate * MemoryLayout<Int16>.size))
    output.appendLittleEndian(UInt16(MemoryLayout<Int16>.size))
    output.appendLittleEndian(UInt16(16))
    output.append(contentsOf: "data".utf8)
    output.appendLittleEndian(UInt32(dataSize.partialValue))
    for sample in samples {
      output.appendLittleEndian(sample)
    }
    return output
  }

  static func renderRecognizedPitch(
    notes: [RecognizedPitchNote],
    durationSeconds: Double,
    sampleRate: Int
  ) throws -> [Int16] {
    precondition(sampleRate > 0)
    let noteDuration = notes.map(\.endSeconds).max() ?? 0
    let requestedDuration = durationSeconds.isFinite ? max(0, durationSeconds) : 0
    let duration = max(requestedDuration, noteDuration)
    guard duration * Double(sampleRate) <= Double(Int32.max) else {
      throw RecordingError.recordingTooLong
    }
    let sampleCount = Int((duration * Double(sampleRate)).rounded())
    guard sampleCount > 0 else { return [] }
    var mix = Array(repeating: Float(0), count: sampleCount)

    for note in notes {
      let start = max(0, min(sampleCount, Int((note.startSeconds * Double(sampleRate)).rounded())))
      let end = max(start, min(sampleCount, Int((note.endSeconds * Double(sampleRate)).rounded())))
      guard end > start else { continue }
      let frequency = 440 * pow(2, (note.pitch - 69) / 12)
      guard frequency > 0, frequency < Double(sampleRate) * 0.48 else { continue }

      let length = end - start
      let attack = min(Int((Double(sampleRate) * 0.008).rounded()), max(1, length / 3))
      let release = min(Int((Double(sampleRate) * 0.065).rounded()), max(1, length / 2))
      let gain = 0.32 * sqrt(Double(note.velocity) / 127)
      let phaseStep = 2 * Double.pi * frequency / Double(sampleRate)
      for index in 0..<length {
        let attackEnvelope = min(1, Double(index) / Double(attack))
        let releaseEnvelope = min(1, Double(length - index) / Double(release))
        let envelope = min(attackEnvelope, releaseEnvelope)
        let phase = phaseStep * Double(index)
        let tone = (
          sin(phase) + 0.28 * sin(phase * 2) + 0.09 * sin(phase * 3)
        ) / 1.37
        mix[start + index] += Float(tone * envelope * gain)
      }
    }

    let peak = max(1, mix.reduce(Float(0)) { max($0, abs($1)) })
    let normalization = Float(0.92) / peak
    return mix.map { sample in
      let value = Int((sample * normalization * Float(Int16.max)).rounded())
      return Int16(clamping: value)
    }
  }

  static func save(
    snapshot: PitchRecordingSnapshot,
    noteMaps: [[String: Any]],
    durationSeconds: Double,
    suggestedName: String
  ) throws -> [String: Any] {
    guard !snapshot.samples.isEmpty else { throw RecordingError.emptyRecording }
    let notes = noteMaps.compactMap(RecognizedPitchNote.init(map:))
    let duration = max(
      durationSeconds.isFinite ? durationSeconds : 0,
      Double(snapshot.samples.count) / Double(snapshot.sampleRate)
    )
    let safeStem = safeFileStem(suggestedName)
    let recordingName = "\(safeStem)_recording.wav"
    let recognizedName = "\(safeStem)_recognized.wav"
    let recordingWave = try encodeWave(samples: snapshot.samples, sampleRate: snapshot.sampleRate)
    let recognizedSamples = try renderRecognizedPitch(
      notes: notes,
      durationSeconds: duration,
      sampleRate: snapshot.sampleRate
    )
    let recognizedWave = try encodeWave(samples: recognizedSamples, sampleRate: snapshot.sampleRate)

    let documents = try FileManager.default.url(
      for: .documentDirectory,
      in: .userDomainMask,
      appropriateFor: nil,
      create: true
    )
    let directory = documents.appendingPathComponent("XenSynth", isDirectory: true)
    try FileManager.default.createDirectory(
      at: directory,
      withIntermediateDirectories: true,
      attributes: nil
    )
    let recordingURL = directory.appendingPathComponent(recordingName)
    let recognizedURL = directory.appendingPathComponent(recognizedName)
    do {
      try recordingWave.write(to: recordingURL, options: .atomic)
      try recognizedWave.write(to: recognizedURL, options: .atomic)
    } catch {
      try? FileManager.default.removeItem(at: recordingURL)
      try? FileManager.default.removeItem(at: recognizedURL)
      throw error
    }

    return [
      "saved": true,
      "directory": "Documents/XenSynth",
      "recordingName": recordingName,
      "recognizedName": recognizedName,
      "recordingUri": recordingURL.absoluteString,
      "recognizedUri": recognizedURL.absoluteString,
    ]
  }

  private static func safeFileStem(_ value: String) -> String {
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
    let replaced = value.unicodeScalars.map { allowed.contains($0) ? Character(String($0)) : "_" }
    let trimmed = String(replaced).trimmingCharacters(in: CharacterSet(charactersIn: "_.-"))
    return trimmed.isEmpty ? "XenSynth_microphone" : trimmed
  }

  private static let waveHeaderSize = 44
}

private extension Data {
  mutating func appendLittleEndian<Value: FixedWidthInteger>(_ value: Value) {
    var littleEndian = value.littleEndian
    Swift.withUnsafeBytes(of: &littleEndian) { bytes in
      append(contentsOf: bytes)
    }
  }
}
