package com.cleanroom.stadiumdsp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.cleanroom.stadiumdsp.DspBridgeGateway
import com.cleanroom.stadiumdsp.NativeDspBridge
import com.cleanroom.stadiumdsp.R
import com.cleanroom.stadiumdsp.StreamDiagnosticsUi

data class AudioDspSettings(
    val enableMicrophoneInput: Boolean = true,
    val inputDeviceId: Int = 0,
    val outputDeviceId: Int = 0,
    val bufferSizeMode: BufferSizeMode = BufferSizeMode.HARDWARE_BURST,
    val burstMultiplier: Int = 2, // 1x (Ultra-Low), 2x (Stage Safe), 3x, 4x (Safe), 6x, 8x
    val requestedBufferFrames: Int = 0, // Used when bufferSizeMode == FIXED_FRAMES (0 = auto burst * mult)
    val autoXrunGuard: Boolean = true,
    val interfaceMaxDbu: Float = 12.0f,
    val keepScreenAwakeInLive: Boolean = true,
)

/**
 * Process-scoped Singleton Audio Session Controller (GAP-23):
 * Keeps the C++20 Oboe AAudio MMAP stream alive across Activity configuration changes
 * (Portrait <-> Landscape rotation, foldable hinge changes) with 0 ms audio dropout,
 * and applies live buffer size edits without restarting the stream!
 */
object OboeSessionController {
    var bridge: DspBridgeGateway = NativeDspBridge
    var currentSettings: AudioDspSettings = AudioDspSettings()
        private set

    val isRunning: Boolean get() = bridge.isAudioRunning()

    private fun effectiveRequestedFrames(settings: AudioDspSettings): Int =
        if (settings.bufferSizeMode == BufferSizeMode.FIXED_FRAMES) settings.requestedBufferFrames else 0

    @Synchronized
    fun ensureStarted(context: Context, settings: AudioDspSettings = currentSettings): Boolean {
        currentSettings = settings
        if (bridge.isAudioRunning()) return true
        val ok = bridge.startAudio(
            enableInput = settings.enableMicrophoneInput,
            inputDeviceId = settings.inputDeviceId,
            outputDeviceId = settings.outputDeviceId,
            burstMultiplier = settings.burstMultiplier,
            requestedBufferFrames = effectiveRequestedFrames(settings),
            autoXrunGuard = settings.autoXrunGuard,
        )
        if (ok) {
            StadiumAudioForegroundService.start(context)
        }
        return ok
    }

    /**
     * Applies updated audio settings with zero-glitch live buffer tuning whenever stream topology
     * (device IDs / mic input) did not change; restarts stream only when I/O route changed.
     */
    @Synchronized
    fun applySettings(context: Context, settings: AudioDspSettings): Boolean {
        val prev = currentSettings
        AudioSettingsRepository.save(context, settings)
        if (bridge.isAudioRunning() && !AudioBufferPlanner.needsStreamRestart(prev, settings)) {
            currentSettings = settings
            val diag = bridge.getStreamDiagnostics()
            val target = AudioBufferPlanner.resolveTargetBufferFrames(
                settings = settings,
                framesPerBurst = diag.framesPerBurst,
                bufferCapacityFrames = diag.bufferCapacityFrames,
            )
            bridge.setLiveBufferSizeFrames(target)
            bridge.setAutoXrunGuard(settings.autoXrunGuard)
            return true
        }
        return restartWithSettings(context, settings)
    }

    @Synchronized
    fun setLiveBufferFrames(
        context: Context,
        targetFrames: Int,
        mode: BufferSizeMode,
        burstMultiplier: Int,
    ): Int {
        val updated = currentSettings.copy(
            bufferSizeMode = mode,
            burstMultiplier = burstMultiplier.coerceIn(1, 16),
            requestedBufferFrames = if (mode == BufferSizeMode.FIXED_FRAMES) targetFrames.coerceAtLeast(16) else 0,
        )
        currentSettings = updated
        AudioSettingsRepository.save(context, updated)
        return if (bridge.isAudioRunning()) {
            bridge.setLiveBufferSizeFrames(targetFrames)
        } else {
            targetFrames
        }
    }

    @Synchronized
    fun restartWithSettings(context: Context, settings: AudioDspSettings): Boolean {
        currentSettings = settings
        AudioSettingsRepository.save(context, settings)
        bridge.stopAudio()
        val ok = bridge.startAudio(
            enableInput = settings.enableMicrophoneInput,
            inputDeviceId = settings.inputDeviceId,
            outputDeviceId = settings.outputDeviceId,
            burstMultiplier = settings.burstMultiplier,
            requestedBufferFrames = effectiveRequestedFrames(settings),
            autoXrunGuard = settings.autoXrunGuard,
        )
        if (ok) {
            StadiumAudioForegroundService.start(context)
        }
        return ok
    }

    @Synchronized
    fun stopAll(context: Context) {
        bridge.stopAudio()
        StadiumAudioForegroundService.stop(context)
    }

    fun diagnostics(): StreamDiagnosticsUi = bridge.getStreamDiagnostics()

    /**
     * Computes hardware compatibility headroom percentage based on live RT CPU budget (0..100%).
     */
    fun estimateDspCompatibilityPct(rtCpuLoadPct: Float): Int =
        (100f - rtCpuLoadPct.coerceIn(0f, 100f)).toInt().coerceIn(0, 100)
}

/**
 * GAP-23: Foreground Service (`microphone|mediaPlayback`) preventing Android Doze/Background limits
 * from throttling or silencing guitar audio when the phone screen dims or locks on stage.
 */
class StadiumAudioForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        promoteToForeground()
        return START_STICKY
    }

    private fun promoteToForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (OboeSessionController.currentSettings.enableMicrophoneInput) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            runCatching {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            }.onFailure {
                // Fallback to mediaPlayback-only when RECORD_AUDIO permission hasn't been granted yet
                runCatching {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.audio_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.audio_service_notification_title))
            .setContentText(getString(R.string.audio_service_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "stadium_dsp_rt_audio"
        private const val NOTIFICATION_ID = 4801
        private const val ACTION_STOP = "com.cleanroom.stadiumdsp.action.STOP_AUDIO_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, StadiumAudioForegroundService::class.java)
            // Prefer startService() while Activity is foregrounded (promotes via startForeground()
            // in onCreate/onStartCommand without arming Android 12+ cold-start FGS timeout crashes).
            runCatching { context.startService(intent) }.onFailure {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StadiumAudioForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
