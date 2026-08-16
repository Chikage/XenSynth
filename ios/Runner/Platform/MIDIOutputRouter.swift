import CoreMIDI
import Foundation
import Network

/// Routes generated MIDI 1.0 events to CoreMIDI destinations and UDP network MIDI.
final class MIDIOutputRouter {
  private static let channelCount = 16
  private static let pitchBendCenter = 8192
  private static let pitchBendMaximum = 16_383
  private static let pitchBendRangeSemitones = 2.0

  private struct ActiveNote {
    let token: Int
    let id: Int?
    let channel: Int
    let key: Int
  }

  private var client = MIDIClientRef()
  private var outputPort = MIDIPortRef()
  private var selectedBluetoothDestinationIds = Set<String>()
  private var outputEnabled = true
  private var activeNotes: [Int: ActiveNote] = [:]
  private var activeIds: [Int: Int] = [:]
  private var nextToken = 1
  private let networkQueue = DispatchQueue(
    label: "icu.ringona.xensynth.network-midi",
    qos: .userInitiated
  )
  private var networkConnection: NWConnection?

  func bluetoothDestinations() -> [[String: Any]] {
    endpoints().compactMap { endpoint in
      guard let id = endpointId(for: endpoint) else { return nil }
      return ["id": id, "name": displayName(for: endpoint)]
    }
  }

  func setBluetoothDestinationIds(_ ids: [String]) {
    selectedBluetoothDestinationIds = Set(ids.filter { !$0.isEmpty })
  }

  func setOutputEnabled(_ enabled: Bool) {
    if !enabled { allNotesOff() }
    outputEnabled = enabled
  }

  func configureNetwork(enabled: Bool, host: String, port: Int) {
    networkConnection?.cancel()
    networkConnection = nil
    guard enabled, !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
          (1...65_535).contains(port),
          let networkPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
      return
    }
    let connection = NWConnection(
      host: NWEndpoint.Host(host.trimmingCharacters(in: .whitespacesAndNewlines)),
      port: networkPort,
      using: .udp
    )
    connection.start(queue: networkQueue)
    networkConnection = connection
  }

  @discardableResult
  func noteOn(
    id: Int?,
    pitch: Double,
    velocity: Int,
    channel: Int,
    program: Int,
    bankMsb: Int,
    bankLsb: Int
  ) -> Int {
    let key = Int(pitch.rounded()).clamped(to: 0...127)
    let preferredChannel = channel.clamped(to: 0...15)
    let previous = id.flatMap { activeIds[$0] }.flatMap { activeNotes.removeValue(forKey: $0) }
    if let previous, let id = previous.id { activeIds.removeValue(forKey: id) }
    let stolen = activeNotes.count >= Self.channelCount
      ? activeNotes.keys.min().flatMap { activeNotes.removeValue(forKey: $0) }
      : nil
    if let stolen, let id = stolen.id { activeIds.removeValue(forKey: id) }

    let active = ActiveNote(
      token: nextToken,
      id: id,
      channel: availableChannel(preferred: preferredChannel),
      key: key
    )
    nextToken = nextToken == Int.max ? 1 : nextToken + 1
    activeNotes[active.token] = active
    if let id { activeIds[id] = active.token }

    if let previous { sendNoteOff(previous) }
    if let stolen { sendNoteOff(stolen) }
    sendMessages([
      controlChange(channel: active.channel, controller: 0, value: bankMsb),
      controlChange(channel: active.channel, controller: 32, value: bankLsb),
      [UInt8(0xC0 | active.channel), UInt8(program.clamped(to: 0...127))],
      controlChange(channel: active.channel, controller: 101, value: 0),
      controlChange(channel: active.channel, controller: 100, value: 0),
      controlChange(channel: active.channel, controller: 6, value: 2),
      controlChange(channel: active.channel, controller: 38, value: 0),
      controlChange(channel: active.channel, controller: 101, value: 127),
      controlChange(channel: active.channel, controller: 100, value: 127),
      pitchBend(channel: active.channel, value: pitchBendValue(pitch: pitch, key: key)),
      [UInt8(0x90 | active.channel), UInt8(key), UInt8(velocity.clamped(to: 1...127))],
    ])
    return active.token
  }

  func noteOff(_ token: Int) {
    guard let active = activeNotes.removeValue(forKey: token) else { return }
    if let id = active.id { activeIds.removeValue(forKey: id) }
    sendNoteOff(active)
  }

  func allNotesOff() {
    let notes = Array(activeNotes.values)
    activeNotes.removeAll()
    activeIds.removeAll()
    notes.forEach(sendNoteOff)
    sendMessages((0..<Self.channelCount).flatMap { channel in
      [
        controlChange(channel: channel, controller: 120, value: 0),
        controlChange(channel: channel, controller: 123, value: 0),
      ]
    })
  }

  private func availableChannel(preferred: Int) -> Int {
    let occupied = Set(activeNotes.values.map(\.channel))
    if !occupied.contains(preferred) { return preferred }
    return (0..<Self.channelCount).first(where: { !occupied.contains($0) }) ?? preferred
  }

  private func sendNoteOff(_ active: ActiveNote) {
    sendMessages([
      [UInt8(0x80 | active.channel), UInt8(active.key), 0],
      pitchBend(channel: active.channel, value: Self.pitchBendCenter),
    ])
  }

  private func sendMessages(_ messages: [[UInt8]]) {
    guard outputEnabled, !messages.isEmpty else { return }
    let destinations = endpoints().filter { endpoint in
      endpointId(for: endpoint).map(selectedBluetoothDestinationIds.contains) ?? false
    }
    for message in messages {
      for destination in destinations { send(message, to: destination) }
      networkConnection?.send(content: Data(message), completion: .contentProcessed { _ in })
    }
  }

  private func send(_ message: [UInt8], to destination: MIDIEndpointRef) {
    guard prepareOutputPort() else { return }
    var packetList = MIDIPacketList()
    let packet = MIDIPacketListInit(&packetList)
    message.withUnsafeBufferPointer { data in
      guard let baseAddress = data.baseAddress else { return }
      _ = MIDIPacketListAdd(
        &packetList,
        1_024,
        packet,
        0,
        message.count,
        baseAddress
      )
    }
    let status = MIDISend(outputPort, destination, &packetList)
    if status != noErr {
      NSLog("Xen Synth could not send CoreMIDI message: %d", status)
    }
  }

  private func prepareOutputPort() -> Bool {
    if outputPort != 0 { return true }
    var newClient = MIDIClientRef()
    guard MIDIClientCreate("Xen Synth Flutter MIDI Output" as CFString, nil, nil, &newClient) == noErr else {
      return false
    }
    var newOutputPort = MIDIPortRef()
    guard MIDIOutputPortCreate(newClient, "Xen Synth Flutter MIDI Output" as CFString, &newOutputPort) == noErr else {
      MIDIClientDispose(newClient)
      return false
    }
    client = newClient
    outputPort = newOutputPort
    return true
  }

  private func endpoints() -> [MIDIEndpointRef] {
    (0..<MIDIGetNumberOfDestinations()).compactMap { index in
      let endpoint = MIDIGetDestination(index)
      return endpoint == 0 ? nil : endpoint
    }
  }

  private func endpointId(for endpoint: MIDIEndpointRef) -> String? {
    var uniqueId: Int32 = 0
    guard MIDIObjectGetIntegerProperty(endpoint, kMIDIPropertyUniqueID, &uniqueId) == noErr else {
      return nil
    }
    return "coremidi:\(uniqueId)"
  }

  private func displayName(for endpoint: MIDIEndpointRef) -> String {
    var unmanagedName: Unmanaged<CFString>?
    guard MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &unmanagedName) == noErr,
          let name = unmanagedName?.takeRetainedValue() else {
      return "MIDI output"
    }
    return name as String
  }

  private func controlChange(channel: Int, controller: Int, value: Int) -> [UInt8] {
    [
      UInt8(0xB0 | channel.clamped(to: 0...15)),
      UInt8(controller.clamped(to: 0...127)),
      UInt8(value.clamped(to: 0...127)),
    ]
  }

  private func pitchBend(channel: Int, value: Int) -> [UInt8] {
    let safeValue = value.clamped(to: 0...Self.pitchBendMaximum)
    return [
      UInt8(0xE0 | channel.clamped(to: 0...15)),
      UInt8(safeValue & 0x7F),
      UInt8((safeValue >> 7) & 0x7F),
    ]
  }

  private func pitchBendValue(pitch: Double, key: Int) -> Int {
    let bend = Double(Self.pitchBendCenter) +
      (pitch - Double(key)) / Self.pitchBendRangeSemitones * Double(Self.pitchBendCenter)
    return Int(bend.rounded()).clamped(to: 0...Self.pitchBendMaximum)
  }

  deinit {
    allNotesOff()
    networkConnection?.cancel()
    if outputPort != 0 { MIDIPortDispose(outputPort) }
    if client != 0 { MIDIClientDispose(client) }
  }
}
