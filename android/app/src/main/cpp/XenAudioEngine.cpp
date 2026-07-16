#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <list>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>
#include <oboe/Oboe.h>
#include "fluidsynth.h"

namespace {

extern "C" {
extern const uint8_t xen_soundfont_blob_start[];
extern const uint8_t xen_soundfont_blob_end[];
}

constexpr const char *TAG = "XenAudioEngine";
constexpr const char *kBuiltinSoundFontUri = "memory://xensynth/builtin.sf2";
constexpr int32_t kChannelCount = 2;
constexpr int32_t kSourceMidiChannelCount = 16;
constexpr int32_t kFluidMidiChannelCount = 256;
constexpr int32_t kMaxFluidSynthInstanceCount = 8;
constexpr int32_t kMixScratchFrameCount = 1024;
constexpr int32_t kSampleRate = 44100;
constexpr int32_t kBufferSizeInBursts = 3;
constexpr int32_t kPitchClassCount = 12;
constexpr int32_t kMidiKeyCount = 128;
constexpr int32_t kMidiExpressionController = 11;
constexpr size_t kPressureMailboxCount = 2048;
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
constexpr size_t kSoundFontNonceBytes = 12;
constexpr size_t kSoundFontGcmTagBytes = 16;
constexpr jint kGcmTagBits = static_cast<jint>(kSoundFontGcmTagBytes * 8);
constexpr jint kCipherDecryptMode = 2;
constexpr std::array<uint8_t, 8> kSoundFontPackageMagic = {
        0x9d, 0x72, 0xb4, 0x1e, 0x43, 0xe8, 0x0d, 0xa6
};
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

using PressureMailboxWord = unsigned long long;
static_assert(sizeof(PressureMailboxWord) == sizeof(uint64_t),
              "Pressure mailbox requires a 64-bit word");
static_assert(ATOMIC_LLONG_LOCK_FREE == 2,
              "Pressure mailbox requires lock-free 64-bit atomics");
constexpr PressureMailboxWord kPressureExpressionMask = 0x7fULL;
constexpr PressureMailboxWord kPressureDirtyBit = 0x80ULL;
constexpr PressureMailboxWord kPressurePayloadMask = 0xffULL;
constexpr uint32_t kPressureNoteIdShift = 8;
constexpr PressureMailboxWord kPressureNoteIdMask = 0x7fffffffULL;
constexpr uint32_t kPressureGenerationShift = 39;
constexpr PressureMailboxWord kPressureGenerationMask = 0x1ffffffULL;
constexpr PressureMailboxWord kPressureGenerationBitsMask =
        kPressureGenerationMask << kPressureGenerationShift;

enum class Sf2ReleaseProfile : int32_t {
    Piano = 0,
    Default = 1,
    Sustained = 2,
    Lead = 3,
    Percussive = 4
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

struct FluidHandle {
    fluid_settings_t *settings = nullptr;
    fluid_synth_t *synth = nullptr;
    int soundfontId = 0;
    std::atomic<bool> loading{false};
    std::array<FluidProgramState, kFluidMidiChannelCount> programs{};
    std::array<FluidTuningState, kFluidMidiChannelCount> tunings{};
    int32_t midiChannelCount = kSourceMidiChannelCount;
    int32_t playbackChannelCursor = 0;
    bool channelIsolationAvailable = false;
};

struct FluidNote {
    int32_t id = 0;
    int32_t synthIndex = 0;
    int32_t playbackChannel = 0;
    int32_t key = 0;
    int32_t velocity = 0;
    int32_t expression = 127;
    int32_t pressureMailboxIndex = -1;
    int32_t bank = 0;
    int32_t program = 0;
    int32_t startFramesRemaining = 0;
    int32_t releaseFramesRemaining = 0;
    float cents = 0.0f;
    Sf2ReleaseProfile profile = Sf2ReleaseProfile::Default;
    bool started = false;
    bool releasing = false;
};

struct PlaybackTarget {
    int32_t synthIndex = -1;
    int32_t channel = -1;

    PlaybackTarget() = default;

    PlaybackTarget(int32_t synthIndex, int32_t channel)
            : synthIndex(synthIndex), channel(channel) {}

    bool isValid() const {
        return synthIndex >= 0 && channel >= 0;
    }
};

enum class SoundFontSource : int32_t {
    None = 0,
    External = 1,
    Builtin = 2
};

struct PressureMailbox {
    std::atomic<PressureMailboxWord> value{0};
};

struct EmbeddedSoundFontCursor {
    const uint8_t *data = nullptr;
    size_t size = 0;
    size_t offset = 0;
};

static const uint8_t *embeddedSoundFontData = nullptr;
static size_t embeddedSoundFontSize = 0;

static std::array<jbyte, 32> buildSoundFontKey();

static void secureClear(void *data, size_t size) {
    auto *bytes = static_cast<volatile uint8_t *>(data);
    while (size-- > 0) {
        *bytes++ = 0;
    }
}

static void secureClearAndRelease(std::vector<uint8_t> &data) {
    if (!data.empty()) {
        secureClear(data.data(), data.size());
    }
    std::vector<uint8_t>().swap(data);
}

static bool clearJniException(JNIEnv *env, const char *operation) {
    if (!env->ExceptionCheck()) {
        return false;
    }
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, TAG, "SoundFont decrypt failed during %s", operation);
    return true;
}

static bool isSoundFontData(const std::vector<uint8_t> &data) {
    if (data.size() < 12 ||
        std::memcmp(data.data(), "RIFF", 4) != 0 ||
        std::memcmp(data.data() + 8, "sfbk", 4) != 0) {
        return false;
    }
    const uint32_t riffSize = static_cast<uint32_t>(data[4]) |
                              (static_cast<uint32_t>(data[5]) << 8u) |
                              (static_cast<uint32_t>(data[6]) << 16u) |
                              (static_cast<uint32_t>(data[7]) << 24u);
    return static_cast<uint64_t>(riffSize) + 8u == data.size();
}

static bool decryptEmbeddedSoundFont(JNIEnv *env, std::vector<uint8_t> &plaintext) {
    const uintptr_t blobStart = reinterpret_cast<uintptr_t>(xen_soundfont_blob_start);
    const uintptr_t blobEnd = reinterpret_cast<uintptr_t>(xen_soundfont_blob_end);
    constexpr size_t packageHeaderBytes = kSoundFontPackageMagic.size() + kSoundFontNonceBytes;
    if (blobEnd <= blobStart) {
        return false;
    }
    const size_t packageSize = blobEnd - blobStart;
    if (packageSize <= packageHeaderBytes + kSoundFontGcmTagBytes ||
        std::memcmp(xen_soundfont_blob_start,
                    kSoundFontPackageMagic.data(),
                    kSoundFontPackageMagic.size()) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Embedded SoundFont package is invalid");
        return false;
    }

    const size_t encryptedSize = packageSize - packageHeaderBytes;
    const size_t plaintextSize = encryptedSize - kSoundFontGcmTagBytes;
    if (encryptedSize > static_cast<size_t>(std::numeric_limits<jint>::max()) ||
        plaintextSize > static_cast<size_t>(std::numeric_limits<jint>::max())) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Embedded SoundFont package is too large");
        return false;
    }

    plaintext.resize(encryptedSize);
    if (env->PushLocalFrame(24) != JNI_OK) {
        secureClearAndRelease(plaintext);
        return false;
    }

    bool decrypted = false;
    auto key = buildSoundFontKey();
    do {
        jbyteArray keyArray = env->NewByteArray(static_cast<jsize>(key.size()));
        if (clearJniException(env, "key allocation") || keyArray == nullptr) {
            break;
        }
        env->SetByteArrayRegion(keyArray, 0, static_cast<jsize>(key.size()), key.data());
        if (clearJniException(env, "key copy")) {
            break;
        }

        jbyteArray nonceArray = env->NewByteArray(static_cast<jsize>(kSoundFontNonceBytes));
        if (clearJniException(env, "nonce allocation") || nonceArray == nullptr) {
            break;
        }
        env->SetByteArrayRegion(
                nonceArray,
                0,
                static_cast<jsize>(kSoundFontNonceBytes),
                reinterpret_cast<const jbyte *>(
                        xen_soundfont_blob_start + kSoundFontPackageMagic.size()));
        if (clearJniException(env, "nonce copy")) {
            break;
        }

        jclass secretKeySpecClass = env->FindClass("javax/crypto/spec/SecretKeySpec");
        if (clearJniException(env, "AES key class lookup") || secretKeySpecClass == nullptr) {
            break;
        }
        jmethodID secretKeySpecConstructor = env->GetMethodID(
                secretKeySpecClass,
                "<init>",
                "([BLjava/lang/String;)V");
        if (clearJniException(env, "AES key constructor lookup") ||
            secretKeySpecConstructor == nullptr) {
            break;
        }
        jstring aesName = env->NewStringUTF("AES");
        if (clearJniException(env, "AES name allocation") || aesName == nullptr) {
            break;
        }
        jobject secretKey = env->NewObject(
                secretKeySpecClass,
                secretKeySpecConstructor,
                keyArray,
                aesName);
        if (clearJniException(env, "AES key creation") || secretKey == nullptr) {
            break;
        }
        std::array<jbyte, 32> clearedKey{};
        env->SetByteArrayRegion(
                keyArray,
                0,
                static_cast<jsize>(clearedKey.size()),
                clearedKey.data());
        if (clearJniException(env, "temporary key clearing")) {
            break;
        }

        jclass gcmParameterSpecClass = env->FindClass("javax/crypto/spec/GCMParameterSpec");
        if (clearJniException(env, "GCM class lookup") || gcmParameterSpecClass == nullptr) {
            break;
        }
        jmethodID gcmParameterSpecConstructor = env->GetMethodID(
                gcmParameterSpecClass,
                "<init>",
                "(I[B)V");
        if (clearJniException(env, "GCM constructor lookup") ||
            gcmParameterSpecConstructor == nullptr) {
            break;
        }
        jobject gcmParameterSpec = env->NewObject(
                gcmParameterSpecClass,
                gcmParameterSpecConstructor,
                kGcmTagBits,
                nonceArray);
        if (clearJniException(env, "GCM parameter creation") || gcmParameterSpec == nullptr) {
            break;
        }

        jclass cipherClass = env->FindClass("javax/crypto/Cipher");
        if (clearJniException(env, "cipher class lookup") || cipherClass == nullptr) {
            break;
        }
        jmethodID getInstance = env->GetStaticMethodID(
                cipherClass,
                "getInstance",
                "(Ljava/lang/String;)Ljavax/crypto/Cipher;");
        if (clearJniException(env, "cipher factory lookup") || getInstance == nullptr) {
            break;
        }
        jstring transformation = env->NewStringUTF("AES/GCM/NoPadding");
        if (clearJniException(env, "cipher name allocation") || transformation == nullptr) {
            break;
        }
        jobject cipher = env->CallStaticObjectMethod(cipherClass, getInstance, transformation);
        if (clearJniException(env, "cipher creation") || cipher == nullptr) {
            break;
        }

        jmethodID init = env->GetMethodID(
                cipherClass,
                "init",
                "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V");
        if (clearJniException(env, "cipher initialization lookup") || init == nullptr) {
            break;
        }
        env->CallVoidMethod(cipher, init, kCipherDecryptMode, secretKey, gcmParameterSpec);
        if (clearJniException(env, "cipher initialization")) {
            break;
        }

        jmethodID doFinal = env->GetMethodID(
                cipherClass,
                "doFinal",
                "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)I");
        if (clearJniException(env, "cipher finalization lookup") || doFinal == nullptr) {
            break;
        }
        jobject inputBuffer = env->NewDirectByteBuffer(
                const_cast<uint8_t *>(xen_soundfont_blob_start + packageHeaderBytes),
                static_cast<jlong>(encryptedSize));
        if (clearJniException(env, "encrypted buffer setup") || inputBuffer == nullptr) {
            break;
        }
        jobject outputBuffer = env->NewDirectByteBuffer(
                plaintext.data(),
                static_cast<jlong>(plaintext.size()));
        if (clearJniException(env, "plaintext buffer setup") || outputBuffer == nullptr) {
            break;
        }
        const jint written = env->CallIntMethod(cipher, doFinal, inputBuffer, outputBuffer);
        if (clearJniException(env, "authenticated decryption") ||
            written != static_cast<jint>(plaintextSize)) {
            break;
        }
        plaintext.resize(plaintextSize);
        decrypted = isSoundFontData(plaintext);
        if (!decrypted) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Decrypted SoundFont validation failed");
        }
    } while (false);

    secureClear(key.data(), key.size());
    env->PopLocalFrame(nullptr);
    if (!decrypted) {
        secureClearAndRelease(plaintext);
    }
    return decrypted;
}

static void *openEmbeddedSoundFont(const char *filename) {
    if (filename == nullptr || std::strcmp(filename, kBuiltinSoundFontUri) != 0 ||
        embeddedSoundFontData == nullptr || embeddedSoundFontSize == 0) {
        return nullptr;
    }
    return new EmbeddedSoundFontCursor{
            embeddedSoundFontData,
            embeddedSoundFontSize,
            0
    };
}

static int readEmbeddedSoundFont(void *buffer, fluid_long_long_t count, void *handle) {
    auto *cursor = static_cast<EmbeddedSoundFontCursor *>(handle);
    if (cursor == nullptr || buffer == nullptr || count < 0 ||
        cursor->offset > cursor->size ||
        static_cast<uint64_t>(count) > cursor->size - cursor->offset) {
        return FLUID_FAILED;
    }
    std::memcpy(buffer, cursor->data + cursor->offset, static_cast<size_t>(count));
    cursor->offset += static_cast<size_t>(count);
    return FLUID_OK;
}

static int seekEmbeddedSoundFont(void *handle, fluid_long_long_t offset, int origin) {
    auto *cursor = static_cast<EmbeddedSoundFontCursor *>(handle);
    if (cursor == nullptr || cursor->size > static_cast<size_t>(
            std::numeric_limits<fluid_long_long_t>::max())) {
        return FLUID_FAILED;
    }
    fluid_long_long_t base = 0;
    switch (origin) {
        case SEEK_SET:
            break;
        case SEEK_CUR:
            base = static_cast<fluid_long_long_t>(cursor->offset);
            break;
        case SEEK_END:
            base = static_cast<fluid_long_long_t>(cursor->size);
            break;
        default:
            return FLUID_FAILED;
    }
    if ((offset > 0 && base > std::numeric_limits<fluid_long_long_t>::max() - offset) ||
        (offset < 0 && offset < -base)) {
        return FLUID_FAILED;
    }
    const fluid_long_long_t next = base + offset;
    if (next < 0 || static_cast<uint64_t>(next) > cursor->size) {
        return FLUID_FAILED;
    }
    cursor->offset = static_cast<size_t>(next);
    return FLUID_OK;
}

static fluid_long_long_t tellEmbeddedSoundFont(void *handle) {
    auto *cursor = static_cast<EmbeddedSoundFontCursor *>(handle);
    if (cursor == nullptr || cursor->offset > static_cast<size_t>(
            std::numeric_limits<fluid_long_long_t>::max())) {
        return FLUID_FAILED;
    }
    return static_cast<fluid_long_long_t>(cursor->offset);
}

static int closeEmbeddedSoundFont(void *handle) {
    delete static_cast<EmbeddedSoundFontCursor *>(handle);
    return FLUID_OK;
}

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
        soundFontSource = SoundFontSource::External;
        soundFontPath = path;
        selectDefaultProgramsLocked(fluid);
        applyPitchCalibrationLocked(fluid);
        applyReverbLocked(fluid);
        fluid.loading.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, TAG, "sf2 loaded: %s", path);
        return true;
    }

    bool loadBuiltinSf2(JNIEnv *env) {
        std::vector<uint8_t> decryptedSoundFont;
        if (env == nullptr || !decryptEmbeddedSoundFont(env, decryptedSoundFont)) {
            return false;
        }

        std::lock_guard<std::mutex> lock(controlMutex);
        if (!openFluidSynthLocked() || fluid.synth == nullptr) {
            secureClearAndRelease(decryptedSoundFont);
            return false;
        }
        unloadSf2Locked();
        builtinSoundFont.swap(decryptedSoundFont);
        embeddedSoundFontData = builtinSoundFont.data();
        embeddedSoundFontSize = builtinSoundFont.size();
        fluid.loading.store(true, std::memory_order_release);
        const int soundfontId = fluid_synth_sfload(fluid.synth, kBuiltinSoundFontUri, 1);
        if (soundfontId == FLUID_FAILED) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "embedded sf2 load failed");
            fluid.loading.store(false, std::memory_order_release);
            clearBuiltinSoundFontLocked();
            return false;
        }
        fluid.soundfontId = soundfontId;
        soundFontSource = SoundFontSource::Builtin;
        soundFontPath.clear();
        selectDefaultProgramsLocked(fluid);
        applyPitchCalibrationLocked(fluid);
        applyReverbLocked(fluid);
        fluid.loading.store(false, std::memory_order_release);
        __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "embedded sf2 loaded from authenticated native memory (%zu bytes)",
                builtinSoundFont.size());
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
            double delaySeconds,
            int expression) {
        std::unique_lock<std::mutex> lock(synthMutex);
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
        const int32_t bank = resolvePresetBankLocked(fluid, requestedBank, safeProgram);
        if (bank < 0) {
            return -1;
        }
        const Sf2ReleaseProfile profile = classifyReleaseProfile(safeSourceChannel, bank, safeProgram);
        PlaybackTarget target = allocatePlaybackChannelLocked(safeSourceChannel, key);
        if (!target.isValid()) {
            lock.unlock();
            addFluidInstanceOnDemand(safeSourceChannel);
            lock.lock();
            if (!isFluidReadyLocked()) {
                return -1;
            }
            target = allocatePlaybackChannelLocked(safeSourceChannel, key);
            if (!target.isValid()) {
                target = allocateBestEffortPlaybackChannelLocked(safeSourceChannel, key);
            }
        }
        if (!target.isValid()) {
            return -1;
        }
        const int32_t noteId = nextFluidNoteId();
        FluidNote note{};
        note.id = noteId;
        note.synthIndex = target.synthIndex;
        note.playbackChannel = target.channel;
        note.key = key;
        note.velocity = std::min(127, velocity);
        note.expression = clampInt(expression, 0, 127);
        note.bank = bank;
        note.program = safeProgram;
        note.startFramesRemaining = framesFromSeconds(delaySeconds);
        note.cents = cents;
        note.profile = profile;
        note.started = note.startFramesRemaining <= 0;
        if (note.started && !startFluidNoteLocked(note)) {
            return -1;
        }
        note.pressureMailboxIndex = claimPressureMailboxLocked(noteId, note.expression);
        activeFluidNotes.push_back(note);
        return noteId;
    }

    void noteOff(int noteId, bool immediate) {
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
            releasePressureMailboxLocked(*found);
            activeFluidNotes.erase(found);
            return;
        }
        if (immediate) {
            stopFluidNoteLocked(*found);
            releasePressureMailboxLocked(*found);
            activeFluidNotes.erase(found);
            return;
        }
        if (!found->releasing) {
            found->releasing = true;
            found->releaseFramesRemaining = releaseFramesForProfile(found->profile);
            if (found->releaseFramesRemaining <= 0) {
                stopFluidNoteLocked(*found);
                releasePressureMailboxLocked(*found);
                activeFluidNotes.erase(found);
            }
        }
    }

    void setNotePressure(int noteId, int expression) {
        if (noteId <= 0) {
            return;
        }
        // A producer only replaces this note's tagged payload; it never touches synth state.
        const PressureMailboxWord safeExpression = static_cast<PressureMailboxWord>(
                clampInt(expression, 0, 127));
        const size_t start = static_cast<uint32_t>(noteId) % kPressureMailboxCount;
        for (size_t offset = 0; offset < kPressureMailboxCount; offset++) {
            PressureMailbox &mailbox = pressureMailboxes[
                    (start + offset) % kPressureMailboxCount];
            PressureMailboxWord current = mailbox.value.load(std::memory_order_acquire);
            const PressureMailboxWord generation = current & kPressureGenerationBitsMask;
            while (pressureMailboxNoteId(current) == noteId &&
                   (current & kPressureGenerationBitsMask) == generation) {
                const PressureMailboxWord next =
                        (current & ~kPressurePayloadMask) |
                        safeExpression |
                        kPressureDirtyBit;
                if (mailbox.value.compare_exchange_weak(
                        current,
                        next,
                        std::memory_order_release,
                        std::memory_order_relaxed)) {
                    return;
                }
            }
        }
    }

    void allSoundOff() {
        std::lock_guard<std::mutex> lock(synthMutex);
        forEachFluidInstanceLocked([](FluidHandle &instance, int32_t) {
            if (instance.synth != nullptr) {
                fluid_synth_all_sounds_off(instance.synth, -1);
            }
            instance.playbackChannelCursor = 0;
        });
        activeFluidNotes.clear();
        clearPressureMailboxesLocked();
        fallbackSynthCursor = 0;
    }

    void setGain(float gain) {
        const float safeGain = std::max(0.0f, std::min(kFluidMaxGain, gain));
        fluidGain.store(safeGain, std::memory_order_release);
        std::lock_guard<std::mutex> lock(synthMutex);
        forEachFluidInstanceLocked([safeGain](FluidHandle &instance, int32_t) {
            if (instance.synth != nullptr) {
                fluid_synth_set_gain(instance.synth, safeGain);
            }
        });
    }

    void setReverb(int value) {
        std::lock_guard<std::mutex> lock(synthMutex);
        reverbValue.store(std::max(0, std::min(100, value)), std::memory_order_release);
        forEachFluidInstanceLocked([this](FluidHandle &instance, int32_t) {
            applyReverbLocked(instance);
        });
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
        forEachFluidInstanceLocked([this](FluidHandle &instance, int32_t) {
            applyPitchCalibrationLocked(instance);
        });
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
                drainNotePressureLocked();
                renderFluidInstancesLocked(out, numFrames);
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
        return initializeFluidInstanceLocked(fluid, true);
    }

    bool initializeFluidInstanceLocked(FluidHandle &instance, bool primary) {
        instance.loading.store(true, std::memory_order_release);
        instance.settings = new_fluid_settings();
        if (instance.settings == nullptr) {
            instance.loading.store(false, std::memory_order_release);
            return false;
        }
        fluid_settings_setint(instance.settings, "synth.threadsafe-api", 1);
        fluid_settings_setint(instance.settings, "synth.cpu-cores", primary ? 4 : 1);
        fluid_settings_setint(instance.settings, "audio.realtime-prio", 99);
        fluid_settings_setint(instance.settings, "synth.midi-channels", kFluidMidiChannelCount);
        fluid_settings_setint(instance.settings, "synth.polyphony", 1024);
        fluid_settings_setnum(instance.settings, "synth.sample-rate", kSampleRate);
        fluid_settings_setnum(
                instance.settings,
                "synth.gain",
                fluidGain.load(std::memory_order_acquire));
        fluid_settings_setint(instance.settings, "synth.reverb.active", 1);
        fluid_settings_setint(instance.settings, "synth.chorus.active", 1);
        instance.synth = new_fluid_synth(instance.settings);
        instance.soundfontId = 0;
        instance.programs.fill(FluidProgramState{});
        instance.tunings.fill(FluidTuningState{});
        if (instance.synth == nullptr) {
            closeFluidInstanceLocked(instance);
            return false;
        }
        fluid_sfloader_t *embeddedLoader = new_fluid_defsfloader(instance.settings);
        if (embeddedLoader == nullptr ||
            fluid_sfloader_set_callbacks(
                    embeddedLoader,
                    openEmbeddedSoundFont,
                    readEmbeddedSoundFont,
                    seekEmbeddedSoundFont,
                    tellEmbeddedSoundFont,
                    closeEmbeddedSoundFont) != FLUID_OK) {
            if (embeddedLoader != nullptr) {
                delete_fluid_sfloader(embeddedLoader);
            }
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not register embedded sf2 loader");
            closeFluidInstanceLocked(instance);
            return false;
        }
        fluid_synth_add_sfloader(instance.synth, embeddedLoader);
        instance.midiChannelCount = clampInt(
                fluid_synth_count_midi_channels(instance.synth),
                kSourceMidiChannelCount,
                kFluidMidiChannelCount);
        instance.channelIsolationAvailable =
                instance.midiChannelCount > kSourceMidiChannelCount;
        instance.playbackChannelCursor = 0;
        if (!instance.channelIsolationAvailable && !loggedChannelIsolationUnavailable) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "FluidSynth exposed only %d MIDI channels; dense microtonal slides use best-effort playback",
                                instance.midiChannelCount);
            loggedChannelIsolationUnavailable = true;
        }
        fluid_synth_set_interp_method(instance.synth, -1, FLUID_INTERP_HIGHEST);
        fluid_synth_set_reverb_group_roomsize(instance.synth, -1, kFluidReverbRoomSize);
        fluid_synth_set_reverb_group_damp(instance.synth, -1, kFluidReverbDamp);
        fluid_synth_set_reverb_group_width(instance.synth, -1, kFluidReverbWidth);
        fluid_synth_chorus_on(instance.synth, -1, 1);
        fluid_synth_set_chorus_group_nr(instance.synth, -1, kFluidChorusVoiceCount);
        fluid_synth_set_chorus_group_level(instance.synth, -1, kFluidChorusLevel);
        fluid_synth_set_chorus_group_speed(instance.synth, -1, kFluidChorusSpeed);
        fluid_synth_set_chorus_group_depth(instance.synth, -1, kFluidChorusDepthMs);
        fluid_synth_set_chorus_group_type(instance.synth, -1, FLUID_CHORUS_MOD_SINE);
        instance.loading.store(false, std::memory_order_release);
        return true;
    }

    bool addFluidInstanceOnDemand(int32_t sourceChannel) {
        std::lock_guard<std::mutex> controlLock(controlMutex);
        {
            std::lock_guard<std::mutex> synthLock(synthMutex);
            if (!isFluidReadyLocked()) {
                return false;
            }
            if (hasUnusedPlaybackChannelLocked(sourceChannel)) {
                return true;
            }
            if (fluidExpansionFailed ||
                fluidInstanceCountLocked() >= kMaxFluidSynthInstanceCount) {
                if (!loggedFluidExpansionLimit) {
                    __android_log_print(
                            ANDROID_LOG_WARN,
                            TAG,
                            "Microtonal synth pool reached its %d-instance limit; using best-effort channel sharing",
                            kMaxFluidSynthInstanceCount);
                    loggedFluidExpansionLimit = true;
                }
                return false;
            }
        }

        auto instance = std::unique_ptr<FluidHandle>(new FluidHandle());
        if (!initializeFluidInstanceLocked(*instance, false)) {
            fluidExpansionFailed = true;
            return false;
        }
        const char *source = soundFontSource == SoundFontSource::Builtin
                             ? kBuiltinSoundFontUri
                             : soundFontPath.c_str();
        if (soundFontSource == SoundFontSource::None || source[0] == '\0') {
            closeFluidInstanceLocked(*instance);
            return false;
        }
        instance->loading.store(true, std::memory_order_release);
        const int soundfontId = fluid_synth_sfload(instance->synth, source, 1);
        if (soundfontId == FLUID_FAILED) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not expand FluidSynth instance pool");
            closeFluidInstanceLocked(*instance);
            fluidExpansionFailed = true;
            return false;
        }
        instance->soundfontId = soundfontId;
        selectDefaultProgramsLocked(*instance);
        applyPitchCalibrationLocked(*instance);
        applyReverbLocked(*instance);
        instance->loading.store(false, std::memory_order_release);

        int32_t instanceCount = 0;
        {
            std::lock_guard<std::mutex> synthLock(synthMutex);
            if (!isFluidReadyLocked()) {
                closeFluidInstanceLocked(*instance);
                return false;
            }
            extraFluidInstances.push_back(std::move(instance));
            instanceCount = fluidInstanceCountLocked();
        }
        __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "Expanded microtonal synth pool to %d instances",
                instanceCount);
        return true;
    }

    void closeFluidSynthLocked() {
        fluid.loading.store(true, std::memory_order_release);
        std::lock_guard<std::mutex> lock(synthMutex);
        for (auto &instance : extraFluidInstances) {
            closeFluidInstanceLocked(*instance);
        }
        extraFluidInstances.clear();
        closeFluidInstanceLocked(fluid);
        fluid.loading.store(false, std::memory_order_release);
        activeFluidNotes.clear();
        clearPressureMailboxesLocked();
        soundFontSource = SoundFontSource::None;
        soundFontPath.clear();
        fluidExpansionFailed = false;
        loggedFluidExpansionLimit = false;
        fallbackSynthCursor = 0;
        clearBuiltinSoundFontLocked();
    }

    static void closeFluidInstanceLocked(FluidHandle &instance) {
        if (instance.synth != nullptr) {
            delete_fluid_synth(instance.synth);
            instance.synth = nullptr;
        }
        if (instance.settings != nullptr) {
            delete_fluid_settings(instance.settings);
            instance.settings = nullptr;
        }
        instance.soundfontId = 0;
        instance.programs.fill(FluidProgramState{});
        instance.tunings.fill(FluidTuningState{});
        instance.midiChannelCount = kSourceMidiChannelCount;
        instance.playbackChannelCursor = 0;
        instance.channelIsolationAvailable = false;
        instance.loading.store(false, std::memory_order_release);
    }

    void unloadSf2Locked() {
        if (fluid.synth != nullptr) {
            fluid.loading.store(true, std::memory_order_release);
            std::lock_guard<std::mutex> lock(synthMutex);
            for (auto &instance : extraFluidInstances) {
                fluid_synth_all_sounds_off(instance->synth, -1);
                closeFluidInstanceLocked(*instance);
            }
            extraFluidInstances.clear();
            if (fluid.soundfontId > 0) {
                fluid_synth_all_sounds_off(fluid.synth, -1);
                fluid_synth_sfunload(fluid.synth, fluid.soundfontId, 1);
            }
            fluid.soundfontId = 0;
            activeFluidNotes.clear();
            clearPressureMailboxesLocked();
            fluid.programs.fill(FluidProgramState{});
            fluid.tunings.fill(FluidTuningState{});
            fluid.playbackChannelCursor = 0;
            fluid.loading.store(false, std::memory_order_release);
        }
        soundFontSource = SoundFontSource::None;
        soundFontPath.clear();
        fluidExpansionFailed = false;
        loggedFluidExpansionLimit = false;
        fallbackSynthCursor = 0;
        clearBuiltinSoundFontLocked();
    }

    void clearBuiltinSoundFontLocked() {
        embeddedSoundFontData = nullptr;
        embeddedSoundFontSize = 0;
        secureClearAndRelease(builtinSoundFont);
    }

    void selectDefaultProgramsLocked(FluidHandle &instance) {
        for (int32_t channel = 0; channel < instance.midiChannelCount; channel++) {
            const int32_t bank = channel == kDrumChannel ? kDrumBank : 0;
            selectProgramLocked(instance, channel, bank, 0);
        }
    }

    bool selectProgramLocked(
            FluidHandle &instance,
            int32_t channel,
            int32_t requestedBank,
            int32_t requestedProgram) {
        if (instance.synth == nullptr || instance.soundfontId <= 0 ||
            channel < 0 || channel >= instance.midiChannelCount) {
            return false;
        }
        const int32_t program = clampInt(requestedProgram, 0, 127);
        const int32_t bank = resolvePresetBankLocked(instance, requestedBank, program);
        if (bank < 0) {
            return false;
        }
        const FluidProgramState next{bank, program};
        if (instance.programs[channel].bank == next.bank &&
            instance.programs[channel].program == next.program) {
            return true;
        }
        if (fluid_synth_program_select(
                instance.synth,
                channel,
                instance.soundfontId,
                bank,
                program) == FLUID_FAILED) {
            return false;
        }
        instance.programs[channel] = next;
        return true;
    }

    int32_t resolvePresetBankLocked(
            FluidHandle &instance,
            int32_t requestedBank,
            int32_t program) {
        fluid_sfont_t *soundFont = fluid_synth_get_sfont_by_id(
                instance.synth,
                instance.soundfontId);
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

    void applyPitchCalibrationLocked(FluidHandle &instance) {
        if (instance.synth == nullptr) {
            return;
        }
        double pitch[kMidiKeyCount];
        for (int32_t i = 0; i < kMidiKeyCount; i++) {
            pitch[i] = static_cast<double>(i) * 100.0 +
                       static_cast<double>(
                               pitchCalibrationCents[i % kPitchClassCount].load(
                                       std::memory_order_acquire));
        }
        for (int32_t channel = 0; channel < instance.midiChannelCount; channel++) {
            const int32_t tuningBank = tuningBankForChannel(channel);
            const int32_t tuningProgram = tuningProgramForChannel(channel);
            fluid_synth_activate_key_tuning(instance.synth, tuningBank, tuningProgram,
                                            "XenSynth tuning", pitch, 1);
            fluid_synth_activate_tuning(
                    instance.synth,
                    channel,
                    tuningBank,
                    tuningProgram,
                    1);
        }
        instance.tunings.fill(FluidTuningState{});
    }

    void applyReverbLocked(FluidHandle &instance) {
        if (instance.synth == nullptr) {
            return;
        }
        const int value = reverbValue.load(std::memory_order_acquire);
        if (value <= 0) {
            fluid_synth_reverb_on(instance.synth, -1, 0);
        } else {
            fluid_synth_reverb_on(instance.synth, -1, 1);
            fluid_synth_set_reverb_group_roomsize(
                    instance.synth,
                    -1,
                    kFluidReverbRoomSize);
            fluid_synth_set_reverb_group_damp(instance.synth, -1, kFluidReverbDamp);
            fluid_synth_set_reverb_group_width(instance.synth, -1, kFluidReverbWidth);
            fluid_synth_set_reverb_group_level(
                    instance.synth,
                    -1,
                    std::min(1.0, static_cast<double>(value) / 100.0 * kFluidReverbMaxLevel));
        }
    }

    bool startFluidNoteLocked(const FluidNote &note) {
        FluidHandle *instance = fluidInstanceAtLocked(note.synthIndex);
        if (instance == nullptr ||
            !selectProgramLocked(*instance, note.playbackChannel, note.bank, note.program)) {
            return false;
        }
        const double calibrationCents = static_cast<double>(
                pitchCalibrationCents[note.key % kPitchClassCount].load(std::memory_order_acquire));
        const double pitch = static_cast<double>(note.key) * 100.0 +
                             static_cast<double>(note.cents) +
                             calibrationCents;
        if (!ensureTuningForNoteLocked(
                *instance,
                note.playbackChannel,
                note.key,
                pitch)) {
            return false;
        }
        applyNotePressureLocked(*instance, note);
        return fluid_synth_noteon(
                instance->synth,
                note.playbackChannel,
                note.key,
                note.velocity) != FLUID_FAILED;
    }

    static void applyNotePressureLocked(FluidHandle &instance, const FluidNote &note) {
        fluid_synth_key_pressure(
                instance.synth,
                note.playbackChannel,
                note.key,
                note.expression);
        if (instance.channelIsolationAvailable) {
            fluid_synth_cc(
                    instance.synth,
                    note.playbackChannel,
                    kMidiExpressionController,
                    note.expression);
        }
    }

    static PressureMailboxWord packPressureMailboxValue(
            int32_t noteId,
            int32_t expression,
            bool dirty,
            PressureMailboxWord generation) {
        return ((generation & kPressureGenerationMask) << kPressureGenerationShift) |
               (static_cast<PressureMailboxWord>(static_cast<uint32_t>(noteId))
                << kPressureNoteIdShift) |
               static_cast<PressureMailboxWord>(expression) |
               (dirty ? kPressureDirtyBit : 0ULL);
    }

    static int32_t pressureMailboxNoteId(PressureMailboxWord value) {
        return static_cast<int32_t>(
                (value >> kPressureNoteIdShift) & kPressureNoteIdMask);
    }

    int32_t claimPressureMailboxLocked(int32_t noteId, int32_t expression) {
        const size_t start = static_cast<uint32_t>(noteId) % kPressureMailboxCount;
        for (size_t offset = 0; offset < kPressureMailboxCount; offset++) {
            const size_t index = (start + offset) % kPressureMailboxCount;
            PressureMailboxWord expected = pressureMailboxes[index].value.load(
                    std::memory_order_acquire);
            if (pressureMailboxNoteId(expected) != 0) {
                continue;
            }
            const PressureMailboxWord generation =
                    ((expected >> kPressureGenerationShift) + 1ULL) &
                    kPressureGenerationMask;
            const PressureMailboxWord initial = packPressureMailboxValue(
                    noteId,
                    clampInt(expression, 0, 127),
                    false,
                    generation);
            if (pressureMailboxes[index].value.compare_exchange_strong(
                    expected,
                    initial,
                    std::memory_order_release,
                    std::memory_order_relaxed)) {
                return static_cast<int32_t>(index);
            }
        }
        return -1;
    }

    void releasePressureMailboxLocked(const FluidNote &note) {
        if (note.pressureMailboxIndex < 0 ||
            note.pressureMailboxIndex >= static_cast<int32_t>(kPressureMailboxCount)) {
            return;
        }
        // Preserve the generation so an in-flight producer cannot hit a reused slot.
        pressureMailboxes[note.pressureMailboxIndex].value.fetch_and(
                kPressureGenerationBitsMask,
                std::memory_order_acq_rel);
    }

    void clearPressureMailboxesLocked() {
        for (PressureMailbox &mailbox : pressureMailboxes) {
            mailbox.value.fetch_and(
                    kPressureGenerationBitsMask,
                    std::memory_order_acq_rel);
        }
    }

    void drainNotePressureLocked() {
        for (FluidNote &note : activeFluidNotes) {
            if (note.pressureMailboxIndex < 0 ||
                note.pressureMailboxIndex >= static_cast<int32_t>(kPressureMailboxCount)) {
                continue;
            }
            PressureMailbox &mailbox = pressureMailboxes[note.pressureMailboxIndex];
            PressureMailboxWord current = mailbox.value.load(std::memory_order_acquire);
            if (pressureMailboxNoteId(current) != note.id ||
                (current & kPressureDirtyBit) == 0) {
                continue;
            }
            const PressureMailboxWord consumed = current & ~kPressureDirtyBit;
            if (!mailbox.value.compare_exchange_strong(
                    current,
                    consumed,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire)) {
                // A racing producer leaves the mailbox dirty for the next callback.
                continue;
            }
            note.expression = static_cast<int32_t>(consumed & kPressureExpressionMask);
            if (note.started && !note.releasing) {
                FluidHandle *instance = fluidInstanceAtLocked(note.synthIndex);
                if (instance != nullptr) {
                    applyNotePressureLocked(*instance, note);
                }
            }
        }
    }

    void stopFluidNoteLocked(const FluidNote &note) {
        if (hasOtherSustainedFluidNoteLocked(note)) {
            return;
        }
        FluidHandle *instance = fluidInstanceAtLocked(note.synthIndex);
        if (instance != nullptr) {
            fluid_synth_noteoff(instance->synth, note.playbackChannel, note.key);
        }
    }

    static bool ensureTuningForNoteLocked(
            FluidHandle &instance,
            int32_t playbackChannel,
            int32_t key,
            double pitch) {
        if (playbackChannel < 0 || playbackChannel >= instance.midiChannelCount) {
            return false;
        }
        FluidTuningState &state = instance.tunings[playbackChannel];
        if (state.active &&
            state.key == key &&
            std::abs(state.pitch - pitch) <= kFluidTuningPitchEpsilon) {
            return true;
        }
        const int32_t tuningBank = tuningBankForChannel(playbackChannel);
        const int32_t tuningProgram = tuningProgramForChannel(playbackChannel);
        const int32_t tunedKey = key;
        if (fluid_synth_tune_notes(
                instance.synth,
                tuningBank,
                tuningProgram,
                1,
                &tunedKey,
                &pitch,
                1) ==
            FLUID_FAILED) {
            return false;
        }
        if (fluid_synth_activate_tuning(
                instance.synth,
                playbackChannel,
                tuningBank,
                tuningProgram,
                1) ==
            FLUID_FAILED) {
            return false;
        }
        state.key = key;
        state.pitch = pitch;
        state.active = true;
        return true;
    }

    void finishPendingFluidNoteOffsLocked(
            int32_t synthIndex,
            int32_t playbackChannel,
            int32_t key,
            Sf2ReleaseProfile profile,
            int32_t excludedNoteId) {
        for (auto note = activeFluidNotes.begin(); note != activeFluidNotes.end();) {
            if (note->id != excludedNoteId &&
                note->started &&
                note->releasing &&
                note->synthIndex == synthIndex &&
                note->playbackChannel == playbackChannel &&
                note->key == key &&
                note->profile == profile) {
                stopFluidNoteLocked(*note);
                releasePressureMailboxLocked(*note);
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
                        note->synthIndex,
                        note->playbackChannel,
                        note->key,
                        note->profile,
                        note->id);
                note->started = true;
                if (!startFluidNoteLocked(*note)) {
                    releasePressureMailboxLocked(*note);
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
            releasePressureMailboxLocked(*note);
            note = activeFluidNotes.erase(note);
        }
    }

    PlaybackTarget allocatePlaybackChannelLocked(int32_t sourceChannel, int32_t) {
        PlaybackTarget target{};
        forEachFluidInstanceLocked(
                [this, sourceChannel, &target](FluidHandle &instance, int32_t synthIndex) {
                    if (target.isValid()) {
                        return;
                    }
                    if (instance.midiChannelCount <= kSourceMidiChannelCount) {
                        if (!hasActiveFluidNoteOnChannelLocked(synthIndex, sourceChannel)) {
                            target = PlaybackTarget{synthIndex, sourceChannel};
                        }
                        return;
                    }
                    const int32_t overflowCount =
                            instance.midiChannelCount - kSourceMidiChannelCount;
                    for (int32_t i = 0; i < overflowCount; i++) {
                        const int32_t channel = kSourceMidiChannelCount +
                                                (instance.playbackChannelCursor + i) %
                                                overflowCount;
                        if (!hasActiveFluidNoteOnChannelLocked(synthIndex, channel)) {
                            instance.playbackChannelCursor =
                                    (channel - kSourceMidiChannelCount + 1) % overflowCount;
                            target = PlaybackTarget{synthIndex, channel};
                            return;
                        }
                    }
                });
        return target;
    }

    PlaybackTarget allocateBestEffortPlaybackChannelLocked(
            int32_t sourceChannel,
            int32_t key) {
        PlaybackTarget target{};
        forEachFluidInstanceLocked(
                [this, sourceChannel, key, &target](
                        FluidHandle &instance,
                        int32_t synthIndex) {
                    if (target.isValid()) {
                        return;
                    }
                    if (instance.midiChannelCount <= kSourceMidiChannelCount) {
                        target = PlaybackTarget{synthIndex, sourceChannel};
                        return;
                    }
                    const int32_t overflowCount =
                            instance.midiChannelCount - kSourceMidiChannelCount;
                    for (int32_t i = 0; i < overflowCount; i++) {
                        const int32_t channel = kSourceMidiChannelCount +
                                                (instance.playbackChannelCursor + i) %
                                                overflowCount;
                        if (!hasActiveFluidNoteForKeyLocked(synthIndex, channel, key)) {
                            instance.playbackChannelCursor =
                                    (channel - kSourceMidiChannelCount + 1) % overflowCount;
                            target = PlaybackTarget{synthIndex, channel};
                            return;
                        }
                    }
                });
        if (target.isValid()) {
            return target;
        }
        const int32_t instanceCount = fluidInstanceCountLocked();
        if (instanceCount <= 0) {
            return target;
        }
        const int32_t synthIndex = fallbackSynthCursor % instanceCount;
        fallbackSynthCursor = (fallbackSynthCursor + 1) % instanceCount;
        FluidHandle *instance = fluidInstanceAtLocked(synthIndex);
        if (instance == nullptr || instance->midiChannelCount <= kSourceMidiChannelCount) {
            return PlaybackTarget{synthIndex, sourceChannel};
        }
        const int32_t overflowCount = instance->midiChannelCount - kSourceMidiChannelCount;
        const int32_t channel = kSourceMidiChannelCount + instance->playbackChannelCursor;
        instance->playbackChannelCursor =
                (instance->playbackChannelCursor + 1) % overflowCount;
        return PlaybackTarget{synthIndex, channel};
    }

    bool hasUnusedPlaybackChannelLocked(int32_t sourceChannel) const {
        const int32_t instanceCount = fluidInstanceCountLocked();
        for (int32_t synthIndex = 0; synthIndex < instanceCount; synthIndex++) {
            const FluidHandle *instance = fluidInstanceAtLocked(synthIndex);
            if (instance == nullptr) {
                continue;
            }
            const int32_t firstChannel = instance->midiChannelCount > kSourceMidiChannelCount
                                         ? kSourceMidiChannelCount
                                         : sourceChannel;
            const int32_t channelLimit = instance->midiChannelCount > kSourceMidiChannelCount
                                         ? instance->midiChannelCount
                                         : sourceChannel + 1;
            for (int32_t channel = firstChannel;
                 channel < channelLimit;
                 channel++) {
                if (!hasActiveFluidNoteOnChannelLocked(synthIndex, channel)) {
                    return true;
                }
            }
        }
        return false;
    }

    bool hasActiveFluidNoteOnChannelLocked(
            int32_t synthIndex,
            int32_t playbackChannel) const {
        return std::any_of(
                activeFluidNotes.begin(),
                activeFluidNotes.end(),
                [synthIndex, playbackChannel](const FluidNote &note) {
                    return note.synthIndex == synthIndex &&
                           note.playbackChannel == playbackChannel;
                });
    }

    bool hasActiveFluidNoteForKeyLocked(
            int32_t synthIndex,
            int32_t playbackChannel,
            int32_t key) const {
        return std::any_of(
                activeFluidNotes.begin(),
                activeFluidNotes.end(),
                [synthIndex, playbackChannel, key](const FluidNote &note) {
                    return note.synthIndex == synthIndex &&
                           note.playbackChannel == playbackChannel &&
                           note.key == key;
                });
    }

    bool hasOtherSustainedFluidNoteLocked(const FluidNote &stoppingNote) const {
        const FluidHandle *instance = fluidInstanceAtLocked(stoppingNote.synthIndex);
        if (instance == nullptr || instance->channelIsolationAvailable) {
            return false;
        }
        return std::any_of(
                activeFluidNotes.begin(),
                activeFluidNotes.end(),
                [&stoppingNote](const FluidNote &note) {
                    return note.id != stoppingNote.id &&
                           note.started &&
                           !note.releasing &&
                           note.synthIndex == stoppingNote.synthIndex &&
                           note.playbackChannel == stoppingNote.playbackChannel &&
                           note.key == stoppingNote.key;
                });
    }

    FluidHandle *fluidInstanceAtLocked(int32_t synthIndex) {
        if (synthIndex == 0) {
            return &fluid;
        }
        const size_t extraIndex = static_cast<size_t>(synthIndex - 1);
        if (synthIndex < 0 || extraIndex >= extraFluidInstances.size()) {
            return nullptr;
        }
        return extraFluidInstances[extraIndex].get();
    }

    const FluidHandle *fluidInstanceAtLocked(int32_t synthIndex) const {
        if (synthIndex == 0) {
            return &fluid;
        }
        const size_t extraIndex = static_cast<size_t>(synthIndex - 1);
        if (synthIndex < 0 || extraIndex >= extraFluidInstances.size()) {
            return nullptr;
        }
        return extraFluidInstances[extraIndex].get();
    }

    int32_t fluidInstanceCountLocked() const {
        return fluid.synth == nullptr
               ? 0
               : static_cast<int32_t>(extraFluidInstances.size()) + 1;
    }

    template<typename Callback>
    void forEachFluidInstanceLocked(Callback callback) {
        if (fluid.synth == nullptr) {
            return;
        }
        callback(fluid, 0);
        for (size_t i = 0; i < extraFluidInstances.size(); i++) {
            callback(*extraFluidInstances[i], static_cast<int32_t>(i + 1));
        }
    }

    void renderFluidInstancesLocked(float *out, int32_t numFrames) {
        bool renderedFirstInstance = false;
        forEachFluidInstanceLocked(
                [this, out, numFrames, &renderedFirstInstance](
                        FluidHandle &instance,
                        int32_t) {
                    if (instance.soundfontId <= 0 ||
                        instance.loading.load(std::memory_order_acquire)) {
                        return;
                    }
                    if (!renderedFirstInstance) {
                        fluid_synth_write_float(
                                instance.synth,
                                numFrames,
                                out,
                                0,
                                kChannelCount,
                                out,
                                1,
                                kChannelCount);
                        renderedFirstInstance = true;
                        return;
                    }
                    int32_t frameOffset = 0;
                    while (frameOffset < numFrames) {
                        const int32_t frames = std::min(
                                kMixScratchFrameCount,
                                numFrames - frameOffset);
                        const int32_t sampleCount = frames * kChannelCount;
                        std::fill(
                                mixScratch.begin(),
                                mixScratch.begin() + sampleCount,
                                0.0f);
                        fluid_synth_write_float(
                                instance.synth,
                                frames,
                                mixScratch.data(),
                                0,
                                kChannelCount,
                                mixScratch.data(),
                                1,
                                kChannelCount);
                        float *destination = out + frameOffset * kChannelCount;
                        for (int32_t i = 0; i < sampleCount; i++) {
                            destination[i] += mixScratch[i];
                        }
                        frameOffset += frames;
                    }
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
    std::vector<std::unique_ptr<FluidHandle>> extraFluidInstances;
    std::list<FluidNote> activeFluidNotes;
    std::array<PressureMailbox, kPressureMailboxCount> pressureMailboxes{};
    std::array<float, kMixScratchFrameCount * kChannelCount> mixScratch{};
    std::vector<uint8_t> builtinSoundFont;
    std::string soundFontPath;
    SoundFontSource soundFontSource = SoundFontSource::None;
    int32_t fluidNoteId = 0;
    int32_t fallbackSynthCursor = 0;
    bool loggedChannelIsolationUnavailable = false;
    bool fluidExpansionFailed = false;
    bool loggedFluidExpansionLimit = false;
    std::array<std::atomic<float>, kPitchClassCount> pitchCalibrationCents{};
    std::atomic<float> fluidGain{kFluidDefaultGain};
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

JNIEXPORT jboolean JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_loadBuiltinSf2Native(JNIEnv *env, jclass) {
    return engine.loadBuiltinSf2(env);
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
        jdouble delaySeconds,
        jint expression) {
    return engine.noteOn(
            key,
            velocity,
            cents,
            channel,
            program,
            bankMsb,
            bankLsb,
            delaySeconds,
            expression);
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_noteOffNative(
        JNIEnv *,
        jclass,
        jint noteId,
        jboolean immediate) {
    engine.noteOff(noteId, immediate == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_icu_ringona_xensynth_audio_NativeAudioEngine_setNotePressureNative(
        JNIEnv *,
        jclass,
        jint noteId,
        jint expression) {
    engine.setNotePressure(noteId, expression);
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
