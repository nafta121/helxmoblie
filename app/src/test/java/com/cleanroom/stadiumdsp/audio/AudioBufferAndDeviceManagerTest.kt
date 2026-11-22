package com.cleanroom.stadiumdsp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBufferAndDeviceManagerTest {

    @Test
    fun hardwareAlignedOptionsRespectDeviceBurstAndCapacity() {
        val opts = AudioBufferPlanner.buildHardwareAlignedOptions(
            sampleRate = 48000,
            framesPerBurst = 96,
            bufferCapacityFrames = 384,
        )
        // Multiples fitting 384f: 1x (96), 2x (192), 3x (288), 4x (384)
        assertEquals(listOf(96, 192, 288, 384), opts.map { it.frames })
        assertEquals(2.0f, opts[0].latencyMs, 1e-3f)
        assertEquals(4.0f, opts[1].latencyMs, 1e-3f)
        assertTrue(opts.all { it.isNativeAligned && it.isWithinCapacity })
    }

    @Test
    fun fixedFramePresetsFlagNativeMmapAlignmentAndCapacityBounds() {
        val presets = AudioBufferPlanner.buildFixedFramePresets(
            sampleRate = 48000,
            framesPerBurst = 96,
            bufferCapacityFrames = 512,
        )
        val p48 = presets.first { it.frames == 48 }
        val p96 = presets.first { it.frames == 96 }
        val p128 = presets.first { it.frames == 128 }
        val p192 = presets.first { it.frames == 192 }
        val p1024 = presets.first { it.frames == 1024 }

        assertFalse(p48.isWithinCapacity)
        assertFalse(p48.isNativeAligned)

        assertTrue(p96.isNativeAligned)
        assertTrue(p96.isWithinCapacity)
        assertEquals(1, p96.multiplier)

        assertFalse(p128.isNativeAligned)
        assertTrue(p128.isWithinCapacity)

        assertTrue(p192.isNativeAligned)
        assertEquals(2, p192.multiplier)

        assertFalse(p1024.isWithinCapacity)
    }

    @Test
    fun resolveTargetBufferFramesClampsBothHardwareAndFixedModes() {
        val hw = AudioDspSettings(
            bufferSizeMode = BufferSizeMode.HARDWARE_BURST,
            burstMultiplier = 6,
        )
        assertEquals(384, AudioBufferPlanner.resolveTargetBufferFrames(hw, framesPerBurst = 96, bufferCapacityFrames = 384))

        val dawTooLow = AudioDspSettings(
            bufferSizeMode = BufferSizeMode.FIXED_FRAMES,
            requestedBufferFrames = 48,
        )
        assertEquals(96, AudioBufferPlanner.resolveTargetBufferFrames(dawTooLow, framesPerBurst = 96, bufferCapacityFrames = 512))

        val dawValid = AudioDspSettings(
            bufferSizeMode = BufferSizeMode.FIXED_FRAMES,
            requestedBufferFrames = 256,
        )
        assertEquals(256, AudioBufferPlanner.resolveTargetBufferFrames(dawValid, framesPerBurst = 96, bufferCapacityFrames = 512))
    }

    @Test
    fun needsStreamRestartIsFalseForLiveBufferChangesAndTrueForDeviceChanges() {
        val base = AudioDspSettings(
            enableMicrophoneInput = true,
            inputDeviceId = 0,
            outputDeviceId = 0,
            bufferSizeMode = BufferSizeMode.HARDWARE_BURST,
            burstMultiplier = 2,
            autoXrunGuard = true,
        )

        // Changing buffer mode, multiplier, fixed frames, auto XRun guard, or keep-awake needs NO restart!
        val liveOnly = base.copy(
            bufferSizeMode = BufferSizeMode.FIXED_FRAMES,
            burstMultiplier = 4,
            requestedBufferFrames = 256,
            autoXrunGuard = false,
            keepScreenAwakeInLive = false,
        )
        assertFalse(AudioBufferPlanner.needsStreamRestart(base, liveOnly))

        // Changing input device, output device, or mic toggle requires clean stream reopen
        assertTrue(AudioBufferPlanner.needsStreamRestart(base, base.copy(inputDeviceId = 12)))
        assertTrue(AudioBufferPlanner.needsStreamRestart(base, base.copy(outputDeviceId = 18)))
        assertTrue(AudioBufferPlanner.needsStreamRestart(base, base.copy(enableMicrophoneInput = false)))
    }

    @Test
    fun autoXrunGuardStepsByOneHardwareBurstUntilCapacity() {
        assertEquals(192, AudioBufferPlanner.computeNextXrunGuardStep(96, 96, 384))
        assertEquals(288, AudioBufferPlanner.computeNextXrunGuardStep(192, 96, 384))
        assertEquals(384, AudioBufferPlanner.computeNextXrunGuardStep(320, 96, 384))
        assertNull(AudioBufferPlanner.computeNextXrunGuardStep(384, 96, 384))
    }

    @Test
    fun unpluggedSavedDeviceFallsBackCleanlyToSystemDefault() {
        val devices = listOf(
            AudioDeviceOption(id = 0, name = "System Default (Auto)", typeBadge = "AUTO", isInput = true),
            AudioDeviceOption(id = 42, name = "Focusrite Scarlett", typeBadge = "USB-C", isInput = true, isUsbOrPro = true),
        )
        assertEquals(42, AudioBufferPlanner.resolveAvailableDeviceId(42, devices))
        assertEquals(0, AudioBufferPlanner.resolveAvailableDeviceId(99, devices))
        assertEquals(0, AudioBufferPlanner.resolveAvailableDeviceId(0, devices))
    }
}
