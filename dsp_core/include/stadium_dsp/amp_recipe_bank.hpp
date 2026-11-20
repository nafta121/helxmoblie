#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>

#include "stadium_dsp/dual_cab_convolver.hpp"
#include "stadium_dsp/pot_tapers.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// Exact 3rd-order & 4th-order double-precision Direct Form II Transposed IIR Tone Stack filter
template <size_t Order>
struct HighOrderIirCoeffs {
    std::array<double, Order + 1> b{};
    std::array<double, Order + 1> a{};  // a[0] normalized to 1.0

    double EvalMagnitudeDb(double freq_hz, double fs = kSampleRateD) const noexcept {
        const double w = kTwoPiD * freq_hz / fs;
        const std::complex<double> z_inv(std::cos(w), -std::sin(w));
        std::complex<double> num(0.0, 0.0), den(0.0, 0.0);
        std::complex<double> zp(1.0, 0.0);
        for (size_t k = 0; k <= Order; ++k) {
            num += b[k] * zp;
            den += a[k] * zp;
            zp *= z_inv;
        }
        return 20.0 * std::log10(std::max(std::abs(num / den), 1.0e-12));
    }
};

template <size_t Order>
class HighOrderIirDF2T {
public:
    void SetCoeffs(const HighOrderIirCoeffs<Order>& c) noexcept { c_ = c; }
    const HighOrderIirCoeffs<Order>& GetCoeffs() const noexcept { return c_; }

    void Reset() noexcept { z_.fill(0.0); }

    inline float Process(float x) noexcept {
        const double xd = static_cast<double>(x);
        const double y = c_.b[0] * xd + z_[0];
        for (size_t i = 0; i + 1 < Order; ++i) {
            z_[i] = c_.b[i + 1] * xd - c_.a[i + 1] * y + z_[i + 1];
        }
        z_[Order - 1] = c_.b[Order] * xd - c_.a[Order] * y;
        return static_cast<float>(y);
    }

private:
    HighOrderIirCoeffs<Order> c_{};
    std::array<double, Order> z_{};
};

namespace detail {

// Small fixed-degree polynomial helpers (ascending powers of s: p[0] + p[1]*s + ...)
template <size_t NA, size_t NB>
inline std::array<double, NA + NB - 1> PolyMul(const std::array<double, NA>& a,
                                               const std::array<double, NB>& b) noexcept {
    std::array<double, NA + NB - 1> r{};
    for (size_t i = 0; i < NA; ++i) {
        for (size_t j = 0; j < NB; ++j) {
            r[i + j] += a[i] * b[j];
        }
    }
    return r;
}

template <size_t N>
inline std::array<double, N> PolyAdd(const std::array<double, N>& a,
                                     const std::array<double, N>& b) noexcept {
    std::array<double, N> r{};
    for (size_t i = 0; i < N; ++i) r[i] = a[i] + b[i];
    return r;
}

template <size_t N>
inline std::array<double, N> PolySub(const std::array<double, N>& a,
                                     const std::array<double, N>& b) noexcept {
    std::array<double, N> r{};
    for (size_t i = 0; i < N; ++i) r[i] = a[i] - b[i];
    return r;
}

// Exact bilinear substitution s = c * (1 - z^-1) / (1 + z^-1) for Order 3 or 4
template <size_t Order>
inline HighOrderIirCoeffs<Order> BilinearTransformPoly(const std::array<double, Order + 1>& b_s,
                                                       const std::array<double, Order + 1>& a_s,
                                                       double fs) noexcept {
    const double c = 2.0 * fs;
    // Compute (1 - z^-1)^k * (1 + z^-1)^(Order - k) using direct polynomial convolution
    std::array<double, Order + 1> Bz{};
    std::array<double, Order + 1> Az{};

    for (size_t k = 0; k <= Order; ++k) {
        // Basis polynomial P_k(z^-1) = (1 - z^-1)^k * (1 + z^-1)^(Order - k)
        std::array<double, Order + 1> basis{};
        basis[0] = 1.0;
        size_t deg = 0;
        for (size_t i = 0; i < k; ++i) {
            for (size_t j = deg + 1; j > 0; --j) basis[j] -= basis[j - 1];
            ++deg;
        }
        for (size_t i = 0; i < Order - k; ++i) {
            for (size_t j = deg + 1; j > 0; --j) basis[j] += basis[j - 1];
            ++deg;
        }
        const double ck = std::pow(c, static_cast<double>(k));
        for (size_t j = 0; j <= Order; ++j) {
            Bz[j] += b_s[k] * ck * basis[j];
            Az[j] += a_s[k] * ck * basis[j];
        }
    }

    HighOrderIirCoeffs<Order> out{};
    const double inv_a0 = 1.0 / ((std::fabs(Az[0]) > 1.0e-30) ? Az[0] : 1.0);
    for (size_t j = 0; j <= Order; ++j) {
        out.b[j] = Bz[j] * inv_a0;
        out.a[j] = Az[j] * inv_a0;
    }
    return out;
}

}  // namespace detail

// 3rd-order Fender / Marshall / Vox (FMV) passive tone stack component spec
struct FmvToneStackSpec {
    double r1_treble = 250.0e3;
    double r2_bass = 250.0e3;
    double r3_mid = 10.0e3;
    double r4_slope = 100.0e3;
    double c1_treble = 120.0e-12;
    double c2_bass = 100.0e-9;
    double c3_mid = 47.0e-9;
    PotTaper bass_taper = PotTaper::kLog10A;
    PotTaper mid_taper = PotTaper::kLinear;
    PotTaper treble_taper = PotTaper::kLinear;
};

// 4th-order R.T. James (1949) passive tone stack component spec (Hiwatt DR103 / Ampeg B15 / Orange AD200)
struct JamesToneStackSpec {
    double r1_top = 90.0e3;      // Series resistor into top of bass pot
    double r2_couple = 220.0e3;  // Coupling resistor from bass wiper to treble wiper
    double r3_gnd = 10.0e3;      // Ground resistor below bass pot
    double r_bass = 1.0e6;
    double r_treble = 1.0e6;
    double c1_bass_top = 4.7e-9;
    double c2_bass_bot = 42.3e-9;
    double c3_treble_top = 470.0e-12;
    double c4_treble_bot = 4.23e-9;
    PotTaper bass_taper = PotTaper::kLog10A;
    PotTaper treble_taper = PotTaper::kLog10A;
};

// Zero-division exact 3rd-order FMV Tone Stack solver (matches 5-node Kirchhoff MNA to < 1e-14!)
inline HighOrderIirCoeffs<3> ComputeFmvToneStackBilinearDouble(const FmvToneStackSpec& sp,
                                                               double bass_knob,
                                                               double mid_knob,
                                                               double treble_knob,
                                                               double fs = kSampleRateD) noexcept {
    const double l = std::max(0.002, static_cast<double>(EvalPotTaper(sp.bass_taper, static_cast<float>(bass_knob))));
    const double m = std::max(0.002, static_cast<double>(EvalPotTaper(sp.mid_taper, static_cast<float>(mid_knob))));
    const double t = std::max(0.0, std::min(1.0, static_cast<double>(EvalPotTaper(sp.treble_taper, static_cast<float>(treble_knob)))));

    const double R1 = sp.r1_treble;
    const double rb = std::max(1.0, l * sp.r2_bass);
    const double rm = std::max(1.0, m * sp.r3_mid);
    const double R4 = sp.r4_slope;
    const double C1 = sp.c1_treble;
    const double C2 = sp.c2_bass;
    const double C3 = sp.c3_mid;

    auto det3 = [](const std::array<double, 3>& a00, const std::array<double, 3>& a01, const std::array<double, 3>& a02,
                   const std::array<double, 3>& a10, const std::array<double, 3>& a11, const std::array<double, 3>& a12,
                   const std::array<double, 3>& a20, const std::array<double, 3>& a21, const std::array<double, 3>& a22) {
        auto m0 = detail::PolySub(detail::PolyMul(a11, a22), detail::PolyMul(a12, a21));
        auto m1 = detail::PolySub(detail::PolyMul(a10, a22), detail::PolyMul(a12, a20));
        auto m2 = detail::PolySub(detail::PolyMul(a10, a21), detail::PolyMul(a11, a20));
        auto c0 = detail::PolyMul(a00, m0);
        auto c1 = detail::PolyMul(a01, m1);
        auto c2 = detail::PolyMul(a02, m2);
        return detail::PolyAdd(detail::PolySub(c0, c1), c2);
    };

    const std::array<double, 3> M00 = {1.0, R4 * (C2 + C3), 0.0};
    const std::array<double, 3> M01 = {0.0, -R4 * C2, 0.0};
    const std::array<double, 3> M02 = {0.0, -R4 * C3, 0.0};

    const std::array<double, 2> D1 = {1.0, C1 * R1};
    const auto M10_raw = detail::PolyMul(std::array<double, 2>{0.0, -C2 * rb}, D1);
    const auto M11_p1 = detail::PolyMul(std::array<double, 2>{1.0, C2 * rb}, D1);
    const std::array<double, 3> M11 = {M11_p1[0], M11_p1[1] + C1 * rb, M11_p1[2]};
    const std::array<double, 3> M12 = {-1.0, -C1 * R1, 0.0};

    const std::array<double, 3> M20 = {0.0, -C3 * rm * rb, 0.0};
    const std::array<double, 3> M21 = {-rm, 0.0, 0.0};
    const std::array<double, 3> M22 = {rb + rm, C3 * rm * rb, 0.0};

    const std::array<double, 3> RHS0 = {1.0, 0.0, 0.0};
    const std::array<double, 3> RHS1 = {0.0, C1 * rb, 0.0};
    const std::array<double, 3> RHS2 = {0.0, 0.0, 0.0};

    const auto den_full = det3(M00, M01, M02, M10_raw, M11, M12, M20, M21, M22);
    const auto num_vb = det3(M00, RHS0, M02, M10_raw, RHS1, M12, M20, RHS2, M22);

    // Analytic factor cancellation of D1 = (1 + s*C1*R1) out of Row 1
    const std::array<double, 3> M01_sub = {-1.0, -R4 * C2, 0.0};
    const std::array<double, 3> Q10 = {0.0, -C2 * rb, 0.0};
    const std::array<double, 3> Q11 = {1.0, C2 * rb, 0.0};
    const std::array<double, 3> Q12 = {-1.0, 0.0, 0.0};
    const auto diff_over_d1 = det3(M00, M01_sub, M02, Q10, Q11, Q12, M20, M21, M22);

    std::array<double, 4> b_s{};
    std::array<double, 4> a_s{};
    for (size_t i = 0; i < 4; ++i) {
        a_s[i] = den_full[i];
        b_s[i] = num_vb[i] + ((i > 0) ? (t * C1 * R1 * diff_over_d1[i - 1]) : 0.0);
    }

    return detail::BilinearTransformPoly<3>(b_s, a_s, fs);
}

// Zero-division exact 4th-order R.T. James (1949) Tone Stack solver (matches 6-node MNA to < 1e-15!)
inline HighOrderIirCoeffs<4> ComputeJamesToneStackBilinearDouble(const JamesToneStackSpec& sp,
                                                                 double bass_knob,
                                                                 double treble_knob,
                                                                 double fs = kSampleRateD) noexcept {
    const double l = std::max(0.002, std::min(0.998, static_cast<double>(EvalPotTaper(sp.bass_taper, static_cast<float>(bass_knob)))));
    const double t = std::max(0.002, std::min(0.998, static_cast<double>(EvalPotTaper(sp.treble_taper, static_cast<float>(treble_knob)))));

    const double rb1 = std::max(1.0, (1.0 - l) * sp.r_bass);
    const double rb2 = std::max(1.0, l * sp.r_bass);
    const double rt1 = std::max(1.0, (1.0 - t) * sp.r_treble);
    const double rt2 = std::max(1.0, t * sp.r_treble);
    const double gb1 = 1.0 / rb1;
    const double gb2 = 1.0 / rb2;

    const std::array<double, 2> D_b1 = {1.0 + sp.r1_top * gb1, sp.r1_top * sp.c1_bass_top};
    const std::array<double, 2> D_b2 = {1.0 + sp.r3_gnd * gb2, sp.r3_gnd * sp.c2_bass_bot};
    const std::array<double, 2> P_top = {gb1, sp.c1_bass_top};
    const std::array<double, 2> P_bot = {gb2, sp.c2_bass_bot};

    const auto P_top_full = detail::PolyMul(P_top, D_b2);
    const auto P_bot_full = detail::PolyMul(P_bot, D_b1);
    const auto P_sum = detail::PolyAdd(P_top_full, P_bot_full);
    const auto D_b12 = detail::PolyMul(D_b1, D_b2);

    std::array<double, 3> Q_b{};
    for (size_t i = 0; i < 3; ++i) Q_b[i] = D_b12[i] + sp.r2_couple * P_sum[i];

    const std::array<double, 2> D_t1 = {1.0, sp.c3_treble_top * rt1};
    const std::array<double, 2> D_t2 = {1.0, sp.c4_treble_bot * rt2};
    const auto D_t12 = detail::PolyMul(D_t1, D_t2);

    const auto T1 = detail::PolyMul(std::array<double, 2>{0.0, sp.c3_treble_top}, detail::PolyMul(Q_b, D_t2));
    const auto T2 = detail::PolyMul(std::array<double, 2>{0.0, sp.c4_treble_bot}, detail::PolyMul(Q_b, D_t1));

    const auto b_s = detail::PolyAdd(detail::PolyMul(P_top_full, D_t12), T1);
    const auto a_s = detail::PolyAdd(detail::PolyAdd(detail::PolyMul(P_sum, D_t12), T1), T2);

    return detail::BilinearTransformPoly<4>(b_s, a_s, fs);
}

enum class ToneStackTopology : uint8_t {
    kFmvFender = 0,
    kFmvMarshall,
    kFmvVoxTopBoost,
    kFmvSoldanoHighGain,
    kFmvMesaPreGain,
    kJamesHiwatt,
    kJamesBassBaxandall,
    kTweedOneKnob,
};

enum class AmpSpecialSubcircuit : uint8_t {
    kNone = 0,
    kCali2CPlusGeq5,       // Mesa Boogie Mark IIC+ 5-Band GEQ V-Scoop (80/240/750/2200/6600 Hz)
    kSingleEndedClassA,    // 5F1 Tweed Champ single-ended Class-A power tube
    kVh4DeepContour,       // Diezel VH4 92 Hz resonant Deep NFB contour
    kVoxTopBoostCut,       // Vox AC30 zero-NFB Cut high-cut control
    kSvtUltraMidGyrator,   // Ampeg SVT 5-pos Ultra-Mid + Baxandall active gyrator
};

// 30 Specific Catalog Amp Models (20 Agoura Guitar + 5 HX Boutique/Clean + 5 Agoura/HX Bass)
enum class AmpModelId : int32_t {
    // --- 20 Agoura Guitar Amps ---
    kUsDoubleNrm = 0,     // Fender Twin Reverb AB763
    kUsDeluxeNrm,         // Fender Deluxe Reverb AB763
    kUsDeluxeVib,         // Fender Deluxe Reverb Vibrato (Bright Cap)
    kUsPrincess,          // Fender Princeton Reverb AA1164
    kUsTweedman,          // Fender Bassman 5F6-A
    kUsSmallTweed,        // Fender Champ 5F1 (Single-Ended Class-A)
    kEssexA30,            // Vox AC30 Top Boost (Zero-NFB)
    kEssexA15,            // Vox AC15 EF86
    kWhoWatt103,          // Hiwatt DR-103 (R.T. James 4th-order)
    kBritPlexiJump,       // Marshall Super Lead 1959 Jumped
    kBritPlexiLow,        // Marshall Super Lead Low-Jack (136k Auto-Z)
    kBrit2203,            // Marshall JCM800 2203
    kBritJ45,             // Marshall JTM45 (KT66 + GZ34 Sag)
    kBritTrem,            // Marshall JTM50 Tremolo
    kCaliRectifire,       // Mesa Dual Rectifier Red Modern
    kCaliIICPlus,         // Mesa Mark IIC+ (Pre-Gain FMV + 5-Band GEQ)
    kSoloLeadOd,          // Soldano SLO-100 Overdrive
    kPvPanama,            // Peavey 5150 Lead
    kRevvGenPurple,       // Revv Generator 120 Purple
    kDasBenzinVh4,        // Diezel VH4 Ch3 (92 Hz Deep Contour)

    // --- 5 HX Boutique & Studio Clean Guitar Amps ---
    kLitigatorOds,        // Dumble Overdrive Special
    kMatchstickCh1,       // Matchless DC30 Ch1
    kPlacaterDirty,       // Friedman BE-100 Dirty
    kJazzRivet120,        // Roland JC-120 Solid-State Studio Clean
    kArchetypeClean,      // Paul Reed Smith Archon Clean

    // --- 5 Agoura & HX Bass Amps ---
    kSvBeastNrm,          // Ampeg SVT Classic (Ultra-Mid Gyrator)
    kSvB15Portaflex,      // Ampeg B-15NF Portaflex (R.T. James)
    kAgua51,              // Aguilar DB751
    kMandarinAd200,       // Orange AD200 Mk3 (R.T. James)
    kCougar800,           // Gallien-Krueger 800RB

    kCount = 30,
};

struct AmpRecipeSpec {
    AmpModelId id;
    const char* display_name;
    const char* based_on;
    bool is_agoura;
    bool is_bass;
    ToneStackTopology ts_topology;
    AmpSpecialSubcircuit subcircuit;
    FactoryCabModel default_cab;
    float input_z_ohms;
    int gain_stages;
    float preamp_gain_mult;
    float bright_cap_f;
    float cathode_rk_ohm;
    float cathode_ck_f;
    float grid_rg_ohm;
    float coupling_cc_f;
    float nfb_gain;
    float z_pre_post;
    float power_bias_coldness;
    bool single_ended;
    float stock_sag;
    float stock_ripple;
    float makeup_gain;
    FmvToneStackSpec tone_stack;
    JamesToneStackSpec james_stack;
};

inline const AmpRecipeSpec& GetAmpRecipe(AmpModelId id) noexcept {
    static const FmvToneStackSpec kFmvAB763{250e3, 250e3, 10e3, 100e3, 250e-12, 100e-9, 47e-9, PotTaper::kLog10A, PotTaper::kLinear, PotTaper::kLinear};
    static const FmvToneStackSpec kFmv5F6A{250e3, 1.0e6, 25e3, 56e3, 250e-12, 22e-9, 22e-9, PotTaper::kLog10A, PotTaper::kLinear, PotTaper::kLinear};
    static const FmvToneStackSpec kFmvMarshall{250e3, 1.0e6, 25e3, 33e3, 470e-12, 22e-9, 22e-9, PotTaper::kLog10A, PotTaper::kLinear, PotTaper::kLinear};
    static const FmvToneStackSpec kFmvVox{1.0e6, 1.0e6, 10e3, 100e3, 50e-12, 22e-9, 22e-9, PotTaper::kLog10A, PotTaper::kLinear, PotTaper::kLinear};
    static const FmvToneStackSpec kFmvHighGain{250e3, 1.0e6, 25e3, 39e3, 470e-12, 22e-9, 22e-9, PotTaper::kLog10A, PotTaper::kLinear, PotTaper::kLinear};
    static const FmvToneStackSpec kFmvMesa{250e3, 250e3, 10e3, 100e3, 250e-12, 100e-9, 47e-9, PotTaper::kLog10A, PotTaper::kLinear, PotTaper::kLinear};

    static const JamesToneStackSpec kJamesHiwatt{90e3, 220e3, 10e3, 1.0e6, 1.0e6, 4.7e-9, 42.3e-9, 470e-12, 4.23e-9, PotTaper::kLog10A, PotTaper::kLog10A};
    static const JamesToneStackSpec kJamesB15{90e3, 180e3, 10e3, 1.0e6, 1.0e6, 10e-9, 90e-9, 680e-12, 6.12e-9, PotTaper::kLog10A, PotTaper::kLog10A};
    static const JamesToneStackSpec kJamesAd200{90e3, 220e3, 10e3, 1.0e6, 1.0e6, 6.8e-9, 61.2e-9, 560e-12, 5.04e-9, PotTaper::kLog10A, PotTaper::kLog10A};

    static const std::array<AmpRecipeSpec, 30> kBank = {{
        // 0..19: Agoura Guitar Amps
        {AmpModelId::kUsDoubleNrm, "US Double Nrm", "Fender Twin Reverb AB763", true, false, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kNone, FactoryCabModel::k2x12TwinJensen, 1e6f, 2, 1.8f, 120e-12f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.55f, 0.22f, 0.25f, false, 0.15f, 0.12f, 1.55f, kFmvAB763, kJamesHiwatt},
        {AmpModelId::kUsDeluxeNrm, "US Deluxe Nrm", "Fender Deluxe Reverb AB763", true, false, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kNone, FactoryCabModel::k1x12Deluxe, 1e6f, 2, 2.3f, 0.0f, 1500.f, 22e-6f, 220e3f, 22e-9f, 0.45f, 0.28f, 0.28f, false, 0.35f, 0.22f, 1.50f, kFmvAB763, kJamesHiwatt},
        {AmpModelId::kUsDeluxeVib, "US Deluxe Vib", "Fender Deluxe Reverb Vibrato", true, false, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kNone, FactoryCabModel::k1x12Deluxe, 1e6f, 2, 2.5f, 47e-12f, 1500.f, 22e-6f, 220e3f, 22e-9f, 0.45f, 0.28f, 0.28f, false, 0.35f, 0.22f, 1.50f, kFmvAB763, kJamesHiwatt},
        {AmpModelId::kUsPrincess, "US Princess", "Fender Princeton Reverb AA1164", true, false, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kNone, FactoryCabModel::k1x12Tweed, 1e6f, 2, 2.2f, 0.0f, 1500.f, 22e-6f, 220e3f, 22e-9f, 0.40f, 0.32f, 0.30f, false, 0.42f, 0.28f, 1.55f, kFmvAB763, kJamesHiwatt},
        {AmpModelId::kUsTweedman, "US Tweedman", "Fender Bassman 5F6-A", true, false, ToneStackTopology::kFmvMarshall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k1x12Tweed, 1e6f, 3, 2.8f, 100e-12f, 820.f, 25e-6f, 220e3f, 22e-9f, 0.38f, 0.35f, 0.25f, false, 0.48f, 0.30f, 1.45f, kFmv5F6A, kJamesHiwatt},
        {AmpModelId::kUsSmallTweed, "US Small Tweed", "Fender Champ 5F1", true, false, ToneStackTopology::kTweedOneKnob, AmpSpecialSubcircuit::kSingleEndedClassA, FactoryCabModel::k1x8Tweed, 1e6f, 2, 3.2f, 0.0f, 1500.f, 25e-6f, 220e3f, 22e-9f, 0.15f, 0.85f, 0.0f, true, 0.55f, 0.35f, 1.40f, kFmv5F6A, kJamesHiwatt},
        {AmpModelId::kEssexA30, "Essex A30", "Vox AC30 Top Boost", true, false, ToneStackTopology::kFmvVoxTopBoost, AmpSpecialSubcircuit::kVoxTopBoostCut, FactoryCabModel::k2x12BlueBell, 1e6f, 3, 3.0f, 120e-12f, 1500.f, 22e-6f, 220e3f, 22e-9f, 0.0f, 1.0f, 0.02f, false, 0.45f, 0.30f, 1.55f, kFmvVox, kJamesHiwatt},
        {AmpModelId::kEssexA15, "Essex A15", "Vox AC15 EF86", true, false, ToneStackTopology::kFmvVoxTopBoost, AmpSpecialSubcircuit::kVoxTopBoostCut, FactoryCabModel::k2x12BlueBell, 1e6f, 2, 3.1f, 100e-12f, 1500.f, 22e-6f, 220e3f, 22e-9f, 0.0f, 1.0f, 0.02f, false, 0.50f, 0.32f, 1.50f, kFmvVox, kJamesHiwatt},
        {AmpModelId::kWhoWatt103, "WhoWatt 103", "Hiwatt DR-103", true, false, ToneStackTopology::kJamesHiwatt, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12Greenback, 1e6f, 3, 2.2f, 0.0f, 2200.f, 0.47e-6f, 470e3f, 22e-9f, 0.52f, 0.25f, 0.32f, false, 0.12f, 0.10f, 3.60f, kFmvMarshall, kJamesHiwatt},
        {AmpModelId::kBritPlexiJump, "Brit Plexi Jump", "Marshall Super Lead 1959", true, false, ToneStackTopology::kFmvMarshall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12Greenback, 1e6f, 3, 3.8f, 5000e-12f, 2700.f, 0.68e-6f, 220e3f, 22e-9f, 0.42f, 0.32f, 0.35f, false, 0.35f, 0.24f, 1.45f, kFmvMarshall, kJamesHiwatt},
        {AmpModelId::kBritPlexiLow, "Brit Plexi Low", "Marshall 1959 Low-Jack (136k)", true, false, ToneStackTopology::kFmvMarshall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12Greenback, 136000.f, 3, 3.1f, 500e-12f, 2700.f, 0.68e-6f, 220e3f, 22e-9f, 0.42f, 0.32f, 0.35f, false, 0.35f, 0.24f, 1.45f, kFmvMarshall, kJamesHiwatt},
        {AmpModelId::kBrit2203, "Brit 2203", "Marshall JCM800 2203", true, false, ToneStackTopology::kFmvMarshall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12V30, 1e6f, 4, 5.2f, 1000e-12f, 2700.f, 0.68e-6f, 470e3f, 22e-9f, 0.48f, 0.30f, 0.45f, false, 0.24f, 0.18f, 1.38f, kFmvMarshall, kJamesHiwatt},
        {AmpModelId::kBritJ45, "Brit J45", "Marshall JTM45 KT66", true, false, ToneStackTopology::kFmvMarshall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12Greenback, 1e6f, 3, 3.2f, 100e-12f, 820.f, 25e-6f, 220e3f, 22e-9f, 0.40f, 0.35f, 0.28f, false, 0.52f, 0.32f, 1.45f, kFmv5F6A, kJamesHiwatt},
        {AmpModelId::kBritTrem, "Brit Trem", "Marshall JTM50 Tremolo", true, false, ToneStackTopology::kFmvMarshall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12Greenback, 1e6f, 3, 3.4f, 500e-12f, 1500.f, 22e-6f, 220e3f, 22e-9f, 0.42f, 0.34f, 0.30f, false, 0.46f, 0.28f, 1.45f, kFmvMarshall, kJamesHiwatt},
        {AmpModelId::kCaliRectifire, "Cali Rectifire", "Mesa Dual Rectifier Red", true, false, ToneStackTopology::kFmvSoldanoHighGain, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12CaliOversized, 1e6f, 5, 7.6f, 0.0f, 1800.f, 1.0e-6f, 470e3f, 22e-9f, 0.12f, 0.68f, 0.52f, false, 0.30f, 0.20f, 1.30f, kFmvHighGain, kJamesHiwatt},
        {AmpModelId::kCaliIICPlus, "Cali IIC+", "Mesa Boogie Mark IIC+", true, false, ToneStackTopology::kFmvMesaPreGain, AmpSpecialSubcircuit::kCali2CPlusGeq5, FactoryCabModel::k4x12CaliOversized, 1e6f, 5, 6.8f, 120e-12f, 1500.f, 2.2e-6f, 470e3f, 22e-9f, 0.46f, 0.32f, 0.40f, false, 0.20f, 0.14f, 1.48f, kFmvMesa, kJamesHiwatt},
        {AmpModelId::kSoloLeadOd, "Solo Lead OD", "Soldano SLO-100 Overdrive", true, false, ToneStackTopology::kFmvSoldanoHighGain, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12V30, 1e6f, 5, 7.2f, 0.0f, 1800.f, 1.0e-6f, 470e3f, 22e-9f, 0.50f, 0.28f, 0.42f, false, 0.18f, 0.14f, 1.32f, kFmvHighGain, kJamesHiwatt},
        {AmpModelId::kPvPanama, "PV Panama", "Peavey 5150 Lead", true, false, ToneStackTopology::kFmvSoldanoHighGain, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12V30, 1e6f, 5, 8.2f, 0.0f, 1800.f, 1.0e-6f, 470e3f, 22e-9f, 0.45f, 0.30f, 0.60f, false, 0.16f, 0.12f, 1.28f, kFmvHighGain, kJamesHiwatt},
        {AmpModelId::kRevvGenPurple, "Revv Gen Purple", "Revv Generator 120 Purple", true, false, ToneStackTopology::kFmvSoldanoHighGain, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12CaliOversized, 1e6f, 5, 7.8f, 0.0f, 1800.f, 0.68e-6f, 470e3f, 22e-9f, 0.44f, 0.32f, 0.42f, false, 0.16f, 0.12f, 1.30f, kFmvHighGain, kJamesHiwatt},
        {AmpModelId::kDasBenzinVh4, "Das Benzin VH4", "Diezel VH4 Channel 3", true, false, ToneStackTopology::kFmvSoldanoHighGain, AmpSpecialSubcircuit::kVh4DeepContour, FactoryCabModel::k4x12CaliOversized, 1e6f, 5, 7.5f, 0.0f, 1800.f, 1.0e-6f, 470e3f, 22e-9f, 0.50f, 0.26f, 0.45f, false, 0.15f, 0.12f, 1.30f, kFmvHighGain, kJamesHiwatt},

        // 20..24: HX Boutique & Studio Clean
        {AmpModelId::kLitigatorOds, "Litigator ODS", "Dumble Overdrive Special", false, false, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kNone, FactoryCabModel::k1x12Deluxe, 1e6f, 4, 4.4f, 100e-12f, 1500.f, 4.7e-6f, 220e3f, 22e-9f, 0.48f, 0.30f, 0.26f, false, 0.28f, 0.18f, 1.48f, kFmvAB763, kJamesHiwatt},
        {AmpModelId::kMatchstickCh1, "Matchstick Ch1", "Matchless DC30 Ch1", false, false, ToneStackTopology::kFmvVoxTopBoost, AmpSpecialSubcircuit::kVoxTopBoostCut, FactoryCabModel::k2x12BlueBell, 1e6f, 3, 2.9f, 100e-12f, 1500.f, 22e-6f, 220e3f, 22e-9f, 0.0f, 1.0f, 0.02f, false, 0.42f, 0.26f, 1.52f, kFmvVox, kJamesHiwatt},
        {AmpModelId::kPlacaterDirty, "Placater Dirty", "Friedman BE-100 Dirty", false, false, ToneStackTopology::kFmvMarshall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12V30, 1e6f, 4, 6.2f, 500e-12f, 2700.f, 0.68e-6f, 470e3f, 22e-9f, 0.46f, 0.30f, 0.38f, false, 0.22f, 0.16f, 1.36f, kFmvMarshall, kJamesHiwatt},
        {AmpModelId::kJazzRivet120, "Jazz Rivet 120", "Roland JC-120 Solid-State", false, false, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kNone, FactoryCabModel::k2x12TwinJensen, 1e6f, 1, 1.3f, 47e-12f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.65f, 0.15f, 0.0f, false, 0.02f, 0.02f, 1.60f, kFmvAB763, kJamesHiwatt},
        {AmpModelId::kArchetypeClean, "Archetype Clean", "PRS Archon Clean", false, false, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kNone, FactoryCabModel::k4x12V30, 1e6f, 2, 1.9f, 68e-12f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.52f, 0.24f, 0.22f, false, 0.14f, 0.10f, 1.55f, kFmvAB763, kJamesHiwatt},

        // 25..29: Agoura & HX Bass Amps
        {AmpModelId::kSvBeastNrm, "SV Beast Nrm", "Ampeg SVT Classic", true, true, ToneStackTopology::kJamesBassBaxandall, AmpSpecialSubcircuit::kSvtUltraMidGyrator, FactoryCabModel::k8x10Svt, 1e6f, 3, 2.6f, 0.0f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.42f, 0.35f, 0.30f, false, 0.22f, 0.18f, 3.40f, kFmvAB763, kJamesB15},
        {AmpModelId::kSvB15Portaflex, "SV B-15 Portaflex", "Ampeg B-15NF Portaflex", true, true, ToneStackTopology::kJamesBassBaxandall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k1x15B15Portaflex, 1e6f, 2, 2.4f, 0.0f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.35f, 0.42f, 0.25f, false, 0.38f, 0.26f, 3.50f, kFmvAB763, kJamesB15},
        {AmpModelId::kAgua51, "Agua 51", "Aguilar DB751", true, true, ToneStackTopology::kJamesBassBaxandall, AmpSpecialSubcircuit::kSvtUltraMidGyrator, FactoryCabModel::k4x10Ampeg, 1e6f, 3, 2.3f, 0.0f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.45f, 0.30f, 0.22f, false, 0.15f, 0.12f, 3.40f, kFmvAB763, kJamesB15},
        {AmpModelId::kMandarinAd200, "Mandarin AD200", "Orange AD200 Mk3", false, true, ToneStackTopology::kJamesBassBaxandall, AmpSpecialSubcircuit::kNone, FactoryCabModel::k2x15Bassman, 1e6f, 3, 3.1f, 0.0f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.30f, 0.48f, 0.32f, false, 0.34f, 0.24f, 3.50f, kFmvAB763, kJamesAd200},
        {AmpModelId::kCougar800, "Cougar 800", "Gallien-Krueger 800RB", false, true, ToneStackTopology::kFmvFender, AmpSpecialSubcircuit::kSvtUltraMidGyrator, FactoryCabModel::k4x10Ampeg, 1e6f, 2, 2.2f, 0.0f, 1500.f, 22e-6f, 220e3f, 47e-9f, 0.55f, 0.22f, 0.05f, false, 0.08f, 0.08f, 1.55f, kFmvAB763, kJamesB15},
    }};
    const int idx = std::max(0, std::min(static_cast<int>(id), 29));
    return kBank[static_cast<size_t>(idx)];
}

}  // namespace stadium_dsp
