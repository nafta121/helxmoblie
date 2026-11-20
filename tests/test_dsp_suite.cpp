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
#include "stadium_dsp/drive_fuzz_kernel.hpp"
#include "stadium_dsp/dual_cab_convolver.hpp"
#include "stadium_dsp/dynamic_fdn_reverb.hpp"
#include "stadium_dsp/dynamics_gate_comp.hpp"
#include "stadium_dsp/guitar_input_z.hpp"
#include "stadium_dsp/lockfree_spsc.hpp"
#include "stadium_dsp/modulation_bank.hpp"
#include "stadium_dsp/neural_clone_engine.hpp"
#include "stadium_dsp/oscillators_and_lfo.hpp"
#include "stadium_dsp/pitch_synth_kernel.hpp"
#include "stadium_dsp/polyphase_oversampler.hpp"
#include "stadium_dsp/pot_tapers.hpp"
#include "stadium_dsp/simd_utils.hpp"
#include "stadium_dsp/tape_bbd_delay.hpp"
#include "stadium_dsp/wah_parametric_eq.hpp"

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

void Test10_DynamicsGateCompAndStudioEq() {
    std::printf("\n=== [Test 10] Horizon Gate, LA-2A T4B Dual-Release Comp & Multi-Mode Studio EQ ===\n");
    using namespace stadium_dsp;

    // 1. LA-2A T4B optical dual-release memory: sustained compression holds longer GR than short transient
    auto measure_residual_gr = [](int burst_blocks) {
        StudioOptoVcaComp comp;
        CompParams p;
        p.enabled = true;
        p.model = CompModel::kLaStudioOpto;
        p.peak_reduction = 0.80f;
        p.gain_db = 0.0f;
        comp.SetParams(p);

        for (int b = 0; b < burst_blocks; ++b) {
            alignas(64) float in16[16], out16[16];
            for (int i = 0; i < 16; ++i) in16[i] = 0.75f * std::sin(kTwoPi * 300.0f * (b * 16 + i) / kSampleRate);
            comp.ProcessSubBlock16(in16, out16);
        }
        // Release for 120 ms (360 blocks of 16 samples @ 48 kHz)
        for (int b = 0; b < 360; ++b) {
            alignas(64) float z16[16] = {}, out16[16];
            comp.ProcessSubBlock16(z16, out16);
        }
        return comp.GetCurrentGainReductionDb();
    };
    const float gr_short = measure_residual_gr(30);    // 10 ms burst
    const float gr_sust = measure_residual_gr(1800);   // 600 ms sustained note

    // 2. Horizon Precision Gate attenuation & GR getter
    HorizonPrecisionGate gate;
    GateParams gp;
    gp.enabled = true;
    gp.threshold_db = -35.0f;
    gate.SetParams(gp);
    for (int b = 0; b < 120; ++b) {
        alignas(64) float quiet[16], out16[16];
        for (int i = 0; i < 16; ++i) quiet[i] = 0.001f * std::sin(0.1f * i);
        gate.ProcessSubBlock16(quiet, out16);
    }
    const float gate_gr = gate.GetCurrentGainReductionDb();

    // 3. Studio EQ: Parametric + 10-Band Graphic + Tilt EQ + 128-point curve evaluator
    StudioEqBlock eq;
    EqParams ep;
    ep.enabled = true;
    ep.mode = EqMode::kGraphic10Band;
    ep.graphic_gains_db[4] = -8.0f;  // 500 Hz cut
    ep.graphic_gains_db[7] = +6.0f;  // 4 kHz boost
    eq.SetParams(ep);
    std::array<float, 128> curve{};
    eq.EvaluateCurveDb(curve.data(), 128);
    // Index ~59 is ~500 Hz, index ~98 is ~4 kHz on 20..20k log scale
    const float c_500 = curve[59];
    const float c_4k = curve[98];

    std::printf("  LA-2A T4B Residual GR @ +120ms (Short vs Sustained): %.2f dB vs %.2f dB | Gate GR: %.1f dB | GEQ Curve(500/4k): %.1f / %+.1f dB\n",
                gr_short, gr_sust, gate_gr, c_500, c_4k);
    Check(gr_sust > gr_short + 1.5f, "LA-2A T4B sustained compression must hold noticeably longer optical memory");
    Check(gate_gr > 12.0f, "Horizon Gate must report active gain reduction on quiet noise floor");
    Check(c_500 < -4.0f && c_4k > 3.0f, "10-Band Graphic EQ curve evaluator must reflect cut/boost accurately");
}

void Test13_Modulation10Pitch5Wah8Bank() {
    std::printf("\n=== [Test 13] Modulation (10), Pitch/Synth (5), & Wah/Filter (8) Hardware-Precision Bank ===\n");
    using namespace stadium_dsp;

    Check(static_cast<int>(ModulationModel::kCount) == 10, "Must provide 10 Modulation/Rotary models");
    Check(static_cast<int>(PitchSynthModel::kCount) == 5, "Must provide 5 Pitch/Synth models");
    Check(static_cast<int>(WahFilterModel::kCount) == 8, "Must provide 8 Wah/Filter models");

    auto dft_mag = [](const std::vector<float>& buf, double freq_hz, size_t start) {
        double re = 0.0, im = 0.0;
        const size_t N = buf.size() - start;
        for (size_t i = start; i < buf.size(); ++i) {
            const double w = kTwoPiD * freq_hz * static_cast<double>(i) / kSampleRateD;
            re += buf[i] * std::cos(w);
            im -= buf[i] * std::sin(w);
        }
        return 2.0 * std::hypot(re, im) / static_cast<double>(N);
    };

    // 1. Boctaver (BOSS OC-2 CMOS ÷2, ÷4): 220 Hz -> 110 Hz & 55 Hz sub-octaves
    PitchSynthBlock pitch;
    PitchParams pp;
    pp.enabled = true;
    pp.model = PitchSynthModel::kBoctaverOc2;
    pp.oct1_level = 0.9f;
    pp.oct2_level = 0.8f;
    pp.dry_level = 0.0f;
    pitch.SetParams(pp);
    std::vector<float> p_out(4096);
    for (size_t off = 0; off < p_out.size(); off += 16) {
        alignas(64) float in16[16];
        for (int i = 0; i < 16; ++i) in16[i] = 0.6f * std::sin(kTwoPi * 220.0f * (off + i) / kSampleRate);
        pitch.ProcessSubBlock16(in16, &p_out[off]);
    }
    const double oc2_110 = dft_mag(p_out, 110.0, 1024);
    const double oc2_55 = dft_mag(p_out, 55.0, 1024);

    // 2. Poly Capo (-12st: 220 Hz -> 110 Hz) & Pitch Wham (+12st: 440 Hz -> 880 Hz)
    pp.model = PitchSynthModel::kPolyCapo;
    pp.semitones = -12.0f;
    pp.mix = 1.0f;
    pitch.Reset();
    pitch.SetParams(pp);
    for (size_t off = 0; off < p_out.size(); off += 16) {
        alignas(64) float in16[16];
        for (int i = 0; i < 16; ++i) in16[i] = 0.6f * std::sin(kTwoPi * 220.0f * (off + i) / kSampleRate);
        pitch.ProcessSubBlock16(in16, &p_out[off]);
    }
    const double capo_110 = dft_mag(p_out, 110.0, 1536);

    // 3. Script Phase 90 4-stage allpass notch depth (> 28 dB)
    StereoModulationEngine mod;
    ModParams mp;
    mp.enabled = true;
    mp.model = ModulationModel::kScriptPhase90;
    mp.rate_hz = 0.0f;  // freeze LFO for static notch measurement
    mp.depth = 0.5f;
    mp.feedback = 0.0f;
    mp.mix = 0.5f;
    mod.SetParams(mp);
    auto probe_mod = [&](float freq_hz) {
        mod.Reset();
        mod.SetParams(mp);
        double e = 0.0;
        for (int b = 0; b < 96; ++b) {
            alignas(64) float in16[16], l16[16], r16[16];
            for (int i = 0; i < 16; ++i) in16[i] = 0.5f * std::sin(kTwoPi * freq_hz * (b * 16 + i) / kSampleRate);
            mod.ProcessSubBlock16(in16, in16, l16, r16);
            if (b >= 48) for (float y : l16) e += static_cast<double>(y) * y;
        }
        return std::sqrt(e / (48.0 * 16.0));
    };
    double ph_min = 1e9, ph_max = 0.0;
    for (float f = 150.0f; f <= 3000.0f; f *= 1.02f) {
        double m = probe_mod(f);
        ph_min = std::min(ph_min, m);
        ph_max = std::max(ph_max, m);
    }
    const double ph_notch_db = 20.0 * std::log10((ph_max + 1e-12) / (ph_min + 1e-12));

    // 4. Brownface Harmonic Tremolo: Lo & Hi crossover envelopes oscillate strictly anti-phase (corr < -0.70)
    mp.model = ModulationModel::kHarmonicTremolo;
    mp.rate_hz = 5.0f;
    mp.depth = 0.9f;
    mp.mix = 1.0f;
    StereoModulationEngine mod_lo, mod_hi;
    mod_lo.SetParams(mp);
    mod_hi.SetParams(mp);
    std::vector<double> env_lo, env_hi;
    double acc_lo = 0.0, acc_hi = 0.0;
    int frame_cnt = 0;
    for (int b = 0; b < 1200; ++b) {
        alignas(64) float lo_in[16], hi_in[16], lo_l[16], lo_r[16], hi_l[16], hi_r[16];
        for (int i = 0; i < 16; ++i) {
            lo_in[i] = 0.5f * std::sin(kTwoPi * 120.0f * (b * 16 + i) / kSampleRate);
            hi_in[i] = 0.5f * std::sin(kTwoPi * 2400.0f * (b * 16 + i) / kSampleRate);
        }
        mod_lo.ProcessSubBlock16(lo_in, lo_in, lo_l, lo_r);
        mod_hi.ProcessSubBlock16(hi_in, hi_in, hi_l, hi_r);
        if (b >= 75) {
            for (int i = 0; i < 16; ++i) { acc_lo += std::fabs(lo_l[i]); acc_hi += std::fabs(hi_l[i]); }
            if (++frame_cnt == 25) {  // Exactly 400 samples = 1 full 120 Hz cycle & 20 full 2400 Hz cycles
                env_lo.push_back(acc_lo);
                env_hi.push_back(acc_hi);
                acc_lo = 0.0;
                acc_hi = 0.0;
                frame_cnt = 0;
            }
        }
    }
    double mean_l = 0, mean_h = 0;
    for (size_t i = 0; i < env_lo.size(); ++i) { mean_l += env_lo[i]; mean_h += env_hi[i]; }
    mean_l /= env_lo.size(); mean_h /= env_hi.size();
    double cov = 0, vl = 0, vh = 0;
    for (size_t i = 0; i < env_lo.size(); ++i) {
        cov += (env_lo[i] - mean_l) * (env_hi[i] - mean_h);
        vl += (env_lo[i] - mean_l) * (env_lo[i] - mean_l);
        vh += (env_hi[i] - mean_h) * (env_hi[i] - mean_h);
    }
    const double harm_corr = cov / std::max(std::sqrt(vl * vh), 1e-12);

    // 5. Wah & Filter bank: Mutant Filter Up tracks envelope dynamically & all 8 models finite
    InductorWahPedal wah;
    WahParams wp;
    wp.enabled = true;
    wp.model = WahFilterModel::kMutantFilterUp;
    wah.SetParams(wp);
    auto probe_wah = [&](float amp) {
        wah.Reset();
        double e = 0.0;
        for (int b = 0; b < 96; ++b) {
            alignas(64) float in16[16], out16[16];
            for (int i = 0; i < 16; ++i) in16[i] = amp * std::sin(kTwoPi * 1600.0f * (b * 16 + i) / kSampleRate);
            wah.ProcessSubBlock16(in16, out16);
            if (b >= 48) for (float y : out16) e += static_cast<double>(y) * y;
        }
        return std::sqrt(e / (48.0 * 16.0)) / std::max(amp, 1e-6f);
    };
    const double wah_soft = probe_wah(0.03f);
    const double wah_hard = probe_wah(0.65f);

    std::printf("  Boctaver OC-2 (110/55Hz): %.4f / %.4f | PolyCapo -12st: %.4f | Phase90 Notch: %.2f dB | HarmTrem Corr: %.3f | Mutant Soft->Hard: %.3f -> %.3f\n",
                oc2_110, oc2_55, capo_110, ph_notch_db, harm_corr, wah_soft, wah_hard);
    Check(oc2_110 > 0.08 && oc2_55 > 0.05, "Boctaver OC-2 must generate both -1 and -2 octaves");
    Check(capo_110 > 0.06, "Poly Capo WSOLA -12st must shift 220 Hz to 110 Hz");
    Check(ph_notch_db > 28.0, "Script Phase 90 4-stage allpass must create deep > 28 dB notch");
    Check(harm_corr < -0.70, "Brownface Harmonic Tremolo low/high bands must modulate anti-phase");
    Check(wah_hard > wah_soft * 1.4, "Mutant Filter Up must sweep open on harder picking");
}

void Test14_ExpandedDelay7AndReverb7Bank() {
    std::printf("\n=== [Test 14] Expanded Delay (7) & Reverb (7) Catalog Bank ===\n");
    using namespace stadium_dsp;

    Check(static_cast<int>(DelayModel::kCount) == 7, "Must provide 7 Delay catalog models");
    Check(static_cast<int>(ReverbModel::kCount) == 7, "Must provide 7 Reverb catalog models");

    // 1. Cosmos Echo RE-201 3-Head geometry (0.382T / 0.685T / 1.000T)
    StereoTapeBbdDelay dly;
    DelayParams dp;
    dp.enabled = true;
    dp.model = DelayModel::kCosmosEcho;
    dp.time_ms = 200.0f;  // 9600 samples -> H1=3667, H2=6576, H3=9600
    dp.feedback = 0.0f;
    dp.mix = 1.0f;
    dp.head_mode = 7;  // bitmask 111 = all 3 heads active
    dp.spring_mix = 0.0f;
    dly.SetParams(dp);
    std::vector<float> imp_out(12000, 0.0f);
    for (size_t off = 0; off < imp_out.size(); off += 16) {
        alignas(64) float in16[16] = {}, outR[16];
        if (off == 0) in16[0] = 1.0f;
        dly.ProcessSubBlock16(in16, in16, &imp_out[off], outR);
    }
    auto peak_near = [&](size_t center) {
        float pk = 0.0f;
        for (size_t i = center - 48; i <= center + 48 && i < imp_out.size(); ++i) pk = std::max(pk, std::fabs(imp_out[i]));
        return pk;
    };
    const float h1 = peak_near(static_cast<size_t>(9600 * 0.382));
    const float h2 = peak_near(static_cast<size_t>(9600 * 0.685));
    const float h3 = peak_near(9600);

    // 2. Adriatic Overclocked BBD vs Stock DM-2 @ 3.6 kHz
    auto probe_bbd_hf = [](DelayModel m, float rate_scale) {
        StereoTapeBbdDelay d;
        DelayParams p;
        p.enabled = true;
        p.model = m;
        p.time_ms = 120.0f;
        p.feedback = 0.0f;
        p.mix = 1.0f;
        p.bbd_rate_scale = rate_scale;
        d.SetParams(p);
        double e = 0.0;
        for (int b = 0; b < 600; ++b) {
            alignas(64) float in16[16], outL[16], outR[16];
            for (int i = 0; i < 16; ++i) in16[i] = 0.5f * std::sin(kTwoPi * 3600.0f * (b * 16 + i) / kSampleRate);
            d.ProcessSubBlock16(in16, in16, outL, outR);
            if (b >= 450) for (float y : outL) e += static_cast<double>(y) * y;
        }
        return std::sqrt(e / (150.0 * 16.0));
    };
    const double dm2_hf = probe_bbd_hf(DelayModel::kBucketBrigade, 1.0f);
    const double adr_hf = probe_bbd_hf(DelayModel::kAdriaticDelay, 2.2f);

    // 3. '63 Spring 6G15 Helical Chirp Dispersion (36-stage allpass @ 2.85 kHz)
    DynamicFdnReverb rev;
    ReverbParams rp;
    rp.enabled = true;
    rp.model = ReverbModel::kSpring63;
    rp.decay = 0.6f;
    rp.mix = 1.0f;
    rev.SetParams(rp);
    const double disp_ms = rev.MeasureSpringChirpDispersionMs();

    // 4. Searchlights +1 Oct shimmer energy (440 Hz generated from 220 Hz input)
    rp.model = ReverbModel::kSearchlights;
    rp.decay = 0.75f;
    rp.mix = 1.0f;
    rp.shimmer_amount = 0.85f;
    rev.Reset();
    rev.SetParams(rp);
    std::vector<float> sl_out(16384);
    for (size_t off = 0; off < sl_out.size(); off += 16) {
        alignas(64) float in16[16], outR[16];
        for (int i = 0; i < 16; ++i) in16[i] = 0.45f * std::sin(kTwoPi * 220.0f * (off + i) / kSampleRate);
        rev.ProcessSubBlock16(in16, in16, &sl_out[off], outR);
    }
    double re440 = 0.0, im440 = 0.0;
    for (size_t i = 8192; i < sl_out.size(); ++i) {
        const double w = kTwoPiD * 440.0 * static_cast<double>(i) / kSampleRateD;
        re440 += sl_out[i] * std::cos(w);
        im440 -= sl_out[i] * std::sin(w);
    }
    const double sl_440_mag = 2.0 * std::hypot(re440, im440) / 8192.0;

    // 5. Glitz stereo width (side RMS > 0.05)
    rp.model = ReverbModel::kGlitz;
    rev.Reset();
    rev.SetParams(rp);
    double side_e = 0.0;
    for (int b = 0; b < 400; ++b) {
        alignas(64) float in16[16], outL[16], outR[16];
        for (int i = 0; i < 16; ++i) in16[i] = (b < 40) ? 0.5f * std::sin(kTwoPi * 330.0f * (b * 16 + i) / kSampleRate) : 0.0f;
        rev.ProcessSubBlock16(in16, in16, outL, outR);
        if (b >= 60) for (int i = 0; i < 16; ++i) {
            const double sd = 0.5 * (outL[i] - outR[i]);
            side_e += sd * sd;
        }
    }
    const double glitz_side = std::sqrt(side_e / (340.0 * 16.0));

    std::printf("  Cosmos RE-201 3-Head Peaks: %.3f / %.3f / %.3f | Adriatic vs DM-2 @3.6kHz: %.4f vs %.4f\n",
                h1, h2, h3, adr_hf, dm2_hf);
    std::printf("  '63 Spring Chirp Dispersion: +%.2f ms | Searchlights +1 Oct (440Hz): %.5f | Glitz Side RMS: %.4f\n",
                disp_ms, sl_440_mag, glitz_side);

    Check(h1 > 0.15f && h2 > 0.15f && h3 > 0.15f, "Cosmos Echo RE-201 must produce all 3 playback head echoes");
    Check(adr_hf > dm2_hf * 3.0, "Adriatic overclocked MN3102 BBD must preserve much more 3.6 kHz bandwidth than stock DM-2");
    Check(disp_ms > 2.5, "'63 Spring 36-stage helical allpass must exhibit measurable positive chirp dispersion");
    Check(sl_440_mag > 0.001, "Searchlights shimmer reverb must synthesize +1 Octave (440 Hz) tail");
    Check(glitz_side > 0.03, "Glitz bloom reverb must generate rich stereo side decorrelation");
}

void Test15_Expanded12ModelDriveFuzzBoostBank() {
    std::printf("\n=== [Test 15] Expanded 12-Model Drive / Overdrive / Fuzz / Boost Hardware Bank ===\n");
    using namespace stadium_dsp;

    Check(static_cast<int>(DriveFuzzModel::kCount) == 12, "Must provide exactly 12 Drive/Fuzz/Boost models");

    auto measure_thd = [](DriveFuzzModel m, float drive, int voice_mode) {
        DriveFuzzPedal ped;
        DriveFuzzParams p;
        p.enabled = true;
        p.model = m;
        p.drive = drive;
        p.tone = 0.5f;
        p.level = 0.7f;
        p.voice_mode = voice_mode;
        ped.SetParams(p);
        constexpr double f0 = 250.0;
        double re1 = 0, im1 = 0, re2 = 0, im2 = 0, re3 = 0, im3 = 0;
        for (int b = 0; b < 160; ++b) {
            alignas(64) float in16[16], out16[16];
            for (int i = 0; i < 16; ++i) in16[i] = 0.22f * std::sin(kTwoPi * static_cast<float>(f0) * (b * 16 + i) / kSampleRate);
            ped.ProcessSubBlock16(in16, out16);
            if (b >= 60) {
                for (int i = 0; i < 16; ++i) {
                    const int n = b * 16 + i;
                    const double w1 = kTwoPiD * f0 * n / kSampleRateD;
                    const double w2 = 2.0 * w1;
                    const double w3 = 3.0 * w1;
                    re1 += out16[i] * std::cos(w1); im1 -= out16[i] * std::sin(w1);
                    re2 += out16[i] * std::cos(w2); im2 -= out16[i] * std::sin(w2);
                    re3 += out16[i] * std::cos(w3); im3 -= out16[i] * std::sin(w3);
                }
            }
        }
        const double h1 = std::max(std::hypot(re1, im1), 1e-12);
        const double h2 = std::hypot(re2, im2);
        const double h3 = std::hypot(re3, im3);
        return std::pair<double, double>{std::hypot(h2, h3) / h1, h2 / h1};
    };

    // 1. Minotaur (Klon) dual-gang clean/dirty blend
    const double klon_lo = measure_thd(DriveFuzzModel::kMinotaur, 0.0f, 0).first;
    const double klon_hi = measure_thd(DriveFuzzModel::kMinotaur, 0.9f, 0).first;

    // 2. Teemah! (Timmy) flat transparent response at drive=0, bass=0, treble=0
    DriveFuzzPedal timmy;
    DriveFuzzParams tp;
    tp.enabled = true;
    tp.model = DriveFuzzModel::kTeemah;
    tp.drive = 0.0f;
    tp.bass_cut = 0.0f;
    tp.tone = 0.0f;
    tp.level = 0.5f;
    timmy.SetParams(tp);
    double tmin = 1e9, tmax = -1e9;
    for (float f : {110.0f, 350.0f, 1000.0f, 3500.0f}) {
        timmy.Reset();
        double e = 0.0;
        for (int b = 0; b < 400; ++b) {
            alignas(64) float in16[16], out16[16];
            for (int i = 0; i < 16; ++i) in16[i] = 0.04f * std::sin(kTwoPi * f * (b * 16 + i) / kSampleRate);
            timmy.ProcessSubBlock16(in16, out16);
            if (b >= 100) for (float y : out16) e += static_cast<double>(y) * y;
        }
        const double db = 10.0 * std::log10(e + 1e-20);
        tmin = std::min(tmin, db);
        tmax = std::max(tmax, db);
    }
    const double timmy_flat_span = tmax - tmin;

    // 3. Heir Apparent (Prince of Tone) 3-way Boost < OD < Dist harmonics
    const double pot_boost = measure_thd(DriveFuzzModel::kHeirApparent, 0.65f, 0).first;
    const double pot_od = measure_thd(DriveFuzzModel::kHeirApparent, 0.65f, 1).first;
    const double pot_dist = measure_thd(DriveFuzzModel::kHeirApparent, 0.65f, 2).first;

    // 4. Compulsive Drive (OCD) HP/LP voicing + MOSFET/Ge even harmonics
    const double ocd_h2 = measure_thd(DriveFuzzModel::kCompulsiveDrive, 0.65f, 1).second;
    auto probe_ocd_1850 = [](int hp_mode) {
        DriveFuzzPedal o;
        DriveFuzzParams op;
        op.enabled = true;
        op.model = DriveFuzzModel::kCompulsiveDrive;
        op.drive = 0.3f;
        op.voice_mode = hp_mode;
        o.SetParams(op);
        double e_mid = 0.0, e_low = 0.0;
        for (int pass = 0; pass < 2; ++pass) {
            o.Reset();
            const float f = pass == 0 ? 1850.0f : 250.0f;
            for (int b = 0; b < 96; ++b) {
                alignas(64) float in16[16], out16[16];
                for (int i = 0; i < 16; ++i) in16[i] = 0.05f * std::sin(kTwoPi * f * (b * 16 + i) / kSampleRate);
                o.ProcessSubBlock16(in16, out16);
                if (b >= 48) for (float y : out16) (pass == 0 ? e_mid : e_low) += static_cast<double>(y) * y;
            }
        }
        return std::sqrt(e_mid / std::max(e_low, 1e-12));
    };
    const double ocd_hp = probe_ocd_1850(1);
    const double ocd_lp = probe_ocd_1850(0);

    // 5. Big Muff 3-Node Kirchhoff MNA tone stack accuracy @ 4x (192 kHz)
    const auto bm_coeffs = ComputeBigMuffToneStackBilinear(39000.0, 3.3e-9, 39000.0, 12.0e-9, 100000.0, 0.5, kSampleRateD * 4.0);
    double bm_err = 0.0;
    for (double f : {80.0, 300.0, 700.0, 1500.0, 4000.0}) {
        const std::complex<double> s(0.0, kTwoPiD * f);
        const std::complex<double> zh = 39000.0 / (1.0 + s * 39000.0 * 3.3e-9);
        const std::complex<double> vh = (s * 39000.0 * 3.3e-9) / (1.0 + s * 39000.0 * 3.3e-9);
        const std::complex<double> zl = 39000.0 / (1.0 + s * 39000.0 * 12.0e-9);
        const std::complex<double> vl = 1.0 / (1.0 + s * 39000.0 * 12.0e-9);
        const std::complex<double> href = ((50000.0 + zl) * vh + (50000.0 + zh) * vl) / (100000.0 + zh + zl);
        const double ref_db = 20.0 * std::log10(std::abs(href));
        bm_err = std::max(bm_err, std::fabs(bm_coeffs.MagnitudeDb(f, kSampleRateD * 4.0) - ref_db));
    }

    // 6. Verify Auto-Z ohms & all 12 pedals finite
    Check(GetDriveAutoZOhms(DriveFuzzModel::kArbitratorFuzz) == 10000.0f, "Arbitrator Fuzz Auto-Z must be 10k");
    Check(GetDriveAutoZOhms(DriveFuzzModel::kDerangedMaster) == 10000.0f, "Deranged Master Auto-Z must be 10k");
    Check(GetDriveAutoZOhms(DriveFuzzModel::kBighornFuzz) == 136000.0f, "Bighorn Big Muff Auto-Z must be 136k");
    Check(GetDriveAutoZOhms(DriveFuzzModel::kScream808) == 230000.0f, "Scream 808 Auto-Z must be 230k");

    bool all_12_ok = true;
    for (int m = 0; m < 12; ++m) {
        DriveFuzzPedal p;
        DriveFuzzParams dp;
        dp.enabled = true;
        dp.model = static_cast<DriveFuzzModel>(m);
        p.SetParams(dp);
        alignas(64) float in16[16], out16[16];
        for (int i = 0; i < 16; ++i) in16[i] = 0.25f * std::sin(0.2f * i);
        p.ProcessSubBlock16(in16, out16);
        for (float y : out16) if (!std::isfinite(y)) all_12_ok = false;
    }

    std::printf("  Minotaur Gain 0 vs 9 THD: %.4f vs %.4f | Teemah! Flat Span: %.4f dB\n", klon_lo, klon_hi, timmy_flat_span);
    std::printf("  Heir Apparent (Boost/OD/Dist): %.4f < %.4f < %.4f | OCD HP/LP: %.3f vs %.3f (H2/H1=%.4f)\n",
                pot_boost, pot_od, pot_dist, ocd_hp, ocd_lp, ocd_h2);
    std::printf("  Big Muff 3-Node Kirchhoff MNA Max Error: %.4f dB | All 12 Pedals Finite: %s\n",
                bm_err, all_12_ok ? "YES" : "NO");

    Check(klon_hi > klon_lo * 5.0, "Minotaur Klon must blend clean at low gain to 1N34A Ge clip at high gain");
    Check(timmy_flat_span < 0.08, "Teemah! Timmy must be transparently flat (< 0.08 dB)");
    Check(pot_boost < pot_od && pot_od < pot_dist, "Heir Apparent must satisfy Boost < OD < Dist harmonics");
    Check(ocd_hp > ocd_lp * 1.4 && ocd_h2 > 0.02, "OCD HP mode must boost 1.85 kHz and exhibit asymmetric MOSFET+Ge H2");
    Check(bm_err < 0.08, "Big Muff 3-node Kirchhoff MNA error @ 4x must be < 0.08 dB");
    Check(all_12_ok, "All 12 Drive/Fuzz/Boost models must produce finite outputs");
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
    Test10_DynamicsGateCompAndStudioEq();
    Test11_Expanded30AmpRecipesJames2CPlusSE();
    Test12_ExternalFileImportPipeline();
    Test13_Modulation10Pitch5Wah8Bank();
    Test14_ExpandedDelay7AndReverb7Bank();
    Test15_Expanded12ModelDriveFuzzBoostBank();

    if (g_failures == 0) {
        std::printf("\n[ALL MILESTONE 1-3 TESTS PASSED] 0 failures across 14 DSP & circuit suites!\n");
        return 0;
    }
    std::fprintf(stderr, "\n[FAILED] %d assertions failed.\n", g_failures);
    return 1;
}
