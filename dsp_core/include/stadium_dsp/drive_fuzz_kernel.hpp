#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include "stadium_dsp/amp_precision_blocks.hpp"
#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/polyphase_oversampler.hpp"
#include "stadium_dsp/pot_tapers.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// 12 Hardware Drive / Overdrive / Fuzz / Boost Models (Catalog Section 4)
enum class DriveFuzzModel : int32_t {
    kScream808 = 0,       // Ibanez TS808 (230k Auto-Z, 720 Hz op-amp + MA150 + W-Taper)
    kVerminRat,           // Pro Co RAT (LM308 0.3 V/us slew + 1N914 hard shunt + inverted filter)
    kArbitratorFuzz,      // Dallas Arbiter Fuzz Face (10k Auto-Z, AC128 Ge PNP shunt-series)
    kMinotaur,            // Klon Centaur (dual-gang clean/dirty + +18V/-9V + 1N34A Ge ADAA1 + 1.55k active treble)
    kTeemah,              // Paul Cochrane Timmy (transparent flat, pre-clip Bass Cut, 3-way 1N4148, post Treble)
    kHeirApparent,        // Analogman Prince of Tone (3-way Boost / MA856 OD / 1S1588 Dist)
    kCompulsiveDrive,     // Fulltone OCD v1.4 (TL082 + MOSFET 2N7000/1N34A Ge asym + HP/LP 1.85 kHz)
    kBighornFuzz,         // 1973 Violet Ram's Head Big Muff Pi (136k Auto-Z, 4-stage BJT + 3-node MNA 39k/3.3n+39k/12n)
    kTriangleFuzz,        // 1969 V1 Triangle Big Muff Pi (136k Auto-Z, 4-stage BJT + 3-node MNA 22k/4n+22k/10n)
    kKinkyBoost,          // Xotic EP Booster (Maestro EP-3 JFET 2SK117 +18V, DIP Fat/Bright)
    kDerangedMaster,      // 1966 Dallas Rangemaster (10k Auto-Z, OC44 Ge Class-A, 4.7n/10n/47n cap selector)
    kObsidian7000,        // Darkglass Microtubes B7K Ultra (phase-coherent parallel sub + dual CD4049 CMOS)
    kCount = 12,
};

struct DriveFuzzParams {
    bool enabled = false;
    DriveFuzzModel model = DriveFuzzModel::kMinotaur;
    float drive = 0.50f;
    float tone = 0.50f;
    float level = 0.65f;
    float bass_cut = 0.0f;    // Timmy pre-clip bass cut / Obsidian sub blend
    int32_t voice_mode = 0;   // Heir Apparent: 0=Boost,1=OD,2=Dist | OCD: 0=LP,1=HP | Timmy: 0=Sym,1=Asym,2=Comp | Rangemaster: 0=Treb,1=Mid,2=Full
};

inline float GetDriveAutoZOhms(DriveFuzzModel m) noexcept {
    switch (m) {
        case DriveFuzzModel::kArbitratorFuzz:
        case DriveFuzzModel::kDerangedMaster:
            return 10000.0f;
        case DriveFuzzModel::kBighornFuzz:
        case DriveFuzzModel::kTriangleFuzz:
            return 136000.0f;
        case DriveFuzzModel::kScream808:
            return 230000.0f;
        default:
            return 1000000.0f;
    }
}

// Exact 3-Node Kirchhoff MNA Bilinear Big Muff Pi Passive Tone Stack:
//   High-pass arm: Vin -- C1 -- node_H -- R1 -- GND
//   Low-pass arm : Vin -- R2 -- node_L -- C2 -- GND
//   Potentiometer R_pot between node_H and node_L, unloaded wiper at t in [0, 1] (0=Bass/L, 1=Treble/H)
//   V_out / Vin = [(t*R_pot + Z_L)*V_H + ((1-t)*R_pot + Z_H)*V_L] / [R_pot + Z_H + Z_L]
inline BiquadCoeffs ComputeBigMuffToneStackBilinear(double R1, double C1, double R2, double C2,
                                                    double R_pot, double t,
                                                    double fs = kSampleRateD * 4.0) noexcept {
    t = std::max(0.0, std::min(1.0, t));
    const double t1 = R1 * C1;
    const double t2 = R2 * C2;

    // Multiply numerator and denominator by (1 + s*t1)*(1 + s*t2):
    // Den(s) = R_pot*(1+s*t1)*(1+s*t2) + R1*(1+s*t2) + R2*(1+s*t1)
    const double a0_s = R_pot + R1 + R2;
    const double a1_s = R_pot * (t1 + t2) + R1 * t2 + R2 * t1;
    const double a2_s = R_pot * t1 * t2;

    // Num(s) = s*t1*(t*R_pot*(1+s*t2) + R2) + ((1-t)*R_pot*(1+s*t1) + R1)
    const double b0_s = (1.0 - t) * R_pot + R1;
    const double b1_s = t * R_pot * t1 + R2 * t1 + (1.0 - t) * R_pot * t1;  // simplifies to (R_pot + R2)*t1
    const double b2_s = t * R_pot * t1 * t2;

    const double c = 2.0 * fs;
    const double c2 = c * c;
    const double A0 = a0_s + a1_s * c + a2_s * c2;
    const double A1 = 2.0 * (a0_s - a2_s * c2);
    const double A2 = a0_s - a1_s * c + a2_s * c2;
    const double B0 = b0_s + b1_s * c + b2_s * c2;
    const double B1 = 2.0 * (b0_s - b2_s * c2);
    const double B2 = b0_s - b1_s * c + b2_s * c2;
    const double inv = 1.0 / A0;
    return BiquadCoeffs{B0 * inv, B1 * inv, B2 * inv, A1 * inv, A2 * inv};
}

// Exact Shockley 1N34A Germanium Diode ADAA1 Clipper:
//   f_Ge(x) = v_k * asinh(x / v_k)
//   F_Ge(x) = integral_0^x f_Ge(u) du = v_k * (x * asinh(x / v_k) - sqrt(x^2 + v_k^2) + v_k)
class Adaa1GermaniumDiode {
public:
    void Reset() noexcept {
        x_prev_ = 0.0f;
        F_prev_ = 0.0f;
    }

    inline float Process(float x, float vk = 0.24f) noexcept {
        const float F = Antiderivative(x, vk);
        const float dx = x - x_prev_;
        float y = 0.0f;
        if (std::fabs(dx) < 1.0e-5f) {
            const float xm = 0.5f * (x + x_prev_);
            y = vk * std::asinh(xm / vk);
        } else {
            y = (F - F_prev_) / dx;
        }
        x_prev_ = x;
        F_prev_ = F;
        return y;
    }

    static inline double AntiderivativeDouble(double x, double vk) noexcept {
        return vk * (x * std::asinh(x / vk) - std::sqrt(x * x + vk * vk) + vk);
    }

private:
    static inline float Antiderivative(float x, float vk) noexcept {
        return vk * (x * std::asinh(x / vk) - std::sqrt(x * x + vk * vk) + vk);
    }

    float x_prev_ = 0.0f;
    float F_prev_ = 0.0f;
};

class DriveFuzzPedal {
public:
    DriveFuzzPedal() noexcept { SetParams(DriveFuzzParams{}); }

    void Reset() noexcept {
        os4_.Reset();
        pre_hp_.Reset();
        pre_bp_.Reset();
        post_tone_.Reset();
        post_mid_.Reset();
        sub_lp_.Reset();
        sub_hp_.Reset();
        muff_stack_.Reset();
        dc_block_.Reset();
        adaa_ge_.Reset();
        for (auto& a : adaa_si_) a.Reset();
        slew_state_ = 0.0f;
        fuzz_bias_ = 0.0f;
    }

    void SetParams(const DriveFuzzParams& p) noexcept {
        params_ = p;
        ConfigureFilters();
    }

    const DriveFuzzParams& GetParams() const noexcept { return params_; }

    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) out16[i] = in16[i];
            return;
        }

        alignas(64) float up64[64];
        os4_.Upsample16(in16, up64);

        const float drv = Clamp(params_.drive, 0.0f, 1.0f);
        const float lvl = EvalPotTaper(PotTaper::kLog15A, Clamp(params_.level, 0.0f, 1.0f)) * 2.2f;

        for (size_t i = 0; i < 64; ++i) {
            const float x = up64[i];
            float y = x;

            switch (params_.model) {
                case DriveFuzzModel::kScream808: {
                    // 720 Hz non-inverting feedback high-pass + MA150 soft clipping + W-Taper tone
                    const float hp = pre_hp_.Process(x);
                    const float gain = 1.0f + 18.0f * EvalPotTaper(PotTaper::kLog15A, drv);
                    const float clipped = adaa_si_[0].Process(hp * gain) * 0.65f + x * 0.35f;
                    y = post_tone_.Process(clipped);
                    break;
                }
                case DriveFuzzModel::kVerminRat: {
                    // LM308N 0.3 V/us op-amp slew rate limiter @ 192 kHz -> 1N914 hard shunt clip -> inverted filter
                    const float gain = 2.0f + 35.0f * EvalPotTaper(PotTaper::kLog15A, drv);
                    const float target = x * gain;
                    constexpr float kMaxSlewPerSample = 0.42f;
                    const float diff = Clamp(target - slew_state_, -kMaxSlewPerSample, kMaxSlewPerSample);
                    slew_state_ += diff;
                    const float hard = adaa_si_[0].Process(slew_state_ * 1.4f);
                    y = post_tone_.Process(hard);
                    break;
                }
                case DriveFuzzModel::kArbitratorFuzz: {
                    // AC128 Ge PNP 2-transistor shunt-series feedback with dynamic volume cleanup
                    const float gain = 2.5f + 28.0f * drv;
                    fuzz_bias_ += 0.002f * (std::fabs(x) - fuzz_bias_);
                    const float q1 = adaa_ge_.Process(x * gain - 0.32f * fuzz_bias_, 0.22f);
                    const float q2 = adaa_si_[0].Process(q1 * 3.8f + 0.14f) - std::tanh(0.14f);
                    y = post_tone_.Process(q2);
                    break;
                }
                case DriveFuzzModel::kMinotaur: {
                    // Klon Centaur: dual-gang 100kB pot blends high-headroom +18V/-9V (27V) clean
                    // with mid-focused (390..2350 Hz) 1N34A Ge Shockley ADAA1 dirty path + 1.55 kHz active treble
                    const float clean_mix = 1.0f - 0.88f * drv;
                    const float dirty_mix = drv;
                    const float clean_path = x * (1.0f + 0.5f * drv);  // 27V charge-pump headroom (virtually linear!)
                    const float bp = pre_bp_.Process(x);
                    const float dirty_in = bp * (1.5f + 22.0f * drv);
                    const float dirty_path = adaa_ge_.Process(dirty_in, 0.24f) * 1.65f;
                    const float summed = clean_mix * clean_path + dirty_mix * dirty_path;
                    y = post_tone_.Process(summed);
                    break;
                }
                case DriveFuzzModel::kTeemah: {
                    // Paul Cochrane Timmy: truly transparent at drive=0, bass=0, tone=0;
                    // pre-clip bass cut + 3-way 1N4148 quad + post-clip treble cut
                    const float sig = (params_.bass_cut > 0.01f) ? pre_hp_.Process(x) : x;
                    const float gain = 1.0f + 14.0f * EvalPotTaper(PotTaper::kLog15A, drv);
                    float sat = sig;
                    if (drv > 0.005f) {
                        const float driven = sig * gain;
                        if (params_.voice_mode == 1) {
                            // Asymmetrical 2x3 1N4148
                            sat = adaa_si_[0].Process(driven + 0.16f) - std::tanh(0.16f);
                        } else if (params_.voice_mode == 2) {
                            // Compressed 1x1 low-threshold 1N4148
                            sat = adaa_si_[0].Process(driven * 1.45f) * 0.72f;
                        } else {
                            // Symmetrical 2x2
                            sat = adaa_si_[0].Process(driven);
                        }
                        // Soft blend into pure unity at drive -> 0 for exact transparent clean boost
                        const float blend = Clamp(drv * 8.0f, 0.0f, 1.0f);
                        sat = sig * (1.0f - blend) + sat * blend;
                    }
                    y = (params_.tone > 0.01f) ? post_tone_.Process(sat) : sat;
                    break;
                }
                case DriveFuzzModel::kHeirApparent: {
                    // Analogman Prince of Tone 3-way switch: 0=Boost (lifted diodes), 1=MA856 OD, 2=1S1588 Dist
                    const float gain = 1.2f + 16.0f * EvalPotTaper(PotTaper::kLog15A, drv);
                    const float pre = x * gain;
                    if (params_.voice_mode == 0) {
                        // Boost: lifted clipping diodes -> clean JRC4580D rail only
                        y = std::tanh(pre * 0.22f) / 0.22f;
                    } else if (params_.voice_mode == 1) {
                        // OD: high-threshold MA856 soft feedback clip
                        y = adaa_si_[0].Process(pre * 0.75f) * 1.15f;
                    } else {
                        // Dist: MA856 series into 1S1588 hard shunt
                        const float s1 = adaa_si_[0].Process(pre * 1.35f);
                        y = adaa_si_[1].Process(s1 * 2.2f);
                    }
                    y = post_tone_.Process(y);
                    break;
                }
                case DriveFuzzModel::kCompulsiveDrive: {
                    // Fulltone OCD v1.4: TL082 + asymmetric MOSFET 2N7000 / 1N34A Ge + HP (1.85k +6.2 dB) / LP
                    const float gain = 1.6f + 20.0f * EvalPotTaper(PotTaper::kLog15A, drv);
                    const float pre = std::tanh(x * gain * 0.6f) * 1.4f;
                    // Asymmetric positive MOSFET (harder) vs negative Ge diode (softer knee) -> rich H2/H1!
                    const float asym_in = (pre >= 0.0f) ? (pre * 1.35f) : (pre * 0.78f);
                    const float clipped = (pre >= 0.0f)
                        ? adaa_si_[0].Process(asym_in)
                        : adaa_ge_.Process(asym_in, 0.30f) * 1.25f;
                    y = post_tone_.Process(post_mid_.Process(clipped));
                    break;
                }
                case DriveFuzzModel::kBighornFuzz:
                case DriveFuzzModel::kTriangleFuzz: {
                    // Full 4-Stage BJT Big Muff Pi @ 192 kHz:
                    // Q1 Booster -> Q2 Feedback Clip (110 Hz + Miller) -> Q3 Clip -> 3-Node MNA Tone Stack -> Q4 Recovery
                    const float q1 = pre_hp_.Process(x) * (2.5f + 16.0f * drv);
                    const float q2 = adaa_si_[0].Process(q1 * 1.6f);
                    const float q3 = adaa_si_[1].Process(q2 * 3.4f);
                    const float stacked = muff_stack_.Process(q3);
                    y = adaa_si_[2].Process(stacked * 2.8f);  // Q4 recovery stage
                    break;
                }
                case DriveFuzzModel::kKinkyBoost: {
                    // Xotic EP Booster: Maestro EP-3 JFET 2SK117 @ +18V (+3..+20 dB, warm even harmonics)
                    const float boost = DbToLin(3.0f + 17.0f * drv);
                    const float jfet_in = x * boost * 0.25f;
                    const float jfet = jfet_in + 0.08f * jfet_in * jfet_in - 0.03f * jfet_in * jfet_in * jfet_in;
                    y = post_tone_.Process(adaa_si_[0].Process(jfet) * 2.5f);
                    break;
                }
                case DriveFuzzModel::kDerangedMaster: {
                    // 1966 Dallas Rangemaster: OC44 Ge PNP Class-A + 4.7nF(Treble) / 10nF(Mid) / 47nF(Full)
                    const float shaped = pre_hp_.Process(x);
                    const float gain = 2.0f + 14.0f * drv;
                    y = adaa_ge_.Process(shaped * gain + 0.12f, 0.28f) - 0.28f * std::asinh(0.12f / 0.28f);
                    break;
                }
                case DriveFuzzModel::kObsidian7000: {
                    // Darkglass Microtubes B7K Ultra: phase-coherent parallel sub (<200 Hz) + dual CD4049 CMOS
                    const float sub = sub_lp_.Process(x);
                    const float hi = sub_hp_.Process(x);
                    const float c1 = adaa_si_[0].Process(hi * (2.2f + 20.0f * drv));
                    const float c2 = adaa_si_[1].Process(c1 * 2.4f);
                    const float dirt = post_tone_.Process(c2);
                    const float blend = Clamp(0.35f + 0.55f * drv, 0.0f, 1.0f);
                    y = sub * (1.0f - 0.3f * params_.bass_cut) + dirt * blend;
                    break;
                }
                default:
                    break;
            }
            up64[i] = y;
        }

        os4_.Downsample16(up64, out16);
        for (size_t i = 0; i < kSubBlockSize; ++i) {
            out16[i] = dc_block_.Process(out16[i]) * lvl;
        }
    }

private:
    void ConfigureFilters() noexcept {
        constexpr double fs4 = kSampleRateD * 4.0;
        const double tone = Clamp(params_.tone, 0.0f, 1.0f);
        dc_block_.SetCoeffs(DesignHighPass(5.0, 0.707, kSampleRateD));

        switch (params_.model) {
            case DriveFuzzModel::kScream808: {
                pre_hp_.SetCoeffs(DesignHighPass(720.0, 0.707, fs4));
                const double w = static_cast<double>(EvalPotTaper(PotTaper::kWTaper, static_cast<float>(tone)));
                post_tone_.SetCoeffs(DesignLowPass(1200.0 + 4800.0 * w, 0.707, fs4));
                break;
            }
            case DriveFuzzModel::kVerminRat: {
                // Inverted RAT Filter knob (higher = darker)
                const double cut = 12000.0 * std::pow(0.08, tone);
                post_tone_.SetCoeffs(DesignLowPass(cut, 0.707, fs4));
                break;
            }
            case DriveFuzzModel::kMinotaur: {
                pre_bp_.SetCoeffs(DesignAntiCrampedPeak(1050.0, 0.9, 5.5, fs4));
                const double treb_db = (tone - 0.5) * 18.0;  // ±9 dB active treble @ 1.55 kHz
                post_tone_.SetCoeffs(DesignHighShelf(1550.0, 0.707, treb_db, fs4));
                break;
            }
            case DriveFuzzModel::kTeemah: {
                const double hp_hz = 22.0 + 698.0 * static_cast<double>(Clamp(params_.bass_cut, 0.0f, 1.0f));
                pre_hp_.SetCoeffs(DesignHighPass(hp_hz, 0.707, fs4));
                const double lp_hz = 18000.0 * std::pow(0.12, tone);
                post_tone_.SetCoeffs(DesignLowPass(lp_hz, 0.707, fs4));
                break;
            }
            case DriveFuzzModel::kHeirApparent: {
                const double pres_db = (tone - 0.5) * 10.0;
                post_tone_.SetCoeffs(DesignHighShelf(2600.0, 0.707, pres_db, fs4));
                break;
            }
            case DriveFuzzModel::kCompulsiveDrive: {
                // HP switch = +6.2 dB @ 1.85 kHz British peak; LP switch = flat Tweed
                const double hp_db = (params_.voice_mode >= 1) ? 6.2 : 0.3;
                post_mid_.SetCoeffs(DesignAntiCrampedPeak(1850.0, 1.35, hp_db, fs4));
                post_tone_.SetCoeffs(DesignLowPass(2400.0 + 9600.0 * tone, 0.707, fs4));
                break;
            }
            case DriveFuzzModel::kBighornFuzz: {
                pre_hp_.SetCoeffs(DesignHighPass(110.0, 0.707, fs4));
                // 1973 Violet Ram's Head authentic component values: 39k/3.3nF + 39k/12nF
                muff_stack_.SetCoeffs(ComputeBigMuffToneStackBilinear(39000.0, 3.3e-9, 39000.0, 12.0e-9, 100000.0, tone, fs4));
                break;
            }
            case DriveFuzzModel::kTriangleFuzz: {
                pre_hp_.SetCoeffs(DesignHighPass(95.0, 0.707, fs4));
                // 1969 V1 Triangle Big Muff authentic values: 22k/4nF + 22k/10nF
                muff_stack_.SetCoeffs(ComputeBigMuffToneStackBilinear(22000.0, 4.0e-9, 22000.0, 10.0e-9, 100000.0, tone, fs4));
                break;
            }
            case DriveFuzzModel::kDerangedMaster: {
                // Input coupling cap selector into 10k OC44 base: 4.7nF (~1.2kHz), 10nF (~550Hz), 47nF (~120Hz)
                const double hp_hz = (params_.voice_mode == 0) ? 1150.0 : (params_.voice_mode == 1 ? 520.0 : 120.0);
                pre_hp_.SetCoeffs(DesignHighPass(hp_hz, 0.707, fs4));
                break;
            }
            case DriveFuzzModel::kObsidian7000: {
                sub_lp_.SetCoeffs(DesignLowPass(200.0, 0.707, fs4));
                sub_hp_.SetCoeffs(DesignHighPass(200.0, 0.707, fs4));
                const double atk_db = (tone - 0.5) * 10.0 + 3.0;
                post_tone_.SetCoeffs(DesignAntiCrampedPeak(2800.0, 1.3, atk_db, fs4));
                break;
            }
            default: {
                post_tone_.SetCoeffs(DesignLowPass(2200.0 + 10000.0 * tone, 0.707, fs4));
                break;
            }
        }
    }

    DriveFuzzParams params_{};
    PolyphaseOversampler4x os4_;
    BiquadDF2T pre_hp_, pre_bp_, post_tone_, post_mid_, sub_lp_, sub_hp_, muff_stack_, dc_block_;
    Adaa1GermaniumDiode adaa_ge_;
    std::array<Adaa1TanhStage, 3> adaa_si_;
    float slew_state_ = 0.0f;
    float fuzz_bias_ = 0.0f;
};

}  // namespace stadium_dsp
