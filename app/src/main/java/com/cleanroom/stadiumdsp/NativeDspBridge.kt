package com.cleanroom.stadiumdsp

/**
 * Exact integer command type codes mirroring C++ `stadium_dsp::DspCommandType`.
 */
object DspCommandCode {
    const val NONE = 0
    const val SET_AMP_MODEL = 1
    const val SET_AMP_PARAM = 2
    const val SET_AMP_FOCUS_XY = 3
    const val SET_CLONE_PARAM = 4
    const val SET_CAB_SLOT_PARAM = 5
    const val SET_DRIVE_FUZZ = 6
    const val SET_DYNAMICS = 7
    const val SET_WAH_FILTER = 8
    const val SET_EQ_PARAM = 9
    const val SET_MODULATION = 10
    const val SET_PITCH_SYNTH = 11
    const val SET_DELAY_REVERB = 12
    const val SET_VOLUME_PAN = 13
    const val SET_IO_TRIM_PAN = 14
    const val SET_MASTER_MUTE = 15
    const val SET_BLOCK_BYPASS = 16
    const val SET_BLOCK_STEREO = 17
    const val SET_ROUTING_SLOT_ORDER = 18
    const val SET_PARALLEL_SPLIT_MERGE = 19
    const val SET_TUNER_CONFIG = 20
    const val SET_GLOBAL_BPM = 21
    const val SET_LOOPER_COMMAND = 22
    const val SET_PRESET_CLIP_COMMAND = 23
    const val RECALL_SNAPSHOT = 24
    const val SET_AUTO_Z_CONFIG = 25
    const val SET_RIG_ROUTING_MODE = 26
}

/**
 * Exact integer block indices mirroring C++ `stadium_dsp::RigBlockType`.
 */
object RigBlockCode {
    const val GATE = 0
    const val PITCH = 1
    const val COMP = 2
    const val WAH = 3
    const val DRIVE = 4
    const val MOD_PRE = 5
    const val AMP = 6
    const val CAB = 7
    const val EQ = 8
    const val VOL_PAN = 9
    const val MOD_POST = 10
    const val DELAY = 11
    const val REVERB = 12
}

data class CloneMetadataUi(
    val loaded: Boolean = false,
    val format: String = "",
    val architecture: String = "",
    val brand: String = "",
    val product: String = "",
    val author: String = "",
    val notes: String = "",
    val refInputDbu: Float = 12.0f,
    val outputGainDb: Float = 0.0f,
)

data class StreamDiagnosticsUi(
    val sampleRate: Int = 48000,
    val framesPerBurst: Int = 192,
    val bufferSizeFrames: Int = 384,
    val isMmapUsed: Boolean = false,
    val xRunCount: Int = 0,
    val bufferCapacityFrames: Int = 768,
    val inputFramesPerBurst: Int = 0,
    val measuredLatencyUs: Int = -1,
) {
    val outputBufferMs: Float
        get() = if (sampleRate > 0) (bufferSizeFrames * 1000f) / sampleRate else 0f

    val measuredLatencyMs: Float?
        get() = if (measuredLatencyUs > 0) measuredLatencyUs / 1000f else null

    /**
     * Reports hardware AAudio timestamp latency when available, otherwise output + input buffer latency.
     */
    val roundTripLatencyMs: Float
        get() {
            measuredLatencyMs?.let { return it }
            if (sampleRate <= 0) return 0f
            val totalFrames = bufferSizeFrames + if (inputFramesPerBurst > 0) inputFramesPerBurst else 0
            return (totalFrames * 1000f) / sampleRate
        }
}

/**
 * Zero-GC lock-free real-time telemetry frame polled at 60 Hz from C++20.
 */
data class LiveTelemetryUi(
    val inPeakDb: Float = -96f,
    val outPeakLDb: Float = -96f,
    val outPeakRDb: Float = -96f,
    val gateGrDb: Float = 0f,
    val compGrDb: Float = 0f,
    val tunerFreqHz: Float = 0f,
    val tunerCents: Float = 0f,
    val tunerConfidence: Float = 0f,
    val tunerStrobePhase: Float = 0f,
    val clipPlayheadNorm: Float = 0f,
    val looperProgress: Float = 0f,
    val rtCpuLoadPct: Float = 0f,
    val tunerMidiNote: Int = -1,
    val looperState: Int = 0,
    val activeSnapshot: Int = 0,
    val statusFlags: Int = 0,
) {
    val inClipped: Boolean get() = (statusFlags and 1) != 0
    val outClipped: Boolean get() = (statusFlags and 2) != 0
    val isMasterMuted: Boolean get() = (statusFlags and 4) != 0
    val isClipPlaying: Boolean get() = (statusFlags and 8) != 0
    val isStereoCollapsed: Boolean get() = (statusFlags and 16) != 0

    companion object {
        fun fromFloat16(arr: FloatArray): LiveTelemetryUi {
            if (arr.size < 16) return LiveTelemetryUi()
            return LiveTelemetryUi(
                inPeakDb = arr[0],
                outPeakLDb = arr[1],
                outPeakRDb = arr[2],
                gateGrDb = arr[3],
                compGrDb = arr[4],
                tunerFreqHz = arr[5],
                tunerCents = arr[6],
                tunerConfidence = arr[7],
                tunerStrobePhase = arr[8],
                clipPlayheadNorm = arr[9],
                looperProgress = arr[10],
                rtCpuLoadPct = arr[11],
                tunerMidiNote = arr[12].toInt(),
                looperState = arr[13].toInt(),
                activeSnapshot = arr[14].toInt(),
                statusFlags = arr[15].toInt(),
            )
        }
    }
}

/**
 * Abstraction interface enabling deterministic JVM unit tests without requiring Android .so binaries.
 */
interface DspBridgeGateway {
    val isNativeAvailable: Boolean
    fun startAudio(
        enableInput: Boolean,
        inputDeviceId: Int = 0,
        outputDeviceId: Int = 0,
        burstMultiplier: Int = 2,
        requestedBufferFrames: Int = 0,
        autoXrunGuard: Boolean = true,
    ): Boolean
    fun stopAudio()
    fun isAudioRunning(): Boolean
    fun setLiveBufferSizeFrames(targetFrames: Int): Int
    fun setAutoXrunGuard(enabled: Boolean)
    fun getStreamDiagnostics(): StreamDiagnosticsUi
    fun enqueueCommand(
        type: Int,
        targetId: Int = 0,
        paramId: Int = 0,
        flags: Int = 0,
        value0: Float = 0f,
        value1: Float = 0f,
        value2: Float = 0f,
        intVal: Int = 0,
    ): Boolean
    fun readTelemetry(buffer16: FloatArray): LiveTelemetryUi
    fun computeEqCurveDb(outBuffer: FloatArray)
    fun computeCabSpectrumDb(slot: Int, outBuffer: FloatArray)
    fun setDynamicSlotOrder(order12: IntArray)
    fun setParallelSplitMerge(
        mode: Int,
        crossoverHz: Float,
        routeAb: Float,
        lvlADb: Float,
        lvlBDb: Float,
        panA: Float,
        panB: Float,
        invertB: Boolean,
    )
    fun storeSnapshot(idx: Int, bypassMask: Int, params11: FloatArray)
    fun recallSnapshot(idx: Int)
    fun registerTapTempoMs(timestampMs: Long): Float
    fun loadExternalIr(slot: Int, wavBytes: ByteArray): String
    fun loadExternalNam(jsonBytes: ByteArray): String
    fun loadExternalAidax(jsonBytes: ByteArray): String
    fun getCloneMetadata(): CloneMetadataUi
    fun selectFactoryPresetClipRiff(styleIndex: Int)
    fun loadCustomPresetClipWav(wavBytes: ByteArray): String
}

/**
 * Production JNI bridge to `libstadium_dsp.so` with graceful host-JVM fallback.
 */
object NativeDspBridge : DspBridgeGateway {
    override val isNativeAvailable: Boolean = try {
        System.loadLibrary("stadium_dsp")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    private external fun nativeStartAudio(
        enableInput: Boolean,
        inputDeviceId: Int,
        outputDeviceId: Int,
        burstMultiplier: Int,
        requestedBufferFrames: Int,
        autoXrunGuard: Boolean,
    ): Boolean
    private external fun nativeStopAudio()
    private external fun nativeIsAudioRunning(): Boolean
    private external fun nativeSetLiveBufferSizeFrames(targetFrames: Int): Int
    private external fun nativeSetAutoXrunGuard(enabled: Boolean)
    private external fun nativeGetStreamDiagnostics(outInt8: IntArray)
    private external fun nativeEnqueueCommand(
        type: Int,
        targetId: Int,
        paramId: Int,
        flags: Int,
        value0: Float,
        value1: Float,
        value2: Float,
        intVal: Int,
    ): Boolean
    private external fun nativeReadTelemetry(outFloat16: FloatArray)
    private external fun nativeComputeEqCurveDb(outDb: FloatArray)
    private external fun nativeComputeCabSpectrumDb(slot: Int, outDb: FloatArray)
    private external fun nativeSetDynamicSlotOrder(order12: IntArray)
    private external fun nativeSetParallelSplitMerge(
        mode: Int,
        crossoverHz: Float,
        routeAb: Float,
        lvlADb: Float,
        lvlBDb: Float,
        panA: Float,
        panB: Float,
        invertB: Boolean,
    )
    private external fun nativeStoreSnapshot(idx: Int, bypassMask: Int, params11: FloatArray)
    private external fun nativeRecallSnapshot(idx: Int)
    private external fun nativeRegisterTapTempoMs(timestampMs: Long): Float
    private external fun nativeLoadExternalIr(slot: Int, wavBytes: ByteArray): String
    private external fun nativeLoadExternalNam(jsonBytes: ByteArray): String
    private external fun nativeLoadExternalAidax(jsonBytes: ByteArray): String
    private external fun nativeGetCloneMetadata(): Array<String>
    private external fun nativeSelectFactoryPresetClipRiff(styleIndex: Int)
    private external fun nativeLoadCustomPresetClipWav(wavBytes: ByteArray): String

    override fun startAudio(
        enableInput: Boolean,
        inputDeviceId: Int,
        outputDeviceId: Int,
        burstMultiplier: Int,
        requestedBufferFrames: Int,
        autoXrunGuard: Boolean,
    ): Boolean = if (isNativeAvailable) {
        nativeStartAudio(
            enableInput,
            inputDeviceId,
            outputDeviceId,
            burstMultiplier,
            requestedBufferFrames,
            autoXrunGuard,
        )
    } else false

    override fun stopAudio() {
        if (isNativeAvailable) nativeStopAudio()
    }

    override fun isAudioRunning(): Boolean = if (isNativeAvailable) nativeIsAudioRunning() else false

    override fun setLiveBufferSizeFrames(targetFrames: Int): Int =
        if (isNativeAvailable) nativeSetLiveBufferSizeFrames(targetFrames) else targetFrames

    override fun setAutoXrunGuard(enabled: Boolean) {
        if (isNativeAvailable) nativeSetAutoXrunGuard(enabled)
    }

    override fun getStreamDiagnostics(): StreamDiagnosticsUi {
        if (!isNativeAvailable) return StreamDiagnosticsUi()
        val arr = IntArray(8)
        nativeGetStreamDiagnostics(arr)
        return StreamDiagnosticsUi(
            sampleRate = arr[0],
            framesPerBurst = arr[1],
            bufferSizeFrames = arr[2],
            isMmapUsed = arr[3] != 0,
            xRunCount = arr[4],
            bufferCapacityFrames = arr[5].coerceAtLeast(arr[1]),
            inputFramesPerBurst = arr[6],
            measuredLatencyUs = arr[7],
        )
    }

    override fun enqueueCommand(
        type: Int,
        targetId: Int,
        paramId: Int,
        flags: Int,
        value0: Float,
        value1: Float,
        value2: Float,
        intVal: Int,
    ): Boolean = if (isNativeAvailable) {
        nativeEnqueueCommand(type, targetId, paramId, flags, value0, value1, value2, intVal)
    } else true

    override fun readTelemetry(buffer16: FloatArray): LiveTelemetryUi {
        if (!isNativeAvailable) return LiveTelemetryUi()
        nativeReadTelemetry(buffer16)
        return LiveTelemetryUi.fromFloat16(buffer16)
    }

    override fun computeEqCurveDb(outBuffer: FloatArray) {
        if (isNativeAvailable) nativeComputeEqCurveDb(outBuffer)
    }

    override fun computeCabSpectrumDb(slot: Int, outBuffer: FloatArray) {
        if (isNativeAvailable) nativeComputeCabSpectrumDb(slot, outBuffer)
    }

    override fun setDynamicSlotOrder(order12: IntArray) {
        if (isNativeAvailable) nativeSetDynamicSlotOrder(order12)
    }

    override fun setParallelSplitMerge(
        mode: Int,
        crossoverHz: Float,
        routeAb: Float,
        lvlADb: Float,
        lvlBDb: Float,
        panA: Float,
        panB: Float,
        invertB: Boolean,
    ) {
        if (isNativeAvailable) {
            nativeSetParallelSplitMerge(mode, crossoverHz, routeAb, lvlADb, lvlBDb, panA, panB, invertB)
        }
    }

    override fun storeSnapshot(idx: Int, bypassMask: Int, params11: FloatArray) {
        if (isNativeAvailable) nativeStoreSnapshot(idx, bypassMask, params11)
    }

    override fun recallSnapshot(idx: Int) {
        if (isNativeAvailable) nativeRecallSnapshot(idx)
    }

    override fun registerTapTempoMs(timestampMs: Long): Float =
        if (isNativeAvailable) nativeRegisterTapTempoMs(timestampMs) else 120f

    override fun loadExternalIr(slot: Int, wavBytes: ByteArray): String =
        if (isNativeAvailable) nativeLoadExternalIr(slot, wavBytes) else "Native engine unavailable"

    override fun loadExternalNam(jsonBytes: ByteArray): String =
        if (isNativeAvailable) nativeLoadExternalNam(jsonBytes) else "Native engine unavailable"

    override fun loadExternalAidax(jsonBytes: ByteArray): String =
        if (isNativeAvailable) nativeLoadExternalAidax(jsonBytes) else "Native engine unavailable"

    override fun getCloneMetadata(): CloneMetadataUi {
        if (!isNativeAvailable) return CloneMetadataUi()
        val f = nativeGetCloneMetadata()
        if (f.size < 9) return CloneMetadataUi()
        return CloneMetadataUi(
            loaded = f[0] == "true",
            format = f[1],
            architecture = f[2],
            brand = f[3],
            product = f[4],
            author = f[5],
            notes = f[6],
            refInputDbu = f[7].toFloatOrNull() ?: 12f,
            outputGainDb = f[8].toFloatOrNull() ?: 0f,
        )
    }

    override fun selectFactoryPresetClipRiff(styleIndex: Int) {
        if (isNativeAvailable) nativeSelectFactoryPresetClipRiff(styleIndex)
    }

    override fun loadCustomPresetClipWav(wavBytes: ByteArray): String =
        if (isNativeAvailable) nativeLoadCustomPresetClipWav(wavBytes) else "Native engine unavailable"
}
