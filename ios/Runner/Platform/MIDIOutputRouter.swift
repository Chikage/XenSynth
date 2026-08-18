import CoreMIDI
import Darwin
import Foundation

enum AppleMIDIPortPolicy {
  // CoreMIDI exposes a read-only AppleMIDI control port and owns the adjacent data port. It starts
  // at 5004/5005 and advances by one port pair when an earlier pair is unavailable.
  static let fixedControlPort = 5_004
  private static let lastControlPort = Int(UInt16.max) - 1

  static func allowsActiveTransport(
    sessionEnabled: Bool,
    networkPort: Int
  ) -> Bool {
    sessionEnabled
      && networkPort >= fixedControlPort
      && networkPort <= lastControlPort
      && (networkPort - fixedControlPort).isMultiple(of: 2)
  }
}

/// Selects the address that should be used for a Bonjour AppleMIDI peer.
///
/// `NetService` can expose both IPv6 and IPv4 socket addresses, and CoreMIDI's
/// `netService:` initializer is free to choose either one.  The Android and
/// iOS transports in this app use an IPv4 UDP socket, so keep the selection
/// deterministic and limited to addresses that are meaningful on a LAN.
enum AppleMIDIIPv4AddressSelector {
  static func select(from addresses: [Data]) -> String? {
    lanAddresses(from: addresses).first
  }

  static func lanAddresses(from addresses: [Data]) -> [String] {
    Array(Set(addresses
      .compactMap(address(from:))
      .filter(isUsableLanIPv4(_:))))
      .sorted {
        let lhsRank = rank(of: $0)
        let rhsRank = rank(of: $1)
        if lhsRank != rhsRank { return lhsRank < rhsRank }
        return $0.localizedStandardCompare($1) == .orderedAscending
      }
  }

  static func isPrivate(_ address: String) -> Bool {
    rank(of: address) == 0
  }

  static func isLanIPv4(_ address: String) -> Bool {
    rank(of: address) <= 1
  }

  static func localInterfaceAddresses() -> Set<String> {
    var firstAddress: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&firstAddress) == 0, let firstAddress else { return [] }
    defer { freeifaddrs(firstAddress) }

    var result = Set<String>()
    var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddress
    while let interface = cursor {
      cursor = interface.pointee.ifa_next
      guard let socketAddress = interface.pointee.ifa_addr,
            Int32(socketAddress.pointee.sa_family) == AF_INET else { continue }
      let ipv4 = UnsafeRawPointer(socketAddress)
        .assumingMemoryBound(to: sockaddr_in.self)
      var networkAddress = ipv4.pointee.sin_addr
      var host = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
      guard inet_ntop(AF_INET, &networkAddress, &host, socklen_t(host.count)) != nil else {
        continue
      }
      let terminator = host.firstIndex(of: 0) ?? host.endIndex
      let address = String(decoding: host[..<terminator].map {
        UInt8(bitPattern: $0)
      }, as: UTF8.self)
      if isLanIPv4(address) { result.insert(address) }
    }
    return result
  }

  private static func address(from data: Data) -> String? {
    guard data.count >= MemoryLayout<sockaddr_in>.size else { return nil }
    var storage = sockaddr_in()
    data.withUnsafeBytes { rawBuffer in
      guard let baseAddress = rawBuffer.baseAddress else { return }
      memcpy(
        &storage,
        baseAddress,
        min(rawBuffer.count, MemoryLayout<sockaddr_in>.size)
      )
    }
    if storage.sin_family != UInt8(AF_INET) {
      guard data.count >= MemoryLayout<sockaddr_in6>.size else { return nil }
      var ipv6Storage = sockaddr_in6()
      data.withUnsafeBytes { rawBuffer in
        guard let baseAddress = rawBuffer.baseAddress else { return }
        memcpy(
          &ipv6Storage,
          baseAddress,
          min(rawBuffer.count, MemoryLayout<sockaddr_in6>.size)
        )
      }
      guard ipv6Storage.sin6_family == UInt8(AF_INET6) else { return nil }
      var ipv6Address = ipv6Storage.sin6_addr
      var ipv6Host = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
      guard inet_ntop(AF_INET6, &ipv6Address, &ipv6Host, socklen_t(ipv6Host.count)) != nil else {
        return nil
      }
      let ipv6Terminator = ipv6Host.firstIndex(of: 0) ?? ipv6Host.endIndex
      let text = String(decoding: ipv6Host[..<ipv6Terminator].map {
        UInt8(bitPattern: $0)
      }, as: UTF8.self).lowercased()
      guard let marker = text.range(of: "::ffff:") else { return nil }
      // Keep the complete dotted-quad suffix.  Initializing String from a
      // single Character would reduce `::ffff:192.168.1.10` to `"1"` and
      // silently discard an otherwise usable IPv4-mapped address.
      let mapped = String(text[marker.upperBound...])
      return isIPv4Literal(mapped) ? mapped : nil
    }

    var networkAddress = storage.sin_addr
    var host = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
    guard inet_ntop(AF_INET, &networkAddress, &host, socklen_t(host.count)) != nil else {
      return nil
    }
    let terminator = host.firstIndex(of: 0) ?? host.endIndex
    return String(decoding: host[..<terminator].map {
      UInt8(bitPattern: $0)
    }, as: UTF8.self)
  }

  private static func isIPv4Literal(_ address: String) -> Bool {
    let octets = address.split(separator: ".").compactMap { Int($0) }
    return octets.count == 4 && octets.allSatisfy { (0...255).contains($0) }
  }

  private static func rank(of address: String) -> Int {
    let octets = address.split(separator: ".").compactMap { Int($0) }
    guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else {
      return 3
    }
    if octets[0] == 10
      || octets[0] == 192 && octets[1] == 168
      || octets[0] == 172 && (16...31).contains(octets[1]) {
      return 0
    }
    if octets[0] == 169 && octets[1] == 254 {
      return 1
    }
    return 2
  }

  private static func isUsableLanIPv4(_ address: String) -> Bool {
    rank(of: address) <= 1
  }
}

private func makeNetworkMIDIPanicMessages(channelCount: Int) -> [[UInt8]] {
  var messages: [[UInt8]] = []
  messages.reserveCapacity(channelCount * 3)
  for channel in 0..<channelCount {
    let status = UInt8(0xB0 | channel)
    messages.append([status, 64, 0])
    messages.append([status, 120, 0])
    messages.append([status, 123, 0])
  }
  return messages
}

/// Applies bounded batching and loss mitigation before CoreMIDI packetizes AppleMIDI output.
final class NetworkMIDIOutputBuffer {
  private enum Priority: Int {
    case continuous
    case discrete
    case critical
  }

  private enum CoalescingKey: Equatable {
    case pitchBend(channel: UInt8)
    case channelPressure(channel: UInt8)
    case polyPressure(channel: UInt8, note: UInt8)
    case controller(channel: UInt8, number: UInt8)
  }

  private enum RetryGuard {
    case note(channel: UInt8, note: UInt8, generation: UInt64)
    case sustain(channel: UInt8, generation: UInt64)
    case channel(channel: UInt8, generation: UInt64)
  }

  private struct PendingMessage {
    let bytes: [UInt8]
    let priority: Priority
    let coalescingKey: CoalescingKey?
  }

  static let defaultMaximumPendingMessages = 1_536
  static let defaultNormalBatchDelayNanoseconds: UInt64 = 2_000_000
  static let defaultCongestedBatchDelayNanoseconds: UInt64 = 4_000_000
  static let defaultCriticalBatchDelayNanoseconds: UInt64 = 500_000
  static let defaultCriticalRetryDelayNanoseconds: UInt64 = 24_000_000

  var onMessages: (([[UInt8]]) -> Bool)?

  private static let maximumMessagesPerFlush = 256
  private static let maximumBytesPerFlush = 900
  private static let orderedControllers: Set<UInt8> = [
    0, 6, 32, 38, 64, 96, 97, 98, 99, 100, 101,
    120, 121, 122, 123, 124, 125, 126, 127,
  ]
  private static let panicMessages = makeNetworkMIDIPanicMessages(channelCount: 16)

  private let maximumPendingMessages: Int
  private let normalBatchDelayNanoseconds: UInt64
  private let congestedBatchDelayNanoseconds: UInt64
  private let criticalBatchDelayNanoseconds: UInt64
  private let criticalRetryDelayNanoseconds: UInt64
  private let queue = DispatchQueue(
    label: "icu.ringona.xensynth.network-midi-output-buffer",
    qos: .userInteractive
  )
  private var timer: DispatchSourceTimer!
  private var pending: [PendingMessage] = []
  private var panicPending = false
  private var scheduledDeadlineNanoseconds: UInt64?
  private var noteGenerations: [Int: UInt64] = [:]
  private var channelGenerations = Array(repeating: UInt64(0), count: 16)
  private var sustainGenerations = Array(repeating: UInt64(0), count: 16)
  private var lifecycleEpoch: UInt64 = 0
  private var closed = false

  init(
    maximumPendingMessages: Int = NetworkMIDIOutputBuffer.defaultMaximumPendingMessages,
    normalBatchDelayNanoseconds: UInt64 = NetworkMIDIOutputBuffer.defaultNormalBatchDelayNanoseconds,
    congestedBatchDelayNanoseconds: UInt64 = NetworkMIDIOutputBuffer.defaultCongestedBatchDelayNanoseconds,
    criticalBatchDelayNanoseconds: UInt64 = NetworkMIDIOutputBuffer.defaultCriticalBatchDelayNanoseconds,
    criticalRetryDelayNanoseconds: UInt64 = NetworkMIDIOutputBuffer.defaultCriticalRetryDelayNanoseconds
  ) {
    precondition(maximumPendingMessages > 0)
    self.maximumPendingMessages = maximumPendingMessages
    self.normalBatchDelayNanoseconds = normalBatchDelayNanoseconds
    self.congestedBatchDelayNanoseconds = congestedBatchDelayNanoseconds
    self.criticalBatchDelayNanoseconds = criticalBatchDelayNanoseconds
    self.criticalRetryDelayNanoseconds = criticalRetryDelayNanoseconds
    timer = DispatchSource.makeTimerSource(queue: queue)
    timer.setEventHandler { [weak self] in
      self?.drainScheduledBatch()
    }
    timer.schedule(deadline: .distantFuture)
    timer.resume()
  }

  func enqueue(_ messages: [[UInt8]]) {
    let safeMessages = messages.filter(Self.isChannelMessage)
    guard !safeMessages.isEmpty else { return }
    queue.async { [weak self] in
      guard let self, !self.closed else { return }
      for message in safeMessages {
        self.observeStateChange(message)
        let retryGuard = self.retryGuard(for: message)
        if self.append(message), let retryGuard {
          self.scheduleRetry(message, guardedBy: retryGuard)
        }
      }
      self.scheduleNextFlush()
    }
  }

  func flushSynchronously() {
    queue.sync {
      guard !closed else { return }
      while panicPending || !pending.isEmpty {
        emitNextBatch()
      }
      suspendTimer()
    }
  }

  func clear() {
    queue.sync {
      guard !closed else { return }
      lifecycleEpoch &+= 1
      pending.removeAll(keepingCapacity: true)
      panicPending = false
      suspendTimer()
    }
  }

  func close() {
    queue.sync {
      guard !closed else { return }
      closed = true
      lifecycleEpoch &+= 1
      pending.removeAll()
      panicPending = false
      scheduledDeadlineNanoseconds = nil
      timer.cancel()
      onMessages = nil
    }
  }

  private func append(_ bytes: [UInt8]) -> Bool {
    let priority = Self.priority(of: bytes)
    let key = Self.coalescingKey(for: bytes)
    if let key,
       let existingIndex = pending.lastIndex(where: { $0.coalescingKey == key }) {
      pending.remove(at: existingIndex)
    }

    if pending.count >= maximumPendingMessages {
      let evictionIndex: Int?
      switch priority {
      case .continuous:
        evictionIndex = pending.firstIndex { $0.priority == .continuous }
      case .discrete:
        evictionIndex = pending.firstIndex { $0.priority == .continuous }
          ?? pending.firstIndex { $0.priority == .discrete }
      case .critical:
        evictionIndex = pending.firstIndex { $0.priority == .continuous }
          ?? pending.firstIndex { $0.priority == .discrete }
      }
      if let evictionIndex {
        pending.remove(at: evictionIndex)
      } else if priority == .critical {
        pending.removeAll(keepingCapacity: true)
        panicPending = true
        lifecycleEpoch &+= 1
      } else {
        return false
      }
    }

    pending.append(PendingMessage(
      bytes: bytes,
      priority: priority,
      coalescingKey: key
    ))
    return true
  }

  private func scheduleNextFlush() {
    guard panicPending || !pending.isEmpty else {
      suspendTimer()
      return
    }
    let containsCritical = panicPending || pending.contains { $0.priority == .critical }
    let isCongested = pending.count >= max(1, maximumPendingMessages / 2)
    let delay = containsCritical
      ? criticalBatchDelayNanoseconds
      : (isCongested ? congestedBatchDelayNanoseconds : normalBatchDelayNanoseconds)
    let deadline = DispatchTime.now().uptimeNanoseconds &+ delay
    if let scheduledDeadlineNanoseconds, scheduledDeadlineNanoseconds <= deadline {
      return
    }
    scheduledDeadlineNanoseconds = deadline
    timer.schedule(
      deadline: DispatchTime(uptimeNanoseconds: deadline),
      leeway: .microseconds(250)
    )
  }

  private func drainScheduledBatch() {
    guard !closed else { return }
    scheduledDeadlineNanoseconds = nil
    emitNextBatch()
    // Preserve event order while allowing a release behind a large backlog to
    // catch up without another pacing interval.
    while pending.contains(where: { $0.priority == .critical }) {
      emitNextBatch()
    }
    scheduleNextFlush()
  }

  private func emitNextBatch() {
    var messages: [[UInt8]] = []
    var byteCount = 0
    if panicPending {
      messages.append(contentsOf: Self.panicMessages)
      byteCount = Self.panicMessages.reduce(0) { $0 + $1.count }
      panicPending = false
    }
    while !pending.isEmpty, messages.count < Self.maximumMessagesPerFlush {
      let next = pending[0]
      if !messages.isEmpty, byteCount + next.bytes.count > Self.maximumBytesPerFlush {
        break
      }
      pending.removeFirst()
      messages.append(next.bytes)
      byteCount += next.bytes.count
    }
    if !messages.isEmpty, onMessages?(messages) != true {
      // A disabled session or fallback control port must not retain retries
      // that could become stale before fixed-port transport is restored.
      lifecycleEpoch &+= 1
      pending.removeAll(keepingCapacity: true)
      panicPending = false
    }
  }

  private func suspendTimer() {
    scheduledDeadlineNanoseconds = nil
    timer.schedule(deadline: .distantFuture)
  }

  private func observeStateChange(_ message: [UInt8]) {
    let status = message[0]
    let command = status & 0xF0
    let channel = status & 0x0F
    if command == 0x90, message.count >= 3, message[2] > 0 {
      let key = Self.noteKey(channel: channel, note: message[1])
      noteGenerations[key, default: 0] &+= 1
      channelGenerations[Int(channel)] &+= 1
    } else if command == 0xB0, message.count >= 3, message[1] == 64 {
      sustainGenerations[Int(channel)] &+= 1
    }
  }

  private func retryGuard(for message: [UInt8]) -> RetryGuard? {
    let status = message[0]
    let command = status & 0xF0
    let channel = status & 0x0F
    let isNoteOff = command == 0x80 && message.count >= 2
      || command == 0x90 && message.count >= 3 && message[2] == 0
    if isNoteOff {
      let note = message[1]
      return .note(
        channel: channel,
        note: note,
        generation: noteGenerations[Self.noteKey(channel: channel, note: note), default: 0]
      )
    }
    guard command == 0xB0, message.count >= 3 else { return nil }
    if message[1] == 64, message[2] < 64 {
      return .sustain(
        channel: channel,
        generation: sustainGenerations[Int(channel)]
      )
    }
    if message[1] == 120 || message[1] == 123 {
      return .channel(
        channel: channel,
        generation: channelGenerations[Int(channel)]
      )
    }
    return nil
  }

  private func scheduleRetry(_ message: [UInt8], guardedBy retryGuard: RetryGuard) {
    let expectedEpoch = lifecycleEpoch
    queue.asyncAfter(
      deadline: .now() + .nanoseconds(Int(criticalRetryDelayNanoseconds))
    ) { [weak self] in
      guard let self,
            !self.closed,
            self.lifecycleEpoch == expectedEpoch,
            self.isValid(retryGuard) else { return }
      if self.append(message) {
        self.scheduleNextFlush()
      }
    }
  }

  private func isValid(_ retryGuard: RetryGuard) -> Bool {
    switch retryGuard {
    case let .note(channel, note, generation):
      return noteGenerations[Self.noteKey(channel: channel, note: note), default: 0] == generation
    case let .sustain(channel, generation):
      return sustainGenerations[Int(channel)] == generation
    case let .channel(channel, generation):
      return channelGenerations[Int(channel)] == generation
    }
  }

  private static func isChannelMessage(_ message: [UInt8]) -> Bool {
    guard let status = message.first else { return false }
    switch status & 0xF0 {
    case 0xC0, 0xD0:
      return status < 0xF0 && message.count == 2
    case 0x80, 0x90, 0xA0, 0xB0, 0xE0:
      return status < 0xF0 && message.count == 3
    default:
      return false
    }
  }

  private static func priority(of message: [UInt8]) -> Priority {
    let command = message[0] & 0xF0
    if command == 0x80 || command == 0x90 && message.count >= 3 && message[2] == 0 {
      return .critical
    }
    let isCriticalController = command == 0xB0 && message.count >= 3
      && (message[1] == 120 || message[1] == 123 || message[1] == 64 && message[2] < 64)
    if isCriticalController {
      return .critical
    }
    if command == 0xA0 || command == 0xD0 || command == 0xE0 {
      return .continuous
    }
    if command == 0xB0, message.count >= 3,
       !orderedControllers.contains(message[1]) {
      return .continuous
    }
    return .discrete
  }

  private static func coalescingKey(for message: [UInt8]) -> CoalescingKey? {
    guard priority(of: message) == .continuous else { return nil }
    let command = message[0] & 0xF0
    let channel = message[0] & 0x0F
    switch command {
    case 0xA0 where message.count >= 2:
      return .polyPressure(channel: channel, note: message[1])
    case 0xB0 where message.count >= 2:
      return .controller(channel: channel, number: message[1])
    case 0xD0:
      return .channelPressure(channel: channel)
    case 0xE0:
      return .pitchBend(channel: channel)
    default:
      return nil
    }
  }

  private static func noteKey(channel: UInt8, note: UInt8) -> Int {
    Int(channel) * 128 + Int(note)
  }
}

/// Routes generated MIDI 1.0 events to local CoreMIDI destinations and AppleMIDI peers.
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
    let sendToNetwork: Bool
  }

  private var client = MIDIClientRef()
  private var outputPort = MIDIPortRef()
  private var selectedBluetoothDestinationIds = Set<String>()
  private var outputEnabled = true
  private var activeNotes: [Int: ActiveNote] = [:]
  private var activeIds: [Int: Int] = [:]
  private var nextToken = 1
  private let networkSession = AppleMIDINetworkSession()
  private let networkOutputBuffer: NetworkMIDIOutputBuffer
  private let outputStateLock = NSLock()
  private let midiSendLock = NSLock()
  private var networkEnabled = false

  init() {
    let buffer = NetworkMIDIOutputBuffer()
    networkOutputBuffer = buffer
    buffer.onMessages = { [weak self] messages in
      self?.sendBufferedNetworkMessages(messages) ?? false
    }
  }

  func bluetoothDestinations() -> [[String: Any]] {
    endpoints().compactMap { endpoint in
      guard endpoint != networkSession.destinationEndpoint else { return nil }
      guard let id = endpointId(for: endpoint) else { return nil }
      let name = displayName(for: endpoint)
      var destination: [String: Any] = [
        "id": id,
        "name": name,
        "transport": localTransportName(for: endpoint, name: name),
      ]
      if let model = stringProperty(kMIDIPropertyModel, for: endpoint), !model.isEmpty {
        destination["model"] = model
      }
      return destination
    }
  }

  func setBluetoothDestinationIds(_ ids: [String]) {
    selectedBluetoothDestinationIds = Set(ids.filter { !$0.isEmpty })
  }

  func scanNetworkDestinations(completion: @escaping ([[String: Any]]) -> Void) {
    networkSession.scan(completion: completion)
  }

  func setNetworkDestinationIds(_ ids: [String]) {
    networkSession.setDestinationIds(ids)
  }

  func setOutputEnabled(_ enabled: Bool) {
    if outputState().outputEnabled && !enabled {
      allNotesOff()
      networkOutputBuffer.flushSynchronously()
    }
    outputStateLock.lock()
    outputEnabled = enabled
    outputStateLock.unlock()
    if !enabled {
      networkOutputBuffer.clear()
    }
  }

  func configureNetwork(enabled: Bool) {
    if outputState().networkEnabled && !enabled {
      networkOutputBuffer.flushSynchronously()
      sendNetworkMessagesImmediately(Self.panicMessages)
    }
    outputStateLock.lock()
    networkEnabled = enabled
    outputStateLock.unlock()
    if !enabled {
      networkOutputBuffer.clear()
    }
    // The CoreMIDI network session is shared with MIDIKeyboardController for
    // input. Turning output off must not tear down inbound connections.
    networkSession.setEnabled(true)
  }

  @discardableResult
  func noteOn(
    id: Int?,
    pitch: Double,
    velocity: Int,
    channel: Int,
    program: Int,
    bankMsb: Int,
    bankLsb: Int,
    sendToNetwork: Bool = true
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
      key: key,
      sendToNetwork: sendToNetwork
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
    ], sendToNetwork: sendToNetwork)
    return active.token
  }

  func noteOff(_ token: Int) {
    guard let active = activeNotes.removeValue(forKey: token) else { return }
    if let id = active.id { activeIds.removeValue(forKey: id) }
    sendNoteOff(active)
  }

  func allNotesOff(sendToNetwork: Bool = true) {
    let notes = Array(activeNotes.values)
    activeNotes.removeAll()
    activeIds.removeAll()
    notes.forEach { note in
      sendNoteOff(note, sendToNetwork: sendToNetwork && note.sendToNetwork)
    }
    sendMessages((0..<Self.channelCount).flatMap { channel in
      [
        controlChange(channel: channel, controller: 120, value: 0),
        controlChange(channel: channel, controller: 123, value: 0),
      ]
    }, sendToNetwork: sendToNetwork)
  }

  private func availableChannel(preferred: Int) -> Int {
    let occupied = Set(activeNotes.values.map(\.channel))
    if !occupied.contains(preferred) { return preferred }
    return (0..<Self.channelCount).first(where: { !occupied.contains($0) }) ?? preferred
  }

  private func sendNoteOff(
    _ active: ActiveNote,
    sendToNetwork: Bool? = nil
  ) {
    sendMessages([
      [UInt8(0x80 | active.channel), UInt8(active.key), 0],
      pitchBend(channel: active.channel, value: Self.pitchBendCenter),
    ], sendToNetwork: sendToNetwork ?? active.sendToNetwork)
  }

  private func sendMessages(_ messages: [[UInt8]], sendToNetwork: Bool = true) {
    guard outputState().outputEnabled, !messages.isEmpty else { return }
    let destinations = endpoints().filter { endpoint in
      guard endpoint != networkSession.destinationEndpoint else { return false }
      return endpointId(for: endpoint).map(selectedBluetoothDestinationIds.contains) ?? false
    }
    for destination in destinations {
      _ = send(messages, to: destination)
    }
    if sendToNetwork {
      sendNetworkMessages(messages)
    }
  }

  private func sendNetworkMessages(_ messages: [[UInt8]]) {
    let state = outputState()
    guard state.outputEnabled, state.networkEnabled else { return }
    guard networkSession.allowsActiveTransport,
          networkSession.destinationEndpoint != 0 else {
      networkOutputBuffer.clear()
      return
    }
    networkOutputBuffer.enqueue(messages)
  }

  private func sendBufferedNetworkMessages(_ messages: [[UInt8]]) -> Bool {
    sendNetworkMessagesImmediately(messages)
  }

  @discardableResult
  private func sendNetworkMessagesImmediately(_ messages: [[UInt8]]) -> Bool {
    let state = outputState()
    let networkDestination = networkSession.destinationEndpoint
    guard state.outputEnabled,
          state.networkEnabled,
          networkSession.allowsActiveTransport,
          networkDestination != 0 else { return false }
    return send(messages, to: networkDestination)
  }

  @discardableResult
  private func send(_ messages: [[UInt8]], to destination: MIDIEndpointRef) -> Bool {
    midiSendLock.lock()
    defer { midiSendLock.unlock() }
    guard prepareOutputPort() else { return false }
    let packetListByteCount = max(
      1_024,
      MemoryLayout<MIDIPacketList>.size + messages.reduce(0) { size, message in
        size + 12 + ((message.count + 3) & ~3)
      }
    )
    let rawPacketList = UnsafeMutableRawPointer.allocate(
      byteCount: packetListByteCount,
      alignment: MemoryLayout<MIDIPacketList>.alignment
    )
    defer { rawPacketList.deallocate() }
    rawPacketList.initializeMemory(as: UInt8.self, repeating: 0, count: packetListByteCount)
    let packetList = rawPacketList.bindMemory(to: MIDIPacketList.self, capacity: 1)
    var packet = MIDIPacketListInit(packetList)
    for message in messages {
      message.withUnsafeBufferPointer { data in
        guard let baseAddress = data.baseAddress else { return }
        let nextPacket = MIDIPacketListAdd(
          packetList,
          packetListByteCount,
          packet,
          0,
          message.count,
          baseAddress
        )
        packet = nextPacket
      }
    }
    let status = MIDISend(outputPort, destination, packetList)
    if status != noErr {
      NSLog("Xen Synth could not send CoreMIDI message: %d", status)
    }
    return status == noErr
  }

  private func outputState() -> (outputEnabled: Bool, networkEnabled: Bool) {
    outputStateLock.lock()
    defer { outputStateLock.unlock() }
    return (outputEnabled, networkEnabled)
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

  private func stringProperty(
    _ property: CFString,
    for endpoint: MIDIEndpointRef
  ) -> String? {
    var unmanagedValue: Unmanaged<CFString>?
    guard MIDIObjectGetStringProperty(endpoint, property, &unmanagedValue) == noErr,
          let value = unmanagedValue?.takeRetainedValue() else { return nil }
    return value as String
  }

  private func localTransportName(for endpoint: MIDIEndpointRef, name: String) -> String {
    let model = stringProperty(kMIDIPropertyModel, for: endpoint) ?? ""
    let identity = "\(name) \(model)".lowercased()
    if identity.contains("bluetooth") || identity.contains("ble") {
      return "bluetooth"
    }
    if identity.contains("usb") {
      return "usb"
    }
    return "coremidi"
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

  private static let panicMessages = makeNetworkMIDIPanicMessages(channelCount: channelCount)

  deinit {
    allNotesOff()
    networkOutputBuffer.flushSynchronously()
    sendNetworkMessagesImmediately(Self.panicMessages)
    networkOutputBuffer.close()
    networkSession.close()
    if outputPort != 0 { MIDIPortDispose(outputPort) }
    if client != 0 { MIDIClientDispose(client) }
  }
}

/// Owns the system AppleMIDI session and the Bonjour services used to initiate it.
///
/// CoreMIDI implements the AppleMIDI control channel, RTP-MIDI payloads, clock
/// synchronization, packet recovery, and sequential control/data UDP port fallback.
private final class AppleMIDINetworkSession: NSObject {
  private static let destinationIdPrefix = "applemidi:"
  // NetService may report the IPv6 address first and deliver the IPv4 A
  // record on a later run-loop turn. Leave enough time for both callbacks.
  private static let scanDuration: TimeInterval = 2.5

  private struct DestinationCandidate {
    let id: String
    let service: NetService
    let address: String
    let port: Int
    let connected: Bool
  }

  private let session = MIDINetworkSession.default()
  private let browser = NetServiceBrowser()
  private var servicesById: [String: NetService] = [:]
  /// NetServiceBrowser can report the same service once per active interface.
  /// Keep aliases so an IPv6-only callback cannot replace a resolved IPv4 one.
  private var serviceAliasesById: [String: [NetService]] = [:]
  private var ipv4AddressesById: [String: String] = [:]
  private var selectedDestinationIds = Set<String>()
  private var initiatedConnections: [String: MIDINetworkConnection] = [:]
  private var pendingScans: [([[String: Any]]) -> Void] = []
  private var browserStarted = false
  private var enabled = false
  private var closed = false

  var destinationEndpoint: MIDIEndpointRef {
    session.destinationEndpoint()
  }

  var allowsActiveTransport: Bool {
    AppleMIDIPortPolicy.allowsActiveTransport(
      sessionEnabled: session.isEnabled,
      networkPort: session.networkPort
    )
  }

  override init() {
    super.init()
    browser.delegate = self
    browser.includesPeerToPeer = true
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(sessionDidChange),
      name: NSNotification.Name(rawValue: MIDINetworkNotificationSessionDidChange),
      object: session
    )
  }

  func setEnabled(_ enabled: Bool) {
    onMain { [weak self] in
      guard let self, !self.closed else { return }
      self.enabled = enabled
      if enabled {
        self.session.connectionPolicy = .anyone
        self.session.isEnabled = true
        self.startBrowserIfNeeded()
        self.reconcileConnections()
      } else {
        self.disconnectAll()
        self.session.connectionPolicy = .noOne
        self.session.isEnabled = false
      }
    }
  }

  func setDestinationIds(_ ids: [String]) {
    onMain { [weak self] in
      guard let self, !self.closed else { return }
      let next = Set(ids.filter { $0.hasPrefix(Self.destinationIdPrefix) })
      self.selectedDestinationIds = next
      let obsoleteConnections = self.initiatedConnections.filter { !next.contains($0.key) }
      for (id, connection) in obsoleteConnections {
        _ = self.removeConnection(connection)
        self.initiatedConnections.removeValue(forKey: id)
      }
      self.reconcileConnections()
    }
  }

  func scan(completion: @escaping ([[String: Any]]) -> Void) {
    onMain { [weak self] in
      guard let self, !self.closed else {
        completion([])
        return
      }
      self.pendingScans.append(completion)
      self.startBrowserIfNeeded()
      DispatchQueue.main.asyncAfter(deadline: .now() + Self.scanDuration) { [weak self] in
        self?.finishPendingScans()
      }
    }
  }

  func close() {
    let cleanup = { [self] in
      guard !closed else { return }
      closed = true
      enabled = false
      browser.stop()
      browser.delegate = nil
      browserStarted = false
      disconnectAll()
      session.connectionPolicy = .noOne
      session.isEnabled = false
      servicesById.removeAll()
      serviceAliasesById.removeAll()
      ipv4AddressesById.removeAll()
      let scans = pendingScans
      pendingScans.removeAll()
      scans.forEach { $0([]) }
      NotificationCenter.default.removeObserver(self)
    }
    if Thread.isMainThread {
      cleanup()
    } else {
      DispatchQueue.main.sync(execute: cleanup)
    }
  }

  private func onMain(_ work: @escaping () -> Void) {
    if Thread.isMainThread {
      work()
    } else {
      DispatchQueue.main.async(execute: work)
    }
  }

  private func startBrowserIfNeeded() {
    guard !browserStarted, !closed else { return }
    browserStarted = true
    browser.searchForServices(
      ofType: MIDINetworkBonjourServiceType,
      inDomain: "local."
    )
  }

  private func finishPendingScans() {
    guard !pendingScans.isEmpty else { return }
    let snapshot = destinationSnapshot()
    let scans = pendingScans
    pendingScans.removeAll()
    scans.forEach { $0(snapshot) }
  }

  private func destinationSnapshot() -> [[String: Any]] {
    let candidates = servicesById.compactMap { id, service -> DestinationCandidate? in
      let displayService = preferredService(for: id) ?? service
      guard !isLocalService(displayService) else { return nil }
      guard let address = ipv4AddressesById[id] else { return nil }
      let port = displayService.port > 0
        ? displayService.port
        : AppleMIDIPortPolicy.fixedControlPort
      return DestinationCandidate(
        id: id,
        service: displayService,
        address: address,
        port: port,
        connected: connectionExists(for: service)
      )
    }
    var seen = Set<String>()
    return candidates
      .sorted { lhs, rhs in
        let lhsScore = destinationScore(lhs)
        let rhsScore = destinationScore(rhs)
        if lhsScore != rhsScore { return lhsScore < rhsScore }
        return lhs.service.name.localizedCaseInsensitiveCompare(rhs.service.name)
          == .orderedAscending
      }
      .filter { candidate in
        seen.insert(destinationGroupKey(candidate)).inserted
      }
      .sorted {
        $0.service.name.localizedCaseInsensitiveCompare($1.service.name)
          == .orderedAscending
      }
      .map { candidate in
        [
          "id": candidate.id,
          "name": candidate.service.name,
          "model": deviceModel(for: candidate.service),
          "hostAddress": candidate.address,
          "port": candidate.port,
          "protocol": "rtp-midi",
          "connected": candidate.connected,
        ]
      }
  }

  private func destinationScore(_ candidate: DestinationCandidate) -> Int {
    let fixedPortScore = candidate.port == AppleMIDIPortPolicy.fixedControlPort ? 0 : 1
    let connectedScore = candidate.connected ? 0 : 1
    return fixedPortScore * 10 + connectedScore
  }

  private func destinationGroupKey(_ candidate: DestinationCandidate) -> String {
    "\(logicalServiceName(candidate.service.name).lowercased())\u{0}\(candidate.address)"
  }

  private func logicalServiceName(_ name: String) -> String {
    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let open = trimmed.lastIndex(of: "("), trimmed.last == ")" else {
      return trimmed
    }
    let suffix = trimmed[trimmed.index(after: open)..<trimmed.index(before: trimmed.endIndex)]
    guard !suffix.isEmpty, suffix.allSatisfy(\.isNumber), suffix != "1" else {
      return trimmed
    }
    return String(trimmed[..<open]).trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private func reconcileConnections() {
    guard !closed, enabled, session.isEnabled else { return }
    guard allowsActiveTransport else {
      disconnectInitiatedConnections()
      return
    }
    let liveConnections = session.connections()
    let liveConnectionsByKey = Dictionary(
      liveConnections.map { (connectionKey($0), $0) },
      uniquingKeysWith: { first, _ in first }
    )
    for (id, connection) in Array(initiatedConnections) {
      let key = connectionKey(connection)
      let matchedLive = liveConnectionsByKey[key] ?? preferredService(for: id).flatMap { service in
        guard !isLocalService(service) else { return nil }
        return liveConnections.first { connectionMatches($0, service: service, id: id) }
      }
      if let live = matchedLive {
        // CoreMIDI may recreate the Swift wrapper for the same socket. Keep
        // the current wrapper so later deselection removes the real session.
        initiatedConnections[id] = live
      } else {
        initiatedConnections.removeValue(forKey: id)
      }
    }
    for id in selectedDestinationIds where initiatedConnections[id] == nil {
      guard let service = preferredService(for: id), !isLocalService(service) else { continue }
      guard let host = host(for: service, id: id) else { continue }
      if liveConnections.contains(where: { connectionMatches($0, service: service, id: id) }) {
        continue
      }
      let connection = MIDINetworkConnection(host: host)
      if session.addConnection(connection) {
        initiatedConnections[id] = connection
      } else {
        NSLog("XenSynth could not connect to AppleMIDI peer %@", service.name)
      }
    }
  }

  private func disconnectAll() {
    for connection in session.connections() {
      _ = session.removeConnection(connection)
    }
    initiatedConnections.removeAll()
  }

  private func disconnectInitiatedConnections() {
    let connections = Array(initiatedConnections.values)
    initiatedConnections.removeAll()
    for connection in connections {
      _ = removeConnection(connection)
    }
  }

  /// CoreMIDI may return a new Swift wrapper for an unchanged socket. Resolve
  /// the current wrapper by stable host identity before removing it so a stale
  /// wrapper cannot leave a selected peer connected indefinitely.
  @discardableResult
  private func removeConnection(_ requested: MIDINetworkConnection) -> Bool {
    let key = connectionKey(requested)
    let live = session.connections().first { connectionKey($0) == key } ?? requested
    return session.removeConnection(live)
  }

  private func connectionExists(for service: NetService) -> Bool {
    let id = destinationId(for: service)
    return session.connections().contains {
      connectionMatches($0, service: service, id: id)
    }
  }

  private func connectionMatches(
    _ connection: MIDINetworkConnection,
    service: NetService,
    id: String
  ) -> Bool {
    guard let ipv4Host = host(for: service, id: id) else { return false }
    let candidate = connection.host
    if candidate.hasSameAddress(as: ipv4Host) { return true }

    // Passive CoreMIDI invitations can retain an IPv6 connection object even
    // when Bonjour resolves the same participant to IPv4. Match its service
    // identity so selecting the peer does not create a duplicate session.
    let candidateName = candidate.netServiceName ?? candidate.name
    guard !candidateName.isEmpty else { return false }
    let sameName = logicalServiceName(candidateName)
      .caseInsensitiveCompare(logicalServiceName(service.name)) == .orderedSame
    let controlPort = ipv4Host.port
    let dataPort = controlPort < Int(UInt16.max) ? controlPort + 1 : controlPort
    return sameName && (
      candidate.port == 0
        || candidate.port == controlPort
        || candidate.port == dataPort
    )
  }

  private func connectionKey(_ connection: MIDINetworkConnection) -> String {
    let host = connection.host
    let domain = host.netServiceDomain?
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased() ?? ""
    let serviceName = host.netServiceName?
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased() ?? ""
    if !serviceName.isEmpty {
      return "service|\(domain)|\(serviceName)"
    }
    let address = host.address
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .split(separator: "%", maxSplits: 1)
      .first
      .map(String.init)?
      .lowercased() ?? ""
    return "address|\(address)|\(host.port)"
  }

  private func host(for service: NetService, id: String) -> MIDINetworkHost? {
    guard let address = ipv4AddressesById[id] else { return nil }
    let advertisedPort = service.port > 0 ? service.port : AppleMIDIPortPolicy.fixedControlPort
    guard advertisedPort > 0, advertisedPort <= Int(UInt16.max) else { return nil }
    return MIDINetworkHost(
      name: service.name,
      address: address,
      port: advertisedPort
    )
  }

  private func updateResolvedService(_ service: NetService) {
    let id = destinationId(for: service)
    guard servicesById[id] != nil else { return }
    // Merge every interface-specific NetService result.  A/AAAA ordering is
    // not stable, so selecting from only `service.addresses` can lose IPv4.
    let resolvedAddresses = allServices(for: id).flatMap { $0.addresses ?? [] }
    let nextAddress = AppleMIDIIPv4AddressSelector.select(from: resolvedAddresses)
    let previousAddress = ipv4AddressesById[id]
    if let nextAddress {
      ipv4AddressesById[id] = nextAddress
    } else if previousAddress == nil {
      // NetService can briefly expose only its IPv6 result. Do not discard a
      // previously resolved IPv4 endpoint during that transient callback.
      ipv4AddressesById.removeValue(forKey: id)
    }

    // A service can be re-announced with the same Bonjour name after its
    // interface changes.  Remove the old CoreMIDI connection so the next
    // reconciliation uses the resolved IPv4 endpoint.
    if let nextAddress,
       previousAddress != nextAddress,
       let connection = initiatedConnections.removeValue(forKey: id) {
      _ = removeConnection(connection)
    }
    reconcileConnections()
  }

  private func isLocalService(_ service: NetService) -> Bool {
    let serviceName = service.name.trimmingCharacters(in: .whitespacesAndNewlines)
    let networkName = session.networkName.trimmingCharacters(in: .whitespacesAndNewlines)
    let exactName = serviceName.caseInsensitiveCompare(networkName) == .orderedSame
    guard exactName || logicalServiceName(serviceName)
      .caseInsensitiveCompare(logicalServiceName(networkName)) == .orderedSame else {
      return false
    }
    // A remote device may use the same display name.  Require the local
    // control port and a resolved local IPv4 address before suppressing it.
    guard service.port > 0,
          service.port == session.networkPort,
          let addresses = service.addresses else { return false }
    let serviceAddresses = Set(AppleMIDIIPv4AddressSelector.lanAddresses(from: addresses))
    return !serviceAddresses.isDisjoint(
      with: AppleMIDIIPv4AddressSelector.localInterfaceAddresses()
    )
  }

  private func allServices(for id: String) -> [NetService] {
    guard let primary = servicesById[id] else { return [] }
    return [primary] + (serviceAliasesById[id] ?? [])
  }

  private func preferredService(for id: String) -> NetService? {
    allServices(for: id).sorted { lhs, rhs in
      let lhsHasIPv4 = !AppleMIDIIPv4AddressSelector
        .lanAddresses(from: lhs.addresses ?? [])
        .isEmpty
      let rhsHasIPv4 = !AppleMIDIIPv4AddressSelector
        .lanAddresses(from: rhs.addresses ?? [])
        .isEmpty
      if lhsHasIPv4 != rhsHasIPv4 { return lhsHasIPv4 }
      let lhsFixed = lhs.port == AppleMIDIPortPolicy.fixedControlPort
      let rhsFixed = rhs.port == AppleMIDIPortPolicy.fixedControlPort
      if lhsFixed != rhsFixed { return lhsFixed }
      return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
    }.first
  }

  private func deviceModel(for service: NetService) -> String {
    if let txtData = service.txtRecordData() {
      let values = NetService.dictionary(fromTXTRecord: txtData)
      if let encoded = values.first(where: { $0.key.lowercased() == "model" })?.value,
         let model = String(data: encoded, encoding: .utf8)?
          .trimmingCharacters(in: .whitespacesAndNewlines),
         !model.isEmpty {
        return model
      }
    }
    return service.name.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private func destinationId(for service: NetService) -> String {
    let identity = "\(service.domain)\u{0}\(service.name)"
    let encoded = Data(identity.utf8).base64EncodedString()
    return Self.destinationIdPrefix + encoded
  }

  @objc private func sessionDidChange() {
    onMain { [weak self] in
      self?.reconcileConnections()
    }
  }

  deinit {
    close()
  }
}

extension AppleMIDINetworkSession: NetServiceBrowserDelegate {
  func netServiceBrowserWillSearch(_ browser: NetServiceBrowser) {
    browserStarted = true
  }

  func netServiceBrowser(
    _ browser: NetServiceBrowser,
    didFind service: NetService,
    moreComing: Bool
  ) {
    guard !closed, !isLocalService(service) else { return }
    let id = destinationId(for: service)
    if let previous = servicesById[id] {
      if previous !== service,
         !(serviceAliasesById[id] ?? []).contains(where: { $0 === service }) {
        serviceAliasesById[id, default: []].append(service)
      }
    } else {
      servicesById[id] = service
    }
    service.delegate = self
    service.resolve(withTimeout: 1.0)
    updateResolvedService(service)
    reconcileConnections()
  }

  func netServiceBrowser(
    _ browser: NetServiceBrowser,
    didRemove service: NetService,
    moreComing: Bool
  ) {
    let id = destinationId(for: service)
    service.stop()
    if servicesById[id] === service {
      if var aliases = serviceAliasesById[id], !aliases.isEmpty {
        let replacement = aliases.removeFirst()
        servicesById[id] = replacement
        serviceAliasesById[id] = aliases.isEmpty ? nil : aliases
        updateResolvedService(replacement)
      } else {
        servicesById.removeValue(forKey: id)
        serviceAliasesById.removeValue(forKey: id)
        ipv4AddressesById.removeValue(forKey: id)
      }
    } else if var aliases = serviceAliasesById[id] {
      aliases.removeAll { $0 === service }
      serviceAliasesById[id] = aliases.isEmpty ? nil : aliases
    } else {
      return
    }
    if servicesById[id] == nil,
       let connection = initiatedConnections.removeValue(forKey: id) {
      _ = removeConnection(connection)
    }
  }

  func netServiceBrowser(
    _ browser: NetServiceBrowser,
    didNotSearch errorDict: [String: NSNumber]
  ) {
    browserStarted = false
    NSLog("XenSynth AppleMIDI Bonjour search failed: %@", errorDict)
    finishPendingScans()
  }

  func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
    browserStarted = false
  }
}

extension AppleMIDINetworkSession: NetServiceDelegate {
  func netServiceDidResolveAddress(_ sender: NetService) {
    onMain { [weak self] in
      guard let self, !self.closed else { return }
      self.updateResolvedService(sender)
    }
  }

  func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
    NSLog(
      "XenSynth could not resolve AppleMIDI service %@: %@",
      sender.name,
      errorDict
    )
    onMain { [weak self] in
      guard let self, !self.closed else { return }
      self.updateResolvedService(sender)
    }
  }
}
