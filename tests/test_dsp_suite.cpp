#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include "stadium_dsp/agoura_tube_amp.hpp"
#include "stadium_dsp/amp_precision_blocks.hpp"
#include "stadium_dsp/amp_recipe_bank.hpp"
#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/dual_cab_convolver.hpp"
#include "stadium_dsp/guitar_input_z.hpp"
#include "stadium_dsp/lockfree_spsc.hpp"
#include "stadium_dsp/neural_clone_engine.hpp"
#include "stadium_dsp/oscillators_and_lfo.hpp"
#include "stadium_dsp/polyphase_oversampler.hpp"
#include "stadium_dsp/pot_tapers.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace {

int g_failures = 0;

void Check(bool cond, const char* msg) {
    if (!cond) {
        std::fprintf(stderr, "  [FAIL] %s\n", msg);
        ++g_failures;
    }
}

std::vector<uint8_t> BuildTestWav(const std::vector<double>& samples,
                                  uint32_t sample_rate,
                                  uint16_t audio_format,
                                  uint16_t bits_per_sample) {
    const uint16_t channels = 1;
    const uint16_t block_align = channels * (bits_per_sample / 8);
    const uint32_t byte_rate = sample_rate * block_align;
    const uint32_t data_size = static_cast<uint32_t>(samples.size()) * block_align;
    const uint32_t riff_size = 36 + data_size;

    std::vector<uint8_t> out;
    out.reserve(44 + data_size);
    auto push_u16 = [&](uint16_t v) {
        out.push_back(static_cast<uint8_t>(v & 0xFF));
        out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    };
    auto push_u32 = [&](uint32_t v) {
        out.push_back(static_cast<uint8_t>(v & 0xFF));
        out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
        out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
        out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    };
    auto push_tag = [&](const char* t) {
        for (int i = 0; i < 4; ++i) out.push_back(static_cast<uint8_t>(t[i]));
    };

    push_tag("RIFF");
    push_u32(riff_size);
    push_tag("WAVE");
    push_tag("fmt ");
    push_u32(16);
    push_u16(audio_format);
    push_u16(channels);
    push_u32(sample_rate);
    push_u32(byte_rate);
    push_u16(block_align);
    push_u16(bits_per_sample);
    push_tag("data");
    push_u32(data_size);

    for (double s : samples) {
        if (audio_format == 1 && bits_per_sample == 16) {
            int16_t v = static_cast<int16_t>(std::lrint(std::max(-1.0, std::min(1.0, s)) * 32767.0));
            push_u16(static_cast<uint16_t>(v));
        } else if (audio_format == 1 && bits_per_sample == 24) {
            int32_t v = static_cast<int32_t>(std::lrint(std::max(-1.0, std::min(1.0, s)) * 8388607.0));
            out.push_back(static_cast<uint8_t>(v & 0xFF));
            out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
            out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
        } else if (audio_format == 3 && bits_per_sample == 32) {
            float f = static_cast<float>(s);
            uint32_t bits = 0;
            std::memcpy(&bits, &f, 4);
            push_u32(bits);
        } else if (audio_format == 3 && bits_per_sample == 64) {
            uint64_t bits = 0;
            std::memcpy(&bits, &s, 8);
            push_u32(static_cast<uint32_t>(bits & 0xFFFFFFFFu));
            push_u32(static_cast<uint32_t>((bits >> 32) & 0xFFFFFFFFu));
        }
    }
    return out;
}

void Test1_ZeroLatencyConvolver2048() {
    std::printf("\n=== [Test 1] Zero-Latency 2,048-Tap Non-Uniform Partitioned Convolver ===\n");
    using namespace stadium_dsp;

    std::array<float, 2048> ir{};
    uint32_t rng = 0x12345678u;
    for (size_t i = 0; i < ir.size(); ++i) {
        rng = rng * 1664525u + 1013904223u;
        float u = (static_cast<float>(rng >> 8) / static_cast<float>(1u << 24)) * 2.0f - 1.0f;
        float env = std::exp(-static_cast<float>(i) / 380.0f);
        ir[i] = u * env * 0.4f;
    }
    ir[0] = 0.75f;
    ir[63] = -0.35f;
    ir[64] = 0.42f;
    ir[256] = -0.25f;
    ir[2047] = 0.12f;

    ZeroLatencyIrConvolver2048 conv;
    conv.SetImpulseResponseImmediate(ir.data(), ir.size());

    constexpr size_t kTotal = 4096;
    std::vector<float> x(kTotal, 0.0f);
    for (size_t i = 0; i < kTotal; ++i) {
        rng = rng * 1664525u + 1013904223u;
        x[i] = ((static_cast<float>(rng >> 8) / static_cast<float>(1u << 24)) * 2.0f - 1.0f) * 0.5f;
    }
    x[0] = 1.0f;

    std::vector<float> y_conv(kTotal, 0.0f);
    for (size_t off = 0; off < kTotal; off += 16) {
        conv.ProcessSubBlock16(&x[off], &y_conv[off]);
    }

    float max_err = 0.0f;
    for (size_t n = 0; n < kTotal; ++n) {
        double ref = 0.0;
        size_t lim = std::min(n + 1, ir.size());
        for (size_t k = 0; k < lim; ++k) {
            ref += static_cast<double>(ir[k]) * static_cast<double>(x[n - k]);
        }
        float err = std::fabs(y_conv[n] - static_cast<float>(ref));
        if (err > max_err) max_err = err;
    }

    float latency0_err = std::fabs(y_conv[0] - ir[0] * x[0]);
    std::printf("  Sample-0 Zero-Latency Error |y[0]-h[0]x[0]|: %.3e | Random Signal Max Error: %.3e\n",
                latency0_err, max_err);
    Check(latency0_err < 1e-6f, "Zero-latency sample-0 error must be < 1e-6");
    Check(max_err < 1.5e-5f, "2,048-tap partitioned FFT convolver max error must be < 1.5e-5");
}

void Test2_EllipticPolyphaseOversampler() {
    std::printf("\n=== [Test 2] 17th-Order Elliptic Polyphase Oversampler (2x & 4x) ===\n");
    using namespace stadium_dsp;

    PolyphaseOversampler2x os2;
    PolyphaseOversampler4x os4;
    os2.Reset();
    os4.Reset();

    // Measure stopband rejection of 2x decimation on a high-frequency tone in the upper half of 96 kHz (38 kHz)
    constexpr size_t kBlocks = 256;
    constexpr double kPi = 3.14159265358979323846;
    double pass_e = 0.0;
    double stop_e = 0.0;

    // Passband test: 1 kHz through 2x up+down
    for (size_t b = 0; b < kBlocks; ++b) {
        alignas(64) float in16[16];
        alignas(64) float up32[32];
        alignas(64) float out16[16];
        for (int i = 0; i < 16; ++i) {
            size_t n = b * 16 + i;
            in16[i] = static_cast<float>(std::sin(2.0 * kPi * 1000.0 * n / 48000.0));
        }
        os2.Upsample16(in16, up32);
        os2.Downsample16(up32, out16);
        if (b >= 64) {
            for (int i = 0; i < 16; ++i) pass_e += static_cast<double>(out16[i]) * out16[i];
        }
    }

    // Stopband test: 36 kHz signal injected directly at 96 kHz into 2x downsampler
    os2.Reset();
    for (size_t b = 0; b < kBlocks; ++b) {
        alignas(64) float up32[32];
        alignas(64) float out16[16];
        for (int i = 0; i < 32; ++i) {
            size_t n = b * 32 + i;
            up32[i] = static_cast<float>(std::sin(2.0 * kPi * 36000.0 * n / 96000.0));
        }
        os2.Downsample16(up32, out16);
        if (b >= 64) {
            for (int i = 0; i < 16; ++i) stop_e += static_cast<double>(out16[i]) * out16[i];
        }
    }
    double stop_rej_db = 10.0 * std::log10((pass_e + 1e-20) / (stop_e + 1e-20));
    double pass_gain_db = 10.0 * std::log10((pass_e / ((kBlocks - 64) * 16.0 * 0.5)) + 1e-20);

    // 4x roundtrip passband check
    double pass4_e = 0.0;
    for (size_t b = 0; b < kBlocks; ++b) {
        alignas(64) float in16[16];
        alignas(64) float up64[64];
        alignas(64) float out16[16];
        for (int i = 0; i < 16; ++i) {
            size_t n = b * 16 + i;
            in16[i] = static_cast<float>(std::sin(2.0 * kPi * 1000.0 * n / 48000.0));
        }
        os4.Upsample16(in16, up64);
        os4.Downsample16(up64, out16);
        if (b >= 64) {
            for (int i = 0; i < 16; ++i) pass4_e += static_cast<double>(out16[i]) * out16[i];
        }
    }
    double pass4_gain_db = 10.0 * std::log10((pass4_e / ((kBlocks - 64) * 16.0 * 0.5)) + 1e-20);

    std::printf("  2x Passband Gain: %.4f dB | Stopband Rejection (@36k/96k): %.2f dB | 4x Passband: %.4f dB\n",
                pass_gain_db, stop_rej_db, pass4_gain_db);
    Check(std::fabs(pass_gain_db) < 0.05, "2x passband gain must be within 0.05 dB of unity");
    Check(stop_rej_db > 95.0, "17th-order elliptic half-band stopband rejection must exceed 95 dB");
    Check(std::fabs(pass4_gain_db) < 0.05, "4x passband gain must be within 0.05 dB of unity");
}

void Test4_Batch16NeuralCloneEngine() {
    std::printf("\n=== [Test 4] Batch-16 Neural Clone Engine (.nam WaveNet & .aidax GRU-16) ===\n");
    using namespace stadium_dsp;

    // 1. Test WaveNet Batch-16 vs Scalar parity
    NeuralCloneEngine wn_batch;
    NeuralCloneEngine wn_scalar;
    wn_batch.LoadBuiltInDemoWaveNet();
    wn_scalar.LoadBuiltInDemoWaveNet();

    constexpr size_t kN = 512;
    std::vector<float> x(kN), y_b(kN), y_s(kN);
    for (size_t i = 0; i < kN; ++i) {
        x[i] = 0.45f * std::sin(2.0f * 3.14159265f * 196.0f * i / 48000.0f) +
               0.25f * std::sin(2.0f * 3.14159265f * 587.0f * i / 48000.0f);
    }
    for (size_t off = 0; off < kN; off += 16) {
        wn_batch.ProcessSubBlock16(&x[off], &y_b[off]);
        wn_scalar.ProcessScalarReference(&x[off], &y_s[off], 16);
    }
    float wn_err = 0.0f;
    for (size_t i = 0; i < kN; ++i) wn_err = std::max(wn_err, std::fabs(y_b[i] - y_s[i]));

    // 2. Test GRU-16 Batch-16 vs Scalar parity
    NeuralCloneEngine gru_batch;
    NeuralCloneEngine gru_scalar;
    gru_batch.LoadBuiltInDemoGru16();
    gru_scalar.LoadBuiltInDemoGru16();

    std::vector<float> yg_b(kN), yg_s(kN);
    for (size_t off = 0; off < kN; off += 16) {
        gru_batch.ProcessSubBlock16(&x[off], &yg_b[off]);
        gru_scalar.ProcessScalarReference(&x[off], &yg_s[off], 16);
    }
    float gru_err = 0.0f;
    for (size_t i = 0; i < kN; ++i) gru_err = std::max(gru_err, std::fabs(yg_b[i] - yg_s[i]));

    // 3. Test low_cut / high_cut parameter effect (GAP-08)
    NeuralCloneEngine wn_cut;
    wn_cut.LoadBuiltInDemoWaveNet();
    NeuralCloneParams p = wn_cut.GetParams();
    p.low_cut_hz = 220.0f;
    p.high_cut_hz = 3200.0f;
    wn_cut.SetParams(p);
    std::vector<float> y_cut(kN);
    for (size_t off = 0; off < kN; off += 16) {
        wn_cut.ProcessSubBlock16(&x[off], &y_cut[off]);
    }
    float cut_diff = 0.0f;
    for (size_t i = 64; i < kN; ++i) cut_diff = std::max(cut_diff, std::fabs(y_cut[i] - y_b[i]));

    std::printf("  WaveNet Batch-16 vs Scalar Max Err: %.3e | GRU-16 Max Err: %.3e | Cut Filter Diff: %.4f\n",
                wn_err, gru_err, cut_diff);
    Check(wn_err < 1.5e-5f, "WaveNet Batch-16 parity error must be < 1.5e-5");
    Check(gru_err < 1.5e-5f, "GRU-16 Batch-16 parity error must be < 1.5e-5");
    Check(cut_diff > 0.01f, "Clone low_cut/high_cut post-filter must actively shape output");
}

void Test9_SpectralMorph12MicsAutoDelayDbu() {
    std::printf("\n=== [Test 9] 128-Sample Spectral IR Morph, 12 Mics, Auto Delay & dBu Calibration ===\n");
    using namespace stadium_dsp;

    DualCabEngine cab;
    cab.InitializeFactoryCabs();

    // Test 128-sample clickless morph between two factory cabs
    CabSlotParams s = cab.GetSlotParams(0);
    s.factory_cab = FactoryCabModel::k4x12V30;
    s.mic_model = StudioMicModel::k57Dynamic;
    s.distance_inch = 1.0f;
    s.auto_delay = true;
    cab.SetSlotParams(0, s);

    // Trigger morph to 1x12 Deluxe + 121 Ribbon
    s.factory_cab = FactoryCabModel::k1x12Deluxe;
    s.mic_model = StudioMicModel::k121Ribbon;
    cab.SetSlotParams(0, s);

    float max_step = 0.0f;
    float prev = 0.0f;
    for (int b = 0; b < 64; ++b) {
        alignas(64) float in16[16], outL[16], outR[16];
        for (int i = 0; i < 16; ++i) {
            in16[i] = 0.35f * std::sin(2.0f * 3.14159265f * 220.0f * (b * 16 + i) / 48000.0f);
        }
        cab.ProcessSubBlock16(in16, outL, outR);
        for (int i = 0; i < 16; ++i) {
            if (b > 0 || i > 0) max_step = std::max(max_step, std::fabs(outL[i] - prev));
            prev = outL[i];
        }
    }

    // Auto Air Delay check: 1" vs 6" (~5 inches * 3.555 = ~18 samples)
    DualCabEngine cab1, cab6;
    cab1.InitializeFactoryCabs();
    cab6.InitializeFactoryCabs();
    CabSlotParams p1 = cab1.GetSlotParams(0);
    p1.enabled = true;
    p1.distance_inch = 1.0f;
    p1.auto_delay = true;
    cab1.SetSlotParams(0, p1);
    CabSlotParams p6 = p1;
    p6.distance_inch = 6.0f;
    cab6.SetSlotParams(0, p6);
    CabSlotParams off = cab1.GetSlotParams(1);
    off.enabled = false;
    cab1.SetSlotParams(1, off);
    cab6.SetSlotParams(1, off);

    // Measure peak index of impulse for 1" vs 6"
    int peak1 = 0, peak6 = 0;
    float pv1 = 0.0f, pv6 = 0.0f;
    for (int b = 0; b < 16; ++b) {
        alignas(64) float in16[16] = {};
        if (b == 0) in16[0] = 1.0f;
        alignas(64) float l1[16], r1[16], l6[16], r6[16];
        cab1.ProcessSubBlock16(in16, l1, r1);
        cab6.ProcessSubBlock16(in16, l6, r6);
        for (int i = 0; i < 16; ++i) {
            if (std::fabs(l1[i]) > pv1) { pv1 = std::fabs(l1[i]); peak1 = b * 16 + i; }
            if (std::fabs(l6[i]) > pv6) { pv6 = std::fabs(l6[i]); peak6 = b * 16 + i; }
        }
    }
    int air_delay_delta = peak6 - peak1;

    // Verify all 12 studio mics are valid and distinct
    Check(static_cast<int>(StudioMicModel::kCount) == 12, "Must provide exactly 12 Studio Mic models");
    Check(static_cast<int>(FactoryCabModel::kCount) == 12, "Must provide exactly 12 Factory Cab models");

    // Verify dBu calibration calculation: interface +9 dBu vs model +12 dBu -> -3 dB input scaling (linear 0.7079)
    float cal_gain = ComputeDbuCalibrationLinearGain(9.0f, 12.0f);
    float cal_err = std::fabs(cal_gain - std::pow(10.0f, -3.0f / 20.0f));

    // Verify Factory Cab Z_cab Cone Fs ordering (8x10 SVT < 4x12 V30 < 1x8 Tweed)
    auto z_svt = cab.GetFactoryCabImpedance(FactoryCabModel::k8x10Svt);
    auto z_v30 = cab.GetFactoryCabImpedance(FactoryCabModel::k4x12V30);
    auto z_tweed = cab.GetFactoryCabImpedance(FactoryCabModel::k1x8Tweed);

    // Verify 128-bin IR spectrum extraction
    std::array<float, 128> spec_db{};
    cab.ComputeSlotMagnitudeSpectrumDb(0, spec_db.data(), 128);
    bool spec_finite = true;
    for (float v : spec_db) if (!std::isfinite(v)) spec_finite = false;

    std::printf("  Spectral Morph Max Step: %.4f | Auto Air Delay (1\" vs 6\"): +%d samples | dBu Cal Err: %.2e\n",
                max_step, air_delay_delta, cal_err);
    std::printf("  Factory Cab Cone Fs (8x10 SVT / 4x12 V30 / 1x8 Tweed): %.1f / %.1f / %.1f Hz\n",
                z_svt.peak1_hz, z_v30.peak1_hz, z_tweed.peak1_hz);

    Check(max_step < 0.08f, "128-sample spectral morph must remain clickless (< 0.08 step)");
    Check(air_delay_delta >= 16 && air_delay_delta <= 20, "Auto Air Delay (1\" -> 6\") must be ~18 samples");
    Check(cal_err < 1e-5f, "dBu calibration linear gain must match exact dB formula");
    Check(z_svt.peak1_hz < z_v30.peak1_hz && z_v30.peak1_hz < z_tweed.peak1_hz,
          "Factory cab Z_cab cone Fs ordering must satisfy 8x10 SVT < 4x12 V30 < 1x8 Tweed");
    Check(spec_finite, "128-bin IR magnitude spectrum must be finite");
}

void Test12_ExternalFileImportPipeline() {
    std::printf("\n=== [Test 12] External Local File Import Pipeline (.nam / .aidax / .wav 2048-Tap) ===\n");
    using namespace stadium_dsp;

    // 1. Test RIFF WAVE decode + Blackman-Harris Windowed-Sinc resampling from 44.1 kHz & 96 kHz -> 48 kHz
    auto make_res_ir = [](uint32_t sr) {
        size_t len = static_cast<size_t>(sr * 0.045);
        std::vector<double> ir(len, 0.0);
        for (size_t n = 0; n < len; ++n) {
            double t = static_cast<double>(n) / sr;
            ir[n] = std::exp(-t * 95.0) * std::cos(2.0 * 3.14159265358979323846 * 108.0 * t);
        }
        return ir;
    };

    auto wav44_24 = BuildTestWav(make_res_ir(44100), 44100, 1, 24);
    auto wav96_f32 = BuildTestWav(make_res_ir(96000), 96000, 3, 32);
    auto wav48_f64 = BuildTestWav(make_res_ir(48000), 48000, 3, 64);

    DecodedIrAsset ir44{}, ir96{}, ir48{};
    std::string err;
    Check(LoadExternalCabIrWavFromMemory(wav44_24.data(), wav44_24.size(), &ir44, &err),
          "24-bit 44.1kHz WAV IR import must succeed");
    Check(LoadExternalCabIrWavFromMemory(wav96_f32.data(), wav96_f32.size(), &ir96, &err),
          "32-bit float 96kHz WAV IR import must succeed");
    Check(LoadExternalCabIrWavFromMemory(wav48_f64.data(), wav48_f64.size(), &ir48, &err),
          "64-bit float 48kHz WAV IR import must succeed");

    // Verify extracted cone resonance near 108 Hz for both resampled IRs
    std::printf("  WAV SRC Cone Fs (44.1k->48k / 96k->48k / 48k): %.1f / %.1f / %.1f Hz\n",
                ir44.extracted_z.peak1_hz, ir96.extracted_z.peak1_hz, ir48.extracted_z.peak1_hz);
    Check(std::fabs(ir44.extracted_z.peak1_hz - 108.0f) < 12.0f, "44.1k->48k Sinc SRC must preserve 108 Hz cone resonance");
    Check(std::fabs(ir96.extracted_z.peak1_hz - 108.0f) < 12.0f, "96k->48k Sinc SRC must preserve 108 Hz cone resonance");

    // 2. Test multi-stage .nam JSON import + CloneMetadata extraction
    const std::string nam_json = R"({
      "version": "0.5.4",
      "architecture": "WaveNet",
      "metadata": {
        "brand": "CleanRoom Lab",
        "model": "Brit 800 Hot",
        "notes": "2203 MV=7 EL34",
        "input_level_dbu": 12.2,
        "output_level_dbu": 12.2
      },
      "config": {
        "layers": [
          {
            "input_size": 1,
            "condition_size": 1,
            "channels": 2,
            "head_size": 2,
            "kernel_size": 2,
            "dilations": [1, 2],
            "activation": "Tanh",
            "gated": false,
            "head_bias": false
          }
        ],
        "head_scale": 0.5
      },
      "weights": [
        0.4, -0.3,
        0.2, 0.1, -0.1, 0.3, 0.15, -0.2, 0.05, 0.25, 0.01, -0.02,
        0.1, -0.1,
        0.5, 0.0, 0.0, 0.5, 0.0, 0.0,
        0.25, -0.15, 0.1, 0.2, -0.05, 0.15, 0.2, -0.1, 0.0, 0.01,
        -0.05, 0.1,
        0.4, 0.1, -0.1, 0.4, 0.0, 0.0,
        0.3, -0.2,
        0.6, -0.4
      ]
    })";

    NeuralCloneEngine nam_eng;
    Check(LoadExternalNamJsonFromMemory(nam_json.data(), nam_json.size(), &nam_eng, &err),
          "External .nam JSON import must succeed");
    Check(nam_eng.GetMetadata().brand == "CleanRoom Lab", "CloneMetadata brand must be parsed");
    Check(nam_eng.GetMetadata().product == "Brit 800 Hot", "CloneMetadata model/product must be parsed");
    Check(std::fabs(nam_eng.GetMetadata().input_level_dbu - 12.2f) < 1e-4f, "CloneMetadata dBu must be parsed");

    // 3. Test .aidax Keras GRU JSON import
    const std::string aidax_json = R"({
      "name": "CleanRoom Tweed GRU",
      "author": "HelxMobile",
      "unit_type": "amp",
      "model_data": {
        "model": "SimpleRNN",
        "unit_type": "GRU",
        "hidden_size": 2,
        "input_skip": 1
      },
      "state_dict": {
        "rec.weight_ih_l0": [[0.2], [-0.1], [0.15], [0.25], [-0.3], [0.35]],
        "rec.weight_hh_l0": [
          [0.1, -0.05], [0.05, 0.1],
          [-0.1, 0.08], [0.04, -0.06],
          [0.12, 0.03], [-0.07, 0.11]
        ],
        "rec.bias_ih_l0": [0.01, -0.01, 0.02, -0.02, 0.0, 0.0],
        "rec.bias_hh_l0": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        "lin.weight": [[0.5, -0.4]],
        "lin.bias": [0.01]
      }
    })";

    NeuralCloneEngine aidax_eng;
    Check(LoadExternalAidaxJsonFromMemory(aidax_json.data(), aidax_json.size(), &aidax_eng, &err),
          "External .aidax GRU JSON import must succeed");
    alignas(64) float test_in[16], test_out[16], test_ref[16];
    for (int i = 0; i < 16; ++i) test_in[i] = 0.3f * std::sin(0.2f * i);
    NeuralCloneEngine aidax_ref = aidax_eng;
    aidax_eng.ProcessSubBlock16(test_in, test_out);
    aidax_ref.ProcessScalarReference(test_in, test_ref, 16);
    float aidax_err = 0.0f;
    for (int i = 0; i < 16; ++i) aidax_err = std::max(aidax_err, std::fabs(test_out[i] - test_ref[i]));
    std::printf("  External .aidax GRU Parity Max Err: %.3e | Brand: %s\n",
                aidax_err, nam_eng.GetMetadata().brand.c_str());
    Check(aidax_err < 1.5e-5f, "External .aidax Batch-16 vs scalar parity must be < 1.5e-5");
}

void Test3_AgouraTubeAmpAdaaNrCabZ() {
    std::printf("\n=== [Test 3] Agoura Tube Amp + ADAA1 + 4x NR Cathode-Follower + Z_cab Coupling ===\n");
    using namespace stadium_dsp;

    AgouraTubeAmp amp;
    amp.SetModel(AmpModelId::kBrit2203);
    AmpParams p = amp.GetParams();
    p.drive = 0.72f;
    p.master = 0.65f;
    p.hype = 0.0f;
    amp.SetParams(p);

    // Verify finite bounded output & reactive Z_cab response sensitivity
    auto z_v30 = GetFactoryCabSpec(FactoryCabModel::k4x12V30).impedance;
    auto z_flat = ReactiveCabImpedanceDesc{96.0f, 3.0f, 0.0f, 200.0f, 2.0f, 0.0f, 1600.0f, 0.0f, 0.3f};

    auto measure_band = [&](const ReactiveCabImpedanceDesc& z, float freq_hz) {
        amp.Reset();
        amp.SetReactiveCabImpedance(z);
        double e = 0.0;
        for (int b = 0; b < 128; ++b) {
            alignas(64) float in16[16], out16[16];
            for (int i = 0; i < 16; ++i) {
                in16[i] = 0.12f * std::sin(kTwoPi * freq_hz * (b * 16 + i) / kSampleRate);
            }
            amp.ProcessSubBlock16(in16, out16);
            if (b >= 48) {
                for (int i = 0; i < 16; ++i) e += static_cast<double>(out16[i]) * out16[i];
            }
        }
        return std::sqrt(e / (80.0 * 16.0));
    };

    double rms_z = measure_band(z_v30, 96.0f);
    double rms_flat = measure_band(z_flat, 96.0f);
    double z_boost_db = 20.0 * std::log10((rms_z + 1e-12) / (rms_flat + 1e-12));

    // Verify Stock (hype=0) vs Hype (hype=1) produces distinct harmonic saturation/EQ contour
    p.hype = 1.0f;
    amp.SetParams(p);
    double rms_hype = measure_band(z_v30, 96.0f);

    std::printf("  Z_cab Cone Resonance Coupling Delta (@96Hz): %+.2f dB | Stock vs Hype RMS: %.4f vs %.4f\n",
                z_boost_db, rms_z, rms_hype);
    Check(std::isfinite(rms_z) && rms_z > 0.01 && rms_z < 10.0, "Agoura amp output must be finite and musical");
    Check(z_boost_db > 0.8, "Reactive Z_cab coupling must enhance cabinet resonance around Fs");
    Check(std::fabs(rms_hype - rms_z) > 1e-3, "Stock vs Hype must alter amp response");
}

void Test6_KirchhoffRlcAutoZ() {
    std::printf("\n=== [Test 6] Kirchhoff RLC Guitar Pickup Auto-Z (10k..1M Ohm) Verification ===\n");
    using namespace stadium_dsp;

    // Exact analog 2-node Kirchhoff pickup voltage transfer ratio |H(jw; R_in) / H(jw; 1M)|
    auto analog_ratio_db = [](double f, double L, double Rp, double Cp, double Rin) {
        const std::complex<double> s(0.0, kTwoPiD * f);
        auto H = [&](double R) {
            const std::complex<double> Z_series = Rp + s * L;
            const std::complex<double> Y_load = (1.0 / R) + s * Cp;
            return 1.0 / (1.0 + Z_series * Y_load);
        };
        return 20.0 * std::log10(std::abs(H(Rin) / H(1.0e6)));
    };

    GuitarPickupInputZ z_10k, z_136k, z_1m;
    z_10k.Configure(GuitarPickupType::kSingleCoil, 10000.0f);
    z_136k.Configure(GuitarPickupType::kSingleCoil, 136000.0f);
    z_1m.Configure(GuitarPickupType::kSingleCoil, 1000000.0f);

    const auto& pk = GetGuitarPickupSpec(GuitarPickupType::kSingleCoil);
    double err_10k = 0.0, err_136k = 0.0, err_1m = 0.0;
    for (double f : {100.0, 400.0, 1000.0, 2200.0, 3500.0, 4400.0, 6000.0, 8000.0}) {
        err_10k = std::max(err_10k, std::fabs(z_10k.EvalMagnitudeDb(f) - analog_ratio_db(f, pk.L_h, pk.R_ohm, pk.C_f, 10000.0)));
        err_136k = std::max(err_136k, std::fabs(z_136k.EvalMagnitudeDb(f) - analog_ratio_db(f, pk.L_h, pk.R_ohm, pk.C_f, 136000.0)));
        err_1m = std::max(err_1m, std::fabs(z_1m.EvalMagnitudeDb(f)));
    }

    std::printf("  Max Kirchhoff RLC Error (10k Fuzz / 136k Low Jack / 1M Bypass): %.4f / %.4f / %.4f dB\n",
                err_10k, err_136k, err_1m);
    Check(err_10k < 0.15, "10k Auto-Z RLC loading error must be < 0.15 dB");
    Check(err_136k < 0.15, "136k Auto-Z RLC loading error must be < 0.15 dB");
    Check(err_1m < 1e-6, "1M Ohm Auto-Z must be strictly transparent (0 dB)");
}

void Test7_PotTapersAndCoupledBrightCap() {
    std::printf("\n=== [Test 7] Potentiometer Tapers (10A/15A/RevC/W) & Coupled Bright-Cap Verification ===\n");
    using namespace stadium_dsp;

    // Verify key taper curves at 0.0, 0.5, 1.0
    Check(std::fabs(EvalPotTaper(PotTaper::kLog10A, 0.5f) - 0.10f) < 0.015f, "Log 10A must be ~10% at noon");
    Check(std::fabs(EvalPotTaper(PotTaper::kLog15A, 0.5f) - 0.15f) < 0.015f, "Log 15A must be ~15% at noon");
    Check(EvalPotTaper(PotTaper::kRevLogC, 0.5f) > 0.80f, "Rev-Log C must be > 80% at noon");
    Check(std::fabs(EvalPotTaper(PotTaper::kWTaper, 0.5f) - 0.50f) < 0.02f, "W-Taper must be symmetric around 0.5");

    // Verify coupled bright-cap Kirchhoff divider error and unity at Drive=10
    CoupledBrightCapFilter bright;
    bright.Configure(1.0e6, 120.0e-12, 0.25, PotTaper::kLog15A, true);
    const double alpha = EvalPotTaper(PotTaper::kLog15A, 0.25f);
    const double r_top = (1.0 - alpha) * 1.0e6;
    const double r_bot = alpha * 1.0e6;
    double max_err = 0.0;
    for (double f : {100.0, 500.0, 1500.0, 4000.0, 8000.0}) {
        const std::complex<double> s(0.0, kTwoPiD * f);
        const std::complex<double> z_top = r_top / (1.0 + s * r_top * 120.0e-12);
        const std::complex<double> h_ref = (r_bot / (r_bot + z_top)) / alpha;  // normalized to DC
        const double ref_db = 20.0 * std::log10(std::abs(h_ref));
        max_err = std::max(max_err, std::fabs(bright.EvalMagnitudeDb(f) - ref_db));
    }

    bright.Configure(1.0e6, 120.0e-12, 1.0, PotTaper::kLog15A, true);
    const double boost_at_10 = std::fabs(bright.EvalMagnitudeDb(6000.0));
    std::printf("  Coupled Bright-Cap Kirchhoff Max Error: %.4f dB | Boost @ Drive=10: %.6f dB\n",
                max_err, boost_at_10);
    Check(max_err < 0.12, "Coupled bright-cap error must be < 0.12 dB");
    Check(boost_at_10 < 1e-5, "Bright cap must vanish at Drive=10 (0 dB boost)");
}

void Test8_VictoriaMicroPhysicsAndFmvStack() {
    std::printf("\n=== [Test 8] Victoria/T11 Micro-Physics & FMV Tone Stack Nodal Verification ===\n");
    using namespace stadium_dsp;

    // 1. Verify Asymmetric Rectifier Ripple 31% charge / 69% discharge duty @ 120 Hz
    AsymmetricRectifierRippleGen ripple;
    ripple.Configure(120.0f, 60.0f);
    int rising = 0;
    constexpr int kPeriod = 400;  // 48000 / 120 = 400 samples exactly
    float prev = ripple.NextRippleSample();
    for (int i = 0; i < kPeriod; ++i) {
        float cur = ripple.NextRippleSample();
        if (cur > prev) ++rising;
        prev = cur;
    }
    const double duty = static_cast<double>(rising) / static_cast<double>(kPeriod);

    // 2. Verify Unitary 8x8 Walsh-Hadamard transform preserves L2 norm
    alignas(64) float v[8] = {1.0f, -0.5f, 0.25f, 0.75f, -0.3f, 0.9f, -0.1f, 0.4f};
    double e0 = 0.0, e1 = 0.0;
    for (float x : v) e0 += static_cast<double>(x) * x;
    ApplyHadamard8x8(v);
    for (float x : v) e1 += static_cast<double>(x) * x;

    // 3. Verify 3D Chua Chaotic LFO remains bounded and non-periodic
    ChuaChaoticLfo chua;
    float cmin = 1e9f, cmax = -1e9f;
    for (int i = 0; i < 4000; ++i) {
        float s = chua.ProcessSample();
        cmin = std::min(cmin, s);
        cmax = std::max(cmax, s);
    }

    // 4. Verify Fender AB763 3rd-order FMV bilinear tone stack matches exact analog formula
    const auto& twin = GetAmpRecipe(AmpModelId::kUsDoubleNrm);
    const auto fmv = ComputeFmvToneStackBilinearDouble(twin.tone_stack, 0.5, 0.5, 0.5, kSampleRateD);
    const double mid_scoop_db = fmv.EvalMagnitudeDb(500.0, kSampleRateD) - fmv.EvalMagnitudeDb(100.0, kSampleRateD);

    std::printf("  120 Hz Rectifier Ripple Duty: %.3f (Target 0.310) | Hadamard Energy Err: %.2e | AB763 Mid Scoop: %.2f dB\n",
                duty, std::fabs(e1 - e0), mid_scoop_db);
    Check(std::fabs(duty - 0.31) < 0.015, "Rectifier ripple charge duty must match 31%");
    Check(std::fabs(e1 - e0) < 1e-5, "8x8 Walsh-Hadamard must be orthonormal");
    Check(std::isfinite(cmin) && std::isfinite(cmax) && (cmax - cmin) > 0.2f, "Chua chaotic LFO must oscillate boundedly");
    Check(mid_scoop_db < -3.0, "Fender AB763 FMV tone stack must exhibit authentic mid scoop");
}

void Test11_Expanded30AmpRecipesJames2CPlusSE() {
    std::printf("\n=== [Test 11] Expanded 30-Model Catalog Recipes, James Stack, 2C+ GEQ & SE Class-A ===\n");
    using namespace stadium_dsp;

    Check(static_cast<int>(AmpModelId::kCount) == 30, "Amp recipe bank must contain exactly 30 models");

    // 1. Verify R.T. James 4th-order tone stack 10A noon flatness (Hiwatt DR-103)
    const auto& hiwatt = GetAmpRecipe(AmpModelId::kWhoWatt103);
    const auto james = ComputeJamesToneStackBilinearDouble(hiwatt.james_stack, 0.5, 0.5, kSampleRateD);
    double j_min = 1e9, j_max = -1e9;
    for (double f : {40.0, 100.0, 300.0, 1000.0, 3000.0, 8000.0}) {
        double db = james.EvalMagnitudeDb(f, kSampleRateD);
        j_min = std::min(j_min, db);
        j_max = std::max(j_max, db);
    }
    const double james_flat_span = j_max - j_min;

    // 2. Verify Single-Ended 5F1 Tweed Champ generates significantly higher H2/H1 than Push-Pull Brit 2203
    auto measure_h2_h1 = [](AmpModelId id) {
        AgouraTubeAmp amp;
        amp.SetModel(id);
        AmpParams p = amp.GetParams();
        p.drive = 0.65f;
        p.master = 0.72f;
        amp.SetParams(p);
        constexpr double f0 = 200.0;
        double re1 = 0, im1 = 0, re2 = 0, im2 = 0;
        for (int b = 0; b < 160; ++b) {
            alignas(64) float in16[16], out16[16];
            for (int i = 0; i < 16; ++i) {
                in16[i] = 0.18f * std::sin(kTwoPi * static_cast<float>(f0) * (b * 16 + i) / kSampleRate);
            }
            amp.ProcessSubBlock16(in16, out16);
            if (b >= 60) {
                for (int i = 0; i < 16; ++i) {
                    const int n = b * 16 + i;
                    const double w1 = kTwoPiD * f0 * n / kSampleRateD;
                    const double w2 = kTwoPiD * (2.0 * f0) * n / kSampleRateD;
                    re1 += out16[i] * std::cos(w1);
                    im1 -= out16[i] * std::sin(w1);
                    re2 += out16[i] * std::cos(w2);
                    im2 -= out16[i] * std::sin(w2);
                }
            }
        }
        return std::hypot(re2, im2) / std::max(std::hypot(re1, im1), 1e-12);
    };
    const double h2_se = measure_h2_h1(AmpModelId::kUsSmallTweed);
    const double h2_pp = measure_h2_h1(AmpModelId::kBrit2203);

    // 3. Verify Cali 2C+ 5-Band GEQ V-Scoop & all 30 models run cleanly without NaN/Inf
    bool all_30_finite = true;
    AgouraTubeAmp amp;
    for (int m = 0; m < static_cast<int>(AmpModelId::kCount); ++m) {
        amp.SetModel(static_cast<AmpModelId>(m));
        alignas(64) float in16[16], out16[16];
        for (int i = 0; i < 16; ++i) in16[i] = 0.25f * std::sin(0.15f * i);
        amp.ProcessSubBlock16(in16, out16);
        for (float y : out16) if (!std::isfinite(y)) all_30_finite = false;
    }

    // Verify Cali 2C+ GEQ V-scoop cuts 750 Hz relative to 80 Hz and 2200 Hz
    amp.SetModel(AmpModelId::kCaliIICPlus);
    auto probe_amp = [&](float freq_hz) {
        amp.Reset();
        double e = 0.0;
        for (int b = 0; b < 96; ++b) {
            alignas(64) float in16[16], out16[16];
            for (int i = 0; i < 16; ++i) in16[i] = 0.05f * std::sin(kTwoPi * freq_hz * (b * 16 + i) / kSampleRate);
            amp.ProcessSubBlock16(in16, out16);
            if (b >= 40) for (float y : out16) e += static_cast<double>(y) * y;
        }
        return std::sqrt(e / (56.0 * 16.0));
    };
    const double g80 = probe_amp(80.0f);
    const double g750 = probe_amp(750.0f);
    const double g2200 = probe_amp(2200.0f);

    std::printf("  R.T. James 10A Noon Flat Span: %.4f dB | SE 5F1 vs PP 2203 H2/H1: %.4f vs %.4f\n",
                james_flat_span, h2_se, h2_pp);
    std::printf("  Cali 2C+ 5-Band GEQ V-Scoop (80 / 750 / 2200 Hz): %.4f / %.4f / %.4f | All 30 Finite: %s\n",
                g80, g750, g2200, all_30_finite ? "YES" : "NO");

    Check(james_flat_span < 0.05, "R.T. James tone stack at 10A noon must be flat (< 0.05 dB span)");
    Check(h2_se > h2_pp * 1.5, "Single-ended 5F1 Champ must exhibit stronger even-order H2/H1 than push-pull 2203");
    Check(g750 < g80 && g750 < g2200, "Cali 2C+ 5-band GEQ default V-scoop must dip 750 Hz");
    Check(all_30_finite, "All 30 catalog amp recipes must produce finite outputs");
}

}  // namespace

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;
    stadium_dsp::EnableFlushToZeroDenormals();

    Test1_ZeroLatencyConvolver2048();
    Test2_EllipticPolyphaseOversampler();
    Test3_AgouraTubeAmpAdaaNrCabZ();
    Test4_Batch16NeuralCloneEngine();
    Test6_KirchhoffRlcAutoZ();
    Test7_PotTapersAndCoupledBrightCap();
    Test8_VictoriaMicroPhysicsAndFmvStack();
    Test9_SpectralMorph12MicsAutoDelayDbu();
    Test11_Expanded30AmpRecipesJames2CPlusSE();
    Test12_ExternalFileImportPipeline();

    if (g_failures == 0) {
        std::printf("\n[ALL MILESTONE 1+2 TESTS PASSED] 0 failures across 10 DSP & circuit suites!\n");
        return 0;
    }
    std::fprintf(stderr, "\n[FAILED] %d assertions failed.\n", g_failures);
    return 1;
}
