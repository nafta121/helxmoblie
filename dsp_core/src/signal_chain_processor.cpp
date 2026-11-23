#include "stadium_dsp/signal_chain_processor.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace stadium_dsp {

float GuitarRigGraph::ResolveAutoInputImpedanceOhms() const noexcept {
    if (!auto_z_enabled) return manual_input_z_ohms;
    auto check_slot = [this](RigBlockType blk, int idx) -> float {
        switch (blk) {
            case RigBlockType::kWah: {
                const auto& w = (idx & 1) ? wah_2 : wah;
                if (w.GetParams().enabled) return GetWahAutoZOhms(w.GetParams().model);
                break;
            }
            case RigBlockType::kDrive: {
                const auto& d = (idx & 1) ? drive_2 : drive;
                if (d.GetParams().enabled) return GetDriveAutoZOhms(d.GetParams().model);
                break;
            }
            case RigBlockType::kModPre:
            case RigBlockType::kModPost: {
                const auto& m = (idx & 1) ? mod_post : mod_pre;
                if (m.GetParams().enabled) return GetModAutoZOhms(m.GetParams().model);
                break;
            }
            case RigBlockType::kAmp: {
                const auto& a = (idx & 1) ? amp_2 : amp;
                return GetAmpRecipe(a.GetModel()).input_z_ohms;
            }
            default:
                break;
        }
        return -1.0f;
    };

    if (use_dual_path_plan) {
        for (uint8_t i = 0; i < dual_path_plan.count_1a && i < DualPathRoutingPlan::kMaxSlotsPerBranch; ++i) {
            const float z = check_slot(dual_path_plan.path_1a[i].type, dual_path_plan.path_1a[i].instance_idx);
            if (z > 0.0f) return z;
        }
        return 1000000.0f;
    }

    // Legacy 12-slot scan (Tests 1-18 backwards-compatible)
    for (RigBlockType blk : slot_order) {
        const float z = check_slot(blk, 0);
        if (z > 0.0f) return z;
    }
    return 1000000.0f;
}

void GuitarRigGraph::RefreshAutoZ() noexcept {
    const float target_ohms = ResolveAutoInputImpedanceOhms();
    auto_z.Configure(pickup_type, target_ohms, kSampleRateD);
}

void GuitarRigGraph::SyncAmpCabImpedance() noexcept {
    amp.SetReactiveCabImpedance(cab.GetActiveImpedance());
    amp_2.SetReactiveCabImpedance(cab.GetActiveImpedance());
}

void GuitarRigGraph::ResetAll() noexcept {
    auto_z.Reset();
    gate.Reset(); gate_2.Reset();
    pitch.Reset();
    comp.Reset(); comp_2.Reset();
    wah.Reset(); wah_2.Reset();
    drive.Reset(); drive_2.Reset();
    mod_pre.Reset(); mod_post.Reset();
    amp.Reset(); amp_2.Reset();
    clone.ResetState();
    cab.Reset();
    eq.Reset(); eq_2.Reset();
    vol_pan.Reset(); vol_pan_2.Reset();
    delay.Reset(); delay_2.Reset();
    reverb.Reset(); reverb_2.Reset();
    split_merge.Reset(); split_merge_2.Reset();
}

void GuitarRigGraph::ProcessSlotBlock16(RoutingSlotRef slot, float* buf_l, float* buf_r,
                                        bool& is_stereo_signal, bool& stereo_collapsed) noexcept {
    const int inst = slot.instance_idx & 1;
    auto process_mono_block = [&](auto&& fn) {
        if (is_stereo_signal) {
            stereo_collapsed = true;
            for (size_t i = 0; i < kSubBlockSize; ++i) buf_l[i] = 0.5f * (buf_l[i] + buf_r[i]);
        }
        fn(buf_l, buf_l);
        std::memcpy(buf_r, buf_l, kSubBlockSize * sizeof(float));
        is_stereo_signal = false;
    };

    switch (slot.type) {
        case RigBlockType::kGate: {
            auto& g = GateAt(inst);
            if (!g.GetParams().enabled) return;
            process_mono_block([&](const float* in, float* out) { g.ProcessSubBlock16(in, out); });
            break;
        }
        case RigBlockType::kPitch: {
            if (!pitch.GetParams().enabled) return;
            process_mono_block([&](const float* in, float* out) { pitch.ProcessSubBlock16(in, out); });
            break;
        }
        case RigBlockType::kComp: {
            auto& c = CompAt(inst);
            if (!c.GetParams().enabled) return;
            process_mono_block([&](const float* in, float* out) { c.ProcessSubBlock16(in, out); });
            break;
        }
        case RigBlockType::kWah: {
            auto& w = WahAt(inst);
            if (!w.GetParams().enabled) return;
            process_mono_block([&](const float* in, float* out) { w.ProcessSubBlock16(in, out); });
            break;
        }
        case RigBlockType::kDrive: {
            auto& d = DriveAt(inst);
            if (!d.GetParams().enabled) return;
            process_mono_block([&](const float* in, float* out) { d.ProcessSubBlock16(in, out); });
            break;
        }
        case RigBlockType::kModPre:
        case RigBlockType::kModPost: {
            const int mod_idx = (slot.type == RigBlockType::kModPost) ? 1 : inst;
            auto& m = ModAt(mod_idx);
            if (!m.GetParams().enabled) return;
            if (!m.GetParams().is_stereo && is_stereo_signal) stereo_collapsed = true;
            m.ProcessSubBlock16(buf_l, buf_r, buf_l, buf_r);
            if (m.GetParams().is_stereo) is_stereo_signal = true;
            break;
        }
        case RigBlockType::kAmp: {
            process_mono_block([&](const float* in, float* out) {
                if (inst == 0 && routing_mode != RigRoutingMode::kAgouraAmpAndCab) {
                    clone.ProcessSubBlock16(in, out);
                } else {
                    AmpAt(inst).ProcessSubBlock16(in, out);
                }
            });
            break;
        }
        case RigBlockType::kCab: {
            if (routing_mode != RigRoutingMode::kNeuralFullRigNoCab) {
                cab.ProcessSubBlock16(buf_l, buf_l, buf_r);
                is_stereo_signal = true;
            }
            break;
        }
        case RigBlockType::kEq: {
            auto& e = EqAt(inst);
            if (!e.GetParams().enabled) return;
            if (!e.GetParams().is_stereo && is_stereo_signal) stereo_collapsed = true;
            e.ProcessSubBlock16(buf_l, buf_r, buf_l, buf_r);
            break;
        }
        case RigBlockType::kVolPan: {
            auto& v = VolPanAt(inst);
            if (!v.GetParams().enabled) return;
            v.ProcessSubBlock16(buf_l, buf_r, buf_l, buf_r);
            is_stereo_signal = true;
            break;
        }
        case RigBlockType::kDelay: {
            auto& dl = DelayAt(inst);
            if (!dl.GetParams().enabled) return;
            if (!dl.GetParams().is_stereo && is_stereo_signal) stereo_collapsed = true;
            dl.ProcessSubBlock16(buf_l, buf_r, buf_l, buf_r);
            if (dl.GetParams().is_stereo) is_stereo_signal = true;
            break;
        }
        case RigBlockType::kReverb: {
            auto& rv = ReverbAt(inst);
            if (!rv.GetParams().enabled) return;
            if (!rv.GetParams().is_stereo && is_stereo_signal) stereo_collapsed = true;
            rv.ProcessSubBlock16(buf_l, buf_r, buf_l, buf_r);
            if (rv.GetParams().is_stereo) is_stereo_signal = true;
            break;
        }
        default:
            break;
    }
}

void GuitarRigGraph::ProcessDualPathSubBlock16(const float* in_mono16, float* out_l16, float* out_r16,
                                               bool* out_stereo_collapsed) noexcept {
    alignas(64) float dry_l[kSubBlockSize];
    alignas(64) float p1a_l[kSubBlockSize];
    alignas(64) float p1a_r[kSubBlockSize];
    alignas(64) float p1b_l[kSubBlockSize] = {};
    alignas(64) float p1b_r[kSubBlockSize] = {};
    alignas(64) float p2a_l[kSubBlockSize];
    alignas(64) float p2a_r[kSubBlockSize];
    alignas(64) float p2b_l[kSubBlockSize] = {};
    alignas(64) float p2b_r[kSubBlockSize] = {};

    const float in_gain = DbToLin(Clamp(input_trim_db, -120.0f, 18.0f));
    for (size_t i = 0; i < kSubBlockSize; ++i) {
        dry_l[i] = in_mono16[i] * in_gain;
    }
    auto_z.ProcessSubBlock16(dry_l, dry_l);
    std::memcpy(p1a_l, dry_l, sizeof(dry_l));
    std::memcpy(p1a_r, dry_l, sizeof(dry_l));

    const auto& plan = dual_path_plan;
    const uint8_t c1a = std::min<uint8_t>(plan.count_1a, DualPathRoutingPlan::kMaxSlotsPerBranch);
    const uint8_t c1b = std::min<uint8_t>(plan.count_1b, DualPathRoutingPlan::kMaxSlotsPerBranch);
    const uint8_t c2a = std::min<uint8_t>(plan.count_2a, DualPathRoutingPlan::kMaxSlotsPerBranch);
    const uint8_t c2b = std::min<uint8_t>(plan.count_2b, DualPathRoutingPlan::kMaxSlotsPerBranch);

    bool stereo_1a = false;
    bool stereo_collapsed = false;
    bool has_direct_1b = false;

    const uint8_t s1 = std::min<uint8_t>(plan.split_1_after_index, c1a);
    for (uint8_t i = 0; i < s1; ++i) {
        ProcessSlotBlock16(plan.path_1a[i], p1a_l, p1a_r, stereo_1a, stereo_collapsed);
    }

    if (c1b > 0) {
        split_merge.SplitStereo16(p1a_l, p1a_r, p1a_l, p1a_r, p1b_l, p1b_r);
        bool stereo_1b = stereo_1a;
        for (uint8_t i = s1; i < c1a; ++i) {
            ProcessSlotBlock16(plan.path_1a[i], p1a_l, p1a_r, stereo_1a, stereo_collapsed);
        }
        for (uint8_t i = 0; i < c1b; ++i) {
            ProcessSlotBlock16(plan.path_1b[i], p1b_l, p1b_r, stereo_1b, stereo_collapsed);
        }
        if (plan.merge_1b_into_1a) {
            split_merge.MergeStereo16(p1a_l, p1a_r, p1b_l, p1b_r, p1a_l, p1a_r);
            stereo_1a = true;
        } else {
            has_direct_1b = true;
        }
    } else {
        for (uint8_t i = s1; i < c1a; ++i) {
            ProcessSlotBlock16(plan.path_1a[i], p1a_l, p1a_r, stereo_1a, stereo_collapsed);
        }
    }

    float* final_l = p1a_l;
    float* final_r = p1a_r;

    if (c2a > 0 || c2b > 0) {
        if (plan.path_1a_feeds_2a) {
            std::memcpy(p2a_l, p1a_l, sizeof(p2a_l));
            std::memcpy(p2a_r, p1a_r, sizeof(p2a_r));
        } else {
            std::memcpy(p2a_l, dry_l, sizeof(p2a_l));
            std::memcpy(p2a_r, dry_l, sizeof(p2a_r));
        }
        bool stereo_2a = plan.path_1a_feeds_2a ? stereo_1a : false;
        const uint8_t s2 = std::min<uint8_t>(plan.split_2_after_index, c2a);
        const uint8_t m2 = std::max<uint8_t>(s2, std::min<uint8_t>(plan.merge_2_at_index, c2a));

        for (uint8_t i = 0; i < s2; ++i) {
            ProcessSlotBlock16(plan.path_2a[i], p2a_l, p2a_r, stereo_2a, stereo_collapsed);
        }

        if (c2b > 0) {
            split_merge_2.SplitStereo16(p2a_l, p2a_r, p2a_l, p2a_r, p2b_l, p2b_r);
            bool stereo_2b = stereo_2a;
            for (uint8_t i = s2; i < m2; ++i) {
                ProcessSlotBlock16(plan.path_2a[i], p2a_l, p2a_r, stereo_2a, stereo_collapsed);
            }
            for (uint8_t i = 0; i < c2b; ++i) {
                ProcessSlotBlock16(plan.path_2b[i], p2b_l, p2b_r, stereo_2b, stereo_collapsed);
            }
            split_merge_2.MergeStereo16(p2a_l, p2a_r, p2b_l, p2b_r, p2a_l, p2a_r);
            stereo_2a = true;
            for (uint8_t i = m2; i < c2a; ++i) {
                ProcessSlotBlock16(plan.path_2a[i], p2a_l, p2a_r, stereo_2a, stereo_collapsed);
            }
        } else {
            for (uint8_t i = s2; i < c2a; ++i) {
                ProcessSlotBlock16(plan.path_2a[i], p2a_l, p2a_r, stereo_2a, stereo_collapsed);
            }
        }

        final_l = p2a_l;
        final_r = p2a_r;
    }

    if (has_direct_1b) {
        for (size_t i = 0; i < kSubBlockSize; ++i) {
            final_l[i] = 0.70710678f * (final_l[i] + p1b_l[i]);
            final_r[i] = 0.70710678f * (final_r[i] + p1b_r[i]);
        }
    }

    const float out_gain = DbToLin(Clamp(output_level_db, -120.0f, 18.0f));
    const float pan_ang = (Clamp(output_pan, -1.0f, 1.0f) + 1.0f) * (kPi * 0.25f);
    const float gl = std::cos(pan_ang) * 1.41421356f * out_gain;
    const float gr = std::sin(pan_ang) * 1.41421356f * out_gain;
    for (size_t i = 0; i < kSubBlockSize; ++i) {
        out_l16[i] = final_l[i] * gl;
        out_r16[i] = final_r[i] * gr;
    }

    if (out_stereo_collapsed) *out_stereo_collapsed = stereo_collapsed;
}

void GuitarRigGraph::ProcessSubBlock16(const float* in_mono16, float* out_l16, float* out_r16,
                                       bool* out_stereo_collapsed) noexcept {
    if (use_dual_path_plan) {
        ProcessDualPathSubBlock16(in_mono16, out_l16, out_r16, out_stereo_collapsed);
        return;
    }

    alignas(64) float buf_l[kSubBlockSize];
    alignas(64) float buf_r[kSubBlockSize];
    alignas(64) float par_l[kSubBlockSize];
    alignas(64) float par_r[kSubBlockSize];

    // 1. Input Trim + Passive Guitar Pickup Auto-Z RLC loading
    const float in_gain = DbToLin(Clamp(input_trim_db, -120.0f, 18.0f));
    for (size_t i = 0; i < kSubBlockSize; ++i) {
        buf_l[i] = in_mono16[i] * in_gain;
    }
    auto_z.ProcessSubBlock16(buf_l, buf_l);
    std::memcpy(buf_r, buf_l, sizeof(buf_l));

    // Optional Parallel Split 1A/1B right after Auto-Z (Path 1B carries clean/parallel sub or crossover branch)
    const bool parallel = split_merge.IsParallelActive();
    if (parallel) {
        split_merge.SplitMono16(buf_l, buf_l, par_l);
        std::memcpy(buf_r, buf_l, sizeof(buf_l));
        std::memcpy(par_r, par_l, sizeof(par_l));
    }

    bool is_stereo_signal = false;
    bool stereo_collapsed = false;
    bool merged = false;

    for (RigBlockType blk : slot_order) {
        ProcessSlotBlock16(RoutingSlotRef{blk, 0}, buf_l, buf_r, is_stereo_signal, stereo_collapsed);
        if (blk == RigBlockType::kCab && parallel && !merged) {
            split_merge.MergeStereo16(buf_l, buf_r, par_l, par_r, buf_l, buf_r);
            is_stereo_signal = true;
            merged = true;
        }
    }

    if (parallel && !merged) {
        split_merge.MergeStereo16(buf_l, buf_r, par_l, par_r, buf_l, buf_r);
    }

    // Post-chain Output Level & Pan
    const float out_gain = DbToLin(Clamp(output_level_db, -120.0f, 18.0f));
    const float pan_ang = (Clamp(output_pan, -1.0f, 1.0f) + 1.0f) * (kPi * 0.25f);
    const float gl = std::cos(pan_ang) * 1.41421356f * out_gain;
    const float gr = std::sin(pan_ang) * 1.41421356f * out_gain;
    for (size_t i = 0; i < kSubBlockSize; ++i) {
        out_l16[i] = buf_l[i] * gl;
        out_r16[i] = buf_r[i] * gr;
    }

    if (out_stereo_collapsed) *out_stereo_collapsed = stereo_collapsed;
}

// ============================================================================
// StadiumSignalChainProcessor
// ============================================================================

StadiumSignalChainProcessor::StadiumSignalChainProcessor()
    : graph_a_(std::make_unique<GuitarRigGraph>()),
      graph_b_(std::make_unique<GuitarRigGraph>()) {
    graph_a_->RefreshAutoZ();
    graph_a_->SyncAmpCabImpedance();
    *graph_b_ = *graph_a_;
    active_graph_.store(graph_a_.get(), std::memory_order_release);

    // Initialize default 8 stage snapshots (Clean, Edge, Crunch, Lead, Ambient, Solo, Heavy, Clean-Mod)
    const float drives[8] = {0.22f, 0.40f, 0.58f, 0.78f, 0.30f, 0.85f, 0.90f, 0.25f};
    const bool drv_on[8] = {false, false, true, true, false, true, true, false};
    const float dly_mix[8] = {0.15f, 0.20f, 0.18f, 0.32f, 0.50f, 0.38f, 0.12f, 0.28f};
    const float rev_mix[8] = {0.26f, 0.22f, 0.20f, 0.28f, 0.55f, 0.34f, 0.15f, 0.35f};
    for (int s = 0; s < 8; ++s) {
        snapshots_[static_cast<size_t>(s)].valid = true;
        snapshots_[static_cast<size_t>(s)].amp_drive = drives[s];
        snapshots_[static_cast<size_t>(s)].drive_enabled = drv_on[s];
        snapshots_[static_cast<size_t>(s)].delay_enabled = true;
        snapshots_[static_cast<size_t>(s)].reverb_enabled = true;
        snapshots_[static_cast<size_t>(s)].delay_mix = dly_mix[s];
        snapshots_[static_cast<size_t>(s)].reverb_mix = rev_mix[s];
    }
}

// Continuous-C¹ soft ceiling limiter at -0.10 dBFS (0.98855 linear):
// Transparent below -1.5 dBFS (0.841), smoothly compresses above to asymptotically approach 0.98855.
inline float StadiumSignalChainProcessor::SoftCeilingC1(float x) noexcept {
    constexpr float kCeiling = 0.988553f;  // -0.10 dBFS
    constexpr float kKnee = 0.841395f;     // -1.50 dBFS
    constexpr float kRange = kCeiling - kKnee;
    const float ax = std::fabs(x);
    if (ax <= kKnee) return x;
    const float excess = (ax - kKnee) / kRange;
    const float limited = kKnee + kRange * std::tanh(excess);
    return (x >= 0.0f) ? limited : -limited;
}

void StadiumSignalChainProcessor::DrainCommands() noexcept {
    DspCommand cmd;
    while (cmd_queue_.Pop(cmd)) {
        ApplyCommand(cmd);
    }
}

void StadiumSignalChainProcessor::ApplyCommand(const DspCommand& cmd) noexcept {
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    if (!g) return;
    const int inst = cmd.flags & 1;

    switch (cmd.type) {
        case DspCommandType::kSetAmpModel: {
            const auto id = static_cast<AmpModelId>(std::max(0, std::min(cmd.int_val, static_cast<int>(AmpModelId::kCount) - 1)));
            g->AmpAt(inst).SetModel(id);
            if (inst == 0 && g->amp_cab_link) {
                CabSlotParams cp = g->cab.GetSlotParams(0);
                cp.use_external_ir = false;
                cp.factory_cab = GetAmpRecipe(id).default_cab;
                g->cab.SetSlotParams(0, cp);
            }
            g->RefreshAutoZ();
            g->SyncAmpCabImpedance();
            break;
        }
        case DspCommandType::kSetAmpParam: {
            auto& target_amp = g->AmpAt(inst);
            AmpParams p = target_amp.GetParams();
            switch (cmd.param_id) {
                case 0: p.drive = cmd.value0; break;
                case 1: p.bass = cmd.value0; break;
                case 2: p.mid = cmd.value0; break;
                case 3: p.treble = cmd.value0; break;
                case 4: p.presence = cmd.value0; break;
                case 5: p.master = cmd.value0; break;
                case 6: p.ch_vol = cmd.value0; break;
                case 7: p.sag = cmd.value0; break;
                case 8: p.ripple = cmd.value0; break;
                case 9: p.bias = cmd.value0; break;
                case 10: p.bias_x = cmd.value0; break;
                case 11: p.hype = cmd.value0; break;
                case 12: p.bright_switch = (cmd.int_val != 0); break;
                case 13: p.deep = cmd.value0; break;
                case 14: p.cut = cmd.value0; break;
                case 15: p.geq_v_scoop = cmd.value0; break;
                default: break;
            }
            target_amp.SetParams(p);
            break;
        }
        case DspCommandType::kSetAmpFocusXY:
            g->AmpAt(inst).ApplyFocusViewXY(cmd.value0, cmd.value1);
            break;
        case DspCommandType::kSetCloneParam: {
            NeuralCloneParams p = g->clone.GetParams();
            switch (cmd.param_id) {
                case 0: p.enabled = (cmd.int_val != 0); break;
                case 1: p.input_gain_db = cmd.value0; break;
                case 2: p.output_gain_db = cmd.value0; break;
                case 3: p.interface_max_dbu = cmd.value0; break;
                case 4: p.low_cut_hz = cmd.value0; break;   // GAP-08
                case 5: p.high_cut_hz = cmd.value0; break;  // GAP-08
                default: break;
            }
            g->clone.SetParams(p);
            break;
        }
        case DspCommandType::kSetCabSlotParam: {
            const int slot = (cmd.target_id | cmd.flags) & 1;
            CabSlotParams p = g->cab.GetSlotParams(slot);
            switch (cmd.param_id) {
                case 0: p.enabled = (cmd.int_val != 0); break;
                case 1: p.factory_cab = static_cast<FactoryCabModel>(cmd.int_val); p.use_external_ir = false; break;
                case 2: p.mic_model = static_cast<StudioMicModel>(cmd.int_val); break;
                case 3: p.position = cmd.value0; break;
                case 4: p.distance_inch = cmd.value0; break;
                case 5: p.angle_deg = cmd.value0; break;
                case 6: p.low_cut_hz = cmd.value0; break;
                case 7: p.high_cut_hz = cmd.value0; break;
                case 8: p.level_db = cmd.value0; break;
                case 9: p.pan = cmd.value0; break;
                case 10: p.auto_delay = (cmd.int_val != 0); break;
                case 11: p.manual_delay_ms = cmd.value0; break;
                case 12: p.phase_invert = (cmd.int_val != 0); break;
                case 13: g->amp_cab_link = (cmd.int_val != 0); return;
                case 14: g->cab_cab_link = (cmd.int_val != 0); return;
                default: break;
            }
            g->cab.SetSlotParams(slot, p);
            if (g->cab_cab_link && slot == 0 && cmd.param_id >= 2 && cmd.param_id <= 7) {
                CabSlotParams p1 = g->cab.GetSlotParams(1);
                p1.position = p.position;
                p1.distance_inch = p.distance_inch;
                p1.angle_deg = p.angle_deg;
                p1.low_cut_hz = p.low_cut_hz;
                p1.high_cut_hz = p.high_cut_hz;
                g->cab.SetSlotParams(1, p1);
            }
            if (slot == 0) g->SyncAmpCabImpedance();
            break;
        }
        case DspCommandType::kSetDriveFuzz: {
            auto& target_drive = g->DriveAt(inst);
            DriveFuzzParams p = target_drive.GetParams();
            if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
            else if (cmd.param_id == 1) p.model = static_cast<DriveFuzzModel>(std::max(0, std::min(cmd.int_val, 11)));
            else if (cmd.param_id == 2) { p.drive = cmd.value0; p.tone = cmd.value1; p.level = cmd.value2; }
            else if (cmd.param_id == 3) { p.bass_cut = cmd.value0; p.voice_mode = cmd.int_val; }
            target_drive.SetParams(p);
            g->RefreshAutoZ();
            break;
        }
        case DspCommandType::kSetDynamics: {
            if (cmd.target_id == 0) {
                auto& target_gate = g->GateAt(inst);
                GateParams gp = target_gate.GetParams();
                if (cmd.param_id == 0) gp.enabled = (cmd.int_val != 0);
                else if (cmd.param_id == 1) { gp.threshold_db = cmd.value0; gp.decay_ms = cmd.value1; }
                target_gate.SetParams(gp);
            } else {
                auto& target_comp = g->CompAt(inst);
                CompParams cp = target_comp.GetParams();
                if (cmd.param_id == 0) cp.enabled = (cmd.int_val != 0);
                else if (cmd.param_id == 1) cp.model = static_cast<CompModel>(cmd.int_val & 1);
                else if (cmd.param_id == 2) { cp.peak_reduction = cmd.value0; cp.gain_db = cmd.value1; cp.mix = cmd.value2; }
                target_comp.SetParams(cp);
            }
            break;
        }
        case DspCommandType::kSetWahFilter: {
            auto& target_wah = g->WahAt(inst);
            WahParams p = target_wah.GetParams();
            if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
            else if (cmd.param_id == 1) p.model = static_cast<WahFilterModel>(std::max(0, std::min(cmd.int_val, 7)));
            else if (cmd.param_id == 2) { p.position = cmd.value0; p.q_resonance = cmd.value1; p.mix = cmd.value2; }
            target_wah.SetParams(p);
            g->RefreshAutoZ();
            break;
        }
        case DspCommandType::kSetEqParam: {
            auto& target_eq = g->EqAt(inst);
            EqParams p = target_eq.GetParams();
            if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
            else if (cmd.param_id == 1) p.mode = static_cast<EqMode>(std::max(0, std::min(cmd.int_val, 2)));
            else if (cmd.param_id == 2) { p.low_freq = cmd.value0; p.low_gain_db = cmd.value1; p.low_q = cmd.value2; }
            else if (cmd.param_id == 3) { p.mid1_freq = cmd.value0; p.mid1_gain_db = cmd.value1; p.mid1_q = cmd.value2; }
            else if (cmd.param_id == 4) { p.mid2_freq = cmd.value0; p.mid2_gain_db = cmd.value1; p.mid2_q = cmd.value2; }
            else if (cmd.param_id == 5) { p.high_freq = cmd.value0; p.high_gain_db = cmd.value1; p.high_q = cmd.value2; }
            else if (cmd.param_id == 6 && cmd.int_val >= 0 && cmd.int_val < 10) p.graphic_gains_db[static_cast<size_t>(cmd.int_val)] = cmd.value0;
            else if (cmd.param_id == 7) { p.tilt_db = cmd.value0; p.tilt_center_hz = cmd.value1; }
            else if (cmd.param_id == 8) { p.low_cut_hz = cmd.value0; p.high_cut_hz = cmd.value1; p.level_db = cmd.value2; }
            target_eq.SetParams(p);
            break;
        }
        case DspCommandType::kSetModulation: {
            const int mod_idx = (cmd.target_id != 0 || inst != 0) ? 1 : 0;
            auto& target_mod = g->ModAt(mod_idx);
            ModParams p = target_mod.GetParams();
            if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
            else if (cmd.param_id == 1) p.model = static_cast<ModulationModel>(std::max(0, std::min(cmd.int_val, 9)));
            else if (cmd.param_id == 2) { p.rate_hz = cmd.value0; p.depth = cmd.value1; p.mix = cmd.value2; }
            else if (cmd.param_id == 3) { p.feedback = cmd.value0; p.fast_speed = (cmd.int_val != 0); }
            else if (cmd.param_id == 4) {
                mod_sync_ = static_cast<NoteSubdivision>(std::max(0, std::min(cmd.int_val, 8)));
                if (mod_sync_ != NoteSubdivision::kOff) p.rate_hz = tempo_clock_.SubdivisionToHz(mod_sync_);
            }
            target_mod.SetParams(p);
            if (mod_idx == 0) g->RefreshAutoZ();
            break;
        }
        case DspCommandType::kSetPitchSynth: {
            PitchParams p = g->pitch.GetParams();
            if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
            else if (cmd.param_id == 1) p.model = static_cast<PitchSynthModel>(std::max(0, std::min(cmd.int_val, 4)));
            else if (cmd.param_id == 2) { p.semitones = cmd.value0; p.pedal_pos = cmd.value1; p.mix = cmd.value2; }
            g->pitch.SetParams(p);
            break;
        }
        case DspCommandType::kSetDelayReverb: {
            if (cmd.target_id == 0) {
                auto& target_delay = g->DelayAt(inst);
                DelayParams p = target_delay.GetParams();
                if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
                else if (cmd.param_id == 1) p.model = static_cast<DelayModel>(std::max(0, std::min(cmd.int_val, 6)));
                else if (cmd.param_id == 2) { p.time_ms = cmd.value0; p.feedback = cmd.value1; p.mix = cmd.value2; }
                else if (cmd.param_id == 3) { p.head_mode = cmd.int_val; p.spring_mix = cmd.value0; p.bbd_rate_scale = cmd.value1; }
                else if (cmd.param_id == 4) {
                    delay_sync_ = static_cast<NoteSubdivision>(std::max(0, std::min(cmd.int_val, 8)));
                    if (delay_sync_ != NoteSubdivision::kOff) p.time_ms = tempo_clock_.SubdivisionToMs(delay_sync_);
                }
                target_delay.SetParams(p);
            } else {
                auto& target_rev = g->ReverbAt(inst);
                ReverbParams p = target_rev.GetParams();
                if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
                else if (cmd.param_id == 1) p.model = static_cast<ReverbModel>(std::max(0, std::min(cmd.int_val, 6)));
                else if (cmd.param_id == 2) { p.decay = cmd.value0; p.damping_hz = cmd.value1; p.mix = cmd.value2; }
                else if (cmd.param_id == 3) { p.predelay_ms = cmd.value0; p.shimmer_amount = cmd.value1; p.mod_depth = cmd.value2; }
                target_rev.SetParams(p);
            }
            break;
        }
        case DspCommandType::kSetVolumePan: {
            auto& target_vol = g->VolPanAt(inst);
            VolumePanParams p = target_vol.GetParams();
            if (cmd.param_id == 0) p.enabled = (cmd.int_val != 0);
            else if (cmd.param_id == 1) { p.position = cmd.value0; p.pan = cmd.value1; p.stereo_width = cmd.value2; p.logarithmic = (cmd.int_val != 0); }
            target_vol.SetParams(p);
            break;
        }
        case DspCommandType::kSetIoTrimPan:
            g->input_trim_db = cmd.value0;
            g->output_level_db = cmd.value1;
            g->output_pan = cmd.value2;
            break;
        case DspCommandType::kSetMasterMute:
            master_mute_target_ = (cmd.int_val != 0);
            break;
        case DspCommandType::kSetBlockStereo: {
            const bool st = (cmd.int_val != 0);
            switch (static_cast<RigBlockType>(cmd.target_id)) {
                case RigBlockType::kEq: { auto p = g->EqAt(inst).GetParams(); p.is_stereo = st; g->EqAt(inst).SetParams(p); break; }
                case RigBlockType::kModPre: { auto p = g->mod_pre.GetParams(); p.is_stereo = st; g->mod_pre.SetParams(p); break; }
                case RigBlockType::kModPost: { auto p = g->mod_post.GetParams(); p.is_stereo = st; g->mod_post.SetParams(p); break; }
                case RigBlockType::kDelay: { auto p = g->DelayAt(inst).GetParams(); p.is_stereo = st; g->DelayAt(inst).SetParams(p); break; }
                case RigBlockType::kReverb: { auto p = g->ReverbAt(inst).GetParams(); p.is_stereo = st; g->ReverbAt(inst).SetParams(p); break; }
                default: break;
            }
            break;
        }
        case DspCommandType::kSetTunerConfig:
            tuner_.Configure(cmd.int_val != 0, cmd.value0 > 100.0f ? cmd.value0 : 440.0f, cmd.flags != 0);
            break;
        case DspCommandType::kSetGlobalBpm: {
            tempo_clock_.SetBpm(cmd.value0);
            if (delay_sync_ != NoteSubdivision::kOff) {
                for (int i = 0; i < 2; ++i) {
                    DelayParams dp = g->DelayAt(i).GetParams();
                    dp.time_ms = tempo_clock_.SubdivisionToMs(delay_sync_);
                    g->DelayAt(i).SetParams(dp);
                }
            }
            if (mod_sync_ != NoteSubdivision::kOff) {
                for (int i = 0; i < 2; ++i) {
                    ModParams mp = g->ModAt(i).GetParams();
                    mp.rate_hz = tempo_clock_.SubdivisionToHz(mod_sync_);
                    g->ModAt(i).SetParams(mp);
                }
            }
            break;
        }
        case DspCommandType::kSetLooperCommand:
            looper_.TriggerAction(static_cast<LooperAction>(cmd.int_val));
            break;
        case DspCommandType::kSetPresetClipCommand:
            if (cmd.param_id == 0) clip_player_.SetPlaying(cmd.int_val != 0);
            else if (cmd.param_id == 1) clip_player_.SeekSeconds(cmd.value0);
            else if (cmd.param_id == 2) clip_player_.SkipSeconds(cmd.value0);
            else if (cmd.param_id == 3) clip_player_.SetRepeat(cmd.int_val != 0);
            break;
        case DspCommandType::kRecallSnapshot:
            ApplySnapshotToActiveGraph(cmd.int_val);
            break;
        case DspCommandType::kSetAutoZConfig:
            g->pickup_type = static_cast<GuitarPickupType>(std::max(0, std::min(cmd.int_val, 3)));
            g->auto_z_enabled = (cmd.flags != 0);
            if (cmd.value0 >= 5000.0f) g->manual_input_z_ohms = cmd.value0;
            g->RefreshAutoZ();
            break;
        case DspCommandType::kSetRigRoutingMode:
            g->routing_mode = static_cast<RigRoutingMode>(std::max(0, std::min(cmd.int_val, 2)));
            break;
        default:
            break;
    }
}

void StadiumSignalChainProcessor::ApplySnapshotToActiveGraph(int idx) noexcept {
    if (idx < 0 || idx >= 8) return;
    const auto& s = snapshots_[static_cast<size_t>(idx)];
    if (!s.valid) return;
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    if (!g) return;

    // ZERO ResetAll()! Preserves live Delay & Reverb feedback buffers for true spillover trails!
    auto gp = g->gate.GetParams(); gp.enabled = s.gate_enabled; g->gate.SetParams(gp);
    auto pp = g->pitch.GetParams(); pp.enabled = s.pitch_enabled; g->pitch.SetParams(pp);
    auto cp = g->comp.GetParams(); cp.enabled = s.comp_enabled; g->comp.SetParams(cp);
    auto wp = g->wah.GetParams(); wp.enabled = s.wah_enabled; g->wah.SetParams(wp);
    auto dp = g->drive.GetParams(); dp.enabled = s.drive_enabled; dp.drive = s.drive_gain; g->drive.SetParams(dp);
    auto mp = g->mod_post.GetParams(); mp.enabled = s.mod_enabled; g->mod_post.SetParams(mp);
    auto ep = g->eq.GetParams(); ep.enabled = s.eq_enabled; g->eq.SetParams(ep);
    auto vp = g->vol_pan.GetParams(); vp.enabled = s.vol_enabled; g->vol_pan.SetParams(vp);

    AmpParams ap = g->amp.GetParams();
    ap.drive = s.amp_drive;
    ap.master = s.amp_master;
    ap.ch_vol = s.amp_ch_vol;
    ap.bass = s.amp_bass;
    ap.mid = s.amp_mid;
    ap.treble = s.amp_treble;
    ap.presence = s.amp_presence;
    g->amp.SetParams(ap);

    auto dlp = g->delay.GetParams(); dlp.enabled = s.delay_enabled; dlp.mix = s.delay_mix; g->delay.SetParams(dlp);
    auto rvp = g->reverb.GetParams(); rvp.enabled = s.reverb_enabled; rvp.mix = s.reverb_mix; g->reverb.SetParams(rvp);
    g->output_level_db = s.output_level_db;
    g->RefreshAutoZ();
    active_snapshot_ = idx;
}

void StadiumSignalChainProcessor::ProcessAudioBurst(const float* in_mono, float* out_l, float* out_r,
                                                    size_t num_frames) noexcept {
    DrainCommands();

    // Check for pending atomic RCU graph swap
    GuitarRigGraph* pending = pending_graph_.exchange(nullptr, std::memory_order_acq_rel);
    if (pending != nullptr) {
        prev_graph_ = active_graph_.exchange(pending, std::memory_order_acq_rel);
        rcu_xfade_pos_ = 0;
    }

    GuitarRigGraph* cur = active_graph_.load(std::memory_order_acquire);
    if (!cur) return;

    float in_peak = 0.0f;
    float out_peak_l = 0.0f;
    float out_peak_r = 0.0f;
    bool stereo_collapsed = false;

    constexpr float kMuteStep = 1.0f / 240.0f;  // 5.0 ms clickless raised-cosine ramp @ 48 kHz

    size_t offset = 0;
    while (offset < num_frames) {
        const size_t chunk = std::min(kSubBlockSize, num_frames - offset);
        alignas(64) float sub_in[kSubBlockSize] = {};
        alignas(64) float clip_in[kSubBlockSize] = {};
        alignas(64) float sub_l[kSubBlockSize] = {};
        alignas(64) float sub_r[kSubBlockSize] = {};

        // Mix live guitar input with Preset Clip audition player when playing
        if (clip_player_.IsPlaying()) {
            clip_player_.RenderSubBlock16(clip_in);
        }
        for (size_t i = 0; i < chunk; ++i) {
            const float x = (in_mono ? in_mono[offset + i] : 0.0f) + clip_in[i];
            sub_in[i] = x;
            in_peak = std::max(in_peak, std::fabs(x));
        }

        // Feed allocation-free Chromatic YIN Tuner straight from clean dry input
        tuner_.ProcessSubBlock16(sub_in);

        // Execute active signal chain graph
        bool collapsed_block = false;
        cur->ProcessSubBlock16(sub_in, sub_l, sub_r, &collapsed_block);
        stereo_collapsed = stereo_collapsed || collapsed_block;

        // If RCU crossfade is in progress (64 samples), blend cleanly from prev_graph_ -> cur
        if (prev_graph_ != nullptr && rcu_xfade_pos_ < kRcuXfadeLen) {
            alignas(64) float old_l[kSubBlockSize] = {};
            alignas(64) float old_r[kSubBlockSize] = {};
            prev_graph_->ProcessSubBlock16(sub_in, old_l, old_r);
            for (size_t i = 0; i < chunk; ++i) {
                const float u = Clamp(static_cast<float>(rcu_xfade_pos_) / static_cast<float>(kRcuXfadeLen), 0.0f, 1.0f);
                const float w_new = 0.5f * (1.0f - std::cos(kPi * u));
                sub_l[i] = old_l[i] * (1.0f - w_new) + sub_l[i] * w_new;
                sub_r[i] = old_r[i] * (1.0f - w_new) + sub_r[i] * w_new;
                if (rcu_xfade_pos_ < kRcuXfadeLen) ++rcu_xfade_pos_;
            }
            if (rcu_xfade_pos_ >= kRcuXfadeLen) {
                prev_graph_ = nullptr;
            }
        }

        // Stereo Looper (post-rig so recorded layers preserve tone!)
        looper_.ProcessSubBlock16(sub_l, sub_r, sub_l, sub_r);

        // Clickless MasterMute (or Tuner Auto-Mute) + Continuous-C¹ -0.10 dBFS Master DAC Ceiling
        const bool effective_mute = master_mute_target_ || tuner_.ShouldMuteOutput();
        for (size_t i = 0; i < chunk; ++i) {
            if (effective_mute && mute_ramp_pos_ < 1.0f) {
                mute_ramp_pos_ = std::min(1.0f, mute_ramp_pos_ + kMuteStep);
            } else if (!effective_mute && mute_ramp_pos_ > 0.0f) {
                mute_ramp_pos_ = std::max(0.0f, mute_ramp_pos_ - kMuteStep);
            }
            const float mute_gain = 0.5f * (1.0f + std::cos(kPi * mute_ramp_pos_));
            const float yl = SoftCeilingC1(FlushDenormal(sub_l[i] * mute_gain));
            const float yr = SoftCeilingC1(FlushDenormal(sub_r[i] * mute_gain));
            out_l[offset + i] = yl;
            out_r[offset + i] = yr;
            out_peak_l = std::max(out_peak_l, std::fabs(yl));
            out_peak_r = std::max(out_peak_r, std::fabs(yr));
        }

        offset += chunk;
    }

    // Publish lock-free telemetry snapshot (ping-pong double buffer)
    const int write_idx = 1 - telemetry_read_idx_.load(std::memory_order_relaxed);
    RtTelemetrySnapshot& snap = telemetry_buf_[static_cast<size_t>(write_idx)];
    snap.in_peak_dbfs = LinToDb(in_peak);
    snap.out_peak_l_dbfs = LinToDb(out_peak_l);
    snap.out_peak_r_dbfs = LinToDb(out_peak_r);
    snap.gate_gr_db = cur->gate.GetCurrentGainReductionDb();
    snap.comp_gr_db = cur->comp.GetCurrentGainReductionDb();
    const auto& tr = tuner_.GetResult();
    snap.tuner_freq_hz = tr.freq_hz;
    snap.tuner_cents = tr.cents;
    snap.tuner_midi_note = tr.midi_note;
    snap.tuner_confidence = tr.confidence;
    snap.tuner_strobe_phase = tr.strobe_phase;
    snap.clip_playhead_norm = clip_player_.GetPlayheadNorm();
    snap.looper_state = static_cast<int32_t>(looper_.GetState());
    snap.looper_progress = looper_.GetProgressNorm();
    snap.rt_cpu_load_pct = rt_cpu_load_pct_.load(std::memory_order_relaxed);
    snap.active_snapshot = active_snapshot_;

    uint32_t flags = 0;
    if (snap.in_peak_dbfs >= -0.15f) flags |= 1u;
    if (snap.out_peak_l_dbfs >= -0.15f || snap.out_peak_r_dbfs >= -0.15f) flags |= 2u;
    if (master_mute_target_) flags |= 4u;
    if (clip_player_.IsPlaying()) flags |= 8u;
    if (stereo_collapsed) flags |= 16u;
    snap.status_flags = flags;

    telemetry_read_idx_.store(write_idx, std::memory_order_release);
}

RtTelemetrySnapshot StadiumSignalChainProcessor::ReadTelemetry() const noexcept {
    const int r = telemetry_read_idx_.load(std::memory_order_acquire);
    return telemetry_buf_[static_cast<size_t>(r)];
}

bool StadiumSignalChainProcessor::LoadExternalIrFromMemory(int slot, const uint8_t* wav_bytes,
                                                           size_t size, std::string* err) {
    DecodedIrAsset asset;
    if (!LoadExternalCabIrWavFromMemory(wav_bytes, size, &asset, err)) return false;
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    if (g) {
        g->cab.SetExternalIr(slot, asset.ir_48k.data(), asset.ir_48k.size(), asset.extracted_z, false);
        if ((slot & 1) == 0) g->SyncAmpCabImpedance();
    }
    return true;
}

bool StadiumSignalChainProcessor::LoadExternalNamFromMemory(const char* json_bytes, size_t size,
                                                            std::string* err) {
    GuitarRigGraph* cur = active_graph_.load(std::memory_order_acquire);
    GuitarRigGraph* bg = (cur == graph_a_.get()) ? graph_b_.get() : graph_a_.get();
    *bg = *cur;
    if (!LoadExternalNamJsonFromMemory(json_bytes, size, &bg->clone, err)) return false;
    bg->routing_mode = RigRoutingMode::kNeuralCloneAndCab;
    pending_graph_.store(bg, std::memory_order_release);
    return true;
}

bool StadiumSignalChainProcessor::LoadExternalAidaxFromMemory(const char* json_bytes, size_t size,
                                                              std::string* err) {
    GuitarRigGraph* cur = active_graph_.load(std::memory_order_acquire);
    GuitarRigGraph* bg = (cur == graph_a_.get()) ? graph_b_.get() : graph_a_.get();
    *bg = *cur;
    if (!LoadExternalAidaxJsonFromMemory(json_bytes, size, &bg->clone, err)) return false;
    bg->routing_mode = RigRoutingMode::kNeuralCloneAndCab;
    pending_graph_.store(bg, std::memory_order_release);
    return true;
}

void StadiumSignalChainProcessor::LoadCustomPresetClipDi(const float* samples, size_t count) {
    clip_player_.LoadCustomDiBuffer(samples, count);
}

void StadiumSignalChainProcessor::EnableAllBlocksForBenchmark() {
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    auto gp = g->gate.GetParams(); gp.enabled = true; g->gate.SetParams(gp);
    auto pp = g->pitch.GetParams(); pp.enabled = true; pp.model = PitchSynthModel::kPolyCapo; g->pitch.SetParams(pp);
    auto cp = g->comp.GetParams(); cp.enabled = true; g->comp.SetParams(cp);
    auto wp = g->wah.GetParams(); wp.enabled = true; g->wah.SetParams(wp);
    auto dp = g->drive.GetParams(); dp.enabled = true; dp.model = DriveFuzzModel::kMinotaur; dp.drive = 0.7f; g->drive.SetParams(dp);
    auto mp = g->mod_pre.GetParams(); mp.enabled = true; mp.model = ModulationModel::kScriptPhase90; g->mod_pre.SetParams(mp);
    g->amp.SetModel(AmpModelId::kBrit2203);
    auto ap = g->amp.GetParams(); ap.drive = 0.78f; ap.master = 0.72f; g->amp.SetParams(ap);
    auto ep = g->eq.GetParams(); ep.enabled = true; ep.mode = EqMode::kParametric4Band; g->eq.SetParams(ep);
    auto vp = g->vol_pan.GetParams(); vp.enabled = true; g->vol_pan.SetParams(vp);
    auto dlp = g->delay.GetParams(); dlp.enabled = true; dlp.model = DelayModel::kCosmosEcho; g->delay.SetParams(dlp);
    auto rvp = g->reverb.GetParams(); rvp.enabled = true; rvp.model = ReverbModel::kDynamicHall; g->reverb.SetParams(rvp);
    g->RefreshAutoZ();
    g->SyncAmpCabImpedance();
}

void StadiumSignalChainProcessor::ConfigureDelayReverbForSpilloverTest() {
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    auto dlp = g->delay.GetParams(); dlp.enabled = true; dlp.time_ms = 80.0f; dlp.feedback = 0.55f; dlp.mix = 0.45f; g->delay.SetParams(dlp);
    auto rvp = g->reverb.GetParams(); rvp.enabled = true; rvp.model = ReverbModel::kDynamicHall; rvp.decay = 0.75f; rvp.mix = 0.55f; g->reverb.SetParams(rvp);
}

void StadiumSignalChainProcessor::RequestRcuGraphSwap() {
    GuitarRigGraph* cur = active_graph_.load(std::memory_order_acquire);
    GuitarRigGraph* bg = (cur == graph_a_.get()) ? graph_b_.get() : graph_a_.get();
    *bg = *cur;
    pending_graph_.store(bg, std::memory_order_release);
}

void StadiumSignalChainProcessor::SetDynamicSlotOrder(const std::array<RigBlockType, 12>& order) {
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    g->use_dual_path_plan = false;
    g->slot_order = order;
    g->RefreshAutoZ();
}

void StadiumSignalChainProcessor::SetDualPathRouting(const DualPathRoutingPlan& plan) {
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    if (!g) return;
    g->dual_path_plan = plan;
    g->use_dual_path_plan = true;
    g->RefreshAutoZ();
}

void StadiumSignalChainProcessor::SetParallelSplitMerge(SplitMode mode, float crossover_hz, float route_ab,
                                                        float lvl_a_db, float lvl_b_db, float pan_a, float pan_b,
                                                        bool invert_b) {
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    ParallelRoutingParams p;
    p.split_mode = mode;
    p.crossover_hz = crossover_hz;
    p.route_ab = route_ab;
    p.merge_level_a_db = lvl_a_db;
    p.merge_level_b_db = lvl_b_db;
    p.merge_pan_a = pan_a;
    p.merge_pan_b = pan_b;
    p.invert_phase_b = invert_b;
    g->split_merge.SetParams(p);
}

void StadiumSignalChainProcessor::SetSecondaryParallelSplitMerge(SplitMode mode, float crossover_hz, float route_ab,
                                                                 float lvl_a_db, float lvl_b_db, float pan_a, float pan_b,
                                                                 bool invert_b) {
    GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    ParallelRoutingParams p;
    p.split_mode = mode;
    p.crossover_hz = crossover_hz;
    p.route_ab = route_ab;
    p.merge_level_a_db = lvl_a_db;
    p.merge_level_b_db = lvl_b_db;
    p.merge_pan_a = pan_a;
    p.merge_pan_b = pan_b;
    p.invert_phase_b = invert_b;
    g->split_merge_2.SetParams(p);
}

void StadiumSignalChainProcessor::StoreSnapshot(int idx, const SnapshotSlotData& data) {
    if (idx >= 0 && idx < 8) snapshots_[static_cast<size_t>(idx)] = data;
}

void StadiumSignalChainProcessor::RecallSnapshot(int idx) {
    ApplySnapshotToActiveGraph(idx);
}

void StadiumSignalChainProcessor::RegisterTapTempoMs(int64_t timestamp_ms) {
    tempo_clock_.RegisterTapMs(timestamp_ms);
    EnqueueCommand(DspCommand{DspCommandType::kSetGlobalBpm, 0, 0, 0, tempo_clock_.GetBpm(), 0.0f, 0.0f, 0});
}

void StadiumSignalChainProcessor::ComputeEqCurveDb(float* out_db, size_t num_points) const {
    const GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    if (g && out_db && num_points > 0) g->eq.EvaluateCurveDb(out_db, num_points);
}

void StadiumSignalChainProcessor::ComputeCabSpectrumDb(int slot, float* out_db, size_t num_bins) const {
    const GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    if (g && out_db && num_bins > 0) g->cab.ComputeSlotMagnitudeSpectrumDb(slot, out_db, num_bins);
}

CloneMetadata StadiumSignalChainProcessor::GetActiveCloneMetadata() const {
    const GuitarRigGraph* g = active_graph_.load(std::memory_order_acquire);
    return g ? g->clone.GetMetadata() : CloneMetadata{};
}

}  // namespace stadium_dsp
