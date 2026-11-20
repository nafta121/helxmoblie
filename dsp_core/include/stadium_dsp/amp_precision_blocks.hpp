#pragma once

#include <algorithm>
#include <cmath>

#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// Victoria/T11 Micro-Physics Block 1:
// Asymmetric Full-Wave Rectifier B+ Ripple (31% fast diode charge / 69% RC cap discharge @ 100/120 Hz)
// plus independent 50/60 Hz filament heater hum injection.
class AsymmetricRectifierRippleGen {
public:
    void Configure(float ripple_hz = 120.0f, float mains_hz = 60.0f, float fs = kSampleRate) noexcept {
        ripple_inc_ = ripple_hz / fs;
        mains_inc_ = mains_hz / fs;
    }

    void Reset() noexcept {
        ripple_phase_ = 0.0f;
        mains_phase_ = 0.0f;
    }

    // Returns zero-mean asymmetric sawtooth-like rectifier ripple in [-1, +1]
    // with exact 31% rising charge interval and 69% falling discharge interval.
    inline float NextRippleSample() noexcept {
        constexpr float kChargeDuty = 0.31f;
        float r = 0.0f;
        if (ripple_phase_ < kChargeDuty) {
            const float u = ripple_phase_ / kChargeDuty;
            r = -1.0f + 2.0f * (0.5f - 0.5f * std::cos(kPi * u));
        } else {
            const float u = (ripple_phase_ - kChargeDuty) / (1.0f - kChargeDuty);
            // Exponential-like smooth RC discharge curve
            r = 1.0f - 2.0f * (1.0f - std::exp(-2.2f * u)) / (1.0f - std::exp(-2.2f));
        }
        ripple_phase_ += ripple_inc_;
        if (ripple_phase_ >= 1.0f) ripple_phase_ -= 1.0f;
        return r;
    }

    inline float NextHeaterHumSample() noexcept {
        const float h = std::sin(kTwoPi * mains_phase_);
        mains_phase_ += mains_inc_;
        if (mains_phase_ >= 1.0f) mains_phase_ -= 1.0f;
        return h;
    }

private:
    float ripple_inc_ = 120.0f / kSampleRate;
    float mains_inc_ = 60.0f / kSampleRate;
    float ripple_phase_ = 0.0f;
    float mains_phase_ = 0.0f;
};

// Victoria/T11 Micro-Physics Block 2:
// Half-Wave Cathode Bypass Capacitor dynamic bias shift (Rk || Ck, e.g. 2.7k||0.68uF vs 1.5k||22uF)
class HalfWaveCathodeBypass {
public:
    void Configure(float rk_ohm, float ck_farad, float amount = 0.25f, float fs = kSampleRate) noexcept {
        const float tau = std::max(0.0005f, rk_ohm * ck_farad);
        alpha_ = 1.0f - std::exp(-1.0f / (tau * fs));
        amount_ = Clamp(amount, 0.0f, 0.8f);
    }

    void Reset() noexcept { vk_ = 0.0f; }

    inline float ShiftGridVoltage(float v_in) noexcept {
        return v_in - amount_ * vk_;
    }

    inline void UpdateFromPlateCurrent(float y_triode) noexcept {
        const float ik = std::max(0.0f, y_triode);
        vk_ += alpha_ * (ik - vk_);
    }

private:
    float alpha_ = 0.001f;
    float amount_ = 0.22f;
    float vk_ = 0.0f;
};

// Victoria/T11 Micro-Physics Block 3:
// Class-A to Class-AB Push-Pull Crossover Distortion Stage with slow thermal envelope (~8 ms)
class ClassBCrossoverStage {
public:
    void Configure(float bias_coldness, bool single_ended, float fs = kSampleRate) noexcept {
        // bias_coldness: 0.0 = pure hot Class-A (Vox AC30 / 5F1), 1.0 = cold fixed-bias Class-AB
        coldness_ = single_ended ? 0.0f : Clamp(bias_coldness, 0.0f, 1.0f);
        single_ended_ = single_ended;
        env_alpha_ = 1.0f - std::exp(-1.0f / (0.008f * fs));
    }

    void Reset() noexcept { thermal_env_ = 0.0f; }

    inline float Process(float x) noexcept {
        thermal_env_ += env_alpha_ * (std::fabs(x) - thermal_env_);
        if (single_ended_) {
            // Single-ended Class-A power stage: asymmetric conduction generating rich even harmonics (H2)
            const float asym = x + 0.24f * x * x - 0.05f * x * x * x;
            return std::tanh(asym * 1.15f) - 0.10f * thermal_env_;
        }
        // Class-AB push-pull crossover deadzone scaled by coldness & modulated slightly by thermal bias drift
        const float notch = coldness_ * 0.035f * (1.0f + 0.35f * thermal_env_);
        if (notch < 1.0e-5f) return x;
        const float ax = std::fabs(x);
        const float gain = (ax + 0.35f * notch) / (ax + notch);
        return x * gain;
    }

private:
    float coldness_ = 0.3f;
    bool single_ended_ = false;
    float env_alpha_ = 0.0026f;
    float thermal_env_ = 0.0f;
};

// Victoria/T11 Micro-Physics Block 4:
// Clamped Interstage Coupling-Cap Grid-Bias Excursion (blocking distortion recovery tau = Rg*Cc)
class ClampedGridBiasExcursion {
public:
    void Configure(float rg_ohm, float cc_farad, float bias_x_depth = 0.5f, float fs = kSampleRate) noexcept {
        const float tau_charge = 0.0008f;
        const float tau_release = std::max(0.002f, rg_ohm * cc_farad);
        alpha_chg_ = 1.0f - std::exp(-1.0f / (tau_charge * fs));
        alpha_rel_ = 1.0f - std::exp(-1.0f / (tau_release * fs));
        depth_ = Clamp(bias_x_depth, 0.0f, 1.0f);
    }

    void Reset() noexcept { v_shift_ = 0.0f; }

    inline float Process(float v_grid, float threshold = 0.35f) noexcept {
        const float over = std::max(0.0f, v_grid - threshold);
        if (over > v_shift_) {
            v_shift_ += alpha_chg_ * (over - v_shift_);
        } else {
            v_shift_ += alpha_rel_ * (0.0f - v_shift_);
        }
        v_shift_ = Clamp(v_shift_, 0.0f, 0.55f);
        return v_grid - depth_ * 0.45f * v_shift_;
    }

private:
    float alpha_chg_ = 0.025f;
    float alpha_rel_ = 0.004f;
    float depth_ = 0.5f;
    float v_shift_ = 0.0f;
};

// First-Order Antiderivative Anti-Aliasing (ADAA1) hyperbolic tangent triode waveshaper:
//   f(x) = tanh(x),  F(x) = ln(cosh(x)) = |x| + ln(1 + exp(-2|x|)) - ln(2)
class Adaa1TanhStage {
public:
    void Reset() noexcept {
        x_prev_ = 0.0f;
        F_prev_ = 0.0f;
    }

    inline float Process(float x) noexcept {
        const float F = Antiderivative(x);
        const float dx = x - x_prev_;
        float y = 0.0f;
        if (std::fabs(dx) < 1.0e-5f) {
            y = std::tanh(0.5f * (x + x_prev_));
        } else {
            y = (F - F_prev_) / dx;
        }
        x_prev_ = x;
        F_prev_ = F;
        return y;
    }

private:
    static inline float Antiderivative(float x) noexcept {
        const float ax = std::fabs(x);
        return ax + std::log1p(std::exp(-2.0f * ax)) - 0.6931471805599453f;
    }

    float x_prev_ = 0.0f;
    float F_prev_ = 0.0f;
};

}  // namespace stadium_dsp
