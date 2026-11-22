package com.cleanroom.stadiumdsp.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlin.math.max
import kotlin.math.min

enum class BufferSizeMode(val label: String) {
    HARDWARE_BURST("HW Burst (MMAP)"),
    FIXED_FRAMES("Fixed Frames (DAW)"),
}

data class AudioBufferOption(
    val frames: Int,
    val latencyMs: Float,
    val label: String,
    val multiplier: Int? = null,
    val isNativeAligned: Boolean = true,
    val isWithinCapacity: Boolean = true,
)

data class AudioDeviceOption(
    val id: Int,
    val name: String,
    val typeBadge: String,
    val isInput: Boolean,
    val isUsbOrPro: Boolean = false,
    val isBluetoothWarning: Boolean = false,
) {
    val displayLabel: String
        get() = if (id == 0) name else "$name ($typeBadge)"
}

/**
 * Pure deterministic buffer planning & live-vs-restart policy (100% JVM unit-testable).
 */
object AudioBufferPlanner {
    val STANDARD_FIXED_FRAMES: List<Int> = listOf(48, 64, 96, 128, 192, 256, 384, 512, 1024)

    fun framesToMs(frames: Int, sampleRate: Int = 48000): Float =
        if (sampleRate > 0) (frames * 1000f) / sampleRate else 0f

    fun buildHardwareAlignedOptions(
        sampleRate: Int,
        framesPerBurst: Int,
        bufferCapacityFrames: Int,
    ): List<AudioBufferOption> {
        val burst = max(16, framesPerBurst)
        val cap = max(burst, bufferCapacityFrames)
        val mults = listOf(
            1 to "1x Ultra",
            2 to "2x Stage",
            3 to "3x Smooth",
            4 to "4x Safe",
            6 to "6x Heavy",
            8 to "8x Max",
        )
        val options = mutableListOf<AudioBufferOption>()
        for ((m, tag) in mults) {
            val f = burst * m
            if (f <= cap || m <= 2) {
                options.add(
                    AudioBufferOption(
                        frames = min(f, cap),
                        latencyMs = framesToMs(min(f, cap), sampleRate),
                        label = tag,
                        multiplier = m,
                        isNativeAligned = true,
                        isWithinCapacity = f <= cap,
                    )
                )
            }
        }
        return options.distinctBy { it.frames }
    }

    fun buildFixedFramePresets(
        sampleRate: Int,
        framesPerBurst: Int,
        bufferCapacityFrames: Int,
    ): List<AudioBufferOption> {
        val burst = max(16, framesPerBurst)
        val cap = max(burst, bufferCapacityFrames)
        return STANDARD_FIXED_FRAMES.map { frames ->
            AudioBufferOption(
                frames = frames,
                latencyMs = framesToMs(frames, sampleRate),
                label = "${frames}f",
                multiplier = if (frames % burst == 0) frames / burst else null,
                isNativeAligned = frames >= burst && (frames % burst == 0),
                isWithinCapacity = frames in burst..cap,
            )
        }
    }

    fun resolveTargetBufferFrames(
        settings: AudioDspSettings,
        framesPerBurst: Int,
        bufferCapacityFrames: Int,
    ): Int {
        val burst = max(16, framesPerBurst)
        val cap = max(burst, bufferCapacityFrames)
        val raw = when (settings.bufferSizeMode) {
            BufferSizeMode.HARDWARE_BURST -> burst * settings.burstMultiplier.coerceIn(1, 16)
            BufferSizeMode.FIXED_FRAMES -> {
                if (settings.requestedBufferFrames > 0) settings.requestedBufferFrames
                else burst * settings.burstMultiplier.coerceIn(1, 16)
            }
        }
        return raw.coerceIn(burst, cap)
    }

    /**
     * Returns true ONLY when stream topology changes (device ID or full-duplex mic toggle).
     * Pure buffer size / Auto XRun Guard / Keep-Awake edits apply LIVE with 0-ms dropout!
     */
    fun needsStreamRestart(previous: AudioDspSettings, next: AudioDspSettings): Boolean =
        previous.enableMicrophoneInput != next.enableMicrophoneInput ||
            previous.inputDeviceId != next.inputDeviceId ||
            previous.outputDeviceId != next.outputDeviceId

    /**
     * Computes next step-up buffer size (+1 hardware burst) for Auto XRun Guard.
     * Returns null if buffer is already at hardware capacity.
     */
    fun computeNextXrunGuardStep(
        currentBufferFrames: Int,
        framesPerBurst: Int,
        bufferCapacityFrames: Int,
    ): Int? {
        val burst = max(16, framesPerBurst)
        val cap = max(burst, bufferCapacityFrames)
        if (currentBufferFrames >= cap) return null
        return min(cap, currentBufferFrames + burst)
    }

    /**
     * Validates a persisted device ID against currently attached devices; falls back to 0 (System Default).
     */
    fun resolveAvailableDeviceId(savedDeviceId: Int, availableDevices: List<AudioDeviceOption>): Int {
        if (savedDeviceId <= 0) return 0
        return if (availableDevices.any { it.id == savedDeviceId }) savedDeviceId else 0
    }
}

/**
 * Enumerates Android AudioManager input & output routes (USB-C interfaces, wired headsets, built-in, BT).
 */
object AudioDeviceEnumerator {
    fun listInputDevices(context: Context): List<AudioDeviceOption> {
        val defaults = mutableListOf(
            AudioDeviceOption(
                id = 0,
                name = "System Default (Auto)",
                typeBadge = "AUTO",
                isInput = true,
            )
        )
        val am = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull() ?: return defaults

        val found = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        }.getOrNull().orEmpty()
            .filter { it.isSource }
            .map { info -> toOption(info, isInput = true) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<AudioDeviceOption> { it.isUsbOrPro }.thenBy { it.isBluetoothWarning }.thenBy { it.name })

        defaults.addAll(found)
        return defaults
    }

    fun listOutputDevices(context: Context): List<AudioDeviceOption> {
        val defaults = mutableListOf(
            AudioDeviceOption(
                id = 0,
                name = "System Default (Auto)",
                typeBadge = "AUTO",
                isInput = false,
            )
        )
        val am = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull() ?: return defaults

        val found = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull().orEmpty()
            .filter { it.isSink }
            .map { info -> toOption(info, isInput = false) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<AudioDeviceOption> { it.isUsbOrPro }.thenBy { it.isBluetoothWarning }.thenBy { it.name })

        defaults.addAll(found)
        return defaults
    }

    private fun toOption(info: AudioDeviceInfo, isInput: Boolean): AudioDeviceOption {
        val rawName = info.productName?.toString()?.trim().orEmpty()
        val (badge, isPro, isBt) = when (info.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> Triple("USB-C", true, false)
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> Triple("WIRED", true, false)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> Triple("BT HIGH LAT", false, true)
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> Triple("BUILT-IN", false, false)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Triple("SPEAKER", false, false)
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Triple("EARPIECE", false, false)
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> Triple("HDMI", true, false)
            else -> Triple("AUDIO", false, false)
        }
        val cleanName = rawName.ifBlank {
            if (isInput) "Input #$badge" else "Output #$badge"
        }
        return AudioDeviceOption(
            id = info.id,
            name = cleanName,
            typeBadge = badge,
            isInput = isInput,
            isUsbOrPro = isPro,
            isBluetoothWarning = isBt,
        )
    }
}

/**
 * SharedPreferences persistence for Audio/DSP buffer & device preferences across app launches.
 */
object AudioSettingsRepository {
    private const val PREFS_NAME = "stadium_audio_dsp_settings"
    private const val KEY_ENABLE_MIC = "enable_mic"
    private const val KEY_INPUT_DEV = "input_device_id"
    private const val KEY_OUTPUT_DEV = "output_device_id"
    private const val KEY_BUFFER_MODE = "buffer_size_mode"
    private const val KEY_BURST_MULT = "burst_multiplier"
    private const val KEY_REQ_FRAMES = "requested_buffer_frames"
    private const val KEY_AUTO_XRUN = "auto_xrun_guard"
    private const val KEY_MAX_DBU = "interface_max_dbu"
    private const val KEY_KEEP_AWAKE = "keep_screen_awake"

    fun load(context: Context): AudioDspSettings = runCatching {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeName = p.getString(KEY_BUFFER_MODE, BufferSizeMode.HARDWARE_BURST.name)
        val mode = runCatching { BufferSizeMode.valueOf(modeName ?: "") }
            .getOrDefault(BufferSizeMode.HARDWARE_BURST)
        AudioDspSettings(
            enableMicrophoneInput = p.getBoolean(KEY_ENABLE_MIC, true),
            inputDeviceId = p.getInt(KEY_INPUT_DEV, 0),
            outputDeviceId = p.getInt(KEY_OUTPUT_DEV, 0),
            bufferSizeMode = mode,
            burstMultiplier = p.getInt(KEY_BURST_MULT, 2).coerceIn(1, 16),
            requestedBufferFrames = p.getInt(KEY_REQ_FRAMES, 0).coerceAtLeast(0),
            autoXrunGuard = p.getBoolean(KEY_AUTO_XRUN, true),
            interfaceMaxDbu = p.getFloat(KEY_MAX_DBU, 12.0f),
            keepScreenAwakeInLive = p.getBoolean(KEY_KEEP_AWAKE, true),
        )
    }.getOrDefault(AudioDspSettings())

    fun save(context: Context, settings: AudioDspSettings) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLE_MIC, settings.enableMicrophoneInput)
                .putInt(KEY_INPUT_DEV, settings.inputDeviceId)
                .putInt(KEY_OUTPUT_DEV, settings.outputDeviceId)
                .putString(KEY_BUFFER_MODE, settings.bufferSizeMode.name)
                .putInt(KEY_BURST_MULT, settings.burstMultiplier)
                .putInt(KEY_REQ_FRAMES, settings.requestedBufferFrames)
                .putBoolean(KEY_AUTO_XRUN, settings.autoXrunGuard)
                .putFloat(KEY_MAX_DBU, settings.interfaceMaxDbu)
                .putBoolean(KEY_KEEP_AWAKE, settings.keepScreenAwakeInLive)
                .apply()
        }
    }
}
