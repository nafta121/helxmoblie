#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// 5 Hardware Pitch & Synth Models (Catalog Section 10)
enum class PitchSynthModel : int32_t {
    kPitchWham = 0,   // DigiTech Whammy continuous EXP pitch shifter
    kPolyCapo,        // Polyphonic Capo (-12..+12 semitones)
    kTwinHarmony,     // Eventide H3000 Dual-Voice Detune/Harmony
    kBoctaverOc2,     // BOSS OC-2 Analog CMOS Flip-Flop ÷2 & ÷4 Sub-Octaver
    kTwelveString,    // 12-String Octave-Up High-Band Shimmer Emulator
    kCount = 5,
};

struct PitchParams {
    bool enabled = false;
    PitchSynthModel model = PitchSynthModel::kPolyCapo;
    float semitones = -12.0f;    // -24..+24 semitones
    float harmony_st2 = 7.0f;    // 2nd voice for Twin Harmony
    float pedal_pos = 1.0f;      // Pitch Wham expression pedal [0..1]
    float oct1_level = 0.80f;    // Boctaver -1 Oct
    float oct2_level = 0.60f;    // Boctaver -2 Oct
    float dry_level = 0.50f;     // Boctaver dry level
    float mix = 0.50f;
};

// Phase-Locked Dual-Window WSOLA / Overlap-Add Pitch Shifter (zero heap allocation, reusable by Reverb Shimmer!)
class DualWindowPitchShifter {
public:
    static constexpr size_t kBufSize = 4096;
    static constexpr float kWindowLen = 1024.0f;  // ~21.3 ms Hann grain window

    DualWindowPitchShifter() noexcept { Reset(); }

    void Reset() noexcept {
        buf_.fill(0.0f);
        write_pos_ = 0;
        phase_a_ = 0.0f;
        phase_b_ = 0.5f;
    }

    inline float ProcessSample(float x, float pitch_ratio) noexcept {
        buf_[write_pos_] = x;

        // Read pointer speed relative to write pointer: delta_delay = 1.0 - pitch_ratio
        const float rate = (1.0f - pitch_ratio) / kWindowLen;
        phase_a_ += rate;
        phase_b_ += rate;
        phase_a_ -= std::floor(phase_a_);
        phase_b_ -= std::floor(phase_b_);

        const float d_a = 16.0f + phase_a_ * kWindowLen;
        const float d_b = 16.0f + phase_b_ * kWindowLen;

        // Hann crossfade windows (50% overlap -> constant unity gain sum = 1.0!)
        const float w_a = 0.5f * (1.0f - std::cos(kTwoPi * phase_a_));
        const float w_b = 0.5f * (1.0f - std::cos(kTwoPi * phase_b_));

        const float sa = ReadInterp(d_a);
        const float sb = ReadInterp(d_b);

        write_pos_ = (write_pos_ + 1) & (kBufSize - 1);
        return sa * w_a + sb * w_b;
    }

private:
    inline float ReadInterp(float delay) const noexcept {
        const float d = Clamp(delay, 2.0f, static_cast<float>(kBufSize - 4));
        const size_t di = static_cast<size_t>(d);
        const float frac = d - static_cast<float>(di);
        const size_t i0 = (write_pos_ + kBufSize - di) & (kBufSize - 1);
        const size_t i1 = (i0 + kBufSize - 1) & (kBufSize - 1);
        return buf_[i0] * (1.0f - frac) + buf_[i1] * frac;
    }

    std::array<float, kBufSize> buf_{};
    size_t write_pos_ = 0;
    float phase_a_ = 0.0f;
    float phase_b_ = 0.5f;
};

class PitchSynthBlock {
public:
    PitchSynthBlock() noexcept {
        ConfigureFilters();
        Reset();
    }

    void Reset() noexcept {
        shifter1_.Reset();
        shifter2_.Reset();
        oc2_pre_lp_.Reset();
        oc2_post_lp1_.Reset();
        oc2_post_lp2_.Reset();
        twelve_hp_.Reset();
        oc2_env_ = 0.0f;
        oc2_prev_sig_ = 0.0f;
        oc2_ff1_ = 1.0f;
        oc2_ff2_ = 1.0f;
        oc2_ff1_prev_ = 1.0f;
    }

    void SetParams(const PitchParams& p) noexcept { params_ = p; }
    const PitchParams& GetParams() const noexcept { return params_; }

    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) out16[i] = in16[i];
            return;
        }

        const float mix = Clamp(params_.mix, 0.0f, 1.0f);

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            const float x = in16[i];
            float y = x;

            switch (params_.model) {
                case PitchSynthModel::kBoctaverOc2: {
                    // BOSS OC-2 Analog CMOS Flip-Flop divider:
                    // 1. Pre-LPF isolates fundamental -> Schmitt zero-crossing triggers ÷2 (ff1) and ÷4 (ff2)
                    // 2. Smooth envelope follower modulates analog VCA + warm 2-pole sub-LPF
                    const float ax = std::fabs(x);
                    oc2_env_ += ((ax > oc2_env_) ? 0.03f : 0.002f) * (ax - oc2_env_);
                    const float filt = oc2_pre_lp_.Process(x);
                    if (oc2_prev_sig_ <= 0.0f && filt > 0.002f) {
                        oc2_ff1_ = -oc2_ff1_;
                        if (oc2_ff1_prev_ < 0.0f && oc2_ff1_ > 0.0f) {
                            oc2_ff2_ = -oc2_ff2_;
                        }
                        oc2_ff1_prev_ = oc2_ff1_;
                    }
                    oc2_prev_sig_ = filt;
                    const float gate = Clamp((oc2_env_ - 0.003f) * 200.0f, 0.0f, 1.0f);
                    const float oct1 = oc2_post_lp1_.Process(oc2_ff1_ * oc2_env_ * gate) * 1.35f;
                    const float oct2 = oc2_post_lp2_.Process(oc2_ff2_ * oc2_env_ * gate) * 1.35f;
                    y = x * params_.dry_level + oct1 * params_.oct1_level + oct2 * params_.oct2_level;
                    break;
                }
                case PitchSynthModel::kPitchWham: {
                    const float st = params_.semitones * Clamp(params_.pedal_pos, 0.0f, 1.0f);
                    const float ratio = std::pow(2.0f, st / 12.0f);
                    const float wet = shifter1_.ProcessSample(x, ratio);
                    y = x * (1.0f - mix) + wet * mix;
                    break;
                }
                case PitchSynthModel::kPolyCapo: {
                    const float ratio = std::pow(2.0f, Clamp(params_.semitones, -24.0f, 24.0f) / 12.0f);
                    const float wet = shifter1_.ProcessSample(x, ratio);
                    y = x * (1.0f - mix) + wet * mix;
                    break;
                }
                case PitchSynthModel::kTwinHarmony: {
                    const float r1 = std::pow(2.0f, Clamp(params_.semitones, -24.0f, 24.0f) / 12.0f);
                    const float r2 = std::pow(2.0f, Clamp(params_.harmony_st2, -24.0f, 24.0f) / 12.0f);
                    const float w1 = shifter1_.ProcessSample(x, r1);
                    const float w2 = shifter2_.ProcessSample(x, r2);
                    y = x * (1.0f - mix) + 0.5f * (w1 + w2) * mix;
                    break;
                }
                case PitchSynthModel::kTwelveString: {
                    // Octave-up shimmer blended gently with crisp high-pass string chime
                    const float up = shifter1_.ProcessSample(x, 2.0f);
                    const float chime = twelve_hp_.Process(up);
                    y = x * (1.0f - 0.5f * mix) + chime * (0.65f * mix);
                    break;
                }
                default:
                    break;
            }
            out16[i] = y;
        }
    }

private:
    void ConfigureFilters() noexcept {
        oc2_pre_lp_.SetCoeffs(DesignLowPass(420.0, 0.85, kSampleRateD));
        oc2_post_lp1_.SetCoeffs(DesignLowPass(280.0, 0.707, kSampleRateD));
        oc2_post_lp2_.SetCoeffs(DesignLowPass(190.0, 0.707, kSampleRateD));
        twelve_hp_.SetCoeffs(DesignHighPass(420.0, 0.707, kSampleRateD));
    }

    PitchParams params_{};
    DualWindowPitchShifter shifter1_, shifter2_;
    BiquadDF2T oc2_pre_lp_, oc2_post_lp1_, oc2_post_lp2_, twelve_hp_;
    float oc2_env_ = 0.0f;
    float oc2_prev_sig_ = 0.0f;
    float oc2_ff1_ = 1.0f, oc2_ff2_ = 1.0f, oc2_ff1_prev_ = 1.0f;
};

}  // namespace stadium_dsp
