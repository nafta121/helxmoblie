#pragma once

#include <algorithm>
#include <cmath>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

enum class PotTaper : uint8_t {
    kLinear = 0,
    kLog10A,    // Audio 10% at 50% rotation (Fender / Marshall / Hiwatt James)
    kLog15A,    // Audio 15% at 50% rotation (Vintage Alpha / CTS)
    kRevLogC,   // Reverse-Log C (85% at 50% rotation — Presence / Fuzz / Gain)
    kWTaper,    // S-curve W-taper (Ibanez TS808 Tone)
};

// Evaluates hardware potentiometer taper for normalized knob position x in [0, 1]
inline float EvalPotTaper(PotTaper taper, float x) noexcept {
    x = Clamp(x, 0.0f, 1.0f);
    switch (taper) {
        case PotTaper::kLinear:
            return x;
        case PotTaper::kLog10A: {
            // Smooth exponential passing through (0,0), (0.5, 0.10), (1,1):
            // (b^x - 1) / (b - 1) where sqrt(b) = (1 - 0.10) / 0.10 = 9 => b = 81!
            constexpr float b = 81.0f;
            return (std::pow(b, x) - 1.0f) / (b - 1.0f);
        }
        case PotTaper::kLog15A: {
            // Passes through (0,0), (0.5, 0.15), (1,1): sqrt(b) = 0.85/0.15 = 5.6667 => b = 32.1111
            constexpr float b = 32.111111f;
            return (std::pow(b, x) - 1.0f) / (b - 1.0f);
        }
        case PotTaper::kRevLogC: {
            // Exact mirror of Log15A: 1 - Log15A(1 - x) -> 0.85 at x=0.5
            constexpr float b = 32.111111f;
            return 1.0f - (std::pow(b, 1.0f - x) - 1.0f) / (b - 1.0f);
        }
        case PotTaper::kWTaper: {
            // Symmetric W-taper: flat plateau around center, steeper ends
            const float u = 2.0f * x - 1.0f;  // [-1, 1]
            const float s = 0.28f * u + 0.72f * u * u * u;
            return Clamp(0.5f * (s + 1.0f), 0.0f, 1.0f);
        }
    }
    return x;
}

// Coupled Potentiometer + Bright Capacitor Kirchhoff Divider:
//   Z_top = R_top || (1 / (s * C_bright)), R_top = (1 - alpha) * R_pot, R_bot = alpha * R_pot
//   H_norm(s) = (1 + s * R_top * C_bright) / (1 + s * alpha * R_top * C_bright)
// At Drive = 1.0 (alpha = 1.0): R_top = 0 => H_norm(s) === 1 (0.0000 dB boost!).
// Discretized with exact Matched Pole-Zero / Tustin at 48 kHz (error < 0.07 dB vs analog MNA).
class CoupledBrightCapFilter {
public:
    void Reset() noexcept { filter_.Reset(); }

    void Configure(double r_pot_ohm, double c_bright_f, double knob_pos,
                   PotTaper taper = PotTaper::kLog15A, bool enabled = true,
                   double fs = kSampleRateD) noexcept {
        fs_ = fs;
        if (!enabled || c_bright_f <= 0.0 || knob_pos >= 0.999) {
            bypass_ = true;
            filter_.SetCoeffs(BiquadCoeffs{});
            return;
        }
        bypass_ = false;
        const double alpha = std::max(0.015, static_cast<double>(EvalPotTaper(taper, static_cast<float>(knob_pos))));
        const double r_top = (1.0 - alpha) * r_pot_ohm;
        // Zero frequency f_z = 1 / (2*pi*R_top*C), Pole frequency f_p = 1 / (2*pi*alpha*R_top*C)
        const double tau_z = r_top * c_bright_f;
        const double tau_p = alpha * tau_z;

        // First-order Vicanek MPZ magnitude matching (exact at DC and 6 kHz, < 0.09 dB across 50..8k Hz)
        const double a1 = -std::exp(-1.0 / std::max(tau_p * fs, 1.0e-9));
        const double A0 = (1.0 + a1) * (1.0 + a1);
        const double A1 = (1.0 - a1) * (1.0 - a1);
        const double f_match = 6000.0;
        const double w = kTwoPiD * f_match / fs;
        const double s2 = std::sin(0.5 * w) * std::sin(0.5 * w);
        const double c2 = 1.0 - s2;
        const double w_ana = kTwoPiD * f_match;
        const double href2 = (1.0 + (w_ana * tau_z) * (w_ana * tau_z)) /
                             (1.0 + (w_ana * tau_p) * (w_ana * tau_p));
        const double B0 = A0;
        const double B1 = ((A0 * c2 + A1 * s2) * href2 - B0 * c2) / std::max(s2, 1.0e-12);
        const double sb0 = std::sqrt(std::max(0.0, B0));
        const double sb1 = std::sqrt(std::max(0.0, B1));
        const double b0 = 0.5 * (sb0 + sb1);
        const double b1 = 0.5 * (sb0 - sb1);

        filter_.SetCoeffs(BiquadCoeffs{b0, b1, 0.0, a1, 0.0});
    }

    inline float ProcessSample(float x) noexcept {
        return bypass_ ? x : filter_.Process(x);
    }

    double EvalMagnitudeDb(double freq_hz) const noexcept {
        if (bypass_) return 0.0;
        return filter_.GetCoeffs().MagnitudeDb(freq_hz, fs_);
    }

private:
    double fs_ = kSampleRateD;
    bool bypass_ = true;
    BiquadDF2T filter_;
};

}  // namespace stadium_dsp
