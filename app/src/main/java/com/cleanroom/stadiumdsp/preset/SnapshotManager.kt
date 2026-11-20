package com.cleanroom.stadiumdsp.preset

enum class SnapshotReselectBehavior {
    RELOAD_CURRENT,
    TOGGLE_PREVIOUS,
}

/**
 * GAP-14: 8-Snapshot Engine managing:
 * - White Camera (`discardEdits = false`): edits auto-remembered in the active Snapshot slot
 * - Red Camera (`discardEdits = true`): stage-safe mode reverting unsaved tweaks on snapshot re-entry
 * - Snapshot Reselect (`Reload` vs `Toggle Previous`)
 */
class SnapshotManager(
    initialSnapshots: List<SnapshotData>,
    initialActiveIndex: Int = 0,
    initialDiscardEdits: Boolean = false,
) {
    private val storedSnapshots: MutableList<SnapshotData> = initialSnapshots
        .take(8)
        .toMutableList()
        .also { list ->
            while (list.size < 8) {
                list.add(SnapshotData(index = list.size, name = "SNAP ${list.size + 1}"))
            }
        }

    var activeIndex: Int = initialActiveIndex.coerceIn(0, 7)
        private set
    var previousIndex: Int = activeIndex
        private set
    var discardEdits: Boolean = initialDiscardEdits
        private set
    var reselectBehavior: SnapshotReselectBehavior = SnapshotReselectBehavior.RELOAD_CURRENT

    private val workingParams = mutableMapOf<String, Float>()
    private val workingBypasses = mutableMapOf<String, Boolean>()

    init {
        loadWorkingFromStored(activeIndex)
    }

    fun allSnapshots(): List<SnapshotData> = storedSnapshots.toList()
    fun currentSnapshot(): SnapshotData = storedSnapshots[activeIndex]

    fun setDiscardEdits(discard: Boolean) {
        discardEdits = discard
    }

    fun selectSnapshot(index: Int): SnapshotData {
        val target = index.coerceIn(0, 7)
        val resolvedTarget = if (target == activeIndex && reselectBehavior == SnapshotReselectBehavior.TOGGLE_PREVIOUS) {
            previousIndex
        } else {
            target
        }

        if (!discardEdits) {
            commitWorkingToSlot(activeIndex)
        }

        if (resolvedTarget != activeIndex) {
            previousIndex = activeIndex
        }
        activeIndex = resolvedTarget
        loadWorkingFromStored(activeIndex)
        return storedSnapshots[activeIndex]
    }

    fun recordParameterEdit(paramKey: String, value: Float) {
        workingParams[paramKey] = value
        if (!discardEdits) {
            commitWorkingToSlot(activeIndex)
        }
    }

    fun recordBypassEdit(blockId: String, enabled: Boolean) {
        workingBypasses[blockId] = enabled
        if (!discardEdits) {
            commitWorkingToSlot(activeIndex)
        }
    }

    fun activeWorkingParam(paramKey: String, fallback: Float): Float =
        workingParams[paramKey] ?: fallback

    fun activeWorkingBypass(blockId: String, fallback: Boolean): Boolean =
        workingBypasses[blockId] ?: fallback

    fun renameSnapshot(index: Int, newName: String) {
        val i = index.coerceIn(0, 7)
        storedSnapshots[i] = storedSnapshots[i].copy(name = newName.trim().ifEmpty { "SNAP ${i + 1}" })
    }

    fun saveWorkingExplicitly() {
        commitWorkingToSlot(activeIndex)
    }

    fun replaceAll(snapshots: List<SnapshotData>, active: Int = 0) {
        for (i in 0 until 8) {
            storedSnapshots[i] = snapshots.getOrElse(i) { SnapshotData(i, "SNAP ${i + 1}") }
        }
        activeIndex = active.coerceIn(0, 7)
        previousIndex = activeIndex
        loadWorkingFromStored(activeIndex)
    }

    private fun commitWorkingToSlot(slot: Int) {
        val cur = storedSnapshots[slot]
        storedSnapshots[slot] = cur.copy(
            paramOverrides = cur.paramOverrides + workingParams,
            blockEnabled = cur.blockEnabled + workingBypasses,
        )
    }

    private fun loadWorkingFromStored(slot: Int) {
        workingParams.clear()
        workingBypasses.clear()
        val snap = storedSnapshots[slot]
        workingParams.putAll(snap.paramOverrides)
        workingBypasses.putAll(snap.blockEnabled)
    }

    companion object {
        fun createDefault(): SnapshotManager =
            SnapshotManager(StadiumPreset.default8Snapshots(), initialActiveIndex = 0)
    }
}
