#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// 10 Hardware Modulation & Rotary Models (Catalog Section 7)
enum class ModulationModel : int32_t {
    kCe1Chorus = 0,       // BOSS CE-1 Chorus Ensemble (230k Auto-Z)
    kTrinityChorus,       // Dytronics Tri-Stereo 120° 3-Voice Chorus
    kVb2Vibrato,          // BOSS VB-2 Pitch Vibrato
    kScriptPhase90,       // MXR Script Phase 90 (4-stage op-amp allpass)
    kUbiquitousVibe,      // Shin-Ei Uni-Vibe (22k Auto-Z, 4 asymmetric photocell stages)
    kFlanger117,          // MXR M117 Flanger (short modulated BBD + regen)
    kOpticalTremolo,      // Fender Blackface Opto-Isolator Tremolo
    kBiasTremolo,         // Vox / Tweed Power-Tube Bias Tremolo
    kHarmonicTremolo,     // Fender Brownface LR2 Anti-Phase Harmonic Tremolo
    kRotary122,           // Leslie 122 Dual-Rotor Horn/Drum Doppler
    kCount = 10,
};

struct ModParams {
    bool enabled = false;
    ModulationModel model = ModulationModel::kCe1Chorus;
    bool is_stereo = true;
    float rate_hz = 1.2f;
    float depth = 0.55f;
    float feedback = 0.25f;
    float mix = 0.50f;
    bool fast_speed = false;  // Leslie 122 Slow/Fast horn inertia
};

inline float GetModAutoZOhms(ModulationModel m) noexcept {
    switch (m) {
        case ModulationModel::kUbiquitousVibe: return 22000.0f;
        case ModulationModel::kCe1Chorus:      return 230000.0f;
        default:                               return 1000000.0f;
    }
}

class FirstOrderAllpass {
public:
    void Reset() noexcept { x1_ = 0.0f; y1_ = 0.0f; }
    inline float Process(float x, float a) noexcept {
        // With a = (t - 1)/(t + 1), H(z) = (a + z^-1) / (1 + a*z^-1) gives exact -90° at fc
        const float y = a * x + x1_ - a * y1_;
        x1_ = x;
        y1_ = y;
        return y;
    }
    static inline float CoeffFromFreq(float freq_hz) noexcept {
        const float t = std::tan(kPi * Clamp(freq_hz, 40.0f, 14000.0f) / kSampleRate);
        return (t - 1.0f) / (t + 1.0f);
    }

private:
    float x1_ = 0.0f;
    float y1_ = 0.0f;
};

class StereoModulationEngine {
public:
    StereoModulationEngine() noexcept {
        ConfigureCrossovers();
        Reset();
    }

    void Reset() noexcept {
        for (auto& d : delay_buf_) d.fill(0.0f);
        delay_w_ = 0;
        lfo_phase_ = 0.0f;
        horn_phase_ = 0.0f;
        drum_phase_ = 0.0f;
        horn_rate_ = 0.8f;
        drum_rate_ = 0.65f;
        for (int ch = 0; ch < 2; ++ch) {
            for (auto& ap : allpass_[ch]) ap.Reset();
            ap_fb_[ch] = 0.0f;
            lr_lp1_[ch].Reset();
            lr_lp2_[ch].Reset();
            lr_hp1_[ch].Reset();
            lr_hp2_[ch].Reset();
            rot_lp_[ch].Reset();
            rot_hp_[ch].Reset();
        }
    }

    void SetParams(const ModParams& p) noexcept { params_ = p; }
    const ModParams& GetParams() const noexcept { return params_; }

    void ProcessSubBlock16(const float* in_l, const float* in_r, float* out_l, float* out_r) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
            }
            return;
        }

        const float rate = Clamp(params_.rate_hz, 0.0f, 18.0f);
        const float depth = Clamp(params_.depth, 0.0f, 1.0f);
        const float mix = Clamp(params_.mix, 0.0f, 1.0f);

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            float l_in = in_l[i];
            float r_in = params_.is_stereo ? in_r[i] : 0.5f * (in_l[i] + in_r[i]);
            if (!params_.is_stereo) l_in = r_in;

            const float ph = lfo_phase_;
            const float lfo0 = std::sin(kTwoPi * ph);
            const float lfo120 = std::sin(kTwoPi * (ph + 0.3333333f));
            const float lfo240 = std::sin(kTwoPi * (ph + 0.6666667f));
            lfo_phase_ += rate / kSampleRate;
            if (lfo_phase_ >= 1.0f) lfo_phase_ -= 1.0f;

            float wl = l_in, wr = r_in;

            switch (params_.model) {
                case ModulationModel::kCe1Chorus: {
                    WriteDelay(0, l_in);
                    WriteDelay(1, r_in);
                    const float d_l = (6.5f + 3.2f * depth * lfo0) * (kSampleRate * 0.001f);
                    const float d_r = (6.5f - 3.2f * depth * lfo0) * (kSampleRate * 0.001f);
                    wl = ReadDelayInterp(0, d_l);
                    wr = ReadDelayInterp(1, d_r);
                    break;
                }
                case ModulationModel::kTrinityChorus: {
                    // 3-voice 120° phase-offset stereo chorus
                    const float mono = 0.5f * (l_in + r_in);
                    WriteDelay(0, mono);
                    const float d1 = (7.0f + 3.8f * depth * lfo0) * (kSampleRate * 0.001f);
                    const float d2 = (9.0f + 3.8f * depth * lfo120) * (kSampleRate * 0.001f);
                    const float d3 = (11.0f + 3.8f * depth * lfo240) * (kSampleRate * 0.001f);
                    const float v1 = ReadDelayInterp(0, d1);
                    const float v2 = ReadDelayInterp(0, d2);
                    const float v3 = ReadDelayInterp(0, d3);
                    wl = 0.58f * v1 + 0.42f * v2;
                    wr = 0.58f * v3 + 0.42f * v2;
                    break;
                }
                case ModulationModel::kVb2Vibrato: {
                    WriteDelay(0, l_in);
                    WriteDelay(1, r_in);
                    const float d = (5.0f + 2.8f * depth * lfo0) * (kSampleRate * 0.001f);
                    wl = ReadDelayInterp(0, d);
                    wr = ReadDelayInterp(1, d);
                    out_l[i] = wl;
                    out_r[i] = params_.is_stereo ? wr : wl;
                    AdvanceDelay();
                    continue;
                }
                case ModulationModel::kScriptPhase90: {
                    // 4 identical op-amp 1st-order allpass stages -> exact 180° & 540° cancellation notches!
                    const float sweep = 0.5f + 0.5f * lfo0;
                    const float fc = 260.0f * std::pow(8.0f, sweep * depth + 0.5f * (1.0f - depth));
                    const float a = FirstOrderAllpass::CoeffFromFreq(fc);
                    for (int ch = 0; ch < 2; ++ch) {
                        const float x = (ch == 0) ? l_in : r_in;
                        float s = x + Clamp(params_.feedback, 0.0f, 0.7f) * ap_fb_[ch];
                        for (int st = 0; st < 4; ++st) s = allpass_[ch][st].Process(s, a);
                        ap_fb_[ch] = s;
                        if (ch == 0) wl = s; else wr = s;
                    }
                    break;
                }
                case ModulationModel::kUbiquitousVibe: {
                    // Shin-Ei Uni-Vibe: 4 staggered asymmetric photocell allpass stages (1.0x, 1.8x, 3.6x, 7.2x)
                    static constexpr float kStagger[4] = {1.0f, 1.8f, 3.6f, 7.2f};
                    const float bulb = std::pow(0.5f + 0.5f * lfo0, 1.35f);  // incandescent lamp throb
                    const float f_base = 110.0f * std::pow(4.5f, bulb * depth);
                    for (int ch = 0; ch < 2; ++ch) {
                        const float x = (ch == 0) ? l_in : r_in;
                        float s = x + 0.18f * ap_fb_[ch];
                        for (int st = 0; st < 4; ++st) {
                            s = allpass_[ch][st].Process(s, FirstOrderAllpass::CoeffFromFreq(f_base * kStagger[st]));
                        }
                        ap_fb_[ch] = s;
                        if (ch == 0) wl = s; else wr = s;
                    }
                    break;
                }
                case ModulationModel::kFlanger117: {
                    const float fb = Clamp(params_.feedback, -0.82f, 0.82f);
                    const float d = (1.2f + 4.8f * depth * (0.5f + 0.5f * lfo0)) * (kSampleRate * 0.001f);
                    const float dl = ReadDelayInterp(0, d);
                    const float dr = ReadDelayInterp(1, d);
                    WriteDelay(0, l_in + fb * dl);
                    WriteDelay(1, r_in + fb * dr);
                    wl = dl;
                    wr = dr;
                    break;
                }
                case ModulationModel::kOpticalTremolo:
                case ModulationModel::kBiasTremolo: {
                    const float mod_amp = (params_.model == ModulationModel::kOpticalTremolo)
                        ? (1.0f - depth * 0.85f * std::pow(0.5f + 0.5f * lfo0, 1.4f))
                        : (1.0f - depth * 0.72f * (0.5f + 0.5f * lfo0));
                    wl = l_in * mod_amp;
                    wr = r_in * mod_amp;
                    out_l[i] = wl;
                    out_r[i] = params_.is_stereo ? wr : wl;
                    AdvanceDelay();
                    continue;
                }
                case ModulationModel::kHarmonicTremolo: {
                    // Fender Brownface LR2 Crossover @ 600 Hz with strictly anti-phase Lo/Hi modulation!
                    const float g_lo = 1.0f - depth * 0.90f * (0.5f + 0.5f * lfo0);
                    const float g_hi = 1.0f - depth * 0.90f * (0.5f - 0.5f * lfo0);
                    for (int ch = 0; ch < 2; ++ch) {
                        const float x = (ch == 0) ? l_in : r_in;
                        const float lo = lr_lp2_[ch].Process(lr_lp1_[ch].Process(x));
                        const float hi = lr_hp2_[ch].Process(lr_hp1_[ch].Process(x));  // LR4 sums in-phase flat!
                        const float sum = lo * g_lo + hi * g_hi;
                        if (ch == 0) wl = sum; else wr = sum;
                    }
                    out_l[i] = wl;
                    out_r[i] = params_.is_stereo ? wr : wl;
                    AdvanceDelay();
                    continue;
                }
                case ModulationModel::kRotary122: {
                    // Leslie 122 dual-rotor inertia (Horn 0.8 <-> 6.4 Hz, Drum 0.65 <-> 5.6 Hz) + Doppler
                    const float target_h = params_.fast_speed ? 6.4f : 0.8f;
                    const float target_d = params_.fast_speed ? 5.6f : 0.65f;
                    horn_rate_ += 0.00004f * (target_h - horn_rate_);
                    drum_rate_ += 0.000018f * (target_d - drum_rate_);
                    horn_phase_ += horn_rate_ / kSampleRate; if (horn_phase_ >= 1.0f) horn_phase_ -= 1.0f;
                    drum_phase_ += drum_rate_ / kSampleRate; if (drum_phase_ >= 1.0f) drum_phase_ -= 1.0f;

                    const float mono = 0.5f * (l_in + r_in);
                    const float drum = rot_lp_[0].Process(mono);
                    const float horn = rot_hp_[0].Process(mono);
                    WriteDelay(0, horn);
                    WriteDelay(1, drum);
                    const float hl = ReadDelayInterp(0, (3.0f + 1.4f * depth * std::sin(kTwoPi * horn_phase_)) * 48.0f);
                    const float hr = ReadDelayInterp(0, (3.0f - 1.4f * depth * std::sin(kTwoPi * horn_phase_)) * 48.0f);
                    const float dl = ReadDelayInterp(1, (2.2f + 0.8f * depth * std::sin(kTwoPi * drum_phase_)) * 48.0f);
                    const float dr = ReadDelayInterp(1, (2.2f - 0.8f * depth * std::sin(kTwoPi * drum_phase_)) * 48.0f);
                    const float am_hl = 0.72f + 0.28f * std::cos(kTwoPi * horn_phase_);
                    const float am_hr = 0.72f - 0.28f * std::cos(kTwoPi * horn_phase_);
                    wl = hl * am_hl + dl * (0.85f + 0.15f * std::cos(kTwoPi * drum_phase_));
                    wr = hr * am_hr + dr * (0.85f - 0.15f * std::cos(kTwoPi * drum_phase_));
                    break;
                }
                default:
                    break;
            }

            AdvanceDelay();
            out_l[i] = l_in * (1.0f - mix) + wl * mix;
            out_r[i] = params_.is_stereo ? (r_in * (1.0f - mix) + wr * mix) : out_l[i];
        }
    }

private:
    void ConfigureCrossovers() noexcept {
        // Linkwitz-Riley 2nd-order (cascaded Butterworth 1st-order / Q=0.707 biquad pairs) @ 600 Hz
        const auto lp = DesignLowPass(600.0, 0.70710678, kSampleRateD);
        const auto hp = DesignHighPass(600.0, 0.70710678, kSampleRateD);
        const auto rlp = DesignLowPass(800.0, 0.70710678, kSampleRateD);
        const auto rhp = DesignHighPass(800.0, 0.70710678, kSampleRateD);
        for (int ch = 0; ch < 2; ++ch) {
            lr_lp1_[ch].SetCoeffs(lp);
            lr_lp2_[ch].SetCoeffs(lp);
            lr_hp1_[ch].SetCoeffs(hp);
            lr_hp2_[ch].SetCoeffs(hp);
            rot_lp_[ch].SetCoeffs(rlp);
            rot_hp_[ch].SetCoeffs(rhp);
        }
    }

    inline void WriteDelay(int ch, float x) noexcept { delay_buf_[ch][delay_w_] = x; }
    inline void AdvanceDelay() noexcept { delay_w_ = (delay_w_ + 1) & (kDelayCap - 1); }

    inline float ReadDelayInterp(int ch, float d_samples) const noexcept {
        const float d = Clamp(d_samples, 1.0f, static_cast<float>(kDelayCap - 3));
        const size_t di = static_cast<size_t>(d);
        const float frac = d - static_cast<float>(di);
        const size_t i0 = (delay_w_ + kDelayCap - di) & (kDelayCap - 1);
        const size_t i1 = (i0 + kDelayCap - 1) & (kDelayCap - 1);
        return delay_buf_[ch][i0] * (1.0f - frac) + delay_buf_[ch][i1] * frac;
    }

    static constexpr size_t kDelayCap = 2048;
    ModParams params_{};
    std::array<std::array<float, kDelayCap>, 2> delay_buf_{};
    size_t delay_w_ = 0;
    float lfo_phase_ = 0.0f;
    float horn_phase_ = 0.0f, drum_phase_ = 0.0f;
    float horn_rate_ = 0.8f, drum_rate_ = 0.65f;

    std::array<std::array<FirstOrderAllpass, 4>, 2> allpass_;
    std::array<float, 2> ap_fb_{0.0f, 0.0f};
    std::array<BiquadDF2T, 2> lr_lp1_, lr_lp2_, lr_hp1_, lr_hp2_, rot_lp_, rot_hp_;
};

}  // namespace stadium_dsp
