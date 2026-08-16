import CoreMIDI
import Darwin
import Foundation

final class NetworkMIDIEventBuffer {
  private struct PendingEvent {
    let event: [String: Any]
    let targetUptimeNanoseconds: UInt64
    let insertionOrder: UInt64
  }

  // The Android receiver uses the same 60 ms / 6144-event capacity after the
  // requested threefold expansion. CoreMIDI already maps AppleMIDI timestamps
  // onto the local host clock; this queue absorbs delivery jitter after that mapping.
  static let defaultPlayoutDelayNanoseconds: UInt64 = 60_000_000
  static let defaultMaximumTimestampSkewNanoseconds: UInt64 = 120_000_000
  static let defaultMaximumPendingEvents = 6_144
  private static let maximumEventsPerDrain = 256
  private static let timerLeeway = DispatchTimeInterval.milliseconds(1)
  private static let timebase: mach_timebase_info_data_t = {
    var info = mach_timebase_info_data_t()
    mach_timebase_info(&info)
    return info
  }()

  var onEvents: (([[String: Any]]) -> Void)?

  private let playoutDelayNanoseconds: UInt64
  private let maximumTimestampSkewNanoseconds: UInt64
  private let maximumPendingEvents: Int
  private let queue = DispatchQueue(
    label: "icu.ringona.xensynth.network-midi-buffer",
    qos: .userInteractive
  )
  private let lifecycleLock = NSLock()
  private var lifecycleEpoch: UInt64 = 0
  private var lifecycleClosed = false
  private var timer: DispatchSourceTimer!
  private var pending: [PendingEvent] = []
  private var pendingStartIndex = 0
  private var nextInsertionOrder: UInt64 = 0
  private var activeEpoch: UInt64 = 0
  private var queueClosed = false

  init(
    playoutDelayNanoseconds: UInt64 = NetworkMIDIEventBuffer.defaultPlayoutDelayNanoseconds,
    maximumTimestampSkewNanoseconds: UInt64 = NetworkMIDIEventBuffer.defaultMaximumTimestampSkewNanoseconds,
    maximumPendingEvents: Int = NetworkMIDIEventBuffer.defaultMaximumPendingEvents
  ) {
    precondition(maximumPendingEvents > 0)
    self.playoutDelayNanoseconds = playoutDelayNanoseconds
    self.maximumTimestampSkewNanoseconds = maximumTimestampSkewNanoseconds
    self.maximumPendingEvents = maximumPendingEvents
    timer = DispatchSource.makeTimerSource(queue: queue)
    timer.setEventHandler { [weak self] in
      self?.drainDueEvents()
    }
    timer.schedule(deadline: .distantFuture)
    timer.resume()
  }

  func captureIngressEpoch() -> UInt64? {
    lifecycleLock.lock()
    defer { lifecycleLock.unlock() }
    return lifecycleClosed ? nil : lifecycleEpoch
  }

  func enqueue(_ events: [[String: Any]], ingressEpoch: UInt64? = nil) {
    guard !events.isEmpty,
          let expectedEpoch = ingressEpoch ?? captureIngressEpoch() else { return }
    let arrivalUptimeNanoseconds = DispatchTime.now().uptimeNanoseconds
    let currentHostTime = mach_absolute_time()
    let scheduled = events.map { event in
      (
        event,
        Self.targetUptimeNanoseconds(
          for: (event["midiTimestamp"] as? NSNumber)?.uint64Value ?? 0,
          arrivalUptimeNanoseconds: arrivalUptimeNanoseconds,
          currentHostTime: currentHostTime,
          playoutDelayNanoseconds: playoutDelayNanoseconds,
          maximumTimestampSkewNanoseconds: maximumTimestampSkewNanoseconds
        )
      )
    }
    queue.async { [weak self] in
      guard let self,
            !self.queueClosed,
            self.activeEpoch == expectedEpoch,
            self.isCurrent(epoch: expectedEpoch) else { return }
      var additions: [PendingEvent] = []
      additions.reserveCapacity(scheduled.count)
      for (event, target) in scheduled {
        additions.append(PendingEvent(
          event: event,
          targetUptimeNanoseconds: target,
          insertionOrder: self.nextInsertionOrder
        ))
        self.nextInsertionOrder &+= 1
      }
      additions.sort(by: Self.isOrderedBefore)
      self.insertSorted(additions)
      if self.pendingCount > self.maximumPendingEvents {
        self.pending = Array(self.pending.suffix(self.maximumPendingEvents))
        self.pendingStartIndex = 0
        self.deliver(
          [["type": "allNotesOff", "source": "network"]],
          epoch: expectedEpoch
        )
      }
      self.scheduleNextDrain()
    }
  }

  func clear() {
    guard let nextEpoch = invalidateLifecycle(closing: false) else { return }
    queue.sync {
      guard !queueClosed else { return }
      activeEpoch = nextEpoch
      pending.removeAll(keepingCapacity: true)
      pendingStartIndex = 0
      timer.schedule(deadline: .distantFuture)
    }
  }

  func close() {
    guard let nextEpoch = invalidateLifecycle(closing: true) else { return }
    queue.sync {
      guard !queueClosed else { return }
      queueClosed = true
      activeEpoch = nextEpoch
      pending.removeAll()
      pendingStartIndex = 0
      timer.cancel()
    }
  }

  private func drainDueEvents() {
    guard !queueClosed, pendingCount > 0 else {
      timer.schedule(deadline: .distantFuture)
      return
    }
    let now = DispatchTime.now().uptimeNanoseconds
    let deliveryLimit = min(
      pending.count,
      pendingStartIndex + Self.maximumEventsPerDrain
    )
    var dueEndIndex = pendingStartIndex
    while dueEndIndex < deliveryLimit,
          pending[dueEndIndex].targetUptimeNanoseconds <= now {
      dueEndIndex += 1
    }
    guard dueEndIndex > pendingStartIndex else {
      scheduleNextDrain()
      return
    }
    let dueEvents = pending[pendingStartIndex..<dueEndIndex].map(\.event)
    pendingStartIndex = dueEndIndex
    let deliveryEpoch = activeEpoch
    compactPendingIfNeeded()
    scheduleNextDrain()
    deliver(dueEvents, epoch: deliveryEpoch)
  }

  private func scheduleNextDrain() {
    guard pendingStartIndex < pending.count else {
      timer.schedule(deadline: .distantFuture)
      return
    }
    let first = pending[pendingStartIndex]
    timer.schedule(
      deadline: DispatchTime(uptimeNanoseconds: first.targetUptimeNanoseconds),
      leeway: Self.timerLeeway
    )
  }

  private func deliver(_ events: [[String: Any]], epoch deliveryEpoch: UInt64) {
    DispatchQueue.main.async { [weak self] in
      guard let self, self.isCurrent(epoch: deliveryEpoch) else { return }
      self.onEvents?(events)
    }
  }

  private var pendingCount: Int {
    pending.count - pendingStartIndex
  }

  private func insertSorted(_ additions: [PendingEvent]) {
    guard !additions.isEmpty else { return }
    guard pendingStartIndex < pending.count else {
      pending = additions
      pendingStartIndex = 0
      return
    }
    if let last = pending.last,
       let firstAddition = additions.first,
       !Self.isOrderedBefore(firstAddition, last) {
      pending.append(contentsOf: additions)
      return
    }

    let existing = pending[pendingStartIndex...]
    var merged: [PendingEvent] = []
    merged.reserveCapacity(existing.count + additions.count)
    var existingIndex = existing.startIndex
    var additionIndex = additions.startIndex
    while existingIndex < existing.endIndex, additionIndex < additions.endIndex {
      if Self.isOrderedBefore(additions[additionIndex], existing[existingIndex]) {
        merged.append(additions[additionIndex])
        additionIndex += 1
      } else {
        merged.append(existing[existingIndex])
        existing.formIndex(after: &existingIndex)
      }
    }
    if existingIndex < existing.endIndex {
      merged.append(contentsOf: existing[existingIndex...])
    }
    if additionIndex < additions.endIndex {
      merged.append(contentsOf: additions[additionIndex...])
    }
    pending = merged
    pendingStartIndex = 0
  }

  private func compactPendingIfNeeded() {
    guard pendingStartIndex > 0 else { return }
    if pendingStartIndex == pending.count {
      pending.removeAll(keepingCapacity: true)
      pendingStartIndex = 0
    } else if pendingStartIndex >= 1_024,
              pendingStartIndex >= pending.count / 2 {
      pending = Array(pending[pendingStartIndex...])
      pendingStartIndex = 0
    }
  }

  private static func isOrderedBefore(_ lhs: PendingEvent, _ rhs: PendingEvent) -> Bool {
    if lhs.targetUptimeNanoseconds == rhs.targetUptimeNanoseconds {
      return lhs.insertionOrder < rhs.insertionOrder
    }
    return lhs.targetUptimeNanoseconds < rhs.targetUptimeNanoseconds
  }

  private func invalidateLifecycle(closing: Bool) -> UInt64? {
    lifecycleLock.lock()
    defer { lifecycleLock.unlock() }
    guard !lifecycleClosed else { return nil }
    lifecycleEpoch &+= 1
    if closing { lifecycleClosed = true }
    return lifecycleEpoch
  }

  private func isCurrent(epoch expected: UInt64) -> Bool {
    lifecycleLock.lock()
    defer { lifecycleLock.unlock() }
    return !lifecycleClosed && lifecycleEpoch == expected
  }

  static func targetUptimeNanoseconds(
    for midiTimestamp: MIDITimeStamp,
    arrivalUptimeNanoseconds: UInt64,
    currentHostTime: UInt64,
    playoutDelayNanoseconds: UInt64,
    maximumTimestampSkewNanoseconds: UInt64
  ) -> UInt64 {
    let bufferedArrival = addingWithoutOverflow(
      arrivalUptimeNanoseconds,
      playoutDelayNanoseconds
    )
    guard midiTimestamp != 0 else { return bufferedArrival }
    if midiTimestamp >= currentHostTime {
      let offset = min(
        hostTicksToNanoseconds(midiTimestamp - currentHostTime),
        maximumTimestampSkewNanoseconds
      )
      return addingWithoutOverflow(bufferedArrival, offset)
    }
    let offset = min(
      hostTicksToNanoseconds(currentHostTime - midiTimestamp),
      maximumTimestampSkewNanoseconds
    )
    return max(arrivalUptimeNanoseconds, bufferedArrival > offset ? bufferedArrival - offset : 0)
  }

  private static func hostTicksToNanoseconds(_ ticks: UInt64) -> UInt64 {
    let value = Double(ticks) * Double(timebase.numer) / Double(timebase.denom)
    if !value.isFinite || value >= Double(UInt64.max) { return UInt64.max }
    return UInt64(value.rounded())
  }

  private static func addingWithoutOverflow(_ lhs: UInt64, _ rhs: UInt64) -> UInt64 {
    let (value, overflow) = lhs.addingReportingOverflow(rhs)
    return overflow ? UInt64.max : value
  }
}

final class MIDIKeyboardController {
  var onEvent: (([String: Any]) -> Void)?

  private final class SourceContext {
    let isNetwork: Bool
    var runningStatus: UInt8?

    init(isNetwork: Bool) {
      self.isNetwork = isNetwork
    }
  }

  private var client = MIDIClientRef()
  private var inputPort = MIDIPortRef()
  private var sourceContexts: [MIDIEndpointRef: SourceContext] = [:]
  private var isStarted = false
  private var networkSessionObserver: NSObjectProtocol?
  private var networkConnectionIds = Set<ObjectIdentifier>()
  private let networkEventBuffer = NetworkMIDIEventBuffer()
  private let inputEnabledLock = NSLock()
  private var storedInputEnabled = true

  var inputEnabled: Bool {
    inputEnabledLock.lock()
    defer { inputEnabledLock.unlock() }
    return storedInputEnabled
  }

  init() {
    networkEventBuffer.onEvents = { [weak self] events in
      guard let self, self.inputEnabled else { return }
      events.forEach(self.emit)
    }
    let session = MIDINetworkSession.default()
    networkConnectionIds = Set(session.connections().map(ObjectIdentifier.init))
    networkSessionObserver = NotificationCenter.default.addObserver(
      forName: NSNotification.Name(rawValue: MIDINetworkNotificationSessionDidChange),
      object: session,
      queue: .main
    ) { [weak self] _ in
      self?.networkSessionDidChange()
    }
  }

  func setInputEnabled(_ enabled: Bool) {
    inputEnabledLock.lock()
    storedInputEnabled = enabled
    inputEnabledLock.unlock()
    if !enabled { stop() }
  }

  func start() throws {
    guard inputEnabled else { return }
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
    networkEventBuffer.clear()
    guard isStarted else { return }
    for source in sourceContexts.keys {
      MIDIPortDisconnectSource(inputPort, source)
    }
    sourceContexts.removeAll()
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
    for source in Array(sourceContexts.keys) where !sources.contains(source) {
      MIDIPortDisconnectSource(inputPort, source)
      sourceContexts.removeValue(forKey: source)
    }
    let networkSource = MIDINetworkSession.default().sourceEndpoint()
    for source in sources where sourceContexts[source] == nil {
      let context = SourceContext(
        isNetwork: networkSource != 0 && source == networkSource
      )
      let sourceRefCon = Unmanaged.passUnretained(context).toOpaque()
      if MIDIPortConnectSource(inputPort, source, sourceRefCon) == noErr {
        sourceContexts[source] = context
      }
    }
  }

  private func handle(
    packetList: UnsafePointer<MIDIPacketList>,
    sourceContext: SourceContext?
  ) {
    guard inputEnabled else { return }
    let isNetwork = sourceContext?.isNetwork == true
    let ingressEpoch = isNetwork ? networkEventBuffer.captureIngressEpoch() : nil
    if isNetwork, ingressEpoch == nil { return }
    var runningStatus = sourceContext?.runningStatus
    let parsed = Self.events(
      from: packetList,
      runningStatus: &runningStatus,
      isNetwork: isNetwork
    )
    sourceContext?.runningStatus = runningStatus
    guard !parsed.isEmpty else { return }
    if isNetwork {
      networkEventBuffer.enqueue(parsed, ingressEpoch: ingressEpoch)
      return
    }
    DispatchQueue.main.async { [weak self] in
      guard let self, self.inputEnabled else { return }
      for event in parsed { self.emit(event) }
    }
  }

  private func emit(_ event: [String: Any]) {
    onEvent?(event)
  }

  private func networkSessionDidChange() {
    let next = Set(MIDINetworkSession.default().connections().map(ObjectIdentifier.init))
    let connectionWasRemoved = !networkConnectionIds.subtracting(next).isEmpty
    networkConnectionIds = next
    if inputEnabled && connectionWasRemoved {
      networkEventBuffer.clear()
      emit(["type": "allNotesOff", "source": "network"])
    }
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

  private static let readProc: MIDIReadProc = { packetList, refCon, sourceConnectionRefCon in
    guard let refCon else { return }
    let controller = Unmanaged<MIDIKeyboardController>.fromOpaque(refCon).takeUnretainedValue()
    let sourceContext = sourceConnectionRefCon.map {
      Unmanaged<SourceContext>.fromOpaque($0).takeUnretainedValue()
    }
    controller.handle(packetList: packetList, sourceContext: sourceContext)
  }

  private static func events(
    from packetList: UnsafePointer<MIDIPacketList>,
    runningStatus: inout UInt8?,
    isNetwork: Bool
  ) -> [[String: Any]] {
    var parsed: [[String: Any]] = []

    let mutableList = UnsafeMutablePointer(mutating: packetList)
    withUnsafeMutablePointer(to: &mutableList.pointee.packet) { firstPacket in
      var packet = firstPacket
      for _ in 0..<packetList.pointee.numPackets {
        let current = packet.pointee
        let bytes = withUnsafeBytes(of: current.data) { rawBuffer in
          Array(rawBuffer.prefix(Int(current.length)))
        }
        var packetEvents: [[String: Any]] = []
        parse(bytes, runningStatus: &runningStatus, into: &packetEvents)
        for var event in packetEvents {
          // AppleMIDI's clock synchronization is represented by this CoreMIDI
          // host-time value. Keep it intact across the platform boundary.
          event["midiTimestamp"] = NSNumber(value: current.timeStamp)
          if isNetwork { event["source"] = "network" }
          parsed.append(event)
        }
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
    if let networkSessionObserver {
      NotificationCenter.default.removeObserver(networkSessionObserver)
    }
    stop()
    networkEventBuffer.close()
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
