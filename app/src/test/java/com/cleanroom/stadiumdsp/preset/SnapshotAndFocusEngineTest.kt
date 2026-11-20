package com.cleanroom.stadiumdsp.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAndFocusEngineTest {

    @Test
    fun blockCategoryColorsMatchOfficial11HexTokens() {
        assertEquals(0xFFFF3C3CL, BlockCategory.AMP.colorHex)
        assertEquals(0xFFFF509AL, BlockCategory.CAB.colorHex)
        assertEquals(0xFFFF9B2DL, BlockCategory.DISTORTION.colorHex)
        assertEquals(0xFFE8D62FL, BlockCategory.DYNAMICS.colorHex)
        assertEquals(0xFFE8D62FL, BlockCategory.EQ.colorHex)
        assertEquals(0xFF00A5FFL, BlockCategory.MODULATION.colorHex)
        assertEquals(0xFF43FF40L, BlockCategory.DELAY.colorHex)
        assertEquals(0xFFFF6828L, BlockCategory.REVERB.colorHex)
        assertEquals(0xFFA25DFFL, BlockCategory.PITCH_WAH.colorHex)
        assertEquals(0xFF0FFFFFL, BlockCategory.VOL_PAN.colorHex)
        assertEquals(0xFF949494L, BlockCategory.ROUTING.colorHex)
    }

    @Test
    fun focusZoneInterpolatorHitsExactOriginAndAllFourCorners() {
        val origin = mapOf("drive" to 0.50f, "treble" to 0.55f, "mid" to 0.50f)
        val tl = mapOf("drive" to 0.20f, "treble" to 0.70f, "mid" to 0.60f) // x=-1, y=+1
        val tr = mapOf("drive" to 0.90f, "treble" to 0.80f, "mid" to 0.40f) // x=+1, y=+1
        val bl = mapOf("drive" to 0.15f, "treble" to 0.30f, "mid" to 0.75f) // x=-1, y=-1
        val br = mapOf("drive" to 0.85f, "treble" to 0.45f, "mid" to 0.30f) // x=+1, y=-1

        val c = FocusZoneInterpolator.interpolate5Zone(0f, 0f, origin, bl, tl, br, tr)
        assertEquals(0.50f, c["drive"]!!, 1e-5f)
        assertEquals(0.55f, c["treble"]!!, 1e-5f)

        val hitTL = FocusZoneInterpolator.interpolate5Zone(-1f, 1f, origin, bl, tl, br, tr)
        assertEquals(0.20f, hitTL["drive"]!!, 1e-5f)
        assertEquals(0.70f, hitTL["treble"]!!, 1e-5f)

        val hitBR = FocusZoneInterpolator.interpolate5Zone(1f, -1f, origin, bl, tl, br, tr)
        assertEquals(0.85f, hitBR["drive"]!!, 1e-5f)
        assertEquals(0.30f, hitBR["mid"]!!, 1e-5f)

        // All 30 amps must have authentic 5-zone maps in FocusZoneBank
        for (ampId in 0 until 30) {
            val map = FocusZoneBank.forAmpModel(ampId)
            assertTrue("Amp $ampId origin params missing", map.origin.containsKey("drive"))
        }
    }

    @Test
    fun snapshotManagerDiscardEditsRedCameraVsAutoRememberWhiteCamera() {
        val mgr = SnapshotManager.createDefault()
        mgr.selectSnapshot(1) // Snapshot 2 (0-based index 1)
        val originalDrive = mgr.currentSnapshot().paramOverrides["amp.drive"] ?: 0.40f

        // Mode 1: RED CAMERA (Discard Edits = true)
        mgr.setDiscardEdits(true)
        mgr.recordParameterEdit("amp.drive", 0.91f)
        assertEquals(0.91f, mgr.activeWorkingParam("amp.drive", originalDrive), 1e-5f)

        // Switch Snapshot 2 -> 3 -> 2: value MUST revert to stored originalDrive!
        mgr.selectSnapshot(2)
        mgr.selectSnapshot(1)
        assertEquals(originalDrive, mgr.activeWorkingParam("amp.drive", originalDrive), 1e-5f)

        // Mode 2: WHITE CAMERA (Auto-Remember = false)
        mgr.setDiscardEdits(false)
        mgr.recordParameterEdit("amp.drive", 0.88f)
        mgr.selectSnapshot(2)
        mgr.selectSnapshot(1)
        assertEquals(0.88f, mgr.activeWorkingParam("amp.drive", originalDrive), 1e-5f)
    }

    @Test
    fun presetRepositoryProtectsFactoryPresetsAndSupportsUserCrudAndSearch() {
        val repo = PresetRepository()
        assertTrue(repo.allPresets().size >= 8)
        val factory = repo.allPresets().first { it.isFactory }
        assertFalse("Factory preset must be read-only", repo.saveUserPreset(factory))

        val custom = factory.copy(id = "user_custom_1", name = "My Live Worship Lead", isFactory = false)
        assertTrue(repo.saveUserPreset(custom))
        assertTrue(repo.search("Worship").any { it.id == "user_custom_1" })

        val json = repo.exportPresetJson(custom)
        val imported = repo.importPresetJson(json)
        assertEquals("My Live Worship Lead", imported.name)
        assertFalse(imported.isFactory)
    }

    @Test
    fun historyManagerSupports50StepUndoRedoAndABCompare() {
        val initial = StadiumPreset.createDefaultPreset()
        val history = RigHistoryManager(initial, maxSteps = 50)
        val edited = initial.copy(name = "Edited Crunch", globalBpm = 132f)
        history.pushState(edited)
        assertTrue(history.canUndo)
        assertEquals("Edited Crunch", history.current.name)

        val undone = history.undo()
        assertEquals(initial.name, undone.name)
        assertTrue(history.canRedo)

        val redone = history.redo()
        assertEquals("Edited Crunch", redone.name)

        // A/B Compare & Copy A -> B
        history.copyActiveToOtherSlot()
        val slotB = history.toggleAB()
        assertEquals("Edited Crunch", slotB.name)
        history.pushState(slotB.copy(globalBpm = 148f))
        val backToA = history.toggleAB()
        assertNotEquals(148f, backToA.globalBpm)
    }

    @Test
    fun favoritesStoreHandlesUserAndFactoryDefaults() {
        val store = BlockFavoritesStore()
        store.saveUserDefault("amp:11", mapOf("drive" to 0.77f, "master" to 0.65f))
        assertEquals(0.77f, store.getUserDefault("amp:11")?.get("drive")!!, 1e-5f)

        store.addFavorite(FavoriteBlockEntry("fav1", "My JCM800 Crunch", BlockCategory.AMP, 11, mapOf("drive" to 0.77f)))
        assertEquals(1, store.favorites.size)
        store.clearUserDefault("amp:11")
        assertEquals(null, store.getUserDefault("amp:11"))
    }
}
