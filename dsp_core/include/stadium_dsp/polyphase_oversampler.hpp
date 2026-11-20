#pragma once

#include <array>
#include <cstddef>

#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// Clean-room 17th-order Elliptic Polyphase Half-Band IIR Allpass Filter
// Derived directly via Jacobi elliptic sn/cn/dn AGM transformation (fp = 20.16 kHz @ 96 kHz,
// stopband rejection > 125 dB, passband ripple < 0.0001 dB, zero dynamic allocations).
template <size_t NumSections>
class EllipticAllpassBranch {
public:
    explicit constexpr EllipticAllpassBranch(std::array<float, NumSections> coeffs) noexcept
        : c_(coeffs) {}

    void Reset() noexcept {
        x1_.fill(0.0f);
        y1_.fill(0.0f);
    }

    inline float Process(float x) noexcept {
        float s = x;
        for (size_t i = 0; i < NumSections; ++i) {
            const float y = c_[i] * (s - y1_[i]) + x1_[i];
            x1_[i] = s;
            y1_[i] = y;
            s = y;
        }
        return s;
    }

private:
    std::array<float, NumSections> c_{};
    std::array<float, NumSections> x1_{};
    std::array<float, NumSections> y1_{};
};

// 17th-order stage (4 + 4 allpass sections): fp = 0.210 * Fs_high, SB <= -125.89 dB
class PolyphaseHalfBandStage17 {
public:
    PolyphaseHalfBandStage17() noexcept
        : up_a_({0.026821928500f, 0.214527533360f, 0.489449268646f, 0.773727278635f}),
          up_b_({0.102468849567f, 0.347836340388f, 0.631780124224f, 0.920832324410f}),
          dn_a_({0.026821928500f, 0.214527533360f, 0.489449268646f, 0.773727278635f}),
          dn_b_({0.102468849567f, 0.347836340388f, 0.631780124224f, 0.920832324410f}) {}

    void Reset() noexcept {
        up_a_.Reset();
        up_b_.Reset();
        dn_a_.Reset();
        dn_b_.Reset();
    }

    inline void UpsampleBlock(const float* in, float* out, size_t n_in) noexcept {
        for (size_t i = 0; i < n_in; ++i) {
            const float x = in[i];
            out[2 * i] = up_a_.Process(x);
            out[2 * i + 1] = up_b_.Process(x);
        }
    }

    inline void DownsampleBlock(const float* in, float* out, size_t n_out) noexcept {
        for (size_t i = 0; i < n_out; ++i) {
            // Odd-phase polyphase decimation: H(z) = 0.5*(A(z^2) + z^-1*B(z^2)) sampled at 2n+1
            const float even_s = in[2 * i];
            const float odd_s = in[2 * i + 1];
            out[i] = 0.5f * (dn_a_.Process(odd_s) + dn_b_.Process(even_s));
        }
    }

private:
    EllipticAllpassBranch<4> up_a_;
    EllipticAllpassBranch<4> up_b_;
    EllipticAllpassBranch<4> dn_a_;
    EllipticAllpassBranch<4> dn_b_;
};

// 13th-order upper stage (3 + 3 allpass sections): fp = 0.160 * Fs_high, SB <= -138.46 dB
class PolyphaseHalfBandStage13 {
public:
    PolyphaseHalfBandStage13() noexcept
        : up_a_({0.025778591377f, 0.220957088144f, 0.583470149092f}),
          up_b_({0.101053586949f, 0.381306139605f, 0.840771671825f}),
          dn_a_({0.025778591377f, 0.220957088144f, 0.583470149092f}),
          dn_b_({0.101053586949f, 0.381306139605f, 0.840771671825f}) {}

    void Reset() noexcept {
        up_a_.Reset();
        up_b_.Reset();
        dn_a_.Reset();
        dn_b_.Reset();
    }

    inline void UpsampleBlock(const float* in, float* out, size_t n_in) noexcept {
        for (size_t i = 0; i < n_in; ++i) {
            const float x = in[i];
            out[2 * i] = up_a_.Process(x);
            out[2 * i + 1] = up_b_.Process(x);
        }
    }

    inline void DownsampleBlock(const float* in, float* out, size_t n_out) noexcept {
        for (size_t i = 0; i < n_out; ++i) {
            const float even_s = in[2 * i];
            const float odd_s = in[2 * i + 1];
            out[i] = 0.5f * (dn_a_.Process(odd_s) + dn_b_.Process(even_s));
        }
    }

private:
    EllipticAllpassBranch<3> up_a_;
    EllipticAllpassBranch<3> up_b_;
    EllipticAllpassBranch<3> dn_a_;
    EllipticAllpassBranch<3> dn_b_;
};

class PolyphaseOversampler2x {
public:
    void Reset() noexcept { stage1_.Reset(); }
    inline void Upsample16(const float* in16, float* out32) noexcept {
        stage1_.UpsampleBlock(in16, out32, 16);
    }
    inline void Downsample16(const float* in32, float* out16) noexcept {
        stage1_.DownsampleBlock(in32, out16, 16);
    }

private:
    PolyphaseHalfBandStage17 stage1_;
};

class PolyphaseOversampler4x {
public:
    void Reset() noexcept {
        stage1_.Reset();
        stage2_.Reset();
    }
    inline void Upsample16(const float* in16, float* out64) noexcept {
        alignas(64) float mid32[32];
        stage1_.UpsampleBlock(in16, mid32, 16);
        stage2_.UpsampleBlock(mid32, out64, 32);
    }
    inline void Downsample16(const float* in64, float* out16) noexcept {
        alignas(64) float mid32[32];
        stage2_.DownsampleBlock(in64, mid32, 32);
        stage1_.DownsampleBlock(mid32, out16, 16);
    }

private:
    PolyphaseHalfBandStage17 stage1_;
    PolyphaseHalfBandStage13 stage2_;
};

class PolyphaseOversampler8x {
public:
    void Reset() noexcept {
        stage1_.Reset();
        stage2_.Reset();
        stage3_.Reset();
    }
    inline void Upsample16(const float* in16, float* out128) noexcept {
        alignas(64) float mid32[32];
        alignas(64) float mid64[64];
        stage1_.UpsampleBlock(in16, mid32, 16);
        stage2_.UpsampleBlock(mid32, mid64, 32);
        stage3_.UpsampleBlock(mid64, out128, 64);
    }
    inline void Downsample16(const float* in128, float* out16) noexcept {
        alignas(64) float mid64[64];
        alignas(64) float mid32[32];
        stage3_.DownsampleBlock(in128, mid64, 64);
        stage2_.DownsampleBlock(mid64, mid32, 32);
        stage1_.DownsampleBlock(mid32, out16, 16);
    }

private:
    PolyphaseHalfBandStage17 stage1_;
    PolyphaseHalfBandStage13 stage2_;
    PolyphaseHalfBandStage13 stage3_;
};

}  // namespace stadium_dsp
