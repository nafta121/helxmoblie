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
    val burstMultiplier: Int = 2, // 1x (Ultra-Low), 2x (Stage Safe), 4x (Compatibility)
    val interfaceMaxDbu: Float = 12.0f,
    val keepScreenAwakeInLive: Boolean = true,
)

/**
 * Process-scoped Singleton Audio Session Controller (GAP-23):
 * Keeps the C++20 Oboe AAudio MMAP stream alive across Activity configuration changes
 * (Portrait <-> Landscape rotation, foldable hinge changes) with 0 ms audio dropout!
 */
object OboeSessionController {
    var bridge: DspBridgeGateway = NativeDspBridge
    var currentSettings: AudioDspSettings = AudioDspSettings()
        private set

    val isRunning: Boolean get() = bridge.isAudioRunning()

    @Synchronized
    fun ensureStarted(context: Context, settings: AudioDspSettings = currentSettings): Boolean {
        currentSettings = settings
        if (bridge.isAudioRunning()) return true
        val ok = bridge.startAudio(
            enableInput = settings.enableMicrophoneInput,
            inputDeviceId = settings.inputDeviceId,
            outputDeviceId = settings.outputDeviceId,
            burstMultiplier = settings.burstMultiplier,
        )
        if (ok) {
            StadiumAudioForegroundService.start(context)
        }
        return ok
    }

    @Synchronized
    fun restartWithSettings(context: Context, settings: AudioDspSettings): Boolean {
        currentSettings = settings
        bridge.stopAudio()
        val ok = bridge.startAudio(
            enableInput = settings.enableMicrophoneInput,
            inputDeviceId = settings.inputDeviceId,
            outputDeviceId = settings.outputDeviceId,
            burstMultiplier = settings.burstMultiplier,
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

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
        return START_STICKY
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
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StadiumAudioForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
