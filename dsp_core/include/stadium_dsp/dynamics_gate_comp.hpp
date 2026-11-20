#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/oscillators_and_lfo.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

struct GateParams {
    bool enabled = false;
    float threshold_db = -48.0f;   // -96..0 dBFS
    float decay_ms = 80.0f;        // 10..1000 ms
    float range_db = -60.0f;       // Max attenuation floor
    bool lookahead_enabled = true; // 32-sample (0.667 ms) look-ahead vs 0 ms fast mode
};

// Horizon Precision Gate: 850 Hz Sidechain HPF + Dual-Window Hysteresis + 32-pt S-Curve + GR Telemetry
class HorizonPrecisionGate {
public:
    HorizonPrecisionGate() noexcept { SetParams(GateParams{}); }

    void Reset() noexcept {
        sc_hpf_.Reset();
        ring_.fill(0.0f);
        ring_write_ = 0;
        env_ = 0.0f;
        open_state_ = 0.0f;
        gate_is_open_ = false;
        current_gr_db_ = 0.0f;
    }

    void SetParams(const GateParams& p) noexcept {
        params_ = p;
        sc_hpf_.SetCoeffs(DesignHighPass(850.0, 0.707, kSampleRateD));
        const float rel_s = std::max(0.01f, p.decay_ms * 0.001f);
        release_alpha_ = 1.0f - std::exp(-1.0f / (rel_s * kSampleRate));
        attack_alpha_ = 1.0f - std::exp(-1.0f / (0.0005f * kSampleRate));
    }

    const GateParams& GetParams() const noexcept { return params_; }
    float GetCurrentGainReductionDb() const noexcept { return current_gr_db_; }

    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        if (!params_.enabled) {
            current_gr_db_ = 0.0f;
            for (size_t i = 0; i < kSubBlockSize; ++i) out16[i] = in16[i];
            return;
        }

        const float open_thresh = DbToLin(params_.threshold_db);
        const float close_thresh = DbToLin(params_.threshold_db - 4.5f);  // 4.5 dB Schmitt hysteresis
        const float min_gain = DbToLin(Clamp(params_.range_db, -90.0f, 0.0f));
        float max_gr = 0.0f;

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            const float x = in16[i];
            ring_[ring_write_] = x;
            const size_t read_idx = params_.lookahead_enabled ? ((ring_write_ + 32) & 63u) : ring_write_;
            const float delayed_x = ring_[read_idx];
            ring_write_ = (ring_write_ + 1) & 63u;

            // Sidechain combines full-band peak & 850 Hz pick-attack detector
            const float sc = std::max(std::fabs(x), std::fabs(sc_hpf_.Process(x)) * 1.35f);
            if (sc > env_) {
                env_ += 0.25f * (sc - env_);
            } else {
                env_ += 0.008f * (sc - env_);
            }

            if (!gate_is_open_ && env_ >= open_thresh) gate_is_open_ = true;
            if (gate_is_open_ && env_ <= close_thresh) gate_is_open_ = false;

            const float target_open = gate_is_open_ ? 1.0f : 0.0f;
            const float alpha = (target_open > open_state_) ? attack_alpha_ : release_alpha_;
            open_state_ += alpha * (target_open - open_state_);

            const float curve = EvalSCurve32(open_state_);
            const float gain = min_gain + (1.0f - min_gain) * curve;
            out16[i] = delayed_x * gain;
            max_gr = std::max(max_gr, -LinToDb(gain));
        }
        current_gr_db_ = Clamp(max_gr, 0.0f, 60.0f);
    }

private:
    GateParams params_{};
    BiquadDF2T sc_hpf_;
    std::array<float, 64> ring_{};
    size_t ring_write_ = 0;
    float env_ = 0.0f;
    float open_state_ = 0.0f;
    bool gate_is_open_ = false;
    float attack_alpha_ = 0.04f;
    float release_alpha_ = 0.00025f;
    float current_gr_db_ = 0.0f;
};

enum class CompModel : int32_t {
    kLaStudioOpto = 0,  // Teletronix LA-2A T4B dual-release optical feedback compressor
    kRedSqueezeVca,     // MXR Dyna Comp / VCA Ross-style punchy compressor
    kCount = 2,
};

struct CompParams {
    bool enabled = false;
    CompModel model = CompModel::kLaStudioOpto;
    float peak_reduction = 0.50f;
    float gain_db = 0.0f;
    float mix = 1.0f;
    float attack_ms = 10.0f;
    float release_ms = 120.0f;
};

// Studio Opto (LA-2A T4B Dual-Release Optical Memory) & VCA Compressor + GR Telemetry
class StudioOptoVcaComp {
public:
    StudioOptoVcaComp() noexcept { SetParams(CompParams{}); }

    void Reset() noexcept {
        t4b_fast_ = 0.0f;
        t4b_slow_mem_ = 0.0f;
        vca_env_ = 0.0f;
        prev_out_ = 0.0f;
        current_gr_db_ = 0.0f;
    }

    void SetParams(const CompParams& p) noexcept { params_ = p; }
    const CompParams& GetParams() const noexcept { return params_; }
    float GetCurrentGainReductionDb() const noexcept { return current_gr_db_; }

    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        if (!params_.enabled) {
            current_gr_db_ = 0.0f;
            for (size_t i = 0; i < kSubBlockSize; ++i) out16[i] = in16[i];
            return;
        }

        const float makeup = DbToLin(params_.gain_db);
        const float mix = Clamp(params_.mix, 0.0f, 1.0f);
        float max_gr = 0.0f;

        if (params_.model == CompModel::kLaStudioOpto) {
            // LA-2A T4B electroluminescent cell:
            // Fast initial release (~60 ms) + slow phosphor memory tail (~650 ms) that charges up on sustained notes!
            const float thresh = DbToLin(-4.0f - 32.0f * Clamp(params_.peak_reduction, 0.0f, 1.0f));
            const float atk_fast = 1.0f - std::exp(-1.0f / (0.008f * kSampleRate));
            const float rel_fast = 1.0f - std::exp(-1.0f / (0.055f * kSampleRate));
            const float chg_mem = 1.0f - std::exp(-1.0f / (0.180f * kSampleRate));
            const float rel_mem = 1.0f - std::exp(-1.0f / (0.650f * kSampleRate));

            for (size_t i = 0; i < kSubBlockSize; ++i) {
                const float x = in16[i];
                // Feedback topology detects post-gain-cell level
                const float det = std::fabs(prev_out_) + 0.35f * std::fabs(x);
                const float excess_db = std::max(0.0f, LinToDb(det) - LinToDb(thresh));
                const float target_gr = excess_db * 0.72f;  // ~3.5:1 soft-knee optical ratio

                if (target_gr > t4b_fast_) {
                    t4b_fast_ += atk_fast * (target_gr - t4b_fast_);
                } else {
                    t4b_fast_ += rel_fast * (target_gr - t4b_fast_);
                }
                // Phosphor memory accumulates slowly during sustained compression and decays very slowly
                if (t4b_fast_ > t4b_slow_mem_) {
                    t4b_slow_mem_ += chg_mem * (t4b_fast_ - t4b_slow_mem_);
                } else {
                    t4b_slow_mem_ += rel_mem * (t4b_fast_ - t4b_slow_mem_);
                }

                const float eff_gr = std::max(t4b_fast_, 0.65f * t4b_slow_mem_);
                const float gain = DbToLin(-Clamp(eff_gr, 0.0f, 42.0f));
                const float wet = x * gain;
                prev_out_ = wet;
                out16[i] = (x * (1.0f - mix) + wet * mix) * makeup;
                max_gr = std::max(max_gr, eff_gr);
            }
        } else {
            // Red Squeeze VCA feedforward compressor
            const float thresh_db = -6.0f - 30.0f * Clamp(params_.peak_reduction, 0.0f, 1.0f);
            const float atk = 1.0f - std::exp(-1.0f / (std::max(0.5f, params_.attack_ms) * 0.001f * kSampleRate));
            const float rel = 1.0f - std::exp(-1.0f / (std::max(10.0f, params_.release_ms) * 0.001f * kSampleRate));

            for (size_t i = 0; i < kSubBlockSize; ++i) {
                const float x = in16[i];
                const float lvl = std::fabs(x);
                vca_env_ += ((lvl > vca_env_) ? atk : rel) * (lvl - vca_env_);
                const float over_db = std::max(0.0f, LinToDb(vca_env_) - thresh_db);
                const float gr_db = over_db * 0.80f;  // 5:1 snappy squeeze
                const float gain = DbToLin(-Clamp(gr_db, 0.0f, 42.0f));
                out16[i] = (x * (1.0f - mix) + (x * gain) * mix) * makeup;
                max_gr = std::max(max_gr, gr_db);
            }
        }
        current_gr_db_ = Clamp(max_gr, 0.0f, 42.0f);
    }

private:
    CompParams params_{};
    float t4b_fast_ = 0.0f;
    float t4b_slow_mem_ = 0.0f;
    float vca_env_ = 0.0f;
    float prev_out_ = 0.0f;
    float current_gr_db_ = 0.0f;
};

}  // namespace stadium_dsp
