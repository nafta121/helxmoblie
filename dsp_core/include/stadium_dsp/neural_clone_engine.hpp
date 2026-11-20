#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

inline float ComputeDbuCalibrationLinearGain(float interface_max_dbu, float model_ref_dbu) noexcept {
    // Unity gain calibration: difference between user's USB audio interface 0 dBFS headroom
    // and the capture reference input_level_dbu (e.g., +12.2 dBu).
    const float delta_db = interface_max_dbu - model_ref_dbu;
    return DbToLin(delta_db);
}

enum class NeuralArchitecture : uint8_t {
    kNone = 0,
    kWaveNet = 1,
    kGru = 2,
};

struct CloneMetadata {
    std::string brand = "CleanRoom Lab";
    std::string product = "Built-In Reference Clone";
    std::string notes = "48 kHz Batch-16 NEON";
    std::string uuid = "cleanroom-default";
    std::string architecture = "WaveNet";
    float input_level_dbu = 12.0f;
    float output_level_dbu = 12.0f;
    float sample_rate = 48000.0f;
};

struct NeuralCloneParams {
    bool enabled = false;
    float input_gain_db = 0.0f;
    float output_gain_db = 0.0f;
    float interface_max_dbu = 12.0f;
    float low_cut_hz = 20.0f;
    float high_cut_hz = 20000.0f;
};

// Multi-Stage Dilated WaveNet Layer (compatible with NAM v0.5.x Tone3000)
struct WaveNetLayer {
    int channels = 0;
    int condition_size = 1;
    int kernel_size = 2;
    int dilation = 1;
    bool gated = false;
    // conv_w: [out_ch x channels x kernel_size] (out_ch = gated ? 2*channels : channels)
    std::vector<float> conv_w;
    std::vector<float> conv_b;
    // input_mixin: [out_ch x condition_size]
    std::vector<float> mixin_w;
    // 1x1 output projection: [channels x channels] + bias [channels]
    std::vector<float> one_x_one_w;
    std::vector<float> one_x_one_b;

    // Pre-allocated ring buffer: [ring_len x channels]
    int ring_mask = 0;
    int ring_pos = 0;
    std::vector<float> ring;
};

struct WaveNetStage {
    int input_size = 1;
    int condition_size = 1;
    int channels = 0;
    int head_size = 0;
    bool head_bias = false;
    std::vector<float> input_w;       // [channels x input_size]
    std::vector<WaveNetLayer> layers;
    std::vector<float> head_rechannel_w;  // [head_size x channels]
    std::vector<float> head_rechannel_b;  // [head_size] if head_bias
};

// GRU Network (compatible with AIDA-X / PyTorch / Keras InputLayer)
struct GruModel {
    int hidden_size = 0;
    bool input_skip = true;
    // Weight layout [3 * H] in PyTorch/AIDA-X order: reset(r), update(z), new(n)
    std::vector<float> w_ih;   // [3 * H]
    std::vector<float> b_ih;   // [3 * H]
    std::vector<float> w_hh;   // [3 * H x H]
    std::vector<float> b_hh;   // [3 * H]
    std::vector<float> lin_w;  // [H]
    float lin_b = 0.0f;
    std::vector<float> h;      // [H]
};

class NeuralCloneEngine {
public:
    NeuralCloneEngine() noexcept {
        UpdateFilters();
        LoadBuiltInDemoWaveNet();
    }

    void SetParams(const NeuralCloneParams& p) noexcept {
        params_ = p;
        UpdateFilters();
    }

    const NeuralCloneParams& GetParams() const noexcept { return params_; }
    const CloneMetadata& GetMetadata() const noexcept { return metadata_; }
    NeuralArchitecture GetArchitecture() const noexcept { return arch_; }

    void ResetState() noexcept {
        for (auto& st : wn_stages_) {
            for (auto& l : st.layers) {
                std::fill(l.ring.begin(), l.ring.end(), 0.0f);
                l.ring_pos = 0;
            }
        }
        std::fill(gru_.h.begin(), gru_.h.end(), 0.0f);
        low_cut_.Reset();
        high_cut_.Reset();
    }

    void SetWaveNetModel(std::vector<WaveNetStage> stages, float head_scale,
                         const CloneMetadata& meta) {
        wn_stages_ = std::move(stages);
        wn_head_scale_ = head_scale;
        metadata_ = meta;
        arch_ = NeuralArchitecture::kWaveNet;
        PrepareWaveNetBuffers();
        ResetState();
    }

    void SetGruModel(GruModel model, const CloneMetadata& meta) {
        gru_ = std::move(model);
        gru_.h.assign(static_cast<size_t>(gru_.hidden_size), 0.0f);
        metadata_ = meta;
        arch_ = NeuralArchitecture::kGru;
        ResetState();
    }

    void LoadBuiltInDemoWaveNet() {
        WaveNetStage st;
        st.input_size = 1;
        st.condition_size = 1;
        st.channels = 4;
        st.head_size = 1;
        st.head_bias = false;
        st.input_w = {0.65f, -0.50f, 0.40f, -0.35f};

        for (int d : {1, 2, 4}) {
            WaveNetLayer l;
            l.channels = 4;
            l.condition_size = 1;
            l.kernel_size = 2;
            l.dilation = d;
            l.gated = false;
            l.conv_w.resize(4 * 4 * 2);
            l.conv_b.assign(4, 0.01f * static_cast<float>(d));
            l.mixin_w = {0.15f, -0.12f, 0.18f, -0.10f};
            l.one_x_one_w.resize(4 * 4, 0.0f);
            l.one_x_one_b.assign(4, 0.0f);
            for (int oc = 0; oc < 4; ++oc) {
                l.one_x_one_w[oc * 4 + oc] = 0.45f;
                for (int ic = 0; ic < 4; ++ic) {
                    l.conv_w[(oc * 4 + ic) * 2 + 0] = (oc == ic) ? 0.35f : 0.06f * ((oc + ic) % 2 ? 1.0f : -1.0f);
                    l.conv_w[(oc * 4 + ic) * 2 + 1] = (oc == ic) ? -0.18f : 0.04f;
                }
            }
            st.layers.push_back(std::move(l));
        }
        st.head_rechannel_w = {0.42f, -0.36f, 0.28f, -0.22f};

        CloneMetadata meta;
        meta.brand = "CleanRoom Lab";
        meta.product = "Built-In WaveNet Reference";
        meta.architecture = "WaveNet";
        meta.input_level_dbu = 12.0f;
        meta.output_level_dbu = 12.0f;
        SetWaveNetModel({std::move(st)}, 0.65f, meta);
    }

    void LoadBuiltInDemoGru16() {
        GruModel g;
        g.hidden_size = 16;
        g.input_skip = true;
        g.w_ih.resize(48);
        g.b_ih.resize(48);
        g.w_hh.resize(48 * 16);
        g.b_hh.assign(48, 0.0f);
        g.lin_w.resize(16);
        g.lin_b = 0.0f;
        for (int i = 0; i < 48; ++i) {
            g.w_ih[i] = 0.22f * std::sin(0.37f * static_cast<float>(i + 1));
            g.b_ih[i] = 0.02f * std::cos(0.19f * static_cast<float>(i));
            for (int j = 0; j < 16; ++j) {
                g.w_hh[i * 16 + j] = (i % 16 == j) ? 0.14f : 0.025f * std::sin(0.23f * (i + j + 1));
            }
        }
        for (int j = 0; j < 16; ++j) {
            g.lin_w[j] = 0.12f * ((j & 1) ? -1.0f : 1.0f);
        }
        CloneMetadata meta;
        meta.brand = "CleanRoom Lab";
        meta.product = "Built-In GRU-16 Reference";
        meta.architecture = "GRU-16";
        meta.input_level_dbu = 12.0f;
        meta.output_level_dbu = 12.0f;
        SetGruModel(std::move(g), meta);
    }

    // Batch-16 Zero-FIFO-Latency Real-Time Path (0 allocations, cache-resident GEMM)
    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        alignas(64) float buf[kSubBlockSize];
        const float in_scale = DbToLin(params_.input_gain_db) *
                               ComputeDbuCalibrationLinearGain(params_.interface_max_dbu, metadata_.input_level_dbu);
        for (size_t t = 0; t < kSubBlockSize; ++t) {
            buf[t] = in16[t] * in_scale;
        }

        if (arch_ == NeuralArchitecture::kWaveNet && !wn_stages_.empty()) {
            ProcessWaveNetBatch16(buf, out16);
        } else if (arch_ == NeuralArchitecture::kGru && gru_.hidden_size > 0) {
            ProcessGruBatch16(buf, out16);
        } else {
            for (size_t t = 0; t < kSubBlockSize; ++t) out16[t] = buf[t];
        }

        ApplyPostFiltersAndGain(out16, kSubBlockSize);
    }

    // Exact Sample-by-Sample Scalar Reference (for verification parity tests)
    void ProcessScalarReference(const float* in, float* out, size_t n) noexcept {
        const float in_scale = DbToLin(params_.input_gain_db) *
                               ComputeDbuCalibrationLinearGain(params_.interface_max_dbu, metadata_.input_level_dbu);
        for (size_t t = 0; t < n; ++t) {
            const float x = in[t] * in_scale;
            float y = x;
            if (arch_ == NeuralArchitecture::kWaveNet && !wn_stages_.empty()) {
                y = ProcessWaveNetScalarSample(x);
            } else if (arch_ == NeuralArchitecture::kGru && gru_.hidden_size > 0) {
                y = ProcessGruScalarSample(x);
            }
            out[t] = y;
        }
        ApplyPostFiltersAndGain(out, n);
    }

private:
    static inline float Sigmoid(float x) noexcept {
        return 1.0f / (1.0f + std::exp(-x));
    }

    void UpdateFilters() noexcept {
        const bool use_lc = params_.low_cut_hz > 21.0f;
        const bool use_hc = params_.high_cut_hz < 19800.0f;
        low_cut_active_ = use_lc;
        high_cut_active_ = use_hc;
        if (use_lc) {
            low_cut_.SetCoeffs(DesignHighPass(Clamp(params_.low_cut_hz, 20.0f, 1200.0f), 0.707, kSampleRateD));
        }
        if (use_hc) {
            high_cut_.SetCoeffs(DesignLowPass(Clamp(params_.high_cut_hz, 1200.0f, 20000.0f), 0.707, kSampleRateD));
        }
    }

    void ApplyPostFiltersAndGain(float* buf, size_t n) noexcept {
        const float out_scale = DbToLin(params_.output_gain_db) *
                                ComputeDbuCalibrationLinearGain(metadata_.output_level_dbu, params_.interface_max_dbu);
        for (size_t t = 0; t < n; ++t) {
            float y = buf[t] * out_scale;
            if (low_cut_active_) y = low_cut_.Process(y);
            if (high_cut_active_) y = high_cut_.Process(y);
            buf[t] = y;
        }
    }

    void PrepareWaveNetBuffers() {
        int max_ch = 1;
        for (auto& st : wn_stages_) {
            max_ch = std::max(max_ch, st.channels);
            for (auto& l : st.layers) {
                const int needed = l.kernel_size * l.dilation + 32;
                int cap = 16;
                while (cap < needed) cap <<= 1;
                l.ring_mask = cap - 1;
                l.ring_pos = 0;
                l.ring.assign(static_cast<size_t>(cap) * static_cast<size_t>(l.channels), 0.0f);
            }
        }
        scratch_layer_in_.assign(static_cast<size_t>(max_ch) * kSubBlockSize, 0.0f);
        scratch_layer_act_.assign(static_cast<size_t>(max_ch) * 2 * kSubBlockSize, 0.0f);
        scratch_head_acc_.assign(static_cast<size_t>(max_ch) * kSubBlockSize, 0.0f);
    }

    // Batch-16 WaveNet: evaluates 1x1 input projection, causal dilated conv, activation, and 1x1 mix
    // across all 16 time steps per layer for dense L1-cache reuse with zero FIFO latency.
    void ProcessWaveNetBatch16(const float* in16, float* out16) noexcept {
        alignas(64) float head_out[kSubBlockSize] = {};
        alignas(64) float cond[kSubBlockSize];
        for (size_t t = 0; t < kSubBlockSize; ++t) cond[t] = in16[t];

        for (size_t s_idx = 0; s_idx < wn_stages_.size(); ++s_idx) {
            auto& st = wn_stages_[s_idx];
            const int C = st.channels;

            // Stage input projection: [C x 16]
            for (int c = 0; c < C; ++c) {
                const float w = st.input_w[static_cast<size_t>(c)];
                for (size_t t = 0; t < kSubBlockSize; ++t) {
                    scratch_layer_in_[static_cast<size_t>(c) * kSubBlockSize + t] = w * cond[t];
                    scratch_head_acc_[static_cast<size_t>(c) * kSubBlockSize + t] = 0.0f;
                }
            }

            for (auto& l : st.layers) {
                const int out_c = l.gated ? (2 * C) : C;
                const int K = l.kernel_size;
                const int D = l.dilation;
                const int mask = l.ring_mask;

                // Write all 16 incoming samples into layer ring sequentially
                const int base_pos = l.ring_pos;
                for (size_t t = 0; t < kSubBlockSize; ++t) {
                    const int pos = (base_pos + static_cast<int>(t)) & mask;
                    for (int ic = 0; ic < C; ++ic) {
                        l.ring[static_cast<size_t>(pos) * C + ic] =
                            scratch_layer_in_[static_cast<size_t>(ic) * kSubBlockSize + t];
                    }
                }
                l.ring_pos = (base_pos + static_cast<int>(kSubBlockSize)) & mask;

                // Causal dilated convolution + condition mixin + activation across 16 steps
                for (int oc = 0; oc < out_c; ++oc) {
                    const float bias = l.conv_b[static_cast<size_t>(oc)];
                    const float mw = l.mixin_w[static_cast<size_t>(oc)];
                    for (size_t t = 0; t < kSubBlockSize; ++t) {
                        float acc = bias + mw * cond[t];
                        const int cur_pos = (base_pos + static_cast<int>(t)) & mask;
                        for (int k = 0; k < K; ++k) {
                            const int tap_pos = (cur_pos - (K - 1 - k) * D) & mask;
                            const float* ring_row = &l.ring[static_cast<size_t>(tap_pos) * C];
                            for (int ic = 0; ic < C; ++ic) {
                                acc += l.conv_w[(static_cast<size_t>(oc) * C + ic) * K + k] * ring_row[ic];
                            }
                        }
                        scratch_layer_act_[static_cast<size_t>(oc) * kSubBlockSize + t] = acc;
                    }
                }

                // Apply activation (Tanh or Gated Tanh*Sigmoid)
                for (int c = 0; c < C; ++c) {
                    for (size_t t = 0; t < kSubBlockSize; ++t) {
                        float a = scratch_layer_act_[static_cast<size_t>(c) * kSubBlockSize + t];
                        if (l.gated) {
                            float g = scratch_layer_act_[static_cast<size_t>(c + C) * kSubBlockSize + t];
                            a = std::tanh(a) * Sigmoid(g);
                        } else {
                            a = std::tanh(a);
                        }
                        scratch_layer_act_[static_cast<size_t>(c) * kSubBlockSize + t] = a;
                        scratch_head_acc_[static_cast<size_t>(c) * kSubBlockSize + t] += a;
                    }
                }

                // Residual 1x1 projection: layer_in[oc, t] += b[oc] + sum_ic W[oc, ic] * act[ic, t]
                for (int oc = 0; oc < C; ++oc) {
                    const float b1 = l.one_x_one_b[static_cast<size_t>(oc)];
                    const float* w_row = &l.one_x_one_w[static_cast<size_t>(oc) * C];
                    for (size_t t = 0; t < kSubBlockSize; ++t) {
                        float sum = b1;
                        for (int ic = 0; ic < C; ++ic) {
                            sum += w_row[ic] * scratch_layer_act_[static_cast<size_t>(ic) * kSubBlockSize + t];
                        }
                        scratch_layer_in_[static_cast<size_t>(oc) * kSubBlockSize + t] += sum;
                    }
                }
            }

            // Stage head projection into scalar output
            for (size_t t = 0; t < kSubBlockSize; ++t) {
                float hsum = st.head_bias ? st.head_rechannel_b[0] : 0.0f;
                for (int c = 0; c < C; ++c) {
                    hsum += st.head_rechannel_w[static_cast<size_t>(c)] *
                            scratch_head_acc_[static_cast<size_t>(c) * kSubBlockSize + t];
                }
                head_out[t] += hsum;
            }
        }

        for (size_t t = 0; t < kSubBlockSize; ++t) {
            out16[t] = head_out[t] * wn_head_scale_;
        }
    }

    float ProcessWaveNetScalarSample(float x) noexcept {
        float head_total = 0.0f;
        for (auto& st : wn_stages_) {
            const int C = st.channels;
            std::vector<float> cur(static_cast<size_t>(C), 0.0f);
            std::vector<float> head_acc(static_cast<size_t>(C), 0.0f);
            for (int c = 0; c < C; ++c) cur[c] = st.input_w[static_cast<size_t>(c)] * x;

            for (auto& l : st.layers) {
                const int out_c = l.gated ? (2 * C) : C;
                const int K = l.kernel_size;
                const int D = l.dilation;
                const int mask = l.ring_mask;
                const int pos = l.ring_pos;
                for (int ic = 0; ic < C; ++ic) l.ring[static_cast<size_t>(pos) * C + ic] = cur[ic];
                l.ring_pos = (pos + 1) & mask;

                std::vector<float> pre(static_cast<size_t>(out_c), 0.0f);
                for (int oc = 0; oc < out_c; ++oc) {
                    float acc = l.conv_b[static_cast<size_t>(oc)] + l.mixin_w[static_cast<size_t>(oc)] * x;
                    for (int k = 0; k < K; ++k) {
                        const int tap_pos = (pos - (K - 1 - k) * D) & mask;
                        for (int ic = 0; ic < C; ++ic) {
                            acc += l.conv_w[(static_cast<size_t>(oc) * C + ic) * K + k] *
                                   l.ring[static_cast<size_t>(tap_pos) * C + ic];
                        }
                    }
                    pre[oc] = acc;
                }
                std::vector<float> act(static_cast<size_t>(C), 0.0f);
                for (int c = 0; c < C; ++c) {
                    act[c] = l.gated ? (std::tanh(pre[c]) * Sigmoid(pre[c + C])) : std::tanh(pre[c]);
                    head_acc[c] += act[c];
                }
                for (int oc = 0; oc < C; ++oc) {
                    float sum = l.one_x_one_b[static_cast<size_t>(oc)];
                    for (int ic = 0; ic < C; ++ic) sum += l.one_x_one_w[static_cast<size_t>(oc) * C + ic] * act[ic];
                    cur[oc] += sum;
                }
            }
            float hsum = st.head_bias ? st.head_rechannel_b[0] : 0.0f;
            for (int c = 0; c < C; ++c) hsum += st.head_rechannel_w[static_cast<size_t>(c)] * head_acc[c];
            head_total += hsum;
        }
        return head_total * wn_head_scale_;
    }

    // Batch-16 GRU: projects all 16 W_ih * x + b_ih upfront, then runs tight register-resident Uh recurrence
    void ProcessGruBatch16(const float* in16, float* out16) noexcept {
        const int H = gru_.hidden_size;
        constexpr int kMaxH = 32;
        if (H <= 0 || H > kMaxH) {
            for (size_t t = 0; t < kSubBlockSize; ++t) out16[t] = in16[t];
            return;
        }
        alignas(64) float ih_proj[3 * kMaxH][kSubBlockSize];
        for (int g = 0; g < 3 * H; ++g) {
            const float w = gru_.w_ih[static_cast<size_t>(g)];
            const float b = gru_.b_ih[static_cast<size_t>(g)];
            for (size_t t = 0; t < kSubBlockSize; ++t) {
                ih_proj[g][t] = w * in16[t] + b;
            }
        }

        alignas(64) float h_cur[kMaxH];
        alignas(64) float h_next[kMaxH];
        for (int i = 0; i < H; ++i) h_cur[i] = gru_.h[static_cast<size_t>(i)];

        for (size_t t = 0; t < kSubBlockSize; ++t) {
            float y = gru_.lin_b + (gru_.input_skip ? in16[t] : 0.0f);
            for (int i = 0; i < H; ++i) {
                float hr = gru_.b_hh[static_cast<size_t>(i)];
                float hz = gru_.b_hh[static_cast<size_t>(H + i)];
                float hn = gru_.b_hh[static_cast<size_t>(2 * H + i)];
                const float* wr = &gru_.w_hh[static_cast<size_t>(i) * H];
                const float* wz = &gru_.w_hh[static_cast<size_t>(H + i) * H];
                const float* wn = &gru_.w_hh[static_cast<size_t>(2 * H + i) * H];
                for (int j = 0; j < H; ++j) {
                    const float hj = h_cur[j];
                    hr += wr[j] * hj;
                    hz += wz[j] * hj;
                    hn += wn[j] * hj;
                }
                const float r = Sigmoid(ih_proj[i][t] + hr);
                const float z = Sigmoid(ih_proj[H + i][t] + hz);
                const float n = std::tanh(ih_proj[2 * H + i][t] + r * hn);
                h_next[i] = (1.0f - z) * n + z * h_cur[i];
                y += gru_.lin_w[static_cast<size_t>(i)] * h_next[i];
            }
            for (int i = 0; i < H; ++i) h_cur[i] = h_next[i];
            out16[t] = y;
        }
        for (int i = 0; i < H; ++i) gru_.h[static_cast<size_t>(i)] = h_cur[i];
    }

    float ProcessGruScalarSample(float x) noexcept {
        const int H = gru_.hidden_size;
        std::vector<float> h_next(static_cast<size_t>(H), 0.0f);
        float y = gru_.lin_b + (gru_.input_skip ? x : 0.0f);
        for (int i = 0; i < H; ++i) {
            float hr = gru_.b_hh[static_cast<size_t>(i)];
            float hz = gru_.b_hh[static_cast<size_t>(H + i)];
            float hn = gru_.b_hh[static_cast<size_t>(2 * H + i)];
            for (int j = 0; j < H; ++j) {
                const float hj = gru_.h[static_cast<size_t>(j)];
                hr += gru_.w_hh[static_cast<size_t>(i) * H + j] * hj;
                hz += gru_.w_hh[static_cast<size_t>(H + i) * H + j] * hj;
                hn += gru_.w_hh[static_cast<size_t>(2 * H + i) * H + j] * hj;
            }
            const float r = Sigmoid(gru_.w_ih[static_cast<size_t>(i)] * x + gru_.b_ih[static_cast<size_t>(i)] + hr);
            const float z = Sigmoid(gru_.w_ih[static_cast<size_t>(H + i)] * x + gru_.b_ih[static_cast<size_t>(H + i)] + hz);
            const float n = std::tanh(gru_.w_ih[static_cast<size_t>(2 * H + i)] * x + gru_.b_ih[static_cast<size_t>(2 * H + i)] + r * hn);
            h_next[i] = (1.0f - z) * n + z * gru_.h[static_cast<size_t>(i)];
            y += gru_.lin_w[static_cast<size_t>(i)] * h_next[i];
        }
        gru_.h = std::move(h_next);
        return y;
    }

    NeuralArchitecture arch_ = NeuralArchitecture::kNone;
    NeuralCloneParams params_{};
    CloneMetadata metadata_{};
    std::vector<WaveNetStage> wn_stages_;
    float wn_head_scale_ = 1.0f;
    GruModel gru_{};

    std::vector<float> scratch_layer_in_;
    std::vector<float> scratch_layer_act_;
    std::vector<float> scratch_head_acc_;

    BiquadDF2T low_cut_;
    BiquadDF2T high_cut_;
    bool low_cut_active_ = false;
    bool high_cut_active_ = false;
};

struct DecodedIrAsset {
    std::array<float, 2048> ir_48k{};
    uint32_t source_sample_rate = 48000;
    uint16_t source_bits_per_sample = 32;
    ReactiveCabImpedanceDesc extracted_z{};
};

bool LoadExternalCabIrWavFromMemory(const uint8_t* data, size_t size,
                                    DecodedIrAsset* out_ir, std::string* out_err);

bool LoadExternalNamJsonFromMemory(const char* json_text, size_t len,
                                   NeuralCloneEngine* out_engine, std::string* out_err);

bool LoadExternalAidaxJsonFromMemory(const char* json_text, size_t len,
                                     NeuralCloneEngine* out_engine, std::string* out_err);

}  // namespace stadium_dsp
