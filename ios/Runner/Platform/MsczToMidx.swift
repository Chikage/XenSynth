import Foundation
import zlib

enum MsczToMidx {
    private static let defaultDivision = 480
    private static let defaultBPM = 120
    private static let defaultVelocity = 80
    private static let defaultTimeSigN = 4
    private static let defaultTimeSigD = 4

    private static let midxMetaType = 0x7F
    private static let midxPayloadLength = 7
    private static let midxExperimentalManufacturerID = 0x7D
    private static let midxPitchedOffsetRecordType = 0x03
    private static let midxCentRange = 64.0
    private static let midxSafeCentRange = 63.0
    private static let midxOffsetSteps = 32768.0

    static func isMuseScoreInput(fileName: String, bytes: [UInt8]) -> Bool {
        let lowerName = fileName.lowercased()
        return lowerName.hasSuffix(".mscz") ||
            lowerName.hasSuffix(".mscx") ||
            isZip(bytes)
    }

    static func convert(_ inputBytes: [UInt8], fileName: String) throws -> [UInt8] {
        guard !inputBytes.isEmpty else {
            throw MsczToMidxError.invalidInput("Input bytes are empty")
        }
        let score = try parseScore(inputBytes, fileName: fileName)
        return try writeMidx(score)
    }

    private static func parseScore(_ inputBytes: [UInt8], fileName: String) throws -> ScoreData {
        try parseScoreXml(readMuseScoreXml(inputBytes, fileName: fileName), sourceName: fileName)
    }

    private static func parseScoreXml(_ root: Element, sourceName: String) throws -> ScoreData {
        guard let scoreElement = root.name == "Score" ? root : firstDirectChild(root, "Score") else {
            throw MsczToMidxError.invalidInput("No <Score> element found in \(sourceName)")
        }

        let score = ScoreData()
        score.division = intText(firstDirectChild(scoreElement, "Division"), fallback: defaultDivision)
        if score.division <= 0 {
            score.division = defaultDivision
        }

        parseParts(scoreElement, score: score)
        parseStaffBodies(scoreElement, score: score)

        if score.tempoEvents.isEmpty {
            score.tempoEvents.append(TempoEventData(tick: 0, bpm: Double(defaultBPM)))
        }
        if score.timeSigEvents.isEmpty {
            score.timeSigEvents.append(TimeSigEvent(tick: 0, numerator: defaultTimeSigN, denominator: defaultTimeSigD))
        }

        return score
    }

    private static func readMuseScoreXml(_ inputBytes: [UInt8], fileName: String) throws -> Element {
        let lowerName = fileName.lowercased()
        let xmlBytes: [UInt8]
        if lowerName.hasSuffix(".mscz") || isZip(inputBytes) {
            xmlBytes = try readMsczRootXml(inputBytes, sourceName: fileName)
        } else {
            xmlBytes = inputBytes
        }
        return try parseXml(xmlBytes)
    }

    private static func readMsczRootXml(_ inputBytes: [UInt8], sourceName: String) throws -> [UInt8] {
        let entries = try zipEntries(inputBytes)
        let entriesByName = zipEntriesByName(entries)
        let containerBytes = zipEntryData(named: "META-INF/container.xml", entries: entries, entriesByName: entriesByName)
        let rootPath = try containerBytes.flatMap { try firstRootfilePath($0) }

        if let rootPath,
           let scoreBytes = zipEntryData(named: rootPath, entries: entries, entriesByName: entriesByName) {
            return scoreBytes
        }

        if let scoreEntry = entries.first(where: { normalizedZipPath($0.name).lowercased().hasSuffix(".mscx") }) {
            return scoreEntry.data
        }

        throw MsczToMidxError.invalidInput("No .mscx score found inside \(sourceName)")
    }

    private static func parseXml(_ bytes: [UInt8]) throws -> Element {
        let normalized = try normalizeXmlBytes(bytes)
        try rejectUnsupportedXml(normalized)

        let parser = XMLParser(data: Data(normalized))
        let handler = ElementTreeHandler()
        parser.delegate = handler
        parser.shouldResolveExternalEntities = false

        guard parser.parse(), let root = handler.root else {
            if let parserError = parser.parserError {
                throw parserError
            }
            throw MsczToMidxError.invalidInput("Empty XML document")
        }
        return root
    }

    private static func firstRootfilePath(_ bytes: [UInt8]) throws -> String? {
        let root = try parseXml(bytes)
        return firstDirectDescendant(root, "rootfile")
            .map { $0.attribute("full-path") }
            .flatMap { $0.isEmpty ? nil : $0 }
    }

    private static func zipEntriesByName(_ entries: [ZipEntryData]) -> [String: [UInt8]] {
        var entriesByName: [String: [UInt8]] = [:]
        for entry in entries where entriesByName[entry.name] == nil {
            entriesByName[entry.name] = entry.data
        }
        return entriesByName
    }

    private static func zipEntryData(
        named path: String,
        entries: [ZipEntryData],
        entriesByName: [String: [UInt8]]
    ) -> [UInt8]? {
        let normalized = normalizedZipPath(path)
        if let data = entriesByName[normalized] {
            return data
        }

        let lowered = normalized.lowercased()
        return entries.first { normalizedZipPath($0.name).lowercased() == lowered }?.data
    }

    private static func normalizedZipPath(_ path: String) -> String {
        var normalized = path.trimmingCharacters(in: .whitespacesAndNewlines)
        if let decoded = normalized.removingPercentEncoding {
            normalized = decoded
        }
        normalized = normalized.replacingOccurrences(of: "\\", with: "/")
        while normalized.hasPrefix("/") {
            normalized.removeFirst()
        }
        while normalized.hasPrefix("./") {
            normalized.removeFirst(2)
        }
        return normalized
    }

    private static func normalizeXmlBytes(_ bytes: [UInt8]) throws -> [UInt8] {
        guard !bytes.isEmpty else {
            throw MsczToMidxError.invalidInput("Empty XML document")
        }

        var start = 0
        if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF {
            start = 3
        }
        while start < bytes.count {
            switch bytes[start] {
            case 0x20, 0x09, 0x0A, 0x0D:
                start += 1
            default:
                return start == 0 ? bytes : Array(bytes[start...])
            }
        }
        throw MsczToMidxError.invalidInput("Empty XML document")
    }

    private static func rejectUnsupportedXml(_ bytes: [UInt8]) throws {
        let prefix = String(decoding: bytes.prefix(4096), as: UTF8.self).lowercased()
        if prefix.contains("<!doctype") {
            throw MsczToMidxError.invalidInput("MuseScore XML with DOCTYPE is not supported")
        }
        if prefix.contains("<!entity") {
            throw MsczToMidxError.invalidInput("MuseScore XML with entity declarations is not supported")
        }
    }

    private static func parseParts(_ scoreElement: Element, score: ScoreData) {
        var partIndex = 0
        var nextChannel = 0

        for part in directChildren(scoreElement, "Part") {
            let program = parsePartProgram(part)
            let gateTimePercent = parsePartGateTime(part)
            let trackName = parsePartTrackName(part)
            let instrumentName = parsePartInstrumentName(part)
            let partStaffs = directChildren(part, "Staff")
            if partStaffs.isEmpty {
                continue
            }

            let channel = chooseChannel(nextChannel)
            nextChannel += 1
            var firstStaffInPart = true

            for staffElement in partStaffs {
                let staffId = intAttribute(staffElement, "id", fallback: score.staffInfos.count + 1)
                let info = StaffInfo()
                info.staffId = staffId
                info.partIndex = partIndex
                info.program = clamp(program, min: 0, max: 127)
                info.channel = channel
                info.gateTimePercent = gateTimePercent
                info.writeProgramChange = firstStaffInPart
                info.trackName = trackName
                info.instrumentName = instrumentName
                score.staffInfos[staffId] = info
                firstStaffInPart = false
            }

            partIndex += 1
        }
    }

    private static func parsePartProgram(_ part: Element) -> Int {
        guard let instrument = firstDirectChild(part, "Instrument") else {
            return 0
        }
        let channel = firstDirectChild(instrument, "Channel")
        var program = channel.flatMap { firstDirectChild($0, "program") }
        if program == nil {
            program = firstDirectDescendant(instrument, "program")
        }
        guard let program else {
            return 0
        }
        let value = program.attribute("value")
        if !value.isEmpty {
            return parseInt(value, fallback: 0)
        }
        return parseInt(text(program), fallback: 0)
    }

    private static func parsePartGateTime(_ part: Element) -> Int {
        guard let instrument = firstDirectChild(part, "Instrument") else {
            return 100
        }
        for articulation in directChildren(instrument, "Articulation") {
            if articulation.attribute("name").isEmpty {
                return clamp(intText(firstDirectChild(articulation, "gateTime"), fallback: 100), min: 1, max: 1000)
            }
        }
        return 100
    }

    private static func parsePartTrackName(_ part: Element) -> String {
        let partTrackName = text(firstDirectChild(part, "trackName"))
        if !partTrackName.isEmpty {
            return partTrackName
        }
        return text(firstDirectChild(firstDirectChild(part, "Instrument"), "trackName"))
    }

    private static func parsePartInstrumentName(_ part: Element) -> String {
        let instrument = firstDirectChild(part, "Instrument")
        let longName = text(firstDirectChild(instrument, "longName"))
        if !longName.isEmpty {
            return longName
        }
        let shortName = text(firstDirectChild(instrument, "shortName"))
        if !shortName.isEmpty {
            return shortName
        }
        return text(firstDirectChild(instrument, "instrumentId"))
    }

    private static func chooseChannel(_ index: Int) -> Int {
        let channel = index % 15
        return channel >= 9 ? channel + 1 : channel
    }

    private static func parseStaffBodies(_ scoreElement: Element, score: ScoreData) {
        var fallbackStaffId = 1
        let staffBodies = directChildren(scoreElement, "Staff")
        let measureStarts = buildMeasureStarts(staffBodies, division: score.division)
        parseScorePlaybackSpanners(scoreElement, score: score, measureStarts: measureStarts)

        for staffBody in staffBodies {
            let staffId = intAttribute(staffBody, "id", fallback: fallbackStaffId)
            fallbackStaffId += 1

            let info: StaffInfo
            if let existing = score.staffInfos[staffId] {
                info = existing
            } else {
                let fallback = StaffInfo()
                fallback.staffId = staffId
                fallback.partIndex = score.staffInfos.count
                fallback.program = 0
                fallback.channel = chooseChannel(score.staffInfos.count)
                fallback.gateTimePercent = 100
                fallback.writeProgramChange = true
                fallback.trackName = ""
                fallback.instrumentName = ""
                score.staffInfos[staffId] = fallback
                info = fallback
            }

            let track = score.trackForStaff(info)
            parseStaffBody(staffBody, score: score, track: track, measureStarts: measureStarts)
        }
    }

    private static func parseScorePlaybackSpanners(
        _ scoreElement: Element,
        score: ScoreData,
        measureStarts: [Int64]
    ) {
        for element in scoreElement.children {
            switch element.name {
            case "Ottava", "Trill":
                guard let startTick = explicitStartTick(element, division: score.division, measureStarts: measureStarts) else {
                    continue
                }
                parsePlaybackSpanElement(
                    element,
                    container: element,
                    score: score,
                    fallbackStaffId: staffIdFromElement(element, fallback: 1),
                    startTick: startTick,
                    measureIndex: measureIndex(forTick: startTick, measureStarts: measureStarts),
                    measureStarts: measureStarts
                )
            case "Spanner":
                guard let spannerElement = firstDirectChild(element, element.attribute("type")),
                      let startTick = explicitStartTick(spannerElement, division: score.division, measureStarts: measureStarts) ??
                        explicitStartTick(element, division: score.division, measureStarts: measureStarts)
                else {
                    continue
                }
                parsePlaybackSpanElement(
                    spannerElement,
                    container: element,
                    score: score,
                    fallbackStaffId: staffIdFromElement(spannerElement, fallback: 1),
                    startTick: startTick,
                    measureIndex: measureIndex(forTick: startTick, measureStarts: measureStarts),
                    measureStarts: measureStarts
                )
            default:
                break
            }
        }
    }

    private static func parsePlaybackSpanner(
        _ spanner: Element,
        score: ScoreData,
        fallbackStaffId: Int,
        startTick: Int64,
        measureIndex: Int,
        measureStarts: [Int64]
    ) {
        let type = spanner.attribute("type")
        guard !type.isEmpty,
              let element = firstDirectChild(spanner, type)
        else {
            return
        }

        parsePlaybackSpanElement(
            element,
            container: spanner,
            score: score,
            fallbackStaffId: fallbackStaffId,
            startTick: startTick,
            measureIndex: measureIndex,
            measureStarts: measureStarts
        )
    }

    private static func parsePlaybackSpanElement(
        _ element: Element,
        container: Element,
        score: ScoreData,
        fallbackStaffId: Int,
        startTick: Int64,
        measureIndex: Int,
        measureStarts: [Int64]
    ) {
        guard isPlayable(element), isPlayable(container) else {
            return
        }

        let staffId = staffIdFromElement(element, fallback: staffIdFromElement(container, fallback: fallbackStaffId))
        guard let endTick = playbackSpanEndTick(
            element,
            container: container,
            startTick: startTick,
            measureIndex: measureIndex,
            measureStarts: measureStarts,
            division: score.division
        ), endTick > startTick else {
            return
        }

        switch element.name {
        case "Ottava":
            let shift = ottavaPitchShift(element)
            if shift != 0 {
                score.addOttavaInterval(staffId: staffId, startTick: startTick, endTick: endTick, shift: shift)
            }
        case "Trill":
            score.addPlaybackSpan(
                staffId: staffId,
                startTick: startTick,
                endTick: endTick,
                symbolNames: [trillSymbolName(element)]
            )
        default:
            break
        }
    }

    private static func playbackSpanEndTick(
        _ element: Element,
        container: Element,
        startTick: Int64,
        measureIndex: Int,
        measureStarts: [Int64],
        division: Int
    ) -> Int64? {
        for candidate in [element, container] {
            if let ticks = firstDirectChild(candidate, "ticks")
                .flatMap({ scorePositionTicks(text($0), division: division) }) {
                return startTick + max(Int64(0), ticks)
            }
            if let ticks = firstDirectChild(candidate, "ticks_f")
                .flatMap({ scorePositionTicks(text($0), division: division) }) {
                return startTick + max(Int64(0), ticks)
            }
            if let tick2 = firstDirectChild(candidate, "tick2")
                .flatMap({ scorePositionTicks(text($0), division: division) }) {
                return max(Int64(0), tick2)
            }
        }

        if let next = firstDirectChild(container, "next"),
           let location = firstDirectChild(next, "location") {
            return relativeLocationTick(
                location,
                startTick: startTick,
                measureIndex: measureIndex,
                measureStarts: measureStarts,
                division: division
            )
        }

        return nil
    }

    private static func explicitStartTick(_ element: Element, division: Int, measureStarts: [Int64]) -> Int64? {
        if let tick = firstDirectChild(element, "tick")
            .flatMap({ scorePositionTicks(text($0), division: division) }) {
            return max(Int64(0), tick)
        }
        if let location = firstDirectChild(element, "location") {
            return absoluteLocationTick(location, measureStarts: measureStarts, division: division)
        }
        return nil
    }

    private static func staffIdFromElement(_ element: Element, fallback: Int) -> Int {
        let trackText = element.attribute("track").isEmpty ? text(firstDirectChild(element, "track")) : element.attribute("track")
        if !trackText.isEmpty {
            return max(1, parseInt(trackText, fallback: (fallback - 1) * 4) / 4 + 1)
        }

        let staffText = element.attribute("staff").isEmpty ? text(firstDirectChild(element, "staff")) : element.attribute("staff")
        if !staffText.isEmpty {
            return max(1, parseInt(staffText, fallback: fallback))
        }

        return fallback
    }

    private static func scorePositionTicks(_ value: String, division: Int) -> Int64? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return nil
        }
        if trimmed.contains("/") {
            return ratioTicks(trimmed, division: division)
        }
        return Int64(parseInt(trimmed, fallback: 0))
    }

    private static func absoluteLocationTick(_ location: Element, measureStarts: [Int64], division: Int) -> Int64 {
        let measure = clamp(intText(firstDirectChild(location, "measures"), fallback: 0), min: 0, max: max(0, measureStarts.count - 1))
        let fraction = ratioTicks(text(firstDirectChild(location, "fractions")), division: division)
        return (measure < measureStarts.count ? measureStarts[measure] : 0) + fraction
    }

    private static func relativeLocationTick(
        _ location: Element,
        startTick: Int64,
        measureIndex: Int,
        measureStarts: [Int64],
        division: Int
    ) -> Int64 {
        let currentMeasureStart = measureIndex < measureStarts.count ? measureStarts[measureIndex] : 0
        let currentMeasureOffset = startTick - currentMeasureStart
        let measureDelta = intText(firstDirectChild(location, "measures"), fallback: 0)
        let targetMeasureIndex = clamp(measureIndex + measureDelta, min: 0, max: max(0, measureStarts.count - 1))
        let targetMeasureStart = targetMeasureIndex < measureStarts.count ? measureStarts[targetMeasureIndex] : currentMeasureStart
        let fractionDelta = ratioTicks(text(firstDirectChild(location, "fractions")), division: division)
        return max(Int64(0), targetMeasureStart + currentMeasureOffset + fractionDelta)
    }

    private static func measureIndex(forTick tick: Int64, measureStarts: [Int64]) -> Int {
        guard !measureStarts.isEmpty else {
            return 0
        }
        var index = 0
        for candidate in measureStarts.indices where measureStarts[candidate] <= tick {
            index = candidate
        }
        return min(index, max(0, measureStarts.count - 1))
    }

    private static func ottavaPitchShift(_ element: Element) -> Int {
        let subtype = text(firstDirectChild(element, "subtype"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        switch subtype {
        case "0", "8va", "ottava_8va":
            return 12
        case "1", "8vb", "ottava_8vb":
            return -12
        case "2", "15ma", "ottava_15ma":
            return 24
        case "3", "15mb", "ottava_15mb":
            return -24
        case "4", "22ma", "ottava_22ma":
            return 36
        case "5", "22mb", "ottava_22mb":
            return -36
        default:
            if subtype.contains("22m") {
                return subtype.contains("b") ? -36 : 36
            }
            if subtype.contains("15m") {
                return subtype.contains("b") ? -24 : 24
            }
            if subtype.contains("8v") {
                return subtype.contains("b") ? -12 : 12
            }
            return 12
        }
    }

    private static func trillSymbolName(_ element: Element) -> String {
        let subtype = text(firstDirectChild(element, "subtype")).lowercased()
        if subtype.contains("upprall") {
            return "ornamentUpPrall"
        }
        if subtype.contains("downprall") {
            return "ornamentPrallDown"
        }
        if subtype.contains("prallprall") {
            return "ornamentLinePrall"
        }
        return "ornamentTrill"
    }

    private static func buildMeasureStarts(_ staffBodies: [Element], division: Int) -> [Int64] {
        let measuresByStaff = staffBodies.map { directChildren($0, "Measure") }
        let maxMeasures = measuresByStaff.map(\.count).max() ?? 0
        var starts: [Int64] = []
        var tick: Int64 = 0
        var currentSigN = defaultTimeSigN
        var currentSigD = defaultTimeSigD

        for measureIndex in 0..<maxMeasures {
            starts.append(tick)
            if let signature = firstTimeSigAtMeasure(measuresByStaff, measureIndex: measureIndex) {
                currentSigN = signature.numerator
                currentSigD = signature.denominator
            }
            tick += max(Int64(1), measureLengthAtIndex(
                measuresByStaff,
                measureIndex: measureIndex,
                division: division,
                sigN: currentSigN,
                sigD: currentSigD
            ))
        }
        starts.append(tick)
        return starts
    }

    private static func firstTimeSigAtMeasure(_ measuresByStaff: [[Element]], measureIndex: Int) -> TimeSigEvent? {
        for measures in measuresByStaff where measureIndex < measures.count {
            if let timeSig = firstTimeSigInMeasure(measures[measureIndex]) {
                let n = intText(firstDirectChild(timeSig, "sigN"), fallback: defaultTimeSigN)
                let d = intText(firstDirectChild(timeSig, "sigD"), fallback: defaultTimeSigD)
                if n > 0 && d > 0 {
                    return TimeSigEvent(tick: 0, numerator: n, denominator: d)
                }
            }
        }
        return nil
    }

    private static func firstTimeSigInMeasure(_ measure: Element) -> Element? {
        for voice in directChildren(measure, "voice") {
            if let timeSig = firstDirectChild(voice, "TimeSig") {
                return timeSig
            }
        }
        return nil
    }

    private static func measureLengthAtIndex(
        _ measuresByStaff: [[Element]],
        measureIndex: Int,
        division: Int,
        sigN: Int,
        sigD: Int
    ) -> Int64 {
        let signatureTicks = ticksForTimeSignature(division: division, sigN: sigN, sigD: sigD)
        var irregular = false
        var irregularTicks: Int64 = 0

        for measures in measuresByStaff where measureIndex < measures.count {
            let measure = measures[measureIndex]
            if hasAttributeValue(measure, "len") {
                let explicitTicks = ratioTicks(measure.attribute("len"), division: division)
                if explicitTicks > 0 {
                    return explicitTicks
                }
            }
            if intText(firstDirectChild(measure, "irregular"), fallback: 0) != 0 {
                irregular = true
                irregularTicks = max(irregularTicks, measureContentTicks(measure, division: division, measureTicks: signatureTicks))
            }
        }

        if irregular && irregularTicks > 0 {
            return irregularTicks
        }
        return signatureTicks
    }

    private static func measureContentTicks(_ measure: Element, division: Int, measureTicks: Int64) -> Int64 {
        var maxTicks: Int64 = 0
        for voice in directChildren(measure, "voice") {
            var tick: Int64 = 0
            var tupletRatio = 1.0
            var tupletRemaining = 0

            for element in voice.children {
                switch element.name {
                case "Tuplet":
                    let normal = intText(firstDirectChild(element, "normalNotes"), fallback: 0)
                    let actual = intText(firstDirectChild(element, "actualNotes"), fallback: 0)
                    if normal > 0 && actual > 0 {
                        tupletRatio = Double(normal) / Double(actual)
                        tupletRemaining = actual
                    }
                case "Rest", "Chord":
                    tick += max(Int64(0), durationTicks(element, division: division, tupletRatio: tupletRatio, measureTicks: measureTicks))
                    if tupletRemaining > 0 {
                        tupletRemaining -= 1
                        if tupletRemaining <= 0 {
                            tupletRatio = 1.0
                        }
                    }
                default:
                    break
                }
            }

            maxTicks = max(maxTicks, tick)
        }
        return maxTicks
    }

    private static func parseStaffBody(
        _ staffBody: Element,
        score: ScoreData,
        track: TrackData,
        measureStarts: [Int64]
    ) {
        let measures = directChildren(staffBody, "Measure")
        var voiceStates: [Int: VoiceState] = [:]

        for measureIndex in measures.indices {
            let measure = measures[measureIndex]
            let measureStart = measureIndex < measureStarts.count ? measureStarts[measureIndex] : 0
            let nextMeasureStart = measureIndex + 1 < measureStarts.count
                ? measureStarts[measureIndex + 1]
                : measureStart + Int64(score.division)
            let measureTicks = max(Int64(1), nextMeasureStart - measureStart)
            let voices = directChildren(measure, "voice")
            if voices.isEmpty {
                continue
            }

            for voiceIndex in voices.indices {
                let state: VoiceState
                if let existing = voiceStates[voiceIndex] {
                    state = existing
                } else {
                    let created = VoiceState()
                    created.velocity = defaultVelocity
                    voiceStates[voiceIndex] = created
                    state = created
                }
                state.tick = measureStart
                state.measureTicks = measureTicks
                state.tupletRatio = 1.0
                state.tupletRemaining = 0
                parseVoice(
                    voices[voiceIndex],
                    score: score,
                    track: track,
                    state: state,
                    voiceIndex: voiceIndex,
                    measureIndex: measureIndex,
                    measureStarts: measureStarts
                )
            }
        }

        for state in voiceStates.values {
            for playback in state.activeTies.values {
                emitPlayback(track: track, score: score, playback: playback)
            }
            state.activeTies.removeAll()
        }
    }

    private static func parseVoice(
        _ voice: Element,
        score: ScoreData,
        track: TrackData,
        state: VoiceState,
        voiceIndex: Int,
        measureIndex: Int,
        measureStarts: [Int64]
    ) {
        for element in voice.children {
            switch element.name {
            case "Tempo":
                let tempo = doubleText(firstDirectChild(element, "tempo"), fallback: -1.0)
                if tempo > 0.0 {
                    score.tempoEvents.append(TempoEventData(tick: state.tick, bpm: tempo * 60.0))
                }
            case "TimeSig":
                let n = intText(firstDirectChild(element, "sigN"), fallback: defaultTimeSigN)
                let d = intText(firstDirectChild(element, "sigD"), fallback: defaultTimeSigD)
                if n > 0 && d > 0 {
                    score.timeSigEvents.append(TimeSigEvent(tick: state.tick, numerator: n, denominator: d))
                }
            case "Dynamic":
                state.velocity = dynamicVelocity(element, fallback: state.velocity)
            case "Fermata", "Breath":
                state.pendingPlaybackSymbols.append(element)
            case "Spanner":
                parsePlaybackSpanner(
                    element,
                    score: score,
                    fallbackStaffId: track.staffId,
                    startTick: state.tick,
                    measureIndex: measureIndex,
                    measureStarts: measureStarts
                )
            case "Ottava", "Trill":
                parsePlaybackSpanElement(
                    element,
                    container: element,
                    score: score,
                    fallbackStaffId: track.staffId,
                    startTick: state.tick,
                    measureIndex: measureIndex,
                    measureStarts: measureStarts
                )
            case "location":
                state.tick += locationTicks(element, division: score.division)
            case "Tuplet":
                let normal = intText(firstDirectChild(element, "normalNotes"), fallback: 0)
                let actual = intText(firstDirectChild(element, "actualNotes"), fallback: 0)
                if normal > 0 && actual > 0 {
                    state.tupletRatio = Double(normal) / Double(actual)
                    state.tupletRemaining = actual
                }
            case "Rest":
                let duration = durationTicks(element, division: score.division, tupletRatio: state.tupletRatio, measureTicks: state.measureTicks)
                state.tick += max(Int64(0), duration)
                consumeTupletSlot(state)
            case "Chord":
                let nominalDuration = durationTicks(
                    element,
                    division: score.division,
                    tupletRatio: state.tupletRatio,
                    measureTicks: state.measureTicks
                )
                if let grace = graceKind(element) {
                    if grace.isAfter {
                        appendChordNotes(
                            element,
                            score: score,
                            track: track,
                            state: state,
                            voiceIndex: voiceIndex,
                            startTick: state.tick,
                            nominalDuration: graceDurationTicks(element, division: score.division, measureTicks: state.measureTicks)
                        )
                    } else {
                        state.pendingGraceChords.append(element)
                    }
                    continue
                }

                let graceDuration = flushPendingGraceChords(
                    score: score,
                    track: track,
                    state: state,
                    voiceIndex: voiceIndex,
                    principalDuration: nominalDuration
                )
                appendChordNotes(
                    element,
                    score: score,
                    track: track,
                    state: state,
                    voiceIndex: voiceIndex,
                    startTick: state.tick + graceDuration,
                    nominalDuration: max(Int64(1), nominalDuration - graceDuration),
                    extraPlaybackSymbols: state.consumePendingPlaybackSymbols()
                )
                state.tick += max(Int64(0), nominalDuration)
                consumeTupletSlot(state)
            default:
                break
            }
        }
    }

    private static func appendChordNotes(
        _ chord: Element,
        score: ScoreData,
        track: TrackData,
        state: VoiceState,
        voiceIndex: Int,
        startTick chordStartTick: Int64,
        nominalDuration: Int64,
        extraPlaybackSymbols: [Element] = []
    ) {
        guard isPlayable(chord) else {
            return
        }

        let playback = chordPlayback(
            chord,
            score: score,
            staffId: track.staffId,
            startTick: chordStartTick,
            nominalDuration: nominalDuration,
            division: score.division,
            extraPlaybackSymbols: extraPlaybackSymbols
        )
        let notes = orderedNotes(directChildren(chord, "Note"), arpeggio: playback.arpeggio)

        for noteIndex in notes.indices {
            let note = notes[noteIndex]
            guard isPlayable(note) else {
                continue
            }
            let xmlPitch = intText(firstDirectChild(note, "pitch"), fallback: -1)
            if xmlPitch < 0 {
                continue
            }
            let tuning = doubleText(firstDirectChild(note, "tuning"), fallback: 0.0)
            let velocity = performanceVelocity(
                noteVelocity(note, inheritedVelocity: state.velocity),
                velocityDelta: playback.velocityDelta
            )
            let tiePrev = hasTieEndpoint(note, endpoint: "prev")
            let tieNext = hasTieEndpoint(note, endpoint: "next")
            let timings = eventTimings(
                note,
                nominalDuration: nominalDuration,
                fallback: playback.timings
            )
            let arpeggioDelay = arpeggioDelayTicks(forNoteAt: noteIndex, playback: playback)

            for timing in timings {
                let eventStartTick = chordStartTick + timing.offsetTicks + arpeggioDelay
                let ottavaShift = score.ottavaShift(staffId: track.staffId, tick: eventStartTick)
                let eventPitch = Double(xmlPitch + ottavaShift) + timing.pitchDelta
                let normalized = normalizeMidxPitchCents(pitch: eventPitch, cents: tuning)
                let nativePitch = clamp(Int(eventPitch.rounded()), min: 0, max: 127)
                let lengthTicks = performedLengthTicks(timing.lengthTicks, playback: playback)
                let endTick = max(eventStartTick + 1, eventStartTick + lengthTicks)
                let tieKey = TieKey(
                    staffId: track.staffId,
                    voiceIndex: voiceIndex,
                    pitch: xmlPitch,
                    tuningKey: Int((tuning * 1000.0).rounded())
                )

                if tiePrev {
                    if let active = state.activeTies[tieKey] {
                        active.endTick = max(active.endTick, endTick)
                        if !tieNext {
                            state.activeTies.removeValue(forKey: tieKey)
                            emitPlayback(track: track, score: score, playback: active)
                        }
                        continue
                    }
                    if !tieNext {
                        continue
                    }
                }

                let playback = NotePlayback()
                playback.startTick = eventStartTick
                playback.endTick = endTick
                playback.pitch = normalized.pitch
                playback.nativePitch = nativePitch
                playback.cents = normalized.cents
                playback.velocity = velocity

                if tieNext {
                    state.activeTies[tieKey] = playback
                } else {
                    emitPlayback(track: track, score: score, playback: playback)
                }
            }
        }
    }

    private static func flushPendingGraceChords(
        score: ScoreData,
        track: TrackData,
        state: VoiceState,
        voiceIndex: Int,
        principalDuration: Int64
    ) -> Int64 {
        let graceChords = state.pendingGraceChords
        state.pendingGraceChords.removeAll()
        guard !graceChords.isEmpty, principalDuration > 1 else {
            return 0
        }

        let naturalDurations = graceChords.map {
            graceDurationTicks($0, division: score.division, measureTicks: state.measureTicks)
        }
        let naturalTotal = max(Int64(1), naturalDurations.reduce(Int64(0), +))
        let containsAppoggiatura = graceChords.contains {
            graceKind($0)?.borrowsPrincipalTime == true
        }
        let available = max(Int64(1), min(
            naturalTotal,
            containsAppoggiatura && graceChords.count == 1 ? principalDuration / 2 : principalDuration / 3
        ))
        let scale = Double(available) / Double(naturalTotal)
        var cursor = state.tick

        for index in graceChords.indices {
            let isLast = index == graceChords.count - 1
            let duration = isLast
                ? max(Int64(1), state.tick + available - cursor)
                : max(Int64(1), Int64((Double(naturalDurations[index]) * scale).rounded()))
            appendChordNotes(
                graceChords[index],
                score: score,
                track: track,
                state: state,
                voiceIndex: voiceIndex,
                startTick: cursor,
                nominalDuration: duration
            )
            cursor += duration
        }

        return max(Int64(0), cursor - state.tick)
    }

    private static func emitPlayback(track: TrackData, score: ScoreData, playback: NotePlayback) {
        playback.pitch = clamp(playback.pitch, min: 0, max: 127)
        playback.nativePitch = clamp(playback.nativePitch, min: 0, max: 127)
        playback.velocity = clamp(playback.velocity, min: 1, max: 127)
        let gatedEndTick = gatedEndTick(
            startTick: playback.startTick,
            endTick: playback.endTick,
            gateTimePercent: track.gateTimePercent
        )
        track.events.append(.noteOn(
            tick: playback.startTick,
            pitch: playback.pitch,
            nativePitch: playback.nativePitch,
            velocity: playback.velocity,
            cents: playback.cents
        ))
        track.events.append(.noteOff(tick: gatedEndTick, nativePitch: playback.nativePitch))
        score.noteCount += 1
        if encodeCentOffset(playback.cents) != 0 {
            score.microtonalCount += 1
        }
    }

    private static func gatedEndTick(startTick: Int64, endTick: Int64, gateTimePercent: Int) -> Int64 {
        let duration = max(Int64(1), endTick - startTick)
        let gatedDuration = Int64(floor(Double(duration) * Double(clamp(gateTimePercent, min: 1, max: 1000)) / 100.0)) - 1
        return startTick + max(Int64(1), gatedDuration)
    }

    private static func chordPlayback(
        _ chord: Element,
        score: ScoreData,
        staffId: Int,
        startTick: Int64,
        nominalDuration: Int64,
        division: Int,
        extraPlaybackSymbols: [Element]
    ) -> ChordPlayback {
        let names = playbackSymbolNames(chord, extraPlaybackSymbols: extraPlaybackSymbols) +
            score.playbackSymbolNames(staffId: staffId, tick: startTick)
        let hasExplicitEvents = firstDirectChild(chord, "Events") != nil
        var timings = eventTimings(
            chord,
            nominalDuration: nominalDuration,
            fallback: [EventTiming(offsetTicks: 0, lengthTicks: max(Int64(1), nominalDuration), pitchDelta: 0.0)]
        )

        if !hasExplicitEvents {
            if let tremoloStep = tremoloStepTicks(chord, division: division, nominalDuration: nominalDuration) {
                timings = repeatedTimings(nominalDuration: nominalDuration, stepTicks: tremoloStep)
            } else if let ornament = ornamentTimings(symbolNames: names, nominalDuration: nominalDuration, division: division) {
                timings = ornament
            }
        }

        let modifiers = articulationModifiers(symbolNames: names)
        return ChordPlayback(
            timings: timings,
            velocityDelta: modifiers.velocityDelta,
            gatePercent: modifiers.gatePercent,
            durationMultiplier: modifiers.durationMultiplier,
            arpeggio: arpeggioPlayback(chord, nominalDuration: nominalDuration, division: division)
        )
    }

    private static func eventTimings(
        _ element: Element,
        nominalDuration: Int64,
        fallback: [EventTiming]
    ) -> [EventTiming] {
        guard let events = firstDirectChild(element, "Events") else {
            return fallback
        }

        var out: [EventTiming] = []
        for event in directChildren(events, "Event") {
            let ontime = doubleText(firstDirectChild(event, "ontime"), fallback: 0.0)
            let length = doubleText(firstDirectChild(event, "len"), fallback: 1000.0)
            let pitch = doubleText(firstDirectChild(event, "pitch"), fallback: 0.0)
            out.append(EventTiming(
                offsetTicks: Int64((Double(nominalDuration) * ontime / 1000.0).rounded()),
                lengthTicks: max(Int64(1), Int64((Double(nominalDuration) * length / 1000.0).rounded())),
                pitchDelta: pitch
            ))
        }

        if out.isEmpty {
            out.append(contentsOf: fallback)
        }
        return out
    }

    private static func repeatedTimings(nominalDuration: Int64, stepTicks: Int64) -> [EventTiming] {
        let duration = max(Int64(1), nominalDuration)
        let count = max(1, Int((Double(duration) / Double(max(Int64(1), stepTicks))).rounded()))
        let actualStep = max(Int64(1), duration / Int64(count))
        return (0..<count).map { index in
            let offset = Int64(index) * actualStep
            let length = index == count - 1 ? duration - offset : actualStep
            return EventTiming(offsetTicks: offset, lengthTicks: max(Int64(1), length), pitchDelta: 0.0)
        }
    }

    private static func ornamentTimings(symbolNames: [String], nominalDuration: Int64, division: Int) -> [EventTiming]? {
        let names = normalizedNames(symbolNames)
        if names.isEmpty {
            return nil
        }

        if names.contains(where: { $0.contains("trill") || $0.contains("tremblement") || $0.contains("shake") }) {
            let step = max(Int64(1), min(Int64(division) / 8, max(Int64(1), nominalDuration / 4)))
            let repeated = repeatedTimings(nominalDuration: nominalDuration, stepTicks: step)
            return repeated.enumerated().map { index, timing in
                EventTiming(offsetTicks: timing.offsetTicks, lengthTicks: timing.lengthTicks, pitchDelta: index % 2 == 0 ? 0.0 : 1.0)
            }
        }

        let pattern: [Double]?
        if names.contains(where: { $0.contains("invertedturn") || $0.contains("turnslash") || $0.contains("turnups") }) {
            pattern = [-1.0, 0.0, 1.0, 0.0]
        } else if names.contains(where: { $0.contains("turn") || $0.contains("haydn") }) {
            pattern = [1.0, 0.0, -1.0, 0.0]
        } else if names.contains(where: { $0.contains("uppermordent") || $0.contains("shorttrill") || $0.contains("upmordent") }) {
            pattern = [0.0, 1.0, 0.0]
        } else if names.contains(where: { $0.contains("mordent") || $0.contains("pince") }) {
            pattern = [0.0, -1.0, 0.0]
        } else if names.contains(where: { $0.contains("prall") }) {
            pattern = [1.0, 0.0, 1.0, 0.0]
        } else {
            pattern = nil
        }

        guard let pattern, !pattern.isEmpty else {
            return nil
        }

        let duration = max(Int64(1), nominalDuration)
        let step = max(Int64(1), duration / Int64(pattern.count))
        return pattern.indices.map { index in
            let offset = Int64(index) * step
            let length = index == pattern.count - 1 ? duration - offset : step
            return EventTiming(offsetTicks: offset, lengthTicks: max(Int64(1), length), pitchDelta: pattern[index])
        }
    }

    private static func noteVelocity(_ note: Element, inheritedVelocity: Int) -> Int {
        let velocityElement = firstDirectChild(note, "velocity")
        let veloTypeElement = firstDirectChild(note, "veloType")
        let veloOffsetElement = firstDirectChild(note, "veloOffset")

        var velocity = inheritedVelocity
        if let velocityElement {
            velocity = intText(velocityElement, fallback: velocity)
        } else if let veloOffsetElement {
            velocity += intText(veloOffsetElement, fallback: 0)
        }

        let veloType = text(veloTypeElement)
        if veloType.lowercased() == "offset", let velocityElement {
            velocity = inheritedVelocity + intText(velocityElement, fallback: 0)
        }
        return clamp(velocity, min: 1, max: 127)
    }

    private static func performanceVelocity(_ velocity: Int, velocityDelta: Int) -> Int {
        clamp(velocity + velocityDelta, min: 1, max: 127)
    }

    private static func dynamicVelocity(_ dynamic: Element, fallback: Int) -> Int {
        if let velocity = firstDirectChild(dynamic, "velocity") {
            return clamp(intText(velocity, fallback: fallback), min: 1, max: 127)
        }

        let subtype = text(firstDirectChild(dynamic, "subtype"))
        if let mapped = dynamicVelocity(subtype) {
            return mapped
        }

        return clamp(fallback, min: 1, max: 127)
    }

    private static func dynamicVelocity(_ subtype: String) -> Int? {
        let key = subtype
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "dynamic", with: "")
        switch key {
        case "n":
            return 49
        case "pppppp":
            return 1
        case "ppppp":
            return 5
        case "pppp":
            return 10
        case "ppp":
            return 16
        case "pp":
            return 33
        case "p":
            return 49
        case "mp":
            return 64
        case "mf", "z":
            return 80
        case "m", "f":
            return 96
        case "ff", "sf", "sfz", "sfp", "sfpp", "rfz", "rf", "fz", "r", "s":
            return 112
        case "fff", "sff", "sffz":
            return 126
        case "ffff", "fffff", "ffffff", "sfff", "sfffz":
            return 127
        case "fp", "pf":
            return 96
        default:
            return nil
        }
    }

    private static func isPlayable(_ element: Element) -> Bool {
        guard let play = firstDirectChild(element, "play") else {
            return true
        }
        let value = text(play).lowercased()
        return !(value == "0" || value == "false" || value == "no")
    }

    private static func playbackSymbolNames(_ chord: Element, extraPlaybackSymbols: [Element]) -> [String] {
        var names: [String] = []
        for child in chord.children + extraPlaybackSymbols {
            switch child.name {
            case "Articulation", "Ornament", "Fermata", "Breath":
                names.append(child.name)
                for key in ["subtype", "sym", "symId", "symbol"] {
                    let value = text(firstDirectChild(child, key))
                    if !value.isEmpty {
                        names.append(value)
                    }
                    let attribute = child.attribute(key)
                    if !attribute.isEmpty {
                        names.append(attribute)
                    }
                }
            default:
                break
            }
        }
        return names
    }

    private static func normalizedNames(_ names: [String]) -> [String] {
        names.map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
                .replacingOccurrences(of: "_", with: "")
                .replacingOccurrences(of: "-", with: "")
        }.filter { !$0.isEmpty }
    }

    private static func articulationModifiers(symbolNames: [String]) -> PlaybackModifiers {
        let names = normalizedNames(symbolNames)
        var modifiers = PlaybackModifiers()

        for name in names {
            if name.contains("staccatissimo") {
                modifiers.gatePercent = min(modifiers.gatePercent, 25)
            } else if name.contains("staccato") {
                modifiers.gatePercent = min(modifiers.gatePercent, 50)
            }

            if name.contains("tenuto") || name.contains("laissez") {
                modifiers.gatePercent = max(modifiers.gatePercent, 100)
            }

            if name.contains("marcato") {
                modifiers.velocityDelta += 24
            } else if name.contains("softaccent") {
                modifiers.velocityDelta += 8
            } else if name.contains("accent") {
                modifiers.velocityDelta += 16
            }

            if name.contains("sforz") || name.contains("sfz") || name.contains("rinforz") {
                modifiers.velocityDelta += 20
            }

            if name.contains("fermata") {
                if name.contains("short") {
                    modifiers.durationMultiplier = max(modifiers.durationMultiplier, 1.25)
                } else if name.contains("long") || name.contains("verylong") {
                    modifiers.durationMultiplier = max(modifiers.durationMultiplier, 2.0)
                } else {
                    modifiers.durationMultiplier = max(modifiers.durationMultiplier, 1.5)
                }
            }
        }

        return modifiers
    }

    private static func tremoloStepTicks(_ chord: Element, division: Int, nominalDuration: Int64) -> Int64? {
        guard let tremolo = firstDirectChild(chord, "Tremolo"), isPlayable(tremolo) else {
            return nil
        }

        let subtype = text(firstDirectChild(tremolo, "subtype")).lowercased()
        if subtype.hasPrefix("c") {
            return nil
        }
        if subtype.contains("buzz") {
            return max(Int64(1), min(Int64(division) / 16, nominalDuration))
        }
        if subtype.contains("64") {
            return max(Int64(1), Int64(division) / 16)
        }
        if subtype.contains("32") {
            return max(Int64(1), Int64(division) / 8)
        }
        if subtype.contains("16") {
            return max(Int64(1), Int64(division) / 4)
        }
        return max(Int64(1), Int64(division) / 2)
    }

    private static func arpeggioPlayback(_ chord: Element, nominalDuration: Int64, division: Int) -> ArpeggioPlayback? {
        guard let arpeggio = firstDirectChild(chord, "Arpeggio"), isPlayable(arpeggio) else {
            return nil
        }
        let subtype = text(firstDirectChild(arpeggio, "subtype")).lowercased()
        let direction: ArpeggioDirection = subtype.contains("down") ? .down : .up
        let stretch = max(0.1, doubleText(firstDirectChild(arpeggio, "timeStretch"), fallback: 1.0))
        let baseStep = min(max(Int64(1), Int64(division) / 32), max(Int64(1), nominalDuration / 8))
        return ArpeggioPlayback(direction: direction, stepTicks: max(Int64(1), Int64((Double(baseStep) * stretch).rounded())))
    }

    private static func orderedNotes(_ notes: [Element], arpeggio: ArpeggioPlayback?) -> [Element] {
        guard let arpeggio else {
            return notes
        }
        return notes.sorted {
            let left = intText(firstDirectChild($0, "pitch"), fallback: 0)
            let right = intText(firstDirectChild($1, "pitch"), fallback: 0)
            switch arpeggio.direction {
            case .up:
                return left < right
            case .down:
                return left > right
            }
        }
    }

    private static func arpeggioDelayTicks(forNoteAt index: Int, playback: ChordPlayback) -> Int64 {
        guard let arpeggio = playback.arpeggio else {
            return 0
        }
        return Int64(index) * arpeggio.stepTicks
    }

    private static func performedLengthTicks(_ lengthTicks: Int64, playback: ChordPlayback) -> Int64 {
        let multiplied = Double(max(Int64(1), lengthTicks)) * playback.durationMultiplier
        let gated = multiplied * Double(playback.gatePercent) / 100.0
        return max(Int64(1), Int64(gated.rounded()))
    }

    private static func graceKind(_ chord: Element) -> GraceKind? {
        if firstDirectChild(chord, "acciaccatura") != nil {
            return GraceKind(isAfter: false, borrowsPrincipalTime: false, durationType: "32nd")
        }
        if firstDirectChild(chord, "appoggiatura") != nil {
            return GraceKind(isAfter: false, borrowsPrincipalTime: true, durationType: "eighth")
        }
        if firstDirectChild(chord, "grace4") != nil {
            return GraceKind(isAfter: false, borrowsPrincipalTime: true, durationType: "quarter")
        }
        if firstDirectChild(chord, "grace16") != nil {
            return GraceKind(isAfter: false, borrowsPrincipalTime: false, durationType: "16th")
        }
        if firstDirectChild(chord, "grace32") != nil {
            return GraceKind(isAfter: false, borrowsPrincipalTime: false, durationType: "32nd")
        }
        if firstDirectChild(chord, "grace8after") != nil {
            return GraceKind(isAfter: true, borrowsPrincipalTime: false, durationType: "eighth")
        }
        if firstDirectChild(chord, "grace16after") != nil {
            return GraceKind(isAfter: true, borrowsPrincipalTime: false, durationType: "16th")
        }
        if firstDirectChild(chord, "grace32after") != nil {
            return GraceKind(isAfter: true, borrowsPrincipalTime: false, durationType: "32nd")
        }
        return nil
    }

    private static func graceDurationTicks(_ chord: Element, division: Int, measureTicks: Int64) -> Int64 {
        if let explicitDuration = firstDirectChild(chord, "duration") {
            let ticks = ratioTicks(text(explicitDuration), division: division)
            if ticks > 0 {
                return max(Int64(1), ticks)
            }
        }
        if let durationType = firstDirectChild(chord, "durationType") {
            return max(Int64(1), durationTypeTicks(text(durationType), division: division, measureTicks: measureTicks))
        }
        if let kind = graceKind(chord) {
            return max(Int64(1), durationTypeTicks(kind.durationType, division: division, measureTicks: measureTicks))
        }
        return max(Int64(1), Int64(division) / 8)
    }

    private static func hasTieEndpoint(_ note: Element, endpoint: String) -> Bool {
        directChildren(note, "Spanner").contains {
            $0.attribute("type") == "Tie" && firstDirectChild($0, endpoint) != nil
        }
    }

    private static func locationTicks(_ location: Element, division: Int) -> Int64 {
        guard let fractions = firstDirectChild(location, "fractions") else {
            return 0
        }
        return ratioTicks(text(fractions), division: division)
    }

    private static func ticksForTimeSignature(division: Int, sigN: Int, sigD: Int) -> Int64 {
        let numerator = sigN > 0 ? sigN : defaultTimeSigN
        let denominator = sigD > 0 ? sigD : defaultTimeSigD
        return Int64((Double(division) * 4.0 * Double(numerator) / Double(denominator)).rounded())
    }

    private static func durationTicks(_ element: Element, division: Int, tupletRatio: Double, measureTicks: Int64) -> Int64 {
        if let explicitDuration = firstDirectChild(element, "duration") {
            let ticks = ratioTicks(text(explicitDuration), division: division)
            if ticks > 0 {
                return max(Int64(1), Int64((Double(ticks) * tupletRatio).rounded()))
            }
        }

        let durationType = text(firstDirectChild(element, "durationType"))
        let base = durationTypeTicks(durationType, division: division, measureTicks: measureTicks)
        let dots = intText(firstDirectChild(element, "dots"), fallback: 0)
        var multiplier = 1.0
        var add = 0.5
        for _ in 0..<max(0, dots) {
            multiplier += add
            add *= 0.5
        }
        return max(Int64(1), Int64((Double(base) * multiplier * tupletRatio).rounded()))
    }

    private static func durationTypeTicks(_ durationType: String, division: Int, measureTicks: Int64) -> Int64 {
        let type = durationType.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch type {
        case "measure":
            return measureTicks > 0 ? measureTicks : Int64(division * 4)
        case "longa":
            return Int64(division * 16)
        case "breve":
            return Int64(division * 8)
        case "whole":
            return Int64(division * 4)
        case "half":
            return Int64(division * 2)
        case "quarter", "":
            return Int64(division)
        case "eighth":
            return max(Int64(1), Int64(division) / 2)
        case "16th":
            return max(Int64(1), Int64(division) / 4)
        case "32nd":
            return max(Int64(1), Int64(division) / 8)
        case "64th":
            return max(Int64(1), Int64(division) / 16)
        case "128th":
            return max(Int64(1), Int64(division) / 32)
        case "256th":
            return max(Int64(1), Int64(division) / 64)
        default:
            if type.hasSuffix("th") {
                let denominator = parseInt(String(type.dropLast(2)), fallback: 4)
                if denominator > 0 {
                    return max(Int64(1), Int64((Double(division) * 4.0 / Double(denominator)).rounded()))
                }
            }
            return Int64(division)
        }
    }

    private static func ratioTicks(_ value: String, division: Int) -> Int64 {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return 0
        }
        if let slash = trimmed.firstIndex(of: "/") {
            let numerator = parseDouble(String(trimmed[..<slash]), fallback: 0.0)
            let denominator = parseDouble(String(trimmed[trimmed.index(after: slash)...]), fallback: 1.0)
            if denominator != 0.0 {
                return Int64((Double(division) * 4.0 * numerator / denominator).rounded())
            }
        }
        return Int64((parseDouble(trimmed, fallback: 0.0) * Double(division)).rounded())
    }

    private static func consumeTupletSlot(_ state: VoiceState) {
        if state.tupletRemaining > 0 {
            state.tupletRemaining -= 1
            if state.tupletRemaining <= 0 {
                state.tupletRatio = 1.0
            }
        }
    }

    private static func normalizeMidxPitchCents(pitch: Double, cents: Double) -> NormalizedPitch {
        let pitch = pitch.isFinite ? pitch : 0.0
        let cents = cents.isFinite ? cents : 0.0
        var targetPitch = Int(pitch.rounded())
        var residualCents = cents + (pitch - Double(targetPitch)) * 100.0
        var guardCount = 0

        while residualCents > midxSafeCentRange && guardCount < 512 {
            targetPitch += 1
            residualCents -= 100.0
            guardCount += 1
        }
        while residualCents < -midxSafeCentRange && guardCount < 512 {
            targetPitch -= 1
            residualCents += 100.0
            guardCount += 1
        }
        if abs(residualCents) < 0.000_001 {
            residualCents = 0.0
        }
        return NormalizedPitch(pitch: clamp(targetPitch, min: 0, max: 127), cents: residualCents)
    }

    private static func encodeCentOffset(_ cents: Double) -> Int {
        let cents = cents.isFinite ? cents : 0.0
        let sign = cents < 0.0 ? 0x8000 : 0
        var magnitude = Int((abs(cents) / midxCentRange * midxOffsetSteps).rounded())
        if magnitude > 0x7FFF {
            magnitude = 0x7FFF
        }
        return sign | magnitude
    }

    private static func writeMidx(_ score: ScoreData) throws -> [UInt8] {
        let tracks = score.tracksWithEvents()
        var out: [UInt8] = []
        try writeChunk(&out, type: "MThd", data: headerData(division: score.division, trackCount: max(1, tracks.count)))
        if tracks.isEmpty {
            try writeChunk(&out, type: "MTrk", data: mergedTrackData(track: nil, score: score, includeMeta: true))
        } else {
            for (index, track) in tracks.enumerated() {
                try writeChunk(&out, type: "MTrk", data: mergedTrackData(track: track, score: score, includeMeta: index == 0))
            }
        }
        return out
    }

    private static func headerData(division: Int, trackCount: Int) -> [UInt8] {
        var out: [UInt8] = []
        writeU16(&out, 1)
        writeU16(&out, trackCount)
        writeU16(&out, clamp(division, min: 1, max: 0x7FFF))
        return out
    }

    private static func metaEvents(_ score: ScoreData) -> [MetaTickEvent] {
        var events: [MetaTickEvent] = []
        events.append(contentsOf: score.tempoEvents.map { MetaTickEvent(tick: $0.tick, order: 0, payload: .tempo($0)) })
        events.append(contentsOf: score.timeSigEvents.map { MetaTickEvent(tick: $0.tick, order: 1, payload: .timeSig($0)) })
        return events.sorted {
            if $0.tick != $1.tick {
                return $0.tick < $1.tick
            }
            return $0.order < $1.order
        }
    }

    private static func writeMetaEvent(_ out: inout [UInt8], event: MetaTickEvent) {
        switch event.payload {
        case .tempo(let tempo):
            let bpm = max(1.0, min(1000.0, tempo.bpm))
            let mpqn = Int((60_000_000.0 / bpm).rounded())
            out.append(0xFF)
            out.append(0x51)
            out.append(0x03)
            writeU24(&out, mpqn)
        case .timeSig(let signature):
            out.append(0xFF)
            out.append(0x58)
            out.append(0x04)
            out.append(UInt8(clamp(signature.numerator, min: 1, max: 255)))
            out.append(UInt8(timeSigDenominatorPower(signature.denominator)))
            out.append(24)
            out.append(8)
        }
    }

    private static func timeSigDenominatorPower(_ denominator: Int) -> Int {
        var value = 1
        var power = 0
        while value < denominator && power < 8 {
            value <<= 1
            power += 1
        }
        return power
    }

    private static func mergedTrackData(track: TrackData?, score: ScoreData, includeMeta: Bool) -> [UInt8] {
        var events: [TrackTickEvent] = []
        if includeMeta {
            events.append(contentsOf: metaEvents(score).map { TrackTickEvent.meta($0) })
        }
        if let track {
            events.append(contentsOf: track.events.map { TrackTickEvent.midi($0) })
        }
        events.sort {
            if $0.tick != $1.tick {
                return $0.tick < $1.tick
            }
            if $0.order != $1.order {
                return $0.order < $1.order
            }
            return $0.pitch < $1.pitch
        }

        var out: [UInt8] = []
        if let track, !track.trackName.isEmpty {
            writeVLQ(&out, 0)
            writeTextMeta(&out, metaType: 0x03, value: track.trackName)
        }
        if let track, !track.instrumentName.isEmpty {
            writeVLQ(&out, 0)
            writeTextMeta(&out, metaType: 0x04, value: track.instrumentName)
        }
        if let track, track.writeProgramChange {
            writeVLQ(&out, 0)
            out.append(UInt8(0xC0 | (track.channel & 0x0F)))
            out.append(UInt8(track.program & 0x7F))
        }

        var previousTick: Int64 = 0
        for event in events {
            let tick = max(Int64(0), event.tick)
            writeVLQ(&out, tick - previousTick)
            switch event.payload {
            case .meta(let meta):
                writeMetaEvent(&out, event: meta)
            case .midi(let midi):
                if midi.kind == .noteOn && encodeCentOffset(midi.cents) != 0 {
                    writeMidxOffsetExtension(&out, pitch: midi.pitch, cents: midi.cents)
                    writeVLQ(&out, 0)
                }
                if midi.kind == .noteOff {
                    out.append(UInt8(0x80 | ((track?.channel ?? 0) & 0x0F)))
                    out.append(UInt8(midi.nativePitch & 0x7F))
                    out.append(0x00)
                } else {
                    out.append(UInt8(0x90 | ((track?.channel ?? 0) & 0x0F)))
                    out.append(UInt8(midi.nativePitch & 0x7F))
                    out.append(UInt8(midi.velocity & 0x7F))
                }
            }
            previousTick = tick
        }

        writeVLQ(&out, 0)
        out.append(0xFF)
        out.append(0x2F)
        out.append(0x00)
        return out
    }

    private static func writeMidxOffsetExtension(_ out: inout [UInt8], pitch: Int, cents: Double) {
        out.append(0xFF)
        out.append(UInt8(midxMetaType))
        out.append(UInt8(midxPayloadLength))
        out.append(UInt8(midxExperimentalManufacturerID))
        out.append(UInt8(ascii: "X"))
        out.append(UInt8(ascii: "T"))
        out.append(UInt8(midxPitchedOffsetRecordType))
        out.append(UInt8(clamp(pitch, min: 0, max: 127)))
        writeU16(&out, encodeCentOffset(cents))
    }

    private static func writeTextMeta(_ out: inout [UInt8], metaType: Int, value: String) {
        let bytes = Array(value.utf8)
        out.append(0xFF)
        out.append(UInt8(metaType & 0x7F))
        writeVLQ(&out, Int64(bytes.count))
        out.append(contentsOf: bytes)
    }

    private static func writeChunk(_ out: inout [UInt8], type: String, data: [UInt8]) throws {
        let typeBytes = Array(type.utf8)
        guard typeBytes.count == 4 else {
            throw MsczToMidxError.invalidInput("MIDI chunk type must be 4 bytes")
        }
        out.append(contentsOf: typeBytes)
        writeU32(&out, UInt32(data.count))
        out.append(contentsOf: data)
    }

    private static func writeU16(_ out: inout [UInt8], _ value: Int) {
        out.append(UInt8((value >> 8) & 0xFF))
        out.append(UInt8(value & 0xFF))
    }

    private static func writeU24(_ out: inout [UInt8], _ value: Int) {
        out.append(UInt8((value >> 16) & 0xFF))
        out.append(UInt8((value >> 8) & 0xFF))
        out.append(UInt8(value & 0xFF))
    }

    private static func writeU32(_ out: inout [UInt8], _ value: UInt32) {
        out.append(UInt8((value >> 24) & 0xFF))
        out.append(UInt8((value >> 16) & 0xFF))
        out.append(UInt8((value >> 8) & 0xFF))
        out.append(UInt8(value & 0xFF))
    }

    private static func writeVLQ(_ out: inout [UInt8], _ value: Int64) {
        var value = max(Int64(0), min(Int64(0x0FFF_FFFF), value))
        var stack = [UInt8(value & 0x7F)]
        value >>= 7
        while value > 0 {
            stack.append(UInt8((value & 0x7F) | 0x80))
            value >>= 7
        }
        out.append(contentsOf: stack.reversed())
    }

    private static func zipEntries(_ bytes: [UInt8]) throws -> [ZipEntryData] {
        let eocdOffset = try findEndOfCentralDirectory(bytes)
        guard eocdOffset + 22 <= bytes.count else {
            throw MsczToMidxError.invalidInput("Invalid ZIP end of central directory")
        }

        let entryCount = Int(readLEU16(bytes, offset: eocdOffset + 10))
        let centralDirectorySize = Int(readLEU32(bytes, offset: eocdOffset + 12))
        let centralDirectoryOffset = Int(readLEU32(bytes, offset: eocdOffset + 16))
        guard centralDirectoryOffset >= 0,
              centralDirectorySize >= 0,
              centralDirectoryOffset + centralDirectorySize <= bytes.count
        else {
            throw MsczToMidxError.invalidInput("Invalid ZIP central directory")
        }

        var entries: [ZipEntryData] = []
        var position = centralDirectoryOffset
        for _ in 0..<entryCount {
            guard position + 46 <= bytes.count, readLEU32(bytes, offset: position) == 0x0201_4B50 else {
                throw MsczToMidxError.invalidInput("Invalid ZIP central directory entry")
            }

            let flags = Int(readLEU16(bytes, offset: position + 8))
            let method = Int(readLEU16(bytes, offset: position + 10))
            let compressedSize = Int(readLEU32(bytes, offset: position + 20))
            let uncompressedSize = Int(readLEU32(bytes, offset: position + 24))
            let nameLength = Int(readLEU16(bytes, offset: position + 28))
            let extraLength = Int(readLEU16(bytes, offset: position + 30))
            let commentLength = Int(readLEU16(bytes, offset: position + 32))
            let localHeaderOffset = Int(readLEU32(bytes, offset: position + 42))
            let nameStart = position + 46
            let nameEnd = nameStart + nameLength
            guard nameEnd <= bytes.count else {
                throw MsczToMidxError.invalidInput("Invalid ZIP file name")
            }
            let name = String(decoding: bytes[nameStart..<nameEnd], as: UTF8.self)

            if !name.hasSuffix("/") {
                entries.append(
                    ZipEntryData(
                        name: name,
                        data: try readZipEntryData(
                            bytes,
                            name: name,
                            flags: flags,
                            method: method,
                            compressedSize: compressedSize,
                            uncompressedSize: uncompressedSize,
                            localHeaderOffset: localHeaderOffset
                        )
                    )
                )
            }

            position = nameEnd + extraLength + commentLength
        }
        return entries
    }

    private static func readZipEntryData(
        _ bytes: [UInt8],
        name: String,
        flags: Int,
        method: Int,
        compressedSize: Int,
        uncompressedSize: Int,
        localHeaderOffset: Int
    ) throws -> [UInt8] {
        if (flags & 0x01) != 0 {
            throw MsczToMidxError.invalidInput("Encrypted ZIP entry is not supported: \(name)")
        }
        guard localHeaderOffset >= 0,
              localHeaderOffset + 30 <= bytes.count,
              readLEU32(bytes, offset: localHeaderOffset) == 0x0403_4B50
        else {
            throw MsczToMidxError.invalidInput("Invalid ZIP local header for \(name)")
        }

        let nameLength = Int(readLEU16(bytes, offset: localHeaderOffset + 26))
        let extraLength = Int(readLEU16(bytes, offset: localHeaderOffset + 28))
        let dataStart = localHeaderOffset + 30 + nameLength + extraLength
        guard compressedSize >= 0, dataStart >= 0, dataStart + compressedSize <= bytes.count else {
            throw MsczToMidxError.invalidInput("Invalid ZIP data range for \(name)")
        }

        let compressed = Array(bytes[dataStart..<(dataStart + compressedSize)])
        switch method {
        case 0:
            return compressed
        case 8:
            return try inflateRawDeflate(compressed, expectedSize: uncompressedSize)
        default:
            throw MsczToMidxError.invalidInput("Unsupported ZIP compression method \(method) for \(name)")
        }
    }

    private static func findEndOfCentralDirectory(_ bytes: [UInt8]) throws -> Int {
        guard bytes.count >= 22 else {
            throw MsczToMidxError.invalidInput("Invalid ZIP file")
        }
        let minimumOffset = max(0, bytes.count - 65_557)
        var offset = bytes.count - 22
        while offset >= minimumOffset {
            if readLEU32(bytes, offset: offset) == 0x0605_4B50 {
                return offset
            }
            offset -= 1
        }
        throw MsczToMidxError.invalidInput("Invalid ZIP file: missing central directory")
    }

    private static func inflateRawDeflate(_ input: [UInt8], expectedSize: Int) throws -> [UInt8] {
        if input.isEmpty {
            return []
        }

        var stream = z_stream()
        let initStatus = inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard initStatus == Z_OK else {
            throw MsczToMidxError.invalidInput("Could not initialize ZIP decompressor")
        }
        defer {
            inflateEnd(&stream)
        }

        var output: [UInt8] = []
        output.reserveCapacity(max(0, expectedSize))
        var status: Int32 = Z_OK

        try input.withUnsafeBufferPointer { inputBuffer in
            guard let inputBase = inputBuffer.baseAddress else {
                return
            }
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: inputBase)
            stream.avail_in = uInt(input.count)

            repeat {
                var buffer = [UInt8](repeating: 0, count: max(4096, min(max(expectedSize, 4096), 65_536)))
                try buffer.withUnsafeMutableBufferPointer { outputBuffer in
                    guard let outputBase = outputBuffer.baseAddress else {
                        throw MsczToMidxError.invalidInput("Could not allocate ZIP decompression buffer")
                    }
                    stream.next_out = outputBase
                    stream.avail_out = uInt(outputBuffer.count)
                    status = inflate(&stream, Z_NO_FLUSH)
                    let produced = outputBuffer.count - Int(stream.avail_out)
                    if produced > 0 {
                        output.append(contentsOf: outputBuffer[..<produced])
                    }
                }
            } while status == Z_OK
        }

        guard status == Z_STREAM_END else {
            throw MsczToMidxError.invalidInput("Could not decompress ZIP entry")
        }
        return output
    }

    private static func isZip(_ bytes: [UInt8]) -> Bool {
        bytes.count >= 4 &&
            bytes[0] == 0x50 &&
            bytes[1] == 0x4B &&
            bytes[2] == 0x03 &&
            bytes[3] == 0x04
    }

    private static func readLEU16(_ bytes: [UInt8], offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
    }

    private static func readLEU32(_ bytes: [UInt8], offset: Int) -> UInt32 {
        UInt32(bytes[offset]) |
            (UInt32(bytes[offset + 1]) << 8) |
            (UInt32(bytes[offset + 2]) << 16) |
            (UInt32(bytes[offset + 3]) << 24)
    }

    private static func firstDirectChild(_ parent: Element?, _ tag: String) -> Element? {
        parent?.children.first { $0.name == tag }
    }

    private static func directChildren(_ parent: Element?, _ tag: String?) -> [Element] {
        guard let parent else {
            return []
        }
        return parent.children.filter { tag == nil || $0.name == tag }
    }

    private static func firstDirectDescendant(_ parent: Element?, _ tag: String) -> Element? {
        guard let parent else {
            return nil
        }
        for child in parent.children {
            if child.name == tag {
                return child
            }
            if let descendant = firstDirectDescendant(child, tag) {
                return descendant
            }
        }
        return nil
    }

    private static func text(_ element: Element?) -> String {
        element?.text.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func intText(_ element: Element?, fallback: Int) -> Int {
        parseInt(text(element), fallback: fallback)
    }

    private static func doubleText(_ element: Element?, fallback: Double) -> Double {
        parseDouble(text(element), fallback: fallback)
    }

    private static func intAttribute(_ element: Element?, _ name: String, fallback: Int) -> Int {
        guard let element else {
            return fallback
        }
        return parseInt(element.attribute(name), fallback: fallback)
    }

    private static func hasAttributeValue(_ element: Element?, _ name: String) -> Bool {
        guard let element else {
            return false
        }
        return !element.attribute(name).isEmpty
    }

    private static func parseInt(_ value: String?, fallback: Int) -> Int {
        guard let value,
              let parsed = Double(value.trimmingCharacters(in: .whitespacesAndNewlines))
        else {
            return fallback
        }
        return Int(parsed.rounded())
    }

    private static func parseDouble(_ value: String?, fallback: Double) -> Double {
        guard let value,
              let parsed = Double(value.trimmingCharacters(in: .whitespacesAndNewlines))
        else {
            return fallback
        }
        return parsed
    }

    private static func clamp(_ value: Int, min: Int, max: Int) -> Int {
        if value < min {
            return min
        }
        if value > max {
            return max
        }
        return value
    }
}

private enum MsczToMidxError: LocalizedError {
    case invalidInput(String)

    var errorDescription: String? {
        switch self {
        case .invalidInput(let message):
            return message
        }
    }
}

private final class Element {
    let name: String
    var attributes: [String: String] = [:]
    var children: [Element] = []
    var text = ""

    init(name: String) {
        self.name = name
    }

    func attribute(_ name: String) -> String {
        attributes[name] ?? ""
    }
}

private final class ElementTreeHandler: NSObject, XMLParserDelegate {
    var root: Element?
    private var stack: [Element] = []

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        let element = Element(name: (qName?.isEmpty == false ? qName : elementName) ?? elementName)
        element.attributes = attributeDict
        if let parent = stack.last {
            parent.children.append(element)
        } else {
            root = element
        }
        stack.append(element)
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        stack.last?.text.append(string)
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        if !stack.isEmpty {
            stack.removeLast()
        }
    }
}

private final class ScoreData {
    var division = 480
    var noteCount = 0
    var microtonalCount = 0
    var staffInfos: [Int: StaffInfo] = [:]
    var tracks: [Int: TrackData] = [:]
    var tempoEvents: [TempoEventData] = []
    var timeSigEvents: [TimeSigEvent] = []
    var ottavaIntervals: [Int: [OttavaInterval]] = [:]
    var playbackSpans: [Int: [PlaybackSpan]] = [:]

    func trackForStaff(_ info: StaffInfo) -> TrackData {
        if let track = tracks[info.staffId] {
            return track
        }
        let track = TrackData()
        track.staffId = info.staffId
        track.partIndex = info.partIndex
        track.program = info.program
        track.channel = info.channel
        track.gateTimePercent = info.gateTimePercent
        track.writeProgramChange = info.writeProgramChange
        track.trackName = info.trackName
        track.instrumentName = info.instrumentName
        tracks[info.staffId] = track
        return track
    }

    func tracksWithEvents() -> [TrackData] {
        tracks.keys.sorted().compactMap { key in
            guard let track = tracks[key], !track.events.isEmpty else {
                return nil
            }
            return track
        }
    }

    func addOttavaInterval(staffId: Int, startTick: Int64, endTick: Int64, shift: Int) {
        guard endTick > startTick else {
            return
        }
        ottavaIntervals[staffId, default: []].append(OttavaInterval(startTick: startTick, endTick: endTick, shift: shift))
    }

    func ottavaShift(staffId: Int, tick: Int64) -> Int {
        guard let intervals = ottavaIntervals[staffId] else {
            return 0
        }
        return intervals
            .filter { tick >= $0.startTick && tick < $0.endTick }
            .sorted { $0.startTick < $1.startTick }
            .last?.shift ?? 0
    }

    func addPlaybackSpan(staffId: Int, startTick: Int64, endTick: Int64, symbolNames: [String]) {
        guard endTick > startTick, !symbolNames.isEmpty else {
            return
        }
        playbackSpans[staffId, default: []].append(PlaybackSpan(startTick: startTick, endTick: endTick, symbolNames: symbolNames))
    }

    func playbackSymbolNames(staffId: Int, tick: Int64) -> [String] {
        guard let spans = playbackSpans[staffId] else {
            return []
        }
        return spans
            .filter { tick >= $0.startTick && tick < $0.endTick }
            .flatMap(\.symbolNames)
    }
}

private final class StaffInfo {
    var staffId = 0
    var partIndex = 0
    var program = 0
    var channel = 0
    var gateTimePercent = 100
    var writeProgramChange = false
    var trackName = ""
    var instrumentName = ""
}

private final class TrackData {
    var staffId = 0
    var partIndex = 0
    var program = 0
    var channel = 0
    var gateTimePercent = 100
    var writeProgramChange = false
    var trackName = ""
    var instrumentName = ""
    var events: [MidiEvent] = []
}

private final class VoiceState {
    var tick: Int64 = 0
    var measureTicks: Int64 = 0
    var velocity = 80
    var tupletRatio = 1.0
    var tupletRemaining = 0
    var activeTies: [TieKey: NotePlayback] = [:]
    var pendingGraceChords: [Element] = []
    var pendingPlaybackSymbols: [Element] = []

    func consumePendingPlaybackSymbols() -> [Element] {
        let symbols = pendingPlaybackSymbols
        pendingPlaybackSymbols.removeAll()
        return symbols
    }
}

private struct TempoEventData {
    let tick: Int64
    let bpm: Double
}

private struct TimeSigEvent {
    let tick: Int64
    let numerator: Int
    let denominator: Int
}

private struct OttavaInterval {
    let startTick: Int64
    let endTick: Int64
    let shift: Int
}

private struct PlaybackSpan {
    let startTick: Int64
    let endTick: Int64
    let symbolNames: [String]
}

private struct MetaTickEvent {
    let tick: Int64
    let order: Int
    let payload: MetaPayload
}

private enum MetaPayload {
    case tempo(TempoEventData)
    case timeSig(TimeSigEvent)
}

private struct TrackTickEvent {
    let tick: Int64
    let order: Int
    let pitch: Int
    let payload: TrackTickPayload

    static func meta(_ event: MetaTickEvent) -> TrackTickEvent {
        TrackTickEvent(tick: event.tick, order: event.order, pitch: 0, payload: .meta(event))
    }

    static func midi(_ event: MidiEvent) -> TrackTickEvent {
        TrackTickEvent(
            tick: event.tick,
            order: event.kind == .noteOff ? 10 : 20,
            pitch: event.pitch,
            payload: .midi(event)
        )
    }
}

private enum TrackTickPayload {
    case meta(MetaTickEvent)
    case midi(MidiEvent)
}

private struct MidiEvent {
    enum Kind {
        case noteOff
        case noteOn
    }

    let tick: Int64
    let kind: Kind
    let pitch: Int
    let nativePitch: Int
    let velocity: Int
    let cents: Double

    static func noteOn(tick: Int64, pitch: Int, nativePitch: Int, velocity: Int, cents: Double) -> MidiEvent {
        MidiEvent(tick: tick, kind: .noteOn, pitch: pitch, nativePitch: nativePitch, velocity: velocity, cents: cents)
    }

    static func noteOff(tick: Int64, nativePitch: Int) -> MidiEvent {
        MidiEvent(tick: tick, kind: .noteOff, pitch: nativePitch, nativePitch: nativePitch, velocity: 0, cents: 0.0)
    }
}

private final class NotePlayback {
    var startTick: Int64 = 0
    var endTick: Int64 = 0
    var pitch = 0
    var nativePitch = 0
    var cents = 0.0
    var velocity = 0
}

private struct NormalizedPitch {
    let pitch: Int
    let cents: Double
}

private struct EventTiming {
    let offsetTicks: Int64
    let lengthTicks: Int64
    let pitchDelta: Double
}

private struct ChordPlayback {
    let timings: [EventTiming]
    let velocityDelta: Int
    let gatePercent: Int
    let durationMultiplier: Double
    let arpeggio: ArpeggioPlayback?
}

private struct PlaybackModifiers {
    var velocityDelta = 0
    var gatePercent = 100
    var durationMultiplier = 1.0
}

private enum ArpeggioDirection {
    case up
    case down
}

private struct ArpeggioPlayback {
    let direction: ArpeggioDirection
    let stepTicks: Int64
}

private struct GraceKind {
    let isAfter: Bool
    let borrowsPrincipalTime: Bool
    let durationType: String
}

private struct ZipEntryData {
    let name: String
    let data: [UInt8]
}

private struct TieKey: Hashable {
    let staffId: Int
    let voiceIndex: Int
    let pitch: Int
    let tuningKey: Int
}
