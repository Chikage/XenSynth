#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <list>
#include <limits>
#include <memory>
#include <mutex>

#include <android/log.h>
#include <oboe/Oboe.h>
#include "fluidsynth.h"

namespace {

constexpr const char *TAG = "XenAudioEngine";
constexpr int32_t kChannelCount = 2;
constexpr int32_t kSourceMidiChannelCount = 16;
constexpr int32_t kFluidMidiChannelCount = 256;
constexpr int32_t kSampleRate = 44100;
constexpr int32_t kBufferSizeInBursts = 3;
constexpr int32_t kPitchClassCount = 12;
constexpr int32_t kMidiKeyCount = 128;
constexpr int32_t kDrumChannel = 9;
constexpr int32_t kDrumBank = 128;
constexpr int32_t kFluidTuningProgramsPerBank = 128;
constexpr double kFluidTuningPitchEpsilon = 0.0001;
constexpr float kFluidDefaultGain = 2.05f;
constexpr float kFluidMaxGain = 6.0f;
constexpr float kFluidPianoNoteOffDelaySeconds = 0.68f;
constexpr float kFluidDefaultNoteOffDelaySeconds = 0.035f;
constexpr float kFluidSustainedNoteOffDelaySeconds = 0.0f;
constexpr float kFluidLeadNoteOffDelaySeconds = 0.0f;
constexpr float kFluidPercussiveNoteOffDelaySeconds = 0.0f;
constexpr int32_t kFluidDefaultReverbValue = 54;
constexpr double kFluidReverbRoomSize = 0.90;
constexpr double kFluidReverbDamp = 0.38;
constexpr double kFluidReverbWidth = 88.0;
constexpr double kFluidReverbMaxLevel = 0.68;
constexpr int32_t kFluidChorusVoiceCount = 3;
constexpr double kFluidChorusLevel = 0.16;
constexpr double kFluidChorusSpeed = 0.24;
constexpr double kFluidChorusDepthMs = 0.72;
constexpr std::array<uint8_t, 32> kSoundFontKeyMask = {
        0xdf, 0xa1, 0x09, 0xd1, 0x9a, 0x2e, 0x08, 0x36,
        0xf0, 0xce, 0xe7, 0x5e, 0x6f, 0x8a, 0x68, 0x6e,
        0x25, 0xd6, 0x42, 0xc4, 0x84, 0xfb, 0x3a, 0x32,
        0xf9, 0x2b, 0xe4, 0xba, 0x3c, 0x43, 0xfc, 0xd9
};
constexpr std::array<uint8_t, 32> kMaskedSoundFontKey = {
        0xbb, 0xee, 0x19, 0xaf, 0xb6, 0xde, 0x90, 0x99,
        0x4a, 0xb5, 0xca, 0xbb, 0xfb, 0xb3, 0x9e, 0xfb,
        0xf9, 0xe0, 0xf0, 0x5e, 0xf2, 0x08, 0x72, 0x1c,
        0x82, 0x8c, 0x6b, 0x51, 0x12, 0x82, 0x11, 0x70
};

enum class Sf2ReleaseProfile : int32_t {
    Piano = 0,
    Default = 1,
    Sustained = 2,
    Lead = 3,
    Percussive = 4
};

struct FluidHandle {
    fluid_settings_t *settings = nullptr;
    fluid_synth_t *synth = nullptr;
    int soundfontId = 0;
    std::atomic<bool> loading{false};
};

struct FluidProgramState {
    int32_t bank = -1;
    int32_t program = -1;
};

struct FluidTuningState {
    int32_t key = -1;
    double pitch = 0.0;
    bool active = false;
};

struct FluidNote {
    int32_t id = 0;
    int32_t playbackChannel = 0;
    int32_t key = 0;
    int32_t velocity = 0;
    int32_t bank = 0;
    int32_t program = 0;
    int32_t startFramesRemaining = 0;
    int32_t releaseFramesRemaining = 0;
    float cents = 0.0f;
    Sf2ReleaseProfile profile = Sf2ReleaseProfile::Default;
    bool started = false;
    bool releasing = false;
};

static bool isPianoProgram(int32_t program) {
    return program >= 0 && program <= 7;
}

static bool isSustainedProgram(int32_t program) {
    return (program >= 16 && program <= 23) ||
           (program >= 40 && program <= 47) ||
           (program >= 48 && program <= 55) ||
           (program >= 56 && program <= 63) ||
           (program >= 64 && program <= 79) ||
           (program >= 88 && program <= 95);
}

static bool isLeadProgram(int32_t program) {
    return program >= 80 && program <= 87;
}

static bool isPercussiveProgram(int32_t channel, int32_t bank, int32_t program) {
    return channel == kDrumChannel || bank == kDrumBank || program >= 112;
}

static Sf2ReleaseProfile classifyReleaseProfile(int32_t channel, int32_t bank, int32_t program) {
    if (isPercussiveProgram(channel, bank, program)) {
        return Sf2ReleaseProfile::Percussive;
    }
    if (isPianoProgram(program)) {
        return Sf2ReleaseProfile::Piano;
    }
    if (isSustainedProgram(program)) {
        return Sf2ReleaseProfile::Sustained;
    }
    if (isLeadProgram(program)) {
        return Sf2ReleaseProfile::Lead;
    }
    return Sf2ReleaseProfile::Default;
}

static int32_t framesFromSeconds(double seconds) {
    if (!std::isfinite(seconds) || seconds <= 0.0) {
        return 0;
    }
    const double frames = seconds * static_cast<double>(kSampleRate);
    if (frames >= static_cast<double>(std::numeric_limits<int32_t>::max())) {
        return std::numeric_limits<int32_t>::max();
    }
    return std::max(0, static_cast<int32_t>(std::round(frames)));
}

static std::array<jbyte, 32> buildSoundFontKey() {
    std::array<jbyte, 32> key{};
    for (size_t i = 0; i < key.size(); ++i) {
        key[i] = static_cast<jbyte>(kSoundFontKeyMask[i] ^ kMaskedSoundFontKey[i]);
    }
    return key;
}

static int32_t releaseFramesForProfile(Sf2ReleaseProfile profile) {
    float releaseSeconds = kFluidDefaultNoteOffDelaySeconds;
    switch (profile) {
        case Sf2ReleaseProfile::Piano:
            releaseSeconds = kFluidPianoNoteOffDelaySeconds;
            break;
        case Sf2ReleaseProfile::Sustained:
            releaseSeconds = kFluidSustainedNoteOffDelaySeconds;
            break;
        case Sf2ReleaseProfile::Lead:
            releaseSeconds = kFluidLeadNoteOffDelaySeconds;
            break;
        case Sf2ReleaseProfile::Percussive:
            releaseSeconds = kFluidPercussiveNoteOffDelaySeconds;
            break;
        case Sf2ReleaseProfile::Default:
        default:
            break;
    }
    return framesFromSeconds(releaseSeconds);
}

class XenAudioEngine {
public:
    bool setup() {
        std::lock_guard<std::mutex> lock(controlMutex);
        if (audioStream != nullptr) {
            return true;
        }
        return openStreamLocked();
    }

    bool start() {
        std::lock_guard<std::mutex> lock(controlMutex);
        if (audioStream == nullptr && !openStreamLocked()) {
            return false;
        }
        if (audioStream == nullptr) {
            return false;
        }
        const oboe::Result result = audioStream->requestStart();
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "requestStart failed: %s",
                                oboe::convertToText(result));
            return false;
        }
        return true;
    }

    void teardown() {
        std::lock_guard<std::mutex> lock(controlMutex);
        closeStreamLocked();
        closeFluidSynthLocked();
    }

    bool restart() {
        std::lock_guard<std::mutex> lock(controlMutex);
        if (fluid.synth != nullptr) {
            fluid.loading.store(true, std::memory_order_release);
        }
        closeStreamLocked();
        const bool opened = openStreamLocked();
        bool started = false;
        if (opened && audioStream != nullptr) {
            started = audioStream->requestStart() == oboe::Result::OK;
        }
        if (fluid.synth != nullptr) {
            fluid.loading.store(false, std::memory_order_release);
        }
        return opened && started;
    }

    bool isStarted() const {
        return audioStream != nullptr &&
               audioStream->getState() == oboe::StreamState::Started;
    }

    bool loadSf2(const char *path) {
        if (path == nullptr) {
            return false;
        }
        std::lock_guard<std::mutex> lock(controlMutex);
        if (!openFluidSynthLocked() || fluid.synth == nullptr) {
            return false;
        }
        unloadSf2Locked();
        fluid.loading.store(true, std::memory_order_release);
        const int soundfontId = fluid_synth_sfload(fluid.synth, path, 1);
        if (soundfontId == FLUID_FAILED) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "sf2 load failed: %s", path);
            fluid.loading.store(false, std::memory_order_release);
            return false;
        }
        fluid.soundfontId = soundfontId;
        selectDefaultProgramsLocked();
        applyPitchCalibrationLocked();
        fluid.loading.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, TAG, "sf2 loaded: %s", path);
        return true;
    }

    void unloadSf2() {
        std::lock_guard<std::mutex> lock(controlMutex);
        unloadSf2Locked();
    }

    bool hasSoundFont() const {
        return fluid.synth != nullptr && fluid.soundfontId > 0;
    }

    int32_t noteOn(
            int key,
            int velocity,
            float cents,
            int channel,
            int program,
            int bankMsb,
            int bankLsb,
            double delaySeconds) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (!isFluidReadyLocked() || key < 0 || key >= kMidiKeyCount || velocity <= 0) {
            return -1;
        }
        const int32_t safeSourceChannel = clampInt(channel, 0, kSourceMidiChannelCount - 1);
        const int32_t safeProgram = clampInt(program, 0, 127);
        const int32_t safeBankMsb = clampInt(bankMsb, 0, 127);
        const int32_t safeBankLsb = clampInt(bankLsb, 0, 127);
        const int32_t midiBank = safeBankMsb * 128 + safeBankLsb;
        const int32_t requestedBank = safeSourceChannel == kDrumChannel && midiBank == 0
                                      ? kDrumBank
                                      : midiBank;
        const int32_t bank = resolvePresetBankLocked(requestedBank, safeProgram);
        if (bank < 0) {
            return -1;
        }
        const Sf2ReleaseProfile profile = classifyReleaseProfile(safeSourceChannel, bank, safeProgram);
        const int32_t noteId = nextFluidNoteId();
        FluidNote note{};
        note.id = noteId;
        note.playbackChannel = allocatePlaybackChannelLocked(safeSourceChannel, key);
        note.key = key;
        note.velocity = std::min(127, velocity);
        note.bank = bank;
        note.program = safeProgram;
        note.startFramesRemaining = framesFromSeconds(delaySeconds);
        note.cents = cents;
        note.profile = profile;
        note.started = note.startFramesRemaining <= 0;
        if (note.started && !startFluidNoteLocked(note)) {
            return -1;
        }
        activeFluidNotes.push_back(note);
        return noteId;
    }

    void noteOff(int noteId) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (!isFluidReadyLocked()) {
            return;
        }
        const auto found = std::find_if(
                activeFluidNotes.begin(),
                activeFluidNotes.end(),
                [noteId](const FluidNote &note) { return note.id == noteId; });
        if (found == activeFluidNotes.end()) {
            return;
        }
        if (!found->started) {
            activeFluidNotes.erase(found);
            return;
        }
        if (!found->releasing) {
            found->releasing = true;
            found->releaseFramesRemaining = releaseFramesForProfile(found->profile);
            if (found->releaseFramesRemaining <= 0) {
                stopFluidNoteLocked(*found);
                activeFluidNotes.erase(found);
            }
        }
    }

    void allSoundOff() {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (fluid.synth != nullptr) {
            fluid_synth_all_sounds_off(fluid.synth, -1);
        }
        activeFluidNotes.clear();
        playbackChannelCursor = 0;
    }

    void setGain(float gain) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (fluid.synth != nullptr) {
            fluid_synth_set_gain(fluid.synth, std::max(0.0f, std::min(kFluidMaxGain, gain)));
        }
    }

    void setReverb(int value) {
        std::lock_guard<std::mutex> lock(synthMutex);
        reverbValue.store(std::max(0, std::min(100, value)), std::memory_order_release);
        applyReverbLocked();
    }

    void setPitchCalibration(JNIEnv *env, jfloatArray centsArray) {
        std::array<float, kPitchClassCount> cents{};
        const jsize length = centsArray == nullptr ? 0 : env->GetArrayLength(centsArray);
        if (length > 0) {
            env->GetFloatArrayRegion(centsArray, 0,
                                     std::min(static_cast<jsize>(kPitchClassCount), length),
                                     cents.data());
        }
        for (int32_t i = 0; i < kPitchClassCount; i++) {
            pitchCalibrationCents[i].store(cents[i], std::memory_order_release);
        }
        std::lock_guard<std::mutex> lock(synthMutex);
        applyPitchCalibrationLocked();
    }

private:
    class DataCallback final : public oboe::AudioStreamDataCallback {
    public:
        explicit DataCallback(XenAudioEngine *parent) : parent(parent) {}

        oboe::DataCallbackResult onAudioReady(
                oboe::AudioStream *audioStream,
                void *audioData,
                int32_t numFrames) override {
            return parent->onAudioReady(audioStream, audioData, numFrames);
        }

    private:
        XenAudioEngine *parent;
    };

    class ErrorCallback final : public oboe::AudioStreamErrorCallback {
    public:
        explicit ErrorCallback(XenAudioEngine *parent) : parent(parent) {}

        bool onError(oboe::AudioStream *, oboe::Result error) override {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "audio stream error: %s",
                                oboe::convertToText(error));
            return true;
        }

    private:
        XenAudioEngine *parent;
    };

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *stream,
            void *audioData,
            int32_t numFrames) {
        if (audioData == nullptr || numFrames <= 0) {
            return oboe::DataCallbackResult::Continue;
        }
        auto *out = static_cast<float *>(audioData);
        std::fill(out, out + numFrames * kChannelCount, 0.0f);
        if (stream == nullptr || stream->getState() != oboe::StreamState::Started) {
            return oboe::DataCallbackResult::Continue;
        }
        bool hasAudio = false;
        if (isFluidReadyForCallback()) {
            std::lock_guard<std::mutex> lock(synthMutex);
            if (isFluidReadyLocked()) {
                fluid_synth_write_float(fluid.synth, numFrames, out, 0, kChannelCount, out, 1, kChannelCount);
                handleScheduledFluidNotesLocked(numFrames);
                hasAudio = true;
            }
        }
        if (hasAudio) {
            applyLimiter(out, numFrames * kChannelCount);
        }
        if (latencyTuner != nullptr) {
            latencyTuner->tune();
        }
        return oboe::DataCallbackResult::Continue;
    }

    bool openStreamLocked() {
        dataCallback = std::make_shared<DataCallback>(this);
        errorCallback = std::make_shared<ErrorCallback>(this);

        oboe::AudioStreamBuilder builder;
        builder.setChannelCount(kChannelCount);
        builder.setSampleRate(kSampleRate);
        builder.setDataCallback(dataCallback);
        builder.setErrorCallback(errorCallback);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        builder.setUsage(oboe::Usage::Game);

        const oboe::Result result = builder.openStream(audioStream);
        if (result != oboe::Result::OK || audioStream == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "openStream failed: %s",
                                oboe::convertToText(result));
            audioStream.reset();
            return false;
        }
        audioStream->setPerformanceHintEnabled(true);
        const oboe::Result bufferResult = audioStream->setBufferSizeInFrames(
                audioStream->getFramesPerBurst() * kBufferSizeInBursts);
        if (bufferResult != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "setBufferSizeInFrames failed: %s",
                                oboe::convertToText(bufferResult));
        }
        latencyTuner = std::make_unique<oboe::LatencyTuner>(*audioStream);
        return true;
    }

    void closeStreamLocked() {
        latencyTuner.reset();
        if (audioStream != nullptr) {
            audioStream->flush();
            audioStream->stop();
            audioStream->close();
            audioStream.reset();
        }
        dataCallback.reset();
        errorCallback.reset();
    }

    bool openFluidSynthLocked() {
        if (fluid.synth != nullptr) {
            return true;
        }
        fluid.settings = new_fluid_settings();
        if (fluid.settings == nullptr) {
            return false;
        }
        fluid_settings_setint(fluid.settings, "synth.threadsafe-api", 1);
        fluid_settings_setint(fluid.settings, "synth.cpu-cores", 4);
        fluid_settings_setint(fluid.settings, "audio.realtime-prio", 99);
        fluid_settings_setint(fluid.settings, "synth.midi-channels", kFluidMidiChannelCount);
        fluid_settings_setint(fluid.settings, "synth.polyphony", 1024);
        fluid_settings_setnum(fluid.settings, "synth.sample-rate", kSampleRate);
        fluid_settings_setnum(fluid.settings, "synth.gain", kFluidDefaultGain);
        fluid_settings_setint(fluid.settings, "synth.reverb.active", 1);
        fluid_settings_setint(fluid.settings, "synth.chorus.active", 1);
        fluid.synth = new_fluid_synth(fluid.settings);
        fluid.soundfontId = 0;
        fluid.loading.store(false, std::memory_order_release);
        fluidPrograms.fill(FluidProgramState{});
        fluidTunings.fill(FluidTuningState{});
        if (fluid.synth == nullptr) {
            closeFluidSynthLocked();
            return false;
        }
        fluidMidiChannelCount = clampInt(
                fluid_synth_count_midi_channels(fluid.synth),
                kSourceMidiChannelCount,
                kFluidMidiChannelCount);
        channelIsolationAvailable = fluidMidiChannelCount > kSourceMidiChannelCount;
        if (!channelIsolationAvailable && !loggedChannelIsolationUnavailable) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "FluidSynth exposed only %d MIDI channels; dense microtonal slides use best-effort playback",
                                fluidMidiChannelCount);
            loggedChannelIsolationUnavailable = true;
        }
        fluid_synth_set_interp_method(fluid.synth, -1, FLUID_INTERP_HIGHEST);
        fluid_synth_set_reverb_group_roomsize(fluid.synth, -1, kFluidReverbRoomSize);
        fluid_synth_set_reverb_group_damp(fluid.synth, -1, kFluidReverbDamp);
        fluid_synth_set_reverb_group_width(fluid.synth, -1, kFluidReverbWidth);
        fluid_synth_chorus_on(fluid.synth, -1, 1);
        fluid_synth_set_chorus_group_nr(fluid.synth, -1, kFluidChorusVoiceCount);
        fluid_synth_set_chorus_group_level(fluid.synth, -1, kFluidChorusLevel);
        fluid_synth_set_chorus_group_speed(fluid.synth, -1, kFluidChorusSpeed);
        fluid_synth_set_chorus_group_depth(fluid.synth, -1, kFluidChorusDepthMs);
        fluid_synth_set_chorus_group_type(fluid.synth, -1, FLUID_CHORUS_MOD_SINE);
        applyPitchCalibrationLocked();
        applyReverbLocked();
        return true;
    }

    void closeFluidSynthLocked() {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (fluid.synth != nullptr) {
            delete_fluid_synth(fluid.synth);
            fluid.synth = nullptr;
        }
        if (fluid.settings != nullptr) {
            delete_fluid_settings(fluid.settings);
            fluid.settings = nullptr;
        }
        fluid.soundfontId = 0;
        fluid.loading.store(false, std::memory_order_release);
        activeFluidNotes.clear();
        fluidPrograms.fill(FluidProgramState{});
        fluidTunings.fill(FluidTuningState{});
        fluidMidiChannelCount = kSourceMidiChannelCount;
        playbackChannelCursor = 0;
        channelIsolationAvailable = false;
    }

    void unloadSf2Locked() {
        if (fluid.synth != nullptr && fluid.soundfontId > 0) {
            std::lock_guard<std::mutex> lock(synthMutex);
            fluid.loading.store(true, std::memory_order_release);
            fluid_synth_all_sounds_off(fluid.synth, -1);
            fluid_synth_sfunload(fluid.synth, fluid.soundfontId, 1);
            fluid.soundfontId = 0;
            activeFluidNotes.clear();
            fluidPrograms.fill(FluidProgramState{});
            fluidTunings.fill(FluidTuningState{});
            playbackChannelCursor = 0;
            fluid.loading.store(false, std::memory_order_release);
        }
    }

    void selectDefaultProgramsLocked() {
        for (int32_t channel = 0; channel < fluidMidiChannelCount; channel++) {
            const int32_t bank = channel == kDrumChannel ? kDrumBank : 0;
            selectProgramLocked(channel, bank, 0);
        }
    }

    bool selectProgramLocked(int32_t channel, int32_t requestedBank, int32_t requestedProgram) {
        if (fluid.synth == nullptr || fluid.soundfontId <= 0 ||
            channel < 0 || channel >= fluidMidiChannelCount) {
            return false;
        }
        const int32_t program = clampInt(requestedProgram, 0, 127);
        const int32_t bank = resolvePresetBankLocked(requestedBank, program);
        if (bank < 0) {
            return false;
        }
        const FluidProgramState next{bank, program};
        if (fluidPrograms[channel].bank == next.bank && fluidPrograms[channel].program == next.program) {
            return true;
        }
        if (fluid_synth_program_select(fluid.synth, channel, fluid.soundfontId, bank, program) == FLUID_FAILED) {
            return false;
        }
        fluidPrograms[channel] = next;
        return true;
    }

    int32_t resolvePresetBankLocked(int32_t requestedBank, int32_t program) {
        fluid_sfont_t *soundFont = fluid_synth_get_sfont_by_id(fluid.synth, fluid.soundfontId);
        if (soundFont == nullptr) {
            return -1;
        }
        const int32_t bank = std::max(0, requestedBank);
        if (fluid_sfont_get_preset(soundFont, bank, program) != nullptr) {
            return bank;
        }
        if (bank != 0 && fluid_sfont_get_preset(soundFont, 0, program) != nullptr) {
            return 0;
        }
        if (fluid_sfont_get_preset(soundFont, 0, 0) != nullptr) {
            return 0;
        }
        return -1;
    }

    void applyPitchCalibrationLocked() {
        if (fluid.synth == nullptr) {
            return;
        }
        double pitch[kMidiKeyCount];
        for (int32_t i = 0; i < kMidiKeyCount; i++) {
            pitch[i] = static_cast<double>(i) * 100.0 +
                       static_cast<double>(
                               pitchCalibrationCents[i % kPitchClassCount].load(
                                       std::memory_order_acquire));
        }
        for (int32_t channel = 0; channel < fluidMidiChannelCount; channel++) {
            const int32_t tuningBank = tuningBankForChannel(channel);
            const int32_t tuningProgram = tuningProgramForChannel(channel);
            fluid_synth_activate_key_tuning(fluid.synth, tuningBank, tuningProgram,
                                            "XenSynth tuning", pitch, 1);
            fluid_synth_activate_tuning(fluid.synth, channel, tuningBank, tuningProgram, 1);
        }
        fluidTunings.fill(FluidTuningState{});
    }

    void applyReverbLocked() {
        if (fluid.synth == nullptr) {
            return;
        }
        const int value = reverbValue.load(std::memory_order_acquire);
        if (value <= 0) {
            fluid_synth_reverb_on(fluid.synth, -1, 0);
        } else {
            fluid_synth_reverb_on(fluid.synth, -1, 1);
            fluid_synth_set_reverb_group_roomsize(fluid.synth, -1, kFluidReverbRoomSize);
            fluid_synth_set_reverb_group_damp(fluid.synth, -1, kFluidReverbDamp);
            fluid_synth_set_reverb_group_width(fluid.synth, -1, kFluidReverbWidth);
            fluid_synth_set_reverb_group_level(
                    fluid.synth,
                    -1,
                    std::min(1.0, static_cast<double>(value) / 100.0 * kFluidReverbMaxLevel));
        }
    }

    bool startFluidNoteLocked(const FluidNote &note) {
        if (!selectProgramLocked(note.playbackChannel, note.bank, note.program)) {
            return false;
        }
        const double calibrationCents = static_cast<double>(
                pitchCalibrationCents[note.key % kPitchClassCount].load(std::memory_order_acquire));
        const double pitch = static_cast<double>(note.key) * 100.0 +
                             static_cast<double>(note.cents) +
                             calibrationCents;
        if (!ensureTuningForNoteLocked(note.playbackChannel, note.key, pitch)) {
            return false;
        }
        return fluid_synth_noteon(fluid.synth, note.playbackChannel, note.key, note.velocity) != FLUID_FAILED;
    }

    void stopFluidNoteLocked(const FluidNote &note) {
        if (hasOtherSustainedFluidNoteLocked(note)) {
            return;
        }
        fluid_synth_noteoff(fluid.synth, note.playbackChannel, note.key);
    }

    bool ensureTuningForNoteLocked(int32_t playbackChannel, int32_t key, double pitch) {
        if (playbackChannel < 0 || playbackChannel >= fluidMidiChannelCount) {
            return false;
        }
        FluidTuningState &state = fluidTunings[playbackChannel];
        if (state.active &&
            state.key == key &&
            std::abs(state.pitch - pitch) <= kFluidTuningPitchEpsilon) {
            return true;
        }
        const int32_t tuningBank = tuningBankForChannel(playbackChannel);
        const int32_t tuningProgram = tuningProgramForChannel(playbackChannel);
        const int32_t tunedKey = key;
        if (fluid_synth_tune_notes(fluid.synth, tuningBank, tuningProgram, 1, &tunedKey, &pitch, 1) ==
            FLUID_FAILED) {
            return false;
        }
        if (fluid_synth_activate_tuning(fluid.synth, playbackChannel, tuningBank, tuningProgram, 1) ==
            FLUID_FAILED) {
            return false;
        }
        state.key = key;
        state.pitch = pitch;
        state.active = true;
        return true;
    }

    void finishPendingFluidNoteOffsLocked(
            int32_t playbackChannel,
            int32_t key,
            Sf2ReleaseProfile profile,
            int32_t excludedNoteId) {
        for (auto note = activeFluidNotes.begin(); note != activeFluidNotes.end();) {
            if (note->id != excludedNoteId &&
                note->started &&
                note->releasing &&
                note->playbackChannel == playbackChannel &&
                note->key == key &&
                note->profile == profile) {
                stopFluidNoteLocked(*note);
                note = activeFluidNotes.erase(note);
            } else {
                ++note;
            }
        }
    }

    void handleScheduledFluidNotesLocked(int32_t numFrames) {
        for (auto note = activeFluidNotes.begin(); note != activeFluidNotes.end();) {
            if (!note->started) {
                note->startFramesRemaining -= numFrames;
                if (note->startFramesRemaining > 0) {
                    ++note;
                    continue;
                }
                finishPendingFluidNoteOffsLocked(
                        note->playbackChannel,
                        note->key,
                        note->profile,
                        note->id);
                note->started = true;
                if (!startFluidNoteLocked(*note)) {
                    note = activeFluidNotes.erase(note);
                    continue;
                }
            }
            if (!note->releasing) {
                ++note;
                continue;
            }
            note->releaseFramesRemaining -= numFrames;
            if (note->releaseFramesRemaining > 0) {
                ++note;
                continue;
            }
            stopFluidNoteLocked(*note);
            note = activeFluidNotes.erase(note);
        }
    }

    int32_t allocatePlaybackChannelLocked(int32_t sourceChannel, int32_t key) {
        if (fluidMidiChannelCount <= kSourceMidiChannelCount) {
            return sourceChannel;
        }
        const int32_t overflowCount = fluidMidiChannelCount - kSourceMidiChannelCount;
        for (int32_t i = 0; i < overflowCount; i++) {
            const int32_t channel = kSourceMidiChannelCount +
                                    (playbackChannelCursor + i) % overflowCount;
            if (!hasActiveFluidNoteOnChannelLocked(channel)) {
                playbackChannelCursor = (channel - kSourceMidiChannelCount + 1) % overflowCount;
                return channel;
            }
        }
        for (int32_t i = 0; i < overflowCount; i++) {
            const int32_t channel = kSourceMidiChannelCount +
                                    (playbackChannelCursor + i) % overflowCount;
            if (!hasActiveFluidNoteForKeyLocked(channel, key)) {
                playbackChannelCursor = (channel - kSourceMidiChannelCount + 1) % overflowCount;
                return channel;
            }
        }
        const int32_t channel = kSourceMidiChannelCount + playbackChannelCursor;
        playbackChannelCursor = (playbackChannelCursor + 1) % overflowCount;
        return channel;
    }

    bool hasActiveFluidNoteOnChannelLocked(int32_t playbackChannel) const {
        return std::any_of(
                activeFluidNotes.begin(),
                activeFluidNotes.end(),
                [playbackChannel](const FluidNote &note) {
                    return note.playbackChannel == playbackChannel;
                });
    }

    bool hasActiveFluidNoteForKeyLocked(int32_t playbackChannel, int32_t key) const {
        return std::any_of(
                activeFluidNotes.begin(),
                activeFluidNotes.end(),
                [playbackChannel, key](const FluidNote &note) {
                    return note.playbackChannel == playbackChannel && note.key == key;
                });
    }

    bool hasOtherSustainedFluidNoteLocked(const FluidNote &stoppingNote) const {
        if (channelIsolationAvailable) {
            return false;
        }
        return std::any_of(
                activeFluidNotes.begin(),
                activeFluidNotes.end(),
                [&stoppingNote](const FluidNote &note) {
                    return note.id != stoppingNote.id &&
                           note.started &&
                           !note.releasing &&
                           note.playbackChannel == stoppingNote.playbackChannel &&
                           note.key == stoppingNote.key;
                });
    }

    static int32_t tuningBankForChannel(int32_t channel) {
        return channel / kFluidTuningProgramsPerBank;
    }

    static int32_t tuningProgramForChannel(int32_t channel) {
        return channel % kFluidTuningProgramsPerBank;
    }

    bool isFluidReadyForCallback() const {
        return fluid.synth != nullptr &&
               fluid.soundfontId > 0 &&
               !fluid.loading.load(std::memory_order_acquire);
    }

    bool isFluidReadyLocked() const {
        return fluid.synth != nullptr &&
               fluid.soundfontId > 0 &&
               !fluid.loading.load(std::memory_order_acquire);
    }

    static int32_t clampInt(int32_t value, int32_t minValue, int32_t maxValue) {
        return std::max(minValue, std::min(maxValue, value));
    }

    int32_t nextFluidNoteId() {
        if (fluidNoteId == std::numeric_limits<int32_t>::max()) {
            fluidNoteId = 1;
        } else {
            fluidNoteId++;
        }
        return fluidNoteId;
    }

    static void applyLimiter(float *audioData, int32_t sampleCount) {
        constexpr float outputHeadroom = 0.85f;
        constexpr float limiterThreshold = 0.92f;
        for (int32_t i = 0; i < sampleCount; i++) {
            float sample = audioData[i] * outputHeadroom;
            if (sample > limiterThreshold) {
                sample = limiterThreshold +
                         (1.0f - limiterThreshold) *
                         std::tanh((sample - limiterThreshold) / (1.0f - limiterThreshold));
            } else if (sample < -limiterThreshold) {
                sample = -limiterThreshold +
                         (1.0f - limiterThreshold) *
                         std::tanh((sample + limiterThreshold) / (1.0f - limiterThreshold));
            }
            audioData[i] = sample;
        }
    }

    FluidHandle fluid;
    std::shared_ptr<oboe::AudioStream> audioStream;
    std::shared_ptr<DataCallback> dataCallback;
    std::shared_ptr<ErrorCallback> errorCallback;
    std::unique_ptr<oboe::LatencyTuner> latencyTuner;
    mutable std::mutex controlMutex;
    mutable std::mutex synthMutex;
    std::array<FluidProgramState, kFluidMidiChannelCount> fluidPrograms{};
    std::array<FluidTuningState, kFluidMidiChannelCount> fluidTunings{};
    std::list<FluidNote> activeFluidNotes;
    int32_t fluidNoteId = 0;
    int32_t fluidMidiChannelCount = kSourceMidiChannelCount;
    int32_t playbackChannelCursor = 0;
    bool channelIsolationAvailable = false;
    bool loggedChannelIsolationUnavailable = false;
    std::array<std::atomic<float>, kPitchClassCount> pitchCalibrationCents{};
    std::atomic<int> reverbValue{kFluidDefaultReverbValue};
};

XenAudioEngine engine;

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_setupNative(JNIEnv *, jclass) {
    return engine.setup();
}

JNIEXPORT jboolean JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_startNative(JNIEnv *, jclass) {
    return engine.start();
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_teardownNative(JNIEnv *, jclass) {
    engine.teardown();
}

JNIEXPORT jboolean JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_restartNative(JNIEnv *, jclass) {
    return engine.restart();
}

JNIEXPORT jboolean JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_isStartedNative(JNIEnv *, jclass) {
    return engine.isStarted();
}

JNIEXPORT jboolean JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_loadSf2Native(
        JNIEnv *env,
        jclass,
        jstring filePath) {
    const char *path = filePath == nullptr ? nullptr : env->GetStringUTFChars(filePath, nullptr);
    const bool loaded = engine.loadSf2(path);
    if (filePath != nullptr && path != nullptr) {
        env->ReleaseStringUTFChars(filePath, path);
    }
    return loaded;
}

JNIEXPORT jbyteArray JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_soundFontKeyNative(JNIEnv *env, jclass) {
    auto key = buildSoundFontKey();
    jbyteArray result = env->NewByteArray(static_cast<jsize>(key.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(key.size()), key.data());
    }
    key.fill(0);
    return result;
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_unloadSf2Native(JNIEnv *, jclass) {
    engine.unloadSf2();
}

JNIEXPORT jboolean JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_hasSoundFontNative(JNIEnv *, jclass) {
    return engine.hasSoundFont();
}

JNIEXPORT jint JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_noteOnNative(
        JNIEnv *,
        jclass,
        jint key,
        jint velocity,
        jfloat cents,
        jint channel,
        jint program,
        jint bankMsb,
        jint bankLsb,
        jdouble delaySeconds) {
    return engine.noteOn(key, velocity, cents, channel, program, bankMsb, bankLsb, delaySeconds);
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_noteOffNative(JNIEnv *, jclass, jint noteId) {
    engine.noteOff(noteId);
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_allSoundOffNative(JNIEnv *, jclass) {
    engine.allSoundOff();
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_setGainNative(JNIEnv *, jclass, jfloat gain) {
    engine.setGain(gain);
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_setReverbNative(JNIEnv *, jclass, jint value) {
    engine.setReverb(value);
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_setPitchCalibrationNative(
        JNIEnv *env,
        jclass,
        jfloatArray centsArray) {
    engine.setPitchCalibration(env, centsArray);
}

} // extern "C"
