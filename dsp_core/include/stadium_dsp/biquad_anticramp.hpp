#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>

#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// Double-precision DF2T Biquad with Nyquist-decramped (Vicanek / Orfanidis magnitude-matched)
// design options for high-frequency accuracy near 24 kHz at Fs = 48 kHz.
struct BiquadCoeffs {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;

    std::complex<double> EvalResponse(double freq_hz, double fs = kSampleRateD) const noexcept {
        const double w = kTwoPiD * freq_hz / fs;
        const std::complex<double> z1(std::cos(w), -std::sin(w));
        const std::complex<double> z2 = z1 * z1;
        return (b0 + b1 * z1 + b2 * z2) / (1.0 + a1 * z1 + a2 * z2);
    }

    double MagnitudeDb(double freq_hz, double fs = kSampleRateD) const noexcept {
        const double mag = std::abs(EvalResponse(freq_hz, fs));
        return 20.0 * std::log10(std::max(mag, 1.0e-12));
    }
};

class BiquadDF2T {
public:
    void SetCoeffs(const BiquadCoeffs& c) noexcept { c_ = c; }
    const BiquadCoeffs& GetCoeffs() const noexcept { return c_; }

    void Reset() noexcept {
        z1_ = 0.0;
        z2_ = 0.0;
    }

    inline float Process(float x) noexcept {
        const double xd = static_cast<double>(x);
        const double y = c_.b0 * xd + z1_;
        z1_ = c_.b1 * xd - c_.a1 * y + z2_;
        z2_ = c_.b2 * xd - c_.a2 * y;
        return static_cast<float>(y);
    }

    // Smoothly approach target coefficients (control-rate or per-sample)
    void SmoothTowards(const BiquadCoeffs& target, double alpha) noexcept {
        c_.b0 += alpha * (target.b0 - c_.b0);
        c_.b1 += alpha * (target.b1 - c_.b1);
        c_.b2 += alpha * (target.b2 - c_.b2);
        c_.a1 += alpha * (target.a1 - c_.a1);
        c_.a2 += alpha * (target.a2 - c_.a2);
    }

private:
    BiquadCoeffs c_{};
    double z1_ = 0.0;
    double z2_ = 0.0;
};

// Vicanek (2016) Matched Peaking EQ Biquad (zero Nyquist cramping!)
inline BiquadCoeffs DesignAntiCrampedPeak(double freq_hz, double q, double gain_db,
                                          double fs = kSampleRateD) noexcept {
    freq_hz = std::max(10.0, std::min(freq_hz, fs * 0.485));
    q = std::max(0.15, std::min(q, 24.0));
    if (std::fabs(gain_db) < 1.0e-4) {
        return BiquadCoeffs{};
    }
    const double g = std::pow(10.0, gain_db / 20.0);
    const double w0 = kTwoPiD * freq_hz / fs;
    const double q_eff = q / std::sqrt(g);

    // Pole placement matching analog resonance
    const double decay = std::exp(-w0 / (2.0 * q_eff));
    double a1 = 0.0;
    if (w0 / (2.0 * q_eff) < 1.0) {
        const double r = 1.0 / (2.0 * q_eff);
        const double disc = std::max(0.0, 1.0 - r * r);
        a1 = -2.0 * decay * std::cos(w0 * std::sqrt(disc));
    } else {
        const double r = 1.0 / (2.0 * q_eff);
        const double disc = std::max(0.0, r * r - 1.0);
        a1 = -2.0 * decay * std::cosh(w0 * std::sqrt(disc));
    }
    const double a2 = decay * decay;

    // Solve numerator B coefficients via Vicanek 3-point magnitude matching (DC, w0, Nyquist)
    const double phi0 = 1.0 - std::sin(0.5 * w0) * std::sin(0.5 * w0);
    const double phi1 = std::sin(0.5 * w0) * std::sin(0.5 * w0);
    const double phi2 = 4.0 * phi0 * phi1;

    const double A0 = (1.0 + a1 + a2) * (1.0 + a1 + a2);
    const double A1 = (1.0 - a1 + a2) * (1.0 - a1 + a2);
    const double A2 = -4.0 * a2;

    const double R1 = (A0 * phi0 + A1 * phi1 + A2 * phi2) * (g * g);
    // Match DC = 1 and Nyquist = 1 (or gentle asymptotic analog value)
    const double B0 = A0;
    const double B1 = A1;
    const double B2 = (R1 - B0 * phi0 - B1 * phi1) / std::max(phi2, 1.0e-12);

    const double s0 = std::sqrt(std::max(0.0, B0));
    const double s1 = std::sqrt(std::max(0.0, B1));
    const double W = 0.5 * (s0 + s1);
    const double b1 = 0.5 * (s0 - s1);
    const double disc_b = std::max(0.0, W * W + B2);
    const double b0 = 0.5 * (W + std::sqrt(disc_b));
    const double b2 = W - b0;

    return BiquadCoeffs{b0, b1, b2, a1, a2};
}

inline BiquadCoeffs DesignLowPass(double freq_hz, double q, double fs = kSampleRateD) noexcept {
    freq_hz = std::max(10.0, std::min(freq_hz, fs * 0.485));
    q = std::max(0.2, std::min(q, 20.0));
    const double w0 = kTwoPiD * freq_hz / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    const double inv_a0 = 1.0 / (1.0 + alpha);
    return BiquadCoeffs{
        (1.0 - cw) * 0.5 * inv_a0,
        (1.0 - cw) * inv_a0,
        (1.0 - cw) * 0.5 * inv_a0,
        (-2.0 * cw) * inv_a0,
        (1.0 - alpha) * inv_a0,
    };
}

inline BiquadCoeffs DesignHighPass(double freq_hz, double q, double fs = kSampleRateD) noexcept {
    freq_hz = std::max(10.0, std::min(freq_hz, fs * 0.485));
    q = std::max(0.2, std::min(q, 20.0));
    const double w0 = kTwoPiD * freq_hz / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    const double inv_a0 = 1.0 / (1.0 + alpha);
    return BiquadCoeffs{
        (1.0 + cw) * 0.5 * inv_a0,
        -(1.0 + cw) * inv_a0,
        (1.0 + cw) * 0.5 * inv_a0,
        (-2.0 * cw) * inv_a0,
        (1.0 - alpha) * inv_a0,
    };
}

inline BiquadCoeffs DesignLowShelf(double freq_hz, double slope_q, double gain_db,
                                   double fs = kSampleRateD) noexcept {
    freq_hz = std::max(15.0, std::min(freq_hz, fs * 0.48));
    const double A = std::pow(10.0, gain_db / 40.0);
    const double w0 = kTwoPiD * freq_hz / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * std::max(0.2, slope_q));
    const double two_sqrtA_alpha = 2.0 * std::sqrt(A) * alpha;
    const double a0 = (A + 1.0) + (A - 1.0) * cw + two_sqrtA_alpha;
    const double inv = 1.0 / a0;
    return BiquadCoeffs{
        A * ((A + 1.0) - (A - 1.0) * cw + two_sqrtA_alpha) * inv,
        2.0 * A * ((A - 1.0) - (A + 1.0) * cw) * inv,
        A * ((A + 1.0) - (A - 1.0) * cw - two_sqrtA_alpha) * inv,
        -2.0 * ((A - 1.0) + (A + 1.0) * cw) * inv,
        ((A + 1.0) + (A - 1.0) * cw - two_sqrtA_alpha) * inv,
    };
}

inline BiquadCoeffs DesignHighShelf(double freq_hz, double slope_q, double gain_db,
                                    double fs = kSampleRateD) noexcept {
    freq_hz = std::max(20.0, std::min(freq_hz, fs * 0.48));
    const double A = std::pow(10.0, gain_db / 40.0);
    const double w0 = kTwoPiD * freq_hz / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * std::max(0.2, slope_q));
    const double two_sqrtA_alpha = 2.0 * std::sqrt(A) * alpha;
    const double a0 = (A + 1.0) - (A - 1.0) * cw + two_sqrtA_alpha;
    const double inv = 1.0 / a0;
    return BiquadCoeffs{
        A * ((A + 1.0) + (A - 1.0) * cw + two_sqrtA_alpha) * inv,
        -2.0 * A * ((A - 1.0) + (A + 1.0) * cw) * inv,
        A * ((A + 1.0) + (A - 1.0) * cw - two_sqrtA_alpha) * inv,
        2.0 * ((A - 1.0) - (A + 1.0) * cw) * inv,
        ((A + 1.0) - (A - 1.0) * cw - two_sqrtA_alpha) * inv,
    };
}

// Reactive Cabinet Impedance Descriptor (Victoria-style Z_cab(w)):
// Peak1 (cone mechanical Fs) + Peak2 (enclosure/port) + Voice-Coil Le Shelf + 16-Tap NEON CabZFir
struct ReactiveCabImpedanceDesc {
    float peak1_hz = 96.0f;
    float peak1_q = 3.2f;
    float peak1_db = 6.5f;

    float peak2_hz = 215.0f;
    float peak2_q = 2.0f;
    float peak2_db = 1.8f;

    float le_shelf_hz = 1650.0f;
    float le_shelf_db = 5.2f;

    float z_pre_post = 0.35f;  // 0.0 = fully inside NFB loop, 1.0 = open-loop / zero-NFB post
    alignas(64) std::array<float, 16> zfir_taps = {
        1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
};

class ReactiveCabImpedanceFilter {
public:
    void Configure(const ReactiveCabImpedanceDesc& desc, double fs = kSampleRateD) noexcept {
        desc_ = desc;
        fs_ = fs;
        peak1_.SetCoeffs(DesignAntiCrampedPeak(desc.peak1_hz, desc.peak1_q, desc.peak1_db, fs));
        peak2_.SetCoeffs(DesignAntiCrampedPeak(desc.peak2_hz, desc.peak2_q, desc.peak2_db, fs));
        le_shelf_.SetCoeffs(DesignHighShelf(desc.le_shelf_hz, 0.707, desc.le_shelf_db, fs));
        for (size_t i = 0; i < 16; ++i) {
            zfir_rev_[i] = desc.zfir_taps[15 - i];
        }
    }

    void Reset() noexcept {
        peak1_.Reset();
        peak2_.Reset();
        le_shelf_.Reset();
        fir_hist_.fill(0.0f);
    }

    const ReactiveCabImpedanceDesc& GetDesc() const noexcept { return desc_; }

    inline float Process(float x) noexcept {
        float y = peak1_.Process(x);
        y = peak2_.Process(y);
        y = le_shelf_.Process(y);
        // 16-tap residual cone ripple CabZFir using aligned SIMD dot product
        for (size_t i = 0; i < 15; ++i) {
            fir_hist_[i] = fir_hist_[i + 1];
        }
        fir_hist_[15] = y;
        return DotProductAligned16(fir_hist_.data(), zfir_rev_.data());
    }

private:
    ReactiveCabImpedanceDesc desc_{};
    double fs_ = kSampleRateD;
    BiquadDF2T peak1_;
    BiquadDF2T peak2_;
    BiquadDF2T le_shelf_;
    alignas(64) std::array<float, 16> zfir_rev_{};
    alignas(64) std::array<float, 16> fir_hist_{};
};

}  // namespace stadium_dsp
