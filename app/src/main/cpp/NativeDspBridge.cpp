#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "OboeGuitarEngine.hpp"
#include "stadium_dsp/neural_clone_engine.hpp"

namespace {

stadium_dsp::OboeGuitarEngine& Engine() {
    static stadium_dsp::OboeGuitarEngine instance;
    return instance;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeStartAudio(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enable_input, jint input_device_id,
    jint output_device_id, jint burst_multiplier, jint requested_buffer_frames,
    jboolean auto_xrun_guard) {
    return Engine().Start(enable_input == JNI_TRUE, input_device_id, output_device_id,
                          burst_multiplier, requested_buffer_frames,
                          auto_xrun_guard == JNI_TRUE)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeStopAudio(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    Engine().Stop();
}

JNIEXPORT jboolean JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeIsAudioRunning(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return Engine().IsRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeSetLiveBufferSizeFrames(
    JNIEnv* /*env*/, jobject /*thiz*/, jint target_frames) {
    return Engine().SetLiveBufferSizeFrames(target_frames);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeSetAutoXrunGuard(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    Engine().SetAutoXrunGuard(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeGetStreamDiagnostics(
    JNIEnv* env, jobject /*thiz*/, jintArray out_int8) {
    if (!out_int8) return;
    const jsize len = env->GetArrayLength(out_int8);
    if (len < 5) return;
    const auto d = Engine().GetDiagnostics();
    jint vals[8] = {
        d.sample_rate,
        d.frames_per_burst,
        d.buffer_size_frames,
        d.is_mmap_used,
        d.xrun_count,
        d.buffer_capacity_frames,
        d.input_frames_per_burst,
        d.measured_latency_us,
    };
    const jsize count = std::min<jsize>(len, 8);
    env->SetIntArrayRegion(out_int8, 0, count, vals);
}

JNIEXPORT jboolean JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeEnqueueCommand(
    JNIEnv* /*env*/, jobject /*thiz*/, jint type, jint target_id, jint param_id, jint flags,
    jfloat value0, jfloat value1, jfloat value2, jint int_val) {
    stadium_dsp::DspCommand cmd;
    cmd.type = static_cast<stadium_dsp::DspCommandType>(type);
    cmd.target_id = static_cast<uint16_t>(target_id);
    cmd.param_id = static_cast<uint16_t>(param_id);
    cmd.flags = static_cast<uint16_t>(flags);
    cmd.value0 = value0;
    cmd.value1 = value1;
    cmd.value2 = value2;
    cmd.int_val = int_val;
    return Engine().GetProcessor().EnqueueCommand(cmd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeReadTelemetry(
    JNIEnv* env, jobject /*thiz*/, jfloatArray out_float16) {
    if (!out_float16 || env->GetArrayLength(out_float16) < 16) return;
    const auto t = Engine().GetProcessor().ReadTelemetry();
    jfloat vals[16] = {
        t.in_peak_dbfs,
        t.out_peak_l_dbfs,
        t.out_peak_r_dbfs,
        t.gate_gr_db,
        t.comp_gr_db,
        t.tuner_freq_hz,
        t.tuner_cents,
        t.tuner_confidence,
        t.tuner_strobe_phase,
        t.clip_playhead_norm,
        t.looper_progress,
        t.rt_cpu_load_pct,
        static_cast<jfloat>(t.tuner_midi_note),
        static_cast<jfloat>(t.looper_state),
        static_cast<jfloat>(t.active_snapshot),
        static_cast<jfloat>(t.status_flags),
    };
    env->SetFloatArrayRegion(out_float16, 0, 16, vals);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeComputeEqCurveDb(
    JNIEnv* env, jobject /*thiz*/, jfloatArray out_db) {
    if (!out_db) return;
    const jsize n = env->GetArrayLength(out_db);
    if (n <= 0) return;
    std::vector<float> buf(static_cast<size_t>(n), 0.0f);
    Engine().GetProcessor().ComputeEqCurveDb(buf.data(), buf.size());
    env->SetFloatArrayRegion(out_db, 0, n, buf.data());
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeComputeCabSpectrumDb(
    JNIEnv* env, jobject /*thiz*/, jint slot, jfloatArray out_db) {
    if (!out_db) return;
    const jsize n = env->GetArrayLength(out_db);
    if (n <= 0) return;
    std::vector<float> buf(static_cast<size_t>(n), -60.0f);
    Engine().GetProcessor().ComputeCabSpectrumDb(slot, buf.data(), buf.size());
    env->SetFloatArrayRegion(out_db, 0, n, buf.data());
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeSetDynamicSlotOrder(
    JNIEnv* env, jobject /*thiz*/, jintArray order12) {
    if (!order12 || env->GetArrayLength(order12) < 12) return;
    jint raw[12] = {};
    env->GetIntArrayRegion(order12, 0, 12, raw);
    std::array<stadium_dsp::RigBlockType, 12> order{};
    for (size_t i = 0; i < 12; ++i) {
        const int v = std::max(0, std::min(raw[i], static_cast<int>(stadium_dsp::RigBlockType::kCount) - 1));
        order[i] = static_cast<stadium_dsp::RigBlockType>(v);
    }
    Engine().GetProcessor().SetDynamicSlotOrder(order);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeSetParallelSplitMerge(
    JNIEnv* /*env*/, jobject /*thiz*/, jint mode, jfloat crossover_hz, jfloat route_ab,
    jfloat lvl_a_db, jfloat lvl_b_db, jfloat pan_a, jfloat pan_b, jboolean invert_b) {
    const auto sm = static_cast<stadium_dsp::SplitMode>(std::max(0, std::min(mode, 3)));
    Engine().GetProcessor().SetParallelSplitMerge(sm, crossover_hz, route_ab, lvl_a_db, lvl_b_db,
                                                  pan_a, pan_b, invert_b == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeSetSecondaryParallelSplitMerge(
    JNIEnv* /*env*/, jobject /*thiz*/, jint mode, jfloat crossover_hz, jfloat route_ab,
    jfloat lvl_a_db, jfloat lvl_b_db, jfloat pan_a, jfloat pan_b, jboolean invert_b) {
    const auto sm = static_cast<stadium_dsp::SplitMode>(std::max(0, std::min(mode, 3)));
    Engine().GetProcessor().SetSecondaryParallelSplitMerge(sm, crossover_hz, route_ab, lvl_a_db,
                                                           lvl_b_db, pan_a, pan_b,
                                                           invert_b == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeSetDualPathRouting(
    JNIEnv* env, jobject /*thiz*/,
    jintArray p1a_types, jintArray p1a_inst,
    jintArray p1b_types, jintArray p1b_inst,
    jintArray p2a_types, jintArray p2a_inst,
    jintArray p2b_types, jintArray p2b_inst,
    jint split1_after, jint split2_after, jint merge2_at,
    jboolean merge_1b_into_1a, jboolean path_1a_feeds_2a) {
    auto fill_branch = [&](jintArray types_arr, jintArray inst_arr,
                           std::array<stadium_dsp::RoutingSlotRef, 8>& dst, uint8_t& out_count) {
        out_count = 0;
        if (!types_arr || !inst_arr) return;
        const jsize n = std::min<jsize>(std::min(env->GetArrayLength(types_arr),
                                                 env->GetArrayLength(inst_arr)), 8);
        if (n <= 0) return;
        jint t_buf[8] = {};
        jint i_buf[8] = {};
        env->GetIntArrayRegion(types_arr, 0, n, t_buf);
        env->GetIntArrayRegion(inst_arr, 0, n, i_buf);
        for (jsize k = 0; k < n; ++k) {
            if (t_buf[k] >= 0 && t_buf[k] < static_cast<jint>(stadium_dsp::RigBlockType::kCount)) {
                dst[out_count].type = static_cast<stadium_dsp::RigBlockType>(t_buf[k]);
                dst[out_count].instance_idx = static_cast<uint8_t>(i_buf[k] & 1);
                ++out_count;
            }
        }
    };

    stadium_dsp::DualPathRoutingPlan plan;
    fill_branch(p1a_types, p1a_inst, plan.path_1a, plan.count_1a);
    fill_branch(p1b_types, p1b_inst, plan.path_1b, plan.count_1b);
    fill_branch(p2a_types, p2a_inst, plan.path_2a, plan.count_2a);
    fill_branch(p2b_types, p2b_inst, plan.path_2b, plan.count_2b);
    plan.split_1_after_index = static_cast<uint8_t>(std::max(0, std::min<int>(split1_after, 8)));
    plan.split_2_after_index = static_cast<uint8_t>(std::max(0, std::min<int>(split2_after, 8)));
    plan.merge_2_at_index = static_cast<uint8_t>(std::max(0, std::min<int>(merge2_at, 8)));
    plan.merge_1b_into_1a = (merge_1b_into_1a == JNI_TRUE);
    plan.path_1a_feeds_2a = (path_1a_feeds_2a == JNI_TRUE);
    Engine().GetProcessor().SetDualPathRouting(plan);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeStoreSnapshot(
    JNIEnv* env, jobject /*thiz*/, jint idx, jint bypass_mask, jfloatArray params11) {
    if (idx < 0 || idx >= 8 || !params11 || env->GetArrayLength(params11) < 11) return;
    jfloat p[11] = {};
    env->GetFloatArrayRegion(params11, 0, 11, p);
    stadium_dsp::SnapshotSlotData s;
    s.valid = true;
    s.gate_enabled = (bypass_mask & (1 << 0)) != 0;
    s.pitch_enabled = (bypass_mask & (1 << 1)) != 0;
    s.comp_enabled = (bypass_mask & (1 << 2)) != 0;
    s.wah_enabled = (bypass_mask & (1 << 3)) != 0;
    s.drive_enabled = (bypass_mask & (1 << 4)) != 0;
    s.mod_enabled = (bypass_mask & (1 << 5)) != 0;
    s.eq_enabled = (bypass_mask & (1 << 6)) != 0;
    s.vol_enabled = (bypass_mask & (1 << 7)) != 0;
    s.delay_enabled = (bypass_mask & (1 << 8)) != 0;
    s.reverb_enabled = (bypass_mask & (1 << 9)) != 0;

    s.amp_drive = p[0];
    s.amp_master = p[1];
    s.amp_ch_vol = p[2];
    s.amp_bass = p[3];
    s.amp_mid = p[4];
    s.amp_treble = p[5];
    s.amp_presence = p[6];
    s.drive_gain = p[7];
    s.delay_mix = p[8];
    s.reverb_mix = p[9];
    s.output_level_db = p[10];
    Engine().GetProcessor().StoreSnapshot(idx, s);
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeRecallSnapshot(
    JNIEnv* /*env*/, jobject /*thiz*/, jint idx) {
    stadium_dsp::DspCommand cmd;
    cmd.type = stadium_dsp::DspCommandType::kRecallSnapshot;
    cmd.int_val = idx;
    Engine().GetProcessor().EnqueueCommand(cmd);
}

JNIEXPORT jfloat JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeRegisterTapTempoMs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong timestamp_ms) {
    Engine().GetProcessor().RegisterTapTempoMs(static_cast<int64_t>(timestamp_ms));
    return Engine().GetProcessor().GetTempoClock().GetBpm();
}

JNIEXPORT jstring JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeLoadExternalIr(
    JNIEnv* env, jobject /*thiz*/, jint slot, jbyteArray wav_bytes) {
    if (!wav_bytes) return env->NewStringUTF("Empty IR byte array");
    const jsize len = env->GetArrayLength(wav_bytes);
    std::vector<uint8_t> buf(static_cast<size_t>(std::max(0, len)));
    if (len > 0) {
        env->GetByteArrayRegion(wav_bytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    }
    std::string err;
    const bool ok = Engine().GetProcessor().LoadExternalIrFromMemory(slot, buf.data(), buf.size(), &err);
    return env->NewStringUTF(ok ? "OK" : err.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeLoadExternalNam(
    JNIEnv* env, jobject /*thiz*/, jbyteArray json_bytes) {
    if (!json_bytes) return env->NewStringUTF("Empty NAM byte array");
    const jsize len = env->GetArrayLength(json_bytes);
    std::string json(static_cast<size_t>(std::max(0, len)), '\0');
    if (len > 0) {
        env->GetByteArrayRegion(json_bytes, 0, len, reinterpret_cast<jbyte*>(json.data()));
    }
    std::string err;
    const bool ok = Engine().GetProcessor().LoadExternalNamFromMemory(json.data(), json.size(), &err);
    return env->NewStringUTF(ok ? "OK" : err.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeLoadExternalAidax(
    JNIEnv* env, jobject /*thiz*/, jbyteArray json_bytes) {
    if (!json_bytes) return env->NewStringUTF("Empty AIDAX byte array");
    const jsize len = env->GetArrayLength(json_bytes);
    std::string json(static_cast<size_t>(std::max(0, len)), '\0');
    if (len > 0) {
        env->GetByteArrayRegion(json_bytes, 0, len, reinterpret_cast<jbyte*>(json.data()));
    }
    std::string err;
    const bool ok = Engine().GetProcessor().LoadExternalAidaxFromMemory(json.data(), json.size(), &err);
    return env->NewStringUTF(ok ? "OK" : err.c_str());
}

JNIEXPORT jobjectArray JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeGetCloneMetadata(
    JNIEnv* env, jobject /*thiz*/) {
    const auto m = Engine().GetProcessor().GetActiveCloneMetadata();
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(9, str_cls, env->NewStringUTF(""));
    const bool is_loaded = !m.uuid.empty() && m.uuid != "cleanroom-default";
    const std::string fields[9] = {
        is_loaded ? "true" : "false",
        m.uuid,
        m.architecture,
        m.brand,
        m.product,
        std::to_string(static_cast<int>(m.sample_rate)) + " Hz",
        m.notes,
        std::to_string(m.input_level_dbu),
        std::to_string(m.output_level_dbu),
    };
    for (int i = 0; i < 9; ++i) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(fields[i].c_str()));
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeSelectFactoryPresetClipRiff(
    JNIEnv* /*env*/, jobject /*thiz*/, jint style_index) {
    const int clamped = std::max(0, std::min(style_index, 3));
    Engine().GetProcessor().GetClipPlayer().SetStyle(
        static_cast<stadium_dsp::PresetClipStyle>(clamped));
}

JNIEXPORT jstring JNICALL
Java_com_cleanroom_stadiumdsp_NativeDspBridge_nativeLoadCustomPresetClipWav(
    JNIEnv* env, jobject /*thiz*/, jbyteArray wav_bytes) {
    if (!wav_bytes) return env->NewStringUTF("Empty WAV byte array");
    const jsize len = env->GetArrayLength(wav_bytes);
    std::vector<uint8_t> buf(static_cast<size_t>(std::max(0, len)));
    if (len > 0) {
        env->GetByteArrayRegion(wav_bytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    }
    stadium_dsp::DecodedIrAsset decoded;
    std::string err;
    if (!stadium_dsp::LoadExternalCabIrWavFromMemory(buf.data(), buf.size(), &decoded, &err)) {
        return env->NewStringUTF(err.c_str());
    }
    Engine().GetProcessor().LoadCustomPresetClipDi(decoded.ir_48k.data(), decoded.ir_48k.size());
    return env->NewStringUTF("OK");
}

}  // extern "C"
