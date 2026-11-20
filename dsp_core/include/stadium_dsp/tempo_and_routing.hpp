#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include "stadium_dsp/biquad_anticramp.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// GAP-11: Global/Preset BPM TempoClock + Outlier-Filtered 4-Tap TAP Tempo + Note Sync Subdivisions
enum class NoteSubdivision : int32_t {
    kOff = 0,
    kWhole,         // 4 beats
    kHalf,          // 2 beats
    kQuarter,       // 1 beat
    kDottedEighth,  // 0.75 beat
    kEighth,        // 0.5 beat
    kEighthTriplet, // 1/3 beat
    kSixteenth,     // 0.25 beat
    kSixteenthDot,  // 0.375 beat
    kCount = 9,
};

class TempoClock {
public:
    void SetBpm(float bpm) noexcept { bpm_ = Clamp(bpm, 40.0f, 240.0f); }
    float GetBpm() const noexcept { return bpm_; }

    // Registers a tap timestamp in milliseconds; filters out pauses (> 2.0s) and outliers
    void RegisterTapMs(int64_t timestamp_ms) noexcept {
        if (last_tap_ms_ > 0) {
            const int64_t dt = timestamp_ms - last_tap_ms_;
            if (dt >= 200 && dt <= 1800) {
                intervals_[interval_w_] = static_cast<float>(dt);
                interval_w_ = (interval_w_ + 1) & 3;
                if (interval_count_ < 4) ++interval_count_;

                // Compute outlier-filtered average (drop entries > 35% away from median)
                std::array<float, 4> sorted{};
                for (int i = 0; i < interval_count_; ++i) sorted[i] = intervals_[i];
                std::sort(sorted.begin(), sorted.begin() + interval_count_);
                const float med = sorted[interval_count_ / 2];
                float sum = 0.0f;
                int kept = 0;
                for (int i = 0; i < interval_count_; ++i) {
                    if (std::fabs(sorted[i] - med) <= 0.35f * med) {
                        sum += sorted[i];
                        ++kept;
                    }
                }
                if (kept > 0) {
                    const float avg_ms = sum / static_cast<float>(kept);
                    bpm_ = Clamp(60000.0f / avg_ms, 40.0f, 240.0f);
                }
            } else if (dt > 1800) {
                interval_count_ = 0;
                interval_w_ = 0;
            }
        }
        last_tap_ms_ = timestamp_ms;
    }

    float SubdivisionToMs(NoteSubdivision sub) const noexcept {
        const float quarter_ms = 60000.0f / bpm_;
        switch (sub) {
            case NoteSubdivision::kWhole:         return quarter_ms * 4.0f;
            case NoteSubdivision::kHalf:          return quarter_ms * 2.0f;
            case NoteSubdivision::kQuarter:       return quarter_ms;
            case NoteSubdivision::kDottedEighth:  return quarter_ms * 0.75f;
            case NoteSubdivision::kEighth:        return quarter_ms * 0.5f;
            case NoteSubdivision::kEighthTriplet: return quarter_ms * (1.0f / 3.0f);
            case NoteSubdivision::kSixteenth:     return quarter_ms * 0.25f;
            case NoteSubdivision::kSixteenthDot:  return quarter_ms * 0.375f;
            default:                              return quarter_ms;
        }
    }

    float SubdivisionToHz(NoteSubdivision sub) const noexcept {
        const float ms = SubdivisionToMs(sub);
        return (ms > 1.0f) ? (1000.0f / ms) : 1.0f;
    }

private:
    float bpm_ = 120.0f;
    int64_t last_tap_ms_ = 0;
    std::array<float, 4> intervals_{};
    int interval_w_ = 0;
    int interval_count_ = 0;
};

// GAP-01: Parallel Path 1A / 1B Split & Merge Mixer Nodes
enum class SplitMode : int32_t {
    kBypassSerial = 0,  // Serial single path (no parallel branch)
    kSplitY,            // Identical copy (or L/R balance) to Path 1A & Path 1B
    kSplitAB,           // Continuous A/B route balance (0.0 = 100% A .. 1.0 = 100% B)
    kCrossoverLR4,      // Linkwitz-Riley 4th-order frequency split (Lo -> 1A, Hi -> 1B)
};

struct ParallelRoutingParams {
    SplitMode split_mode = SplitMode::kBypassSerial;
    float crossover_hz = 400.0f;
    float route_ab = 0.5f;         // 0.0 = A, 0.5 = Equal, 1.0 = B
    float merge_level_a_db = 0.0f;
    float merge_level_b_db = 0.0f;
    float merge_pan_a = 0.0f;
    float merge_pan_b = 0.0f;
    bool invert_phase_b = false;
};

class ParallelSplitMergeNode {
public:
    ParallelSplitMergeNode() noexcept { SetParams(ParallelRoutingParams{}); }

    void Reset() noexcept {
        lp1_.Reset(); lp2_.Reset();
        hp1_.Reset(); hp2_.Reset();
    }

    void SetParams(const ParallelRoutingParams& p) noexcept {
        params_ = p;
        const double fc = Clamp(p.crossover_hz, 60.0f, 8000.0f);
        const auto lp = DesignLowPass(fc, 0.70710678, kSampleRateD);
        const auto hp = DesignHighPass(fc, 0.70710678, kSampleRateD);
        lp1_.SetCoeffs(lp); lp2_.SetCoeffs(lp);
        hp1_.SetCoeffs(hp); hp2_.SetCoeffs(hp);
    }

    const ParallelRoutingParams& GetParams() const noexcept { return params_; }
    bool IsParallelActive() const noexcept { return params_.split_mode != SplitMode::kBypassSerial; }

    void SplitMono16(const float* in16, float* path_a16, float* path_b16) noexcept {
        if (params_.split_mode == SplitMode::kSplitY) {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                path_a16[i] = in16[i];
                path_b16[i] = in16[i];
            }
        } else if (params_.split_mode == SplitMode::kSplitAB) {
            const float b_w = Clamp(params_.route_ab, 0.0f, 1.0f);
            const float a_w = 1.0f - b_w;
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                path_a16[i] = in16[i] * a_w;
                path_b16[i] = in16[i] * b_w;
            }
        } else if (params_.split_mode == SplitMode::kCrossoverLR4) {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                const float x = in16[i];
                path_a16[i] = lp2_.Process(lp1_.Process(x));
                path_b16[i] = hp2_.Process(hp1_.Process(x));
            }
        } else {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                path_a16[i] = in16[i];
                path_b16[i] = 0.0f;
            }
        }
    }

    void MergeStereo16(const float* a_l, const float* a_r,
                       const float* b_l, const float* b_r,
                       float* out_l, float* out_r) noexcept {
        const float ga = DbToLin(params_.merge_level_a_db);
        const float gb = DbToLin(params_.merge_level_b_db) * (params_.invert_phase_b ? -1.0f : 1.0f);

        const float ang_a = (Clamp(params_.merge_pan_a, -1.0f, 1.0f) + 1.0f) * (kPi * 0.25f);
        const float ang_b = (Clamp(params_.merge_pan_b, -1.0f, 1.0f) + 1.0f) * (kPi * 0.25f);
        const float gal = std::cos(ang_a) * 1.41421356f * ga;
        const float gar = std::sin(ang_a) * 1.41421356f * ga;
        const float gbl = std::cos(ang_b) * 1.41421356f * gb;
        const float gbr = std::sin(ang_b) * 1.41421356f * gb;

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            out_l[i] = a_l[i] * gal + b_l[i] * gbl;
            out_r[i] = a_r[i] * gar + b_r[i] * gbr;
        }
    }

private:
    ParallelRoutingParams params_{};
    BiquadDF2T lp1_, lp2_, hp1_, hp2_;
};

}  // namespace stadium_dsp
