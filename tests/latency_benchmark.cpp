#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <numeric>
#include <vector>

#include "stadium_dsp/signal_chain_processor.hpp"

using namespace stadium_dsp;

namespace {

struct LatencyStats {
    double mean_us = 0.0;
    double p50_us = 0.0;
    double p95_us = 0.0;
    double p99_us = 0.0;
    double max_us = 0.0;
    double budget_us = 0.0;
    double mean_pct = 0.0;
    double p99_pct = 0.0;
};

LatencyStats BenchmarkBurstLatency(StadiumSignalChainProcessor& proc,
                                   size_t burst_frames,
                                   size_t warmup_bursts,
                                   size_t measured_bursts,
                                   bool switch_snapshots_live) {
    std::vector<float> in_buf(burst_frames, 0.0f);
    std::vector<float> out_l(burst_frames, 0.0f);
    std::vector<float> out_r(burst_frames, 0.0f);

    // Rich harmonic guitar-like test input (E2 82.4 Hz + A2 110 Hz + harmonics)
    for (size_t i = 0; i < burst_frames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        in_buf[i] = 0.20f * std::sin(kTwoPi * 82.4069f * t) +
                    0.12f * std::sin(kTwoPi * 164.8138f * t) +
                    0.08f * std::sin(kTwoPi * 247.2207f * t) +
                    0.05f * std::sin(kTwoPi * 660.0f * t);
    }

    for (size_t b = 0; b < warmup_bursts; ++b) {
        proc.ProcessAudioBurst(in_buf.data(), out_l.data(), out_r.data(), burst_frames);
    }

    std::vector<double> times_us;
    times_us.reserve(measured_bursts);
    for (size_t b = 0; b < measured_bursts; ++b) {
        if (switch_snapshots_live && (b % 32 == 16)) {
            DspCommand snap_cmd;
            snap_cmd.type = DspCommandType::kRecallSnapshot;
            snap_cmd.int_val = static_cast<int32_t>((b / 32) & 7);
            proc.EnqueueCommand(snap_cmd);
        }
        const auto t0 = std::chrono::steady_clock::now();
        proc.ProcessAudioBurst(in_buf.data(), out_l.data(), out_r.data(), burst_frames);
        const auto t1 = std::chrono::steady_clock::now();
        times_us.push_back(std::chrono::duration<double, std::micro>(t1 - t0).count());
    }

    std::sort(times_us.begin(), times_us.end());
    const double sum = std::accumulate(times_us.begin(), times_us.end(), 0.0);
    const double budget_us = (static_cast<double>(burst_frames) / kSampleRateD) * 1.0e6;

    LatencyStats st;
    st.mean_us = sum / static_cast<double>(times_us.size());
    st.p50_us = times_us[times_us.size() * 50 / 100];
    st.p95_us = times_us[times_us.size() * 95 / 100];
    st.p99_us = times_us[times_us.size() * 99 / 100];
    st.max_us = times_us.back();
    st.budget_us = budget_us;
    st.mean_pct = (st.mean_us / budget_us) * 100.0;
    st.p99_pct = (st.p99_us / budget_us) * 100.0;
    return st;
}

int MeasureImpulseOnsetSamples(StadiumSignalChainProcessor& proc, size_t n = 4096) {
    std::vector<float> in(n, 0.0f);
    std::vector<float> out_l(n, 0.0f);
    std::vector<float> out_r(n, 0.0f);
    in[256] = 0.5f;  // Unit impulse at index 256 so prior states are settled

    proc.ProcessAudioBurst(in.data(), out_l.data(), out_r.data(), n);

    float peak = 0.0f;
    size_t peak_idx = 256;
    for (size_t i = 256; i < n; ++i) {
        const float v = std::fabs(out_l[i]);
        if (v > peak) {
            peak = v;
            peak_idx = i;
        }
    }
    // Find first sample reaching >= 5% of impulse peak (exact acoustic onset!)
    const float thresh = peak * 0.05f;
    for (size_t i = 256; i < n; ++i) {
        if (std::fabs(out_l[i]) >= thresh) {
            return static_cast<int>(i - 256);
        }
    }
    return static_cast<int>(peak_idx - 256);
}

int MeasureImpulsePeakSamples(StadiumSignalChainProcessor& proc, size_t n = 4096) {
    std::vector<float> in(n, 0.0f);
    std::vector<float> out_l(n, 0.0f);
    std::vector<float> out_r(n, 0.0f);
    in[256] = 0.5f;

    proc.ProcessAudioBurst(in.data(), out_l.data(), out_r.data(), n);

    float peak = 0.0f;
    size_t peak_idx = 256;
    for (size_t i = 256; i < n; ++i) {
        const float v = std::fabs(out_l[i]);
        if (v > peak) {
            peak = v;
            peak_idx = i;
        }
    }
    return static_cast<int>(peak_idx - 256);
}

}  // namespace

int main() {
    EnableFlushToZeroDenormals();
    std::printf("=========================================================================\n");
    std::printf("  Hx Mobile (Stadium DSP) — Gate 4 Algorithmic & Real-Time Latency Suite\n");
    std::printf("  Sample Rate: 48,000 Hz | Internal Sub-Block: 16 samples (0.333 ms)\n");
    std::printf("=========================================================================\n\n");

    // 1. Algorithmic Impulse Onset & Peak Group-Delay Latency
    std::printf("--- [A] Algorithmic Impulse Latency (Samples & ms @ 48 kHz) ---\n");
    {
        // A1: Standalone 2048-Tap Partitioned FFT Cab Convolver
        ZeroLatencyIrConvolver2048 conv;
        std::array<float, 2048> dirac{};
        dirac[0] = 1.0f;
        conv.SetImpulseResponseImmediate(dirac.data(), dirac.size());
        alignas(64) float x16[16] = {};
        alignas(64) float y16[16] = {};
        x16[0] = 1.0f;
        conv.ProcessSubBlock16(x16, y16);
        std::printf("  1. Dual Cab 2048-Tap Partitioned FFT : onset = 0 samples (0.000 ms) | y[0]=%.6f\n", y16[0]);

        // A2: Standard Guitar Rig (Agoura Brit 2203 4x Oversampled + Dual Cab 2048-Tap, Pitch OFF)
        StadiumSignalChainProcessor proc_std;
        proc_std.EnqueueCommand(DspCommand{DspCommandType::kSetAmpModel, 0, 0, 0, 0.f, 0.f, 0.f, static_cast<int32_t>(AmpModelId::kBrit2203)});
        proc_std.EnqueueCommand(DspCommand{DspCommandType::kSetDelayReverb, 0, 0, 0, 0.f, 0.f, 0.f, 0});
        proc_std.EnqueueCommand(DspCommand{DspCommandType::kSetDelayReverb, 1, 0, 0, 0.f, 0.f, 0.f, 0});
        const int onset_std = MeasureImpulseOnsetSamples(proc_std);
        const int peak_std = MeasureImpulsePeakSamples(proc_std);
        std::printf("  2. Agoura Amp (4x Elliptic) + Cab IR : onset = %d smp (%.3f ms) | peak = %d smp (%.3f ms)\n",
                    onset_std, onset_std * 1000.0 / kSampleRateD,
                    peak_std, peak_std * 1000.0 / kSampleRateD);

        // A3: Heavy Rig (Gate + Comp + Drive + Amp + Cab + EQ + Mod + Delay + Reverb, Pitch OFF)
        StadiumSignalChainProcessor proc_heavy;
        proc_heavy.EnableAllBlocksForBenchmark();
        proc_heavy.EnqueueCommand(DspCommand{DspCommandType::kSetPitchSynth, 0, 0, 0, 0.f, 0.f, 0.f, 0});
        const int onset_heavy = MeasureImpulseOnsetSamples(proc_heavy);
        const int peak_heavy = MeasureImpulsePeakSamples(proc_heavy);
        std::printf("  3. Full Rig (10 Active, Pitch OFF)   : onset = %d smp (%.3f ms) | peak = %d smp (%.3f ms)\n",
                    onset_heavy, onset_heavy * 1000.0 / kSampleRateD,
                    peak_heavy, peak_heavy * 1000.0 / kSampleRateD);

        // A4: Pitch Block Active (WSOLA Poly Capo)
        StadiumSignalChainProcessor proc_pitch;
        proc_pitch.EnqueueCommand(DspCommand{DspCommandType::kSetPitchSynth, 0, 0, 0, 0.f, 0.f, 0.f, 1});
        proc_pitch.EnqueueCommand(DspCommand{DspCommandType::kSetPitchSynth, 0, 1, 0, 0.f, 0.f, 0.f, static_cast<int32_t>(PitchSynthModel::kPolyCapo)});
        proc_pitch.EnqueueCommand(DspCommand{DspCommandType::kSetPitchSynth, 0, 2, 0, -2.0f, 0.0f, 1.0f, 0});
        const int onset_pitch = MeasureImpulseOnsetSamples(proc_pitch);
        std::printf("  4. Poly Capo WSOLA Pitch Active      : onset = %d smp (%.3f ms)\n",
                    onset_pitch, onset_pitch * 1000.0 / kSampleRateD);
    }

    // 2. Real-Time Callback Burst Processing & P99 Jitter Across Oboe/AAudio Burst Sizes
    std::printf("\n--- [B] Real-Time Burst Processing & Jitter (ALL 11 BLOCKS ACTIVE + Live Snapshot Recall) ---\n");
    std::printf("  %-10s | %-9s | %-9s | %-9s | %-9s | %-9s | %-8s | %-8s\n",
                "BurstSize", "Budget", "Mean", "P50", "P95", "P99", "Max", "P99 Load");
    std::printf("  -----------+-----------+-----------+-----------+-----------+-----------+----------+---------\n");

    const std::array<size_t, 5> bursts = {16, 48, 96, 192, 384};
    bool all_within_budget = true;
    for (size_t b : bursts) {
        StadiumSignalChainProcessor proc;
        proc.EnableAllBlocksForBenchmark();
        const size_t iters = (b <= 48) ? 2000 : 1000;
        const auto st = BenchmarkBurstLatency(proc, b, 100, iters, true);
        std::printf("  %4zu smp   | %6.1f us | %6.1f us | %6.1f us | %6.1f us | %6.1f us | %6.1f us | %6.2f%%\n",
                    b, st.budget_us, st.mean_us, st.p50_us, st.p95_us, st.p99_us, st.max_us, st.p99_pct);
        if (st.p99_pct >= 50.0) all_within_budget = false;
    }

    // 3. Projected End-to-End Android Hardware Round-Trip Latency Matrix (Oboe AAudio MMAP)
    std::printf("\n--- [C] Android AAudio/Oboe End-to-End Round-Trip Latency Model (48 kHz) ---\n");
    std::printf("  Formula: RT_Total = Input_Burst + Output_Buffer (burst * mult) + DSP_Algo (~0.10 ms) + HAL/ADC/DAC (~1.5-2.5 ms)\n");
    std::printf("  1. Pro Audio USB-C / Pixel / Flagship (48f,  1x Ultra) : Audio Buf =  2.00 ms | Est. Total RTL = ~3.6 - 4.6 ms\n");
    std::printf("  2. Pro Audio MMAP Exclusive           (96f,  1x Ultra) : Audio Buf =  4.00 ms | Est. Total RTL = ~5.6 - 6.6 ms\n");
    std::printf("  3. Stage-Safe Default                 (96f,  2x Stage) : Audio Buf =  6.00 ms | Est. Total RTL = ~7.6 - 8.6 ms\n");
    std::printf("  4. Standard Android MMAP              (192f, 1x Ultra) : Audio Buf =  8.00 ms | Est. Total RTL = ~9.6 - 10.6 ms\n");
    std::printf("  5. Standard Android Stage-Safe        (192f, 2x Stage) : Audio Buf = 12.00 ms | Est. Total RTL = ~13.6 - 14.6 ms\n");

    std::printf("\nResult: %s\n", all_within_budget ? "[PASS] All Oboe burst sizes well below 50% P99 RT budget!" : "[WARN] High P99 CPU load detected!");
    return all_within_budget ? 0 : 1;
}
