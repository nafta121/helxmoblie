#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// Zero-allocation Radix-2 Cooley-Tukey Complex FFT (supports power-of-two sizes up to 2048)
template <size_t N>
class StaticRadix2Fft {
    static_assert((N & (N - 1)) == 0 && N >= 16, "FFT size must be power of two >= 16");

public:
    StaticRadix2Fft() noexcept {
        // Precompute bit-reversal table and twiddle factors
        size_t log_n = 0;
        for (size_t t = N; t > 1; t >>= 1) ++log_n;
        for (size_t i = 0; i < N; ++i) {
            size_t r = 0;
            size_t x = i;
            for (size_t b = 0; b < log_n; ++b) {
                r = (r << 1) | (x & 1u);
                x >>= 1;
            }
            bitrev_[i] = static_cast<uint16_t>(r);
        }
        for (size_t k = 0; k < N / 2; ++k) {
            const double ang = -kTwoPiD * static_cast<double>(k) / static_cast<double>(N);
            tw_re_[k] = static_cast<float>(std::cos(ang));
            tw_im_[k] = static_cast<float>(std::sin(ang));
        }
    }

    void ForwardReal(const float* in_real, float* out_re, float* out_im) const noexcept {
        for (size_t i = 0; i < N; ++i) {
            const size_t r = bitrev_[i];
            out_re[r] = in_real[i];
            out_im[r] = 0.0f;
        }
        TransformCore(out_re, out_im, false);
    }

    void InverseComplexToReal(const float* in_re, const float* in_im, float* out_real) const noexcept {
        alignas(64) float tmp_re[N];
        alignas(64) float tmp_im[N];
        for (size_t i = 0; i < N; ++i) {
            const size_t r = bitrev_[i];
            tmp_re[r] = in_re[i];
            tmp_im[r] = in_im[i];
        }
        TransformCore(tmp_re, tmp_im, true);
        const float inv_n = 1.0f / static_cast<float>(N);
        for (size_t i = 0; i < N; ++i) {
            out_real[i] = tmp_re[i] * inv_n;
        }
    }

private:
    void TransformCore(float* re, float* im, bool inverse) const noexcept {
        const float sign = inverse ? -1.0f : 1.0f;
        for (size_t len = 2; len <= N; len <<= 1) {
            const size_t half = len >> 1;
            const size_t step = N / len;
            for (size_t i = 0; i < N; i += len) {
                size_t tw_idx = 0;
                for (size_t j = 0; j < half; ++j) {
                    const float wr = tw_re_[tw_idx];
                    const float wi = sign * tw_im_[tw_idx];
                    tw_idx += step;

                    const size_t u = i + j;
                    const size_t v = u + half;
                    const float tr = re[v] * wr - im[v] * wi;
                    const float ti = re[v] * wi + im[v] * wr;

                    const float ur = re[u];
                    const float ui = im[u];
                    re[u] = ur + tr;
                    im[u] = ui + ti;
                    re[v] = ur - tr;
                    im[v] = ui - ti;
                }
            }
        }
    }

    std::array<uint16_t, N> bitrev_{};
    alignas(64) std::array<float, N / 2> tw_re_{};
    alignas(64) std::array<float, N / 2> tw_im_{};
};

// Gardner Non-Uniform Partitioned Zero-Latency 2,048-Tap FP32 Convolver:
// - Head: 64-tap Direct NEON FIR (samples 0..63) -> 0-sample latency!
// - Mid : 3 x 64-tap Overlap-Save FFT-128 (samples 64..255)
// - Tail: 7 x 256-tap Overlap-Save FFT-512 (samples 256..2047)
// Plus 128-sample clickless spectral morphing (1x real-time load, 0 extra FFTs).
class ZeroLatencyIrConvolver2048 {
public:
    static constexpr size_t kIrLength = 2048;
    static constexpr size_t kHeadLen = 64;
    static constexpr size_t kMidBlock = 64;
    static constexpr size_t kMidFft = 128;
    static constexpr size_t kMidParts = 3;   // 64..255
    static constexpr size_t kTailBlock = 256;
    static constexpr size_t kTailFft = 512;
    static constexpr size_t kTailParts = 7;  // 256..2047

    ZeroLatencyIrConvolver2048() noexcept {
        Reset();
        std::array<float, kIrLength> delta{};
        delta[0] = 1.0f;
        SetImpulseResponseImmediate(delta.data(), delta.size());
    }

    void Reset() noexcept {
        x_hist_.fill(0.0f);
        x_write_ = 0;
        mid_pos_ = 0;
        tail_pos_ = 0;
        mid_fdl_slot_ = 0;
        tail_fdl_slot_ = 0;
        for (auto& s : mid_fdl_re_) s.fill(0.0f);
        for (auto& s : mid_fdl_im_) s.fill(0.0f);
        for (auto& s : tail_fdl_re_) s.fill(0.0f);
        for (auto& s : tail_fdl_im_) s.fill(0.0f);
        mid_out_buf_.fill(0.0f);
        tail_out_buf_.fill(0.0f);
        morph_counter_ = 0;
    }

    void SetImpulseResponseImmediate(const float* ir, size_t len) noexcept {
        BuildTargetPartitions(ir, len);
        head_rev_ = target_head_rev_;
        mid_h_re_ = target_mid_h_re_;
        mid_h_im_ = target_mid_h_im_;
        tail_h_re_ = target_tail_h_re_;
        tail_h_im_ = target_tail_h_im_;
        active_ir_time_ = target_ir_time_;
        morph_active_ = false;
    }

    void SetImpulseResponseMorph(const float* ir, size_t len) noexcept {
        BuildTargetPartitions(ir, len);
        morph_active_ = true;
        morph_counter_ = 0;
    }

    const std::array<float, kIrLength>& GetActiveIr() const noexcept { return active_ir_time_; }

    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        // Schedule Mid FFT (every 64 samples, using already-completed previous 128 samples)
        if (mid_pos_ == 0) {
            ComputeMidPartitionBlock();
        }
        // Schedule Tail FFT (every 256 samples, using already-completed previous 512 samples)
        if (tail_pos_ == 0) {
            ComputeTailPartitionBlock();
        }

        // Smooth head & spectra every 128 samples (or head gentle ramp) when morphing
        if (morph_active_) {
            StepSpectralMorph16();
        }

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            // Shift 512-sample contiguous history window (or ring) for exact aligned head dot product
            // Keep a contiguous linear history of 512 samples at tail of x_hist_?
            // Even cleaner: x_hist_ is a 1024-sample doubled-window or direct shift of 64 + ring of 512.
            PushSample(in16[i]);

            // Head 64-tap direct NEON FIR: sum_{k=0}^{63} h[k] * x[n - k]
            const float head_y = DotProductAligned64(head_window_.data(), head_rev_.data());
            const float mid_y = mid_out_buf_[mid_pos_ + i];
            const float tail_y = tail_out_buf_[tail_pos_ + i];
            out16[i] = head_y + mid_y + tail_y;
        }

        mid_pos_ = (mid_pos_ + kSubBlockSize) & (kMidBlock - 1);
        tail_pos_ = (tail_pos_ + kSubBlockSize) & (kTailBlock - 1);
    }

private:
    inline void PushSample(float s) noexcept {
        // Shift 64-sample aligned head window (fast L1 memmove of 252 bytes)
        std::memmove(head_window_.data(), head_window_.data() + 1, (kHeadLen - 1) * sizeof(float));
        head_window_[kHeadLen - 1] = s;

        x_hist_[x_write_] = s;
        x_write_ = (x_write_ + 1) & (kHistSize - 1);
    }

    void CopyLastSamples(float* dst, size_t count) const noexcept {
        // Copies chronological x[n_now - count .. n_now - 1]
        size_t idx = (x_write_ + kHistSize - count) & (kHistSize - 1);
        for (size_t i = 0; i < count; ++i) {
            dst[i] = x_hist_[idx];
            idx = (idx + 1) & (kHistSize - 1);
        }
    }

    void ComputeMidPartitionBlock() noexcept {
        alignas(64) float time_in[kMidFft];
        CopyLastSamples(time_in, kMidFft);
        fft128_.ForwardReal(time_in, mid_fdl_re_[mid_fdl_slot_].data(), mid_fdl_im_[mid_fdl_slot_].data());

        alignas(64) float acc_re[kMidFft] = {};
        alignas(64) float acc_im[kMidFft] = {};
        for (size_t p = 0; p < kMidParts; ++p) {
            const size_t slot = (mid_fdl_slot_ + kMidParts - p) % kMidParts;
            const float* xr = mid_fdl_re_[slot].data();
            const float* xi = mid_fdl_im_[slot].data();
            const float* hr = mid_h_re_[p].data();
            const float* hi = mid_h_im_[p].data();
            for (size_t k = 0; k < kMidFft; ++k) {
                acc_re[k] += xr[k] * hr[k] - xi[k] * hi[k];
                acc_im[k] += xr[k] * hi[k] + xi[k] * hr[k];
            }
        }
        alignas(64) float time_out[kMidFft];
        fft128_.InverseComplexToReal(acc_re, acc_im, time_out);
        std::memcpy(mid_out_buf_.data(), time_out + kMidBlock, kMidBlock * sizeof(float));
        mid_fdl_slot_ = (mid_fdl_slot_ + 1) % kMidParts;
    }

    void ComputeTailPartitionBlock() noexcept {
        alignas(64) float time_in[kTailFft];
        CopyLastSamples(time_in, kTailFft);
        fft512_.ForwardReal(time_in, tail_fdl_re_[tail_fdl_slot_].data(), tail_fdl_im_[tail_fdl_slot_].data());

        alignas(64) float acc_re[kTailFft] = {};
        alignas(64) float acc_im[kTailFft] = {};
        for (size_t p = 0; p < kTailParts; ++p) {
            const size_t slot = (tail_fdl_slot_ + kTailParts - p) % kTailParts;
            const float* xr = tail_fdl_re_[slot].data();
            const float* xi = tail_fdl_im_[slot].data();
            const float* hr = tail_h_re_[p].data();
            const float* hi = tail_h_im_[p].data();
            for (size_t k = 0; k < kTailFft; ++k) {
                acc_re[k] += xr[k] * hr[k] - xi[k] * hi[k];
                acc_im[k] += xr[k] * hi[k] + xi[k] * hr[k];
            }
        }
        alignas(64) float time_out[kTailFft];
        fft512_.InverseComplexToReal(acc_re, acc_im, time_out);
        std::memcpy(tail_out_buf_.data(), time_out + kTailBlock, kTailBlock * sizeof(float));
        tail_fdl_slot_ = (tail_fdl_slot_ + 1) % kTailParts;
    }

    void BuildTargetPartitions(const float* ir, size_t len) noexcept {
        target_ir_time_.fill(0.0f);
        const size_t n = std::min(len, kIrLength);
        if (ir != nullptr && n > 0) {
            std::memcpy(target_ir_time_.data(), ir, n * sizeof(float));
        }
        // Reversed head for dot product with chronological head_window_ [x[n-63]..x[n]]
        for (size_t i = 0; i < kHeadLen; ++i) {
            target_head_rev_[i] = target_ir_time_[kHeadLen - 1 - i];
        }
        // Mid partitions: 64..255
        alignas(64) float pad128[kMidFft];
        for (size_t p = 0; p < kMidParts; ++p) {
            std::memset(pad128, 0, sizeof(pad128));
            std::memcpy(pad128, target_ir_time_.data() + kHeadLen + p * kMidBlock, kMidBlock * sizeof(float));
            fft128_.ForwardReal(pad128, target_mid_h_re_[p].data(), target_mid_h_im_[p].data());
        }
        // Tail partitions: 256..2047
        alignas(64) float pad512[kTailFft];
        for (size_t p = 0; p < kTailParts; ++p) {
            std::memset(pad512, 0, sizeof(pad512));
            std::memcpy(pad512, target_ir_time_.data() + 256 + p * kTailBlock, kTailBlock * sizeof(float));
            fft512_.ForwardReal(pad512, target_tail_h_re_[p].data(), target_tail_h_im_[p].data());
        }
    }

    void StepSpectralMorph16() noexcept {
        // Gentle head interpolation every 16 samples + mid/tail spectral morph every 128 samples
        constexpr float kHeadAlpha = 0.08f;
        float max_diff = 0.0f;
        for (size_t i = 0; i < kHeadLen; ++i) {
            const float d = target_head_rev_[i] - head_rev_[i];
            head_rev_[i] += kHeadAlpha * d;
            max_diff = std::max(max_diff, std::fabs(d));
        }

        morph_counter_ += kSubBlockSize;
        if (morph_counter_ >= 128) {
            morph_counter_ = 0;
            constexpr float kSpecAlpha = 0.25f;
            for (size_t p = 0; p < kMidParts; ++p) {
                for (size_t k = 0; k < kMidFft; ++k) {
                    const float dr = target_mid_h_re_[p][k] - mid_h_re_[p][k];
                    const float di = target_mid_h_im_[p][k] - mid_h_im_[p][k];
                    mid_h_re_[p][k] += kSpecAlpha * dr;
                    mid_h_im_[p][k] += kSpecAlpha * di;
                    max_diff = std::max(max_diff, std::max(std::fabs(dr), std::fabs(di)));
                }
            }
            for (size_t p = 0; p < kTailParts; ++p) {
                for (size_t k = 0; k < kTailFft; ++k) {
                    const float dr = target_tail_h_re_[p][k] - tail_h_re_[p][k];
                    const float di = target_tail_h_im_[p][k] - tail_h_im_[p][k];
                    tail_h_re_[p][k] += kSpecAlpha * dr;
                    tail_h_im_[p][k] += kSpecAlpha * di;
                    max_diff = std::max(max_diff, std::max(std::fabs(dr), std::fabs(di)));
                }
            }
            if (max_diff < 1.0e-5f) {
                head_rev_ = target_head_rev_;
                mid_h_re_ = target_mid_h_re_;
                mid_h_im_ = target_mid_h_im_;
                tail_h_re_ = target_tail_h_re_;
                tail_h_im_ = target_tail_h_im_;
                active_ir_time_ = target_ir_time_;
                morph_active_ = false;
            }
        }
    }

    static constexpr size_t kHistSize = 1024;
    StaticRadix2Fft<kMidFft> fft128_;
    StaticRadix2Fft<kTailFft> fft512_;

    alignas(64) std::array<float, kHeadLen> head_window_{};
    alignas(64) std::array<float, kHeadLen> head_rev_{};
    alignas(64) std::array<float, kHeadLen> target_head_rev_{};

    alignas(64) std::array<float, kHistSize> x_hist_{};
    size_t x_write_ = 0;
    size_t mid_pos_ = 0;
    size_t tail_pos_ = 0;
    size_t mid_fdl_slot_ = 0;
    size_t tail_fdl_slot_ = 0;

    std::array<std::array<float, kMidFft>, kMidParts> mid_fdl_re_{};
    std::array<std::array<float, kMidFft>, kMidParts> mid_fdl_im_{};
    std::array<std::array<float, kMidFft>, kMidParts> mid_h_re_{};
    std::array<std::array<float, kMidFft>, kMidParts> mid_h_im_{};
    std::array<std::array<float, kMidFft>, kMidParts> target_mid_h_re_{};
    std::array<std::array<float, kMidFft>, kMidParts> target_mid_h_im_{};
    alignas(64) std::array<float, kMidBlock> mid_out_buf_{};

    std::array<std::array<float, kTailFft>, kTailParts> tail_fdl_re_{};
    std::array<std::array<float, kTailFft>, kTailParts> tail_fdl_im_{};
    std::array<std::array<float, kTailFft>, kTailParts> tail_h_re_{};
    std::array<std::array<float, kTailFft>, kTailParts> tail_h_im_{};
    std::array<std::array<float, kTailFft>, kTailParts> target_tail_h_re_{};
    std::array<std::array<float, kTailFft>, kTailParts> target_tail_h_im_{};
    alignas(64) std::array<float, kTailBlock> tail_out_buf_{};

    std::array<float, kIrLength> active_ir_time_{};
    std::array<float, kIrLength> target_ir_time_{};
    bool morph_active_ = false;
    size_t morph_counter_ = 0;
};

enum class StudioMicModel : int32_t {
    k57Dynamic = 0,
    k421Dynamic,
    k7Dynamic,
    k906Dynamic,
    k121Ribbon,
    k160Ribbon,
    k4038Ribbon,
    k84Condenser,
    k414Condenser,
    k67Condenser,
    k47CondenserFet,
    k112KickBassDynamic,
    kCount = 12,
};

enum class FactoryCabModel : int32_t {
    k4x12V30 = 0,
    k4x12Greenback,
    k4x12CaliOversized,
    k2x12BlueBell,
    k2x12TwinJensen,
    k1x12Deluxe,
    k1x12Tweed,
    k1x8Tweed,
    k8x10Svt,
    k4x10Ampeg,
    k1x15B15Portaflex,
    k2x15Bassman,
    kCount = 12,
};

struct CabSlotParams {
    bool enabled = true;
    bool use_external_ir = false;
    FactoryCabModel factory_cab = FactoryCabModel::k4x12V30;
    StudioMicModel mic_model = StudioMicModel::k57Dynamic;
    float position = 0.25f;       // 0.0 (Center Cap) .. 1.0 (Cone Rim)
    float distance_inch = 1.5f;   // 1.0" .. 12.0"
    float angle_deg = 0.0f;       // 0.0° or 45.0°
    float low_cut_hz = 65.0f;
    float high_cut_hz = 9500.0f;
    float level_db = 0.0f;
    float pan = 0.0f;             // -1.0 (L) .. +1.0 (R)
    bool auto_delay = true;
    float manual_delay_ms = 0.0f;
    bool phase_invert = false;
};

struct FactoryCabSpec {
    const char* name;
    float cone_fs_hz;
    float port_hz;
    float mid_honk_hz;
    float mid_honk_db;
    float cone_rolloff_hz;
    float cap_edge_pos;
    bool is_bass;
    ReactiveCabImpedanceDesc impedance;
};

inline const FactoryCabSpec& GetFactoryCabSpec(FactoryCabModel model) noexcept {
    static const std::array<FactoryCabSpec, 12> kSpecs = {{
        {"4x12 Brit V30", 96.0f, 195.0f, 2400.0f, 4.2f, 5200.0f, 0.30f, false,
         {96.0f, 3.2f, 6.8f, 195.0f, 2.0f, 1.8f, 1700.0f, 5.4f, 0.30f}},
        {"4x12 Greenback 25", 88.0f, 185.0f, 1750.0f, 3.2f, 4600.0f, 0.28f, false,
         {88.0f, 3.0f, 6.2f, 185.0f, 1.9f, 1.6f, 1550.0f, 5.0f, 0.35f}},
        {"4x12 Cali Oversized", 82.0f, 168.0f, 2550.0f, 4.5f, 5500.0f, 0.31f, false,
         {82.0f, 3.5f, 7.6f, 168.0f, 2.2f, 2.1f, 1800.0f, 5.8f, 0.28f}},
        {"2x12 Essex Blue Bell", 92.0f, 230.0f, 2850.0f, 3.8f, 5400.0f, 0.27f, false,
         {92.0f, 2.8f, 5.5f, 230.0f, 1.8f, 1.4f, 1950.0f, 4.6f, 0.90f}},
        {"2x12 US Twin Jensen", 94.0f, 220.0f, 2200.0f, 2.4f, 5800.0f, 0.26f, false,
         {94.0f, 2.7f, 5.8f, 220.0f, 1.7f, 1.2f, 2100.0f, 4.4f, 0.22f}},
        {"1x12 US Deluxe", 104.0f, 245.0f, 2100.0f, 2.6f, 5000.0f, 0.26f, false,
         {104.0f, 2.9f, 6.0f, 245.0f, 1.8f, 1.3f, 1850.0f, 4.5f, 0.25f}},
        {"1x12 Tweed Alnico", 110.0f, 260.0f, 1600.0f, 3.0f, 4400.0f, 0.25f, false,
         {110.0f, 3.1f, 5.9f, 260.0f, 1.7f, 1.2f, 1600.0f, 4.2f, 0.65f}},
        {"1x8 Tweed Champ", 124.0f, 310.0f, 1850.0f, 3.6f, 4200.0f, 0.22f, false,
         {124.0f, 3.4f, 5.4f, 310.0f, 1.9f, 1.5f, 1500.0f, 4.0f, 0.80f}},
        {"8x10 SV Beast Fridge", 66.0f, 132.0f, 920.0f, 3.0f, 3900.0f, 0.34f, true,
         {66.0f, 3.3f, 7.2f, 132.0f, 2.1f, 2.2f, 1250.0f, 4.8f, 0.35f}},
        {"4x10 Ampeg Pro", 62.0f, 125.0f, 1150.0f, 2.4f, 4800.0f, 0.33f, true,
         {62.0f, 3.0f, 6.8f, 125.0f, 2.0f, 2.0f, 1350.0f, 4.6f, 0.35f}},
        {"1x15 B-15 Portaflex", 54.0f, 108.0f, 720.0f, 2.2f, 3200.0f, 0.38f, true,
         {54.0f, 3.6f, 7.5f, 108.0f, 2.2f, 2.4f, 1050.0f, 4.2f, 0.45f}},
        {"2x15 Bassman Dual", 58.0f, 116.0f, 840.0f, 2.5f, 3600.0f, 0.36f, true,
         {58.0f, 3.1f, 6.9f, 116.0f, 2.0f, 1.9f, 1180.0f, 4.4f, 0.32f}},
    }};
    const int idx = std::max(0, std::min(static_cast<int>(model), 11));
    return kSpecs[static_cast<size_t>(idx)];
}

// Synthesizes a clean-room physically calibrated 2,048-tap Cabinet + Mic IR
inline void SynthesizeFactoryCabMicIr(const CabSlotParams& params,
                                      std::array<float, 2048>& out_ir) noexcept {
    const FactoryCabSpec& cab = GetFactoryCabSpec(params.factory_cab);

    // Build minimum-phase acoustic cascade driven by unit impulse
    BiquadDF2T hp_enclosure, cone_res, port_res, mid_honk, cone_lp, mic_pres, mic_prox, angle_lp;
    hp_enclosure.SetCoeffs(DesignHighPass(cab.cone_fs_hz * 0.72, 0.85, kSampleRateD));
    cone_res.SetCoeffs(DesignAntiCrampedPeak(cab.cone_fs_hz, 2.4, 4.8, kSampleRateD));
    port_res.SetCoeffs(DesignAntiCrampedPeak(cab.port_hz, 1.6, 1.8, kSampleRateD));
    mid_honk.SetCoeffs(DesignAntiCrampedPeak(cab.mid_honk_hz, 1.8, cab.mid_honk_db, kSampleRateD));

    // Position shifts high-frequency cone breakup & brightness (Center Cap brightest -> Rim warmest)
    const double pos = Clamp(params.position, 0.0f, 1.0f);
    const double eff_rolloff = cab.cone_rolloff_hz * (1.25 - 0.58 * pos);
    cone_lp.SetCoeffs(DesignLowPass(eff_rolloff, 0.78, kSampleRateD));

    // Studio microphone voice shaping (12 distinct acoustic signatures)
    double mic_p_hz = 4200.0, mic_p_db = 3.5, mic_low_db = 0.0, mic_hi_hz = 11500.0;
    switch (params.mic_model) {
        case StudioMicModel::k57Dynamic:          mic_p_hz = 4800.0; mic_p_db = 4.2; mic_low_db = -1.2; mic_hi_hz = 11000.0; break;
        case StudioMicModel::k421Dynamic:         mic_p_hz = 4200.0; mic_p_db = 3.8; mic_low_db = 1.4;  mic_hi_hz = 12000.0; break;
        case StudioMicModel::k7Dynamic:           mic_p_hz = 3200.0; mic_p_db = 2.0; mic_low_db = 1.8;  mic_hi_hz = 9800.0;  break;
        case StudioMicModel::k906Dynamic:         mic_p_hz = 4500.0; mic_p_db = 3.4; mic_low_db = 0.5;  mic_hi_hz = 11500.0; break;
        case StudioMicModel::k121Ribbon:          mic_p_hz = 1400.0; mic_p_db = 1.5; mic_low_db = 3.6;  mic_hi_hz = 6200.0;  break;
        case StudioMicModel::k160Ribbon:          mic_p_hz = 1900.0; mic_p_db = 1.8; mic_low_db = 2.0;  mic_hi_hz = 7400.0;  break;
        case StudioMicModel::k4038Ribbon:         mic_p_hz = 1100.0; mic_p_db = 1.2; mic_low_db = 4.2;  mic_hi_hz = 5400.0;  break;
        case StudioMicModel::k84Condenser:        mic_p_hz = 6500.0; mic_p_db = 2.6; mic_low_db = 0.4;  mic_hi_hz = 15000.0; break;
        case StudioMicModel::k414Condenser:       mic_p_hz = 7200.0; mic_p_db = 3.2; mic_low_db = 1.2;  mic_hi_hz = 15500.0; break;
        case StudioMicModel::k67Condenser:        mic_p_hz = 3600.0; mic_p_db = 2.2; mic_low_db = 2.4;  mic_hi_hz = 12500.0; break;
        case StudioMicModel::k47CondenserFet:     mic_p_hz = 2800.0; mic_p_db = 2.5; mic_low_db = 3.2;  mic_hi_hz = 11000.0; break;
        case StudioMicModel::k112KickBassDynamic: mic_p_hz = 3800.0; mic_p_db = 3.0; mic_low_db = 5.0;  mic_hi_hz = 8500.0;  break;
        default: break;
    }

    // Proximity bass boost inversely proportional to mic distance (1"..12")
    const double dist = Clamp(params.distance_inch, 1.0f, 12.0f);
    const double prox_boost_db = mic_low_db + 4.5 / std::pow(dist, 0.72);
    mic_prox.SetCoeffs(DesignLowShelf(165.0, 0.707, prox_boost_db, kSampleRateD));
    mic_pres.SetCoeffs(DesignAntiCrampedPeak(mic_p_hz, 1.6, mic_p_db, kSampleRateD));

    // 45° off-axis angle gently softens top-end harshness
    const double angle_factor = (params.angle_deg >= 22.5f) ? 0.78 : 1.0;
    angle_lp.SetCoeffs(DesignLowPass(mic_hi_hz * angle_factor, 0.707, kSampleRateD));

    // Subtle cabinet box reflections (early enclosure resonance @ 1.1..6.5 ms)
    double energy = 0.0;
    for (size_t n = 0; n < out_ir.size(); ++n) {
        float x = (n == 0) ? 1.0f : 0.0f;
        if (n == 54) x += 0.14f;
        if (n == 112) x -= 0.09f;
        if (n == 198) x += 0.06f;
        if (n == 310) x -= 0.04f;
        float y = hp_enclosure.Process(x);
        y = cone_res.Process(y);
        y = port_res.Process(y);
        y = mid_honk.Process(y);
        y = cone_lp.Process(y);
        y = mic_prox.Process(y);
        y = mic_pres.Process(y);
        y = angle_lp.Process(y);
        // Smooth tail window at end of 2,048 taps
        if (n > 1792) {
            const float w = 0.5f * (1.0f + std::cos(kPi * static_cast<float>(n - 1792) / 256.0f));
            y *= w;
        }
        out_ir[n] = y;
        energy += static_cast<double>(y) * y;
    }
    const float norm = (energy > 1e-12) ? static_cast<float>(0.65 / std::sqrt(energy)) : 1.0f;
    for (float& s : out_ir) s *= norm;
}

class DualCabEngine {
public:
    DualCabEngine() noexcept { InitializeFactoryCabs(); }

    void InitializeFactoryCabs() noexcept {
        slots_[0] = CabSlotParams{};
        slots_[0].enabled = true;
        slots_[0].factory_cab = FactoryCabModel::k4x12V30;
        slots_[0].mic_model = StudioMicModel::k57Dynamic;
        slots_[0].pan = -0.15f;

        slots_[1] = CabSlotParams{};
        slots_[1].enabled = false;
        slots_[1].factory_cab = FactoryCabModel::k4x12Greenback;
        slots_[1].mic_model = StudioMicModel::k121Ribbon;
        slots_[1].pan = 0.15f;

        for (int s = 0; s < 2; ++s) {
            delay_lines_[s].fill(0.0f);
            delay_write_[s] = 0;
            RebuildSlot(s, true);
        }
    }

    void Reset() noexcept {
        for (int s = 0; s < 2; ++s) {
            convolvers_[s].Reset();
            low_cut_[s].Reset();
            high_cut_[s].Reset();
            delay_lines_[s].fill(0.0f);
            delay_write_[s] = 0;
        }
    }

    const CabSlotParams& GetSlotParams(int slot) const noexcept {
        return slots_[slot & 1];
    }

    void SetSlotParams(int slot, const CabSlotParams& p, bool immediate = false) noexcept {
        const int idx = slot & 1;
        slots_[idx] = p;
        RebuildSlot(idx, immediate);
    }

    void SetExternalIr(int slot, const float* ir2048, size_t len,
                       const ReactiveCabImpedanceDesc& z_desc, bool immediate = false) noexcept {
        const int idx = slot & 1;
        slots_[idx].use_external_ir = true;
        external_z_[idx] = z_desc;
        if (immediate) {
            convolvers_[idx].SetImpulseResponseImmediate(ir2048, len);
        } else {
            convolvers_[idx].SetImpulseResponseMorph(ir2048, len);
        }
    }

    ReactiveCabImpedanceDesc GetFactoryCabImpedance(FactoryCabModel model) const noexcept {
        return GetFactoryCabSpec(model).impedance;
    }

    ReactiveCabImpedanceDesc GetActiveImpedance() const noexcept {
        if (slots_[0].use_external_ir) return external_z_[0];
        return GetFactoryCabSpec(slots_[0].factory_cab).impedance;
    }

    // Computes 128 log-spaced magnitude spectrum points (20 Hz .. 20 kHz) in dB for UI Cab/IR Focus View
    void ComputeSlotMagnitudeSpectrumDb(int slot, float* out_db, size_t num_bins) const noexcept {
        const auto& ir = convolvers_[slot & 1].GetActiveIr();
        constexpr double f_min = 20.0;
        constexpr double f_max = 20000.0;
        const double ratio = f_max / f_min;
        // Evaluate DFT directly over first 512 significant taps (fast UI/init utility, off audio thread)
        constexpr size_t kEvalTaps = 512;
        for (size_t b = 0; b < num_bins; ++b) {
            const double t = (num_bins > 1) ? static_cast<double>(b) / static_cast<double>(num_bins - 1) : 0.0;
            const double freq = f_min * std::pow(ratio, t);
            const double w = kTwoPiD * freq / kSampleRateD;
            double re = 0.0, im = 0.0;
            for (size_t n = 0; n < kEvalTaps; ++n) {
                const double ang = w * static_cast<double>(n);
                re += static_cast<double>(ir[n]) * std::cos(ang);
                im -= static_cast<double>(ir[n]) * std::sin(ang);
            }
            const double mag = std::sqrt(re * re + im * im);
            out_db[b] = static_cast<float>(20.0 * std::log10(std::max(mag, 1.0e-5)));
        }
    }

    void ProcessSubBlock16(const float* in_mono16, float* out_l16, float* out_r16) noexcept {
        alignas(64) float in_copy[kSubBlockSize];
        std::memcpy(in_copy, in_mono16, sizeof(in_copy));

        std::memset(out_l16, 0, kSubBlockSize * sizeof(float));
        std::memset(out_r16, 0, kSubBlockSize * sizeof(float));

        const bool any_enabled = slots_[0].enabled || slots_[1].enabled;
        if (!any_enabled) {
            std::memcpy(out_l16, in_copy, sizeof(in_copy));
            std::memcpy(out_r16, in_copy, sizeof(in_copy));
            return;
        }

        const float slot_scale = (slots_[0].enabled && slots_[1].enabled) ? 0.70710678f : 1.0f;

        for (int s = 0; s < 2; ++s) {
            if (!slots_[s].enabled) continue;
            alignas(64) float conv16[kSubBlockSize];
            convolvers_[s].ProcessSubBlock16(in_copy, conv16);

            const float gain = DbToLin(slots_[s].level_db) * slot_scale * (slots_[s].phase_invert ? -1.0f : 1.0f);
            const float pan = Clamp(slots_[s].pan, -1.0f, 1.0f);
            const float pan_ang = (pan + 1.0f) * (kPi * 0.25f);
            const float gl = std::cos(pan_ang) * gain;
            const float gr = std::sin(pan_ang) * gain;

            // Physical acoustic air propagation delay: (distance_inch - 1.0") * 3.555 samples @ 48 kHz
            const float delay_samples = slots_[s].auto_delay
                ? std::max(0.0f, (Clamp(slots_[s].distance_inch, 1.0f, 12.0f) - 1.0f) * 3.555f)
                : Clamp(slots_[s].manual_delay_ms, 0.0f, 20.0f) * (kSampleRate * 0.001f);

            for (size_t i = 0; i < kSubBlockSize; ++i) {
                float y = low_cut_[s].Process(conv16[i]);
                y = high_cut_[s].Process(y);
                y = ReadWriteDelay(s, y, delay_samples);
                out_l16[i] += y * gl;
                out_r16[i] += y * gr;
            }
        }
    }

private:
    void RebuildSlot(int idx, bool immediate) noexcept {
        low_cut_[idx].SetCoeffs(DesignHighPass(Clamp(slots_[idx].low_cut_hz, 20.0f, 500.0f), 0.707, kSampleRateD));
        high_cut_[idx].SetCoeffs(DesignLowPass(Clamp(slots_[idx].high_cut_hz, 1500.0f, 20000.0f), 0.707, kSampleRateD));
        if (!slots_[idx].use_external_ir) {
            std::array<float, 2048> ir{};
            SynthesizeFactoryCabMicIr(slots_[idx], ir);
            if (immediate) {
                convolvers_[idx].SetImpulseResponseImmediate(ir.data(), ir.size());
            } else {
                convolvers_[idx].SetImpulseResponseMorph(ir.data(), ir.size());
            }
        }
    }

    inline float ReadWriteDelay(int s, float x, float delay_samples) noexcept {
        auto& buf = delay_lines_[s];
        const size_t w = delay_write_[s];
        buf[w] = x;
        const float d = Clamp(delay_samples, 0.0f, static_cast<float>(kDelayCap - 2));
        const size_t d_int = static_cast<size_t>(d);
        const float frac = d - static_cast<float>(d_int);
        const size_t idx0 = (w + kDelayCap - d_int) & (kDelayCap - 1);
        const size_t idx1 = (idx0 + kDelayCap - 1) & (kDelayCap - 1);
        delay_write_[s] = (w + 1) & (kDelayCap - 1);
        return buf[idx0] * (1.0f - frac) + buf[idx1] * frac;
    }

    static constexpr size_t kDelayCap = 2048;
    std::array<CabSlotParams, 2> slots_{};
    std::array<ZeroLatencyIrConvolver2048, 2> convolvers_{};
    std::array<BiquadDF2T, 2> low_cut_{};
    std::array<BiquadDF2T, 2> high_cut_{};
    std::array<std::array<float, kDelayCap>, 2> delay_lines_{};
    std::array<size_t, 2> delay_write_{0, 0};
    std::array<ReactiveCabImpedanceDesc, 2> external_z_{};
};

}  // namespace stadium_dsp
