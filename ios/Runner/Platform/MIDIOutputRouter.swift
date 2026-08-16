import CoreMIDI
import Foundation

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
  private var networkEnabled = false

  func bluetoothDestinations() -> [[String: Any]] {
    endpoints().compactMap { endpoint in
      guard endpoint != networkSession.destinationEndpoint else { return nil }
      guard let id = endpointId(for: endpoint) else { return nil }
      return ["id": id, "name": displayName(for: endpoint)]
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
    if !enabled { allNotesOff() }
    outputEnabled = enabled
  }

  func configureNetwork(enabled: Bool) {
    if networkEnabled && !enabled {
      sendNetworkMessages(Self.panicMessages)
    }
    networkEnabled = enabled
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
    guard outputEnabled, !messages.isEmpty else { return }
    let destinations = endpoints().filter { endpoint in
      guard endpoint != networkSession.destinationEndpoint else { return false }
      return endpointId(for: endpoint).map(selectedBluetoothDestinationIds.contains) ?? false
    }
    for destination in destinations {
      send(messages, to: destination)
    }
    if sendToNetwork {
      sendNetworkMessages(messages)
    }
  }

  private func sendNetworkMessages(_ messages: [[UInt8]]) {
    let networkDestination = networkSession.destinationEndpoint
    guard outputEnabled, networkEnabled, networkDestination != 0 else { return }
    send(messages, to: networkDestination)
  }

  private func send(_ messages: [[UInt8]], to destination: MIDIEndpointRef) {
    guard prepareOutputPort() else { return }
    var packetList = MIDIPacketList()
    var packet = MIDIPacketListInit(&packetList)
    for message in messages {
      message.withUnsafeBufferPointer { data in
        guard let baseAddress = data.baseAddress else { return }
        let nextPacket = MIDIPacketListAdd(
          &packetList,
          1_024,
          packet,
          0,
          message.count,
          baseAddress
        )
        packet = nextPacket
      }
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

  private static let panicMessages: [[UInt8]] = (0..<channelCount).flatMap { channel in
    [
      [UInt8(0xB0 | channel), 120, 0],
      [UInt8(0xB0 | channel), 123, 0],
    ]
  }

  deinit {
    allNotesOff()
    networkSession.close()
    if outputPort != 0 { MIDIPortDispose(outputPort) }
    if client != 0 { MIDIClientDispose(client) }
  }
}

/// Owns the system AppleMIDI session and the Bonjour services used to initiate it.
///
/// CoreMIDI implements the AppleMIDI control channel, RTP-MIDI payloads, clock
/// synchronization, packet recovery, and the dynamic control/data UDP port pair.
private final class AppleMIDINetworkSession: NSObject {
  private static let destinationIdPrefix = "applemidi:"
  private static let scanDuration: TimeInterval = 1.25

  private let session = MIDINetworkSession.default()
  private let browser = NetServiceBrowser()
  private var servicesById: [String: NetService] = [:]
  private var selectedDestinationIds = Set<String>()
  private var initiatedConnections: [String: MIDINetworkConnection] = [:]
  private var pendingScans: [([[String: Any]]) -> Void] = []
  private var browserStarted = false
  private var enabled = false
  private var closed = false

  var destinationEndpoint: MIDIEndpointRef {
    session.destinationEndpoint()
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
        _ = self.session.removeConnection(connection)
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
    servicesById.compactMap { id, service in
      guard !isLocalService(service) else { return nil }
      return [
        "id": id,
        "name": service.name,
        "protocol": "rtp-midi",
        "connected": connectionExists(for: service),
      ]
    }.sorted {
      ($0["name"] as? String ?? "").localizedCaseInsensitiveCompare(
        $1["name"] as? String ?? ""
      ) == .orderedAscending
    }
  }

  private func reconcileConnections() {
    guard !closed, enabled, session.isEnabled else { return }
    let liveConnections = session.connections()
    let staleIds = initiatedConnections.compactMap { id, connection in
      liveConnections.contains(where: { $0 === connection }) ? nil : id
    }
    staleIds.forEach { initiatedConnections.removeValue(forKey: $0) }
    for id in selectedDestinationIds where initiatedConnections[id] == nil {
      guard let service = servicesById[id], !isLocalService(service) else { continue }
      let host = MIDINetworkHost(name: service.name, netService: service)
      if liveConnections.contains(where: { $0.host.hasSameAddress(as: host) }) {
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

  private func connectionExists(for service: NetService) -> Bool {
    let host = MIDINetworkHost(name: service.name, netService: service)
    return session.connections().contains { $0.host.hasSameAddress(as: host) }
  }

  private func isLocalService(_ service: NetService) -> Bool {
    service.name == session.networkName
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
    servicesById[destinationId(for: service)] = service
    reconcileConnections()
  }

  func netServiceBrowser(
    _ browser: NetServiceBrowser,
    didRemove service: NetService,
    moreComing: Bool
  ) {
    let id = destinationId(for: service)
    servicesById.removeValue(forKey: id)
    if let connection = initiatedConnections.removeValue(forKey: id) {
      _ = session.removeConnection(connection)
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
