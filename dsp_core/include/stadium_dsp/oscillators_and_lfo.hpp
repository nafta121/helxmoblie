#pragma once

#include <array>
#include <cmath>
#include <cstddef>

#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// Zero-LUT extra-precision parabolic sine approximation for phase in [-1, 1] (maps [-pi, pi])
// Max error < 0.0011 without any memory table lookup; directly vectorizable with NEON FMA.
inline float ParabolicSineNormalized(float x) noexcept {
    // Wrap to [-1, 1]
    x = x - 2.0f * std::floor((x + 1.0f) * 0.5f);
    const float y = 4.0f * x * (1.0f - std::fabs(x));
    return 0.225f * (y * std::fabs(y) - y) + y;
}

inline Vec4f ParabolicSineVec4(Vec4f x) noexcept {
    const Vec4f one = Vec4f::Splat(1.0f);
    const Vec4f four = Vec4f::Splat(4.0f);
    const Vec4f p = Vec4f::Splat(0.225f);
    const Vec4f y = four * x * (one - Vec4f::Abs(x));
    return Vec4f::Fma(y, p, (y * Vec4f::Abs(y) - y));
}

// Variable Triangle / Sawtooth LFO (shape in [0.01 .. 0.99], 0.5 = pure triangle)
class TriSawLfo {
public:
    void Reset(float initial_phase = 0.0f) noexcept {
        phase_ = initial_phase - std::floor(initial_phase);
    }

    inline float Process(float rate_hz, float shape = 0.5f, float fs = kSampleRate) noexcept {
        shape = Clamp(shape, 0.02f, 0.98f);
        float y = 0.0f;
        if (phase_ < shape) {
            y = -1.0f + 2.0f * (phase_ / shape);
        } else {
            y = 1.0f - 2.0f * ((phase_ - shape) / (1.0f - shape));
        }
        phase_ += rate_hz / fs;
        if (phase_ >= 1.0f) phase_ -= 1.0f;
        return y;
    }

    float GetPhase() const noexcept { return phase_; }

private:
    float phase_ = 0.0f;
};

// 3D Chua Chaotic Attractor LFO (float64 integration) for organic non-repeating tape wow/flutter
class ChuaChaoticLfo {
public:
    ChuaChaoticLfo() noexcept { Reset(); }

    void Reset() noexcept {
        x_ = 0.12;
        y_ = 0.02;
        z_ = -0.15;
    }

    inline float ProcessSample(double step_scale = 1.0) noexcept {
        // Classic double-scroll Chua parameters: alpha = 15.6, beta = 28.0, m0 = -1.143, m1 = -0.714
        constexpr double alpha = 15.6;
        constexpr double beta = 28.0;
        constexpr double m0 = -1.143;
        constexpr double m1 = -0.714;
        const double dt = 0.0025 * std::max(0.05, std::min(step_scale, 4.0));

        auto f_chua = [](double v) {
            return m1 * v + 0.5 * (m0 - m1) * (std::fabs(v + 1.0) - std::fabs(v - 1.0));
        };

        // 2nd-order midpoint Runge-Kutta step for stability
        const double dx1 = alpha * (y_ - x_ - f_chua(x_));
        const double dy1 = x_ - y_ + z_;
        const double dz1 = -beta * y_;

        const double xm = x_ + 0.5 * dt * dx1;
        const double ym = y_ + 0.5 * dt * dy1;
        const double zm = z_ + 0.5 * dt * dz1;

        x_ += dt * alpha * (ym - xm - f_chua(xm));
        y_ += dt * (xm - ym + zm);
        z_ += dt * (-beta * ym);

        // Guard against any numeric blowup
        if (!std::isfinite(x_) || std::fabs(x_) > 8.0) Reset();
        return Clamp(static_cast<float>(x_ * 0.42), -1.0f, 1.0f);
    }

private:
    double x_ = 0.12;
    double y_ = 0.02;
    double z_ = -0.15;
};

// Smooth 32-point raised-cosine S-curve transition lookup (used by Horizon Gate & crossfades)
inline constexpr std::array<float, 32> BuildSCurve32() noexcept {
    std::array<float, 32> t{};
    for (size_t i = 0; i < 32; ++i) {
        const float u = static_cast<float>(i) / 31.0f;
        t[i] = u * u * (3.0f - 2.0f * u);
    }
    return t;
}

inline float EvalSCurve32(float u) noexcept {
    static constexpr auto kTable = BuildSCurve32();
    u = Clamp(u, 0.0f, 1.0f) * 31.0f;
    const size_t i0 = static_cast<size_t>(u);
    const size_t i1 = std::min<size_t>(31, i0 + 1);
    const float frac = u - static_cast<float>(i0);
    return kTable[i0] * (1.0f - frac) + kTable[i1] * frac;
}

// Orthonormal 8x8 Fast Walsh-Hadamard Transform (H8^T * H8 = I, exact L2 norm preservation)
inline void ApplyHadamard8x8(float v[8]) noexcept {
    // Stage 1 (stride 1)
    float a0 = v[0] + v[1], a1 = v[0] - v[1];
    float a2 = v[2] + v[3], a3 = v[2] - v[3];
    float a4 = v[4] + v[5], a5 = v[4] - v[5];
    float a6 = v[6] + v[7], a7 = v[6] - v[7];
    // Stage 2 (stride 2)
    float b0 = a0 + a2, b1 = a1 + a3, b2 = a0 - a2, b3 = a1 - a3;
    float b4 = a4 + a6, b5 = a5 + a7, b6 = a4 - a6, b7 = a5 - a7;
    // Stage 3 (stride 4) * (1 / sqrt(8))
    constexpr float kInvSqrt8 = 0.35355339059327376220f;
    v[0] = (b0 + b4) * kInvSqrt8;
    v[1] = (b1 + b5) * kInvSqrt8;
    v[2] = (b2 + b6) * kInvSqrt8;
    v[3] = (b3 + b7) * kInvSqrt8;
    v[4] = (b0 - b4) * kInvSqrt8;
    v[5] = (b1 - b5) * kInvSqrt8;
    v[6] = (b2 - b6) * kInvSqrt8;
    v[7] = (b3 - b7) * kInvSqrt8;
}

}  // namespace stadium_dsp
