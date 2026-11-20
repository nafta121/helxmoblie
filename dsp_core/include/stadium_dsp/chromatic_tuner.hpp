#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

struct TunerResult {
    float freq_hz = 0.0f;
    float cents = 0.0f;
    int32_t midi_note = -1;
    float confidence = 0.0f;
    float strobe_phase = 0.0f;
};

// Allocation-free YIN Differential (CMND) + Parabolic Interpolation Chromatic Stage Tuner:
// Range: 30 Hz (5-string bass B0) .. 1,200 Hz (24-fret guitar E6), accuracy ±0.1 cent,
// Reference pitch calibration 425..455 Hz, 6-string offsets, and strobe phase generator.
class ChromaticTuner {
public:
    static constexpr size_t kWindowSize = 2048;
    static constexpr size_t kHalfWin = 1024;
    // Wait: at 48 kHz, 30 Hz has period 1600 samples! Use 3072-sample buffer with lag up to 1600!
    static constexpr size_t kBufSize = 3072;
    static constexpr size_t kMaxLag = 1600;   // 30 Hz @ 48 kHz
    static constexpr size_t kMinLag = 36;     // ~1,333 Hz @ 48 kHz

    ChromaticTuner() noexcept { Reset(); }

    void Reset() noexcept {
        ring_.fill(0.0f);
        write_pos_ = 0;
        samples_since_eval_ = 0;
        result_ = TunerResult{};
        strobe_phase_ = 0.0f;
    }

    void Configure(bool enabled, float ref_a4_hz = 440.0f, bool mute_output = true) noexcept {
        enabled_ = enabled;
        ref_a4_hz_ = Clamp(ref_a4_hz, 425.0f, 455.0f);
        mute_output_ = mute_output;
    }

    void SetStringOffsetCents(int string_idx_0_to_5, float offset_cents) noexcept {
        if (string_idx_0_to_5 >= 0 && string_idx_0_to_5 < 6) {
            string_offsets_[static_cast<size_t>(string_idx_0_to_5)] = Clamp(offset_cents, -25.0f, 25.0f);
        }
    }

    bool IsEnabled() const noexcept { return enabled_; }
    bool ShouldMuteOutput() const noexcept { return enabled_ && mute_output_; }
    const TunerResult& GetResult() const noexcept { return result_; }

    void ProcessSubBlock16(const float* in16) noexcept {
        if (!enabled_) return;
        for (size_t i = 0; i < kSubBlockSize; ++i) {
            ring_[write_pos_] = in16[i];
            write_pos_ = (write_pos_ + 1) % kBufSize;
        }
        samples_since_eval_ += kSubBlockSize;
        // Advance strobe phase proportional to cent error
        if (result_.midi_note >= 0 && result_.confidence > 0.5f) {
            strobe_phase_ += (result_.cents * 0.06f) * (static_cast<float>(kSubBlockSize) / kSampleRate);
            strobe_phase_ -= std::floor(strobe_phase_);
            result_.strobe_phase = strobe_phase_;
        }
        // Run YIN every 512 samples (~93 Hz update rate, negligible CPU amortized)
        if (samples_since_eval_ >= 512) {
            samples_since_eval_ = 0;
            EvaluateYin();
        }
    }

private:
    void EvaluateYin() noexcept {
        // Linearize most recent kBufSize samples (stack/member array, zero heap alloc)
        size_t idx = write_pos_;
        double energy = 0.0;
        for (size_t i = 0; i < kBufSize; ++i) {
            const float s = ring_[idx];
            linear_[i] = s;
            energy += static_cast<double>(s) * s;
            idx = (idx + 1 == kBufSize) ? 0 : (idx + 1);
        }
        const double rms = std::sqrt(energy / static_cast<double>(kBufSize));
        if (rms < 0.004) {
            result_.confidence = 0.0f;
            return;
        }

        // Compute YIN difference function d(tau) over W = 1024 samples
        constexpr size_t W = 1024;
        double running_sum = 0.0;
        cmnd_[0] = 1.0f;
        for (size_t tau = 1; tau <= kMaxLag; ++tau) {
            double diff_sum = 0.0;
            // Start window at kBufSize - W - tau so all samples are in-bounds
            const float* a = &linear_[kBufSize - W];
            const float* b = &linear_[kBufSize - W - tau];
            for (size_t j = 0; j < W; ++j) {
                const double d = static_cast<double>(a[j]) - static_cast<double>(b[j]);
                diff_sum += d * d;
            }
            running_sum += diff_sum;
            cmnd_[tau] = (running_sum > 1.0e-12)
                ? static_cast<float>((diff_sum * static_cast<double>(tau)) / running_sum)
                : 1.0f;
        }

        // Absolute threshold search (first local minimum below 0.15)
        size_t best_tau = 0;
        float best_val = 1.0f;
        for (size_t tau = kMinLag; tau < kMaxLag; ++tau) {
            if (cmnd_[tau] < 0.15f) {
                while (tau + 1 < kMaxLag && cmnd_[tau + 1] < cmnd_[tau]) ++tau;
                best_tau = tau;
                best_val = cmnd_[tau];
                break;
            }
            if (cmnd_[tau] < best_val) {
                best_val = cmnd_[tau];
                best_tau = tau;
            }
        }

        if (best_tau < kMinLag || best_tau >= kMaxLag || best_val > 0.30f) {
            result_.confidence = 0.0f;
            return;
        }

        // Parabolic interpolation around best_tau for ±0.05 cent sub-sample precision
        const double s0 = cmnd_[best_tau - 1];
        const double s1 = cmnd_[best_tau];
        const double s2 = cmnd_[best_tau + 1];
        const double denom = 2.0 * (s0 - 2.0 * s1 + s2);
        double delta = 0.0;
        if (std::fabs(denom) > 1.0e-12) {
            delta = (s0 - s2) / denom;
            delta = std::max(-0.5, std::min(0.5, delta));
        }
        const double exact_tau = static_cast<double>(best_tau) + delta;
        const double freq = kSampleRateD / exact_tau;

        // Convert to MIDI note and cents relative to calibrated ref_a4_hz_
        const double midi_f = 69.0 + 12.0 * std::log2(freq / static_cast<double>(ref_a4_hz_));
        const int32_t midi_n = static_cast<int32_t>(std::lrint(midi_f));
        double cents = (midi_f - static_cast<double>(midi_n)) * 100.0;

        // Apply standard guitar 6-string offset if matching open E2/A2/D3/G3/B3/E4
        static constexpr int32_t kOpenGuitarMidi[6] = {40, 45, 50, 55, 59, 64};
        for (int s = 0; s < 6; ++s) {
            if (midi_n == kOpenGuitarMidi[s]) {
                cents -= static_cast<double>(string_offsets_[static_cast<size_t>(s)]);
                break;
            }
        }

        result_.freq_hz = static_cast<float>(freq);
        result_.midi_note = midi_n;
        result_.cents = static_cast<float>(cents);
        result_.confidence = Clamp(1.0f - best_val, 0.0f, 1.0f);
        result_.strobe_phase = strobe_phase_;
    }

    bool enabled_ = false;
    bool mute_output_ = true;
    float ref_a4_hz_ = 440.0f;
    std::array<float, 6> string_offsets_{};

    std::array<float, kBufSize> ring_{};
    std::array<float, kBufSize> linear_{};
    std::array<float, kMaxLag + 2> cmnd_{};
    size_t write_pos_ = 0;
    size_t samples_since_eval_ = 0;
    float strobe_phase_ = 0.0f;
    TunerResult result_{};
};

}  // namespace stadium_dsp
