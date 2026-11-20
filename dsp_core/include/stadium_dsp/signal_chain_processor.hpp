#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

#include "stadium_dsp/agoura_tube_amp.hpp"
#include "stadium_dsp/chromatic_tuner.hpp"
#include "stadium_dsp/drive_fuzz_kernel.hpp"
#include "stadium_dsp/dual_cab_convolver.hpp"
#include "stadium_dsp/dynamic_fdn_reverb.hpp"
#include "stadium_dsp/dynamics_gate_comp.hpp"
#include "stadium_dsp/guitar_input_z.hpp"
#include "stadium_dsp/lockfree_spsc.hpp"
#include "stadium_dsp/modulation_bank.hpp"
#include "stadium_dsp/neural_clone_engine.hpp"
#include "stadium_dsp/pitch_synth_kernel.hpp"
#include "stadium_dsp/preset_clip_player.hpp"
#include "stadium_dsp/simd_utils.hpp"
#include "stadium_dsp/tape_bbd_delay.hpp"
#include "stadium_dsp/tempo_and_routing.hpp"
#include "stadium_dsp/volume_pan_looper.hpp"
#include "stadium_dsp/wah_parametric_eq.hpp"

namespace stadium_dsp {

enum class RigRoutingMode : int32_t {
    kAgouraAmpAndCab = 0,
    kNeuralCloneAndCab,
    kNeuralFullRigNoCab,
};

enum class RigBlockType : uint8_t {
    kGate = 0,
    kPitch,
    kComp,
    kWah,
    kDrive,
    kModPre,
    kAmp,
    kCab,
    kEq,
    kVolPan,
    kModPost,
    kDelay,
    kReverb,
    kCount = 13,
};

// Lightweight Snapshot Delta (GAP-14): recalls bypasses & core parameters on active graph
// with smooth interpolation and ZERO ResetAll(), preserving Delay/Reverb spillover trails!
struct SnapshotSlotData {
    bool valid = false;
    bool gate_enabled = false;
    bool pitch_enabled = false;
    bool comp_enabled = false;
    bool wah_enabled = false;
    bool drive_enabled = false;
    bool mod_enabled = false;
    bool eq_enabled = false;
    bool vol_enabled = false;
    bool delay_enabled = true;
    bool reverb_enabled = true;

    float amp_drive = 0.50f;
    float amp_master = 0.55f;
    float amp_ch_vol = 0.70f;
    float amp_bass = 0.50f;
    float amp_mid = 0.50f;
    float amp_treble = 0.50f;
    float amp_presence = 0.50f;
    float drive_gain = 0.50f;
    float delay_mix = 0.30f;
    float reverb_mix = 0.28f;
    float output_level_db = 0.0f;
};

struct GuitarRigGraph {
    GuitarPickupInputZ auto_z;
    HorizonPrecisionGate gate;
    PitchSynthBlock pitch;
    StudioOptoVcaComp comp;
    InductorWahPedal wah;
    DriveFuzzPedal drive;
    StereoModulationEngine mod_pre;
    AgouraTubeAmp amp;
    NeuralCloneEngine clone;
    DualCabEngine cab;
    StudioEqBlock eq;
    VolumePanBlock vol_pan;
    StereoModulationEngine mod_post;
    StereoTapeBbdDelay delay;
    DynamicFdnReverb reverb;
    ParallelSplitMergeNode split_merge;

    RigRoutingMode routing_mode = RigRoutingMode::kAgouraAmpAndCab;
    GuitarPickupType pickup_type = GuitarPickupType::kSingleCoil;
    bool auto_z_enabled = true;
    float manual_input_z_ohms = 1000000.0f;
    bool amp_cab_link = true;
    bool cab_cab_link = false;

    float input_trim_db = 0.0f;
    float output_level_db = 0.0f;
    float output_pan = 0.0f;

    std::array<RigBlockType, 12> slot_order = {
        RigBlockType::kGate, RigBlockType::kPitch, RigBlockType::kComp, RigBlockType::kWah,
        RigBlockType::kDrive, RigBlockType::kModPre, RigBlockType::kAmp, RigBlockType::kCab,
        RigBlockType::kEq, RigBlockType::kVolPan, RigBlockType::kDelay, RigBlockType::kReverb,
    };

    float ResolveAutoInputImpedanceOhms() const noexcept;
    void RefreshAutoZ() noexcept;
    void SyncAmpCabImpedance() noexcept;
    void ResetAll() noexcept;
    void ProcessSubBlock16(const float* in_mono16, float* out_l16, float* out_r16,
                           bool* out_stereo_collapsed = nullptr) noexcept;
};

class StadiumSignalChainProcessor {
public:
    StadiumSignalChainProcessor();

    // Real-time audio entry point: slices any burst into 16-sample L1-cache sub-blocks
    // (0 heap alloc, 0 mutex, 0 JNI, 0 file I/O).
    void ProcessAudioBurst(const float* in_mono, float* out_l, float* out_r, size_t num_frames) noexcept;

    // Wait-free control interface (safe to call from UI / MIDI thread)
    bool EnqueueCommand(const DspCommand& cmd) noexcept { return cmd_queue_.Push(cmd); }
    RtTelemetrySnapshot ReadTelemetry() const noexcept;

    // Off-RT asset loaders & state configuration (rebuilds or swaps safely)
    bool LoadExternalIrFromMemory(int slot, const uint8_t* wav_bytes, size_t size, std::string* err);
    bool LoadExternalNamFromMemory(const char* json_bytes, size_t size, std::string* err);
    bool LoadExternalAidaxFromMemory(const char* json_bytes, size_t size, std::string* err);
    void LoadCustomPresetClipDi(const float* samples, size_t count);

    // Direct configuration helpers for host tests & JNI setup
    void EnableAllBlocksForBenchmark();
    void ConfigureDelayReverbForSpilloverTest();
    void RequestRcuGraphSwap();
    void SetDynamicSlotOrder(const std::array<RigBlockType, 12>& order);
    void SetParallelSplitMerge(SplitMode mode, float crossover_hz, float route_ab,
                               float lvl_a_db, float lvl_b_db, float pan_a, float pan_b,
                               bool invert_b);
    void StoreSnapshot(int idx, const SnapshotSlotData& data);
    void RecallSnapshot(int idx);
    void RegisterTapTempoMs(int64_t timestamp_ms);
    void ComputeEqCurveDb(float* out_db, size_t num_points) const;
    void ComputeCabSpectrumDb(int slot, float* out_db, size_t num_bins) const;
    CloneMetadata GetActiveCloneMetadata() const;
    void SetRtCpuLoadPct(float pct) noexcept { rt_cpu_load_pct_.store(pct, std::memory_order_relaxed); }

    DryClipAuditionPlayer& GetClipPlayer() noexcept { return clip_player_; }
    const TempoClock& GetTempoClock() const noexcept { return tempo_clock_; }

private:
    void DrainCommands() noexcept;
    void ApplyCommand(const DspCommand& cmd) noexcept;
    void ApplySnapshotToActiveGraph(int idx) noexcept;
    static inline float SoftCeilingC1(float x) noexcept;

    std::unique_ptr<GuitarRigGraph> graph_a_;
    std::unique_ptr<GuitarRigGraph> graph_b_;
    std::atomic<GuitarRigGraph*> active_graph_{nullptr};
    std::atomic<GuitarRigGraph*> pending_graph_{nullptr};

    // RCU 64-sample raised-cosine crossfade state
    GuitarRigGraph* prev_graph_ = nullptr;
    size_t rcu_xfade_pos_ = 0;
    static constexpr size_t kRcuXfadeLen = 64;

    // Shared stage utilities (persistent across snapshots & graph swaps!)
    ChromaticTuner tuner_;
    TempoClock tempo_clock_;
    StereoLooperBlock looper_;
    DryClipAuditionPlayer clip_player_;

    // 8 Snapshots bank
    std::array<SnapshotSlotData, 8> snapshots_{};
    int32_t active_snapshot_ = 0;

    // Clickless 5 ms (240 samples @ 48 kHz) MasterMute cosine ramp
    bool master_mute_target_ = false;
    float mute_ramp_pos_ = 0.0f;  // 0.0 = unmuted (gain 1), 1.0 = muted (gain 0)

    // Delay / Mod tempo beat-sync states
    NoteSubdivision delay_sync_ = NoteSubdivision::kOff;
    NoteSubdivision mod_sync_ = NoteSubdivision::kOff;

    SpscRingBuffer<DspCommand, 512> cmd_queue_;

    // Lock-free double-buffered telemetry snapshot
    std::array<RtTelemetrySnapshot, 2> telemetry_buf_{};
    std::atomic<int> telemetry_read_idx_{0};
    std::atomic<float> rt_cpu_load_pct_{0.0f};
};

}  // namespace stadium_dsp
