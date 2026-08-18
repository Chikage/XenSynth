import Flutter
import Darwin
import UIKit
import XCTest
@testable import Runner

class RunnerTests: XCTestCase {

  func testYinDetectsContinuousPitch() {
    let sampleRate = 16_000.0
    let frameSize = 2_048
    let frequency = 432.0
    let samples = sineWave(
      frequency: frequency,
      sampleRate: sampleRate,
      count: frameSize,
      amplitude: 0.7
    )
    let estimate = YinPitchDetector(
      sampleRate: sampleRate,
      frameSize: frameSize
    ).detect(samples)

    XCTAssertNotNil(estimate)
    XCTAssertEqual(estimate?.frequencyHz ?? 0, frequency, accuracy: 1)
    XCTAssertEqual(
      estimate?.midiPitch ?? 0,
      69 + 12 * log2(frequency / 440),
      accuracy: 0.05
    )
    XCTAssertGreaterThan(estimate?.confidence ?? 0, 0.9)
  }

  func testYinSmoothingIsStableAcrossDifferentFrameCadences() {
    let coarse = YinPitchSmoother()
    _ = coarse.update(69, at: 0)
    let coarseResult = coarse.update(70, at: 0.032)

    let fine = YinPitchSmoother()
    _ = fine.update(69, at: 0)
    _ = fine.update(70, at: 0.016)
    let fineResult = fine.update(70, at: 0.032)

    XCTAssertEqual(coarseResult, fineResult, accuracy: 0.000_001)
  }

  func testYinSmoothingDoesNotDelayLargePitchChanges() {
    let smoother = YinPitchSmoother()
    _ = smoother.update(60, at: 0)

    XCTAssertEqual(smoother.update(64, at: 0.016), 64, accuracy: 0.000_001)
  }

  func testSpectrumMapsA440ToMidiPoint() {
    let analyzer = FftSpectrumAnalyzer(sampleRate: 16_000, frameSize: 2_048)
    let magnitudes = analyzer.analyze(sineWave(
      frequency: 440,
      sampleRate: 16_000,
      count: 2_048,
      amplitude: 0.8
    ))

    XCTAssertEqual(magnitudes.count, 128)
    XCTAssertGreaterThan(magnitudes[69], 0.7)
    XCTAssertGreaterThan(magnitudes[69], magnitudes[63])
    XCTAssertGreaterThan(magnitudes[69], magnitudes[75])
  }

  func testHybridPitchDetectorEmitsA4() {
    let samples = sineWave(
      frequency: 440,
      sampleRate: 16_000,
      count: 2_048,
      amplitude: 0.7
    )
    let estimate = HybridPitchFusion.fuse(
      yin: YinPitchDetector(sampleRate: 16_000, frameSize: 2_048).detect(samples),
      fft: FftSpectrumAnalyzer(sampleRate: 16_000, frameSize: 2_048)
        .analyzeFrame(samples)
        .pitchEstimate
    )

    XCTAssertNotNil(estimate)
    XCTAssertEqual(estimate?.midiPitch ?? 0, 69, accuracy: 0.1)
    XCTAssertGreaterThan(estimate?.confidence ?? 0, 0.7)
  }

  func testWaveEncodingCreatesPcmHeader() throws {
    let wave = try PitchRecordingAudio.encodeWave(
      samples: [Int16.min, 0, Int16.max],
      sampleRate: 16_000
    )

    XCTAssertEqual(wave.count, 50)
    XCTAssertEqual(String(data: wave.prefix(4), encoding: .ascii), "RIFF")
    XCTAssertEqual(String(data: wave[8..<12], encoding: .ascii), "WAVE")
    XCTAssertEqual(String(data: wave[36..<40], encoding: .ascii), "data")
  }

  func testScorePlaybackStartsAndCompletes() throws {
    let score = try NativeScore(
      noteMaps: [[
        "start": 0.02,
        "end": 0.08,
        "pitch": 69.0,
        "velocity": 96,
      ]],
      declaredDuration: 0.08
    )
    let controller = ScorePlaybackController()
    let completed = expectation(description: "Score playback completes")
    defer { controller.stop() }

    try controller.play(score: score, from: 0, speed: 1) {
      completed.fulfill()
    }

    wait(for: [completed], timeout: 2)
  }

  func testAppleMIDIPortPolicyAllowsActiveTransportOnSequentialPortPairs() {
    for port in [5_004, 5_006, 5_008, 54_618, 65_534] {
      XCTAssertTrue(AppleMIDIPortPolicy.allowsActiveTransport(
        sessionEnabled: true,
        networkPort: port
      ))
    }
  }

  func testAppleMIDIPortPolicyRejectsDisabledOrInvalidSessions() {
    XCTAssertFalse(AppleMIDIPortPolicy.allowsActiveTransport(
      sessionEnabled: false,
      networkPort: 5_004
    ))
    XCTAssertFalse(AppleMIDIPortPolicy.allowsActiveTransport(
      sessionEnabled: true,
      networkPort: 0
    ))
    XCTAssertFalse(AppleMIDIPortPolicy.allowsActiveTransport(
      sessionEnabled: true,
      networkPort: 5_005
    ))
    XCTAssertFalse(AppleMIDIPortPolicy.allowsActiveTransport(
      sessionEnabled: true,
      networkPort: 65_535
    ))
  }

  func testAppleMIDIIPv4SelectorPrefersPrivateLanAddress() {
    let selected = AppleMIDIIPv4AddressSelector.select(from: [
      socketAddressData("169.254.22.8"),
      socketAddressData("10.36.64.108"),
    ])

    XCTAssertEqual(selected, "10.36.64.108")
    XCTAssertTrue(AppleMIDIIPv4AddressSelector.isPrivate(selected ?? ""))
  }

  func testAppleMIDIIPv4SelectorRejectsNonLanAddresses() {
    XCTAssertNil(AppleMIDIIPv4AddressSelector.select(from: [
      socketAddressData("8.8.8.8"),
      Data(repeating: 0, count: 28),
    ]))
  }

  func testAppleMIDIIPv4SelectorAcceptsIPv4MappedIPv6Address() {
    XCTAssertEqual(
      AppleMIDIIPv4AddressSelector.select(from: [
        mappedIPv6SocketAddressData("192.168.50.24"),
      ]),
      "192.168.50.24"
    )
  }

  func testNetworkMidiOutputBufferUsesBoundedThreefoldDefaults() {
    XCTAssertEqual(NetworkMIDIOutputBuffer.defaultMaximumPendingMessages, 1_536)
    XCTAssertEqual(NetworkMIDIOutputBuffer.defaultNormalBatchDelayNanoseconds, 2_000_000)
    XCTAssertEqual(NetworkMIDIOutputBuffer.defaultCongestedBatchDelayNanoseconds, 4_000_000)
  }

  func testNetworkMidiOutputBufferCoalescesContinuousMessages() {
    let buffer = NetworkMIDIOutputBuffer(
      maximumPendingMessages: 16,
      normalBatchDelayNanoseconds: 1_000_000_000,
      congestedBatchDelayNanoseconds: 1_000_000_000,
      criticalBatchDelayNanoseconds: 1_000_000_000,
      criticalRetryDelayNanoseconds: 1_000_000_000
    )
    var delivered: [[UInt8]] = []
    buffer.onMessages = {
      delivered.append(contentsOf: $0)
      return true
    }
    defer { buffer.close() }

    buffer.enqueue([
      [0xE0, 0, 64],
      [0x90, 60, 100],
      [0xE0, 12, 65],
      [0xB0, 7, 40],
      [0xB0, 7, 96],
      [0xB0, 101, 0],
      [0xB0, 101, 127],
      [0xC0, 5],
      [0x80, 60, 0],
    ])
    buffer.flushSynchronously()

    XCTAssertFalse(delivered.contains([0xE0, 0, 64]))
    XCTAssertFalse(delivered.contains([0xB0, 7, 40]))
    XCTAssertTrue(delivered.contains([0xE0, 12, 65]))
    XCTAssertTrue(delivered.contains([0xB0, 7, 96]))
    XCTAssertEqual(delivered.filter { $0.count >= 2 && $0[1] == 101 }.count, 2)
    XCTAssertTrue(delivered.contains([0x90, 60, 100]))
    XCTAssertTrue(delivered.contains([0xC0, 5]))
    XCTAssertTrue(delivered.contains([0x80, 60, 0]))
  }

  func testNetworkMidiOutputBufferEvictsContinuousDataBeforeReleases() {
    let buffer = NetworkMIDIOutputBuffer(
      maximumPendingMessages: 3,
      normalBatchDelayNanoseconds: 1_000_000_000,
      congestedBatchDelayNanoseconds: 1_000_000_000,
      criticalBatchDelayNanoseconds: 1_000_000_000,
      criticalRetryDelayNanoseconds: 1_000_000_000
    )
    var delivered: [[UInt8]] = []
    buffer.onMessages = {
      delivered.append(contentsOf: $0)
      return true
    }
    defer { buffer.close() }

    buffer.enqueue([
      [0x90, 60, 100],
      [0xE0, 0, 64],
      [0xC0, 5],
      [0x80, 60, 0],
    ])
    buffer.flushSynchronously()

    XCTAssertEqual(delivered, [
      [0x90, 60, 100],
      [0xC0, 5],
      [0x80, 60, 0],
    ])
  }

  func testNetworkMidiOutputBufferUsesPanicWhenCriticalQueueSaturates() {
    let buffer = NetworkMIDIOutputBuffer(
      maximumPendingMessages: 2,
      normalBatchDelayNanoseconds: 1_000_000_000,
      congestedBatchDelayNanoseconds: 1_000_000_000,
      criticalBatchDelayNanoseconds: 1_000_000_000,
      criticalRetryDelayNanoseconds: 1_000_000_000
    )
    var delivered: [[UInt8]] = []
    buffer.onMessages = {
      delivered.append(contentsOf: $0)
      return true
    }
    defer { buffer.close() }

    buffer.enqueue([
      [0x80, 60, 0],
      [0x80, 61, 0],
      [0x80, 62, 0],
    ])
    buffer.flushSynchronously()

    XCTAssertEqual(delivered.count, 49)
    XCTAssertEqual(delivered[0], [0xB0, 64, 0])
    XCTAssertEqual(delivered[1], [0xB0, 120, 0])
    XCTAssertEqual(delivered[47], [0xBF, 123, 0])
    XCTAssertEqual(delivered[48], [0x80, 62, 0])
  }

  func testNetworkMidiOutputBufferRejectsMalformedChannelMessages() {
    let buffer = NetworkMIDIOutputBuffer(
      normalBatchDelayNanoseconds: 1_000_000_000,
      congestedBatchDelayNanoseconds: 1_000_000_000,
      criticalBatchDelayNanoseconds: 1_000_000_000,
      criticalRetryDelayNanoseconds: 1_000_000_000
    )
    var delivered: [[UInt8]] = []
    buffer.onMessages = {
      delivered.append(contentsOf: $0)
      return true
    }
    defer { buffer.close() }

    buffer.enqueue([
      [0x90, 60],
      [0xC0, 5, 1],
      [0xF8, 0],
      [0xC0, 5],
    ])
    buffer.flushSynchronously()

    XCTAssertEqual(delivered, [[0xC0, 5]])
  }

  func testNetworkMidiOutputBufferRetriesReleaseWithoutNewNote() {
    let buffer = makeRetryTestOutputBuffer()
    var delivered: [[UInt8]] = []
    buffer.onMessages = {
      delivered.append(contentsOf: $0)
      return true
    }
    defer { buffer.close() }

    buffer.enqueue([
      [0x90, 60, 100],
      [0x80, 60, 0],
    ])
    waitForOutputRetry()
    buffer.flushSynchronously()

    XCTAssertEqual(delivered.filter { $0 == [0x80, 60, 0] }.count, 2)
  }

  func testNetworkMidiOutputBufferDoesNotRetryAcrossNewNoteGeneration() {
    let buffer = makeRetryTestOutputBuffer()
    var delivered: [[UInt8]] = []
    buffer.onMessages = {
      delivered.append(contentsOf: $0)
      return true
    }
    defer { buffer.close() }

    buffer.enqueue([
      [0x90, 60, 100],
      [0x80, 60, 0],
      [0x90, 60, 90],
    ])
    waitForOutputRetry()
    buffer.flushSynchronously()

    XCTAssertEqual(delivered.filter { $0 == [0x80, 60, 0] }.count, 1)
  }

  func testNetworkMidiOutputBufferInvalidatesRetriesAfterDeliveryGateCloses() {
    let buffer = makeRetryTestOutputBuffer()
    var deliveryAttempts = 0
    buffer.onMessages = { _ in
      deliveryAttempts += 1
      return false
    }
    defer { buffer.close() }

    buffer.enqueue([[0x80, 60, 0]])
    buffer.flushSynchronously()
    waitForOutputRetry()
    buffer.flushSynchronously()

    XCTAssertEqual(deliveryAttempts, 1)
  }

  func testNetworkMidiEventBufferUsesThreefoldDefaults() {
    XCTAssertEqual(NetworkMIDIEventBuffer.defaultPlayoutDelayNanoseconds, 60_000_000)
    XCTAssertEqual(NetworkMIDIEventBuffer.defaultMaximumTimestampSkewNanoseconds, 120_000_000)
    XCTAssertEqual(NetworkMIDIEventBuffer.defaultMaximumPendingEvents, 6_144)
  }

  func testMidiInputSourcesExplicitlyIncludeNetworkEndpoint() {
    XCTAssertEqual(
      MIDIKeyboardController.inputSources(
        enumeratedSources: [11, 12],
        networkSource: 42
      ),
      Set([11, 12, 42])
    )
  }

  func testMidiInputSourcesIgnoreZeroAndDeduplicateNetworkEndpoint() {
    XCTAssertEqual(
      MIDIKeyboardController.inputSources(
        enumeratedSources: [0, 11, 42, 42],
        networkSource: 42
      ),
      Set([11, 42])
    )
  }

  func testMidiNetworkConnectionKeyPrefersStableBonjourIdentity() {
    let first = MIDIKeyboardController.networkConnectionKey(
      serviceDomain: "LOCAL.",
      serviceName: " JustPiano ",
      address: " 192.168.1.27 ",
      port: 5_004
    )
    let second = MIDIKeyboardController.networkConnectionKey(
      serviceDomain: "local.",
      serviceName: "justpiano",
      address: "fe80::27%en0",
      port: 5_005
    )

    XCTAssertEqual(first, second)
    XCTAssertEqual(first, "service|local.|justpiano")
  }

  func testMidiNetworkConnectionKeyNormalizesScopedAddressFallback() {
    let controlPort = MIDIKeyboardController.networkConnectionKey(
      serviceDomain: nil,
      serviceName: nil,
      address: "FE80::27%en0",
      port: 5_004
    )
    let dataPort = MIDIKeyboardController.networkConnectionKey(
      serviceDomain: nil,
      serviceName: nil,
      address: "fe80::27%awdl0",
      port: 5_005
    )

    XCTAssertEqual(controlPort, "address|fe80::27|5004")
    XCTAssertNotEqual(controlPort, dataPort)
  }

  func testNetworkMidiEventBufferPreservesBatchOrder() {
    let buffer = NetworkMIDIEventBuffer(
      playoutDelayNanoseconds: 1_000_000,
      maximumTimestampSkewNanoseconds: 2_000_000,
      maximumPendingEvents: 8
    )
    let delivered = expectation(description: "Buffered MIDI batch delivered")
    var sequences: [Int] = []
    buffer.onEvents = { events in
      sequences.append(contentsOf: events.compactMap { $0["sequence"] as? Int })
      delivered.fulfill()
    }
    defer { buffer.close() }

    buffer.enqueue([
      ["type": "noteOn", "sequence": 1, "midiTimestamp": 0],
      ["type": "noteOn", "sequence": 2, "midiTimestamp": 0],
      ["type": "noteOff", "sequence": 3, "midiTimestamp": 0],
    ])

    wait(for: [delivered], timeout: 1)
    XCTAssertEqual(sequences, [1, 2, 3])
  }

  func testNetworkMidiEventBufferBoundsBacklogAndSignalsPanic() {
    let buffer = NetworkMIDIEventBuffer(
      playoutDelayNanoseconds: 2_000_000,
      maximumTimestampSkewNanoseconds: 4_000_000,
      maximumPendingEvents: 2
    )
    let delivered = expectation(description: "Overflow panic and retained MIDI delivered")
    delivered.expectedFulfillmentCount = 2
    var batches: [[[String: Any]]] = []
    buffer.onEvents = { events in
      batches.append(events)
      delivered.fulfill()
    }
    defer { buffer.close() }

    buffer.enqueue([
      ["type": "noteOn", "sequence": 1, "midiTimestamp": 0],
      ["type": "noteOn", "sequence": 2, "midiTimestamp": 0],
      ["type": "noteOff", "sequence": 3, "midiTimestamp": 0],
    ])

    wait(for: [delivered], timeout: 1)
    XCTAssertEqual(batches.first?.first?["type"] as? String, "allNotesOff")
    XCTAssertEqual(
      batches.last?.compactMap { $0["sequence"] as? Int },
      [2, 3]
    )
  }

  func testNetworkMidiEventBufferClearInvalidatesPendingDelivery() {
    let buffer = NetworkMIDIEventBuffer(
      playoutDelayNanoseconds: 20_000_000,
      maximumTimestampSkewNanoseconds: 40_000_000,
      maximumPendingEvents: 8
    )
    let delivered = expectation(description: "Cleared MIDI is not delivered")
    delivered.isInverted = true
    buffer.onEvents = { _ in delivered.fulfill() }
    defer { buffer.close() }

    buffer.enqueue([["type": "noteOn", "midiTimestamp": 0]])
    buffer.clear()

    wait(for: [delivered], timeout: 0.1)
  }

  func testNetworkMidiEventBufferRejectsIngressCapturedBeforeClear() throws {
    let buffer = NetworkMIDIEventBuffer(
      playoutDelayNanoseconds: 1_000_000,
      maximumTimestampSkewNanoseconds: 2_000_000,
      maximumPendingEvents: 8
    )
    let delivered = expectation(description: "Stale network ingress is rejected")
    delivered.isInverted = true
    buffer.onEvents = { _ in delivered.fulfill() }
    defer { buffer.close() }

    let staleEpoch = try XCTUnwrap(buffer.captureIngressEpoch())
    buffer.clear()
    buffer.enqueue(
      [["type": "noteOn", "midiTimestamp": 0]],
      ingressEpoch: staleEpoch
    )

    wait(for: [delivered], timeout: 0.1)
  }

  func testNetworkMidiEventBufferDoesNotInvalidatePanicOnRepeatedOverflow() throws {
    let buffer = NetworkMIDIEventBuffer(
      playoutDelayNanoseconds: 50_000_000,
      maximumTimestampSkewNanoseconds: 100_000_000,
      maximumPendingEvents: 1
    )
    let panics = expectation(description: "Each overflow can release sounding notes")
    panics.expectedFulfillmentCount = 2
    buffer.onEvents = { events in
      if events.first?["type"] as? String == "allNotesOff" {
        panics.fulfill()
      }
    }
    defer { buffer.close() }

    let epoch = try XCTUnwrap(buffer.captureIngressEpoch())
    buffer.enqueue([
      ["type": "noteOn", "sequence": 1, "midiTimestamp": 0],
      ["type": "noteOn", "sequence": 2, "midiTimestamp": 0],
    ], ingressEpoch: epoch)
    buffer.enqueue([
      ["type": "noteOn", "sequence": 3, "midiTimestamp": 0],
      ["type": "noteOff", "sequence": 4, "midiTimestamp": 0],
    ], ingressEpoch: epoch)

    wait(for: [panics], timeout: 1)
  }

  func testNetworkMidiTargetAppliesConfiguredPlayoutDelay() {
    XCTAssertEqual(
      NetworkMIDIEventBuffer.targetUptimeNanoseconds(
        for: 0,
        arrivalUptimeNanoseconds: 100,
        currentHostTime: 50,
        playoutDelayNanoseconds: 60,
        maximumTimestampSkewNanoseconds: 120
      ),
      160
    )
  }

  private func sineWave(
    frequency: Double,
    sampleRate: Double,
    count: Int,
    amplitude: Double
  ) -> [Float] {
    (0..<count).map { index in
      Float(amplitude * sin(2 * .pi * frequency * Double(index) / sampleRate))
    }
  }

  private func makeRetryTestOutputBuffer() -> NetworkMIDIOutputBuffer {
    NetworkMIDIOutputBuffer(
      maximumPendingMessages: 16,
      normalBatchDelayNanoseconds: 1_000_000_000,
      congestedBatchDelayNanoseconds: 1_000_000_000,
      criticalBatchDelayNanoseconds: 1_000_000_000,
      criticalRetryDelayNanoseconds: 5_000_000
    )
  }

  private func waitForOutputRetry() {
    let elapsed = expectation(description: "Critical MIDI retry elapsed")
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(25)) {
      elapsed.fulfill()
    }
    wait(for: [elapsed], timeout: 0.2)
  }

  private func socketAddressData(_ address: String) -> Data {
    var storage = sockaddr_in()
    storage.sin_family = UInt8(AF_INET)
    address.withCString { pointer in
      _ = inet_pton(AF_INET, pointer, &storage.sin_addr)
    }
    return Data(bytes: &storage, count: MemoryLayout<sockaddr_in>.size)
  }

  private func mappedIPv6SocketAddressData(_ address: String) -> Data {
    var storage = sockaddr_in6()
    storage.sin6_family = UInt8(AF_INET6)
    "::ffff:\(address)".withCString { pointer in
      _ = inet_pton(AF_INET6, pointer, &storage.sin6_addr)
    }
    return Data(bytes: &storage, count: MemoryLayout<sockaddr_in6>.size)
  }

}
