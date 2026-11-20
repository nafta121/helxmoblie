package com.cleanroom.stadiumdsp.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

sealed interface StadiumMidiAction {
    data class SelectSnapshot(val index: Int) : StadiumMidiAction
    data class ToggleStomp(val stompIndex: Int) : StadiumMidiAction
    data class ExpressionPedal(val pedalIndex: Int, val normalizedValue: Float) : StadiumMidiAction
    data object TapTempo : StadiumMidiAction
    data class ToggleTuner(val engage: Boolean) : StadiumMidiAction
    data class SelectPreset(val programNumber: Int) : StadiumMidiAction
    data class LearnedCcParameter(val targetKey: String, val ccNumber: Int, val normalizedValue: Float) : StadiumMidiAction
}

/**
 * Pure JVM MIDI 1.0 message parser matching standard Helix CC & PC maps + 1-tap MIDI Learn:
 * - CC#69 (0..7)   -> Snapshot 1..8
 * - CC#49..56      -> Footswitch Stomp 1..8
 * - CC#1 / CC#2    -> EXP 1 / EXP 2 [0.0 .. 1.0]
 * - CC#64 (>= 64)  -> Tap Tempo
 * - CC#68          -> Tuner Toggle
 * - PC (0..127)    -> Preset / Setlist recall
 */
class StadiumMidiParser(
    private val onAction: (StadiumMidiAction) -> Unit,
    private val onLearnedCc: (targetKey: String, ccNumber: Int) -> Unit = { _, _ -> },
) {
    constructor(onAction: (StadiumMidiAction) -> Unit) : this(onAction, { _, _ -> })

    private var pendingLearnTarget: String? = null
    private val learnedBindings = mutableMapOf<Int, MutableSet<String>>()

    val isLearning: Boolean get() = pendingLearnTarget != null
    val activeLearnTarget: String? get() = pendingLearnTarget

    fun armMidiLearn(targetKey: String) {
        pendingLearnTarget = targetKey
    }

    fun cancelMidiLearn() {
        pendingLearnTarget = null
    }

    fun bindCc(ccNumber: Int, targetKey: String) {
        learnedBindings.getOrPut(ccNumber) { linkedSetOf() }.add(targetKey)
    }

    fun getBoundCcForTarget(targetKey: String): Int? =
        learnedBindings.entries.firstOrNull { targetKey in it.value }?.key

    fun parseBytes(data: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = minOf(data.size, offset + count)
        while (i < end) {
            val status = data[i].toInt() and 0xFF
            val type = status and 0xF0
            when (type) {
                0xB0 -> { // Control Change (3 bytes)
                    if (i + 2 >= end) break
                    val cc = data[i + 1].toInt() and 0x7F
                    val value = data[i + 2].toInt() and 0x7F
                    handleControlChange(cc, value)
                    i += 3
                }
                0xC0 -> { // Program Change (2 bytes)
                    if (i + 1 >= end) break
                    val program = data[i + 1].toInt() and 0x7F
                    onAction(StadiumMidiAction.SelectPreset(program))
                    i += 2
                }
                else -> i += 1
            }
        }
    }

    private fun handleControlChange(cc: Int, value: Int) {
        val norm = value / 127f
        val learning = pendingLearnTarget
        if (learning != null) {
            pendingLearnTarget = null
            bindCc(cc, learning)
            onLearnedCc(learning, cc)
            return
        }

        // Emit learned custom parameter bindings first
        learnedBindings[cc]?.forEach { target ->
            onAction(StadiumMidiAction.LearnedCcParameter(target, cc, norm))
        }

        when (cc) {
            69 -> onAction(StadiumMidiAction.SelectSnapshot(value.coerceIn(0, 7)))
            in 49..56 -> if (value >= 64) onAction(StadiumMidiAction.ToggleStomp(cc - 49))
            1 -> onAction(StadiumMidiAction.ExpressionPedal(1, norm))
            2 -> onAction(StadiumMidiAction.ExpressionPedal(2, norm))
            64 -> if (value >= 64) onAction(StadiumMidiAction.TapTempo)
            68 -> onAction(StadiumMidiAction.ToggleTuner(value >= 64))
        }
    }
}

/**
 * GAP-21: Android USB-MIDI & BLE-MIDI receiver bridging `android.media.midi.MidiManager`
 * directly into `StadiumMidiParser`.
 */
class StadiumMidiService(
    context: Context,
    val parser: StadiumMidiParser,
    private val onDevicesChanged: (List<String>) -> Unit = {},
) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())
    private val openDevices = mutableMapOf<Int, MidiDevice>()
    private val openPorts = mutableMapOf<Int, MidiOutputPort>()
    private val connectedNames = linkedMapOf<Int, String>()

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
            if (msg != null && count > 0) {
                parser.parseBytes(msg, offset, count)
            }
        }
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            connectDevice(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            disconnectDevice(device.id)
        }
    }

    fun start() {
        val mgr = midiManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            mgr.registerDeviceCallback(
                MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                { r -> handler.post(r) },
                deviceCallback,
            )
            mgr.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).forEach { connectDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            mgr.registerDeviceCallback(deviceCallback, handler)
            @Suppress("DEPRECATION")
            mgr.devices?.forEach { connectDevice(it) }
        }
    }

    fun stop() {
        val mgr = midiManager ?: return
        runCatching { mgr.unregisterDeviceCallback(deviceCallback) }
        openPorts.values.forEach { runCatching { it.close() } }
        openDevices.values.forEach { runCatching { it.close() } }
        openPorts.clear()
        openDevices.clear()
        connectedNames.clear()
        onDevicesChanged(emptyList())
    }

    private fun connectDevice(info: MidiDeviceInfo) {
        val mgr = midiManager ?: return
        if (info.outputPortCount <= 0) return
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Controller #${info.id}"
        mgr.openDevice(info, { device ->
            if (device == null) return@openDevice
            val port = device.openOutputPort(0)
            if (port != null) {
                port.connect(receiver)
                openDevices[info.id] = device
                openPorts[info.id] = port
                connectedNames[info.id] = name
                onDevicesChanged(connectedNames.values.toList())
            } else {
                runCatching { device.close() }
            }
        }, handler)
    }

    private fun disconnectDevice(deviceId: Int) {
        openPorts.remove(deviceId)?.let { runCatching { it.close() } }
        openDevices.remove(deviceId)?.let { runCatching { it.close() } }
        connectedNames.remove(deviceId)
        onDevicesChanged(connectedNames.values.toList())
    }
}
