import Flutter
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

  func testPianoDetectorEmitsA4() {
    let detector = PianoPitchDetector(sampleRate: 16_000, frameSize: 8_192)
    let notes = detector.detect(sineWave(
      frequency: 440,
      sampleRate: 16_000,
      count: 8_192,
      amplitude: 0.7
    ))

    XCTAssertNotNil(notes[69])
    XCTAssertGreaterThan(notes[69] ?? 0, 80)
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

  func testAppleMIDIPortPolicyAllowsActiveTransportOnFixedPorts() {
    XCTAssertTrue(AppleMIDIPortPolicy.allowsActiveTransport(
      sessionEnabled: true,
      networkPort: 5_004
    ))
  }

  func testAppleMIDIPortPolicyRejectsDisabledOrFallbackSessions() {
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
      networkPort: 54_618
    ))
  }

  func testNetworkMidiEventBufferUsesThreefoldDefaults() {
    XCTAssertEqual(NetworkMIDIEventBuffer.defaultPlayoutDelayNanoseconds, 60_000_000)
    XCTAssertEqual(NetworkMIDIEventBuffer.defaultMaximumTimestampSkewNanoseconds, 120_000_000)
    XCTAssertEqual(NetworkMIDIEventBuffer.defaultMaximumPendingEvents, 6_144)
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

}
