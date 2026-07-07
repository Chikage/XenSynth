package icu.ringona.xensynth;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Standalone MuseScore .mscz/.mscx to current-format MIDX converter.
 *
 * Current MIDX is a Standard MIDI File superset: native MIDI note-on/off events
 * remain intact, and microtonal pitch offsets are stored as sequencer-specific
 * meta events immediately before the related note-on:
 *
 *   FF 7F 07 7D 58 54 03 <pitch> <offset16_be>
 *
 * The 16-bit offset is signed-magnitude. The low 15 bits cover 0..64 cents.
 *
 * Usage:
 *   javac -d out app/src/main/java/icu/ringona/xensynth/MsczToMidx.java
 *   java -cp out icu.ringona.xensynth.MsczToMidx input.mscz [output.midx|output.mid]
 */
public final class MsczToMidx {
    private static final int DEFAULT_DIVISION = 480;
    private static final int DEFAULT_BPM = 120;
    private static final int DEFAULT_VELOCITY = 80;
    private static final int DEFAULT_TIME_SIG_N = 4;
    private static final int DEFAULT_TIME_SIG_D = 4;

    private static final int MIDX_META_TYPE = 0x7F;
    private static final int MIDX_PAYLOAD_LEN = 7;
    private static final int MIDX_EXPERIMENTAL_MANUFACTURER_ID = 0x7D;
    private static final int MIDX_PITCHED_OFFSET_RECORD_TYPE = 0x03;
    private static final int MIDX_CENT_RANGE = 64;
    private static final int MIDX_SAFE_CENT_RANGE = 63;
    private static final int MIDX_OFFSET_STEPS = 32768;
    private static final int MIDI_CONTROL_SUSTAIN = 64;
    private static final int MAX_REPEAT_COUNT = 8;

    private MsczToMidx() {
    }

    public static byte[] convert(byte[] inputBytes, String fileName) throws Exception {
        return convertToMidx(inputBytes, fileName);
    }

    public static byte[] convertToMidx(byte[] inputBytes, String fileName) throws Exception {
        if (inputBytes == null) {
            throw new IOException("Input bytes are null");
        }
        ScoreData score = parseScore(inputBytes, fileName == null ? "selected-file" : fileName);
        return writeMidiFile(score, true);
    }

    public static byte[] convertToMidi(byte[] inputBytes, String fileName) throws Exception {
        if (inputBytes == null) {
            throw new IOException("Input bytes are null");
        }
        ScoreData score = parseScore(inputBytes, fileName == null ? "selected-file" : fileName);
        return writeMidiFile(score, false);
    }

    public static byte[] convert(File input) throws Exception {
        return convertToMidx(input);
    }

    public static byte[] convertToMidx(File input) throws Exception {
        return writeMidiFile(parseScore(input), true);
    }

    public static byte[] convertToMidi(File input) throws Exception {
        return writeMidiFile(parseScore(input), false);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            return;
        }

        File input = new File(args[0]);
        if (!input.isFile()) {
            throw new IOException("Input file does not exist: " + input.getAbsolutePath());
        }

        File output = args.length >= 2 ? new File(args[1]) : defaultOutputFile(input);
        ScoreData score = parseScore(input);
        boolean includeMidxExtensions = wantsMidxOutput(output);
        byte[] midi = writeMidiFile(score, includeMidxExtensions);

        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory: " + parent.getAbsolutePath());
        }

        FileOutputStream out = new FileOutputStream(output);
        try {
            out.write(midi);
        } finally {
            out.close();
        }

        System.out.println("Wrote " + output.getAbsolutePath());
        System.out.println("format=" + (includeMidxExtensions ? "midx" : "midi")
                + " division=" + score.division
                + " tracks=" + score.tracksWithEvents().size()
                + " notes=" + score.noteCount
                + " microtonalOffsets=" + score.microtonalCount
                + " bytes=" + midi.length);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  javac -d out app/src/main/java/icu/ringona/xensynth/MsczToMidx.java");
        System.out.println("  java -cp out icu.ringona.xensynth.MsczToMidx input.mscz [output.midx|output.mid]");
    }

    private static File defaultOutputFile(File input) {
        String name = input.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return new File(input.getParentFile() == null ? new File(".") : input.getParentFile(), name + ".midx");
    }

    private static boolean wantsMidxOutput(File output) {
        String lower = output == null ? "" : output.getName().toLowerCase(Locale.ROOT);
        return !(lower.endsWith(".mid") || lower.endsWith(".midi"));
    }

    private static ScoreData parseScore(File input) throws Exception {
        return parseScoreXml(readMuseScoreXml(input), input.getAbsolutePath());
    }

    private static ScoreData parseScore(byte[] inputBytes, String fileName) throws Exception {
        return parseScoreXml(readMuseScoreXml(inputBytes, fileName), fileName);
    }

    private static ScoreData parseScoreXml(Element root, String sourceName) throws Exception {
        Element scoreElement = "Score".equals(root.name) ? root : firstDirectChild(root, "Score");
        if (scoreElement == null) {
            throw new IOException("No <Score> element found in " + sourceName);
        }

        ScoreData score = new ScoreData();
        score.division = intText(firstDirectChild(scoreElement, "Division"), DEFAULT_DIVISION);
        if (score.division <= 0) {
            score.division = DEFAULT_DIVISION;
        }

        parseParts(scoreElement, score);
        parseStaffBodies(scoreElement, score);

        if (score.tempoEvents.isEmpty()) {
            score.tempoEvents.add(new TempoEvent(0, DEFAULT_BPM));
        }
        if (score.timeSigEvents.isEmpty()) {
            score.timeSigEvents.add(new TimeSigEvent(0, DEFAULT_TIME_SIG_N, DEFAULT_TIME_SIG_D));
        }

        return score;
    }

    private static Element readMuseScoreXml(File input) throws Exception {
        String lower = input.getName().toLowerCase(Locale.ROOT);
        byte[] xmlBytes;
        if (lower.endsWith(".mscz")) {
            xmlBytes = readMsczRootXml(input);
        } else {
            xmlBytes = readAll(new FileInputStream(input));
        }
        return parseXml(xmlBytes);
    }

    private static Element readMuseScoreXml(byte[] inputBytes, String fileName) throws Exception {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        byte[] xmlBytes;
        if (lower.endsWith(".mscz") || isZip(inputBytes)) {
            xmlBytes = readMsczRootXml(inputBytes, fileName);
        } else {
            xmlBytes = inputBytes;
        }
        return parseXml(xmlBytes);
    }

    private static byte[] readMsczRootXml(File input) throws Exception {
        ZipFile zip = new ZipFile(input);
        try {
            String rootPath = null;
            ZipEntry container = findZipEntry(zip, "META-INF/container.xml");
            if (container != null) {
                rootPath = normalizeZipPath(firstRootfilePath(readAll(zip.getInputStream(container))));
            }

            ZipEntry scoreEntry = rootPath == null ? null : findZipEntry(zip, rootPath);
            if (scoreEntry == null) {
                scoreEntry = firstMscxEntry(zip);
            }
            if (scoreEntry == null) {
                throw new IOException("No .mscx score found inside " + input.getAbsolutePath());
            }
            return readAll(zip.getInputStream(scoreEntry));
        } finally {
            zip.close();
        }
    }

    private static byte[] readMsczRootXml(byte[] inputBytes, String sourceName) throws Exception {
        String rootPath = null;
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(inputBytes));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalizeZipPath(entry.getName());
                if (!entry.isDirectory() && "META-INF/container.xml".equals(name)) {
                    rootPath = normalizeZipPath(firstRootfilePath(readAllKeepingOpen(zip)));
                    zip.closeEntry();
                    break;
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }

        byte[] scoreBytes = rootPath == null ? null : readZipEntry(inputBytes, rootPath);
        if (scoreBytes == null) {
            scoreBytes = readFirstMscxEntry(inputBytes);
        }
        if (scoreBytes == null) {
            throw new IOException("No .mscx score found inside " + sourceName);
        }
        return scoreBytes;
    }

    private static ZipEntry findZipEntry(ZipFile zip, String path) {
        String normalizedPath = normalizeZipPath(path);
        if (normalizedPath == null) {
            return null;
        }
        ZipEntry direct = zip.getEntry(normalizedPath);
        if (direct != null && !direct.isDirectory()) {
            return direct;
        }
        List<? extends ZipEntry> entries = Collections.list(zip.entries());
        for (ZipEntry entry : entries) {
            if (!entry.isDirectory() && normalizedPath.equals(normalizeZipPath(entry.getName()))) {
                return entry;
            }
        }
        return null;
    }

    private static ZipEntry firstMscxEntry(ZipFile zip) {
        List<? extends ZipEntry> entries = Collections.list(zip.entries());
        for (ZipEntry entry : entries) {
            String name = normalizeZipPath(entry.getName());
            if (!entry.isDirectory() && name != null && name.toLowerCase(Locale.ROOT).endsWith(".mscx")) {
                return entry;
            }
        }
        return null;
    }

    private static byte[] readZipEntry(byte[] inputBytes, String path) throws Exception {
        String normalizedPath = normalizeZipPath(path);
        if (normalizedPath == null) {
            return null;
        }
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(inputBytes));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalizeZipPath(entry.getName());
                if (!entry.isDirectory() && normalizedPath.equals(name)) {
                    return readAllKeepingOpen(zip);
                }
                zip.closeEntry();
            }
            return null;
        } finally {
            zip.close();
        }
    }

    private static byte[] readFirstMscxEntry(byte[] inputBytes) throws Exception {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(inputBytes));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalizeZipPath(entry.getName());
                if (!entry.isDirectory() && name != null && name.toLowerCase(Locale.ROOT).endsWith(".mscx")) {
                    return readAllKeepingOpen(zip);
                }
                zip.closeEntry();
            }
            return null;
        } finally {
            zip.close();
        }
    }

    private static String normalizeZipPath(String path) {
        if (path == null) {
            return null;
        }
        String value = path.replace('\\', '/').trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.length() == 0 || value.indexOf(':') >= 0) {
            return null;
        }
        String[] parts = value.split("/");
        List<String> clean = new ArrayList<String>();
        for (String part : parts) {
            if (part.length() == 0 || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null;
            }
            clean.add(part);
        }
        if (clean.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(clean.get(i));
        }
        return out.toString();
    }

    private static boolean isZip(byte[] bytes) {
        return bytes != null
                && bytes.length >= 4
                && bytes[0] == 0x50
                && bytes[1] == 0x4B
                && bytes[2] == 0x03
                && bytes[3] == 0x04;
    }

    private static Element parseXml(byte[] bytes) throws Exception {
        byte[] normalized = normalizeXmlBytes(bytes);
        rejectUnsupportedXml(normalized);
        SAXParserFactory factory = SAXParserFactory.newInstance();
        setSaxNamespaceAware(factory, false);
        setSaxFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setSaxFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setSaxFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setSaxFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setSaxFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);

        SAXParser parser = factory.newSAXParser();
        ElementTreeHandler handler = new ElementTreeHandler();
        parser.parse(new InputSource(new ByteArrayInputStream(normalized)), handler);
        Element root = handler.root;
        if (root == null) {
            throw new IOException("Empty XML document");
        }
        return root;
    }

    private static String firstRootfilePath(byte[] bytes) throws Exception {
        byte[] normalized = normalizeXmlBytes(bytes);
        rejectUnsupportedXml(normalized);
        SAXParserFactory factory = SAXParserFactory.newInstance();
        setSaxNamespaceAware(factory, false);
        setSaxFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setSaxFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setSaxFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setSaxFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setSaxFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);

        RootfileHandler handler = new RootfileHandler();
        try {
            factory.newSAXParser().parse(new InputSource(new ByteArrayInputStream(normalized)), handler);
        } catch (RootfileFound found) {
            return found.path;
        }
        return handler.path;
    }

    private static byte[] normalizeXmlBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Empty XML document");
        }
        int start = 0;
        if (bytes.length >= 3 &&
                bytes[0] == (byte) 0xEF &&
                bytes[1] == (byte) 0xBB &&
                bytes[2] == (byte) 0xBF) {
            start = 3;
        }
        while (start < bytes.length) {
            byte b = bytes[start];
            if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) {
                start++;
            } else {
                break;
            }
        }
        if (start == 0) {
            return bytes;
        }
        byte[] normalized = new byte[bytes.length - start];
        System.arraycopy(bytes, start, normalized, 0, normalized.length);
        return normalized;
    }

    private static void rejectUnsupportedXml(byte[] bytes) throws IOException {
        String prefix = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (prefix.contains("<!doctype")) {
            throw new IOException("MuseScore XML with DOCTYPE is not supported");
        }
        if (prefix.contains("<!entity")) {
            throw new IOException("MuseScore XML with entity declarations is not supported");
        }
    }

    private static void setSaxNamespaceAware(SAXParserFactory factory, boolean enabled) {
        try {
            factory.setNamespaceAware(enabled);
        } catch (Exception ignored) {
            // Some Android XML parsers reject optional JAXP settings.
        }
    }

    private static void setSaxFeature(SAXParserFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
            // Some Android XML parsers do not expose every hardening flag.
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            return readAllKeepingOpen(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAllKeepingOpen(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void parseParts(Element scoreElement, ScoreData score) {
        int partIndex = 0;
        int nextChannel = 0;
        for (Element part : directChildren(scoreElement, "Part")) {
            int program = parsePartProgram(part);
            int bankMsb = parsePartBank(part, 0);
            int bankLsb = parsePartBank(part, 32);
            int midiChannel = parsePartMidiChannel(part);
            int gateTimePercent = parsePartGateTime(part);
            String trackName = parsePartTrackName(part);
            String instrumentName = parsePartInstrumentName(part);
            List<Element> partStaffs = directChildren(part, "Staff");
            if (partStaffs.isEmpty()) {
                continue;
            }

            int channel = midiChannel >= 0 ? clamp(midiChannel, 0, 15) : chooseChannel(nextChannel++);
            boolean firstStaffInPart = true;
            for (Element staffElement : partStaffs) {
                int staffId = intAttribute(staffElement, "id", score.staffInfos.size() + 1);
                StaffInfo info = new StaffInfo();
                info.staffId = staffId;
                info.partIndex = partIndex;
                info.program = clamp(program, 0, 127);
                info.bankMsb = bankMsb;
                info.bankLsb = bankLsb;
                info.channel = channel;
                info.gateTimePercent = gateTimePercent;
                info.writeProgramChange = firstStaffInPart;
                info.trackName = trackName;
                info.instrumentName = instrumentName;
                score.staffInfos.put(Integer.valueOf(staffId), info);
                firstStaffInPart = false;
            }
            partIndex++;
        }
    }

    private static int parsePartProgram(Element part) {
        Element channel = primaryChannelElement(part);
        Element program = channel == null ? null : firstDirectChild(channel, "program");
        Element instrument = firstDirectChild(part, "Instrument");
        if (program == null) {
            program = firstDirectDescendant(instrument, "program");
        }
        if (program == null) {
            return 0;
        }
        String value = program.getAttribute("value");
        if (value != null && value.length() > 0) {
            return parseInt(value, 0);
        }
        return parseInt(text(program), 0);
    }

    private static int parsePartMidiChannel(Element part) {
        Element channel = primaryChannelElement(part);
        return intText(firstDirectChild(channel, "midiChannel"), -1);
    }

    private static int parsePartBank(Element part, int controllerNumber) {
        Element channel = primaryChannelElement(part);
        if (channel == null) {
            return -1;
        }
        for (Element controller : directChildren(channel, "controller")) {
            int ctrl = intAttribute(controller, "ctrl", -1);
            if (ctrl == controllerNumber) {
                return clamp(intAttribute(controller, "value", 0), 0, 127);
            }
        }
        return -1;
    }

    private static Element primaryChannelElement(Element part) {
        Element instrument = firstDirectChild(part, "Instrument");
        if (instrument == null) {
            return null;
        }
        Element fallback = null;
        for (Element channel : directChildren(instrument, "Channel")) {
            if (fallback == null) {
                fallback = channel;
            }
            String name = channel.getAttribute("name");
            if (name == null || name.length() == 0 || "normal".equalsIgnoreCase(name) || "open".equalsIgnoreCase(name)) {
                return channel;
            }
        }
        return fallback;
    }

    private static int parsePartGateTime(Element part) {
        Element instrument = firstDirectChild(part, "Instrument");
        if (instrument == null) {
            return 100;
        }
        for (Element articulation : directChildren(instrument, "Articulation")) {
            String name = articulation.getAttribute("name");
            if (name == null || name.length() == 0) {
                return clamp(intText(firstDirectChild(articulation, "gateTime"), 100), 1, 1000);
            }
        }
        return 100;
    }

    private static String parsePartTrackName(Element part) {
        String name = text(firstDirectChild(part, "trackName"));
        if (name.length() > 0) {
            return name;
        }
        Element instrument = firstDirectChild(part, "Instrument");
        return text(firstDirectChild(instrument, "trackName"));
    }

    private static String parsePartInstrumentName(Element part) {
        Element instrument = firstDirectChild(part, "Instrument");
        String longName = text(firstDirectChild(instrument, "longName"));
        if (longName.length() > 0) {
            return longName;
        }
        String shortName = text(firstDirectChild(instrument, "shortName"));
        if (shortName.length() > 0) {
            return shortName;
        }
        return text(firstDirectChild(instrument, "instrumentId"));
    }

    private static int chooseChannel(int index) {
        int channel = index % 15;
        return channel >= 9 ? channel + 1 : channel;
    }

    private static void parseStaffBodies(Element scoreElement, ScoreData score) {
        int fallbackStaffId = 1;
        List<Element> staffBodies = directChildren(scoreElement, "Staff");
        MeasureTimeline timeline = buildMeasureTimeline(staffBodies, score.division);
        for (Element staffBody : staffBodies) {
            int staffId = intAttribute(staffBody, "id", fallbackStaffId++);
            StaffInfo info = score.staffInfos.get(Integer.valueOf(staffId));
            if (info == null) {
                info = new StaffInfo();
                info.staffId = staffId;
                info.partIndex = score.staffInfos.size();
                info.program = 0;
                info.bankMsb = -1;
                info.bankLsb = -1;
                info.channel = chooseChannel(score.staffInfos.size());
                info.gateTimePercent = 100;
                info.writeProgramChange = true;
                info.trackName = "";
                info.instrumentName = "";
                score.staffInfos.put(Integer.valueOf(staffId), info);
            }
            TrackData track = score.trackForStaff(info);
            collectPlaybackSpanners(staffBody, track, timeline, score.division);
            parseStaffBody(staffBody, score, track, timeline);
        }
    }

    private static MeasureTimeline buildMeasureTimeline(List<Element> staffBodies, int division) {
        List<List<Element>> measuresByStaff = new ArrayList<List<Element>>();
        int maxMeasures = 0;
        for (Element staffBody : staffBodies) {
            List<Element> measures = directChildren(staffBody, "Measure");
            measuresByStaff.add(measures);
            if (measures.size() > maxMeasures) {
                maxMeasures = measures.size();
            }
        }

        List<Long> starts = new ArrayList<Long>();
        List<Long> lengths = new ArrayList<Long>();
        long tick = 0;
        int currentSigN = DEFAULT_TIME_SIG_N;
        int currentSigD = DEFAULT_TIME_SIG_D;
        for (int measureIndex = 0; measureIndex < maxMeasures; measureIndex++) {
            starts.add(Long.valueOf(tick));
            TimeSigEvent signature = firstTimeSigAtMeasure(measuresByStaff, measureIndex);
            if (signature != null) {
                currentSigN = signature.numerator;
                currentSigD = signature.denominator;
            }
            long length = Math.max(1, measureLengthAtIndex(measuresByStaff, measureIndex, division, currentSigN, currentSigD, tick));
            lengths.add(Long.valueOf(length));
            tick += length;
        }

        MeasureTimeline timeline = new MeasureTimeline();
        timeline.sourceStarts.addAll(starts);
        timeline.sourceLengths.addAll(lengths);

        int repeatStart = 0;
        long outputTick = 0;
        for (int measureIndex = 0; measureIndex < maxMeasures; measureIndex++) {
            if (measureHasStartRepeat(measuresByStaff, measureIndex)) {
                repeatStart = measureIndex;
            }
            outputTick = addMeasureSlot(timeline, measureIndex, outputTick);

            int repeatCount = measureEndRepeatCount(measuresByStaff, measureIndex);
            if (repeatCount > 1) {
                int safeCount = clamp(repeatCount, 2, MAX_REPEAT_COUNT);
                for (int pass = 1; pass < safeCount; pass++) {
                    for (int replayIndex = repeatStart; replayIndex <= measureIndex; replayIndex++) {
                        outputTick = addMeasureSlot(timeline, replayIndex, outputTick);
                    }
                }
                repeatStart = measureIndex + 1;
            }
        }
        return timeline;
    }

    private static long addMeasureSlot(MeasureTimeline timeline, int measureIndex, long outputTick) {
        long sourceStart = measureIndex < timeline.sourceStarts.size()
                ? timeline.sourceStarts.get(measureIndex).longValue()
                : 0;
        long sourceLength = measureIndex < timeline.sourceLengths.size()
                ? timeline.sourceLengths.get(measureIndex).longValue()
                : DEFAULT_DIVISION;
        timeline.slots.add(new MeasureSlot(measureIndex, sourceStart, outputTick, Math.max(1, sourceLength)));
        return outputTick + Math.max(1, sourceLength);
    }

    private static boolean measureHasStartRepeat(List<List<Element>> measuresByStaff, int measureIndex) {
        for (List<Element> measures : measuresByStaff) {
            if (measureIndex < measures.size() && firstDirectChild(measures.get(measureIndex), "startRepeat") != null) {
                return true;
            }
        }
        return false;
    }

    private static int measureEndRepeatCount(List<List<Element>> measuresByStaff, int measureIndex) {
        int repeatCount = 0;
        for (List<Element> measures : measuresByStaff) {
            if (measureIndex >= measures.size()) {
                continue;
            }
            Element endRepeat = firstDirectChild(measures.get(measureIndex), "endRepeat");
            if (endRepeat != null) {
                repeatCount = Math.max(repeatCount, Math.max(2, intText(endRepeat, 2)));
            }
        }
        return repeatCount;
    }

    private static TimeSigEvent firstTimeSigAtMeasure(List<List<Element>> measuresByStaff, int measureIndex) {
        for (List<Element> measures : measuresByStaff) {
            if (measureIndex >= measures.size()) {
                continue;
            }
            Element timeSig = firstTimeSigInMeasure(measures.get(measureIndex));
            if (timeSig != null) {
                int n = intText(firstDirectChild(timeSig, "sigN"), DEFAULT_TIME_SIG_N);
                int d = intText(firstDirectChild(timeSig, "sigD"), DEFAULT_TIME_SIG_D);
                if (n > 0 && d > 0) {
                    return new TimeSigEvent(0, n, d);
                }
            }
        }
        return null;
    }

    private static Element firstTimeSigInMeasure(Element measure) {
        for (Element voice : directChildren(measure, "voice")) {
            Element timeSig = firstDirectChild(voice, "TimeSig");
            if (timeSig != null) {
                return timeSig;
            }
        }
        return null;
    }

    private static long measureLengthAtIndex(
            List<List<Element>> measuresByStaff,
            int measureIndex,
            int division,
            int sigN,
            int sigD,
            long measureStart
    ) {
        long signatureTicks = ticksForTimeSignature(division, sigN, sigD);
        boolean irregular = false;
        long irregularTicks = 0;
        for (List<Element> measures : measuresByStaff) {
            if (measureIndex >= measures.size()) {
                continue;
            }
            Element measure = measures.get(measureIndex);
            if (hasAttributeValue(measure, "len")) {
                long explicitTicks = ratioTicks(measure.getAttribute("len"), division);
                if (explicitTicks > 0) {
                    return explicitTicks;
                }
            }
            if (intText(firstDirectChild(measure, "irregular"), 0) != 0) {
                irregular = true;
                irregularTicks = Math.max(irregularTicks, measureContentTicks(measure, division, signatureTicks, measureStart));
            }
        }
        if (irregular && irregularTicks > 0) {
            return irregularTicks;
        }
        return signatureTicks;
    }

    private static long measureContentTicks(Element measure, int division, long measureTicks, long measureStart) {
        long maxTicks = 0;
        for (Element voice : directChildren(measure, "voice")) {
            long tick = 0;
            double legacyTupletRatio = 1.0;
            int legacyTupletRemaining = 0;
            Map<String, TupletInfo> tupletsById = new HashMap<String, TupletInfo>();
            for (Element element : voice.children) {
                String tag = element.getTagName();
                if ("Tuplet".equals(tag)) {
                    TupletInfo info = parseTupletInfo(element, tupletsById);
                    if (info != null) {
                        if (info.id.length() > 0) {
                            tupletsById.put(info.id, info);
                        } else {
                            legacyTupletRatio = info.ratio;
                            legacyTupletRemaining = info.actualNotes;
                        }
                    }
                } else if ("endTuplet".equals(tag)) {
                    legacyTupletRatio = 1.0;
                    legacyTupletRemaining = 0;
                } else if ("tick".equals(tag)) {
                    long absoluteTick = fileTick(text(element));
                    tick = Math.max(0, absoluteTick - measureStart);
                } else if ("location".equals(tag)) {
                    tick += locationTicks(element, division, measureTicks);
                } else if ("Rest".equals(tag) || "Chord".equals(tag)) {
                    if ("Chord".equals(tag) && isGraceChord(element)) {
                        continue;
                    }
                    double ratio = tupletRatioForElement(element, tupletsById, legacyTupletRatio);
                    tick += Math.max(0, durationTicks(element, division, ratio, measureTicks));
                    if (legacyTupletRemaining > 0 && firstDirectChild(element, "Tuplet") == null) {
                        legacyTupletRemaining--;
                        if (legacyTupletRemaining <= 0) {
                            legacyTupletRatio = 1.0;
                        }
                    }
                }
            }
            maxTicks = Math.max(maxTicks, tick);
        }
        return maxTicks;
    }

    private static void parseStaffBody(Element staffBody, ScoreData score, TrackData track, MeasureTimeline timeline) {
        List<Element> measures = directChildren(staffBody, "Measure");
        Map<Integer, VoiceState> voiceStates = new HashMap<Integer, VoiceState>();
        for (MeasureSlot slot : timeline.slots) {
            if (slot.measureIndex >= measures.size()) {
                continue;
            }
            Element measure = measures.get(slot.measureIndex);
            long measureStart = slot.outputStart;
            long measureTicks = Math.max(1, slot.length);
            List<Element> voices = directChildren(measure, "voice");

            if (voices.isEmpty()) {
                continue;
            }

            for (int voiceIndex = 0; voiceIndex < voices.size(); voiceIndex++) {
                VoiceState state = voiceStates.get(Integer.valueOf(voiceIndex));
                if (state == null) {
                    state = new VoiceState();
                    state.velocity = DEFAULT_VELOCITY;
                    voiceStates.put(Integer.valueOf(voiceIndex), state);
                }
                state.tick = measureStart;
                state.measureStart = measureStart;
                state.sourceMeasureStart = slot.sourceStart;
                state.measureTicks = measureTicks;
                state.tupletRatio = 1.0;
                state.tupletRemaining = 0;
                state.tupletsById.clear();
                state.pendingGraceChords.clear();
                state.pendingFermataMultiplier = 1.0;
                parseVoice(voices.get(voiceIndex), score, track, state, voiceIndex);
            }
        }

        for (VoiceState state : voiceStates.values()) {
            for (NotePlayback playback : state.activeTies.values()) {
                emitPlayback(track, score, playback);
            }
            state.activeTies.clear();
        }
    }

    private static void parseVoice(Element voice, ScoreData score, TrackData track, VoiceState state, int voiceIndex) {
        for (int childIndex = 0; childIndex < voice.children.size(); childIndex++) {
            Element element = voice.children.get(childIndex);
            String tag = element.getTagName();
            if ("Tempo".equals(tag)) {
                double tempo = doubleText(firstDirectChild(element, "tempo"), -1.0);
                if (tempo > 0.0) {
                    double bpm = tempo * 60.0;
                    state.bpm = bpm;
                    score.tempoEvents.add(new TempoEvent(state.tick, bpm));
                }
            } else if ("TimeSig".equals(tag)) {
                int n = intText(firstDirectChild(element, "sigN"), DEFAULT_TIME_SIG_N);
                int d = intText(firstDirectChild(element, "sigD"), DEFAULT_TIME_SIG_D);
                if (n > 0 && d > 0) {
                    state.lastTimeSigN = n;
                    state.lastTimeSigD = d;
                    score.timeSigEvents.add(new TimeSigEvent(state.tick, n, d));
                }
            } else if ("Dynamic".equals(tag)) {
                state.velocity = clamp(intText(firstDirectChild(element, "velocity"), state.velocity), 1, 127);
            } else if ("location".equals(tag)) {
                state.tick += locationTicks(element, score.division, state.measureTicks);
            } else if ("tick".equals(tag)) {
                long absoluteTick = fileTick(text(element));
                state.tick = state.measureStart + Math.max(0, absoluteTick - state.sourceMeasureStart);
            } else if ("Tuplet".equals(tag)) {
                TupletInfo info = parseTupletInfo(element, state.tupletsById);
                if (info != null) {
                    if (info.id.length() > 0) {
                        state.tupletsById.put(info.id, info);
                    } else {
                        state.tupletRatio = info.ratio;
                        state.tupletRemaining = info.actualNotes;
                    }
                }
            } else if ("endTuplet".equals(tag)) {
                state.tupletRatio = 1.0;
                state.tupletRemaining = 0;
            } else if ("Rest".equals(tag)) {
                long duration = durationTicks(
                        element,
                        score.division,
                        tupletRatioForElement(element, state.tupletsById, state.tupletRatio),
                        state.measureTicks
                );
                state.tick += Math.max(0, applyAndConsumeFermata(state, duration));
                consumeTupletSlot(state, element);
            } else if ("Chord".equals(tag)) {
                long nominalDuration = durationTicks(
                        element,
                        score.division,
                        tupletRatioForElement(element, state.tupletsById, state.tupletRatio),
                        state.measureTicks
                );
                if (isGraceChord(element)) {
                    state.pendingGraceChords.add(element);
                } else {
                    int tremoloPartnerIndex = twoChordTremoloPartnerIndex(voice, childIndex);
                    if (tremoloPartnerIndex >= 0) {
                        Element partner = voice.children.get(tremoloPartnerIndex);
                        long partnerDuration = durationTicks(
                                partner,
                                score.division,
                                tupletRatioForElement(partner, state.tupletsById, state.tupletRatio),
                                state.measureTicks
                        );
                        long playedDuration = applyAndConsumeFermata(state, Math.max(1, nominalDuration + partnerDuration));
                        appendTwoChordTremoloWithGraceNotes(
                                element,
                                partner,
                                score,
                                track,
                                state,
                                voiceIndex,
                                Math.max(1, playedDuration)
                        );
                        state.tick += Math.max(0, playedDuration);
                        consumeTupletSlot(state, element);
                        consumeTupletSlot(state, partner);
                        childIndex = tremoloPartnerIndex;
                    } else {
                        long playedDuration = applyAndConsumeFermata(state, nominalDuration);
                        appendChordWithGraceNotes(element, score, track, state, voiceIndex, Math.max(1, playedDuration));
                        state.tick += Math.max(0, playedDuration);
                        consumeTupletSlot(state, element);
                    }
                    state.pendingGraceChords.clear();
                }
            } else if ("Fermata".equals(tag)) {
                state.pendingFermataMultiplier = Math.max(state.pendingFermataMultiplier, fermataMultiplier(element));
            }
        }
    }

    private static long applyAndConsumeFermata(VoiceState state, long duration) {
        double multiplier = state.pendingFermataMultiplier;
        state.pendingFermataMultiplier = 1.0;
        if (multiplier <= 1.0) {
            return duration;
        }
        return Math.max(1, Math.round(duration * multiplier));
    }

    private static void appendChordWithGraceNotes(
            Element chord,
            ScoreData score,
            TrackData track,
            VoiceState state,
            int voiceIndex,
            long nominalDuration
    ) {
        List<Element> beforeGrace = new ArrayList<Element>();
        List<Element> afterGrace = new ArrayList<Element>();
        for (Element grace : state.pendingGraceChords) {
            if (isGraceAfterChord(grace)) {
                afterGrace.add(grace);
            } else {
                beforeGrace.add(grace);
            }
        }

        GracePlaybackSplit split = gracePlaybackSplit(chord, state, beforeGrace, afterGrace, nominalDuration, score.division);
        long beforeTick = state.tick;
        for (int i = 0; i < beforeGrace.size(); i++) {
            long graceStart = beforeTick + split.beforeEach * i;
            appendChordNotes(beforeGrace.get(i), score, track, state, voiceIndex, graceStart, split.beforeEach);
        }

        long mainStart = state.tick + split.beforeTotal;
        long mainDuration = Math.max(1, nominalDuration - split.beforeTotal - split.afterTotal);
        appendChordNotes(chord, score, track, state, voiceIndex, mainStart, mainDuration);

        long afterTick = state.tick + nominalDuration - split.afterTotal;
        for (int i = 0; i < afterGrace.size(); i++) {
            long graceStart = afterTick + split.afterEach * i;
            appendChordNotes(afterGrace.get(i), score, track, state, voiceIndex, graceStart, split.afterEach);
        }
    }

    private static void appendTwoChordTremoloWithGraceNotes(
            Element firstChord,
            Element secondChord,
            ScoreData score,
            TrackData track,
            VoiceState state,
            int voiceIndex,
            long nominalDuration
    ) {
        List<Element> beforeGrace = new ArrayList<Element>();
        for (Element grace : state.pendingGraceChords) {
            if (!isGraceAfterChord(grace)) {
                beforeGrace.add(grace);
            }
        }

        long beforeTotal = 0;
        long beforeEach = 0;
        if (!beforeGrace.isEmpty() && nominalDuration > 1) {
            beforeTotal = Math.min(nominalDuration - 1, Math.max(1, nominalDuration / 8));
            beforeEach = Math.max(1, beforeTotal / beforeGrace.size());
            beforeTotal = beforeEach * beforeGrace.size();
        }

        long beforeTick = state.tick;
        for (int i = 0; i < beforeGrace.size(); i++) {
            appendChordNotes(beforeGrace.get(i), score, track, state, voiceIndex, beforeTick + beforeEach * i, beforeEach);
        }

        appendTwoChordTremoloNotes(
                firstChord,
                secondChord,
                score,
                track,
                state,
                voiceIndex,
                state.tick + beforeTotal,
                Math.max(1, nominalDuration - beforeTotal)
        );
    }

    private static void appendChordNotes(
            Element chord,
            ScoreData score,
            TrackData track,
            VoiceState state,
            int voiceIndex,
            long chordStartTick,
            long nominalDuration
    ) {
        List<EventTiming> timings = eventTimings(chord, track, chordStartTick, nominalDuration, score.division);
        appendChordNotesWithTimings(chord, score, track, state, voiceIndex, chordStartTick, nominalDuration, timings);
    }

    private static void appendChordNotesWithTimings(
            Element chord,
            ScoreData score,
            TrackData track,
            VoiceState state,
            int voiceIndex,
            long chordStartTick,
            long nominalDuration,
            List<EventTiming> timings
    ) {
        ChordPerformance performance = chordPerformance(chord);
        int gateTimePercent = clamp(track.gateTimePercent * performance.gateTimePercent / 100, 1, 1000);
        List<Element> notes = directChildren(chord, "Note");
        boolean arpeggio = firstDirectChild(chord, "Arpeggio") != null && intText(firstDirectChild(firstDirectChild(chord, "Arpeggio"), "play"), 1) != 0;
        for (int noteIndex = 0; noteIndex < notes.size(); noteIndex++) {
            Element note = notes.get(noteIndex);
            int xmlPitch = intText(firstDirectChild(note, "pitch"), -1);
            if (xmlPitch < 0) {
                continue;
            }
            if (intText(firstDirectChild(note, "play"), 1) == 0) {
                continue;
            }
            double tuning = doubleText(firstDirectChild(note, "tuning"), 0.0);
            int baseVelocity = clamp(noteVelocity(note, state.velocity) + performance.velocityOffset, 1, 127);
            boolean tiePrev = hasTieEndpoint(note, "prev");
            boolean tieNext = hasTieEndpoint(note, "next");
            long arpeggioOffset = arpeggio ? arpeggioOffsetTicks(chord, noteIndex, notes.size(), nominalDuration, score.division) : 0;

            for (EventTiming timing : timings) {
                double eventPitchDelta = timing.pitchDelta;
                NormalizedPitch normalized = normalizeMidxPitchCents(xmlPitch + eventPitchDelta, tuning);
                int nativePitch = clamp((int) Math.round(xmlPitch + eventPitchDelta), 0, 127);
                long startTick = chordStartTick + timing.offsetTicks + arpeggioOffset;
                long endTick = Math.max(startTick + 1, startTick + timing.lengthTicks);
                int velocity = clamp(baseVelocity + hairpinVelocityOffset(track, startTick), 1, 127);
                TieKey key = new TieKey(track.staffId, voiceIndex, xmlPitch, Math.round(tuning * 1000.0) / 1000.0);

                if (tiePrev) {
                    NotePlayback active = state.activeTies.get(key);
                    if (active != null) {
                        active.endTick = Math.max(active.endTick, endTick);
                        if (!tieNext) {
                            state.activeTies.remove(key);
                            emitPlayback(track, score, active);
                        }
                        continue;
                    }
                    if (!tieNext) {
                        continue;
                    }
                }

                NotePlayback playback = new NotePlayback();
                playback.startTick = startTick;
                playback.endTick = endTick;
                playback.pitch = normalized.pitch;
                playback.nativePitch = nativePitch;
                playback.cents = normalized.cents;
                playback.velocity = velocity;
                playback.gateTimePercent = gateTimePercent;

                if (tieNext) {
                    state.activeTies.put(key, playback);
                } else {
                    emitPlayback(track, score, playback);
                }
            }
        }
    }

    private static void appendTwoChordTremoloNotes(
            Element firstChord,
            Element secondChord,
            ScoreData score,
            TrackData track,
            VoiceState state,
            int voiceIndex,
            long chordStartTick,
            long nominalDuration
    ) {
        Element tremolo = firstDirectChild(firstChord, "Tremolo");
        long unit = tremoloUnitTicks(tremoloSubtype(tremolo), score.division);
        long offset = 0;
        int index = 0;
        while (offset < nominalDuration) {
            long length = Math.min(Math.max(1, unit), nominalDuration - offset);
            Element chord = (index % 2 == 0) ? firstChord : secondChord;
            appendChordNotesWithTimings(
                    chord,
                    score,
                    track,
                    state,
                    voiceIndex,
                    chordStartTick + offset,
                    length,
                    singleEventTiming(length)
            );
            offset += length;
            index++;
        }
    }

    private static void emitPlayback(TrackData track, ScoreData score, NotePlayback playback) {
        playback.pitch = clamp(playback.pitch, 0, 127);
        playback.nativePitch = clamp(playback.nativePitch, 0, 127);
        playback.velocity = clamp(playback.velocity, 1, 127);
        int gateTimePercent = playback.gateTimePercent > 0 ? playback.gateTimePercent : track.gateTimePercent;
        long gatedEndTick = gatedEndTick(playback.startTick, playback.endTick, gateTimePercent);
        gatedEndTick = pedalExtendedEndTick(track, playback.startTick, gatedEndTick);
        track.events.add(MidiEvent.noteOn(playback.startTick, playback.pitch, playback.nativePitch, playback.velocity, playback.cents));
        track.events.add(MidiEvent.noteOff(gatedEndTick, playback.nativePitch));
        score.noteCount++;
        if (encodeCentOffset(playback.cents) != 0) {
            score.microtonalCount++;
        }
    }

    private static long gatedEndTick(long startTick, long endTick, int gateTimePercent) {
        long duration = Math.max(1, endTick - startTick);
        long gatedDuration = (long) Math.floor(duration * clamp(gateTimePercent, 1, 1000) / 100.0) - 1;
        return startTick + Math.max(1, gatedDuration);
    }

    private static List<EventTiming> eventTimings(Element chord, TrackData track, long chordStartTick, long nominalDuration, int division) {
        Element events = firstDirectChild(chord, "Events");
        if (events == null) {
            List<EventTiming> ornament = ornamentEventTimings(chord, nominalDuration, division);
            if (ornament != null) {
                return ornament;
            }
            List<EventTiming> trill = trillEventTimings(track, chordStartTick, nominalDuration, division);
            if (trill != null) {
                return trill;
            }
            List<EventTiming> tremolo = tremoloEventTimings(chord, nominalDuration, division);
            if (tremolo != null) {
                return tremolo;
            }
            return singleEventTiming(nominalDuration);
        }

        List<EventTiming> out = new ArrayList<EventTiming>();
        for (Element event : directChildren(events, "Event")) {
            double ontime = doubleText(firstDirectChild(event, "ontime"), 0.0);
            double len = doubleText(firstDirectChild(event, "len"), 1000.0);
            double pitch = doubleText(firstDirectChild(event, "pitch"), 0.0);
            long offsetTicks = Math.round(nominalDuration * ontime / 1000.0);
            long lengthTicks = Math.max(1, Math.round(nominalDuration * len / 1000.0));
            out.add(new EventTiming(offsetTicks, lengthTicks, pitch));
        }
        if (out.isEmpty()) {
            out.add(new EventTiming(0, Math.max(1, nominalDuration), 0.0));
        }
        return out;
    }

    private static List<EventTiming> singleEventTiming(long nominalDuration) {
        List<EventTiming> single = new ArrayList<EventTiming>();
        single.add(new EventTiming(0, Math.max(1, nominalDuration), 0.0));
        return single;
    }

    private static GracePlaybackSplit gracePlaybackSplit(
            Element mainChord,
            VoiceState state,
            List<Element> beforeGrace,
            List<Element> afterGrace,
            long nominalDuration,
            int division
    ) {
        GracePlaybackSplit split = new GracePlaybackSplit();
        int beforeCount = beforeGrace.size();
        int afterCount = afterGrace.size();
        if (beforeCount + afterCount == 0 || nominalDuration <= 1) {
            return split;
        }

        int dots = intText(firstDirectChild(mainChord, "dots"), 0);
        long dottedShare = Math.round(nominalDuration * fermataBaseGraceRatio(dots));
        boolean acciaccaturaLike = beforeCount > 1
                || (beforeCount == 1 && firstDirectChild(beforeGrace.get(0), "acciaccatura") != null);

        if (beforeCount > 0) {
            if (acciaccaturaLike) {
                long ticksFor65msEach = Math.max(1, Math.round((Math.max(1.0, state.bpm) / 60.0) * division * 0.065 * beforeCount));
                split.beforeTotal = Math.min(Math.max(1, nominalDuration / 2), ticksFor65msEach);
            } else if (afterCount > 0) {
                split.beforeTotal = Math.round(dottedShare * (beforeCount / (double) (beforeCount + afterCount)));
            } else {
                split.beforeTotal = dottedShare;
            }
        }

        if (afterCount > 0) {
            if (beforeCount > 0 && !acciaccaturaLike) {
                split.afterTotal = Math.round(dottedShare * (afterCount / (double) (beforeCount + afterCount)));
            } else {
                split.afterTotal = dottedShare;
            }
        }

        long maxGrace = Math.max(0, nominalDuration - 1);
        long totalGrace = split.beforeTotal + split.afterTotal;
        if (totalGrace > maxGrace && totalGrace > 0) {
            double scale = maxGrace / (double) totalGrace;
            split.beforeTotal = Math.max(0, Math.round(split.beforeTotal * scale));
            split.afterTotal = Math.max(0, Math.round(split.afterTotal * scale));
        }
        split.beforeEach = beforeCount == 0 ? 0 : Math.max(1, split.beforeTotal / beforeCount);
        split.afterEach = afterCount == 0 ? 0 : Math.max(1, split.afterTotal / afterCount);
        split.beforeTotal = split.beforeEach * beforeCount;
        split.afterTotal = split.afterEach * afterCount;
        if (split.beforeTotal + split.afterTotal >= nominalDuration) {
            long overflow = split.beforeTotal + split.afterTotal - nominalDuration + 1;
            if (split.afterTotal >= overflow) {
                split.afterTotal -= overflow;
                split.afterEach = afterCount == 0 ? 0 : Math.max(1, split.afterTotal / afterCount);
                split.afterTotal = split.afterEach * afterCount;
            } else {
                split.beforeTotal = Math.max(0, split.beforeTotal - overflow);
                split.beforeEach = beforeCount == 0 ? 0 : Math.max(1, split.beforeTotal / beforeCount);
                split.beforeTotal = split.beforeEach * beforeCount;
            }
        }
        return split;
    }

    private static double fermataBaseGraceRatio(int dots) {
        if (dots == 1) {
            return 0.667;
        }
        if (dots >= 2) {
            return 0.571;
        }
        return 0.5;
    }

    private static boolean isGraceChord(Element chord) {
        return firstDirectChild(chord, "acciaccatura") != null
                || firstDirectChild(chord, "appoggiatura") != null
                || firstDirectChild(chord, "grace4") != null
                || firstDirectChild(chord, "grace16") != null
                || firstDirectChild(chord, "grace32") != null
                || isGraceAfterChord(chord);
    }

    private static boolean isGraceAfterChord(Element chord) {
        return firstDirectChild(chord, "grace8after") != null
                || firstDirectChild(chord, "grace16after") != null
                || firstDirectChild(chord, "grace32after") != null;
    }

    private static ChordPerformance chordPerformance(Element chord) {
        ChordPerformance performance = new ChordPerformance();
        performance.gateTimePercent = 100;
        performance.velocityOffset = 0;
        for (Element articulation : directChildren(chord, "Articulation")) {
            if (intText(firstDirectChild(articulation, "play"), 1) == 0) {
                continue;
            }
            String subtype = articulationName(articulation);
            String lower = subtype.toLowerCase(Locale.ROOT);
            if (lower.contains("staccatissimo")) {
                performance.gateTimePercent = Math.min(performance.gateTimePercent, 25);
            } else if (lower.contains("staccato")) {
                performance.gateTimePercent = Math.min(performance.gateTimePercent, 50);
            } else if (lower.contains("portato") || lower.contains("tenutostaccato")) {
                performance.gateTimePercent = Math.min(performance.gateTimePercent, 75);
            }

            if (lower.contains("marcato") || lower.contains("sforzato")) {
                performance.velocityOffset += 22;
            } else if (lower.contains("accent")) {
                performance.velocityOffset += lower.contains("soft") ? 6 : 14;
            }
        }
        performance.velocityOffset = clamp(performance.velocityOffset, -64, 64);
        return performance;
    }

    private static String articulationName(Element articulation) {
        String subtype = text(firstDirectChild(articulation, "subtype"));
        if (subtype.length() > 0) {
            return subtype;
        }
        return articulation.getAttribute("name");
    }

    private static List<EventTiming> ornamentEventTimings(Element chord, long nominalDuration, int division) {
        for (Element articulation : directChildren(chord, "Articulation")) {
            if (intText(firstDirectChild(articulation, "play"), 1) == 0) {
                continue;
            }
            OrnamentPattern pattern = ornamentPattern(articulationName(articulation));
            if (pattern != null) {
                return patternEventTimings(pattern.pitchDeltas, pattern.repeat, pattern.sustainLast, nominalDuration, pattern.unitTicks(division));
            }
        }
        return null;
    }

    private static List<EventTiming> tremoloEventTimings(Element chord, long nominalDuration, int division) {
        Element tremolo = firstDirectChild(chord, "Tremolo");
        if (tremolo == null || intText(firstDirectChild(tremolo, "play"), 1) == 0) {
            return null;
        }
        String subtype = tremoloSubtype(tremolo);
        long unit = tremoloUnitTicks(subtype, division);
        return repeatedPitchTiming(0.0, nominalDuration, unit);
    }

    private static List<EventTiming> trillEventTimings(TrackData track, long chordStartTick, long nominalDuration, int division) {
        if (track == null || nominalDuration <= 1) {
            return null;
        }
        long chordEndTick = chordStartTick + nominalDuration;
        for (OrnamentRange range : track.trillRanges) {
            if (range.endTick <= chordStartTick || range.startTick >= chordEndTick) {
                continue;
            }
            OrnamentPattern pattern = trillPattern(range.name);
            if (pattern == null) {
                continue;
            }
            long activeStart = Math.max(0, range.startTick - chordStartTick);
            long activeEnd = Math.min(nominalDuration, range.endTick - chordStartTick);
            if (activeEnd <= activeStart) {
                continue;
            }
            List<EventTiming> out = new ArrayList<EventTiming>();
            if (activeStart > 0) {
                out.add(new EventTiming(0, activeStart, 0.0));
            }
            for (EventTiming timing : patternEventTimings(
                    pattern.pitchDeltas,
                    pattern.repeat,
                    pattern.sustainLast,
                    activeEnd - activeStart,
                    pattern.unitTicks(division)
            )) {
                out.add(new EventTiming(activeStart + timing.offsetTicks, timing.lengthTicks, timing.pitchDelta));
            }
            if (activeEnd < nominalDuration) {
                out.add(new EventTiming(activeEnd, nominalDuration - activeEnd, 0.0));
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    private static OrnamentPattern trillPattern(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.length() == 0 || lower.contains("trill")) {
            return new OrnamentPattern(new int[]{0, 1}, true, true, 32);
        }
        if (lower.contains("upprall")) {
            return new OrnamentPattern(new int[]{-1, 0, 1, 0}, true, true, 16);
        }
        if (lower.contains("downprall") || lower.contains("downmordent")) {
            return new OrnamentPattern(new int[]{1, 0, 1, 0, -1, 0}, true, true, 16);
        }
        if (lower.contains("prallprall")) {
            return new OrnamentPattern(new int[]{0, 1}, true, true, 16);
        }
        return ornamentPattern("ornament" + name);
    }

    private static String tremoloSubtype(Element tremolo) {
        return text(firstDirectChild(tremolo, "subtype")).toLowerCase(Locale.ROOT);
    }

    private static long tremoloUnitTicks(String subtype, int division) {
        int denominator = 16;
        int numeric = parseInt(subtype, -1);
        if (numeric == 7 || numeric == 2 || subtype.contains("32") || subtype.contains("three") || subtype.contains("r32") || subtype.contains("c32")) {
            denominator = 32;
        } else if (numeric == 8 || numeric == 3 || subtype.contains("64") || subtype.contains("four") || subtype.contains("r64") || subtype.contains("c64")) {
            denominator = 64;
        } else if (numeric == 5 || numeric == 0 || subtype.contains("8") || subtype.contains("one") || subtype.contains("r8") || subtype.contains("c8")) {
            denominator = 8;
        }
        return Math.max(1, Math.round(division * 4.0 / denominator));
    }

    private static boolean isTwoChordTremolo(Element chord) {
        Element tremolo = firstDirectChild(chord, "Tremolo");
        if (tremolo == null || intText(firstDirectChild(tremolo, "play"), 1) == 0) {
            return false;
        }
        String subtype = tremoloSubtype(tremolo);
        int numeric = parseInt(subtype, -1);
        return numeric >= 5
                || subtype.startsWith("c")
                || subtype.contains("two")
                || subtype.contains("change");
    }

    private static int twoChordTremoloPartnerIndex(Element voice, int chordIndex) {
        Element chord = chordIndex >= 0 && chordIndex < voice.children.size() ? voice.children.get(chordIndex) : null;
        if (chord == null || !isTwoChordTremolo(chord)) {
            return -1;
        }
        int nextIndex = chordIndex + 1;
        if (nextIndex >= voice.children.size()) {
            return -1;
        }
        Element next = voice.children.get(nextIndex);
        return "Chord".equals(next.getTagName()) && !isGraceChord(next) ? nextIndex : -1;
    }

    private static OrnamentPattern ornamentPattern(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!lower.contains("ornament")) {
            return null;
        }
        if (lower.contains("turninverted") || lower.contains("turnslash")) {
            return new OrnamentPattern(new int[]{-1, 0, 1, 0}, false, true, 32);
        }
        if (lower.contains("turn")) {
            return new OrnamentPattern(new int[]{1, 0, -1, 0}, false, true, 32);
        }
        if (lower.contains("shorttrill")) {
            return new OrnamentPattern(new int[]{0, 1, 0}, false, true, 32);
        }
        if (lower.contains("trill") || lower.contains("tremblement")) {
            return new OrnamentPattern(new int[]{0, 1}, true, true, 32);
        }
        if (lower.contains("prallmordent")) {
            return new OrnamentPattern(new int[]{1, 0, -1, 0}, false, true, 32);
        }
        if (lower.contains("mordent") && !lower.contains("upmordent") && !lower.contains("downmordent")) {
            return new OrnamentPattern(new int[]{0, -1, 0}, false, true, 32);
        }
        if (lower.contains("lineprall")) {
            return new OrnamentPattern(new int[]{1, 0}, true, true, 16);
        }
        if (lower.contains("upprall") || lower.contains("upmordent")) {
            return new OrnamentPattern(new int[]{-1, 0, 1, 0}, true, true, 16);
        }
        if (lower.contains("downmordent")) {
            return new OrnamentPattern(new int[]{1, 0, 1, 0, -1, 0}, true, true, 16);
        }
        if (lower.contains("pralldown")) {
            return new OrnamentPattern(new int[]{1, 0, 1, 0, -1, 0, 0, 0}, true, true, 16);
        }
        if (lower.contains("prallup")) {
            return new OrnamentPattern(new int[]{1, 0, 1, 0}, true, true, 16);
        }
        if (lower.contains("precompmordentupperprefix")) {
            return new OrnamentPattern(new int[]{1, 1, 1, 0, 1, 0}, true, true, 16);
        }
        return null;
    }

    private static List<EventTiming> patternEventTimings(int[] pitchDeltas, boolean repeat, boolean sustainLast, long nominalDuration, long unitTicks) {
        if (pitchDeltas == null || pitchDeltas.length == 0 || nominalDuration <= 1) {
            return singleEventTiming(nominalDuration);
        }
        if (repeat) {
            List<EventTiming> out = new ArrayList<EventTiming>();
            long offset = 0;
            int index = 0;
            while (offset < nominalDuration) {
                long length = Math.min(Math.max(1, unitTicks), nominalDuration - offset);
                out.add(new EventTiming(offset, length, pitchDeltas[index % pitchDeltas.length]));
                offset += length;
                index++;
            }
            return out;
        }

        List<EventTiming> out = new ArrayList<EventTiming>();
        long offset = 0;
        long step = Math.max(1, Math.min(Math.max(1, unitTicks), nominalDuration / pitchDeltas.length));
        for (int i = 0; i < pitchDeltas.length; i++) {
            long length = i == pitchDeltas.length - 1 && sustainLast
                    ? Math.max(1, nominalDuration - offset)
                    : Math.min(step, Math.max(1, nominalDuration - offset));
            out.add(new EventTiming(offset, length, pitchDeltas[i]));
            offset += length;
            if (offset >= nominalDuration) {
                break;
            }
        }
        return out.isEmpty() ? singleEventTiming(nominalDuration) : out;
    }

    private static List<EventTiming> repeatedPitchTiming(double pitchDelta, long nominalDuration, long unitTicks) {
        List<EventTiming> out = new ArrayList<EventTiming>();
        long offset = 0;
        while (offset < nominalDuration) {
            long length = Math.min(Math.max(1, unitTicks), nominalDuration - offset);
            out.add(new EventTiming(offset, length, pitchDelta));
            offset += length;
        }
        return out.isEmpty() ? singleEventTiming(nominalDuration) : out;
    }

    private static long arpeggioOffsetTicks(Element chord, int noteIndex, int noteCount, long nominalDuration, int division) {
        if (noteCount <= 1) {
            return 0;
        }
        Element arpeggio = firstDirectChild(chord, "Arpeggio");
        String subtype = text(firstDirectChild(arpeggio, "subtype")).toLowerCase(Locale.ROOT);
        boolean down = subtype.contains("down");
        int order = down ? (noteCount - noteIndex - 1) : noteIndex;
        long step = Math.max(1, Math.min(Math.max(1, division / 32L), Math.max(1, nominalDuration / (noteCount * 8L))));
        return order * step;
    }

    private static int hairpinVelocityOffset(TrackData track, long tick) {
        int offset = 0;
        for (HairpinRange range : track.hairpins) {
            if (tick < range.startTick || tick > range.endTick) {
                continue;
            }
            long span = Math.max(1, range.endTick - range.startTick);
            double progress = (tick - range.startTick) / (double) span;
            offset += (int) Math.round(range.velocityDelta * progress);
        }
        return clamp(offset, -64, 64);
    }

    private static int noteVelocity(Element note, int inheritedVelocity) {
        Element velocityElement = firstDirectChild(note, "velocity");
        Element veloTypeElement = firstDirectChild(note, "veloType");
        Element veloOffsetElement = firstDirectChild(note, "veloOffset");

        int velocity = inheritedVelocity;
        if (velocityElement != null) {
            velocity = intText(velocityElement, velocity);
        } else if (veloOffsetElement != null) {
            velocity += intText(veloOffsetElement, 0);
        }

        String veloType = veloTypeElement == null ? "" : text(veloTypeElement);
        if ("offset".equalsIgnoreCase(veloType) && velocityElement != null) {
            velocity = inheritedVelocity + intText(velocityElement, 0);
        }
        return clamp(velocity, 1, 127);
    }

    private static boolean hasTieEndpoint(Element note, String endpoint) {
        for (Element spanner : directChildren(note, "Spanner")) {
            if ("Tie".equals(spanner.getAttribute("type")) && firstDirectChild(spanner, endpoint) != null) {
                return true;
            }
        }
        return false;
    }

    private static double fermataMultiplier(Element fermata) {
        String subtype = text(firstDirectChild(fermata, "subtype")).toLowerCase(Locale.ROOT);
        if (subtype.contains("verylong")) {
            return 3.0;
        }
        if (subtype.contains("long")) {
            return 2.0;
        }
        if (subtype.contains("short")) {
            return 1.25;
        }
        return 1.5;
    }

    private static void collectPlaybackSpanners(Element staffBody, TrackData track, MeasureTimeline timeline, int division) {
        Map<String, HairpinRange> activeHairpins = new HashMap<String, HairpinRange>();
        List<HairpinRange> closedHairpins = new ArrayList<HairpinRange>();
        List<Element> measures = directChildren(staffBody, "Measure");
        for (MeasureSlot slot : timeline.slots) {
            if (slot.measureIndex >= measures.size()) {
                continue;
            }
            Element measure = measures.get(slot.measureIndex);
            scanMeasureLevelSpanners(measure, activeHairpins, closedHairpins, slot.outputStart, slot.length, division);
            for (Element voice : directChildren(measure, "voice")) {
                scanVoiceSpanners(voice, track, activeHairpins, closedHairpins, slot, division);
            }
        }
        track.hairpins.addAll(closedHairpins);
        for (HairpinRange range : activeHairpins.values()) {
            if (range.endTick <= range.startTick) {
                range.endTick = range.startTick + DEFAULT_DIVISION;
            }
            track.hairpins.add(range);
        }
    }

    private static void scanMeasureLevelSpanners(
            Element measure,
            Map<String, HairpinRange> activeHairpins,
            List<HairpinRange> closedHairpins,
            long measureStart,
            long measureTicks,
            int division
    ) {
        for (Element child : measure.children) {
            if ("endSpanner".equals(child.getTagName())) {
                closeHairpin(activeHairpins, closedHairpins, child.getAttribute("id"), measureStart + childLocationOffset(child, division, measureTicks));
            }
        }
    }

    private static void scanVoiceSpanners(
            Element voice,
            TrackData track,
            Map<String, HairpinRange> activeHairpins,
            List<HairpinRange> closedHairpins,
            MeasureSlot slot,
            int division
    ) {
        long tick = slot.outputStart;
        double legacyTupletRatio = 1.0;
        int legacyTupletRemaining = 0;
        Map<String, TupletInfo> tupletsById = new HashMap<String, TupletInfo>();
        for (Element element : voice.children) {
            String tag = element.getTagName();
            if ("Tuplet".equals(tag)) {
                TupletInfo info = parseTupletInfo(element, tupletsById);
                if (info != null) {
                    if (info.id.length() > 0) {
                        tupletsById.put(info.id, info);
                    } else {
                        legacyTupletRatio = info.ratio;
                        legacyTupletRemaining = info.actualNotes;
                    }
                }
            } else if ("endTuplet".equals(tag)) {
                legacyTupletRatio = 1.0;
                legacyTupletRemaining = 0;
            } else if ("tick".equals(tag)) {
                long absoluteTick = fileTick(text(element));
                tick = slot.outputStart + Math.max(0, absoluteTick - slot.sourceStart);
            } else if ("location".equals(tag)) {
                tick += locationTicks(element, division, slot.length);
            } else if ("HairPin".equals(tag)) {
                String id = element.getAttribute("id");
                if (id.length() > 0) {
                    HairpinRange range = new HairpinRange();
                    range.startTick = tick;
                    range.endTick = tick + slot.length;
                    range.velocityDelta = hairpinVelocityDelta(element);
                    activeHairpins.put(id, range);
                }
            } else if ("Spanner".equals(tag) && "Pedal".equals(element.getAttribute("type"))) {
                addPedalRangeFromSpanner(element, track, tick, slot.length, division);
            } else if ("Spanner".equals(tag) && "Trill".equals(element.getAttribute("type"))) {
                addTrillRangeFromSpanner(element, track, tick, slot.length, division);
            } else if ("endSpanner".equals(tag)) {
                closeHairpin(activeHairpins, closedHairpins, element.getAttribute("id"), tick + childLocationOffset(element, division, slot.length));
            } else if ("Rest".equals(tag) || "Chord".equals(tag)) {
                if ("Chord".equals(tag) && isGraceChord(element)) {
                    continue;
                }
                double ratio = tupletRatioForElement(element, tupletsById, legacyTupletRatio);
                tick += Math.max(0, durationTicks(element, division, ratio, slot.length));
                if (legacyTupletRemaining > 0 && firstDirectChild(element, "Tuplet") == null) {
                    legacyTupletRemaining--;
                    if (legacyTupletRemaining <= 0) {
                        legacyTupletRatio = 1.0;
                    }
                }
            }
        }
    }

    private static int hairpinVelocityDelta(Element hairpin) {
        int delta = Math.max(1, intText(firstDirectChild(hairpin, "veloChange"), 15));
        String subtype = text(firstDirectChild(hairpin, "subtype")).toLowerCase(Locale.ROOT);
        if ("1".equals(subtype) || subtype.contains("decresc") || subtype.contains("dim")) {
            return -delta;
        }
        return delta;
    }

    private static void closeHairpin(
            Map<String, HairpinRange> activeHairpins,
            List<HairpinRange> closedHairpins,
            String id,
            long endTick
    ) {
        if (id == null || id.length() == 0) {
            return;
        }
        HairpinRange range = activeHairpins.remove(id);
        if (range != null) {
            range.endTick = Math.max(range.startTick + 1, endTick);
            closedHairpins.add(range);
        }
    }

    private static void addPedalRangeFromSpanner(Element spanner, TrackData track, long startTick, long measureTicks, int division) {
        if (firstDirectChild(spanner, "Pedal") == null || firstDirectChild(spanner, "next") == null) {
            return;
        }
        Element next = firstDirectChild(spanner, "next");
        Element location = firstDirectChild(next, "location");
        long offset = locationTicks(location, division, measureTicks);
        long endTick = startTick + Math.max(1, offset);
        PedalRange range = new PedalRange();
        range.startTick = startTick;
        range.endTick = endTick;
        track.pedalRanges.add(range);
        track.events.add(MidiEvent.controlChange(startTick, MIDI_CONTROL_SUSTAIN, 127));
        track.events.add(MidiEvent.controlChange(endTick, MIDI_CONTROL_SUSTAIN, 0));
    }

    private static void addTrillRangeFromSpanner(Element spanner, TrackData track, long startTick, long measureTicks, int division) {
        Element trill = firstDirectChild(spanner, "Trill");
        if (trill == null || firstDirectChild(spanner, "prev") != null) {
            return;
        }
        Element next = firstDirectChild(spanner, "next");
        long length = locationTicks(firstDirectChild(next, "location"), division, measureTicks);
        if (length <= 0) {
            length = Math.max(1, measureTicks);
        }
        OrnamentRange range = new OrnamentRange();
        range.startTick = startTick;
        range.endTick = startTick + Math.max(1, length);
        range.name = text(firstDirectChild(trill, "subtype"));
        track.trillRanges.add(range);
    }

    private static long pedalExtendedEndTick(TrackData track, long startTick, long endTick) {
        long out = endTick;
        for (PedalRange range : track.pedalRanges) {
            if (startTick < range.endTick && out >= range.startTick && out < range.endTick) {
                out = range.endTick;
            }
        }
        return Math.max(endTick, out);
    }

    private static long childLocationOffset(Element element, int division, long measureTicks) {
        Element location = firstDirectChild(element, "location");
        return locationTicks(location, division, measureTicks);
    }

    private static long locationTicks(Element location, int division, long measureTicks) {
        Element fractions = firstDirectChild(location, "fractions");
        long ticks = 0;
        Element measures = firstDirectChild(location, "measures");
        if (measures != null) {
            ticks += intText(measures, 0) * Math.max(1, measureTicks);
        }
        if (fractions != null) {
            ticks += ratioTicks(text(fractions), division);
        }
        return ticks;
    }

    private static TupletInfo parseTupletInfo(Element tuplet, Map<String, TupletInfo> knownTuplets) {
        int normal = intText(firstDirectChild(tuplet, "normalNotes"), 0);
        int actual = intText(firstDirectChild(tuplet, "actualNotes"), 0);
        if (normal <= 0 || actual <= 0) {
            return null;
        }
        double ratio = ((double) normal) / ((double) actual);
        String parentId = text(firstDirectChild(tuplet, "Tuplet"));
        TupletInfo parent = knownTuplets == null ? null : knownTuplets.get(parentId);
        if (parent != null) {
            ratio *= parent.ratio;
        }
        String id = tuplet.getAttribute("id");
        return new TupletInfo(id == null ? "" : id, ratio, actual);
    }

    private static double tupletRatioForElement(
            Element element,
            Map<String, TupletInfo> knownTuplets,
            double fallbackRatio
    ) {
        Element tupletRef = firstDirectChild(element, "Tuplet");
        if (tupletRef != null && knownTuplets != null) {
            TupletInfo info = knownTuplets.get(text(tupletRef));
            if (info != null) {
                return info.ratio;
            }
        }
        return fallbackRatio;
    }

    private static long ticksForTimeSignature(int division, int sigN, int sigD) {
        if (sigN <= 0 || sigD <= 0) {
            sigN = DEFAULT_TIME_SIG_N;
            sigD = DEFAULT_TIME_SIG_D;
        }
        return Math.round(division * 4.0 * sigN / sigD);
    }

    private static long durationTicks(Element element, int division, double tupletRatio, long measureTicks) {
        Element explicitDuration = firstDirectChild(element, "duration");
        if (explicitDuration != null) {
            long ticks = ratioTicks(text(explicitDuration), division);
            if (ticks > 0) {
                return Math.max(1, Math.round(ticks * tupletRatio));
            }
        }

        String durationType = text(firstDirectChild(element, "durationType"));
        long base = durationTypeTicks(durationType, division, measureTicks);
        int dots = intText(firstDirectChild(element, "dots"), 0);
        double multiplier = 1.0;
        double add = 0.5;
        for (int i = 0; i < dots; i++) {
            multiplier += add;
            add *= 0.5;
        }
        return Math.max(1, Math.round(base * multiplier * tupletRatio));
    }

    private static long durationTypeTicks(String durationType, int division, long measureTicks) {
        if (durationType == null) {
            return division;
        }
        String type = durationType.trim().toLowerCase(Locale.ROOT);
        if ("measure".equals(type)) {
            return measureTicks > 0 ? measureTicks : division * 4L;
        }
        if ("longa".equals(type)) {
            return division * 16L;
        }
        if ("breve".equals(type)) {
            return division * 8L;
        }
        if ("whole".equals(type)) {
            return division * 4L;
        }
        if ("half".equals(type)) {
            return division * 2L;
        }
        if ("quarter".equals(type)) {
            return division;
        }
        if ("eighth".equals(type)) {
            return Math.max(1, division / 2L);
        }
        if ("16th".equals(type)) {
            return Math.max(1, division / 4L);
        }
        if ("32nd".equals(type)) {
            return Math.max(1, division / 8L);
        }
        if ("64th".equals(type)) {
            return Math.max(1, division / 16L);
        }
        if ("128th".equals(type)) {
            return Math.max(1, division / 32L);
        }
        if ("256th".equals(type)) {
            return Math.max(1, division / 64L);
        }
        if (type.endsWith("th")) {
            int denominator = parseInt(type.substring(0, type.length() - 2), 4);
            if (denominator > 0) {
                return Math.max(1, Math.round(division * 4.0 / denominator));
            }
        }
        return division;
    }

    private static long ratioTicks(String text, int division) {
        if (text == null) {
            return 0;
        }
        String value = text.trim();
        if (value.length() == 0) {
            return 0;
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            double numerator = parseDouble(value.substring(0, slash), 0.0);
            double denominator = parseDouble(value.substring(slash + 1), 1.0);
            if (denominator != 0.0) {
                return Math.round(division * 4.0 * numerator / denominator);
            }
        }
        return Math.round(parseDouble(value, 0.0) * division);
    }

    private static long fileTick(String text) {
        return Math.round(parseDouble(text, 0.0));
    }

    private static void consumeTupletSlot(VoiceState state, Element element) {
        if (firstDirectChild(element, "Tuplet") != null) {
            return;
        }
        if (state.tupletRemaining > 0) {
            state.tupletRemaining--;
            if (state.tupletRemaining <= 0) {
                state.tupletRatio = 1.0;
            }
        }
    }

    private static NormalizedPitch normalizeMidxPitchCents(double pitch, double cents) {
        if (Double.isNaN(pitch) || Double.isInfinite(pitch)) {
            pitch = 0.0;
        }
        if (Double.isNaN(cents) || Double.isInfinite(cents)) {
            cents = 0.0;
        }

        int targetPitch = (int) Math.round(pitch);
        double residualCents = cents + (pitch - targetPitch) * 100.0;
        int guard = 0;

        while (residualCents > MIDX_SAFE_CENT_RANGE && guard < 512) {
            targetPitch += 1;
            residualCents -= 100.0;
            guard++;
        }
        while (residualCents < -MIDX_SAFE_CENT_RANGE && guard < 512) {
            targetPitch -= 1;
            residualCents += 100.0;
            guard++;
        }
        if (Math.abs(residualCents) < 0.000001) {
            residualCents = 0.0;
        }
        NormalizedPitch out = new NormalizedPitch();
        out.pitch = clamp(targetPitch, 0, 127);
        out.cents = residualCents;
        return out;
    }

    private static int encodeCentOffset(double cents) {
        if (Double.isNaN(cents) || Double.isInfinite(cents)) {
            cents = 0.0;
        }
        int sign = cents < 0.0 ? 0x8000 : 0;
        int magnitude = (int) Math.round(Math.abs(cents) / MIDX_CENT_RANGE * MIDX_OFFSET_STEPS);
        if (magnitude > 0x7FFF) {
            magnitude = 0x7FFF;
        }
        return sign | magnitude;
    }

    private static byte[] writeMidiFile(ScoreData score, boolean includeMidxExtensions) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<TrackData> tracks = score.tracksWithEvents();
        writeChunk(out, "MThd", headerData(score.division, Math.max(1, tracks.size())));
        if (tracks.isEmpty()) {
            writeChunk(out, "MTrk", mergedTrackData(null, score, true, includeMidxExtensions));
        } else {
            for (int i = 0; i < tracks.size(); i++) {
                writeChunk(out, "MTrk", mergedTrackData(tracks.get(i), score, i == 0, includeMidxExtensions));
            }
        }
        return out.toByteArray();
    }

    private static byte[] headerData(int division, int trackCount) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, 1);
        writeU16(out, trackCount);
        writeU16(out, clamp(division, 1, 0x7FFF));
        return out.toByteArray();
    }

    private static List<MetaTickEvent> metaEvents(ScoreData score) {
        List<MetaTickEvent> events = new ArrayList<MetaTickEvent>();
        for (TempoEvent tempo : score.tempoEvents) {
            events.add(new MetaTickEvent(tempo.tick, 0, tempo));
        }
        for (TimeSigEvent sig : score.timeSigEvents) {
            events.add(new MetaTickEvent(sig.tick, 1, sig));
        }
        Collections.sort(events, new Comparator<MetaTickEvent>() {
            public int compare(MetaTickEvent a, MetaTickEvent b) {
                if (a.tick != b.tick) {
                    return a.tick < b.tick ? -1 : 1;
                }
                return a.order - b.order;
            }
        });
        return events;
    }

    private static void writeMetaEvent(ByteArrayOutputStream out, MetaTickEvent event) throws IOException {
        if (event.payload instanceof TempoEvent) {
            TempoEvent tempo = (TempoEvent) event.payload;
            int mpqn = (int) Math.round(60000000.0 / Math.max(1.0, Math.min(1000.0, tempo.bpm)));
            out.write(0xFF);
            out.write(0x51);
            out.write(0x03);
            writeU24(out, mpqn);
        } else if (event.payload instanceof TimeSigEvent) {
            TimeSigEvent sig = (TimeSigEvent) event.payload;
            out.write(0xFF);
            out.write(0x58);
            out.write(0x04);
            out.write(clamp(sig.numerator, 1, 255));
            out.write(timeSigDenominatorPower(sig.denominator));
            out.write(24);
            out.write(8);
        }
    }

    private static int timeSigDenominatorPower(int denominator) {
        int value = 1;
        int power = 0;
        while (value < denominator && power < 8) {
            value <<= 1;
            power++;
        }
        return power;
    }

    private static byte[] mergedTrackData(
            TrackData track,
            ScoreData score,
            boolean includeMeta,
            boolean includeMidxExtensions
    ) throws IOException {
        List<TrackTickEvent> events = new ArrayList<TrackTickEvent>();
        if (includeMeta) {
            for (MetaTickEvent event : metaEvents(score)) {
                events.add(TrackTickEvent.meta(event));
            }
        }
        if (track != null) {
            for (MidiEvent event : track.events) {
                events.add(TrackTickEvent.midi(event));
            }
        }
        Collections.sort(events, new Comparator<TrackTickEvent>() {
            public int compare(TrackTickEvent a, TrackTickEvent b) {
                if (a.tick != b.tick) {
                    return a.tick < b.tick ? -1 : 1;
                }
                if (a.order != b.order) {
                    return a.order - b.order;
                }
                return a.pitch - b.pitch;
            }
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (track != null && track.trackName != null && track.trackName.length() > 0) {
            writeVlq(out, 0);
            writeTextMeta(out, 0x03, track.trackName);
        }
        if (track != null && track.instrumentName != null && track.instrumentName.length() > 0) {
            writeVlq(out, 0);
            writeTextMeta(out, 0x04, track.instrumentName);
        }

        if (track != null && track.writeProgramChange) {
            if (track.bankMsb >= 0) {
                writeVlq(out, 0);
                out.write(0xB0 | (track.channel & 0x0F));
                out.write(0x00);
                out.write(track.bankMsb & 0x7F);
            }
            if (track.bankLsb >= 0) {
                writeVlq(out, 0);
                out.write(0xB0 | (track.channel & 0x0F));
                out.write(0x20);
                out.write(track.bankLsb & 0x7F);
            }
            writeVlq(out, 0);
            out.write(0xC0 | (track.channel & 0x0F));
            out.write(track.program & 0x7F);
        }

        long previousTick = 0;
        for (TrackTickEvent event : events) {
            long tick = Math.max(0, event.tick);
            writeVlq(out, tick - previousTick);
            if (event.meta != null) {
                writeMetaEvent(out, event.meta);
            } else {
                MidiEvent midi = event.midi;
                if (includeMidxExtensions && midi.kind == MidiEvent.KIND_NOTE_ON && encodeCentOffset(midi.cents) != 0) {
                    writeMidxOffsetExtension(out, midi.pitch, midi.cents);
                    writeVlq(out, 0);
                }
                if (midi.kind == MidiEvent.KIND_NOTE_OFF) {
                    out.write(0x80 | (track.channel & 0x0F));
                    out.write(midi.nativePitch & 0x7F);
                    out.write(0x00);
                } else if (midi.kind == MidiEvent.KIND_CONTROL) {
                    out.write(0xB0 | (track.channel & 0x0F));
                    out.write(midi.controller & 0x7F);
                    out.write(midi.value & 0x7F);
                } else {
                    out.write(0x90 | (track.channel & 0x0F));
                    out.write(midi.nativePitch & 0x7F);
                    out.write(midi.velocity & 0x7F);
                }
            }
            previousTick = tick;
        }

        writeVlq(out, 0);
        out.write(0xFF);
        out.write(0x2F);
        out.write(0x00);
        return out.toByteArray();
    }

    private static void writeMidxOffsetExtension(ByteArrayOutputStream out, int pitch, double cents) throws IOException {
        out.write(0xFF);
        out.write(MIDX_META_TYPE);
        out.write(MIDX_PAYLOAD_LEN);
        out.write(MIDX_EXPERIMENTAL_MANUFACTURER_ID);
        out.write('X');
        out.write('T');
        out.write(MIDX_PITCHED_OFFSET_RECORD_TYPE);
        out.write(clamp(pitch, 0, 127));
        writeU16(out, encodeCentOffset(cents));
    }

    private static void writeTextMeta(ByteArrayOutputStream out, int metaType, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(0xFF);
        out.write(metaType & 0x7F);
        writeVlq(out, bytes.length);
        out.write(bytes);
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        for (int i = 0; i < type.length(); i++) {
            out.write(type.charAt(i) & 0xFF);
        }
        writeU32(out, data.length);
        out.write(data);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU24(ByteArrayOutputStream out, int value) {
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static void writeVlq(ByteArrayOutputStream out, long value) {
        value = Math.max(0, Math.min(0x0FFFFFFFL, value));
        int[] stack = new int[5];
        int count = 0;
        stack[count++] = (int) (value & 0x7F);
        value >>>= 7;
        while (value > 0) {
            stack[count++] = (int) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        for (int i = count - 1; i >= 0; i--) {
            out.write(stack[i]);
        }
    }

    private static Element firstDirectChild(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        for (Element child : parent.children) {
            if (tag.equals(child.getTagName())) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String tag) {
        List<Element> out = new ArrayList<Element>();
        if (parent == null) {
            return out;
        }
        for (Element child : parent.children) {
            if (tag == null || tag.equals(child.getTagName())) {
                out.add(child);
            }
        }
        return out;
    }

    private static Element firstDirectDescendant(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        for (Element child : parent.children) {
            if (tag.equals(child.getTagName())) {
                return child;
            }
            Element descendant = firstDirectDescendant(child, tag);
            if (descendant != null) {
                return descendant;
            }
        }
        return null;
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().trim();
    }

    private static int intText(Element element, int fallback) {
        return parseInt(text(element), fallback);
    }

    private static double doubleText(Element element, double fallback) {
        return parseDouble(text(element), fallback);
    }

    private static int intAttribute(Element element, String name, int fallback) {
        if (element == null) {
            return fallback;
        }
        String attr = element.getAttribute(name);
        return attr == null ? fallback : parseInt(attr, fallback);
    }

    private static boolean hasAttributeValue(Element element, String name) {
        if (element == null) {
            return false;
        }
        String value = element.getAttribute(name);
        return value != null && value.length() > 0;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static final class Element {
        final String name;
        final Map<String, String> attributes = new LinkedHashMap<String, String>();
        final List<Element> children = new ArrayList<Element>();
        final StringBuilder text = new StringBuilder();

        Element(String name) {
            this.name = name;
        }

        String getTagName() {
            return name;
        }

        String getAttribute(String name) {
            String value = attributes.get(name);
            return value == null ? "" : value;
        }

        String text() {
            return text.toString();
        }
    }

    private static final class ElementTreeHandler extends DefaultHandler {
        Element root;
        private final List<Element> stack = new ArrayList<Element>();

        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            Element element = new Element(xmlName(localName, qName));
            for (int i = 0; i < attributes.getLength(); i++) {
                element.attributes.put(
                        xmlName(attributes.getLocalName(i), attributes.getQName(i)),
                        attributes.getValue(i)
                );
            }
            if (stack.isEmpty()) {
                root = element;
            } else {
                stack.get(stack.size() - 1).children.add(element);
            }
            stack.add(element);
        }

        public void characters(char[] ch, int start, int length) {
            if (!stack.isEmpty()) {
                stack.get(stack.size() - 1).text.append(ch, start, length);
            }
        }

        public void endElement(String uri, String localName, String qName) {
            if (!stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            }
        }
    }

    private static final class RootfileHandler extends DefaultHandler {
        String path;

        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (!"rootfile".equals(xmlName(localName, qName))) {
                return;
            }
            String candidate = attributes.getValue("full-path");
            if (candidate != null && candidate.length() > 0) {
                path = candidate;
                throw new RootfileFound(candidate);
            }
        }
    }

    private static final class RootfileFound extends SAXException {
        final String path;

        RootfileFound(String path) {
            super("rootfile found");
            this.path = path;
        }
    }

    private static String xmlName(String localName, String qName) {
        return localName != null && localName.length() > 0 ? localName : qName;
    }

    private static final class ScoreData {
        int division = DEFAULT_DIVISION;
        int noteCount = 0;
        int microtonalCount = 0;
        final Map<Integer, StaffInfo> staffInfos = new LinkedHashMap<Integer, StaffInfo>();
        final Map<Integer, TrackData> tracks = new TreeMap<Integer, TrackData>();
        final List<TempoEvent> tempoEvents = new ArrayList<TempoEvent>();
        final List<TimeSigEvent> timeSigEvents = new ArrayList<TimeSigEvent>();

        TrackData trackForStaff(StaffInfo info) {
            TrackData track = tracks.get(Integer.valueOf(info.staffId));
            if (track == null) {
                track = new TrackData();
                track.staffId = info.staffId;
                track.partIndex = info.partIndex;
                track.program = info.program;
                track.bankMsb = info.bankMsb;
                track.bankLsb = info.bankLsb;
                track.channel = info.channel;
                track.gateTimePercent = info.gateTimePercent;
                track.writeProgramChange = info.writeProgramChange;
                track.trackName = info.trackName;
                track.instrumentName = info.instrumentName;
                tracks.put(Integer.valueOf(info.staffId), track);
            }
            return track;
        }

        List<TrackData> tracksWithEvents() {
            List<TrackData> out = new ArrayList<TrackData>();
            for (TrackData track : tracks.values()) {
                if (!track.events.isEmpty()) {
                    out.add(track);
                }
            }
            return out;
        }
    }

    private static final class StaffInfo {
        int staffId;
        int partIndex;
        int program;
        int bankMsb;
        int bankLsb;
        int channel;
        int gateTimePercent;
        boolean writeProgramChange;
        String trackName;
        String instrumentName;
    }

    private static final class TrackData {
        int staffId;
        int partIndex;
        int program;
        int bankMsb;
        int bankLsb;
        int channel;
        int gateTimePercent;
        boolean writeProgramChange;
        String trackName;
        String instrumentName;
        final List<MidiEvent> events = new ArrayList<MidiEvent>();
        final List<PedalRange> pedalRanges = new ArrayList<PedalRange>();
        final List<HairpinRange> hairpins = new ArrayList<HairpinRange>();
        final List<OrnamentRange> trillRanges = new ArrayList<OrnamentRange>();
    }

    private static final class VoiceState {
        long tick;
        long measureStart;
        long sourceMeasureStart;
        long measureTicks;
        int velocity;
        double bpm = DEFAULT_BPM;
        double tupletRatio;
        int tupletRemaining;
        int lastTimeSigN;
        int lastTimeSigD;
        double pendingFermataMultiplier = 1.0;
        final Map<String, TupletInfo> tupletsById = new HashMap<String, TupletInfo>();
        final Map<TieKey, NotePlayback> activeTies = new HashMap<TieKey, NotePlayback>();
        final List<Element> pendingGraceChords = new ArrayList<Element>();
    }

    private static final class MeasureTimeline {
        final List<Long> sourceStarts = new ArrayList<Long>();
        final List<Long> sourceLengths = new ArrayList<Long>();
        final List<MeasureSlot> slots = new ArrayList<MeasureSlot>();
    }

    private static final class MeasureSlot {
        final int measureIndex;
        final long sourceStart;
        final long outputStart;
        final long length;

        MeasureSlot(int measureIndex, long sourceStart, long outputStart, long length) {
            this.measureIndex = measureIndex;
            this.sourceStart = sourceStart;
            this.outputStart = outputStart;
            this.length = length;
        }
    }

    private static final class PedalRange {
        long startTick;
        long endTick;
    }

    private static final class HairpinRange {
        long startTick;
        long endTick;
        int velocityDelta;
    }

    private static final class OrnamentRange {
        long startTick;
        long endTick;
        String name;
    }

    private static final class GracePlaybackSplit {
        long beforeTotal;
        long beforeEach;
        long afterTotal;
        long afterEach;
    }

    private static final class ChordPerformance {
        int gateTimePercent;
        int velocityOffset;
    }

    private static final class OrnamentPattern {
        final int[] pitchDeltas;
        final boolean repeat;
        final boolean sustainLast;
        final int denominator;

        OrnamentPattern(int[] pitchDeltas, boolean repeat, boolean sustainLast, int denominator) {
            this.pitchDeltas = pitchDeltas;
            this.repeat = repeat;
            this.sustainLast = sustainLast;
            this.denominator = denominator;
        }

        long unitTicks(int division) {
            return Math.max(1, Math.round(division * 4.0 / Math.max(1, denominator)));
        }
    }

    private static final class TupletInfo {
        final String id;
        final double ratio;
        final int actualNotes;

        TupletInfo(String id, double ratio, int actualNotes) {
            this.id = id;
            this.ratio = ratio;
            this.actualNotes = actualNotes;
        }
    }

    private static final class TempoEvent {
        final long tick;
        final double bpm;

        TempoEvent(long tick, double bpm) {
            this.tick = tick;
            this.bpm = bpm;
        }
    }

    private static final class TimeSigEvent {
        final long tick;
        final int numerator;
        final int denominator;

        TimeSigEvent(long tick, int numerator, int denominator) {
            this.tick = tick;
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }

    private static final class MetaTickEvent {
        final long tick;
        final int order;
        final Object payload;

        MetaTickEvent(long tick, int order, Object payload) {
            this.tick = tick;
            this.order = order;
            this.payload = payload;
        }
    }

    private static final class TrackTickEvent {
        final long tick;
        final int order;
        final int pitch;
        final MetaTickEvent meta;
        final MidiEvent midi;

        private TrackTickEvent(long tick, int order, int pitch, MetaTickEvent meta, MidiEvent midi) {
            this.tick = tick;
            this.order = order;
            this.pitch = pitch;
            this.meta = meta;
            this.midi = midi;
        }

        static TrackTickEvent meta(MetaTickEvent event) {
            return new TrackTickEvent(event.tick, event.order, 0, event, null);
        }

        static TrackTickEvent midi(MidiEvent event) {
            int order = event.kind == MidiEvent.KIND_NOTE_OFF ? 10 : (event.kind == MidiEvent.KIND_CONTROL ? 15 : 20);
            return new TrackTickEvent(event.tick, order, event.pitch, null, event);
        }
    }

    private static final class MidiEvent {
        static final int KIND_NOTE_OFF = 0;
        static final int KIND_NOTE_ON = 1;
        static final int KIND_CONTROL = 2;

        final long tick;
        final int kind;
        final int pitch;
        final int nativePitch;
        final int velocity;
        final double cents;
        final int controller;
        final int value;

        private MidiEvent(long tick, int kind, int pitch, int nativePitch, int velocity, double cents, int controller, int value) {
            this.tick = tick;
            this.kind = kind;
            this.pitch = pitch;
            this.nativePitch = nativePitch;
            this.velocity = velocity;
            this.cents = cents;
            this.controller = controller;
            this.value = value;
        }

        static MidiEvent noteOn(long tick, int pitch, int nativePitch, int velocity, double cents) {
            return new MidiEvent(tick, KIND_NOTE_ON, pitch, nativePitch, velocity, cents, 0, 0);
        }

        static MidiEvent noteOff(long tick, int nativePitch) {
            return new MidiEvent(tick, KIND_NOTE_OFF, nativePitch, nativePitch, 0, 0.0, 0, 0);
        }

        static MidiEvent controlChange(long tick, int controller, int value) {
            return new MidiEvent(tick, KIND_CONTROL, 0, 0, 0, 0.0, clamp(controller, 0, 127), clamp(value, 0, 127));
        }
    }

    private static final class NotePlayback {
        long startTick;
        long endTick;
        int pitch;
        int nativePitch;
        double cents;
        int velocity;
        int gateTimePercent;
    }

    private static final class NormalizedPitch {
        int pitch;
        double cents;
    }

    private static final class EventTiming {
        final long offsetTicks;
        final long lengthTicks;
        final double pitchDelta;

        EventTiming(long offsetTicks, long lengthTicks, double pitchDelta) {
            this.offsetTicks = offsetTicks;
            this.lengthTicks = lengthTicks;
            this.pitchDelta = pitchDelta;
        }
    }

    private static final class TieKey {
        final int staffId;
        final int voiceIndex;
        final int pitch;
        final double tuning;

        TieKey(int staffId, int voiceIndex, int pitch, double tuning) {
            this.staffId = staffId;
            this.voiceIndex = voiceIndex;
            this.pitch = pitch;
            this.tuning = tuning;
        }

        public boolean equals(Object other) {
            if (!(other instanceof TieKey)) {
                return false;
            }
            TieKey that = (TieKey) other;
            return this.staffId == that.staffId
                    && this.voiceIndex == that.voiceIndex
                    && this.pitch == that.pitch
                    && Double.compare(this.tuning, that.tuning) == 0;
        }

        public int hashCode() {
            long bits = Double.doubleToLongBits(tuning);
            int result = staffId;
            result = 31 * result + voiceIndex;
            result = 31 * result + pitch;
            result = 31 * result + (int) (bits ^ (bits >>> 32));
            return result;
        }
    }
}
