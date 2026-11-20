#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/oscillators_and_lfo.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// 7 Hardware Delay Models (Catalog Section 5)
enum class DelayModel : int32_t {
    kBucketBrigade = 0,   // BOSS DM-2 (MN3005 BBD + NE570 compander, dark analog rolloff)
    kCosmosEcho,          // Roland RE-201 Space Echo (3 heads 0.382T/0.685T/1.000T + bitmask 1..7 + Spring)
    kAdriaticDelay,       // BOSS DM-2 w/ Adrian Overclocked MN3102 BBD Clock Mod (extended HF bandwidth)
    kTransistorTape,      // Maestro Echoplex EP-3 (TIS58 JFET asym + 3D Chua chaotic capstan wow/flutter)
    kElephantMan,         // EHX Deluxe Memory Man (dual MN3005 + Chorus/Vibrato mod)
    kVintageDigital,      // 1980s 12-bit µ-law (mu=255) digital rack delay
    kDuckedDelay,         // TC Electronic 2290 Dynamic Ducked Delay
    kCount = 7,
};

struct DelayParams {
    bool enabled = false;
    DelayModel model = DelayModel::kCosmosEcho;
    bool is_stereo = true;
    float time_ms = 380.0f;          // 20..2000 ms
    float feedback = 0.42f;          // 0..0.95
    float mix = 0.35f;               // 0..1
    float mod_depth = 0.25f;
    float mod_rate_hz = 0.8f;
    int32_t head_mode = 7;           // Cosmos Echo RE-201 3-head bitmask (1..7)
    float spring_mix = 0.15f;        // Cosmos Echo integrated spring tank mix
    float bbd_rate_scale = 2.0f;     // Adriatic overclocked MN3102 clock multiplier (1.0..2.5)
    float duck_thresh_db = -24.0f;   // TC 2290 ducking threshold
    float duck_amount = 0.70f;
};

class StereoTapeBbdDelay {
public:
    static constexpr size_t kMaxDelaySamples = 131072;  // 2.73s @ 48 kHz (power of two)

    StereoTapeBbdDelay() noexcept {
        buf_[0].assign(kMaxDelaySamples, 0.0f);
        buf_[1].assign(kMaxDelaySamples, 0.0f);
        SetParams(DelayParams{});
        Reset();
    }

    void Reset() noexcept {
        std::fill(buf_[0].begin(), buf_[0].end(), 0.0f);
        std::fill(buf_[1].begin(), buf_[1].end(), 0.0f);
        write_pos_ = 0;
        lfo_phase_ = 0.0f;
        chua_.Reset();
        comp_env_ = 0.0f;
        duck_env_ = 0.0f;
        spring_buf_.fill(0.0f);
        spring_w_ = 0;
        for (int ch = 0; ch < 2; ++ch) {
            fb_lp1_[ch].Reset();
            fb_lp2_[ch].Reset();
            fb_hp_[ch].Reset();
        }
    }

    void SetParams(const DelayParams& p) noexcept {
        params_ = p;
        ConfigureToneFilters();
    }

    const DelayParams& GetParams() const noexcept { return params_; }

    void ProcessSubBlock16(const float* in_l, const float* in_r, float* out_l, float* out_r) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
            }
            return;
        }

        const float base_delay = Clamp(params_.time_ms, 15.0f, 2000.0f) * (kSampleRate * 0.001f);
        const float fb = Clamp(params_.feedback, 0.0f, 0.94f);
        const float mix = Clamp(params_.mix, 0.0f, 1.0f);

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            const float dry_l = in_l[i];
            const float dry_r = params_.is_stereo ? in_r[i] : 0.5f * (in_l[i] + in_r[i]);
            const float in_mono = 0.5f * (dry_l + dry_r);

            const float lfo = std::sin(kTwoPi * lfo_phase_);
            lfo_phase_ += Clamp(params_.mod_rate_hz, 0.05f, 8.0f) / kSampleRate;
            if (lfo_phase_ >= 1.0f) lfo_phase_ -= 1.0f;

            float wet_l = 0.0f, wet_r = 0.0f;

            switch (params_.model) {
                case DelayModel::kCosmosEcho: {
                    // Roland RE-201 Space Echo: exact 3 playback heads @ 0.382T, 0.685T, 1.000T
                    const float flutter = chua_.ProcessSample(0.6) * params_.mod_depth * 6.5f;
                    const int mask = std::max(1, params_.head_mode & 7);
                    float sum_l = 0.0f, sum_r = 0.0f;
                    int active_heads = 0;
                    static constexpr float kHeadRatios[3] = {0.382f, 0.685f, 1.000f};
                    for (int h = 0; h < 3; ++h) {
                        if (mask & (1 << h)) {
                            const float dh = base_delay * kHeadRatios[h] + flutter;
                            sum_l += ReadHermite(0, dh);
                            sum_r += ReadHermite(1, dh + (params_.is_stereo ? static_cast<float>(h * 11) : 0.0f));
                            ++active_heads;
                        }
                    }
                    const float norm = 1.0f / std::sqrt(static_cast<float>(std::max(1, active_heads)));
                    wet_l = sum_l * norm;
                    wet_r = sum_r * norm;

                    // Feedback tape saturation & bandlimit
                    const float fb_l = std::tanh(fb_hp_[0].Process(fb_lp1_[0].Process(wet_l)) * 1.15f);
                    const float fb_r = std::tanh(fb_hp_[1].Process(fb_lp1_[1].Process(wet_r)) * 1.15f);
                    buf_[0][write_pos_] = dry_l + fb * fb_l;
                    buf_[1][write_pos_] = dry_r + fb * fb_r;

                    // Integrated RE-201 spring reverb tank (optional spring_mix)
                    if (params_.spring_mix > 0.005f) {
                        const float sp = ProcessIntegratedSpring(in_mono + 0.35f * wet_l);
                        wet_l += params_.spring_mix * sp;
                        wet_r += params_.spring_mix * sp * 0.92f;
                    }
                    break;
                }
                case DelayModel::kBucketBrigade:
                case DelayModel::kAdriaticDelay: {
                    // BOSS DM-2 MN3005 (dark 1.6 kHz 4-pole anti-alias) vs Adriatic overclocked MN3102 (> 5 kHz)
                    const float mod_smp = lfo * params_.mod_depth * 14.0f;
                    // NE570 2:1 input compressor + 1:2 output expander envelope
                    const float ax = std::fabs(in_mono);
                    comp_env_ += 0.004f * (ax - comp_env_);
                    const float comp_g = 1.0f / std::sqrt(std::max(0.05f, comp_env_ + 0.1f));

                    const float dl = ReadHermite(0, base_delay + mod_smp);
                    const float dr = ReadHermite(1, base_delay - (params_.is_stereo ? mod_smp : -mod_smp));
                    // 4-pole BBD anti-alias & reconstruction filter cascade
                    wet_l = fb_lp2_[0].Process(fb_lp1_[0].Process(dl));
                    wet_r = fb_lp2_[1].Process(fb_lp1_[1].Process(dr));
                    const float expand = std::sqrt(std::max(0.05f, comp_env_ + 0.1f));
                    buf_[0][write_pos_] = std::tanh((dry_l * comp_g + fb * wet_l * 1.2f) * 0.85f);
                    buf_[1][write_pos_] = std::tanh((dry_r * comp_g + fb * wet_r * 1.2f) * 0.85f);
                    wet_l *= expand;
                    wet_r *= expand;
                    break;
                }
                case DelayModel::kTransistorTape: {
                    // Maestro Echoplex EP-3: TIS58 JFET asymmetric warmth + chaotic capstan wow/flutter
                    const float wf = chua_.ProcessSample(0.85) * (4.0f + 16.0f * params_.mod_depth);
                    const float dl = ReadHermite(0, base_delay + wf);
                    const float dr = ReadHermite(1, base_delay + wf * 0.85f);
                    wet_l = fb_hp_[0].Process(fb_lp1_[0].Process(dl));
                    wet_r = fb_hp_[1].Process(fb_lp1_[1].Process(dr));
                    auto jfet = [](float v) { return std::tanh(v + 0.12f * v * v); };
                    buf_[0][write_pos_] = jfet(dry_l + fb * wet_l);
                    buf_[1][write_pos_] = jfet(dry_r + fb * wet_r);
                    break;
                }
                case DelayModel::kElephantMan: {
                    // EHX Deluxe Memory Man: lush modulated dual MN3005 stereo chorus/vibrato echoes
                    const float mod_smp = lfo * (12.0f + 42.0f * params_.mod_depth);
                    const float dl = ReadHermite(0, base_delay + mod_smp);
                    const float dr = ReadHermite(1, base_delay - mod_smp);
                    wet_l = fb_lp1_[0].Process(dl);
                    wet_r = fb_lp1_[1].Process(dr);
                    buf_[0][write_pos_] = std::tanh((dry_l + fb * wet_l) * 1.1f);
                    buf_[1][write_pos_] = std::tanh((dry_r + fb * wet_r) * 1.1f);
                    break;
                }
                case DelayModel::kVintageDigital: {
                    // Classic 12-bit µ-law (mu=255) companded rack delay
                    auto mu_quant12 = [](float x) {
                        constexpr float mu = 255.0f;
                        const float ax = Clamp(std::fabs(x), 0.0f, 1.0f);
                        const float sgn = (x >= 0.0f) ? 1.0f : -1.0f;
                        const float enc = sgn * std::log1p(mu * ax) / std::log1p(mu);
                        const float q = std::round(enc * 2047.0f) / 2047.0f;
                        return sgn * (std::pow(1.0f + mu, std::fabs(q)) - 1.0f) / mu;
                    };
                    const float dl = ReadHermite(0, base_delay);
                    const float dr = ReadHermite(1, base_delay * (params_.is_stereo ? 0.995f : 1.0f));
                    wet_l = fb_lp1_[0].Process(mu_quant12(dl));
                    wet_r = fb_lp1_[1].Process(mu_quant12(dr));
                    buf_[0][write_pos_] = dry_l + fb * wet_l;
                    buf_[1][write_pos_] = dry_r + fb * wet_r;
                    break;
                }
                case DelayModel::kDuckedDelay: {
                    // TC Electronic 2290: dynamic ducking attenuates wet echoes while guitarist plays
                    const float ax = std::fabs(in_mono);
                    duck_env_ += ((ax > duck_env_) ? 0.02f : 0.0004f) * (ax - duck_env_);
                    const float over_db = std::max(0.0f, LinToDb(duck_env_) - params_.duck_thresh_db);
                    const float duck_gain = DbToLin(-over_db * params_.duck_amount);
                    const float dl = ReadHermite(0, base_delay);
                    const float dr = ReadHermite(1, base_delay * (params_.is_stereo ? 0.75f : 1.0f));
                    buf_[0][write_pos_] = dry_l + fb * dl;
                    buf_[1][write_pos_] = dry_r + fb * dr;
                    wet_l = dl * duck_gain;
                    wet_r = dr * duck_gain;
                    break;
                }
                default:
                    break;
            }

            write_pos_ = (write_pos_ + 1) & (kMaxDelaySamples - 1);
            out_l[i] = dry_l * (1.0f - mix) + wet_l * mix;
            out_r[i] = params_.is_stereo ? (dry_r * (1.0f - mix) + wet_r * mix) : out_l[i];
        }
    }

private:
    void ConfigureToneFilters() noexcept {
        double lp_hz = 4800.0;
        double hp_hz = 85.0;
        if (params_.model == DelayModel::kBucketBrigade) {
            lp_hz = 1350.0;  // Stock MN3005 DM-2 dark 4-pole LPF -> heavily attenuates 3.6 kHz
        } else if (params_.model == DelayModel::kAdriaticDelay) {
            lp_hz = 1350.0 * std::pow(Clamp(params_.bbd_rate_scale, 1.0f, 3.0f), 1.8f);  // Overclocked MN3102 (~5.5 kHz)
        } else if (params_.model == DelayModel::kCosmosEcho || params_.model == DelayModel::kTransistorTape) {
            lp_hz = 3800.0;
            hp_hz = 110.0;
        } else if (params_.model == DelayModel::kElephantMan) {
            lp_hz = 2900.0;
        } else if (params_.model == DelayModel::kVintageDigital) {
            lp_hz = 8500.0;
        }
        const auto lp = DesignLowPass(lp_hz, 0.75, kSampleRateD);
        const auto hp = DesignHighPass(hp_hz, 0.707, kSampleRateD);
        for (int ch = 0; ch < 2; ++ch) {
            fb_lp1_[ch].SetCoeffs(lp);
            fb_lp2_[ch].SetCoeffs(lp);
            fb_hp_[ch].SetCoeffs(hp);
        }
    }

    inline float ReadHermite(int ch, float delay) const noexcept {
        const float d = Clamp(delay, 2.0f, static_cast<float>(kMaxDelaySamples - 4));
        const size_t di = static_cast<size_t>(d);
        const float f = d - static_cast<float>(di);
        const auto& b = buf_[ch];
        const size_t m = kMaxDelaySamples - 1;
        const float y0 = b[(write_pos_ + kMaxDelaySamples - di + 1) & m];
        const float y1 = b[(write_pos_ + kMaxDelaySamples - di) & m];
        const float y2 = b[(write_pos_ + kMaxDelaySamples - di - 1) & m];
        const float y3 = b[(write_pos_ + kMaxDelaySamples - di - 2) & m];
        const float c0 = y1;
        const float c1 = 0.5f * (y2 - y0);
        const float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        const float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * f + c2) * f + c1) * f + c0;
    }

    inline float ProcessIntegratedSpring(float x) noexcept {
        const float d1 = spring_buf_[(spring_w_ + 2048 - 1187) & 2047u];
        const float d2 = spring_buf_[(spring_w_ + 2048 - 1753) & 2047u];
        const float y = 0.55f * d1 - 0.45f * d2;
        spring_buf_[spring_w_] = x + 0.52f * y;
        spring_w_ = (spring_w_ + 1) & 2047u;
        return y;
    }

    DelayParams params_{};
    std::array<std::vector<float>, 2> buf_;
    size_t write_pos_ = 0;
    float lfo_phase_ = 0.0f;
    ChuaChaoticLfo chua_;
    float comp_env_ = 0.0f;
    float duck_env_ = 0.0f;
    std::array<BiquadDF2T, 2> fb_lp1_, fb_lp2_, fb_hp_;
    std::array<float, 2048> spring_buf_{};
    size_t spring_w_ = 0;
};

}  // namespace stadium_dsp
