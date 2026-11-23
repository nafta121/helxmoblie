# Helix Stadium Native 1:1 UI, Auto-Close Browser, Flexible Drag & Drop, & Dual-Path Multi-Instance DSP — Implementation Plan

> **For agentic workers:** Implementing sequentially inline with strict TDD, Gate 1–4 verification, and commit per milestone.

**Goal:** Build 100% visual parity with Helix Stadium Native (`jpeg.jpeg`, clean-room procedural Canvas/Vector art), auto-closing 2-column Model Browser on empty/non-block tap, cross-panel long-press Drag & Drop (move/reorder across `1A/1B/2A/2B`, add/replace from browser, remove via `× None`), and C++20 multi-instance dual-path DSP (`2×` per FX family + `Space Cadet` default preset).

**Architecture:** Extend `GuitarRigGraph` with pre-allocated dual-instance pools (`std::array<T, 2>`) and `DualPathRoutingPlan` (zero RT heap allocation, 100% backwards-compatible with Tests 1–18), expose `nativeSetDualPathRouting` over JNI, build pure JVM-testable `DualPathRoutingEngine`, coordinate cross-panel drag coordinates via `StudioDragDropController`, and restyle `StadiumTopInstanceBar`, `SignalFlowCanvas`, and `ModelBrowserAndInspector` with clean-room `ProceduralHardwareArt`.

**Tech Stack:** C++20 / ARM64 NEON, Google Oboe 1.9.3, JNI, Kotlin, Jetpack Compose Material 3 Adaptive, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-22-helix-stadium-native-ui-dual-path-dsp-design.md`

## Global Constraints
- 100% clean-room procedural UI/DSP (0 third-party bitmaps/icons/tables).
- Zero heap allocation, zero mutex, zero JNI in `onAudioReady` / `ProcessSubBlock16`; P99 RT load `< 50%`.
- Preserve 5-posture adaptivity, 0-ms rotation continuity, LIVE Play `PointerEventPass.Initial`, Stage Lock, and Opsi C Buffer Size & Device Picker.
- Long-press drag trigger (`detectDragGesturesAfterLongPress`) so normal scrolling & tapping never conflict.

## Review Focus
- Backward compatibility of instance-0 IDs (`"amp"`, `"cab"`, `"drive"`, `"delay"`, `"reverb"`) with existing presets/snapshots/tests.
- Max-2 instance pool guard returns graceful status message without crashing or overwriting active blocks.
- Removing the selected block (`× None`) updates `selectedBlockId` safely even if all blocks are cleared.
- Auto-close triggers on empty Signal Flow / non-block tap without blocking slider drags or button clicks.
- Cross-panel drag coordinates remain accurate inside horizontally/vertically scrolled panes via root-window `Rect` hit-testing.

---

### Task 1: C++20 Multi-Instance Pool & 4-Branch `DualPathRoutingPlan` (`dsp_core` + Tests 19–20)
- [ ] Modify `dsp_core/include/stadium_dsp/signal_chain_processor.hpp` & `dsp_core/src/signal_chain_processor.cpp`
- [ ] Add `Test 19` (Dual-Instance Stacking) & `Test 20` (4-Branch `Space Cadet` Dual-Path Routing + RT CPU budget) to `tests/test_dsp_suite.cpp`
- [ ] Verify Gate 1 (`20/20` C++20), `stadium_latency_bench`, Gate 2 (`8/8` Python MNA) & commit.

### Task 2: JNI Dual-Path Bridge, `DualPathRoutingEngine`, `Space Cadet` Preset, & ViewModel Drag/Auto-Close State
- [ ] Modify `NativeDspBridge.cpp`, `NativeDspBridge.kt`, `StadiumPresetModels.kt`, `PresetRepository.kt`, `StadiumRigViewModel.kt`
- [ ] Create `DualPathRoutingEngine.kt` & `DualPathAndDragDropTest.kt`
- [ ] Verify `./gradlew testDebugUnitTest` passes 100% & commit.

### Task 3: Clean-Room `ProceduralHardwareArt`, `StudioDragDropController`, Top Bar 1:1, & Dual-Path Signal Flow Canvas
- [ ] Create `ProceduralHardwareArt.kt` & `StudioDragDropController.kt`
- [ ] Overhaul `StadiumTopInstanceBar.kt` & `SignalFlowCanvas.kt` (exact `jpeg.jpeg` parity + dB meters + curved parallel wires + auto-close empty canvas tap + drag/drop)
- [ ] Verify compile + unit tests & commit.

### Task 4: 2-Column Collapsible Model Browser (Auto-Close + Drag-to-Add) & Category-Tinted Slider Inspector + 5-Posture Integration
- [ ] Overhaul `ModelBrowserAndInspector.kt`, `StadiumRigScreen.kt`, `StadiumFormFactorPreviews.kt`
- [ ] Verify `./gradlew testDebugUnitTest assembleDebug` (0 warnings, all JUnit PASS) & commit.

### Task 5: Full Gate 1–4 Verification (Live Android 12 Emulator) & Release `v0.3.0` (Google Drive + GitHub Release)
- [ ] Run Gate 1 (`20/20`), `stadium_latency_bench`, Gate 2 (`8/8`), Gate 3 (JUnit + Multi-ABI APK), Gate 4 (`run_gate4_emulator_test.sh`: `0 Crashes`, `0 ANR`, `Oboe Starts == 1`)
- [ ] Upload `Hx-Mobile-StadiumDSP-v0.3.0-debug.apk` to Google Drive (`Hx Mobile`), publish GitHub Release `v0.3.0`, bookmark `.url.json` artifacts.
