import Foundation

enum PitchRecognitionMode: String {
  case piano
  case yin
  case fft

  init(wireName: String?) {
    self = PitchRecognitionMode(rawValue: wireName?.lowercased() ?? "") ?? .yin
  }
}

struct YinPitchEstimate {
  let frequencyHz: Double
  let midiPitch: Double
  let confidence: Double
  let rms: Double
}

final class YinPitchSmoother {
  private let referenceIntervalSeconds: Double
  private let smoothingFactor: Double
  private let smoothingRangeSemitones: Double
  private var value: Double?
  private var timeSeconds: Double?

  init(
    referenceIntervalSeconds: Double = 512.0 / 16_000.0,
    smoothingFactor: Double = 0.35,
    smoothingRangeSemitones: Double = 1.5
  ) {
    precondition(referenceIntervalSeconds > 0)
    precondition((0...1).contains(smoothingFactor))
    precondition(smoothingRangeSemitones > 0)
    self.referenceIntervalSeconds = referenceIntervalSeconds
    self.smoothingFactor = smoothingFactor
    self.smoothingRangeSemitones = smoothingRangeSemitones
  }

  func update(_ midiPitch: Double, at timeSeconds: Double) -> Double {
    let next: Double
    if let value, let previousTime = self.timeSeconds,
       abs(midiPitch - value) < smoothingRangeSemitones {
      let measuredInterval = timeSeconds - previousTime
      let interval = measuredInterval.isFinite && measuredInterval > 0
        ? measuredInterval
        : referenceIntervalSeconds
      let factor = 1 - pow(1 - smoothingFactor, interval / referenceIntervalSeconds)
      next = value + (midiPitch - value) * max(0, min(1, factor))
    } else {
      next = midiPitch
    }
    value = next
    self.timeSeconds = timeSeconds
    return next
  }

  func reset() {
    value = nil
    timeSeconds = nil
  }
}

final class YinPitchDetector {
  let sampleRate: Double
  let frameSize: Int

  private let threshold: Double
  private let minimumConfidence: Double
  private let minimumRMS: Double
  private let minimumTau: Int
  private let maximumTau: Int
  private let comparisonLength: Int
  private var difference: [Double]
  private var normalizedDifference: [Double]

  init(
    sampleRate: Double,
    frameSize: Int,
    minimumFrequencyHz: Double = 27.5,
    maximumFrequencyHz: Double = 2_000,
    threshold: Double = 0.15,
    minimumConfidence: Double = 0.70,
    minimumRMS: Double = 0.006
  ) {
    precondition(sampleRate > 0)
    precondition(frameSize >= 256)
    precondition(minimumFrequencyHz > 0 && maximumFrequencyHz > minimumFrequencyHz)

    self.sampleRate = sampleRate
    self.frameSize = frameSize
    self.threshold = threshold
    self.minimumConfidence = minimumConfidence
    self.minimumRMS = minimumRMS
    minimumTau = max(2, Int(floor(sampleRate / maximumFrequencyHz)))
    maximumTau = min(
      Int(ceil(sampleRate / minimumFrequencyHz)),
      frameSize / 2 - 1
    )
    comparisonLength = frameSize - maximumTau
    precondition(minimumTau < maximumTau && comparisonLength > 0)
    difference = Array(repeating: 0, count: maximumTau + 1)
    normalizedDifference = Array(repeating: 1, count: maximumTau + 1)
  }

  func detect(_ samples: [Float]) -> YinPitchEstimate? {
    precondition(samples.count == frameSize)
    let rms = Self.acRMS(samples)
    guard rms.isFinite, rms >= minimumRMS else { return nil }

    for index in difference.indices {
      difference[index] = 0
      normalizedDifference[index] = 1
    }
    for tau in 1...maximumTau {
      var sum = 0.0
      for index in 0..<comparisonLength {
        let delta = Double(samples[index] - samples[index + tau])
        sum += delta * delta
      }
      difference[tau] = sum
    }

    var cumulative = 0.0
    for tau in 1...maximumTau {
      cumulative += difference[tau]
      normalizedDifference[tau] = cumulative <= 0
        ? 1
        : difference[tau] * Double(tau) / cumulative
    }

    var candidate: Int?
    var tau = minimumTau
    while tau <= maximumTau {
      if normalizedDifference[tau] < threshold {
        while tau < maximumTau,
              normalizedDifference[tau + 1] < normalizedDifference[tau] {
          tau += 1
        }
        candidate = tau
        break
      }
      tau += 1
    }
    let selected = candidate ?? (minimumTau...maximumTau).min {
      normalizedDifference[$0] < normalizedDifference[$1]
    }!
    let confidence = max(0, min(1, 1 - normalizedDifference[selected]))
    guard confidence >= minimumConfidence else { return nil }

    let refinedTau = parabolicTau(selected)
    guard refinedTau.isFinite, refinedTau > 0 else { return nil }
    let frequency = sampleRate / refinedTau
    let midiPitch = 69 + 12 * log2(frequency / 440)
    guard frequency.isFinite, midiPitch.isFinite else { return nil }
    return YinPitchEstimate(
      frequencyHz: frequency,
      midiPitch: midiPitch,
      confidence: confidence,
      rms: rms
    )
  }

  private func parabolicTau(_ tau: Int) -> Double {
    guard tau > minimumTau, tau < maximumTau else { return Double(tau) }
    let previous = normalizedDifference[tau - 1]
    let current = normalizedDifference[tau]
    let next = normalizedDifference[tau + 1]
    let denominator = 2 * (2 * current - next - previous)
    guard denominator != 0 else { return Double(tau) }
    return Double(tau) + (next - previous) / denominator
  }

  static func acRMS(_ samples: [Float]) -> Double {
    guard !samples.isEmpty else { return 0 }
    let mean = samples.reduce(0) { $0 + Double($1) } / Double(samples.count)
    let energy = samples.reduce(0) { sum, sample in
      let centered = Double(sample) - mean
      return sum + centered * centered
    }
    return sqrt(energy / Double(samples.count))
  }
}

final class FftSpectrumAnalyzer {
  static let pointCount = 128

  let sampleRate: Double
  let frameSize: Int
  private let window: [Double]
  private let windowSum: Double

  init(sampleRate: Double, frameSize: Int) {
    precondition(sampleRate > 0)
    precondition(frameSize > 1 && frameSize.nonzeroBitCount == 1)
    self.sampleRate = sampleRate
    self.frameSize = frameSize
    window = (0..<frameSize).map { index in
      0.5 - 0.5 * cos(2 * .pi * Double(index) / Double(frameSize - 1))
    }
    windowSum = max(1, window.reduce(0, +))
  }

  func analyze(_ samples: [Float]) -> [Float] {
    precondition(samples.count == frameSize)
    let magnitudes = FourierTransform.magnitudes(
      samples: samples,
      window: window,
      windowSum: windowSum
    )
    return (0..<Self.pointCount).map { point in
      let midiPitch = Double(point) * 127 / Double(Self.pointCount - 1)
      let frequency = 440 * pow(2, (midiPitch - 69) / 12)
      let exactBin = frequency * Double(frameSize) / sampleRate
      guard exactBin < Double(magnitudes.count - 1) else { return 0 }
      let lower = max(0, Int(exactBin))
      let fraction = exactBin - Double(lower)
      let magnitude = magnitudes[lower] * (1 - fraction)
        + magnitudes[lower + 1] * fraction
      let decibels = 20 * log10(max(magnitude, 0.000_000_01))
      return Float(max(0, min(1, (decibels + 90) / 75)))
    }
  }
}

final class PianoPitchDetector {
  let sampleRate: Double
  let frameSize: Int
  private let window: [Double]
  private let windowSum: Double

  init(sampleRate: Double, frameSize: Int) {
    precondition(sampleRate > 0)
    precondition(frameSize > 1 && frameSize.nonzeroBitCount == 1)
    self.sampleRate = sampleRate
    self.frameSize = frameSize
    window = (0..<frameSize).map { index in
      0.5 - 0.5 * cos(2 * .pi * Double(index) / Double(frameSize - 1))
    }
    windowSum = max(1, window.reduce(0, +))
  }

  func detect(_ samples: [Float]) -> [Int: Int] {
    precondition(samples.count == frameSize)
    guard YinPitchDetector.acRMS(samples) >= 0.004 else { return [:] }
    let magnitudes = FourierTransform.magnitudes(
      samples: samples,
      window: window,
      windowSum: windowSum
    )
    let usefulMagnitudes = magnitudes.prefix(min(magnitudes.count, frameSize / 4))
    let sortedNoise = usefulMagnitudes.sorted()
    let noiseFloor = sortedNoise.isEmpty ? 0 : sortedNoise[sortedNoise.count / 2]
    let detectionFloor = max(0.0015, noiseFloor * 10)
    var notes: [Int: Int] = [:]

    for midiPitch in 21...108 {
      let frequency = 440 * pow(2, (Double(midiPitch) - 69) / 12)
      let fundamental = peakMagnitude(
        near: frequency * Double(frameSize) / sampleRate,
        magnitudes: magnitudes
      )
      guard fundamental >= detectionFloor else { continue }

      var harmonicScore = fundamental
      for harmonic in 2...5 {
        let bin = frequency * Double(harmonic) * Double(frameSize) / sampleRate
        guard bin < Double(magnitudes.count - 1) else { break }
        harmonicScore += peakMagnitude(near: bin, magnitudes: magnitudes)
          * (0.55 / Double(harmonic))
      }
      let lowerOctave = peakMagnitude(
        near: frequency * 0.5 * Double(frameSize) / sampleRate,
        magnitudes: magnitudes
      )
      guard harmonicScore >= detectionFloor * 1.25,
            fundamental >= lowerOctave * 0.28 else { continue }

      let decibels = 20 * log10(max(fundamental, 0.000_000_01))
      let normalized = max(0, min(1, (decibels + 62) / 48))
      notes[midiPitch] = max(1, min(127, Int((1 + normalized * 126).rounded())))
    }
    return notes
  }

  private func peakMagnitude(near exactBin: Double, magnitudes: [Double]) -> Double {
    let center = Int(exactBin.rounded())
    guard center > 0, center < magnitudes.count else { return 0 }
    let lower = max(1, center - 1)
    let upper = min(magnitudes.count - 1, center + 1)
    return magnitudes[lower...upper].max() ?? 0
  }
}

private enum FourierTransform {
  static func magnitudes(
    samples: [Float],
    window: [Double],
    windowSum: Double
  ) -> [Double] {
    var real = zip(samples, window).map { Double($0) * $1 }
    var imaginary = Array(repeating: 0.0, count: samples.count)
    fft(real: &real, imaginary: &imaginary)

    var magnitudes = Array(repeating: 0.0, count: samples.count / 2 + 1)
    magnitudes[0] = abs(real[0]) / windowSum
    if samples.count > 1 {
      magnitudes[samples.count / 2] = abs(real[samples.count / 2]) / windowSum
    }
    for bin in 1..<(samples.count / 2) {
      magnitudes[bin] = 2 * hypot(real[bin], imaginary[bin]) / windowSum
    }
    return magnitudes
  }

  private static func fft(real: inout [Double], imaginary: inout [Double]) {
    let count = real.count
    precondition(count == imaginary.count && count.nonzeroBitCount == 1)

    var target = 0
    for index in 1..<count {
      var bit = count >> 1
      while target & bit != 0 {
        target ^= bit
        bit >>= 1
      }
      target ^= bit
      if index < target {
        real.swapAt(index, target)
        imaginary.swapAt(index, target)
      }
    }

    var length = 2
    while length <= count {
      let angle = -2 * Double.pi / Double(length)
      let stepReal = cos(angle)
      let stepImaginary = sin(angle)
      let half = length / 2
      for start in stride(from: 0, to: count, by: length) {
        var twiddleReal = 1.0
        var twiddleImaginary = 0.0
        for offset in 0..<half {
          let even = start + offset
          let odd = even + half
          let oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
          let oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
          real[odd] = real[even] - oddReal
          imaginary[odd] = imaginary[even] - oddImaginary
          real[even] += oddReal
          imaginary[even] += oddImaginary

          let nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
          twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
          twiddleReal = nextReal
        }
      }
      length <<= 1
    }
  }
}
