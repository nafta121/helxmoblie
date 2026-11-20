#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// GAP-12: Multi-Style Preset Clip Dry DI Audition Player (Clean / Crunch / Metal / Bass / Custom WAV)
enum class PresetClipStyle : int32_t {
    kCleanSingleCoil = 0,
    kCrunchRockRiff,
    kMetalChug,
    kBassFingerGroove,
    kCustomWavDi,
    kCount = 5,
};

class DryClipAuditionPlayer {
public:
    static constexpr size_t kBuiltInClipFrames = 48000 * 4;  // 4.0-second seamless DI loop @ 48 kHz

    DryClipAuditionPlayer() noexcept {
        SynthesizeBuiltInStyle(PresetClipStyle::kCrunchRockRiff);
    }

    void SetStyle(PresetClipStyle style) {
        style_ = style;
        if (style != PresetClipStyle::kCustomWavDi) {
            SynthesizeBuiltInStyle(style);
            playhead_ = 0;
        }
    }

    PresetClipStyle GetStyle() const noexcept { return style_; }
    void SetPlaying(bool playing) noexcept { playing_ = playing; }
    bool IsPlaying() const noexcept { return playing_; }
    void SetRepeat(bool repeat) noexcept { repeat_ = repeat; }
    bool IsRepeat() const noexcept { return repeat_; }

    void SeekSeconds(float sec) noexcept {
        if (clip_buf_.empty()) {
            playhead_ = 0;
            return;
        }
        const float clamped = Clamp(sec, 0.0f, static_cast<float>(clip_buf_.size() - 1) / kSampleRate);
        playhead_ = static_cast<size_t>(clamped * kSampleRate);
    }

    void SkipSeconds(float delta_sec) noexcept {
        const float cur_sec = static_cast<float>(playhead_) / kSampleRate;
        SeekSeconds(cur_sec + delta_sec);
    }

    float GetPlayheadNorm() const noexcept {
        if (clip_buf_.empty()) return 0.0f;
        return static_cast<float>(playhead_) / static_cast<float>(clip_buf_.size());
    }

    float GetDurationSeconds() const noexcept {
        return static_cast<float>(clip_buf_.size()) / kSampleRate;
    }

    void LoadCustomDiBuffer(const float* samples, size_t count) {
        if (!samples || count == 0) return;
        clip_buf_.assign(samples, samples + count);
        style_ = PresetClipStyle::kCustomWavDi;
        playhead_ = 0;
    }

    // Zero-allocation real-time playback inside audio thread
    void RenderSubBlock16(float* out16) noexcept {
        if (!playing_ || clip_buf_.empty()) {
            for (size_t i = 0; i < kSubBlockSize; ++i) out16[i] = 0.0f;
            return;
        }
        const size_t n = clip_buf_.size();
        for (size_t i = 0; i < kSubBlockSize; ++i) {
            if (playhead_ >= n) {
                if (repeat_) {
                    playhead_ = 0;
                } else {
                    playing_ = false;
                    out16[i] = 0.0f;
                    continue;
                }
            }
            out16[i] = clip_buf_[playhead_++];
        }
    }

private:
    // Clean-room Karplus-Strong plucked electric guitar/bass DI synthesizer (pre-rendered off-RT!)
    static void AddPluckedNote(std::vector<float>& dst, size_t start_sample, size_t dur_samples,
                               float freq_hz, float amp, float damping, uint32_t& rng) {
        const size_t period = std::max<size_t>(20, static_cast<size_t>(kSampleRate / std::max(30.0f, freq_hz)));
        std::vector<float> line(period, 0.0f);
        // Pick excitation burst (band-shaped noise with realistic pick attack)
        float prev_n = 0.0f;
        for (size_t i = 0; i < period; ++i) {
            rng = rng * 1664525u + 1013904223u;
            const float u = (static_cast<float>(rng >> 8) / static_cast<float>(1u << 24)) * 2.0f - 1.0f;
            const float shaped = 0.65f * u + 0.35f * prev_n;
            prev_n = u;
            line[i] = shaped * amp;
        }
        size_t pos = 0;
        const size_t end = std::min(dst.size(), start_sample + dur_samples);
        for (size_t n = start_sample; n < end; ++n) {
            const float s0 = line[pos];
            const size_t next = (pos + 1 == period) ? 0 : (pos + 1);
            const float s1 = line[next];
            const float avg = 0.5f * (s0 + s1) * damping;
            line[pos] = avg;
            pos = next;
            // Gentle fade-out at note release
            float env = 1.0f;
            if (end - n < 480) env = static_cast<float>(end - n) / 480.0f;
            dst[n] += s0 * env;
        }
    }

    void SynthesizeBuiltInStyle(PresetClipStyle style) {
        clip_buf_.assign(kBuiltInClipFrames, 0.0f);
        uint32_t rng = 0xC0FFEE12u + static_cast<uint32_t>(style) * 997u;

        if (style == PresetClipStyle::kCleanSingleCoil) {
            // Arpeggiated Em9 -> Cmaj7 chord voicing
            static constexpr float kNotes[8] = {82.41f, 123.47f, 164.81f, 196.00f, 246.94f, 369.99f, 130.81f, 196.00f};
            for (int i = 0; i < 8; ++i) {
                AddPluckedNote(clip_buf_, static_cast<size_t>(i) * 24000, 44000, kNotes[i], 0.36f, 0.9965f, rng);
            }
        } else if (style == PresetClipStyle::kCrunchRockRiff) {
            // Classic A5 -> G5 -> D5 power-chord riff
            const size_t step = 24000;
            AddPluckedNote(clip_buf_, 0 * step, 42000, 110.00f, 0.40f, 0.995f, rng);
            AddPluckedNote(clip_buf_, 0 * step, 42000, 164.81f, 0.34f, 0.995f, rng);
            AddPluckedNote(clip_buf_, 2 * step, 38000, 98.00f, 0.40f, 0.995f, rng);
            AddPluckedNote(clip_buf_, 2 * step, 38000, 146.83f, 0.34f, 0.995f, rng);
            AddPluckedNote(clip_buf_, 4 * step, 48000, 146.83f, 0.40f, 0.995f, rng);
            AddPluckedNote(clip_buf_, 4 * step, 48000, 220.00f, 0.34f, 0.995f, rng);
            AddPluckedNote(clip_buf_, 6 * step, 42000, 110.00f, 0.42f, 0.995f, rng);
            AddPluckedNote(clip_buf_, 6 * step, 42000, 164.81f, 0.36f, 0.995f, rng);
        } else if (style == PresetClipStyle::kMetalChug) {
            // Tight palm-muted Drop-D / E2 chug pattern (fast damped attacks + open accent)
            for (int i = 0; i < 16; ++i) {
                const bool accent = (i % 4 == 0);
                const float f0 = (i >= 12) ? 98.00f : 82.41f;
                const float damp = accent ? 0.994f : 0.972f;  // palm mute damps fast
                AddPluckedNote(clip_buf_, static_cast<size_t>(i) * 12000, 11500, f0, accent ? 0.55f : 0.42f, damp, rng);
                if (accent) {
                    AddPluckedNote(clip_buf_, static_cast<size_t>(i) * 12000, 11500, f0 * 1.498f, 0.40f, damp, rng);
                }
            }
        } else {
            // Bass Finger Groove (E1 = 41.20 Hz, G1 = 49.00 Hz, A1 = 55.00 Hz, B1 = 61.74 Hz)
            static constexpr float kBass[8] = {41.20f, 41.20f, 49.00f, 55.00f, 41.20f, 61.74f, 55.00f, 49.00f};
            for (int i = 0; i < 8; ++i) {
                AddPluckedNote(clip_buf_, static_cast<size_t>(i) * 24000, 23000, kBass[i], 0.52f, 0.997f, rng);
            }
        }
    }

    PresetClipStyle style_ = PresetClipStyle::kCrunchRockRiff;
    std::vector<float> clip_buf_;
    size_t playhead_ = 0;
    bool playing_ = false;
    bool repeat_ = true;
};

}  // namespace stadium_dsp
