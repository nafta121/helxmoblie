#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "stadium_dsp/signal_chain_processor.hpp"

namespace stadium_dsp {

struct AudioStreamDiagnostics {
    int32_t sample_rate = 48000;
    int32_t frames_per_burst = 192;
    int32_t buffer_size_frames = 384;
    int32_t buffer_capacity_frames = 768;
    int32_t is_mmap_used = 0;
    int32_t xrun_count = 0;
    int32_t input_frames_per_burst = 0;
    int32_t measured_latency_us = -1;
};

class OboeGuitarEngine : public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
public:
    OboeGuitarEngine();
    ~OboeGuitarEngine() override;

    bool Start(bool enable_input, int32_t input_device_id, int32_t output_device_id,
               int32_t burst_multiplier, int32_t requested_buffer_frames = 0,
               bool auto_xrun_guard = true);
    void Stop();
    bool IsRunning() const noexcept { return is_running_.load(std::memory_order_acquire); }

    // Adjusts AAudio/Oboe output buffer size LIVE without stopping or glitching the stream.
    // Returns the actual buffer size in frames applied by the audio HAL.
    int32_t SetLiveBufferSizeFrames(int32_t target_frames) noexcept;
    void SetAutoXrunGuard(bool enabled) noexcept {
        auto_xrun_guard_.store(enabled, std::memory_order_release);
    }

    AudioStreamDiagnostics GetDiagnostics() const noexcept;
    StadiumSignalChainProcessor& GetProcessor() noexcept { return processor_; }
    const StadiumSignalChainProcessor& GetProcessor() const noexcept { return processor_; }

    // Oboe RT audio callback (zero heap alloc, zero mutex, zero JNI!)
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audio_stream,
                                          void* audio_data,
                                          int32_t num_frames) override;

    // Automatic device plug/unplug recovery
    void onErrorAfterClose(oboe::AudioStream* oboe_stream, oboe::Result error) override;

private:
    static constexpr size_t kMaxScratchFrames = 2048;

    void InitAdpfSessionIfSupported(int32_t tid, int64_t target_duration_ns) noexcept;
    void ReportAdpfWorkDuration(int64_t actual_work_ns) noexcept;
    void CloseAdpfSession() noexcept;

    StadiumSignalChainProcessor processor_;

    mutable std::mutex stream_mutex_;
    std::shared_ptr<oboe::AudioStream> input_stream_;
    std::shared_ptr<oboe::AudioStream> output_stream_;

    std::atomic<bool> is_running_{false};
    std::atomic<bool> ftz_initialized_{false};
    std::atomic<bool> adpf_initialized_{false};
    std::atomic<bool> has_input_{false};
    std::atomic<bool> auto_xrun_guard_{true};

    // Stored configuration for transparent disconnect auto-restart
    bool last_enable_input_ = true;
    int32_t last_input_device_id_ = 0;
    int32_t last_output_device_id_ = 0;
    int32_t last_burst_multiplier_ = 2;
    int32_t last_requested_buffer_frames_ = 0;

    // Pre-allocated RT callback scratch buffers (0 dynamic allocs in onAudioReady)
    alignas(64) std::array<float, kMaxScratchFrames> scratch_in_{};
    alignas(64) std::array<float, kMaxScratchFrames> scratch_out_l_{};
    alignas(64) std::array<float, kMaxScratchFrames> scratch_out_r_{};

    // ADPF dynamic function pointers (API 33+ safe when minSdk == 31)
    void* adpf_lib_ = nullptr;
    void* adpf_manager_ = nullptr;
    void* adpf_session_ = nullptr;
    void (*adpf_report_fn_)(void*, int64_t) = nullptr;
    void (*adpf_close_fn_)(void*) = nullptr;
};

}  // namespace stadium_dsp
