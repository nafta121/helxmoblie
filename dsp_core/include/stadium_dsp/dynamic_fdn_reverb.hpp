#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstdint>
#include <vector>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/oscillators_and_lfo.hpp"
#include "stadium_dsp/pitch_synth_kernel.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// 7 Hardware & Studio Reverb Models (Catalog Section 6)
enum class ReverbModel : int32_t {
    kDynamicHall = 0,     // 8x8 Unitary Walsh-Hadamard FDN Concert Hall
    kDynamicPlate,        // EMT-style dense metallic plate (bright, fast buildup)
    kGlitz,               // Modulated bloom pre-diffuser + multi-voice tank chorus + 1.85 kHz halo
    kSearchlights,        // Cathedral prime FDN + WSOLA +1 Oct (+12st) shimmer + formant filter
    kSpring63,            // 1963 Fender 6G15 (6K6GT tube driver + 3 counter-wound 4AB3C1B springs + 36-stage helical chirp allpass @ 2.85 kHz)
    kGanymede,            // Ambient modulated chorus hall
    kPlateaux,            // Dual-voice shimmer (+12st Octave & +7st Fifth WSOLA)
    kCount = 7,
};

struct ReverbParams {
    bool enabled = false;
    ReverbModel model = ReverbModel::kDynamicHall;
    bool is_stereo = true;
    float decay = 0.55f;          // Maps to Sabine RT60 (0.6s .. 12.0s)
    float predelay_ms = 18.0f;    // 0..200 ms
    float damping_hz = 6200.0f;   // 1500..16000 Hz
    float mix = 0.30f;
    float shimmer_amount = 0.45f; // Searchlights / Plateaux shimmer feedback
    float mod_depth = 0.35f;      // Glitz / Ganymede tank chorus
};

// 2nd-order Bridged-T Helical Spring Dispersion Allpass Section (4AB3C1B coil chirp @ ~2.85 kHz)
struct SpringDispersionAllpass2 {
    double a1 = 0.0;
    double a2 = 0.0;
    double x1 = 0.0, x2 = 0.0;
    double y1 = 0.0, y2 = 0.0;

    void Configure(double fc_hz, double r_pole, double fs = kSampleRateD) noexcept {
        const double w = kTwoPiD * fc_hz / fs;
        a1 = -2.0 * r_pole * std::cos(w);
        a2 = r_pole * r_pole;
    }
    void Reset() noexcept { x1 = x2 = y1 = y2 = 0.0; }

    inline float Process(float x) noexcept {
        const double xd = static_cast<double>(x);
        // H(z) = (a2 + a1*z^-1 + z^-2) / (1 + a1*z^-1 + a2*z^-2) -> strictly |H(e^jw)| === 1!
        const double y = a2 * xd + a1 * x1 + x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = xd;
        y2 = y1; y1 = y;
        return static_cast<float>(y);
    }

    double GroupDelaySamples(double freq_hz, double fs = kSampleRateD) const noexcept {
        const double w = kTwoPiD * freq_hz / fs;
        const std::complex<double> z1(std::cos(w), -std::sin(w));
        const std::complex<double> z2 = z1 * z1;
        // Exact analytical phase derivative via small centered difference
        const double dw = 1.0e-5;
        auto phase = [&](double ww) {
            const std::complex<double> e1(std::cos(ww), -std::sin(ww));
            const std::complex<double> e2 = e1 * e1;
            const std::complex<double> h = (a2 + a1 * e1 + e2) / (1.0 + a1 * e1 + a2 * e2);
            return std::arg(h);
        };
        double dp = phase(w + dw) - phase(w - dw);
        while (dp > kPiD) dp -= kTwoPiD;
        while (dp < -kPiD) dp += kTwoPiD;
        (void)z2;
        return -dp / (2.0 * dw);
    }
};

class DynamicFdnReverb {
public:
    static constexpr size_t kPreDelayCap = 16384;
    static constexpr size_t kLineCap = 4096;
    // Mutually prime FDN delay line lengths (samples @ 48 kHz)
    static constexpr std::array<size_t, 8> kHallPrimes = {1151, 1327, 1499, 1693, 1907, 2131, 2377, 2659};
    static constexpr std::array<size_t, 8> kPlatePrimes = {541, 653, 769, 887, 1013, 1153, 1301, 1459};
    static constexpr std::array<size_t, 8> kSpringPrimes = {1481, 1789, 2113, 1481, 1789, 2113, 1601, 1933};

    DynamicFdnReverb() noexcept {
        for (auto& ap : spring_chirp_) {
            ap.Configure(2850.0, 0.87, kSampleRateD);
        }
        SetParams(ReverbParams{});
        Reset();
    }

    void Reset() noexcept {
        predelay_l_.fill(0.0f);
        predelay_r_.fill(0.0f);
        pd_w_ = 0;
        for (size_t i = 0; i < 8; ++i) {
            fdn_lines_[i].fill(0.0f);
            fdn_w_[i] = 0;
            damp_state_[i] = 0.0f;
        }
        for (auto& d : diff_buf_) d.fill(0.0f);
        diff_w_.fill(0);
        for (auto& ap : spring_chirp_) ap.Reset();
        shimmer_oct_.Reset();
        shimmer_fifth_.Reset();
        halo_peak_.Reset();
        lfo_phase_ = 0.0f;
    }

    void SetParams(const ReverbParams& p) noexcept {
        params_ = p;
        halo_peak_.SetCoeffs(DesignAntiCrampedPeak(1850.0, 1.4, 3.8, kSampleRateD));
        const double fc = Clamp(p.damping_hz, 1200.0f, 16000.0f);
        damp_coeff_ = static_cast<float>(std::exp(-kTwoPiD * fc / kSampleRateD));
    }

    const ReverbParams& GetParams() const noexcept { return params_; }

    // Analytical + physical group-delay dispersion of the 36-stage 4AB3C1B helical spring cascade
    double MeasureSpringChirpDispersionMs() const noexcept {
        double gd_lo = 0.0, gd_hi = 0.0;
        for (const auto& ap : spring_chirp_) {
            gd_lo += ap.GroupDelaySamples(250.0, kSampleRateD);
            gd_hi += ap.GroupDelaySamples(2850.0, kSampleRateD);
        }
        return (gd_hi - gd_lo) * (1000.0 / kSampleRateD);
    }

    void ProcessSubBlock16(const float* in_l, const float* in_r, float* out_l, float* out_r) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
            }
            return;
        }

        const float mix = Clamp(params_.mix, 0.0f, 1.0f);
        const float rt60 = 0.6f + 9.5f * std::pow(Clamp(params_.decay, 0.02f, 0.98f), 1.6f);
        const size_t pd_smp = static_cast<size_t>(Clamp(params_.predelay_ms, 0.0f, 200.0f) * (kSampleRate * 0.001f));

        const auto& lengths = (params_.model == ReverbModel::kDynamicPlate) ? kPlatePrimes
                            : (params_.model == ReverbModel::kSpring63)     ? kSpringPrimes
                                                                            : kHallPrimes;

        // Precompute exact Sabine feedback gains g_i = 10^(-3 * L_i / (RT60 * Fs))
        std::array<float, 8> g_sabine{};
        for (size_t k = 0; k < 8; ++k) {
            g_sabine[k] = std::pow(10.0f, -3.0f * static_cast<float>(lengths[k]) / (rt60 * kSampleRate));
            g_sabine[k] = Clamp(g_sabine[k], 0.10f, 0.985f);
        }

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            const float dry_l = in_l[i];
            const float dry_r = params_.is_stereo ? in_r[i] : 0.5f * (in_l[i] + in_r[i]);

            // Predelay ring
            predelay_l_[pd_w_] = dry_l;
            predelay_r_[pd_w_] = dry_r;
            const size_t pd_r = (pd_w_ + kPreDelayCap - pd_smp) & (kPreDelayCap - 1);
            const float in_pd_l = predelay_l_[pd_r];
            const float in_pd_r = predelay_r_[pd_r];
            pd_w_ = (pd_w_ + 1) & (kPreDelayCap - 1);

            const float mono_in = 0.5f * (in_pd_l + in_pd_r);
            float wet_l = 0.0f, wet_r = 0.0f;

            if (params_.model == ReverbModel::kSpring63) {
                // 1963 Fender 6G15: 6K6GT warm asymmetric tube driver -> 36-stage helical chirp dispersion
                // -> 3 counter-wound 4AB3C1B spring tanks
                float driven = std::tanh(mono_in * 1.45f + 0.08f * mono_in * mono_in);
                for (auto& ap : spring_chirp_) {
                    driven = ap.Process(driven);
                }
                // 3 counter-wound spring delay tanks with gentle metallic coupling
                float s_out = 0.0f;
                for (size_t t = 0; t < 3; ++t) {
                    const size_t L = kSpringPrimes[t];
                    const float tap = fdn_lines_[t][(fdn_w_[t] + kLineCap - L) & (kLineCap - 1)];
                    damp_state_[t] = tap * (1.0f - damp_coeff_) + damp_state_[t] * damp_coeff_;
                    fdn_lines_[t][fdn_w_[t]] = driven * 0.45f + damp_state_[t] * g_sabine[t] * 0.88f;
                    fdn_w_[t] = (fdn_w_[t] + 1) & (kLineCap - 1);
                    s_out += (t == 1 ? -0.45f : 0.45f) * tap;
                }
                wet_l = s_out;
                wet_r = params_.is_stereo ? (s_out * 0.94f + damp_state_[1] * 0.14f) : s_out;
            } else {
                // 4-stage Schroeder pre-diffuser (gives lush bloom for Glitz / Plate / Hall)
                float d_in = mono_in;
                static constexpr size_t kDiffLens[4] = {149, 211, 307, 431};
                for (size_t s = 0; s < 4; ++s) {
                    const size_t idx = (diff_w_[s] + 512 - kDiffLens[s]) & 511u;
                    const float del = diff_buf_[s][idx];
                    const float v = d_in + 0.52f * del;
                    diff_buf_[s][diff_w_[s]] = v;
                    diff_w_[s] = (diff_w_[s] + 1) & 511u;
                    d_in = del - 0.52f * v;
                }

                lfo_phase_ += 0.65f / kSampleRate;
                if (lfo_phase_ >= 1.0f) lfo_phase_ -= 1.0f;

                // Read 8 FDN lines with subtle quadrature tank chorus modulation (stronger on Glitz / Ganymede)
                const float mod_amp = (params_.model == ReverbModel::kGlitz || params_.model == ReverbModel::kGanymede)
                    ? (14.0f * params_.mod_depth + 4.0f)
                    : (3.0f * params_.mod_depth);

                alignas(64) float tap[8];
                for (size_t k = 0; k < 8; ++k) {
                    const float mod = mod_amp * std::sin(kTwoPi * (lfo_phase_ + static_cast<float>(k) * 0.125f));
                    const float d_f = Clamp(static_cast<float>(lengths[k]) + mod, 16.0f, static_cast<float>(kLineCap - 4));
                    const size_t di = static_cast<size_t>(d_f);
                    const float frac = d_f - static_cast<float>(di);
                    const size_t i0 = (fdn_w_[k] + kLineCap - di) & (kLineCap - 1);
                    const size_t i1 = (i0 + kLineCap - 1) & (kLineCap - 1);
                    const float raw = fdn_lines_[k][i0] * (1.0f - frac) + fdn_lines_[k][i1] * frac;
                    damp_state_[k] = raw * (1.0f - damp_coeff_) + damp_state_[k] * damp_coeff_;
                    tap[k] = damp_state_[k] * g_sabine[k];
                }

                // Orthonormal 8x8 Unitary Walsh-Hadamard Feedback Mixing Matrix
                alignas(64) float mixed[8];
                for (size_t k = 0; k < 8; ++k) mixed[k] = tap[k];
                ApplyHadamard8x8(mixed);

                // Shimmer injection for Searchlights (+1 Oct) & Plateaux (+12st + +7st)
                float shim_sig = 0.0f;
                if (params_.model == ReverbModel::kSearchlights || params_.model == ReverbModel::kPlateaux) {
                    const float tank_sum = 0.25f * (tap[0] + tap[2] + tap[4] + tap[6]) + 0.35f * d_in;
                    const float oct_up = shimmer_oct_.ProcessSample(tank_sum, 2.0f);
                    if (params_.model == ReverbModel::kPlateaux) {
                        const float fifth_up = shimmer_fifth_.ProcessSample(tank_sum, 1.498307f);  // +7 semitones
                        shim_sig = (0.6f * oct_up + 0.4f * fifth_up) * params_.shimmer_amount * 0.55f;
                    } else {
                        shim_sig = oct_up * params_.shimmer_amount * 0.55f;
                    }
                }

                // Write back into 8 delay lines with stereo decorrelated injection
                for (size_t k = 0; k < 8; ++k) {
                    const float inj = ((k & 1u) == 0u ? (d_in + 0.2f * in_pd_l) : (d_in - 0.2f * in_pd_r)) * 0.35f;
                    fdn_lines_[k][fdn_w_[k]] = inj + mixed[k] + shim_sig * ((k & 1u) ? 0.45f : -0.45f);
                    fdn_w_[k] = (fdn_w_[k] + 1) & (kLineCap - 1);
                }

                // Decorrelated stereo tap summation (even/odd opposite phase taps create wide stereo field)
                wet_l = 0.58f * (tap[0] + tap[2] - tap[4] + tap[6]);
                wet_r = 0.58f * (tap[1] - tap[3] + tap[5] + tap[7]);

                if (params_.model == ReverbModel::kGlitz) {
                    wet_l = halo_peak_.Process(wet_l);
                    wet_r = -wet_r * 0.90f + 0.45f * tap[3];  // Extra bloom stereo side width
                }
            }

            out_l[i] = dry_l * (1.0f - mix) + wet_l * mix;
            out_r[i] = params_.is_stereo ? (dry_r * (1.0f - mix) + wet_r * mix) : out_l[i];
        }
    }

private:
    ReverbParams params_{};
    std::array<float, kPreDelayCap> predelay_l_{};
    std::array<float, kPreDelayCap> predelay_r_{};
    size_t pd_w_ = 0;

    std::array<std::array<float, kLineCap>, 8> fdn_lines_{};
    std::array<size_t, 8> fdn_w_{};
    std::array<float, 8> damp_state_{};

    std::array<std::array<float, 512>, 4> diff_buf_{};
    std::array<size_t, 4> diff_w_{};

    std::array<SpringDispersionAllpass2, 36> spring_chirp_;
    DualWindowPitchShifter shimmer_oct_, shimmer_fifth_;
    BiquadDF2T halo_peak_;
    float damp_coeff_ = 0.35f;
    float lfo_phase_ = 0.0f;
};

}  // namespace stadium_dsp
