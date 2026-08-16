import Foundation

enum PitchRecognitionMode: String {
  case hybrid

  init(wireName: String?) {
    self = .hybrid
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

struct SpectrumPeak {
  let midiPitch: Double
  let magnitude: Float
}

struct FftPitchEstimate {
  let frequencyHz: Double
  let midiPitch: Double
  let confidence: Double
  let rms: Double
}

struct FftSpectrumResult {
  let magnitudes: [Float]
  let peaks: [SpectrumPeak]
  let pitchEstimate: FftPitchEstimate?
}

struct HybridPitchEstimate {
  let frequencyHz: Double
  let midiPitch: Double
  let confidence: Double
  let rms: Double
  let algorithm: String
}

enum HybridPitchFusion {
  static let fusedAlgorithm = "yin+fft"
  static let yinAlgorithm = "yin"
  static let fftAlgorithm = "fft"

  static func fuse(yin: YinPitchEstimate?, fft: FftPitchEstimate?) -> HybridPitchEstimate? {
    guard let yin else {
      guard let fft, fft.confidence >= 0.50 else { return nil }
      return hybrid(fft, algorithm: fftAlgorithm)
    }
    guard let fft else { return hybrid(yin, algorithm: yinAlgorithm) }

    let distance = abs(yin.midiPitch - fft.midiPitch)
    if distance <= 1.5 {
      let yinWeight = max(0.01, yin.confidence)
      let fftWeight = max(0.01, fft.confidence) * 0.55
      let pitch = (yin.midiPitch * yinWeight + fft.midiPitch * fftWeight)
        / (yinWeight + fftWeight)
      return HybridPitchEstimate(
        frequencyHz: midiToFrequency(pitch),
        midiPitch: pitch,
        confidence: max(0, min(1, yin.confidence + fft.confidence * 0.5)),
        rms: max(yin.rms, fft.rms),
        algorithm: fusedAlgorithm
      )
    }

    let octaveDistance = distance.truncatingRemainder(dividingBy: 12)
    let nearOctave = min(octaveDistance, 12 - octaveDistance) <= 1.5
    if nearOctave, fft.confidence >= 0.58 {
      return hybrid(fft, algorithm: fusedAlgorithm)
    }
    if yin.confidence >= 0.84 { return hybrid(yin, algorithm: yinAlgorithm) }
    if fft.confidence >= 0.64 { return hybrid(fft, algorithm: fftAlgorithm) }
    return hybrid(yin, algorithm: yinAlgorithm)
  }

  private static func hybrid(
    _ estimate: YinPitchEstimate,
    algorithm: String
  ) -> HybridPitchEstimate {
    HybridPitchEstimate(
      frequencyHz: estimate.frequencyHz,
      midiPitch: estimate.midiPitch,
      confidence: estimate.confidence,
      rms: estimate.rms,
      algorithm: algorithm
    )
  }

  private static func hybrid(
    _ estimate: FftPitchEstimate,
    algorithm: String
  ) -> HybridPitchEstimate {
    HybridPitchEstimate(
      frequencyHz: estimate.frequencyHz,
      midiPitch: estimate.midiPitch,
      confidence: estimate.confidence,
      rms: estimate.rms,
      algorithm: algorithm
    )
  }

  private static func midiToFrequency(_ midiPitch: Double) -> Double {
    440 * pow(2, (midiPitch - 69) / 12)
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
    analyzeFrame(samples).magnitudes
  }

  func analyzeFrame(_ samples: [Float]) -> FftSpectrumResult {
    precondition(samples.count == frameSize)
    let rawMagnitudes = FourierTransform.magnitudes(
      samples: samples,
      window: window,
      windowSum: windowSum
    )
    let display = displayMagnitudes(rawMagnitudes)
    let rms = YinPitchDetector.acRMS(samples)
    guard rms.isFinite, rms >= 0.0035 else {
      return FftSpectrumResult(magnitudes: display, peaks: [], pitchEstimate: nil)
    }

    let minimumBin = max(2, Int(frequencyToBin(27.5).rounded()))
    let maximumBin = min(rawMagnitudes.count - 3, Int(frequencyToBin(2_000).rounded()))
    guard maximumBin > minimumBin else {
      return FftSpectrumResult(magnitudes: display, peaks: [], pitchEstimate: nil)
    }
    let useful = Array(rawMagnitudes[minimumBin...maximumBin])
    guard let maximumMagnitude = useful.max(), maximumMagnitude > 0.000_000_01 else {
      return FftSpectrumResult(magnitudes: display, peaks: [], pitchEstimate: nil)
    }
    let sortedNoise = useful.sorted()
    let noiseFloor = sortedNoise[sortedNoise.count / 2]
    let detectionFloor = max(0.000_15, noiseFloor * 6, maximumMagnitude * 0.035)
    var rawPeaks: [RawPeak] = []
    for bin in minimumBin...maximumBin {
      let magnitude = rawMagnitudes[bin]
      if magnitude >= detectionFloor,
         magnitude >= rawMagnitudes[bin - 1],
         magnitude > rawMagnitudes[bin + 1] {
        rawPeaks.append(RawPeak(bin: refinedBin(bin, rawMagnitudes), magnitude: magnitude))
      }
    }
    guard !rawPeaks.isEmpty else {
      return FftSpectrumResult(magnitudes: display, peaks: [], pitchEstimate: nil)
    }

    let peaks = rawPeaks
      .sorted { $0.magnitude > $1.magnitude }
      .prefix(16)
      .compactMap { peak -> SpectrumPeak? in
        let midiPitch = frequencyToMidi(binToFrequency(peak.bin))
        guard midiPitch.isFinite, (0...127).contains(midiPitch) else { return nil }
        return SpectrumPeak(
          midiPitch: midiPitch,
          magnitude: normalizedMagnitude(peak.magnitude)
        )
      }
      .sorted { $0.midiPitch < $1.midiPitch }

    var candidates: [CandidateScore] = []
    for peak in rawPeaks.sorted(by: { $0.magnitude > $1.magnitude }).prefix(12) {
      for divisor in 1...5 {
        let frequency = binToFrequency(peak.bin) / Double(divisor)
        guard (27.5...2_000).contains(frequency) else { continue }
        candidates.append(scoreCandidate(
          frequencyHz: frequency,
          rawMagnitudes: rawMagnitudes,
          maximumMagnitude: maximumMagnitude,
          detectionFloor: detectionFloor
        ))
      }
    }
    candidates.sort { $0.score > $1.score }
    guard let best = candidates.first else {
      return FftSpectrumResult(magnitudes: display, peaks: peaks, pitchEstimate: nil)
    }
    let selectedPitch = frequencyToMidi(best.frequencyHz)
    let runnerUp = candidates.dropFirst().first {
      abs(frequencyToMidi($0.frequencyHz) - selectedPitch) > 0.75
    }
    let signalDecibels = 20 * log10(max(maximumMagnitude, 0.000_000_01))
    let signalStrength = max(0, min(1, (signalDecibels + 70) / 45))
    let separation = best.score <= 0
      ? 0
      : max(0, min(1, (best.score - (runnerUp?.score ?? 0)) / best.score))
    let coverage = max(0, min(1, best.support / harmonicWeights.reduce(0, +)))
    let confidence = max(0, min(1, 0.30 * signalStrength + 0.35 * separation + 0.35 * coverage))
    let estimate = confidence >= 0.42 && selectedPitch.isFinite && (0...127).contains(selectedPitch)
      ? FftPitchEstimate(
          frequencyHz: best.frequencyHz,
          midiPitch: selectedPitch,
          confidence: confidence,
          rms: rms
        )
      : nil
    return FftSpectrumResult(magnitudes: display, peaks: peaks, pitchEstimate: estimate)
  }

  private func displayMagnitudes(_ rawMagnitudes: [Double]) -> [Float] {
    (0..<Self.pointCount).map { point in
      let midiPitch = Double(point) * 127 / Double(Self.pointCount - 1)
      let exactBin = frequencyToBin(440 * pow(2, (midiPitch - 69) / 12))
      guard exactBin < Double(rawMagnitudes.count - 1) else { return 0 }
      let lower = max(0, Int(exactBin))
      let fraction = exactBin - Double(lower)
      return normalizedMagnitude(
        rawMagnitudes[lower] * (1 - fraction) + rawMagnitudes[lower + 1] * fraction
      )
    }
  }

  private func scoreCandidate(
    frequencyHz: Double,
    rawMagnitudes: [Double],
    maximumMagnitude: Double,
    detectionFloor: Double
  ) -> CandidateScore {
    var support = 0.0
    var matches = 0
    for index in harmonicWeights.indices {
      let exactBin = frequencyToBin(frequencyHz * Double(index + 1))
      guard exactBin < Double(rawMagnitudes.count - 1) else { break }
      let magnitude = peakMagnitude(near: exactBin, magnitudes: rawMagnitudes)
      support += harmonicWeights[index] * max(0, min(1, magnitude / maximumMagnitude))
      if magnitude >= detectionFloor * 0.75 { matches += 1 }
    }
    let fundamental = peakMagnitude(
      near: frequencyToBin(frequencyHz),
      magnitudes: rawMagnitudes
    )
    let fundamentalRatio = max(0, min(1, fundamental / maximumMagnitude))
    let fundamentalWeight = 0.55 + 0.45 * sqrt(fundamentalRatio)
    let matchWeight = 0.8 + 0.2 * Double(matches) / Double(harmonicWeights.count)
    return CandidateScore(
      frequencyHz: frequencyHz,
      score: support * fundamentalWeight * matchWeight,
      support: support
    )
  }

  private func peakMagnitude(near exactBin: Double, magnitudes: [Double]) -> Double {
    let center = Int(exactBin.rounded())
    guard center > 0, center < magnitudes.count - 1 else { return 0 }
    return magnitudes[max(1, center - 1)...min(magnitudes.count - 1, center + 1)].max() ?? 0
  }

  private func refinedBin(_ bin: Int, _ magnitudes: [Double]) -> Double {
    guard bin > 0, bin < magnitudes.count - 1 else { return Double(bin) }
    let previous = log(max(magnitudes[bin - 1], 0.000_000_01))
    let current = log(max(magnitudes[bin], 0.000_000_01))
    let next = log(max(magnitudes[bin + 1], 0.000_000_01))
    let denominator = previous - 2 * current + next
    guard abs(denominator) >= 0.000_000_000_001 else { return Double(bin) }
    return Double(bin) + max(-0.5, min(0.5, 0.5 * (previous - next) / denominator))
  }

  private func normalizedMagnitude(_ magnitude: Double) -> Float {
    let decibels = 20 * log10(max(magnitude, 0.000_000_01))
    return Float(max(0, min(1, (decibels + 90) / 75)))
  }

  private func frequencyToBin(_ frequencyHz: Double) -> Double {
    frequencyHz * Double(frameSize) / sampleRate
  }

  private func binToFrequency(_ bin: Double) -> Double {
    bin * sampleRate / Double(frameSize)
  }

  private func frequencyToMidi(_ frequencyHz: Double) -> Double {
    69 + 12 * log2(frequencyHz / 440)
  }

  private struct RawPeak {
    let bin: Double
    let magnitude: Double
  }

  private struct CandidateScore {
    let frequencyHz: Double
    let score: Double
    let support: Double
  }

  private let harmonicWeights = [0.90, 0.45, 0.30, 0.22, 0.18, 0.14]
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
