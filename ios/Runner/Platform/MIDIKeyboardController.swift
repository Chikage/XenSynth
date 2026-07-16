import CoreMIDI
import Foundation

final class MIDIKeyboardController {
  var onEvent: (([String: Any]) -> Void)?

  private var client = MIDIClientRef()
  private var inputPort = MIDIPortRef()
  private var connectedSources = Set<MIDIEndpointRef>()
  private var isStarted = false

  func start() throws {
    guard !isStarted else {
      refreshConnections()
      return
    }

    var newClient = MIDIClientRef()
    let clientStatus = MIDIClientCreate(
      "Xen Synth Flutter MIDI Client" as CFString,
      Self.notifyProc,
      Unmanaged.passUnretained(self).toOpaque(),
      &newClient
    )
    guard clientStatus == noErr else {
      throw MIDIKeyboardError.coreMIDIStatus(clientStatus, operation: "create MIDI client")
    }

    var newInputPort = MIDIPortRef()
    let inputStatus = MIDIInputPortCreate(
      newClient,
      "Xen Synth Flutter MIDI Input" as CFString,
      Self.readProc,
      Unmanaged.passUnretained(self).toOpaque(),
      &newInputPort
    )
    guard inputStatus == noErr else {
      MIDIClientDispose(newClient)
      throw MIDIKeyboardError.coreMIDIStatus(inputStatus, operation: "create MIDI input port")
    }

    client = newClient
    inputPort = newInputPort
    isStarted = true
    refreshConnections()
  }

  func stop() {
    guard isStarted else { return }
    for source in connectedSources {
      MIDIPortDisconnectSource(inputPort, source)
    }
    connectedSources.removeAll()
    MIDIPortDispose(inputPort)
    MIDIClientDispose(client)
    inputPort = MIDIPortRef()
    client = MIDIClientRef()
    isStarted = false
    emit(["type": "allNotesOff"])
  }

  private func refreshConnections() {
    guard isStarted, inputPort != 0 else { return }
    let sources = Set((0..<MIDIGetNumberOfSources()).compactMap { index -> MIDIEndpointRef? in
      let source = MIDIGetSource(index)
      return source == 0 ? nil : source
    })
    connectedSources.formIntersection(sources)
    for source in sources where !connectedSources.contains(source) {
      if MIDIPortConnectSource(inputPort, source, nil) == noErr {
        connectedSources.insert(source)
      }
    }
  }

  private func handle(packetList: UnsafePointer<MIDIPacketList>) {
    let parsed = Self.events(from: packetList)
    guard !parsed.isEmpty else { return }
    DispatchQueue.main.async { [weak self] in
      for event in parsed { self?.emit(event) }
    }
  }

  private func emit(_ event: [String: Any]) {
    onEvent?(event)
  }

  private static let notifyProc: MIDINotifyProc = { notification, refCon in
    guard let refCon else { return }
    let controller = Unmanaged<MIDIKeyboardController>.fromOpaque(refCon).takeUnretainedValue()
    switch notification.pointee.messageID {
    case .msgObjectAdded, .msgSetupChanged:
      DispatchQueue.main.async { controller.refreshConnections() }
    case .msgObjectRemoved:
      DispatchQueue.main.async {
        controller.refreshConnections()
        controller.emit(["type": "allNotesOff"])
      }
    default:
      break
    }
  }

  private static let readProc: MIDIReadProc = { packetList, refCon, _ in
    guard let refCon else { return }
    let controller = Unmanaged<MIDIKeyboardController>.fromOpaque(refCon).takeUnretainedValue()
    controller.handle(packetList: packetList)
  }

  private static func events(from packetList: UnsafePointer<MIDIPacketList>) -> [[String: Any]] {
    var parsed: [[String: Any]] = []
    var runningStatus: UInt8?

    withUnsafePointer(to: packetList.pointee.packet) { firstPacket in
      var packet = UnsafeMutablePointer(mutating: firstPacket)
      for _ in 0..<packetList.pointee.numPackets {
        let current = packet.pointee
        let bytes = withUnsafeBytes(of: current.data) { rawBuffer in
          Array(rawBuffer.prefix(Int(current.length)))
        }
        parse(bytes, runningStatus: &runningStatus, into: &parsed)
        packet = MIDIPacketNext(packet)
      }
    }
    return parsed
  }

  private static func parse(
    _ bytes: [UInt8],
    runningStatus: inout UInt8?,
    into events: inout [[String: Any]]
  ) {
    var index = 0
    while index < bytes.count {
      let byte = bytes[index]
      let status: UInt8
      if byte & 0x80 != 0 {
        status = byte
        index += 1
        guard status < 0xF0 else {
          if status < 0xF8 { runningStatus = nil }
          skipSystemMessage(status: status, bytes: bytes, index: &index)
          continue
        }
        runningStatus = status
      } else if let previous = runningStatus {
        status = previous
      } else {
        index += 1
        continue
      }

      guard let dataLength = channelDataLength(for: status), index + dataLength <= bytes.count else {
        break
      }
      let data1 = bytes[index]
      let data2 = dataLength > 1 ? bytes[index + 1] : 0
      appendEvent(status: status, data1: data1, data2: data2, to: &events)
      index += dataLength
    }
  }

  private static func appendEvent(
    status: UInt8,
    data1: UInt8,
    data2: UInt8,
    to events: inout [[String: Any]]
  ) {
    let channel = Int(status & 0x0F)
    switch status & 0xF0 {
    case 0x80:
      events.append(noteEvent(type: "noteOff", channel: channel, note: Int(data1), velocity: 0))
    case 0x90:
      let type = data2 == 0 ? "noteOff" : "noteOn"
      events.append(noteEvent(type: type, channel: channel, note: Int(data1), velocity: Int(data2)))
    case 0xB0:
      switch data1 {
      case 64:
        events.append([
          "type": "sustain",
          "channel": channel,
          "enabled": data2 >= 64,
          "down": data2 >= 64,
          "value": Int(data2),
        ])
      case 120, 123...127:
        events.append(["type": "allNotesOff", "channel": channel])
      default:
        break
      }
    case 0xC0:
      events.append([
        "type": "program",
        "channel": channel,
        "program": Int(data1 & 0x7F),
      ])
    default:
      break
    }
  }

  private static func noteEvent(
    type: String,
    channel: Int,
    note: Int,
    velocity: Int
  ) -> [String: Any] {
    [
      "type": type,
      "channel": channel,
      "pitch": note,
      "note": note,
      "noteNumber": note,
      "velocity": velocity,
    ]
  }

  private static func channelDataLength(for status: UInt8) -> Int? {
    switch status & 0xF0 {
    case 0x80, 0x90, 0xA0, 0xB0, 0xE0: return 2
    case 0xC0, 0xD0: return 1
    default: return nil
    }
  }

  private static func skipSystemMessage(status: UInt8, bytes: [UInt8], index: inout Int) {
    switch status {
    case 0xF0:
      while index < bytes.count, bytes[index] != 0xF7 { index += 1 }
      if index < bytes.count { index += 1 }
    case 0xF1, 0xF3:
      index = min(index + 1, bytes.count)
    case 0xF2:
      index = min(index + 2, bytes.count)
    default:
      break
    }
  }

  deinit {
    stop()
  }
}

private enum MIDIKeyboardError: LocalizedError {
  case coreMIDIStatus(OSStatus, operation: String)

  var errorDescription: String? {
    switch self {
    case let .coreMIDIStatus(status, operation):
      return "Could not \(operation) (CoreMIDI status \(status))."
    }
  }
}
