import AVFAudio
import Foundation

protocol PitchRecognitionManagerListener: AnyObject {
  func pitchRecognitionManager(
    _ manager: PitchRecognitionManager,
    didUpdateState state: [String: Any]
  )

  func pitchRecognitionManager(
    _ manager: PitchRecognitionManager,
    didRecognizeNote pitch: Int,
    velocity: Int,
    down: Bool,
    timeSeconds: Double
  )

  func pitchRecognitionManager(
    _ manager: PitchRecognitionManager,
    didRecognizePitch voiced: Bool,
    frequencyHz: Double,
    midiPitch: Double,
    confidence: Double,
    velocity: Int,
    timeSeconds: Double
  )

  func pitchRecognitionManager(
    _ manager: PitchRecognitionManager,
    didAnalyzeSpectrum magnitudes: [Float],
    timeSeconds: Double
  )
}

final class PitchRecognitionManager: NSObject {
  weak var listener: PitchRecognitionManagerListener?

  private let stateLock = NSRecursiveLock()
  private let captureQueue = DispatchQueue(
    label: "icu.ringona.xensynth.pitch-capture",
    qos: .userInitiated
  )
  private let analysisQueue = DispatchQueue(
    label: "icu.ringona.xensynth.pitch-analysis",
    qos: .userInitiated
  )
  private let pendingAnalysisLock = NSLock()
  private var pendingAnalysisFrame: PendingAnalysisFrame?
  private var analysisDrainScheduled = false

  private var selectedMode = PitchRecognitionMode.yin
  private var phase = Phase.idle
  private var message = ""
  private var generation: UInt64 = 0
  private var sensitivity: Float = 1
  private var recording: PitchRecording?
  private var closed = false

  // Accessed only from captureQueue.
  private var audioEngine: AVAudioEngine?
  private var captureSessionActive = false
  private var resampler: LinearAudioResampler?
  private var frameAccumulator: AudioFrameAccumulator?

  // Accessed only from analysisQueue.
  private var yinDetector: YinPitchDetector?
  private var spectrumAnalyzer: FftSpectrumAnalyzer?
  private var pianoDetector: PianoPitchDetector?
  private let yinPitchSmoother = YinPitchSmoother()
  private var yinVoiced = false
  private var yinUnvoicedFrames = 0
  private var pianoActiveNotes: [Int: Int] = [:]
  private var pianoPresentFrames: [Int: Int] = [:]
  private var pianoAbsentFrames: [Int: Int] = [:]

  private var recordingPlayer: AVAudioPlayer?
  private var recordingPlaybackURL: URL?

  init(listener: PitchRecognitionManagerListener? = nil) {
    self.listener = listener
  }

  func state() -> [String: Any] {
    withStateLock { stateLocked() }
  }

  func setSensitivity(_ value: Double) {
    withStateLock {
      sensitivity = Float(max(0.5, min(2, value)))
    }
  }

  @discardableResult
  func start(mode: PitchRecognitionMode) -> [String: Any] {
    stopRecordingPlayback()
    let permission = AVAudioSession.sharedInstance().recordPermission
    switch permission {
    case .granted:
      scheduleStart(mode: mode)
    case .denied:
      withStateLock {
        selectedMode = mode
        phase = .error
        message = "Microphone permission was denied"
      }
      emitState()
    case .undetermined:
      withStateLock {
        selectedMode = mode
        phase = .permission
        message = "Microphone permission is required"
      }
      emitState()
      AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
        self?.resolvePermission(granted: granted, mode: mode)
      }
    @unknown default:
      withStateLock {
        selectedMode = mode
        phase = .error
        message = "Microphone permission is unavailable"
      }
      emitState()
    }
    return state()
  }

  @discardableResult
  func stop() -> [String: Any] {
    withStateLock {
      generation &+= 1
      phase = .idle
      message = ""
    }
    captureQueue.async { [weak self] in
      self?.stopCaptureEngine()
    }
    analysisQueue.async { [weak self] in
      self?.releaseAnalysisState()
    }
    emitState()
    return state()
  }

  func playRecording(fromSeconds: Double) throws -> Bool {
    guard let snapshot = withStateLock({ recording?.snapshot() }),
          !snapshot.samples.isEmpty else { return false }
    let wave = try PitchRecordingAudio.encodeWave(
      samples: snapshot.samples,
      sampleRate: snapshot.sampleRate
    )
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("XenSynthPitchPlayback", isDirectory: true)
    try FileManager.default.createDirectory(
      at: directory,
      withIntermediateDirectories: true,
      attributes: nil
    )
    let url = directory.appendingPathComponent("microphone-take.wav")
    try wave.write(to: url, options: .atomic)
    recordingPlaybackURL = url
    try XenAudioSession.shared.activateForPlayback()
    let player = try AVAudioPlayer(contentsOf: url)
    player.currentTime = max(0, min(player.duration, fromSeconds))
    player.prepareToPlay()
    guard player.play() else { return false }
    recordingPlayer = player
    return true
  }

  func stopRecordingPlayback() {
    recordingPlayer?.stop()
    recordingPlayer = nil
  }

  func discardRecording() {
    stopRecordingPlayback()
    if let recordingPlaybackURL {
      try? FileManager.default.removeItem(at: recordingPlaybackURL)
      self.recordingPlaybackURL = nil
    }
    withStateLock {
      recording = nil
    }
    emitState()
  }

  func recordingSnapshot() -> PitchRecordingSnapshot? {
    withStateLock { recording?.snapshot() }
  }

  func emitCurrentState() {
    emitState()
  }

  func close() {
    let shouldClose = withStateLock {
      if closed { return false }
      closed = true
      generation &+= 1
      phase = .idle
      return true
    }
    guard shouldClose else { return }
    stopRecordingPlayback()
    captureQueue.sync { stopCaptureEngine() }
    analysisQueue.sync { releaseAnalysisState() }
  }

  private func resolvePermission(granted: Bool, mode: PitchRecognitionMode) {
    guard granted else {
      withStateLock {
        guard !closed, phase == .permission, selectedMode == mode else { return }
        phase = .error
        message = "Microphone permission was denied"
      }
      emitState()
      return
    }
    let shouldStart = withStateLock {
      !closed && phase == .permission && selectedMode == mode
    }
    if shouldStart { scheduleStart(mode: mode) }
  }

  private func scheduleStart(mode: PitchRecognitionMode) {
    let startGeneration: UInt64? = withStateLock {
      guard !closed, phase != .starting, phase != .listening else { return nil }
      selectedMode = mode
      phase = .starting
      message = "Starting microphone recognition"
      generation &+= 1
      return generation
    }
    guard let startGeneration else { return }
    emitState()
    captureQueue.async { [weak self] in
      self?.startCaptureEngine(mode: mode, generation: startGeneration)
    }
  }

  private func startCaptureEngine(mode: PitchRecognitionMode, generation: UInt64) {
    stopCaptureEngine()
    do {
      try XenAudioSession.shared.beginCapture()
      captureSessionActive = true
      let engine = AVAudioEngine()
      let input = engine.inputNode
      let format = input.outputFormat(forBus: 0)
      guard format.sampleRate > 0, format.channelCount > 0 else {
        throw CaptureError.invalidInputFormat
      }

      let frameSize = mode == .piano ? Self.pianoFrameSize : Self.analysisFrameSize
      resampler = LinearAudioResampler(
        sourceSampleRate: format.sampleRate,
        targetSampleRate: Double(Self.recordingSampleRate)
      )
      frameAccumulator = AudioFrameAccumulator(
        frameSize: frameSize,
        hopSize: mode == .yin ? Self.yinAnalysisHopSize : Self.analysisHopSize
      )
      analysisQueue.async { [weak self] in
        self?.prepareAnalysis(mode: mode)
      }

      input.installTap(
        onBus: 0,
        bufferSize: mode == .yin ? Self.yinInputTapBufferSize : Self.inputTapBufferSize,
        format: format
      ) { [weak self] buffer, _ in
        guard let channel = buffer.floatChannelData?.pointee else { return }
        let samples = Array(
          UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength))
        )
        self?.captureQueue.async { [weak self] in
          self?.processInput(
            samples,
            mode: mode,
            generation: generation
          )
        }
      }
      engine.prepare()
      try engine.start()

      let nextRecording = PitchRecording(sampleRate: Self.recordingSampleRate)
      let accepted = withStateLock {
        guard !closed, self.generation == generation, phase == .starting else { return false }
        audioEngine = engine
        recording = nextRecording
        phase = .listening
        message = switch mode {
        case .piano: "Listening for piano notes"
        case .yin: "Listening for continuous pitch"
        case .fft: "Listening for FFT spectrum"
        }
        return true
      }
      guard accepted else {
        input.removeTap(onBus: 0)
        engine.stop()
        finishCaptureSession()
        return
      }
      emitState()
    } catch {
      stopCaptureEngine()
      withStateLock {
        guard !closed, self.generation == generation else { return }
        phase = .error
        message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
      }
      emitState()
    }
  }

  private func stopCaptureEngine() {
    if let audioEngine {
      audioEngine.inputNode.removeTap(onBus: 0)
      audioEngine.stop()
      self.audioEngine = nil
    }
    resampler = nil
    frameAccumulator = nil
    finishCaptureSession()
  }

  private func finishCaptureSession() {
    guard captureSessionActive else { return }
    captureSessionActive = false
    XenAudioSession.shared.endCapture()
  }

  private func processInput(
    _ inputSamples: [Float],
    mode: PitchRecognitionMode,
    generation: UInt64
  ) {
    guard isCurrent(generation: generation, phase: .listening),
          let resampler,
          let frameAccumulator else { return }
    let resampled = resampler.process(inputSamples)
    guard !resampled.isEmpty else { return }
    let inputGain = withStateLock { sensitivity }
    let pcm = resampled.map { sample -> Int16 in
      Int16(clamping: Int((max(-1, min(1, sample)) * Float(Int16.max)).rounded()))
    }
    withStateLock { recording?.append(pcm) }
    let analyzed = resampled.map { max(-1, min(1, $0 * inputGain)) }
    let frames = frameAccumulator.append(analyzed)
    let captureTimeSeconds = withStateLock { recording?.durationSeconds ?? 0 }
    for frame in frames {
      let pending = PendingAnalysisFrame(
        samples: frame.samples,
        mode: mode,
        generation: generation,
        timeSeconds: mode == .yin
          ? Double(frame.endSampleIndex) / Double(Self.recordingSampleRate)
          : captureTimeSeconds
      )
      if mode == .yin {
        enqueueAnalysis(pending)
      } else {
        analysisQueue.async { [weak self] in
          self?.analyze(
            pending.samples,
            mode: pending.mode,
            generation: pending.generation,
            timeSeconds: pending.timeSeconds
          )
        }
      }
    }
  }

  private func enqueueAnalysis(_ frame: PendingAnalysisFrame) {
    pendingAnalysisLock.lock()
    pendingAnalysisFrame = frame
    let shouldSchedule = !analysisDrainScheduled
    analysisDrainScheduled = true
    pendingAnalysisLock.unlock()
    guard shouldSchedule else { return }
    analysisQueue.async { [weak self] in self?.drainPendingAnalysis() }
  }

  private func drainPendingAnalysis() {
    pendingAnalysisLock.lock()
    guard let frame = pendingAnalysisFrame else {
      analysisDrainScheduled = false
      pendingAnalysisLock.unlock()
      return
    }
    pendingAnalysisFrame = nil
    pendingAnalysisLock.unlock()

    analyze(
      frame.samples,
      mode: frame.mode,
      generation: frame.generation,
      timeSeconds: frame.timeSeconds
    )

    pendingAnalysisLock.lock()
    let shouldContinue = pendingAnalysisFrame != nil
    if !shouldContinue {
      analysisDrainScheduled = false
    }
    pendingAnalysisLock.unlock()
    if shouldContinue {
      analysisQueue.async { [weak self] in self?.drainPendingAnalysis() }
    }
  }

  private func prepareAnalysis(mode: PitchRecognitionMode) {
    yinDetector = mode == .yin
      ? YinPitchDetector(
          sampleRate: Double(Self.recordingSampleRate),
          frameSize: Self.analysisFrameSize
        )
      : nil
    spectrumAnalyzer = mode == .fft
      ? FftSpectrumAnalyzer(
          sampleRate: Double(Self.recordingSampleRate),
          frameSize: Self.analysisFrameSize
        )
      : nil
    pianoDetector = mode == .piano
      ? PianoPitchDetector(
          sampleRate: Double(Self.recordingSampleRate),
          frameSize: Self.pianoFrameSize
        )
      : nil
    yinPitchSmoother.reset()
    yinVoiced = false
    yinUnvoicedFrames = 0
    pianoActiveNotes.removeAll()
    pianoPresentFrames.removeAll()
    pianoAbsentFrames.removeAll()
  }

  private func analyze(
    _ frame: [Float],
    mode: PitchRecognitionMode,
    generation: UInt64,
    timeSeconds: Double
  ) {
    guard isCurrent(generation: generation, phase: .listening) else { return }
    switch mode {
    case .yin:
      analyzeYin(frame, timeSeconds: timeSeconds)
    case .fft:
      guard let magnitudes = spectrumAnalyzer?.analyze(frame) else { return }
      emitSpectrum(magnitudes, timeSeconds: timeSeconds)
    case .piano:
      guard let notes = pianoDetector?.detect(frame) else { return }
      analyzePiano(notes, timeSeconds: timeSeconds)
    }
  }

  private func analyzeYin(_ frame: [Float], timeSeconds: Double) {
    guard let estimate = yinDetector?.detect(frame) else {
      yinUnvoicedFrames += 1
      if yinVoiced, yinUnvoicedFrames >= Self.yinUnvoicedFrameCount {
        yinVoiced = false
        yinPitchSmoother.reset()
        emitPitch(
          voiced: false,
          frequencyHz: 0,
          midiPitch: 0,
          confidence: 0,
          velocity: 0,
          timeSeconds: timeSeconds
        )
      }
      return
    }

    yinUnvoicedFrames = 0
    let nextMidiPitch = yinPitchSmoother.update(
      estimate.midiPitch,
      at: timeSeconds
    )
    yinVoiced = true
    let decibels = 20 * log10(max(estimate.rms, 0.000_000_001))
    let normalized = max(0, min(1, (decibels + 60) / 48))
    let velocity = max(1, min(127, Int((1 + normalized * 126).rounded())))
    emitPitch(
      voiced: true,
      frequencyHz: 440 * pow(2, (nextMidiPitch - 69) / 12),
      midiPitch: nextMidiPitch,
      confidence: estimate.confidence,
      velocity: velocity,
      timeSeconds: timeSeconds
    )
  }

  private func analyzePiano(_ detected: [Int: Int], timeSeconds: Double) {
    let pitches = Set(detected.keys).union(pianoActiveNotes.keys)
    for pitch in pitches.sorted() {
      if let velocity = detected[pitch] {
        pianoAbsentFrames[pitch] = 0
        pianoPresentFrames[pitch, default: 0] += 1
        if pianoActiveNotes[pitch] == nil,
           pianoPresentFrames[pitch, default: 0] >= Self.pianoNoteOnFrames {
          pianoActiveNotes[pitch] = velocity
          emitNote(pitch: pitch, velocity: velocity, down: true, timeSeconds: timeSeconds)
        } else if pianoActiveNotes[pitch] != nil {
          pianoActiveNotes[pitch] = velocity
        }
      } else if pianoActiveNotes[pitch] != nil {
        pianoPresentFrames[pitch] = 0
        pianoAbsentFrames[pitch, default: 0] += 1
        if pianoAbsentFrames[pitch, default: 0] >= Self.pianoNoteOffFrames {
          pianoActiveNotes.removeValue(forKey: pitch)
          pianoAbsentFrames.removeValue(forKey: pitch)
          emitNote(pitch: pitch, velocity: 0, down: false, timeSeconds: timeSeconds)
        }
      } else {
        pianoPresentFrames.removeValue(forKey: pitch)
        pianoAbsentFrames.removeValue(forKey: pitch)
      }
    }
  }

  private func releaseAnalysisState() {
    let timeSeconds = withStateLock { recording?.durationSeconds ?? 0 }
    for pitch in pianoActiveNotes.keys.sorted() {
      emitNote(pitch: pitch, velocity: 0, down: false, timeSeconds: timeSeconds)
    }
    if yinVoiced {
      emitPitch(
        voiced: false,
        frequencyHz: 0,
        midiPitch: 0,
        confidence: 0,
        velocity: 0,
        timeSeconds: timeSeconds
      )
    }
    prepareAnalysis(mode: withStateLock { selectedMode })
  }

  private func isCurrent(generation: UInt64, phase: Phase) -> Bool {
    withStateLock {
      !closed && self.generation == generation && self.phase == phase
    }
  }

  private func emitState() {
    let payload = state()
    DispatchQueue.main.async { [weak self] in
      guard let self, !self.withStateLock({ self.closed }) else { return }
      self.listener?.pitchRecognitionManager(self, didUpdateState: payload)
    }
  }

  private func emitNote(
    pitch: Int,
    velocity: Int,
    down: Bool,
    timeSeconds: Double
  ) {
    DispatchQueue.main.async { [weak self] in
      guard let self, !self.withStateLock({ self.closed }) else { return }
      self.listener?.pitchRecognitionManager(
        self,
        didRecognizeNote: pitch,
        velocity: velocity,
        down: down,
        timeSeconds: timeSeconds
      )
    }
  }

  private func emitPitch(
    voiced: Bool,
    frequencyHz: Double,
    midiPitch: Double,
    confidence: Double,
    velocity: Int,
    timeSeconds: Double
  ) {
    DispatchQueue.main.async { [weak self] in
      guard let self, !self.withStateLock({ self.closed }) else { return }
      self.listener?.pitchRecognitionManager(
        self,
        didRecognizePitch: voiced,
        frequencyHz: frequencyHz,
        midiPitch: midiPitch,
        confidence: confidence,
        velocity: velocity,
        timeSeconds: timeSeconds
      )
    }
  }

  private func emitSpectrum(_ magnitudes: [Float], timeSeconds: Double) {
    DispatchQueue.main.async { [weak self] in
      guard let self, !self.withStateLock({ self.closed }) else { return }
      self.listener?.pitchRecognitionManager(
        self,
        didAnalyzeSpectrum: magnitudes,
        timeSeconds: timeSeconds
      )
    }
  }

  private func stateLocked() -> [String: Any] {
    let duration = recording?.durationSeconds ?? 0
    return [
      "type": "pitchRecognitionState",
      "source": "microphone",
      "supported": true,
      "mode": selectedMode.rawValue,
      "phase": phase.rawValue,
      "modelReady": true,
      "recognizing": phase == .listening,
      "downloading": false,
      "busy": phase == .permission || phase == .starting,
      "progress": 1.0,
      "message": message,
      "recordingDuration": duration,
      "hasRecording": duration > 0,
    ]
  }

  private func withStateLock<Result>(_ body: () -> Result) -> Result {
    stateLock.lock()
    defer { stateLock.unlock() }
    return body()
  }

  deinit {
    close()
  }

  private enum Phase: String {
    case idle
    case permission
    case starting
    case listening
    case error
  }

  private struct PendingAnalysisFrame {
    let samples: [Float]
    let mode: PitchRecognitionMode
    let generation: UInt64
    let timeSeconds: Double
  }

  private enum CaptureError: LocalizedError {
    case invalidInputFormat

    var errorDescription: String? {
      "No compatible microphone input format was found."
    }
  }

  private static let recordingSampleRate = 16_000
  private static let inputTapBufferSize: AVAudioFrameCount = 1_024
  private static let yinInputTapBufferSize: AVAudioFrameCount = 512
  private static let analysisFrameSize = 2_048
  private static let pianoFrameSize = 8_192
  private static let analysisHopSize = 512
  private static let yinAnalysisHopSize = 256
  private static let yinUnvoicedFrameCount = 6
  private static let pianoNoteOnFrames = 2
  private static let pianoNoteOffFrames = 3
}

private final class LinearAudioResampler {
  private let step: Double
  private var source: [Float] = []
  private var position = 0.0

  init(sourceSampleRate: Double, targetSampleRate: Double) {
    precondition(sourceSampleRate > 0 && targetSampleRate > 0)
    step = sourceSampleRate / targetSampleRate
  }

  func process(_ samples: [Float]) -> [Float] {
    source.append(contentsOf: samples)
    guard source.count > 1 else { return [] }
    var output: [Float] = []
    output.reserveCapacity(Int(Double(samples.count) / step) + 1)
    while position + 1 < Double(source.count) {
      let lower = Int(position)
      let fraction = Float(position - Double(lower))
      output.append(source[lower] * (1 - fraction) + source[lower + 1] * fraction)
      position += step
    }
    let consumed = min(Int(position), source.count - 1)
    if consumed > 0 {
      source.removeFirst(consumed)
      position -= Double(consumed)
    }
    return output
  }
}

private final class AudioFrameAccumulator {
  struct Frame {
    let samples: [Float]
    let endSampleIndex: Int64
  }

  private let frameSize: Int
  private let hopSize: Int
  private var samples: [Float] = []
  private var bufferStartSampleIndex: Int64 = 0

  init(frameSize: Int, hopSize: Int) {
    precondition(frameSize > 0 && hopSize > 0 && hopSize <= frameSize)
    self.frameSize = frameSize
    self.hopSize = hopSize
  }

  func append(_ values: [Float]) -> [Frame] {
    samples.append(contentsOf: values)
    var frames: [Frame] = []
    while samples.count >= frameSize {
      frames.append(Frame(
        samples: Array(samples.prefix(frameSize)),
        endSampleIndex: bufferStartSampleIndex + Int64(frameSize)
      ))
      samples.removeFirst(hopSize)
      bufferStartSampleIndex += Int64(hopSize)
    }
    return frames
  }
}
