#include "OboeGuitarEngine.hpp"

#include <algorithm>
#include <cstring>
#include <dlfcn.h>
#include <thread>
#include <time.h>
#include <unistd.h>

#include <android/log.h>

#include "stadium_dsp/simd_utils.hpp"

#define LOG_TAG "StadiumOboeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace stadium_dsp {

namespace {
inline int64_t GetThreadCpuTimeNs() noexcept {
    struct timespec ts {};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}
}  // namespace

OboeGuitarEngine::OboeGuitarEngine() {
    adpf_lib_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
}

OboeGuitarEngine::~OboeGuitarEngine() {
    Stop();
    if (adpf_lib_) {
        dlclose(adpf_lib_);
        adpf_lib_ = nullptr;
    }
}

void OboeGuitarEngine::InitAdpfSessionIfSupported(int32_t tid, int64_t target_duration_ns) noexcept {
    if (!adpf_lib_ || adpf_session_ != nullptr) return;

    using GetMgrFn = void* (*)();
    using CreateSessionFn = void* (*)(void*, const int32_t*, size_t, int64_t);
    using ReportFn = void (*)(void*, int64_t);
    using CloseFn = void (*)(void*);

    auto get_mgr = reinterpret_cast<GetMgrFn>(dlsym(adpf_lib_, "APerformanceHint_getManager"));
    auto create_sess = reinterpret_cast<CreateSessionFn>(dlsym(adpf_lib_, "APerformanceHint_createSession"));
    auto report_fn = reinterpret_cast<ReportFn>(dlsym(adpf_lib_, "APerformanceHint_reportActualWorkDuration"));
    auto close_fn = reinterpret_cast<CloseFn>(dlsym(adpf_lib_, "APerformanceHint_closeSession"));

    if (!get_mgr || !create_sess || !report_fn || !close_fn) return;
    adpf_manager_ = get_mgr();
    if (!adpf_manager_) return;

    int32_t tids[1] = {tid};
    adpf_session_ = create_sess(adpf_manager_, tids, 1, target_duration_ns);
    if (adpf_session_) {
        adpf_report_fn_ = report_fn;
        adpf_close_fn_ = close_fn;
        LOGI("ADPF PerformanceHintSession initialized (tid=%d, target=%lld ns)",
             tid, static_cast<long long>(target_duration_ns));
    }
}

void OboeGuitarEngine::ReportAdpfWorkDuration(int64_t actual_work_ns) noexcept {
    if (adpf_session_ && adpf_report_fn_ && actual_work_ns > 0) {
        adpf_report_fn_(adpf_session_, actual_work_ns);
    }
}

void OboeGuitarEngine::CloseAdpfSession() noexcept {
    if (adpf_session_ && adpf_close_fn_) {
        adpf_close_fn_(adpf_session_);
    }
    adpf_session_ = nullptr;
    adpf_report_fn_ = nullptr;
    adpf_close_fn_ = nullptr;
    adpf_initialized_.store(false, std::memory_order_release);
}

bool OboeGuitarEngine::Start(bool enable_input, int32_t input_device_id,
                             int32_t output_device_id, int32_t burst_multiplier) {
    Stop();
    std::lock_guard<std::mutex> lock(stream_mutex_);

    last_enable_input_ = enable_input;
    last_input_device_id_ = input_device_id;
    last_output_device_id_ = output_device_id;
    last_burst_multiplier_ = std::max(1, std::min(burst_multiplier, 8));

    // 1. Open Stereo Output Stream (AAudio LowLatency Exclusive + 48 kHz SRC fallback)
    oboe::AudioStreamBuilder out_builder;
    out_builder.setDirection(oboe::Direction::Output)
        ->setAudioApi(oboe::AudioApi::AAudio)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setUsage(oboe::Usage::Media)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(48000)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (output_device_id > 0) {
        out_builder.setDeviceId(output_device_id);
    }

    oboe::Result out_res = out_builder.openStream(output_stream_);
    if (out_res != oboe::Result::OK || !output_stream_) {
        LOGW("Exclusive output stream failed (%s); falling back to Shared AAudio",
             oboe::convertToText(out_res));
        out_builder.setSharingMode(oboe::SharingMode::Shared);
        out_res = out_builder.openStream(output_stream_);
        if (out_res != oboe::Result::OK || !output_stream_) {
            LOGE("Failed to open output stream: %s", oboe::convertToText(out_res));
            return false;
        }
    }

    const int32_t burst = std::max(16, output_stream_->getFramesPerBurst());
    output_stream_->setBufferSizeInFrames(burst * last_burst_multiplier_);

    // 2. Open Optional Mono Instrument Input Stream (Unprocessed Raw Guitar/Bass DI)
    has_input_.store(false, std::memory_order_release);
    if (enable_input) {
        oboe::AudioStreamBuilder in_builder;
        in_builder.setDirection(oboe::Direction::Input)
            ->setAudioApi(oboe::AudioApi::AAudio)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setInputPreset(oboe::InputPreset::Unprocessed)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(48000)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        if (input_device_id > 0) {
            in_builder.setDeviceId(input_device_id);
        }

        oboe::Result in_res = in_builder.openStream(input_stream_);
        if (in_res != oboe::Result::OK || !input_stream_) {
            LOGW("Exclusive input stream failed (%s); falling back to Shared",
                 oboe::convertToText(in_res));
            in_builder.setSharingMode(oboe::SharingMode::Shared);
            in_res = in_builder.openStream(input_stream_);
        }

        if (in_res == oboe::Result::OK && input_stream_) {
            if (input_stream_->requestStart() == oboe::Result::OK) {
                has_input_.store(true, std::memory_order_release);
                LOGI("Full-duplex input stream active (sr=%d, burst=%d, aaudio_excl=%d)",
                     input_stream_->getSampleRate(), input_stream_->getFramesPerBurst(),
                     (input_stream_->usesAAudio() &&
                      input_stream_->getSharingMode() == oboe::SharingMode::Exclusive) ? 1 : 0);
            } else {
                input_stream_->close();
                input_stream_.reset();
            }
        } else {
            LOGW("Continuing in Output-Only mode (Preset Clip / Audition still active)");
        }
    }

    ftz_initialized_.store(false, std::memory_order_release);
    adpf_initialized_.store(false, std::memory_order_release);

    oboe::Result start_res = output_stream_->requestStart();
    if (start_res != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(start_res));
        if (input_stream_) {
            input_stream_->requestStop();
            input_stream_->close();
            input_stream_.reset();
        }
        output_stream_->close();
        output_stream_.reset();
        return false;
    }

    is_running_.store(true, std::memory_order_release);
    LOGI("Stadium Oboe Engine STARTED (sr=%d, burst=%d, buf=%d, aaudio_excl=%d, input=%d)",
         output_stream_->getSampleRate(), output_stream_->getFramesPerBurst(),
         output_stream_->getBufferSizeInFrames(),
         (output_stream_->usesAAudio() &&
          output_stream_->getSharingMode() == oboe::SharingMode::Exclusive) ? 1 : 0,
         has_input_.load(std::memory_order_acquire) ? 1 : 0);
    return true;
}

void OboeGuitarEngine::Stop() {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    is_running_.store(false, std::memory_order_release);
    has_input_.store(false, std::memory_order_release);

    if (output_stream_) {
        output_stream_->requestStop();
        output_stream_->close();
        output_stream_.reset();
    }
    if (input_stream_) {
        input_stream_->requestStop();
        input_stream_->close();
        input_stream_.reset();
    }
    CloseAdpfSession();
}

AudioStreamDiagnostics OboeGuitarEngine::GetDiagnostics() const noexcept {
    AudioStreamDiagnostics d;
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (output_stream_) {
        d.sample_rate = output_stream_->getSampleRate();
        d.frames_per_burst = output_stream_->getFramesPerBurst();
        d.buffer_size_frames = output_stream_->getBufferSizeInFrames();
        d.is_mmap_used = (output_stream_->usesAAudio() &&
                          output_stream_->getSharingMode() == oboe::SharingMode::Exclusive)
                             ? 1
                             : 0;
        auto xrun = output_stream_->getXRunCount();
        d.xrun_count = xrun ? xrun.value() : 0;
    }
    return d;
}

oboe::DataCallbackResult OboeGuitarEngine::onAudioReady(oboe::AudioStream* audio_stream,
                                                        void* audio_data,
                                                        int32_t num_frames) {
    if (!audio_data || num_frames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    if (!ftz_initialized_.load(std::memory_order_relaxed)) {
        EnableFlushToZeroDenormals();
        ftz_initialized_.store(true, std::memory_order_relaxed);
    }

    const int64_t budget_ns = (static_cast<int64_t>(num_frames) * 1000000000LL) / 48000LL;
    if (!adpf_initialized_.load(std::memory_order_relaxed)) {
        InitAdpfSessionIfSupported(static_cast<int32_t>(gettid()), budget_ns);
        adpf_initialized_.store(true, std::memory_order_relaxed);
    }

    const int64_t t_start = GetThreadCpuTimeNs();
    auto* out_interleaved = static_cast<float*>(audio_data);
    const bool use_input = has_input_.load(std::memory_order_acquire) && input_stream_ != nullptr;

    int32_t remaining = num_frames;
    int32_t out_offset = 0;
    while (remaining > 0) {
        const int32_t chunk = std::min<int32_t>(remaining, static_cast<int32_t>(kMaxScratchFrames));
        std::memset(scratch_in_.data(), 0, static_cast<size_t>(chunk) * sizeof(float));

        if (use_input) {
            auto read_res = input_stream_->read(scratch_in_.data(), chunk, 0);
            if (!read_res || read_res.value() < chunk) {
                const int32_t got = read_res ? std::max(0, read_res.value()) : 0;
                if (got < chunk) {
                    std::memset(scratch_in_.data() + got, 0,
                                static_cast<size_t>(chunk - got) * sizeof(float));
                }
            }
        }

        processor_.ProcessAudioBurst(scratch_in_.data(), scratch_out_l_.data(),
                                     scratch_out_r_.data(), static_cast<size_t>(chunk));

        for (int32_t i = 0; i < chunk; ++i) {
            out_interleaved[(out_offset + i) * 2] = scratch_out_l_[static_cast<size_t>(i)];
            out_interleaved[(out_offset + i) * 2 + 1] = scratch_out_r_[static_cast<size_t>(i)];
        }

        out_offset += chunk;
        remaining -= chunk;
    }

    const int64_t work_ns = std::max<int64_t>(0, GetThreadCpuTimeNs() - t_start);
    const float cpu_pct = (budget_ns > 0)
                              ? (100.0f * static_cast<float>(work_ns) / static_cast<float>(budget_ns))
                              : 0.0f;
    processor_.SetRtCpuLoadPct(cpu_pct);
    ReportAdpfWorkDuration(work_ns);

    return oboe::DataCallbackResult::Continue;
}

void OboeGuitarEngine::onErrorAfterClose(oboe::AudioStream* /*oboe_stream*/, oboe::Result error) {
    if (error == oboe::Result::ErrorDisconnected && is_running_.load(std::memory_order_acquire)) {
        LOGW("Audio device disconnected; restarting Oboe stream cleanly on background thread");
        std::thread([this]() {
            Start(last_enable_input_, last_input_device_id_, last_output_device_id_,
                  last_burst_multiplier_);
        }).detach();
    }
}

}  // namespace stadium_dsp
