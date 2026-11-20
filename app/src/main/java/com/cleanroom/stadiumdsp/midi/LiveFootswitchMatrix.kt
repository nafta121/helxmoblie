package com.cleanroom.stadiumdsp.midi

import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.preset.SnapshotData

enum class LivePlayMode(val label: String) {
    COMBO_4_PLUS_4("Combo 4+4"),
    SNAPSHOT_8("Snapshots 1–8"),
    STOMP_A("Stomp A"),
    STOMP_B("Stomp B"),
    PRESET_SETLIST("Preset / Setlist"),
    SHOWCASE("Showcase Cue"),
}

sealed interface FootswitchAction {
    data class RecallSnapshot(val snapshotIndex: Int) : FootswitchAction
    data class ToggleBlocks(val blockIds: List<String>) : FootswitchAction
    data class SelectPreset(val presetIndex: Int) : FootswitchAction
    data object TriggerTapTempo : FootswitchAction
    data object ToggleTuner : FootswitchAction
    data class TriggerLooper(val actionCode: Int) : FootswitchAction
}

data class FootswitchPad(
    val padId: String,
    val label: String,
    val subLabel: String,
    val ledColorHex: Long,
    val focusBlockId: String?,
    val action: FootswitchAction,
)

/**
 * Deterministic Stage Lock rules (Review Focus #2):
 * Blocks accidental slider/XY/reorder edits while keeping Snapshots, Stomps, EXP, Tuner, and MUTE ALL instant!
 */
object StageLockPolicy {
    fun allowParameterEdit(stageLocked: Boolean, fromLiveController: Boolean): Boolean =
        !stageLocked || fromLiveController

    fun allowBlockReorderOrDelete(stageLocked: Boolean): Boolean = !stageLocked
    fun allowLiveFootswitch(stageLocked: Boolean): Boolean = true
    fun allowMasterMute(stageLocked: Boolean): Boolean = true
    fun allowTunerAndTap(stageLocked: Boolean): Boolean = true
}

/**
 * GAP-19: 6-Mode Live Footswitch Matrix supporting Combo 4+4, Snapshot 1–8, Stomp A/B,
 * Preset/Setlist, Showcase, custom scribble strip labels, and MULTIPLE (X) multi-block toggles.
 */
class LiveFootswitchMatrix(
    private val snapshots: List<SnapshotData>,
) {
    private val stompAPads = mutableListOf(
        FootswitchPad("stomp_a0", "MINOTAUR", "Drive", BlockCategory.DISTORTION.colorHex, "drive", FootswitchAction.ToggleBlocks(listOf("drive"))),
        FootswitchPad("stomp_a1", "TRI-CHORUS", "Modulation", BlockCategory.MODULATION.colorHex, "mod", FootswitchAction.ToggleBlocks(listOf("mod"))),
        FootswitchPad("stomp_a2", "COSMOS ECHO", "Delay", BlockCategory.DELAY.colorHex, "delay", FootswitchAction.ToggleBlocks(listOf("delay"))),
        FootswitchPad("stomp_a3", "'63 SPRING", "Reverb", BlockCategory.REVERB.colorHex, "reverb", FootswitchAction.ToggleBlocks(listOf("reverb"))),
        FootswitchPad("stomp_a4", "HORIZON GATE", "Dynamics", BlockCategory.DYNAMICS.colorHex, "gate", FootswitchAction.ToggleBlocks(listOf("gate"))),
        FootswitchPad("stomp_a5", "LA-2A OPTO", "Dynamics", BlockCategory.DYNAMICS.colorHex, "comp", FootswitchAction.ToggleBlocks(listOf("comp"))),
        FootswitchPad("stomp_a6", "PARAM EQ", "EQ", BlockCategory.EQ.colorHex, "eq", FootswitchAction.ToggleBlocks(listOf("eq"))),
        FootswitchPad("stomp_a7", "POLY CAPO", "Pitch/Wah", BlockCategory.PITCH_WAH.colorHex, "pitch", FootswitchAction.ToggleBlocks(listOf("pitch"))),
    )

    private val stompBPads = mutableListOf(
        FootswitchPad("stomp_b0", "LOOPER REC", "Looper", 0xFFFF3C3CL, null, FootswitchAction.TriggerLooper(1)),
        FootswitchPad("stomp_b1", "LOOPER PLAY", "Looper", 0xFF43FF40L, null, FootswitchAction.TriggerLooper(2)),
        FootswitchPad("stomp_b2", "OVERDUB", "Looper", 0xFFFF9B2DL, null, FootswitchAction.TriggerLooper(3)),
        FootswitchPad("stomp_b3", "STOP / CLR", "Looper", 0xFF949494L, null, FootswitchAction.TriggerLooper(4)),
        FootswitchPad("stomp_b4", "UNDO LAYER", "Looper", 0xFF00A5FFL, null, FootswitchAction.TriggerLooper(5)),
        FootswitchPad("stomp_b5", "WAH PEDAL", "Pitch/Wah", BlockCategory.PITCH_WAH.colorHex, "wah", FootswitchAction.ToggleBlocks(listOf("wah"))),
        FootswitchPad("stomp_b6", "VOL PEDAL", "Vol/Pan", BlockCategory.VOL_PAN.colorHex, "vol_pan", FootswitchAction.ToggleBlocks(listOf("vol_pan"))),
        FootswitchPad("stomp_b7", "SOLO BOOST", "MULTIPLE (3)", 0xFFE8D62FL, "drive", FootswitchAction.ToggleBlocks(listOf("drive", "delay", "reverb"))),
    )

    fun assignMultipleBlocks(
        stompSlotIndex: Int,
        blockIds: List<String>,
        customLabel: String,
        ledColorHex: Long = 0xFFE8D62FL,
    ) {
        val idx = stompSlotIndex.coerceIn(0, stompAPads.lastIndex)
        val count = blockIds.size
        val sub = if (count > 1) "MULTIPLE ($count)" else "Stomp"
        stompAPads[idx] = FootswitchPad(
            padId = "stomp_a$idx",
            label = customLabel.ifBlank { "MULTI $count" },
            subLabel = sub,
            ledColorHex = ledColorHex,
            focusBlockId = blockIds.firstOrNull(),
            action = FootswitchAction.ToggleBlocks(blockIds),
        )
    }

    fun padsForMode(mode: LivePlayMode, activeSnapshots: List<SnapshotData> = snapshots): List<FootswitchPad> {
        val snaps = activeSnapshots.take(8)
        return when (mode) {
            LivePlayMode.COMBO_4_PLUS_4 -> {
                val top4 = snaps.take(4).map { s ->
                    FootswitchPad(
                        padId = "snap_${s.index}",
                        label = s.name,
                        subLabel = "SNAP ${s.index + 1}",
                        ledColorHex = s.ledColorHex,
                        focusBlockId = "amp",
                        action = FootswitchAction.RecallSnapshot(s.index),
                    )
                }
                top4 + stompAPads.take(4)
            }
            LivePlayMode.SNAPSHOT_8 -> snaps.map { s ->
                FootswitchPad(
                    padId = "snap_${s.index}",
                    label = s.name,
                    subLabel = "SNAP ${s.index + 1}",
                    ledColorHex = s.ledColorHex,
                    focusBlockId = "amp",
                    action = FootswitchAction.RecallSnapshot(s.index),
                )
            }
            LivePlayMode.STOMP_A -> stompAPads.toList()
            LivePlayMode.STOMP_B -> stompBPads.toList()
            LivePlayMode.PRESET_SETLIST -> (0 until 8).map { i ->
                FootswitchPad(
                    padId = "preset_$i",
                    label = "PRESET 0${i + 1}",
                    subLabel = "Setlist Slot",
                    ledColorHex = 0xFF38BDF8L,
                    focusBlockId = null,
                    action = FootswitchAction.SelectPreset(i),
                )
            }
            LivePlayMode.SHOWCASE -> snaps.take(4).map { s ->
                FootswitchPad(
                    padId = "cue_snap_${s.index}",
                    label = s.name,
                    subLabel = "AUTO CUE S${s.index + 1}",
                    ledColorHex = s.ledColorHex,
                    focusBlockId = "amp",
                    action = FootswitchAction.RecallSnapshot(s.index),
                )
            } + stompAPads.take(4)
        }
    }

    companion object {
        fun createDefault(): LiveFootswitchMatrix =
            LiveFootswitchMatrix(com.cleanroom.stadiumdsp.preset.StadiumPreset.default8Snapshots())
    }
}
