#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#elif defined(__SSE__) || defined(__x86_64__) || defined(_M_X64)
#include <xmmintrin.h>
#if defined(__SSE3__) || defined(__x86_64__)
#include <pmmintrin.h>
#endif
#endif

namespace stadium_dsp {

constexpr float kSampleRate = 48000.0f;
constexpr double kSampleRateD = 48000.0;
constexpr size_t kSubBlockSize = 16;
constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;
constexpr double kPiD = 3.14159265358979323846;
constexpr double kTwoPiD = 6.28318530717958647692;

inline void EnableFlushToZeroDenormals() noexcept {
#if defined(__aarch64__)
    uint64_t fpcr = 0;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);  // FPCR.FZ (Flush-to-Zero)
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__SSE__) || defined(__x86_64__) || defined(_M_X64)
    _MM_SET_FLUSH_ZERO_MODE(_MM_FLUSH_ZERO_ON);
#if defined(__SSE3__) || defined(__x86_64__)
    _MM_SET_DENORMALS_ZERO_MODE(_MM_DENORMALS_ZERO_ON);
#endif
#endif
}

inline float FlushDenormal(float x) noexcept {
    return (std::fabs(x) < 1.0e-20f) ? 0.0f : x;
}

inline float DbToLin(float db) noexcept {
    return std::pow(10.0f, db * 0.05f);
}

inline float LinToDb(float lin) noexcept {
    return 20.0f * std::log10(std::max(lin, 1.0e-7f));
}

inline float Clamp(float v, float lo, float hi) noexcept {
    return std::max(lo, std::min(hi, v));
}

struct alignas(16) Vec4f {
#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t v;

    static Vec4f Splat(float x) noexcept { return Vec4f{vdupq_n_f32(x)}; }
    static Vec4f Load(const float* p) noexcept { return Vec4f{vld1q_f32(p)}; }
    void Store(float* p) const noexcept { vst1q_f32(p, v); }

    Vec4f operator+(Vec4f o) const noexcept { return Vec4f{vaddq_f32(v, o.v)}; }
    Vec4f operator-(Vec4f o) const noexcept { return Vec4f{vsubq_f32(v, o.v)}; }
    Vec4f operator*(Vec4f o) const noexcept { return Vec4f{vmulq_f32(v, o.v)}; }

    static Vec4f Fma(Vec4f acc, Vec4f a, Vec4f b) noexcept {
        return Vec4f{vfmaq_f32(acc.v, a.v, b.v)};
    }
    static Vec4f Abs(Vec4f a) noexcept { return Vec4f{vabsq_f32(a.v)}; }
    float HorizontalSum() const noexcept {
#if defined(__aarch64__)
        return vaddvq_f32(v);
#else
        float32x2_t r = vadd_f32(vget_low_f32(v), vget_high_f32(v));
        return vget_lane_f32(vpadd_f32(r, r), 0);
#endif
    }
#else
    float d[4];

    static Vec4f Splat(float x) noexcept { return Vec4f{{x, x, x, x}}; }
    static Vec4f Load(const float* p) noexcept { return Vec4f{{p[0], p[1], p[2], p[3]}}; }
    void Store(float* p) const noexcept {
        p[0] = d[0];
        p[1] = d[1];
        p[2] = d[2];
        p[3] = d[3];
    }

    Vec4f operator+(Vec4f o) const noexcept {
        return Vec4f{{d[0] + o.d[0], d[1] + o.d[1], d[2] + o.d[2], d[3] + o.d[3]}};
    }
    Vec4f operator-(Vec4f o) const noexcept {
        return Vec4f{{d[0] - o.d[0], d[1] - o.d[1], d[2] - o.d[2], d[3] - o.d[3]}};
    }
    Vec4f operator*(Vec4f o) const noexcept {
        return Vec4f{{d[0] * o.d[0], d[1] * o.d[1], d[2] * o.d[2], d[3] * o.d[3]}};
    }

    static Vec4f Fma(Vec4f acc, Vec4f a, Vec4f b) noexcept {
        return Vec4f{{acc.d[0] + a.d[0] * b.d[0], acc.d[1] + a.d[1] * b.d[1],
                      acc.d[2] + a.d[2] * b.d[2], acc.d[3] + a.d[3] * b.d[3]}};
    }
    static Vec4f Abs(Vec4f a) noexcept {
        return Vec4f{{std::fabs(a.d[0]), std::fabs(a.d[1]), std::fabs(a.d[2]), std::fabs(a.d[3])}};
    }
    float HorizontalSum() const noexcept { return d[0] + d[1] + d[2] + d[3]; }
#endif
};

inline float DotProductAligned16(const float* a, const float* b) noexcept {
    Vec4f acc = Vec4f::Splat(0.0f);
    acc = Vec4f::Fma(acc, Vec4f::Load(a + 0), Vec4f::Load(b + 0));
    acc = Vec4f::Fma(acc, Vec4f::Load(a + 4), Vec4f::Load(b + 4));
    acc = Vec4f::Fma(acc, Vec4f::Load(a + 8), Vec4f::Load(b + 8));
    acc = Vec4f::Fma(acc, Vec4f::Load(a + 12), Vec4f::Load(b + 12));
    return acc.HorizontalSum();
}

inline float DotProductAligned64(const float* a, const float* b) noexcept {
    Vec4f acc0 = Vec4f::Splat(0.0f);
    Vec4f acc1 = Vec4f::Splat(0.0f);
    for (size_t i = 0; i < 64; i += 8) {
        acc0 = Vec4f::Fma(acc0, Vec4f::Load(a + i), Vec4f::Load(b + i));
        acc1 = Vec4f::Fma(acc1, Vec4f::Load(a + i + 4), Vec4f::Load(b + i + 4));
    }
    return (acc0 + acc1).HorizontalSum();
}

}  // namespace stadium_dsp
