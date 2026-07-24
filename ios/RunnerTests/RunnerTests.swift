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
