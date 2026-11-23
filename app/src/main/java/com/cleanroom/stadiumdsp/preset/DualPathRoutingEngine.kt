package com.cleanroom.stadiumdsp.preset

import com.cleanroom.stadiumdsp.RigBlockCode

/**
 * Pure JVM-testable Multi-Instance Dual-Path Routing Engine (Path 1A/1B -> Path 2A/2B).
 * Manages up to 2 simultaneous DSP pool instances per effect family and up to 8 blocks per branch.
 */
object DualPathRoutingEngine {
    const val MAX_SLOTS_PER_BRANCH = 8
    val ALL_BRANCHES: List<String> = listOf("1A", "1B", "2A", "2B")

    data class MutationResult(
        val flow: SignalFlowState,
        val activeBlockId: String?,
        val error: String? = null,
    )

    fun blocksOnBranch(flow: SignalFlowState, branch: String): List<RigBlockState> =
        flow.blocks.filter { it.pathBranch == branch }

    /**
     * Pool family key matching the pre-allocated C++20 GuitarRigGraph pools (max 2 each, Pitch max 1).
     */
    fun poolFamilyKey(nativeBlockCode: Int): String = when (nativeBlockCode) {
        RigBlockCode.GATE -> "gate"
        RigBlockCode.PITCH -> "pitch"
        RigBlockCode.COMP -> "comp"
        RigBlockCode.WAH -> "wah"
        RigBlockCode.DRIVE -> "drive"
        RigBlockCode.MOD_PRE, RigBlockCode.MOD_POST -> "mod"
        RigBlockCode.AMP -> "amp"
        RigBlockCode.CAB -> "cab"
        RigBlockCode.EQ -> "eq"
        RigBlockCode.VOL_PAN -> "vol_pan"
        RigBlockCode.DELAY -> "delay"
        RigBlockCode.REVERB -> "reverb"
        else -> "fx_$nativeBlockCode"
    }

    fun maxInstancesFor(nativeBlockCode: Int): Int = when (nativeBlockCode) {
        RigBlockCode.PITCH -> 1
        else -> 2
    }

    fun allocateFreeInstanceIndex(flow: SignalFlowState, nativeBlockCode: Int, excludeBlockId: String? = null): Int? {
        val fam = poolFamilyKey(nativeBlockCode)
        val used = flow.blocks
            .filter { it.id != excludeBlockId && poolFamilyKey(it.nativeBlockCode) == fam }
            .map { it.instanceIndex }
            .toSet()
        val limit = maxInstancesFor(nativeBlockCode)
        for (idx in 0 until limit) {
            if (idx !in used) return idx
        }
        return null
    }

    fun canInsert(flow: SignalFlowState, nativeBlockCode: Int, targetBranch: String): Boolean {
        if (targetBranch !in ALL_BRANCHES) return false
        if (blocksOnBranch(flow, targetBranch).size >= MAX_SLOTS_PER_BRANCH) return false
        return allocateFreeInstanceIndex(flow, nativeBlockCode) != null
    }

    fun defaultParamsFor(nativeBlockCode: Int, isPreamp: Boolean = false): Map<String, Float> = when (nativeBlockCode) {
        RigBlockCode.GATE -> mapOf("gate.threshold_db" to -52f, "gate.decay_ms" to 45f)
        RigBlockCode.PITCH -> mapOf("pitch.semitones" to 0f, "pitch.pedal_pos" to 0f, "pitch.mix" to 0.5f)
        RigBlockCode.COMP -> mapOf("comp.peak_reduction" to 0.45f, "comp.gain_db" to 4f, "comp.mix" to 0.85f)
        RigBlockCode.WAH -> mapOf("wah.position" to 0.50f, "wah.q_resonance" to 0.55f, "wah.mix" to 1.0f)
        RigBlockCode.DRIVE -> mapOf("drive.drive" to 0.45f, "drive.tone" to 0.55f, "drive.level" to 0.68f, "drive.bass_cut" to 0.25f)
        RigBlockCode.MOD_PRE, RigBlockCode.MOD_POST -> mapOf("mod.rate_hz" to 1.2f, "mod.depth" to 0.55f, "mod.mix" to 0.45f, "mod.feedback" to 0.30f)
        RigBlockCode.AMP -> mapOf(
            "amp.drive" to if (isPreamp) 0.35f else 0.55f,
            "amp.bass" to 0.50f, "amp.mid" to 0.55f, "amp.treble" to 0.60f,
            "amp.presence" to 0.50f,
            "amp.master" to if (isPreamp) 0.22f else 0.65f,
            "amp.ch_vol" to 0.72f,
            "amp.sag" to if (isPreamp) 0.0f else 0.45f,
            "amp.ripple" to if (isPreamp) 0.0f else 0.25f,
            "amp.bias" to 0.50f, "amp.cut" to 0.10f, "amp.hype" to 0.0f,
        )
        RigBlockCode.CAB -> mapOf(
            "cab.position" to 0.25f, "cab.distance_inch" to 2.0f, "cab.angle_deg" to 0f,
            "cab.low_cut_hz" to 65f, "cab.high_cut_hz" to 9200f, "cab.level_db" to 0f, "cab.pan" to 0f, "cab.mic_model" to 0f,
        )
        RigBlockCode.EQ -> mapOf(
            "eq.low_freq" to 120f, "eq.low_gain_db" to 0f, "eq.low_q" to 0.8f,
            "eq.mid1_freq" to 650f, "eq.mid1_gain_db" to 0f, "eq.mid1_q" to 1.2f,
            "eq.mid2_freq" to 2400f, "eq.mid2_gain_db" to 0f, "eq.mid2_q" to 1.2f,
            "eq.high_freq" to 6500f, "eq.high_gain_db" to 0f, "eq.high_q" to 0.8f,
            "eq.tilt_db" to 0f,
        )
        RigBlockCode.VOL_PAN -> mapOf("vol.position" to 1.0f, "vol.pan" to 0f, "vol.stereo_width" to 1.0f)
        RigBlockCode.DELAY -> mapOf("delay.time_ms" to 375f, "delay.feedback" to 0.42f, "delay.mix" to 0.34f)
        RigBlockCode.REVERB -> mapOf("reverb.decay" to 0.60f, "reverb.damping_hz" to 4800f, "reverb.mix" to 0.32f, "reverb.predelay_ms" to 22f, "reverb.shimmer_amount" to 0.25f)
        else -> emptyMap()
    }

    fun generateBlockId(flow: SignalFlowState, nativeBlockCode: Int, instanceIndex: Int): String {
        val base = poolFamilyKey(nativeBlockCode)
        val preferred = if (instanceIndex == 0) base else "${base}_2"
        if (flow.blocks.none { it.id == preferred }) return preferred
        var n = 2
        while (flow.blocks.any { it.id == "${base}_$n" }) n++
        return "${base}_$n"
    }

    /**
     * Inserts a new block at an exact branch ("1A"/"1B"/"2A"/"2B") and index within that branch.
     */
    fun insertBlockAt(
        flow: SignalFlowState,
        category: BlockCategory,
        nativeBlockCode: Int,
        modelId: Int,
        displayName: String,
        shortLabel: String,
        targetBranch: String,
        targetIndexInBranch: Int,
        isPreamp: Boolean = false,
    ): MutationResult {
        if (targetBranch !in ALL_BRANCHES) {
            return MutationResult(flow, null, "Invalid path branch $targetBranch")
        }
        val branchBlocks = blocksOnBranch(flow, targetBranch).toMutableList()
        if (branchBlocks.size >= MAX_SLOTS_PER_BRANCH) {
            return MutationResult(flow, null, "Path $targetBranch full (max $MAX_SLOTS_PER_BRANCH blocks)")
        }
        val inst = allocateFreeInstanceIndex(flow, nativeBlockCode)
            ?: return MutationResult(flow, null, "Max ${maxInstancesFor(nativeBlockCode)} ${category.displayName} blocks active")

        val resolvedNativeCode = if (nativeBlockCode == RigBlockCode.MOD_PRE && inst == 1) {
            RigBlockCode.MOD_POST
        } else {
            nativeBlockCode
        }
        val newBlock = RigBlockState(
            id = generateBlockId(flow, resolvedNativeCode, inst),
            nativeBlockCode = resolvedNativeCode,
            category = category,
            modelId = modelId,
            displayName = displayName,
            shortLabel = shortLabel.take(9),
            enabled = true,
            isStereo = category in setOf(BlockCategory.CAB, BlockCategory.EQ, BlockCategory.MODULATION, BlockCategory.DELAY, BlockCategory.REVERB, BlockCategory.VOL_PAN),
            pathBranch = targetBranch,
            instanceIndex = inst,
            params = defaultParamsFor(resolvedNativeCode, isPreamp = isPreamp),
        )
        val clampedIdx = targetIndexInBranch.coerceIn(0, branchBlocks.size)
        branchBlocks.add(clampedIdx, newBlock)

        val rebuilt = rebuildBlocksList(flow, targetBranch, branchBlocks)
        return MutationResult(rebuilt, newBlock.id)
    }

    /**
     * Replaces an existing block's model (and optionally category) in place, preserving its branch & position.
     */
    fun replaceBlockAt(
        flow: SignalFlowState,
        blockId: String,
        category: BlockCategory,
        nativeBlockCode: Int,
        modelId: Int,
        displayName: String,
        shortLabel: String,
        isPreamp: Boolean = false,
    ): MutationResult {
        val existing = flow.blocks.firstOrNull { it.id == blockId }
            ?: return MutationResult(flow, null, "Block $blockId not found")
        val sameFamily = poolFamilyKey(existing.nativeBlockCode) == poolFamilyKey(nativeBlockCode)
        val inst = if (sameFamily) {
            existing.instanceIndex
        } else {
            allocateFreeInstanceIndex(flow, nativeBlockCode, excludeBlockId = blockId)
                ?: return MutationResult(flow, blockId, "Max ${maxInstancesFor(nativeBlockCode)} ${category.displayName} blocks active")
        }
        val resolvedNativeCode = if (nativeBlockCode == RigBlockCode.MOD_PRE && inst == 1) {
            RigBlockCode.MOD_POST
        } else {
            nativeBlockCode
        }
        val updated = flow.blocks.map { blk ->
            if (blk.id == blockId) {
                blk.copy(
                    nativeBlockCode = resolvedNativeCode,
                    category = category,
                    modelId = modelId,
                    displayName = displayName,
                    shortLabel = shortLabel.take(9),
                    instanceIndex = inst,
                    enabled = true,
                    params = if (sameFamily && !isPreamp) blk.params else defaultParamsFor(resolvedNativeCode, isPreamp = isPreamp),
                )
            } else blk
        }
        return MutationResult(flow.copy(blocks = updated), blockId)
    }

    /**
     * Moves any existing block to a new branch ("1A"/"1B"/"2A"/"2B") and slot index (Flexible Drag & Drop!).
     */
    fun moveBlockTo(
        flow: SignalFlowState,
        blockId: String,
        targetBranch: String,
        targetIndexInBranch: Int,
    ): MutationResult {
        if (targetBranch !in ALL_BRANCHES) return MutationResult(flow, blockId, "Invalid branch")
        val moving = flow.blocks.firstOrNull { it.id == blockId }
            ?: return MutationResult(flow, null, "Block not found")

        val isCrossBranch = moving.pathBranch != targetBranch
        val destList = blocksOnBranch(flow, targetBranch).filter { it.id != blockId }.toMutableList()
        if (isCrossBranch && destList.size >= MAX_SLOTS_PER_BRANCH) {
            return MutationResult(flow, blockId, "Path $targetBranch full (max $MAX_SLOTS_PER_BRANCH blocks)")
        }

        val clampedIdx = targetIndexInBranch.coerceIn(0, destList.size)
        destList.add(clampedIdx, moving.copy(pathBranch = targetBranch))

        val rebuilt = rebuildBlocksListCrossBranch(flow, blockId, targetBranch, destList)
        return MutationResult(rebuilt, blockId)
    }

    /**
     * Removes a block from the signal flow (`× None` or Drag-to-Remove), returning a valid fallback selectedBlockId.
     */
    fun removeBlock(flow: SignalFlowState, blockId: String): MutationResult {
        val idx = flow.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return MutationResult(flow, flow.blocks.firstOrNull()?.id)
        val remaining = flow.blocks.filterNot { it.id == blockId }
        val nextSelected = remaining.getOrNull(idx.coerceAtMost(remaining.lastIndex))?.id
        return MutationResult(flow.copy(blocks = remaining), nextSelected)
    }

    private fun rebuildBlocksList(
        flow: SignalFlowState,
        updatedBranch: String,
        updatedBranchBlocks: List<RigBlockState>,
    ): SignalFlowState {
        val merged = ALL_BRANCHES.flatMap { br ->
            if (br == updatedBranch) updatedBranchBlocks else blocksOnBranch(flow, br)
        }
        return normalizeSplitIndices(flow.copy(blocks = merged))
    }

    private fun rebuildBlocksListCrossBranch(
        flow: SignalFlowState,
        movedBlockId: String,
        targetBranch: String,
        targetBranchBlocks: List<RigBlockState>,
    ): SignalFlowState {
        val merged = ALL_BRANCHES.flatMap { br ->
            if (br == targetBranch) {
                targetBranchBlocks
            } else {
                blocksOnBranch(flow, br).filterNot { it.id == movedBlockId }
            }
        }
        return normalizeSplitIndices(flow.copy(blocks = merged))
    }

    private fun normalizeSplitIndices(flow: SignalFlowState): SignalFlowState {
        val c1a = blocksOnBranch(flow, "1A").size
        val c2a = blocksOnBranch(flow, "2A").size
        val s1 = flow.split1AfterIndex.coerceIn(0, c1a)
        val s2 = flow.split2AfterIndex.coerceIn(0, c2a)
        val m2 = flow.merge2AtIndex.coerceIn(s2, c2a)
        return flow.copy(
            split1AfterIndex = s1,
            split2AfterIndex = s2,
            merge2AtIndex = m2,
        )
    }
}
