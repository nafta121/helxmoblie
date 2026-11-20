#pragma once

#include <algorithm>
#include <array>
#include <cmath>

#include "stadium_dsp/amp_precision_blocks.hpp"
#include "stadium_dsp/amp_recipe_bank.hpp"
#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/polyphase_oversampler.hpp"
#include "stadium_dsp/pot_tapers.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

struct AmpParams {
    float drive = 0.50f;
    float bass = 0.50f;
    float mid = 0.50f;
    float treble = 0.50f;
    float presence = 0.50f;
    float master = 0.55f;
    float ch_vol = 0.70f;
    float sag = 0.50f;
    float ripple = 0.25f;
    float bias = 0.50f;
    float bias_x = 0.50f;
    float hype = 0.0f;       // 0.0 = Stock hardware calibration, 1.0 = Idealized studio Hype
    bool bright_switch = true;
    float deep = 0.50f;      // VH4 Deep / Bass Resonance
    float cut = 0.25f;       // Vox AC30 Cut
    float geq_v_scoop = 0.75f;  // Cali 2C+ 5-Band GEQ V-scoop intensity
};

// Clean-Room Agoura-style Tube Amplifier Engine:
// - Coupled Bright-Cap Kirchhoff divider
// - 4x Elliptic Polyphase Oversampled triode stages (ADAA1 + damped Newton-Raphson Cathode Follower)
// - Victoria/T11 micro-physics (Half-Wave Cathode Bypass, Grid Bias Excursion, Class-B Crossover, 31% Duty Ripple)
// - Exact float64 3rd-order FMV or 4th-order R.T. James Tone Stack
// - Bidirectional Reactive Speaker Impedance Z_cab(w) with Z PrePost + 16-tap CabZFir
// - 5 Special Hardware Sub-Circuits + Stock vs Hype + 5-Zone Focus View XY
class AgouraTubeAmp {
public:
    AgouraTubeAmp() noexcept {
        SetModel(AmpModelId::kBrit2203);
    }

    void SetModel(AmpModelId id) noexcept {
        model_id_ = id;
        const auto& r = GetAmpRecipe(id);
        cathode_bypass_.Configure(r.cathode_rk_ohm, r.cathode_ck_f, 0.22f, kSampleRate * 4.0f);
        grid_bias_.Configure(r.grid_rg_ohm, r.coupling_cc_f, params_.bias_x, kSampleRate * 4.0f);
        class_b_.Configure(r.power_bias_coldness, r.single_ended, kSampleRate * 4.0f);
        ripple_gen_.Configure(120.0f, 60.0f, kSampleRate);
        cab_z_filter_.Configure(GetFactoryCabSpec(r.default_cab).impedance, kSampleRateD);
        UpdateToneAndSubcircuits();
    }

    AmpModelId GetModel() const noexcept { return model_id_; }

    void SetParams(const AmpParams& p) noexcept {
        params_ = p;
        const auto& r = GetAmpRecipe(model_id_);
        grid_bias_.Configure(r.grid_rg_ohm, r.coupling_cc_f, p.bias_x, kSampleRate * 4.0f);
        class_b_.Configure(r.power_bias_coldness * (1.2f - 0.8f * p.bias), r.single_ended, kSampleRate * 4.0f);
        UpdateToneAndSubcircuits();
    }

    const AmpParams& GetParams() const noexcept { return params_; }

    void SetReactiveCabImpedance(const ReactiveCabImpedanceDesc& z) noexcept {
        cab_z_filter_.Configure(z, kSampleRateD);
    }

    // 5-Zone Focus View XY Morphing (x, y in [-1, +1]; (0,0) = current center baseline)
    void ApplyFocusViewXY(float x, float y) noexcept {
        x = Clamp(x, -1.0f, 1.0f);
        y = Clamp(y, -1.0f, 1.0f);
        AmpParams p = params_;
        // X axis: Darker/Thicker (-1) <-> Brighter/Tighter (+1)
        // Y axis: Cleaner/Dynamic (-1) <-> Saturated/Compressed (+1)
        p.drive = Clamp(0.50f + 0.35f * y + 0.08f * x, 0.05f, 0.98f);
        p.treble = Clamp(0.50f + 0.28f * x + 0.06f * y, 0.05f, 0.98f);
        p.presence = Clamp(0.50f + 0.30f * x, 0.05f, 0.98f);
        p.bass = Clamp(0.50f - 0.22f * x - 0.10f * y, 0.05f, 0.98f);
        p.mid = Clamp(0.50f + 0.15f * x - 0.12f * y, 0.05f, 0.98f);
        p.master = Clamp(0.55f + 0.22f * y, 0.10f, 0.95f);
        p.sag = Clamp(0.45f + 0.25f * y - 0.15f * x, 0.05f, 0.95f);
        SetParams(p);
    }

    void Reset() noexcept {
        os4_.Reset();
        bright_cap_.Reset();
        fmv_stack_.Reset();
        james_stack_.Reset();
        tweed_tone_.Reset();
        presence_shelf_.Reset();
        hype_tight_hp_.Reset();
        hype_air_shelf_.Reset();
        dc_blocker_.Reset();
        cathode_bypass_.Reset();
        grid_bias_.Reset();
        class_b_.Reset();
        ripple_gen_.Reset();
        cab_z_filter_.Reset();
        for (auto& s : adaa_stages_) s.Reset();
        for (auto& g : geq5_) g.Reset();
        vh4_deep_.Reset();
        vox_cut_.Reset();
        svt_mid_.Reset();
        cf_prev_y_ = 0.0f;
        sag_env_ = 0.0f;
    }

    void ProcessSubBlock16(const float* in16, float* out16) noexcept {
        const auto& r = GetAmpRecipe(model_id_);

        // Stage 0: Input Coupled Bright-Cap & optional Hype sub-tightening + pre-gain Mesa tone stack
        alignas(64) float pre16[kSubBlockSize];
        const float drive_taper = EvalPotTaper(PotTaper::kLog15A, params_.drive);
        const float pre_gain = r.preamp_gain_mult * (0.35f + 3.4f * drive_taper) * (1.0f + 0.22f * params_.hype);
        const bool pre_gain_ts = (r.ts_topology == ToneStackTopology::kFmvMesaPreGain);

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            float x = bright_cap_.ProcessSample(in16[i]);
            if (params_.hype > 0.01f) {
                const float tight = hype_tight_hp_.Process(x);
                x = x + params_.hype * (tight - x);
            }
            if (pre_gain_ts) {
                x = fmv_stack_.Process(x) * 2.2f;
            }
            pre16[i] = x * pre_gain;
        }

        // Stage 1: 4x Polyphase Oversampled Nonlinear Preamp + Cathode Follower + Power Stage
        alignas(64) float up64[64];
        os4_.Upsample16(pre16, up64);

        const float master_taper = EvalPotTaper(PotTaper::kLog15A, params_.master);
        const float power_drive = 0.45f + 2.4f * master_taper;
        const float sag_amount = Clamp(r.stock_sag * (0.4f + 1.2f * params_.sag) * (1.0f - 0.35f * params_.hype), 0.0f, 0.85f);
        const float ripple_amount = r.stock_ripple * params_.ripple * 0.035f;

        // Compute control-rate B+ ripple modulation once per sub-block (48 kHz rate -> smooth at 192 kHz)
        const float bplus_ripple = ripple_gen_.NextRippleSample();
        const float heater_hum = ripple_gen_.NextHeaterHumSample() * ripple_amount * 0.15f;

        for (size_t i = 0; i < 64; ++i) {
            float v = up64[i] + heater_hum;

            // Multi-stage 12AX7 triode cascade with ADAA1 + cathode bypass + grid bias excursion
            const int stages = std::max(1, std::min(r.gain_stages, 5));
            for (int st = 0; st < stages; ++st) {
                if (st == 0) {
                    v = cathode_bypass_.ShiftGridVoltage(v);
                } else if (st == 1) {
                    v = grid_bias_.Process(v, 0.35f);
                }
                // Slight asymmetric triode grid bias
                const float asym = (st & 1) ? -0.08f : 0.11f;
                v = adaa_stages_[static_cast<size_t>(st)].Process(v + asym) - std::tanh(asym);
                if (st == 0) {
                    cathode_bypass_.UpdateFromPlateCurrent(v);
                }
                if (st + 1 < stages) {
                    v *= 1.48f;
                }
            }

            // Damped Newton-Raphson Cathode Follower (3 bounded iterations, soft-compresses positive swing)
            if (stages >= 3 && !r.is_bass) {
                v = SolveCathodeFollowerNR(v);
            }

            // Power Supply Sag & Asymmetric 31% Duty Rectifier Ripple modulating headroom
            const float inst_load = std::fabs(v) * power_drive;
            sag_env_ += 0.00045f * (inst_load - sag_env_);
            const float bplus_scale = std::max(0.45f, 1.0f - sag_amount * 0.38f * sag_env_ + ripple_amount * bplus_ripple);

            // Power-amp stage (Single-Ended Class-A vs Push-Pull Class-AB with crossover notch)
            float pv = (v * power_drive) / bplus_scale;
            pv = class_b_.Process(pv);
            pv = adaa_stages_[5].Process(pv) * bplus_scale;
            up64[i] = pv;
        }

        // Downsample back to 48 kHz
        alignas(64) float post16[kSubBlockSize];
        os4_.Downsample16(up64, post16);

        // Stage 2: Post-Preamp Tone Stack + Reactive Z_cab Coupling (Pre/Post NFB) + Hardware Sub-Circuits
        const float ch_gain = EvalPotTaper(PotTaper::kLog15A, params_.ch_vol) * r.makeup_gain * 1.15f;
        const float z_pre_post = Clamp(r.z_pre_post, 0.0f, 1.0f);
        // Stronger NFB dampens clean Z_cab peaks while zero-NFB or saturated power stages unleash full cab resonance
        const float nfb_damp = 1.0f - r.nfb_gain * (1.0f - z_pre_post) * 0.35f;
        const float z_blend = Clamp(0.55f + 0.45f * nfb_damp, 0.35f, 1.0f);

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            float y = post16[i];

            if (!pre_gain_ts) {
                if (r.ts_topology == ToneStackTopology::kJamesHiwatt ||
                    r.ts_topology == ToneStackTopology::kJamesBassBaxandall) {
                    y = james_stack_.Process(y);
                } else if (r.ts_topology == ToneStackTopology::kTweedOneKnob) {
                    y = tweed_tone_.Process(y);
                } else {
                    y = fmv_stack_.Process(y);
                }
            }

            // Presence shelf & Bidirectional Reactive Cabinet Impedance Z_cab(w)
            y = presence_shelf_.Process(y);
            const float y_z = cab_z_filter_.Process(y);
            y = y * (1.0f - z_blend) + y_z * z_blend;

            // Model-Specific Hardware Sub-Circuits
            switch (r.subcircuit) {
                case AmpSpecialSubcircuit::kCali2CPlusGeq5:
                    for (auto& band : geq5_) y = band.Process(y);
                    break;
                case AmpSpecialSubcircuit::kVh4DeepContour:
                    y = vh4_deep_.Process(y);
                    break;
                case AmpSpecialSubcircuit::kVoxTopBoostCut:
                    y = vox_cut_.Process(y);
                    break;
                case AmpSpecialSubcircuit::kSvtUltraMidGyrator:
                    y = svt_mid_.Process(y);
                    break;
                default:
                    break;
            }

            if (params_.hype > 0.01f) {
                const float air = hype_air_shelf_.Process(y);
                y = y + params_.hype * (air - y);
            }

            out16[i] = dc_blocker_.Process(y) * ch_gain;
        }
    }

private:
    // Implicit Cathode Follower solver: g(y) = y + alpha * softplus(y - v_in) - 0.92 * v_in = 0
    // Solved with 3 bounded damped Newton-Raphson iterations starting from cf_prev_y_.
    inline float SolveCathodeFollowerNR(float v_in) noexcept {
        float y = 0.5f * (cf_prev_y_ + v_in);
        constexpr float kKnee = 0.45f;
        for (int it = 0; it < 3; ++it) {
            const float u = (v_in - y - kKnee) * 3.0f;
            const float eu = (u > 12.0f) ? 1.62754e5f : std::exp(u);
            const float soft_ig = (u > 12.0f) ? (u / 3.0f) : (std::log1p(eu) / 3.0f);
            const float d_soft = (u > 12.0f) ? 1.0f : (eu / (1.0f + eu));
            const float f = y + 0.42f * soft_ig - 0.94f * v_in;
            const float df = 1.0f - 0.42f * d_soft;
            y -= f / std::max(df, 0.25f);
        }
        cf_prev_y_ = Clamp(y, -3.0f, 3.0f);
        return cf_prev_y_;
    }

    void UpdateToneAndSubcircuits() noexcept {
        const auto& r = GetAmpRecipe(model_id_);
        bright_cap_.Configure(1.0e6, static_cast<double>(r.bright_cap_f),
                              static_cast<double>(params_.drive), PotTaper::kLog15A,
                              params_.bright_switch && r.bright_cap_f > 0.0f, kSampleRateD);

        if (r.ts_topology == ToneStackTopology::kJamesHiwatt ||
            r.ts_topology == ToneStackTopology::kJamesBassBaxandall) {
            james_stack_.SetCoeffs(
                ComputeJamesToneStackBilinearDouble(r.james_stack, params_.bass, params_.treble, kSampleRateD));
        } else if (r.ts_topology == ToneStackTopology::kTweedOneKnob) {
            const double cut_hz = 1200.0 + 5200.0 * static_cast<double>(params_.treble);
            tweed_tone_.SetCoeffs(DesignLowPass(cut_hz, 0.707, kSampleRateD));
        } else {
            fmv_stack_.SetCoeffs(
                ComputeFmvToneStackBilinearDouble(r.tone_stack, params_.bass, params_.mid, params_.treble, kSampleRateD));
        }

        const double pres_db = (static_cast<double>(params_.presence) - 0.5) * 10.0;
        presence_shelf_.SetCoeffs(DesignHighShelf(2800.0, 0.707, pres_db, kSampleRateD));

        hype_tight_hp_.SetCoeffs(DesignHighPass(r.is_bass ? 36.0 : 85.0, 0.707, kSampleRateD));
        hype_air_shelf_.SetCoeffs(DesignHighShelf(3600.0, 0.707, 3.2, kSampleRateD));
        dc_blocker_.SetCoeffs(DesignHighPass(18.0, 0.707, kSampleRateD));

        // Model-specific sub-circuits
        const double v = static_cast<double>(Clamp(params_.geq_v_scoop, 0.0f, 1.0f));
        geq5_[0].SetCoeffs(DesignAntiCrampedPeak(80.0, 1.6, 5.5 * v, kSampleRateD));
        geq5_[1].SetCoeffs(DesignAntiCrampedPeak(240.0, 1.6, 1.5 * v, kSampleRateD));
        geq5_[2].SetCoeffs(DesignAntiCrampedPeak(750.0, 1.8, -9.5 * v, kSampleRateD));
        geq5_[3].SetCoeffs(DesignAntiCrampedPeak(2200.0, 1.6, 4.8 * v, kSampleRateD));
        geq5_[4].SetCoeffs(DesignAntiCrampedPeak(6600.0, 1.6, 3.2 * v, kSampleRateD));

        const double deep_db = 2.5 + 6.5 * static_cast<double>(params_.deep);
        vh4_deep_.SetCoeffs(DesignAntiCrampedPeak(92.0, 2.2, deep_db, kSampleRateD));

        const double cut_hz = 12000.0 * std::pow(0.18, static_cast<double>(params_.cut));
        vox_cut_.SetCoeffs(DesignLowPass(cut_hz, 0.707, kSampleRateD));

        const double svt_mid_db = (static_cast<double>(params_.mid) - 0.5) * 12.0;
        svt_mid_.SetCoeffs(DesignAntiCrampedPeak(450.0, 1.4, svt_mid_db, kSampleRateD));
    }

    AmpModelId model_id_ = AmpModelId::kBrit2203;
    AmpParams params_{};

    PolyphaseOversampler4x os4_;
    CoupledBrightCapFilter bright_cap_;
    HighOrderIirDF2T<3> fmv_stack_;
    HighOrderIirDF2T<4> james_stack_;
    BiquadDF2T tweed_tone_;
    BiquadDF2T presence_shelf_;
    BiquadDF2T hype_tight_hp_;
    BiquadDF2T hype_air_shelf_;
    BiquadDF2T dc_blocker_;

    HalfWaveCathodeBypass cathode_bypass_;
    ClampedGridBiasExcursion grid_bias_;
    ClassBCrossoverStage class_b_;
    AsymmetricRectifierRippleGen ripple_gen_;
    ReactiveCabImpedanceFilter cab_z_filter_;
    std::array<Adaa1TanhStage, 6> adaa_stages_;

    std::array<BiquadDF2T, 5> geq5_;
    BiquadDF2T vh4_deep_;
    BiquadDF2T vox_cut_;
    BiquadDF2T svt_mid_;

    float cf_prev_y_ = 0.0f;
    float sag_env_ = 0.0f;
};

}  // namespace stadium_dsp
