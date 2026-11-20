#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// 8 Hardware Wah & Filter Models (Catalog Section 11)
enum class WahFilterModel : int32_t {
    kFassel310 = 0,     // Cry Baby Fasel Inductor (90k Auto-Z)
    kThroatyClyde,      // Vox Clyde McCoy (70k Auto-Z)
    kUk846,             // Vox V846 Vintage
    kTeardrop,          // Dunlop Cry Baby Teardrop
    kMutantFilterUp,    // Mu-Tron III Vactrol Envelope Up
    kTronDown,          // Mu-Tron III Vactrol Envelope Down
    kObiWah,            // Oberheim Voltage Controlled Sample & Hold (230k Auto-Z)
    kMurf8Band,         // Moog MF-105 MuRF 8-Band Resonant Step Filter
    kCount = 8,
};

struct WahParams {
    bool enabled = false;
    WahFilterModel model = WahFilterModel::kFassel310;
    float position = 0.50f;     // EXP 1 pedal position [0..1]
    float q_resonance = 0.60f;
    float sensitivity = 0.65f;  // Envelope sensitivity for Mu-Tron
    float rate_hz = 4.0f;       // Step rate for Obi Wah / MuRF
    float mix = 1.0f;
};

inline float GetWahAutoZOhms(WahFilterModel m) noexcept {
    switch (m) {
        case WahFilterModel::kThroatyClyde: return 70000.0f;
        case WahFilterModel::kFassel310:    return 90000.0f;
        case WahFilterModel::kObiWah:       return 230000.0f;
        default:                            return 1000000.0f;
    }
}

// Cytomic TPT SVF (Zero-Delay Feedback State Variable Filter) for clickless audio-rate Wah/Filter sweeps
class CytomicTptSvf {
public:
    void Reset() noexcept {
        ic1eq_ = 0.0f;
        ic2eq_ = 0.0f;
    }

    inline void Process(float x, float cutoff_hz, float q, float& lp, float& bp, float& hp) noexcept {
        const float fc = Clamp(cutoff_hz, 40.0f, 16000.0f);
        const float g = std::tan(kPi * fc / kSampleRate);
        const float k = 1.0f / std::max(0.5f, q);
        const float a1 = 1.0f / (1.0f + g * (g + k));
        const float a2 = g * a1;
        const float a3 = g * a2;

        const float v3 = x - ic2eq_;
        const float v1 = a1 * ic1eq_ + a2 * v3;
        const float v2 = ic2eq_ + a2 * ic1eq_ + a3 * v3;
        ic1eq_ = 2.0f * v1 - ic1eq_;
        ic2eq_ = 2.0f * v2 - ic2eq_;

        lp = v2;
        bp = v1;
        hp = x - k * v1 - v2;
    }

private:
    float ic1eq_ = 0.0f;
    float ic2eq_ = 0.0f;
};

class InductorWahPedal {
public:
    InductorWahPedal() noexcept { Reset(); }

    void Reset() noexcept {
        svf_.Reset();
        for (auto& b : murf_bands_) b.Reset();
        smooth_pos_ = params_.position;
        vactrol_env_ = 0.0f;
        step_phase_ = 0.0f;
        step_index_ = 0;
        sh_freq_ = 800.0f;
        rng_ = 0x51A7F311u;
    }

    void SetParams(const WahParams& p) noexcept { params_ = p; }
    const WahParams& GetParams() const noexcept { return params_; }

    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) out16[i] = in16[i];
            return;
        }

        const float mix = Clamp(params_.mix, 0.0f, 1.0f);

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            const float x = in16[i];
            smooth_pos_ += 0.008f * (Clamp(params_.position, 0.0f, 1.0f) - smooth_pos_);
            float wet = x;

            if (params_.model == WahFilterModel::kMurf8Band) {
                static constexpr float kMurfFreqs[8] = {200.f, 340.f, 520.f, 780.f, 1150.f, 1650.f, 2350.f, 3400.f};
                step_phase_ += std::max(0.2f, params_.rate_hz) / kSampleRate;
                if (step_phase_ >= 1.0f) {
                    step_phase_ -= 1.0f;
                    step_index_ = (step_index_ + 1) & 7;
                }
                float sum = 0.0f;
                for (int b = 0; b < 8; ++b) {
                    float lp, bp, hp;
                    murf_bands_[b].Process(x, kMurfFreqs[b], 5.5f, lp, bp, hp);
                    const float w = (b == step_index_) ? 1.4f : ((b == ((step_index_ + 3) & 7)) ? 0.65f : 0.12f);
                    sum += bp * w;
                }
                wet = sum * 0.85f;
            } else if (params_.model == WahFilterModel::kObiWah) {
                step_phase_ += std::max(0.2f, params_.rate_hz) / kSampleRate;
                if (step_phase_ >= 1.0f) {
                    step_phase_ -= 1.0f;
                    rng_ = rng_ * 1664525u + 1013904223u;
                    const float u = static_cast<float>(rng_ >> 8) / static_cast<float>(1u << 24);
                    sh_freq_ = 280.0f * std::pow(10.5f, u);
                }
                float lp, bp, hp;
                svf_.Process(x, sh_freq_, 4.0f + 5.0f * params_.q_resonance, lp, bp, hp);
                wet = bp * 2.2f + lp * 0.35f;
            } else if (params_.model == WahFilterModel::kMutantFilterUp ||
                       params_.model == WahFilterModel::kTronDown) {
                // Mu-Tron III Vactrol optical envelope follower
                const float ax = std::fabs(x) * (1.5f + 5.5f * params_.sensitivity);
                const float alpha = (ax > vactrol_env_) ? 0.020f : 0.0008f;
                vactrol_env_ += alpha * (ax - vactrol_env_);
                const float norm = Clamp(vactrol_env_, 0.0f, 1.0f);
                const float f_env = (params_.model == WahFilterModel::kMutantFilterUp)
                    ? (240.0f * std::pow(14.0f, norm))
                    : (3100.0f * std::pow(0.08f, norm));
                float lp, bp, hp;
                svf_.Process(x, f_env, 3.2f + 4.5f * params_.q_resonance, lp, bp, hp);
                wet = bp * 2.1f + lp * 0.30f;
            } else {
                // Classic inductor Wah sweep profiles
                float f_min = 380.0f, f_max = 2150.0f, q = 4.2f;
                switch (params_.model) {
                    case WahFilterModel::kFassel310:    f_min = 390.f; f_max = 2250.f; q = 4.5f; break;
                    case WahFilterModel::kThroatyClyde: f_min = 330.f; f_max = 1850.f; q = 5.4f; break;
                    case WahFilterModel::kUk846:        f_min = 420.f; f_max = 2400.f; q = 3.8f; break;
                    case WahFilterModel::kTeardrop:     f_min = 360.f; f_max = 2100.f; q = 4.8f; break;
                    default: break;
                }
                const float fc = f_min * std::pow(f_max / f_min, smooth_pos_);
                float lp, bp, hp;
                svf_.Process(x, fc, q * (0.6f + 0.8f * params_.q_resonance), lp, bp, hp);
                wet = bp * 2.4f + lp * 0.25f;
            }
            out16[i] = x * (1.0f - mix) + wet * mix;
        }
    }

private:
    WahParams params_{};
    CytomicTptSvf svf_;
    std::array<CytomicTptSvf, 8> murf_bands_;
    float smooth_pos_ = 0.5f;
    float vactrol_env_ = 0.0f;
    float step_phase_ = 0.0f;
    int step_index_ = 0;
    float sh_freq_ = 800.0f;
    uint32_t rng_ = 0x51A7F311u;
};

// Multi-Mode Studio EQ (GAP-07): Parametric Anti-Cramped + 10-Band Graphic + Tilt EQ + 128-pt Curve Evaluator
enum class EqMode : int32_t {
    kParametric4Band = 0,
    kGraphic10Band,
    kTiltEq,
    kCount = 3,
};

struct EqParams {
    bool enabled = false;
    EqMode mode = EqMode::kParametric4Band;
    bool is_stereo = true;
    // Parametric 4-band
    float low_freq = 120.0f, low_q = 0.8f, low_gain_db = 0.0f;
    float mid1_freq = 650.0f, mid1_q = 1.2f, mid1_gain_db = 0.0f;
    float mid2_freq = 2400.0f, mid2_q = 1.2f, mid2_gain_db = 0.0f;
    float high_freq = 6500.0f, high_q = 0.8f, high_gain_db = 0.0f;
    float low_cut_hz = 20.0f;
    float high_cut_hz = 20000.0f;
    // 10-Band Graphic EQ (31.25, 62.5, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz)
    std::array<float, 10> graphic_gains_db{};
    // Tilt EQ (-12..+12 dB tilt around center_freq)
    float tilt_db = 0.0f;
    float tilt_center_hz = 900.0f;
    float level_db = 0.0f;
};

class StudioEqBlock {
public:
    static constexpr std::array<double, 10> kGraphic10Freqs = {
        31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0};

    StudioEqBlock() noexcept { SetParams(EqParams{}); }

    void Reset() noexcept {
        for (int ch = 0; ch < 2; ++ch) {
            for (auto& b : para_[ch]) b.Reset();
            for (auto& b : graphic_[ch]) b.Reset();
            tilt_lo_[ch].Reset();
            tilt_hi_[ch].Reset();
            lc_[ch].Reset();
            hc_[ch].Reset();
        }
    }

    void SetParams(const EqParams& p) noexcept {
        params_ = p;
        // Parametric
        para_c_[0] = DesignAntiCrampedPeak(p.low_freq, p.low_q, p.low_gain_db, kSampleRateD);
        para_c_[1] = DesignAntiCrampedPeak(p.mid1_freq, p.mid1_q, p.mid1_gain_db, kSampleRateD);
        para_c_[2] = DesignAntiCrampedPeak(p.mid2_freq, p.mid2_q, p.mid2_gain_db, kSampleRateD);
        para_c_[3] = DesignAntiCrampedPeak(p.high_freq, p.high_q, p.high_gain_db, kSampleRateD);
        // Graphic 10-band
        for (size_t i = 0; i < 10; ++i) {
            graphic_c_[i] = DesignAntiCrampedPeak(kGraphic10Freqs[i], 1.414, p.graphic_gains_db[i], kSampleRateD);
        }
        // Tilt EQ
        tilt_lo_c_ = DesignLowShelf(p.tilt_center_hz, 0.55, -0.5 * p.tilt_db, kSampleRateD);
        tilt_hi_c_ = DesignHighShelf(p.tilt_center_hz, 0.55, +0.5 * p.tilt_db, kSampleRateD);
        // Cuts
        use_lc_ = p.low_cut_hz > 21.0f;
        use_hc_ = p.high_cut_hz < 19800.0f;
        lc_c_ = use_lc_ ? DesignHighPass(p.low_cut_hz, 0.707, kSampleRateD) : BiquadCoeffs{};
        hc_c_ = use_hc_ ? DesignLowPass(p.high_cut_hz, 0.707, kSampleRateD) : BiquadCoeffs{};

        for (int ch = 0; ch < 2; ++ch) {
            for (size_t i = 0; i < 4; ++i) para_[ch][i].SetCoeffs(para_c_[i]);
            for (size_t i = 0; i < 10; ++i) graphic_[ch][i].SetCoeffs(graphic_c_[i]);
            tilt_lo_[ch].SetCoeffs(tilt_lo_c_);
            tilt_hi_[ch].SetCoeffs(tilt_hi_c_);
            lc_[ch].SetCoeffs(lc_c_);
            hc_[ch].SetCoeffs(hc_c_);
        }
    }

    const EqParams& GetParams() const noexcept { return params_; }

    // Evaluates exact composite magnitude curve 20*log10|H(e^jw)| across 20 Hz .. 20 kHz (128 points for UI)
    void EvaluateCurveDb(float* out_db, size_t num_points) const noexcept {
        constexpr double f_min = 20.0;
        constexpr double f_max = 20000.0;
        const double ratio = f_max / f_min;
        for (size_t i = 0; i < num_points; ++i) {
            const double t = (num_points > 1) ? static_cast<double>(i) / static_cast<double>(num_points - 1) : 0.0;
            const double f = f_min * std::pow(ratio, t);
            double db = static_cast<double>(params_.level_db);
            if (params_.mode == EqMode::kParametric4Band) {
                for (const auto& c : para_c_) db += c.MagnitudeDb(f, kSampleRateD);
            } else if (params_.mode == EqMode::kGraphic10Band) {
                for (const auto& c : graphic_c_) db += c.MagnitudeDb(f, kSampleRateD);
            } else {
                db += tilt_lo_c_.MagnitudeDb(f, kSampleRateD) + tilt_hi_c_.MagnitudeDb(f, kSampleRateD);
            }
            if (use_lc_) db += lc_c_.MagnitudeDb(f, kSampleRateD);
            if (use_hc_) db += hc_c_.MagnitudeDb(f, kSampleRateD);
            out_db[i] = static_cast<float>(db);
        }
    }

    void ProcessSubBlock16(const float* in_l, const float* in_r, float* out_l, float* out_r) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
            }
            return;
        }
        const float lvl = DbToLin(params_.level_db);
        for (size_t i = 0; i < kSubBlockSize; ++i) {
            float l = in_l[i];
            float r = params_.is_stereo ? in_r[i] : 0.5f * (in_l[i] + in_r[i]);
            if (!params_.is_stereo) l = r;

            auto run_ch = [&](int ch, float s) {
                if (params_.mode == EqMode::kParametric4Band) {
                    for (auto& b : para_[ch]) s = b.Process(s);
                } else if (params_.mode == EqMode::kGraphic10Band) {
                    for (auto& b : graphic_[ch]) s = b.Process(s);
                } else {
                    s = tilt_hi_[ch].Process(tilt_lo_[ch].Process(s));
                }
                if (use_lc_) s = lc_[ch].Process(s);
                if (use_hc_) s = hc_[ch].Process(s);
                return s * lvl;
            };

            out_l[i] = run_ch(0, l);
            out_r[i] = params_.is_stereo ? run_ch(1, r) : out_l[i];
        }
    }

private:
    EqParams params_{};
    std::array<BiquadCoeffs, 4> para_c_{};
    std::array<BiquadCoeffs, 10> graphic_c_{};
    BiquadCoeffs tilt_lo_c_{}, tilt_hi_c_{}, lc_c_{}, hc_c_{};
    bool use_lc_ = false;
    bool use_hc_ = false;

    std::array<std::array<BiquadDF2T, 4>, 2> para_;
    std::array<std::array<BiquadDF2T, 10>, 2> graphic_;
    std::array<BiquadDF2T, 2> tilt_lo_, tilt_hi_, lc_, hc_;
};

}  // namespace stadium_dsp
