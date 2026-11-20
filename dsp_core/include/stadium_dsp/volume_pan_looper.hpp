#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "stadium_dsp/pot_tapers.hpp"
#include "stadium_dsp/simd_utils.hpp"

namespace stadium_dsp {

// GAP-04: Dedicated Volume Pedal & Stereo Pan/Width Block (EXP 2 Cyan #0FFFFF)
struct VolumePanParams {
    bool enabled = false;
    float position = 1.0f;     // 0.0 .. 1.0 (EXP 2)
    bool logarithmic = true;   // Audio log taper vs linear taper
    float pan = 0.0f;          // -1.0 (L) .. +1.0 (R)
    float stereo_width = 1.0f; // 0.0 (Mono) .. 2.0 (Wide M/S)
};

class VolumePanBlock {
public:
    void Reset() noexcept {
        smooth_gain_ = EvalTargetGain();
        smooth_pan_ = params_.pan;
    }

    void SetParams(const VolumePanParams& p) noexcept { params_ = p; }
    const VolumePanParams& GetParams() const noexcept { return params_; }

    void ProcessSubBlock16(const float* in_l, const float* in_r, float* out_l, float* out_r) noexcept {
        if (!params_.enabled) {
            for (size_t i = 0; i < kSubBlockSize; ++i) {
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
            }
            return;
        }

        const float target_g = EvalTargetGain();
        const float target_pan = Clamp(params_.pan, -1.0f, 1.0f);
        const float width = Clamp(params_.stereo_width, 0.0f, 2.0f);

        for (size_t i = 0; i < kSubBlockSize; ++i) {
            smooth_gain_ += 0.015f * (target_g - smooth_gain_);
            smooth_pan_ += 0.015f * (target_pan - smooth_pan_);

            // Mid-Side stereo width shaping
            const float mid = 0.5f * (in_l[i] + in_r[i]);
            const float side = 0.5f * (in_l[i] - in_r[i]) * width;
            const float l = mid + side;
            const float r = mid - side;

            // Equal-power stereo balance pan law
            const float ang = (smooth_pan_ + 1.0f) * (kPi * 0.25f);
            const float gl = std::cos(ang) * 1.41421356f * smooth_gain_;
            const float gr = std::sin(ang) * 1.41421356f * smooth_gain_;
            out_l[i] = l * gl;
            out_r[i] = r * gr;
        }
    }

private:
    float EvalTargetGain() const noexcept {
        const float pos = Clamp(params_.position, 0.0f, 1.0f);
        if (pos <= 0.001f) return 0.0f;
        return params_.logarithmic ? EvalPotTaper(PotTaper::kLog10A, pos) : pos;
    }

    VolumePanParams params_{};
    float smooth_gain_ = 1.0f;
    float smooth_pan_ = 0.0f;
};

// GAP-22a: Allocation-free-on-RT Stereo Looper (30 seconds @ 48 kHz + 1-level Undo/Redo + crossfade splice)
enum class LooperState : int32_t {
    kEmpty = 0,
    kRecording = 1,
    kPlaying = 2,
    kOverdubbing = 3,
    kStopped = 4,
};

enum class LooperAction : int32_t {
    kNone = 0,
    kRecord,
    kPlay,
    kOverdub,
    kStop,
    kUndo,
    kClear,
};

class StereoLooperBlock {
public:
    static constexpr size_t kMaxFrames = 48000 * 30;  // 30 seconds stereo @ 48 kHz

    StereoLooperBlock() noexcept {
        buf_l_.assign(kMaxFrames, 0.0f);
        buf_r_.assign(kMaxFrames, 0.0f);
        undo_l_.assign(kMaxFrames, 0.0f);
        undo_r_.assign(kMaxFrames, 0.0f);
    }

    void Reset() noexcept { TriggerAction(LooperAction::kClear); }

    void SetPlaybackLevel(float lvl) noexcept { playback_level_ = Clamp(lvl, 0.0f, 1.5f); }
    LooperState GetState() const noexcept { return state_; }

    float GetProgressNorm() const noexcept {
        if (loop_len_ == 0) return 0.0f;
        return static_cast<float>(play_pos_) / static_cast<float>(loop_len_);
    }

    void TriggerAction(LooperAction act) noexcept {
        switch (act) {
            case LooperAction::kRecord:
                loop_len_ = 0;
                play_pos_ = 0;
                has_undo_ = false;
                state_ = LooperState::kRecording;
                break;
            case LooperAction::kPlay:
                if (state_ == LooperState::kRecording && loop_len_ > 64) {
                    ApplySpliceCrossfade();
                    play_pos_ = 0;
                    state_ = LooperState::kPlaying;
                } else if (loop_len_ > 0) {
                    state_ = LooperState::kPlaying;
                }
                break;
            case LooperAction::kOverdub:
                if (loop_len_ > 0) {
                    // Snapshot current loop layer into pre-allocated undo buffer (0 heap alloc!)
                    std::copy(buf_l_.begin(), buf_l_.begin() + static_cast<ptrdiff_t>(loop_len_), undo_l_.begin());
                    std::copy(buf_r_.begin(), buf_r_.begin() + static_cast<ptrdiff_t>(loop_len_), undo_r_.begin());
                    has_undo_ = true;
                    state_ = LooperState::kOverdubbing;
                }
                break;
            case LooperAction::kStop:
                if (state_ == LooperState::kRecording && loop_len_ > 64) {
                    ApplySpliceCrossfade();
                }
                if (loop_len_ > 0) state_ = LooperState::kStopped;
                break;
            case LooperAction::kUndo:
                if (has_undo_ && loop_len_ > 0) {
                    std::swap_ranges(buf_l_.begin(), buf_l_.begin() + static_cast<ptrdiff_t>(loop_len_), undo_l_.begin());
                    std::swap_ranges(buf_r_.begin(), buf_r_.begin() + static_cast<ptrdiff_t>(loop_len_), undo_r_.begin());
                    state_ = LooperState::kPlaying;
                }
                break;
            case LooperAction::kClear:
                loop_len_ = 0;
                play_pos_ = 0;
                has_undo_ = false;
                state_ = LooperState::kEmpty;
                break;
            default:
                break;
        }
    }

    void ProcessSubBlock16(const float* in_l, const float* in_r, float* out_l, float* out_r) noexcept {
        for (size_t i = 0; i < kSubBlockSize; ++i) {
            const float dl = in_l[i];
            const float dr = in_r[i];

            if (state_ == LooperState::kRecording) {
                if (loop_len_ < kMaxFrames) {
                    buf_l_[loop_len_] = dl;
                    buf_r_[loop_len_] = dr;
                    ++loop_len_;
                } else {
                    ApplySpliceCrossfade();
                    play_pos_ = 0;
                    state_ = LooperState::kPlaying;
                }
                out_l[i] = dl;
                out_r[i] = dr;
            } else if (state_ == LooperState::kPlaying && loop_len_ > 0) {
                out_l[i] = dl + buf_l_[play_pos_] * playback_level_;
                out_r[i] = dr + buf_r_[play_pos_] * playback_level_;
                play_pos_ = (play_pos_ + 1 == loop_len_) ? 0 : (play_pos_ + 1);
            } else if (state_ == LooperState::kOverdubbing && loop_len_ > 0) {
                const float prev_l = buf_l_[play_pos_];
                const float prev_r = buf_r_[play_pos_];
                buf_l_[play_pos_] = Clamp(prev_l + dl, -2.0f, 2.0f);
                buf_r_[play_pos_] = Clamp(prev_r + dr, -2.0f, 2.0f);
                out_l[i] = dl + prev_l * playback_level_;
                out_r[i] = dr + prev_r * playback_level_;
                play_pos_ = (play_pos_ + 1 == loop_len_) ? 0 : (play_pos_ + 1);
            } else {
                out_l[i] = dl;
                out_r[i] = dr;
            }
        }
    }

private:
    void ApplySpliceCrossfade() noexcept {
        // Clickless 32-sample raised-cosine boundary taper at loop start/end
        constexpr size_t kFade = 32;
        if (loop_len_ <= kFade * 2) return;
        for (size_t i = 0; i < kFade; ++i) {
            const float w = 0.5f * (1.0f - std::cos(kPi * static_cast<float>(i) / static_cast<float>(kFade)));
            buf_l_[i] *= w;
            buf_r_[i] *= w;
            buf_l_[loop_len_ - 1 - i] *= w;
            buf_r_[loop_len_ - 1 - i] *= w;
        }
    }

    std::vector<float> buf_l_, buf_r_;
    std::vector<float> undo_l_, undo_r_;
    size_t loop_len_ = 0;
    size_t play_pos_ = 0;
    bool has_undo_ = false;
    float playback_level_ = 1.0f;
    LooperState state_ = LooperState::kEmpty;
};

}  // namespace stadium_dsp
