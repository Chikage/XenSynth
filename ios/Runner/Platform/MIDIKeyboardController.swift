import CoreMIDI
import Darwin
import Foundation
import UIKit

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
    let endpoint: MIDIEndpointRef
    let generation: UInt64
    let isNetwork: Bool
    private let lifecycleLock = NSLock()
    private let parserLock = NSLock()
    private var active = true
    private var runningStatus: UInt8?

    init(endpoint: MIDIEndpointRef, generation: UInt64, isNetwork: Bool) {
      self.endpoint = endpoint
      self.generation = generation
      self.isNetwork = isNetwork
    }

    var isActive: Bool {
      lifecycleLock.lock()
      defer { lifecycleLock.unlock() }
      return active
    }

    func deactivate() {
      lifecycleLock.lock()
      active = false
      lifecycleLock.unlock()
    }

    func withRunningStatus<T>(_ body: (inout UInt8?) -> T) -> T {
      parserLock.lock()
      defer { parserLock.unlock() }
      return body(&runningStatus)
    }
  }

  private var client = MIDIClientRef()
  private var inputPort = MIDIPortRef()
  private var sourceContexts: [MIDIEndpointRef: SourceContext] = [:]
  private let sourceContextsLock = NSLock()
  private var nextSourceGeneration: UInt64 = 0
  private var retiredSourceContexts: [SourceContext] = []
  private let retiredSourceContextsLock = NSLock()
  private var isStarted = false
  private var networkSessionObserver: NSObjectProtocol?
  private var applicationActiveObserver: NSObjectProtocol?
  /// `connections()` can recreate Swift wrappers for an unchanged socket, so
  /// object identity cannot be used to decide whether a peer was removed.
  private var networkConnectionKeys = Set<String>()
  private var networkRefreshGeneration: UInt64 = 0
  /// A source is opened only when it is explicitly selected. CoreMIDI exposes
  /// one shared source for AppleMIDI peers, so any selected AppleMIDI peer ID
  /// enables that source while peer connection ownership stays in the router.
  private var selectedInputSourceIds = Set<String>()
  private let networkEventBuffer = NetworkMIDIEventBuffer()
  private let networkIngressLogLock = NSLock()
  private var didLogNetworkIngress = false
  private let inputEnabledLock = NSLock()
  private var storedInputEnabled = true
  private let networkInputEnabledLock = NSLock()
  private var storedNetworkInputEnabled = true

  var inputEnabled: Bool {
    inputEnabledLock.lock()
    defer { inputEnabledLock.unlock() }
    return storedInputEnabled
  }

  private var networkInputEnabled: Bool {
    networkInputEnabledLock.lock()
    defer { networkInputEnabledLock.unlock() }
    return storedNetworkInputEnabled
  }

  init() {
    networkEventBuffer.onEvents = { [weak self] events in
      guard let self, self.inputEnabled, self.networkInputEnabled else { return }
      events.forEach(self.emit)
    }
    let session = MIDINetworkSession.default()
    networkConnectionKeys = Set(session.connections().map(Self.networkConnectionKey))
    networkSessionObserver = NotificationCenter.default.addObserver(
      forName: NSNotification.Name(rawValue: MIDINetworkNotificationSessionDidChange),
      object: session,
      queue: .main
    ) { [weak self] _ in
      self?.networkSessionDidChange()
    }
    // CoreMIDI can tear down the network source while the app is suspended.
    // Reconnect once on foreground so a stale endpoint ref cannot silently
    // swallow the first MIDI packets after returning to the app.
    applicationActiveObserver = NotificationCenter.default.addObserver(
      forName: UIApplication.didBecomeActiveNotification,
      object: nil,
      queue: .main
    ) { [weak self] _ in
      guard let self, self.inputEnabled, self.isStarted else { return }
      self.refreshConnections(forceNetworkReconnect: true)
      self.scheduleNetworkConnectionRefreshes()
    }
  }

  func setInputEnabled(_ enabled: Bool) {
    inputEnabledLock.lock()
    storedInputEnabled = enabled
    inputEnabledLock.unlock()
    if !enabled { stop() }
  }

  func setNetworkInputEnabled(_ enabled: Bool) {
    networkInputEnabledLock.lock()
    let changed = storedNetworkInputEnabled != enabled
    storedNetworkInputEnabled = enabled
    networkInputEnabledLock.unlock()
    guard changed else { return }
    if !enabled {
      networkEventBuffer.clear()
      emit(["type": "allNotesOff", "source": "network"])
    }
    guard isStarted else { return }
    refreshConnections()
    if enabled { scheduleNetworkConnectionRefreshes() }
  }

  /// Returns every CoreMIDI source visible to the app, including the shared
  /// AppleMIDI source.  The payload intentionally mirrors NativeMidiOutput so
  /// Flutter can render input and output devices with one model.
  func inputDevices() -> [[String: Any]] {
    var devices: [[String: Any]] = []
    var seen = Set<String>()
    let networkSource = MIDINetworkSession.default().sourceEndpoint()
    let enumeratedSources = (0..<MIDIGetNumberOfSources()).compactMap { index -> MIDIEndpointRef? in
      let source = MIDIGetSource(index)
      return source == 0 ? nil : source
    }
    for source in enumeratedSources {
      let isNetwork = source == networkSource && networkSource != 0
      let id = Self.sourceId(for: source, isNetwork: isNetwork)
      guard seen.insert(id).inserted else { continue }
      devices.append(Self.deviceMap(for: source, id: id, isNetwork: isNetwork))
    }
    if networkSource != 0, seen.insert(Self.networkInputSourceId).inserted {
      devices.append(Self.deviceMap(
        for: networkSource,
        id: Self.networkInputSourceId,
        isNetwork: true
      ))
    // The network source may not have been published yet (for example during
    // app launch), but the session is still a valid selectable input target.
    } else if networkSource == 0, MIDINetworkSession.default().isEnabled {
      let id = Self.networkInputSourceId
      if seen.insert(id).inserted {
        devices.append([
          "id": id,
          "name": "RTP-MIDI / AppleMIDI",
          "model": "Network",
          "transport": "network",
          "isNetwork": true,
          "hostAddress": "",
          "port": MIDINetworkSession.default().networkPort,
        ])
      }
    }
    return devices.sorted {
      ($0["name"] as? String ?? "").localizedCaseInsensitiveCompare(
        $1["name"] as? String ?? ""
      ) == .orderedAscending
    }
  }

  func targetId(forInputSourceId id: String) -> String {
    if id.hasPrefix("applemidi:") { return id }
    let networkSource = MIDINetworkSession.default().sourceEndpoint()
    for index in 0..<MIDIGetNumberOfSources() {
      let source = MIDIGetSource(index)
      guard source != 0, source != networkSource else { continue }
      if Self.sourceId(for: source, isNetwork: false) == id {
        return MIDIEndpointIdentity.targetId(for: source)
      }
    }
    return id
  }

  /// Applies the user-selected source IDs.  Passing `configured = false`
  /// preserves the pre-device-list behaviour (all sources enabled).
  func setInputSourceIds(_ ids: [String], configured: Bool = true) {
    let next = Set(ids.lazy.filter { !$0.isEmpty }.prefix(1))
    guard selectedInputSourceIds != next else { return }
    selectedInputSourceIds = next
    if isStarted {
      // A deselected source can no longer deliver its matching Note Off. Clear
      // queued network events and release all input-owned notes before the
      // source endpoints are disconnected.
      networkEventBuffer.clear()
      emit(["type": "allNotesOff"])
      refreshConnections()
    }
  }

  func start() throws {
    guard inputEnabled else { return }
    let networkSession = MIDINetworkSession.default()
    if networkInputEnabled {
      networkSession.connectionPolicy = .anyone
      networkSession.isEnabled = true
    }
    guard !isStarted else {
      refreshConnections()
      scheduleNetworkConnectionRefreshes()
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
    NSLog(
      "Xen Synth MIDI input started (port=%u, AppleMIDI port=%lu, source=%u)",
      newInputPort,
      networkSession.networkPort,
      networkSession.sourceEndpoint()
    )
    refreshConnections()
    scheduleNetworkConnectionRefreshes()
  }

  func stop() {
    networkEventBuffer.clear()
    networkRefreshGeneration &+= 1
    guard isStarted else { return }
    for source in sourceContextsSnapshot().keys {
      disconnectInputSource(source)
    }
    MIDIPortDispose(inputPort)
    MIDIClientDispose(client)
    inputPort = MIDIPortRef()
    client = MIDIClientRef()
    isStarted = false
    emit(["type": "allNotesOff"])
  }

  private func refreshConnections(forceNetworkReconnect: Bool = false) {
    guard isStarted, inputPort != 0 else { return }
    let enumeratedSources = (0..<MIDIGetNumberOfSources()).compactMap { index -> MIDIEndpointRef? in
      let source = MIDIGetSource(index)
      return source == 0 ? nil : source
    }
    let networkSource = MIDINetworkSession.default().sourceEndpoint()
    let hasNetworkConnection = !MIDINetworkSession.default().connections().isEmpty
    let sources = Self.inputSources(
      enumeratedSources: enumeratedSources,
      networkSource: networkSource
    ).filter { source in
      let isNetwork = networkSource != 0 && source == networkSource
      if isNetwork {
        return networkInputEnabled && Self.shouldReceiveNetworkInput(
          selectedInputSourceIds: selectedInputSourceIds,
          hasActiveConnection: hasNetworkConnection
        )
      }
      return selectedInputSourceIds.contains(
        Self.sourceId(for: source, isNetwork: false)
      )
    }
    if forceNetworkReconnect, networkSource != 0,
       sourceContext(for: networkSource) != nil {
      disconnectInputSource(networkSource)
    }
    for (source, context) in sourceContextsSnapshot() {
      let shouldBeNetwork = networkSource != 0 && source == networkSource
      guard !sources.contains(source) || context.isNetwork != shouldBeNetwork else {
        continue
      }
      disconnectInputSource(source)
    }
    for source in sources where sourceContext(for: source) == nil {
      connectInputSource(
        source,
        isNetwork: networkSource != 0 && source == networkSource
      )
    }
  }

  private func scheduleNetworkConnectionRefreshes() {
    // CoreMIDI may publish sourceEndpoint well after the session notification
    // on a busy device. Supersede older retry sets so notifications cannot
    // accumulate redundant refresh work.
    networkRefreshGeneration &+= 1
    let generation = networkRefreshGeneration
    let delays: [TimeInterval] = [0.05, 0.25, 0.75, 1.5, 3.0]
    for (index, delay) in delays.enumerated() {
      DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
        guard let self,
              self.inputEnabled,
              self.networkInputEnabled,
              self.isStarted,
              self.networkRefreshGeneration == generation else { return }
        let networkSource = MIDINetworkSession.default().sourceEndpoint()
        if networkSource != 0, self.sourceContext(for: networkSource) != nil {
          return
        }
        self.refreshConnections()
        if index == delays.count - 1,
           MIDINetworkSession.default().sourceEndpoint() == 0 {
          NSLog("Xen Synth AppleMIDI input source was not published after retry window")
        }
      }
    }
  }

  static func inputSources(
    enumeratedSources: [MIDIEndpointRef],
    networkSource: MIDIEndpointRef
  ) -> Set<MIDIEndpointRef> {
    var sources = Set(enumeratedSources.filter { $0 != 0 })
    if networkSource != 0 {
      sources.insert(networkSource)
    }
    return sources
  }

  /// A remote AppleMIDI invitation establishes a duplex peer connection.
  /// Once CoreMIDI reports that connection, attach its shared source even if
  /// this device has no separately selected network input row.
  static func shouldReceiveNetworkInput(
    selectedInputSourceIds: Set<String>,
    hasActiveConnection: Bool
  ) -> Bool {
    hasActiveConnection || selectedInputSourceIds.contains { $0.hasPrefix("applemidi:") }
  }

  private static let networkInputSourceId = "applemidi:input"

  private static func sourceId(for source: MIDIEndpointRef, isNetwork: Bool) -> String {
    if isNetwork { return networkInputSourceId }
    var uniqueId: Int32 = 0
    if MIDIObjectGetIntegerProperty(source, kMIDIPropertyUniqueID, &uniqueId) == noErr {
      return "coremidi:\(uniqueId)"
    }
    return "coremidi:endpoint:\(source)"
  }

  private static func displayName(for source: MIDIEndpointRef) -> String {
    var unmanagedName: Unmanaged<CFString>?
    guard MIDIObjectGetStringProperty(source, kMIDIPropertyDisplayName, &unmanagedName) == noErr,
          let name = unmanagedName?.takeRetainedValue() else {
      return "MIDI input"
    }
    let value = name as String
    return value.isEmpty ? "MIDI input" : value
  }

  private static func transportName(for source: MIDIEndpointRef, isNetwork: Bool) -> String {
    if isNetwork { return "network" }
    // CoreMIDI does not publish a portable transport property on every iOS
    // endpoint. Use the endpoint's display identity for the two transports
    // users need to distinguish, and keep a neutral CoreMIDI fallback.
    let normalizedName = displayName(for: source).lowercased()
    if normalizedName.contains("bluetooth") || normalizedName.contains("ble") {
      return "bluetooth"
    }
    if normalizedName.contains("usb") {
      return "usb"
    }
    return "coremidi"
  }

  private static func deviceMap(
    for source: MIDIEndpointRef,
    id: String,
    isNetwork: Bool
  ) -> [String: Any] {
    [
      "id": id,
      "name": displayName(for: source),
      "model": isNetwork ? "Network" : "CoreMIDI",
      "targetId": isNetwork
        ? Self.networkInputSourceId
        : MIDIEndpointIdentity.targetId(for: source),
      "transport": transportName(for: source, isNetwork: isNetwork),
      "isNetwork": isNetwork,
    ]
  }

  private func handle(
    packetList: UnsafePointer<MIDIPacketList>,
    sourceContext: SourceContext?
  ) {
    guard inputEnabled,
          let sourceContext,
          isCurrentSourceContext(sourceContext) else { return }
    let isNetwork = sourceContext.isNetwork
    let ingressEpoch = isNetwork ? networkEventBuffer.captureIngressEpoch() : nil
    if isNetwork, ingressEpoch == nil { return }
    let parsed = sourceContext.withRunningStatus { runningStatus in
      Self.events(
        from: packetList,
        runningStatus: &runningStatus,
        isNetwork: isNetwork
      )
    }
    guard isCurrentSourceContext(sourceContext) else { return }
    guard !parsed.isEmpty else { return }
    if isNetwork {
      logFirstNetworkIngress(eventCount: parsed.count)
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

  private func connectInputSource(_ source: MIDIEndpointRef, isNetwork: Bool) {
    sourceContextsLock.lock()
    guard sourceContexts[source] == nil else {
      sourceContextsLock.unlock()
      return
    }
    nextSourceGeneration &+= 1
    let context = SourceContext(
      endpoint: source,
      generation: nextSourceGeneration,
      isNetwork: isNetwork
    )
    // Install the context before CoreMIDI sees its unretained refCon. The
    // connect call is allowed to deliver an already queued packet immediately.
    sourceContexts[source] = context
    sourceContextsLock.unlock()

    let sourceRefCon = Unmanaged.passUnretained(context).toOpaque()
    let status = MIDIPortConnectSource(inputPort, source, sourceRefCon)
    guard status == noErr else {
      sourceContextsLock.lock()
      if sourceContexts[source] === context {
        sourceContexts.removeValue(forKey: source)
      }
      sourceContextsLock.unlock()
      context.deactivate()
      retainRetiredSourceContext(context)
      NSLog("Xen Synth could not connect MIDI input source %u: %d", source, status)
      return
    }
    if isNetwork {
      NSLog(
        "Xen Synth connected AppleMIDI input source %u generation %llu",
        source,
        context.generation
      )
    }
  }

  private func sourceContext(for source: MIDIEndpointRef) -> SourceContext? {
    sourceContextsLock.lock()
    defer { sourceContextsLock.unlock() }
    return sourceContexts[source]
  }

  private func sourceContextsSnapshot() -> [MIDIEndpointRef: SourceContext] {
    sourceContextsLock.lock()
    defer { sourceContextsLock.unlock() }
    return sourceContexts
  }

  private func isCurrentSourceContext(_ context: SourceContext) -> Bool {
    sourceContextsLock.lock()
    let current = sourceContexts[context.endpoint]
    let matches = current === context && current?.generation == context.generation
    sourceContextsLock.unlock()
    return matches && context.isActive
  }

  private func disconnectInputSource(_ source: MIDIEndpointRef) {
    sourceContextsLock.lock()
    let context = sourceContexts.removeValue(forKey: source)
    sourceContextsLock.unlock()
    guard let context else { return }
    context.deactivate()
    if inputPort != 0 {
      MIDIPortDisconnectSource(inputPort, source)
    }
    retainRetiredSourceContext(context)
  }

  private func retainRetiredSourceContext(_ context: SourceContext) {
    retiredSourceContextsLock.lock()
    retiredSourceContexts.append(context)
    retiredSourceContextsLock.unlock()
    // A CoreMIDI callback may already be in flight when disconnect returns.
    // Preserve its opaque refCon briefly; `isActive` suppresses stale events.
    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self, context] in
      guard let self else { return }
      self.retiredSourceContextsLock.lock()
      self.retiredSourceContexts.removeAll { $0 === context }
      self.retiredSourceContextsLock.unlock()
    }
  }

  private func logFirstNetworkIngress(eventCount: Int) {
    networkIngressLogLock.lock()
    let shouldLog = !didLogNetworkIngress
    didLogNetworkIngress = true
    networkIngressLogLock.unlock()
    if shouldLog {
      NSLog("Xen Synth received AppleMIDI input (%d parsed event(s))", eventCount)
    }
  }

  private func networkSessionDidChange() {
    let session = MIDINetworkSession.default()
    let connections = session.connections()
    logNetworkConnections(connections)
    let next = Set(connections.map(Self.networkConnectionKey))
    // The key prefers Bonjour domain/name, so CoreMIDI wrapper replacement and
    // control/data-port changes do not look like a disconnect.  Compare the
    // identities themselves instead of only the count: a peer can disappear
    // while another is added in the same notification, leaving the count
    // unchanged but still requiring release recovery for notes it owned.
    let connectionWasRemoved = !networkConnectionKeys.subtracting(next).isEmpty
    networkConnectionKeys = next
    if inputEnabled && networkInputEnabled {
      // All peers feed CoreMIDI's shared source endpoint. A peer-list change
      // must not disconnect that endpoint; refreshConnections will replace it
      // naturally if CoreMIDI actually publishes a different endpoint ref.
      refreshConnections()
      scheduleNetworkConnectionRefreshes()
    }
    if inputEnabled && connectionWasRemoved {
      networkEventBuffer.clear()
      emit(["type": "allNotesOff", "source": "network"])
    }
  }

  /// The CoreMIDI session chooses the address family for incoming invitations.
  /// Keep those connections alive so the shared network source can deliver
  /// MIDI; IPv4-only selection is applied to Bonjour discovery and outbound
  /// `MIDINetworkHost` construction in `MIDIOutputRouter`.
  private func logNetworkConnections(_ connections: Set<MIDINetworkConnection>) {
    guard !connections.isEmpty else { return }
    let description = connections.map { connection in
      "\(connection.host.address):\(connection.host.port)"
    }.joined(separator: ",")
    NSLog(
      "Xen Synth AppleMIDI connections %@ (IPv4 preferred for outbound; incoming family retained)",
      description
    )
  }

  private static func networkConnectionKey(_ connection: MIDINetworkConnection) -> String {
    let host = connection.host
    return networkConnectionKey(
      serviceDomain: host.netServiceDomain,
      serviceName: host.netServiceName,
      address: host.address,
      port: host.port
    )
  }

  static func networkConnectionKey(
    serviceDomain: String?,
    serviceName: String?,
    address: String,
    port: Int
  ) -> String {
    let domain = serviceDomain?
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased() ?? ""
    let name = serviceName?
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased() ?? ""
    if !name.isEmpty {
      // Bonjour identity describes the peer, while its advertised control
      // and data ports may differ or fall back independently.
      return "service|\(domain)|\(name)"
    }
    let numericAddress = address
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .split(separator: "%", maxSplits: 1)
      .first
      .map(String.init)?
      .lowercased() ?? ""
    return "address|\(numericAddress)|\(port)"
  }

  private static let notifyProc: MIDINotifyProc = { notification, refCon in
    guard let refCon else { return }
    let controller = Unmanaged<MIDIKeyboardController>.fromOpaque(refCon).takeUnretainedValue()
    switch notification.pointee.messageID {
    case .msgObjectAdded, .msgSetupChanged:
      DispatchQueue.main.async {
        controller.refreshConnections()
        controller.scheduleNetworkConnectionRefreshes()
      }
    case .msgObjectRemoved:
      DispatchQueue.main.async {
        controller.refreshConnections()
        controller.scheduleNetworkConnectionRefreshes()
        controller.emit(["type": "allNotesOff"])
      }
    default:
      break
    }
  }

  private static let readProc: MIDIReadProc = { packetList, refCon, sourceConnectionRefCon in
    guard let refCon, let sourceConnectionRefCon else { return }
    let controller = Unmanaged<MIDIKeyboardController>.fromOpaque(refCon).takeUnretainedValue()
    let sourceContext = Unmanaged<SourceContext>
      .fromOpaque(sourceConnectionRefCon)
      .takeUnretainedValue()
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
    if let applicationActiveObserver {
      NotificationCenter.default.removeObserver(applicationActiveObserver)
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
