package com.cleanroom.stadiumdsp.preset

/**
 * GAP-17: Immutable 50-step Undo/Redo history stack + instant A/B Compare & Copy A<->B slots.
 */
class RigHistoryManager(
    initialState: StadiumPreset,
    private val maxSteps: Int = 50,
) {
    private val undoStack = ArrayDeque<StadiumPreset>()
    private val redoStack = ArrayDeque<StadiumPreset>()

    var current: StadiumPreset = initialState
        private set

    var isSlotB: Boolean = false
        private set
    private var slotA: StadiumPreset = initialState
    private var slotB: StadiumPreset = initialState

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun pushState(next: StadiumPreset) {
        if (next == current) return
        undoStack.addLast(current)
        while (undoStack.size > maxSteps) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        current = next
        syncActiveCompareSlot()
    }

    fun undo(): StadiumPreset {
        if (undoStack.isEmpty()) return current
        redoStack.addLast(current)
        current = undoStack.removeLast()
        syncActiveCompareSlot()
        return current
    }

    fun redo(): StadiumPreset {
        if (redoStack.isEmpty()) return current
        undoStack.addLast(current)
        current = redoStack.removeLast()
        syncActiveCompareSlot()
        return current
    }

    fun toggleAB(): StadiumPreset {
        syncActiveCompareSlot()
        isSlotB = !isSlotB
        current = if (isSlotB) slotB else slotA
        return current
    }

    fun copyActiveToOtherSlot() {
        syncActiveCompareSlot()
        if (isSlotB) {
            slotA = current
        } else {
            slotB = current
        }
    }

    fun reset(newPreset: StadiumPreset) {
        undoStack.clear()
        redoStack.clear()
        isSlotB = false
        current = newPreset
        slotA = newPreset
        slotB = newPreset
    }

    private fun syncActiveCompareSlot() {
        if (isSlotB) slotB = current else slotA = current
    }
}
