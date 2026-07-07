/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <android/log.h>

#include "stream/InputStream.h"

#include "AudioEncoding.h"
#include "WavRIFFChunkHeader.h"
#include "WavFmtChunkHeader.h"
#include "WavChunkHeader.h"
#include "WavStreamReader.h"

static const char *TAG = "WavStreamReader";

namespace parselib {

    static float clampAudioSample(float sample) {
        return std::max(-1.0f, std::min(1.0f, sample));
    }

    static int32_t readSignedLittleEndian(const unsigned char *data, int bytesPerSample) {
        uint32_t value = 0;
        for (int byteIndex = 0; byteIndex < bytesPerSample; byteIndex++) {
            value |= (uint32_t) data[byteIndex] << (byteIndex * 8);
        }
        int shift = (4 - bytesPerSample) * 8;
        return ((int32_t) (value << shift)) >> shift;
    }

    static float convertPcmSampleToFloat(const unsigned char *data, int bitsPerSample) {
        switch (bitsPerSample) {
            case 8:
                return ((float) data[0] - 128.0f) / 128.0f;
            case 16:
                return (float) readSignedLittleEndian(data, 2) / 32768.0f;
            case 24:
                return (float) readSignedLittleEndian(data, 3) / 8388608.0f;
            case 32:
                return (float) readSignedLittleEndian(data, 4) / 2147483648.0f;
            default:
                return 0.0f;
        }
    }

    static float convertFloatSampleToFloat(const unsigned char *data, int bitsPerSample) {
        if (bitsPerSample != 32) {
            return 0.0f;
        }
        float sample;
        memcpy(&sample, data, sizeof(sample));
        return clampAudioSample(sample);
    }

    WavStreamReader::WavStreamReader(InputStream *stream) {
        mStream = stream;

        mWavChunk = nullptr;
        mFmtChunk = nullptr;
        mDataChunk = nullptr;

        mAudioDataStartPos = -1;

        mChunkMap = new std::map<RiffID, WavChunkHeader *>();
    }

    int WavStreamReader::getSampleEncoding() {
        if (mFmtChunk->mEncodingId == WavFmtChunkHeader::ENCODING_PCM) {
            switch (mFmtChunk->mSampleSize) {
                case 8:
                    return AudioEncoding::PCM_8;

                case 16:
                    return AudioEncoding::PCM_16;

                case 24:
                    return AudioEncoding::PCM_24;

                case 32:
                    return AudioEncoding::PCM_32;

                default:
                    return AudioEncoding::INVALID;
            }
        } else if (mFmtChunk->mEncodingId == WavFmtChunkHeader::ENCODING_IEEE_FLOAT) {
            return AudioEncoding::PCM_IEEEFLOAT;
        }

        return AudioEncoding::INVALID;
    }

    void WavStreamReader::parse() {
        RiffID tag;

        while (true) {
            int numRead = mStream->peek(&tag, sizeof(tag));
            if (numRead <= 0) {
                break; // done
            }

            WavChunkHeader *chunk;
            if (tag == WavRIFFChunkHeader::RIFFID_RIFF) {
                chunk = mWavChunk = new WavRIFFChunkHeader(tag);
                mWavChunk->read(mStream);
            } else if (tag == WavFmtChunkHeader::RIFFID_FMT) {
                chunk = mFmtChunk = new WavFmtChunkHeader(tag);
                mFmtChunk->read(mStream);
            } else if (tag == WavChunkHeader::RIFFID_DATA) {
                chunk = mDataChunk = new WavChunkHeader(tag);
                mDataChunk->read(mStream);
                // We are now positioned at the start of the audio data.
                mAudioDataStartPos = mStream->getPos();
                mStream->advance(mDataChunk->mChunkSize);
            } else {
                // 【关键修复】遇到未知 chunk（如 LIST、JUNK、fact 等），应该跳过当前 chunk 的 body
                chunk = new WavChunkHeader(tag);
                chunk->read(mStream);
                // ❌ 错误：mStream->advance(mDataChunk->mChunkSize); // mDataChunk 此时可能为 nullptr
                // ✅ 正确：应该跳过当前 chunk 的 body
                __android_log_print(ANDROID_LOG_DEBUG, TAG, "[WAV] 跳过未知 chunk, tag=0x%08X, size=%d", tag, chunk->mChunkSize);
                mStream->advance(chunk->mChunkSize); // 使用当前 chunk 的大小
            }

            (*mChunkMap)[tag] = chunk;
        }

        if (mDataChunk != nullptr) {
            mStream->setPos(mAudioDataStartPos);
        }
    }

// Data access
    void WavStreamReader::positionToAudio() {
        if (mDataChunk != nullptr) {
            mStream->setPos(mAudioDataStartPos);
        }
    }

    int WavStreamReader::getDataFloat(float *buff, int numFrames) {
        // __android_log_print(ANDROID_LOG_INFO, TAG, "getData(%d)", numFrames);

        if (mDataChunk == nullptr || mFmtChunk == nullptr) {
            return 0;
        }

        int totalFramesRead = 0;

        int numChans = mFmtChunk->mNumChannels;
        if (numChans <= 0) {
            return 0;
        }
        int framesWritten = 0;
        bool excludeStartMute = true;

        int bytesPerSample = mFmtChunk->mSampleSize / 8;
        bool isPcm = mFmtChunk->mEncodingId == WavFmtChunkHeader::ENCODING_PCM;
        bool isFloat = mFmtChunk->mEncodingId == WavFmtChunkHeader::ENCODING_IEEE_FLOAT;
        bool isSupportedPcm = isPcm && (mFmtChunk->mSampleSize == 8 ||
                                       mFmtChunk->mSampleSize == 16 ||
                                       mFmtChunk->mSampleSize == 24 ||
                                       mFmtChunk->mSampleSize == 32);
        bool isSupportedFloat = isFloat && mFmtChunk->mSampleSize == 32;
        if (bytesPerSample > 0 && (isSupportedPcm || isSupportedFloat)) {
            auto *readBuff = new unsigned char[128 * numChans * bytesPerSample];
            auto *frameSamples = new float[numChans];
            int framesLeft = numFrames;
            while (framesLeft > 0) {
                int framesThisRead = std::min(framesLeft, 128);
                int numFramesRead =
                        mStream->read(readBuff, framesThisRead * bytesPerSample * numChans) /
                        (bytesPerSample * numChans);
                totalFramesRead += numFramesRead;

                for (int frame = 0; frame < numFramesRead; frame++) {
                    const int readOffset = frame * numChans;
                    bool silentFrame = true;
                    for (int channel = 0; channel < numChans; channel++) {
                        const unsigned char *sampleData =
                                readBuff + (readOffset + channel) * bytesPerSample;
                        float sample = isPcm
                                       ? convertPcmSampleToFloat(sampleData, mFmtChunk->mSampleSize)
                                       : convertFloatSampleToFloat(sampleData, mFmtChunk->mSampleSize);
                        frameSamples[channel] = sample;
                        if (std::fabs(sample) > 0.0f) {
                            silentFrame = false;
                        }
                    }
                    if (excludeStartMute && silentFrame) {
                        continue;
                    }
                    excludeStartMute = false;
                    const int writeOffset = framesWritten * numChans;
                    for (int channel = 0; channel < numChans; channel++) {
                        buff[writeOffset + channel] = frameSamples[channel];
                    }
                    framesWritten++;
                }

                if (numFramesRead < framesThisRead) {
                    break; // none left
                }

                framesLeft -= framesThisRead;
            }
            delete[] frameSamples;
            delete[] readBuff;

            // Zero out any unread frames
            if (framesWritten < numFrames) {
                memset(buff + (framesWritten * numChans), 0,
                       (numFrames - framesWritten) * sizeof(buff[0]) * numChans);
            }

            // __android_log_print(ANDROID_LOG_INFO, TAG, "  returns:%d", totalFramesRead);
            return totalFramesRead;
        }
        return 0;
    }

} // namespace parselib
