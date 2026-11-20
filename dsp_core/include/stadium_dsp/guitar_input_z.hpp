#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

enum class GuitarPickupType : int32_t {
    kSingleCoil = 0,
    kPafHumbucker,
    kHighOutputHumbucker,
    kActiveBypass,
    kCount = 4,
};

struct GuitarPickupSpec {
    const char* name;
    double L_h;      // Pickup inductance (Henrys)
    double R_ohm;    // Pickup DC winding resistance (Ohms)
    double C_f;      // Pickup + cable capacitance (Farads)
};

inline const GuitarPickupSpec& GetGuitarPickupSpec(GuitarPickupType type) noexcept {
    static constexpr std::array<GuitarPickupSpec, 4> kSpecs = {{
        {"Single-Coil (Strat/Tele)", 2.6, 6500.0, 520.0e-12},
        {"PAF Humbucker", 4.8, 8200.0, 560.0e-12},
        {"High-Output Humbucker", 7.2, 13500.0, 520.0e-12},
        {"Active Pickup (Bypass)", 0.0, 0.0, 0.0},
    }};
    const int idx = std::max(0, std::min(static_cast<int>(type), 3));
    return kSpecs[static_cast<size_t>(idx)];
}

// Compensates a 1 MOhm USB-C audio interface input to physically model passive pickup RLC loading
// into target input impedance R_in in {10k, 22k, 32k, 70k, 90k, 136k, 230k, 1M} Ohms:
//   H_ratio(s) = H_pickup(s; R_target) / H_pickup(s; 1M)
// Discretized via exact Matched Pole-Zero (s -> z = exp(s / Fs)) with exact DC & high-freq matching.
class GuitarPickupInputZ {
public:
    GuitarPickupInputZ() noexcept { Configure(GuitarPickupType::kSingleCoil, 1000000.0f); }

    void Reset() noexcept { filter_.Reset(); }

    void Configure(GuitarPickupType pickup_type, float target_ohms,
                   double fs = kSampleRateD) noexcept {
        pickup_type_ = pickup_type;
        target_ohms_ = std::max(5000.0f, std::min(target_ohms, 1000000.0f));
        fs_ = fs;

        if (pickup_type == GuitarPickupType::kActiveBypass || target_ohms_ >= 995000.0f) {
            bypass_ = true;
            filter_.SetCoeffs(BiquadCoeffs{});
            return;
        }

        bypass_ = false;
        const auto& pk = GetGuitarPickupSpec(pickup_type);
        const double L = pk.L_h;
        const double Rp = pk.R_ohm;
        const double Cp = pk.C_f;
        const double R_ref = 1.0e6;
        const double R_tgt = static_cast<double>(target_ohms_);

        // H(s; R) = (1 / (L*C)) / (s^2 + (Rp/L + 1/(R*C))*s + (1 + Rp/R)/(L*C))
        // Therefore H_ratio(s) = (s^2 + z1*s + z0) / (s^2 + p1*s + p0)
        // where zeros come from R_ref = 1 MOhm denominator and poles from R_tgt denominator!
        auto map_quad_mpz = [&](double b1_s, double b0_s, double& c1_z, double& c2_z,
                                bool& is_real, double& r1_out, double& r2_out) {
            const double disc = b1_s * b1_s - 4.0 * b0_s;
            if (disc < 0.0) {
                is_real = false;
                const double sigma = -0.5 * b1_s;
                const double omega = 0.5 * std::sqrt(-disc);
                const double r = std::exp(sigma / fs);
                c1_z = -2.0 * r * std::cos(omega / fs);
                c2_z = r * r;
            } else {
                is_real = true;
                const double sd = std::sqrt(disc);
                r1_out = std::exp(0.5 * (-b1_s + sd) / fs);
                r2_out = std::exp(0.5 * (-b1_s - sd) / fs);
                c1_z = -(r1_out + r2_out);
                c2_z = r1_out * r2_out;
            }
        };

        const double z1_s = Rp / L + 1.0 / (R_ref * Cp);
        const double z0_s = (1.0 + Rp / R_ref) / (L * Cp);
        const double p1_s = Rp / L + 1.0 / (R_tgt * Cp);
        const double p0_s = (1.0 + Rp / R_tgt) / (L * Cp);

        double nz1 = 0.0, nz2 = 0.0, da1 = 0.0, da2 = 0.0;
        bool z_real = false, p_real = false;
        double zr1 = 0.0, zr2 = 0.0, pr1 = 0.0, pr2 = 0.0;
        map_quad_mpz(z1_s, z0_s, nz1, nz2, z_real, zr1, zr2);
        map_quad_mpz(p1_s, p0_s, da1, da2, p_real, pr1, pr2);

        // Closed-form fast real-pole Nyquist matching (< 0.01 dB error for low-Z 10k..22k loads)
        if (p_real) {
            const double f_hi = 7000.0;
            const double w = kTwoPiD * f_hi / fs;
            const std::complex<double> z1(std::cos(w), -std::sin(w));
            const std::complex<double> z2 = z1 * z1;
            const std::complex<double> s(0.0, kTwoPiD * f_hi);
            const auto h_ana = [&](double R) {
                return 1.0 / (1.0 + (Rp + s * L) * ((1.0 / R) + s * Cp));
            };
            const double num_ratio = std::abs(1.0 + nz1 * z1 + nz2 * z2) / (1.0 + nz1 + nz2);
            const double p1_ratio = std::abs(1.0 - pr1 * z1) / (1.0 - pr1);
            const double ref_ratio = std::abs(h_ana(R_tgt) / h_ana(R_ref)) / (z0_s / p0_s);
            const double t_mag = num_ratio / std::max(p1_ratio * ref_ratio, 1.0e-12);
            const double s2 = std::sin(0.5 * w) * std::sin(0.5 * w);
            const double M = (t_mag * t_mag - 1.0) / std::max(4.0 * s2, 1.0e-12);
            if (std::fabs(M) < 1.0e-9) {
                pr2 = 0.0;
            } else if (M > -0.24) {
                pr2 = (2.0 * M + 1.0 - std::sqrt(std::max(0.0, 4.0 * M + 1.0))) / (2.0 * M);
                pr2 = std::max(-0.85, std::min(0.99, pr2));
            }
            da1 = -(pr1 + pr2);
            da2 = pr1 * pr2;
        }

        // Normalize gain exactly at DC: H_ratio(0) = z0_s / p0_s = (1 + Rp/R_ref) / (1 + Rp/R_tgt)
        const double dc_target = z0_s / p0_s;
        const double dc_mpz = (1.0 + nz1 + nz2) / (1.0 + da1 + da2);
        const double k = dc_target / std::max(dc_mpz, 1.0e-12);

        filter_.SetCoeffs(BiquadCoeffs{k, k * nz1, k * nz2, da1, da2});
    }

    inline float ProcessSample(float x) noexcept {
        return bypass_ ? x : filter_.Process(x);
    }

    inline void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        if (bypass_) {
            for (size_t i = 0; i < kSubBlockSize; ++i) out16[i] = in16[i];
            return;
        }
        for (size_t i = 0; i < kSubBlockSize; ++i) {
            out16[i] = filter_.Process(in16[i]);
        }
    }

    double EvalMagnitudeDb(double freq_hz) const noexcept {
        if (bypass_) return 0.0;
        return filter_.GetCoeffs().MagnitudeDb(freq_hz, fs_);
    }

    float GetTargetOhms() const noexcept { return target_ohms_; }

private:
    GuitarPickupType pickup_type_ = GuitarPickupType::kSingleCoil;
    float target_ohms_ = 1000000.0f;
    double fs_ = kSampleRateD;
    bool bypass_ = true;
    BiquadDF2T filter_;
};

}  // namespace stadium_dsp
