package com.cleanroom.stadiumdsp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cleanroom.stadiumdsp.audio.AudioDspSettings
import com.cleanroom.stadiumdsp.audio.OboeSessionController
import com.cleanroom.stadiumdsp.importer.ExternalRigFileImporter
import com.cleanroom.stadiumdsp.midi.StadiumMidiService
import com.cleanroom.stadiumdsp.ui.StadiumRigScreen
import com.cleanroom.stadiumdsp.ui.state.StadiumRigViewModel
import com.cleanroom.stadiumdsp.ui.state.WorkspaceDestination
import com.cleanroom.stadiumdsp.ui.theme.StadiumDspTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-Activity Adaptive Host for Hx Mobile (Stadium DSP).
 * - Full Edge-to-Edge (`enableEdgeToEdge()`, `isNavigationBarContrastEnforced = false`)
 * - 0-ms Rotation Audio Continuity: NEVER calls `stopAll()` when `isChangingConfigurations == true`!
 * - Automatic `FLAG_KEEP_SCREEN_ON` in LIVE Play / Tuner stage modes
 * - USB-MIDI & BLE-MIDI `MidiManager` lifecycle binding + SAF `.nam/.aidax/.wav` importer
 */
class MainActivity : ComponentActivity() {

    private val rigViewModel: StadiumRigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false

        setContent {
            StadiumDspTheme {
                var currentDest by rememberSaveable { mutableStateOf(WorkspaceDestination.EDIT) }

                // Permission launcher for full-duplex guitar/mic input (graceful fallback to Output-Only if denied)
                val audioPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    val settings = rigViewModel.uiState.value.audioSettings.copy(enableMicrophoneInput = granted)
                    lifecycleScope.launch(Dispatchers.IO) {
                        rigViewModel.startAudioEngine(applicationContext, settings)
                    }
                }

                // SAF Document Picker for .nam, .aidax, and .wav IR files
                val safPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ExternalRigFileImporter.readUriBytes(applicationContext, uri)
                            }
                            result.onSuccess { imported ->
                                rigViewModel.importExternalFile(imported)
                            }.onFailure { err ->
                                rigViewModel.importExternalFile(
                                    com.cleanroom.stadiumdsp.importer.ImportedExternalFile(
                                        fileName = err.message ?: "invalid",
                                        kind = com.cleanroom.stadiumdsp.importer.ExternalRigAssetKind.UNKNOWN,
                                        bytes = ByteArray(0),
                                    )
                                )
                            }
                        }
                    }
                }

                // Start Oboe audio engine AFTER the first Compose frame commits so the main Looper
                // is guaranteed responsive for StadiumAudioForegroundService.startForeground() (0 ANR).
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    val hasMic = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMic) {
                        withContext(Dispatchers.IO) {
                            rigViewModel.startAudioEngine(applicationContext)
                        }
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                // 60 Hz Lock-Free Telemetry Polling Loop (isolated to telemetry StateFlow!)
                LaunchedEffect(Unit) {
                    while (isActive) {
                        rigViewModel.pollTelemetryFrame()
                        delay(16L)
                    }
                }

                // USB/BLE MIDI Manager Lifecycle Binding
                DisposableEffect(Unit) {
                    val midiService = StadiumMidiService(
                        context = applicationContext,
                        parser = rigViewModel.midiParser,
                        onDevicesChanged = { devices -> rigViewModel.updateMidiDevices(devices) },
                    )
                    midiService.start()
                    onDispose { midiService.stop() }
                }

                // Keep Screen Awake during LIVE Play & Stage Tuner
                LaunchedEffect(currentDest) {
                    val keepAwake = (currentDest == WorkspaceDestination.LIVE_PLAY || currentDest == WorkspaceDestination.TUNER) &&
                        rigViewModel.uiState.value.audioSettings.keepScreenAwakeInLive
                    if (keepAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                StadiumRigScreen(
                    viewModel = rigViewModel,
                    onImportExternalAsset = {
                        ExternalRigFileImporter.launchSafDocumentPicker(safPickerLauncher) { /* handled in status */ }
                    },
                    onApplyAudioSettings = { newSettings: AudioDspSettings ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            rigViewModel.restartAudioWithSettings(applicationContext, newSettings)
                        }
                    },
                    onStartAudio = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            rigViewModel.startAudioEngine(applicationContext)
                        }
                    },
                    onDestinationChanged = { currentDest = it },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // CRITICAL: Never stop Oboe audio during rotation or foldable hinge configuration changes!
        if (!isChangingConfigurations && isFinishing) {
            OboeSessionController.stopAll(applicationContext)
        }
    }
}
