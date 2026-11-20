package com.cleanroom.stadiumdsp.midi

import kotlinx.serialization.Serializable

@Serializable
data class ControllerAssignment(
    val pedalIndex: Int, // 1 = EXP 1 (Violet), 2 = EXP 2 (Cyan)
    val targetBlockId: String,
    val targetParamKey: String,
    val minValue: Float = 0.0f,
    val maxValue: Float = 1.0f,
    val autoEngage: Boolean = false,
    val engageThreshold: Float = 0.03f,
    val disengageDelayMs: Long = 200L,
)

data class ControllerEvaluationResult(
    val assignment: ControllerAssignment,
    val mappedValue: Float,
    val blockEnabledChange: Boolean? = null, // non-null only when Auto-Engage toggles state
)

/**
 * GAP-20: Expression Pedal & MIDI Controller assignment engine supporting:
 * - Multiple targets per pedal (EXP 1 / EXP 2)
 * - Arbitrary `[minValue .. maxValue]` ranges including inverted sweeps (`minValue > maxValue`)
 * - Deterministic Auto-Engage (`engageThreshold = 3%`, `disengageDelayMs = 200 ms`)
 */
class ControllerAssignmentEngine {
    private val assignments = mutableListOf<ControllerAssignment>()
    private val engagedState = mutableMapOf<String, Boolean>()
    private val heelEntryTimestampMs = mutableMapOf<String, Long?>()

    fun allAssignments(): List<ControllerAssignment> = assignments.toList()

    fun assign(assignment: ControllerAssignment) {
        assignments.removeAll {
            it.pedalIndex == assignment.pedalIndex && it.targetParamKey == assignment.targetParamKey
        }
        assignments.add(assignment)
    }

    fun remove(pedalIndex: Int, targetParamKey: String) {
        assignments.removeAll { it.pedalIndex == pedalIndex && it.targetParamKey == targetParamKey }
    }

    fun evaluatePedal(
        pedalIndex: Int,
        normalizedPos: Float,
        nowMs: Long,
    ): List<ControllerEvaluationResult> {
        val pos = normalizedPos.coerceIn(0f, 1f)
        return assignments
            .filter { it.pedalIndex == pedalIndex }
            .map { a ->
                val mapped = a.minValue + (a.maxValue - a.minValue) * pos
                var bypassDelta: Boolean? = null

                if (a.autoEngage) {
                    val stateKey = "${a.pedalIndex}:${a.targetBlockId}"
                    val currentlyEngaged = engagedState[stateKey] ?: false
                    if (pos >= a.engageThreshold) {
                        heelEntryTimestampMs[stateKey] = null
                        if (!currentlyEngaged) {
                            engagedState[stateKey] = true
                            bypassDelta = true
                        }
                    } else if (currentlyEngaged) {
                        val entered = heelEntryTimestampMs[stateKey]
                        if (entered == null) {
                            heelEntryTimestampMs[stateKey] = nowMs
                        } else if (nowMs - entered >= a.disengageDelayMs) {
                            engagedState[stateKey] = false
                            heelEntryTimestampMs[stateKey] = null
                            bypassDelta = false
                        }
                    }
                }

                ControllerEvaluationResult(
                    assignment = a,
                    mappedValue = mapped,
                    blockEnabledChange = bypassDelta,
                )
            }
    }

    companion object {
        fun createDefault(): ControllerAssignmentEngine = ControllerAssignmentEngine().apply {
            assign(
                ControllerAssignment(
                    pedalIndex = 1,
                    targetBlockId = "wah",
                    targetParamKey = "wah.position",
                    minValue = 0f,
                    maxValue = 1f,
                    autoEngage = true,
                    engageThreshold = 0.03f,
                    disengageDelayMs = 200L,
                )
            )
            assign(
                ControllerAssignment(
                    pedalIndex = 2,
                    targetBlockId = "vol_pan",
                    targetParamKey = "vol.position",
                    minValue = 0f,
                    maxValue = 1f,
                    autoEngage = false,
                )
            )
        }
    }
}
