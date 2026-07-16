import Foundation

struct NativeScoreNote: Equatable {
  let start: Double
  let end: Double
  let pitch: Double
  let velocity: Int
  let channel: Int
  let program: Int
  let bankMsb: Int
  let bankLsb: Int
  let track: Int

  init(map: [String: Any], index: Int) throws {
    guard let start = map.double(forAnyKey: ["start", "startSeconds", "startTime", "time"]) else {
      throw NativeScoreError.invalidNote(index: index, reason: "missing start")
    }

    let explicitEnd = map.double(forAnyKey: ["end", "endSeconds", "endTime", "stop"])
    let duration = map.double(forAnyKey: ["duration", "length"])
    guard let end = explicitEnd ?? duration.map({ start + $0 }) else {
      throw NativeScoreError.invalidNote(index: index, reason: "missing end or duration")
    }

    let explicitPitch = map.double(forAnyKey: ["pitch", "audioPitch", "pitchFloat"])
    let midiPitch = map.double(forAnyKey: ["midiPitch", "note", "noteNumber", "key"])
    let cents = map.double(forAnyKey: ["cents", "centOffset"]) ?? 0
    guard let pitch = explicitPitch ?? midiPitch.map({ $0 + cents / 100.0 }) else {
      throw NativeScoreError.invalidNote(index: index, reason: "missing pitch")
    }

    guard start.isFinite, end.isFinite, pitch.isFinite else {
      throw NativeScoreError.invalidNote(index: index, reason: "time and pitch must be finite")
    }

    self.start = max(0, start)
    self.end = max(self.start, end)
    self.pitch = pitch
    velocity = (map.int(forAnyKey: ["velocity", "vel"]) ?? 96).clamped(to: 1...127)
    channel = (map.int(forAnyKey: ["channel", "midiChannel"]) ?? 0).clamped(to: 0...15)
    program = (map.int(forAnyKey: ["program", "programNumber", "instrument"]) ?? 0).clamped(to: 0...127)
    bankMsb = (map.int(forAnyKey: ["bankMsb", "bankMSB", "bank_msb"]) ?? 0).clamped(to: 0...127)
    bankLsb = (map.int(forAnyKey: ["bankLsb", "bankLSB", "bank_lsb"]) ?? 0).clamped(to: 0...127)
    track = max(0, map.int(forAnyKey: ["track", "trackIndex"]) ?? 0)
  }
}

struct NativeScore: Equatable {
  let notes: [NativeScoreNote]
  let duration: Double

  init(noteMaps: [[String: Any]], declaredDuration: Double?) throws {
    var parsed: [NativeScoreNote] = []
    parsed.reserveCapacity(noteMaps.count)
    for (index, map) in noteMaps.enumerated() {
      parsed.append(try NativeScoreNote(map: map, index: index))
    }

    notes = parsed.sorted {
      if $0.start != $1.start { return $0.start < $1.start }
      if $0.pitch != $1.pitch { return $0.pitch < $1.pitch }
      return $0.track < $1.track
    }
    let notesDuration = notes.map(\.end).max() ?? 0
    duration = max(notesDuration, declaredDuration?.isFinite == true ? max(0, declaredDuration ?? 0) : 0)
  }
}

enum NativeScoreError: LocalizedError {
  case missingNotes
  case invalidNote(index: Int, reason: String)

  var errorDescription: String? {
    switch self {
    case .missingNotes:
      return "Score payload must contain a notes list."
    case let .invalidNote(index, reason):
      return "Invalid score note at index \(index): \(reason)."
    }
  }
}

extension Dictionary where Key == String, Value == Any {
  func double(forAnyKey keys: [String]) -> Double? {
    for key in keys {
      guard let value = self[key], !(value is NSNull) else { continue }
      if let number = value as? NSNumber { return number.doubleValue }
      if let string = value as? String, let number = Double(string) { return number }
    }
    return nil
  }

  func int(forAnyKey keys: [String]) -> Int? {
    double(forAnyKey: keys).map { Int($0.rounded()) }
  }

  func bool(forAnyKey keys: [String]) -> Bool? {
    for key in keys {
      guard let value = self[key], !(value is NSNull) else { continue }
      if let bool = value as? Bool { return bool }
      if let number = value as? NSNumber { return number.boolValue }
      if let string = value as? String {
        switch string.lowercased() {
        case "true", "yes", "1": return true
        case "false", "no", "0": return false
        default: continue
        }
      }
    }
    return nil
  }
}

extension Comparable {
  func clamped(to range: ClosedRange<Self>) -> Self {
    min(max(self, range.lowerBound), range.upperBound)
  }
}
