#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace stadium_dsp {

template <typename T, size_t Capacity>
class SpscRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of 2");

public:
    bool Push(const T& item) noexcept {
        const size_t w = write_idx_.load(std::memory_order_relaxed);
        const size_t next = (w + 1) & (Capacity - 1);
        if (next == read_idx_.load(std::memory_order_acquire)) {
            return false;
        }
        buffer_[w] = item;
        write_idx_.store(next, std::memory_order_release);
        return true;
    }

    bool Pop(T& out) noexcept {
        const size_t r = read_idx_.load(std::memory_order_relaxed);
        if (r == write_idx_.load(std::memory_order_acquire)) {
            return false;
        }
        out = buffer_[r];
        read_idx_.store((r + 1) & (Capacity - 1), std::memory_order_release);
        return true;
    }

    void Clear() noexcept {
        read_idx_.store(write_idx_.load(std::memory_order_acquire), std::memory_order_release);
    }

private:
    alignas(64) std::atomic<size_t> write_idx_{0};
    alignas(64) std::atomic<size_t> read_idx_{0};
    alignas(64) std::array<T, Capacity> buffer_{};
};

enum class DspCommandType : uint16_t {
    kNone = 0,
    kSetAmpModel,
    kSetAmpParam,
    kSetAmpFocusXY,
    kSetCloneParam,
    kSetCabSlotParam,
    kSetDriveFuzz,
    kSetDynamics,
    kSetWahFilter,
    kSetEqParam,
    kSetModulation,
    kSetPitchSynth,
    kSetDelayReverb,
    kSetVolumePan,
    kSetIoTrimPan,
    kSetMasterMute,
    kSetBlockBypass,
    kSetBlockStereo,
    kSetRoutingSlotOrder,
    kSetParallelSplitMerge,
    kSetTunerConfig,
    kSetGlobalBpm,
    kSetLooperCommand,
    kSetPresetClipCommand,
    kRecallSnapshot,
    kSetAutoZConfig,
    kSetRigRoutingMode,
};

struct DspCommand {
    DspCommandType type = DspCommandType::kNone;
    uint16_t target_id = 0;
    uint16_t param_id = 0;
    uint16_t flags = 0;
    float value0 = 0.0f;
    float value1 = 0.0f;
    float value2 = 0.0f;
    int32_t int_val = 0;
};

struct RtTelemetrySnapshot {
    float in_peak_dbfs = -96.0f;
    float out_peak_l_dbfs = -96.0f;
    float out_peak_r_dbfs = -96.0f;
    float gate_gr_db = 0.0f;
    float comp_gr_db = 0.0f;
    float tuner_freq_hz = 0.0f;
    float tuner_cents = 0.0f;
    float tuner_confidence = 0.0f;
    float tuner_strobe_phase = 0.0f;
    float clip_playhead_norm = 0.0f;
    float looper_progress = 0.0f;
    float rt_cpu_load_pct = 0.0f;
    int32_t tuner_midi_note = -1;
    int32_t looper_state = 0;
    int32_t active_snapshot = 0;
    uint32_t status_flags = 0;  // bit0=in_clip, bit1=out_clip, bit2=muted, bit3=clip_playing, bit4=stereo_collapsed

    bool InClipped() const noexcept { return (status_flags & 1u) != 0u; }
    bool OutClipped() const noexcept { return (status_flags & 2u) != 0u; }
    bool IsMasterMuted() const noexcept { return (status_flags & 4u) != 0u; }
    bool IsClipPlaying() const noexcept { return (status_flags & 8u) != 0u; }
    bool IsStereoCollapsed() const noexcept { return (status_flags & 16u) != 0u; }
};

}  // namespace stadium_dsp
