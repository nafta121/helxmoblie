# Desain Arsitektur — UI 100% Identik Helix Stadium Native, Auto-Close Browser, Flexible Drag & Drop, & Dual-Path Multi-Instance DSP

**Tanggal:** 22 November 2026  
**Proyek:** Hx Mobile (`Stadium DSP` / `com.cleanroom.stadiumdsp`)  
**Repositori:** `nafta121/helxmoblie`  
**Referensi Visual:** `file:///home/antigravity/jpeg.jpeg` (*Space Cadet* dual-path layout)

---

## 1. Tujuan & Alasan (*Intent & Why*)
Menghadirkan pengalaman penyuntingan *rig* gitar/bass di Android yang **100% identik secara tata letak, hierarki visual, proporsi, warna, dan ergonomi interaksi dengan *Helix Stadium Native*** — tetap **100% clean-room** (seluruh ikon, siluet ampli/kabinet/pedal, dan meter digambar prosedural lewat Jetpack Compose `Canvas` / `ImageVector`, 0 byte aset pihak ketiga) — sekaligus membuka kemampuan **Dual-Path (`Path 1A/1B` → `Path 2A/2B`) Multi-Instance DSP** sehingga pengguna dapat men-drag, memindahkan, menghapus (`None`), dan menumpuk efek sejenis (mis. `2× Delay`, `2× Reverb`, `2× Drive`, `2× EQ`, `2× Vol/Pan`, `2× Mod`) secara bebas persis seperti *preset* `Space Cadet` pada referensi.

### Goals
1. **Paritas Visual 1:1 Gaya Helix Stadium Native:**
   - **Top Instance Bar (`#141414`):** Knob rotary *Input* kiri (`+0.0 dB` trim), tombol `A → B` Compare + `Undo/Redo`, bar tengah `••• | ⇕ Preset Name` + Kamera Snapshot (`[1]..[8]`) + Clip/DI Audition + Info `(i)`, serta `Tap Clock | BPM` + knob rotary *Output* kanan (plus akses cepat Audio/Buffer Settings & LIVE Mode).
   - **Dual-Path Signal Flow Canvas (`#000000`):** Meter dB presisi vertikal di tepi paling kiri (`IN`) & tepi paling kanan (`OUT`) dengan skala `+0.0, 0, -6, -12, -18, -24, -36, -48, -60 dB`; dua jalur utama **Path 1** (atas) dan **Path 2** (bawah) lengkap dengan simpul bundar I/O (`wave`, panah turun `1A → 2A`), simpul bundar `Split` & `Merge` dengan lengkungan kabel halus (`1B` & `2B`), serta blok ikon kotak (`44dp × 44dp`, sudut `8dp`, garis tepi warna kategori + cincin putih tebal `2dp` saat terpilih + ikon `LINK` emas di bawah Amp & Cab).
   - **Bottom Split Workspace:**
     - **Kiri (~36% lebar, collapsible):** **Modul Pemilihan Amp/Efek 2-Kolom** — *Sidebar* kiri sempit (`Search`, `× None`, `★ Favorites`, daftar kategori berwarna bergradasi: `Amp`, `Preamp`, `Cab`, `Distortion`, `Delay`, `Reverb`, `Modulation`, `Dynamics`, `EQ`, `Pitch/Synth`, `Wah/Filter`, `Volume/Pan`) berdampingan dengan **Panel Model** (bar ikon sub-kategori + toggle `List / Grid` + tombol audisi speaker + **Grid Kartu 2-Kolom** bergambar ilustrasi vektor prosedural clean-room untuk setiap ampli/kabinet/pedal).
     - **Kanan (~64% lebar, melebar ke 100% saat browser tertutup):** **Inspector Parameter** dengan *header* gradasi warna kategori (mis. merah crimson gelap `#4A1010 → #260808` untuk Amp), tombol Focus Iris di kiri, `[Ikon Kategori] Kategori  Nama Model` di tengah, tombol Sliders/Snapshot/Power Bypass `⏻` di kanan; isi berupa **2-kolom baris parameter** dengan label kiri, nilai berwarna aksen + panah `▲▼` di kanan, dan **batang slider horizontal ramping (`16dp`) berisi isian warna kategori transparan + jarum vertikal terang**, serta segmen pill inline (mis. `Normal | Top Boost`, `Stock | Hype`, `Pre | Post`).
2. **Auto-Close Modul Pemilihan Amp/Efek:**
   - Mengetuk/klik area kosong hitam pada *Signal Flow Canvas*, area non-blok (header kosong/latar belakang), atau tombol tutup otomatis menutup modul pemilihan kiri dengan animasi halus (`AnimatedVisibility` / transisi lebar), sehingga *Parameter Inspector* langsung meluas penuh `100%`.
   - Mengetuk blok efek yang ingin diganti, mengetuk slot kosong `+` di kabel, atau mengetuk nama model/ikon browser di *Inspector Header* membuka modul pemilihan kembali.
3. **Flexible Drag & Drop (Pindah & Tambah Efek di Dalam Blok):**
   - **Drag-to-Move / Reorder:** Tekan-tahan (`long-press`) lalu seret blok mana pun pada *Signal Flow Canvas* untuk memindahkannya ke posisi lain pada jalur yang sama **atau lintas jalur (`1A ↔ 1B ↔ 2A ↔ 2B`)** dengan *ghost floating card* + indikator garis/titik sisip bercahaya pada kabel target.
   - **Drag-to-Add / Replace:** Tekan-tahan lalu seret kartu model/efek langsung dari **Modul Pemilihan Kiri** ke atas *Signal Flow Canvas* — lepas di atas kabel/slot kosong untuk **menyisipkan blok baru**, atau lepas tepat di atas blok yang sudah ada untuk **mengganti model blok tersebut**.
   - **Drop-to-Remove / `None`:** Memilih `× None` pada modul pemilihan (atau menyeret blok keluar ke zona sampah *Remove* saat drag aktif) mengosongkan slot tersebut dan menonaktifkan instance DSP-nya dengan aman (beban CPU `0%` untuk slot kosong).
4. **Dual-Path Multi-Instance DSP (`dsp_core` C++20 + JNI):**
   - Memperluas `GuitarRigGraph` dengan pool multi-instance pra-alokasi (**0 alokasi heap pada RT thread**): `2× Amp`, `2× Cab Slot` (sudah ada), `2× Drive`, `2× Delay`, `2× Reverb`, `2× EQ`, `2× Vol/Pan`, `2× Modulation`, `2× Dynamics` (`Gate` + `Comp` × 2), `2× Wah`, `1× Pitch`, `1× Neural Clone`.
   - Menambahkan `DualPathRoutingPlan` (4 cabang: `Path 1A`, `Path 1B`, `Path 2A`, `Path 2B`, masing-masing hingga `8` slot = hingga `32` slot total, dengan 2 `ParallelSplitMergeNode` + konfigurasi titik *Split* & *Merge* serta rute `Path 1A → Path 2A` / `Path 1B → Out`).
   - Menambahkan *factory preset* **`Space Cadet`** sebagai contoh bawaan yang mereplikasi topologi referensi (`Drive + Mod + Amp + Cab + Split 1 -> [1A: Delay -> Path 2, 1B: Delay -> Reverb -> Vol/Pan -> Out] -> Path 2 [EQ -> Mod -> Split 2 -> (2A: Reverb, 2B: Reverb) -> Merge 2 -> EQ -> Vol/Pan -> Out]`).

### Non-Goals & Anti-Goals
- **Non-Goal:** Tidak menyalin bitmap, ikon, foto produk, atau berkas properti milik Line 6 / pihak ketiga (100% ilustrasi prosedural *clean-room*).
- **Anti-Goal 1:** Tidak boleh melanggar aturan *real-time audio* (0 `malloc`/`new`, 0 `std::mutex` di `onAudioReady`, beban CPU P99 tetap `< 50%` pada burst `48–192f`).
- **Anti-Goal 2:** Tidak boleh merusak postur adaptif HP Portrait/Landscape, Foldable Tabletop, LIVE Play (`PointerEventPass.Initial`), Stage Lock, maupun Opsi C Buffer Size & Device Picker yang sudah lulus Gate 1–4.
- **Anti-Goal 3:** Drag & Drop tidak boleh memblokir *horizontal scroll* biasa di HP kecil (karena drag dipicu via *long-press* `~180–250 ms` pada blok/kartu, sementara geser biasa tetap menggeser kanvas/daftar dengan mulus).

---

## 2. Pendekatan yang Dipertimbangkan (*Approaches*)

| Pendekatan | Ringkasan | Trade-off | Keputusan |
| :--- | :--- | :--- | :--- |
| **Pendekatan 1: UI Identik + 12 Blok Single-Instance** | Merombak UI persis referensi + auto-close + Drag & Drop hanya pada 12 slot tunggal yang ada. | Cepat & CPU `~11–14%`, tetapi tidak bisa menumpuk `2× Delay`, `2× Reverb`, `2× EQ`, atau `2× Vol/Pan` seperti screenshot *Space Cadet*. | Ditolak |
| **Pendekatan 2: UI 100% Identik + Multi-Instance Dual-Path DSP (`Path 1A/1B` + `Path 2A/2B`)** | Merombak seluruh UI studio persis referensi (`jpeg.jpeg`) + auto-close browser + Drag & Drop fleksibel **sekaligus** memperluas `GuitarRigGraph` C++20 & JNI dengan pool multi-instance pra-alokasi dan router 4 cabang (`1A/1B/2A/2B`). | Ruang lingkup lebih besar & butuh uji ketat Gate 1–4; slot kosong dilewati (0 CPU), sehingga rig standar tetap `~11–14%` dan rig ganda penuh tetap jauh di bawah `50%` RT budget. | **Dipilih Pengguna** |

---

## 3. Spesifikasi Teknis Terperinci

### 3.1 Lapisan C++20 DSP Multi-Instance & Dual-Path Router (`dsp_core/`)
1. **Pool Instance Pra-Alokasi pada `GuitarRigGraph` ([signal_chain_processor.hpp](file:///home/antigravity/nafta121/helxmoblie/dsp_core/include/stadium_dsp/signal_chain_processor.hpp)):**
   - Setiap famili efek ber-instance ganda disimpan dalam `std::array<T, 2>` (tanpa heap dinamis di RT thread):
     - `drives[2]`, `delays[2]`, `reverbs[2]`, `eqs[2]`, `vol_pans[2]`, `mods[2]` (menggantikan `mod_pre`/`mod_post` dengan alias kompatibel), `gates[2]`, `comps[2]`, `wahs[2]`, `amps[2]` (`amps[0]` tetap primary Agoura + `amps[1]` untuk Dual Amp / Preamp).
     - `cab` (`DualCabEngine`, sudah memiliki Slot `0` & Slot `1`), `clone` (`NeuralCloneEngine`), `pitch` (`PitchSynthBlock`).
   - **Kompatibilitas Penuh Test 1–18:** `target_id = 0` pada `DspCommand` selalu memetakan ke instance `[0]`; `target_id = 1` memetakan ke instance `[1]`. Metode lama `SetDynamicSlotOrder(order12)` tetap berfungsi dengan mengisi `Path 1A` instance `0`.
2. **Router 4 Cabang `DualPathRoutingPlan` (`Path 1A`, `Path 1B`, `Path 2A`, `Path 2B`):**
   ```cpp
   struct RoutingSlotRef {
       RigBlockType type = RigBlockType::kNone;
       uint8_t instance_idx = 0; // 0 or 1
   };
   struct DualPathRoutingPlan {
       std::array<RoutingSlotRef, 8> path_1a{}; uint8_t count_1a = 0;
       std::array<RoutingSlotRef, 8> path_1b{}; uint8_t count_1b = 0;
       std::array<RoutingSlotRef, 8> path_2a{}; uint8_t count_2a = 0;
       std::array<RoutingSlotRef, 8> path_2b{}; uint8_t count_2b = 0;
       uint8_t split_1_after_index = 4; // Index on 1A where 1B branches off
       uint8_t split_2_after_index = 2; // Index on 2A where 2B branches off
       bool merge_1b_into_1a = false;   // false = 1B mixes directly to final Out (like Space Cadet), true = merges back into 1A
       bool path_1a_feeds_2a = true;    // Serial cascade Path 1A -> Path 2A (arrow-down node)
   };
   ```
   - `ProcessSubBlock16(...)` mengeksekusi berurutan secara deterministik (0 alokasi, seluruh buffer sub-blok 16 sampel berada di stack `alignas(64)`):
     1. Proses `1A[0 .. split_1_after_index)` → jika `count_1b > 0`, belah lewat `split_merge_1` ke `(1A_tail, 1B)`.
     2. Proses sisa `1A[split_1_after_index .. count_1a)` & proses `1B[0 .. count_1b)`.
     3. Jika `merge_1b_into_1a`, gabung lewat `split_merge_1`; jika tidak, simpan hasil `1B` untuk dijumlahkan bersih ke stereo output akhir (persis seperti cabang `1B → Out` di `jpeg.jpeg`).
     4. Umpankan hasil `1A` ke `Path 2A` (jika `path_1a_feeds_2a`), proses `2A[0 .. split_2_after_index)` → jika `count_2b > 0`, belah lewat `split_merge_2` ke `(2A_tail, 2B)` → proses `2A_tail` & `2B` → gabung via `split_merge_2` → proses sisa blok pasca-merge pada `2A` (berdasarkan `merge_2_before_index` — kita simpan `merge_2_at_index` sehingga blok setelah `Merge 2` seperti `EQ` & `Vol/Pan` di kanan bawah `jpeg.jpeg` memproses sinyal gabungan `2A + 2B`!).
     5. Jumlahkan cabang `1B` langsung (jika terpisah) + hasil `Path 2` → `output_level_db` & `output_pan` → `MasterMute` → `SoftCeilingC1(-0.10 dBFS)`.
3. **JNI Bridge (`NativeDspBridge.cpp` & `NativeDspBridge.kt`):**
   - Menambahkan `nativeSetDualPathRouting(path1aTypes, path1aInst, path1bTypes, path1bInst, path2aTypes, path2aInst, path2bTypes, path2bInst, split1After, split2After, merge2At, merge1bInto1a, path1aFeeds2a)`.
   - Meneruskan `instanceIdx` (`0` atau `1`) melalui parameter `targetId` pada `enqueueCommand(...)` untuk seluruh blok.

### 3.2 Model Data, Pool Allocator, & Logika Drag-Drop (`Kotlin`)
1. **Perluasan `RigBlockState` & `SignalFlowState` ([StadiumPresetModels.kt](file:///home/antigravity/nafta121/helxmoblie/app/src/main/java/com/cleanroom/stadiumdsp/preset/StadiumPresetModels.kt)):**
   - Menambahkan `instanceIndex: Int = 0` (`0` atau `1`), `slotIndex: Int` (urutan dalam cabang), dan `pathBranch: String` mendukung `"1A"`, `"1B"`, `"2A"`, `"2B"`.
   - Menambahkan `split1AfterIndex`, `split2AfterIndex`, `merge2AtIndex`, `merge1bInto1a`, `path1aFeeds2a` pada `SignalFlowState`.
   - Menambahkan helper murni & teruji JUnit **`DualPathRoutingEngine`**:
     - `canAddBlock(flow, category)`: memeriksa apakah masih ada `instanceIndex` (`0` atau `1`) yang kosong untuk kategori tersebut (dan maksimal `8` blok per cabang).
     - `insertBlockAt(flow, category, modelId, displayName, shortLabel, targetBranch, targetIndex)`: mengalokasikan `instanceIndex` bebas (`0` atau `1`), membuat `RigBlockState` dengan parameter default skema, menyisipkan pada cabang & indeks tepat, dan mengembalikan `(updatedFlow, newBlockId)`.
     - `replaceBlockAt(flow, blockId, category, modelId, displayName, shortLabel)`: mengganti model/kategori blok (menjaga posisi & cabang, mengembalikan instance lama ke pool jika kategori berubah).
     - `moveBlockTo(flow, blockId, targetBranch, targetIndex)`: memindahkan blok bebas di dalam cabang atau lintas `1A ↔ 1B ↔ 2A ↔ 2B` (termasuk memicu undo snapshot).
     - `removeBlock(flow, blockId)` (`× None`): menghapus blok dari `SignalFlowState`, menonaktifkan instance-nya di C++ DSP, dan merapikan `selectedBlockId`.
   - Menambahkan preset pabrik **`01A Space Cadet`** (default preset baru yang menampilkan struktur 2 jalur penuh mirip `jpeg.jpeg`, sementara preset rock/metal/clean/bass sebelumnya tetap tersedia!).

### 3.3 Perombakan Visual 100% Identik Helix Stadium Native & Interaksi UI
1. **Koordinator Drag & Drop Lintas-Panel (`StudioDragDropController`):**
   - Dibungkus oleh satu `Box` tingkat studio (`StadiumRigAdaptiveContent`) sehingga koordinat pointer global (`Offset`) dapat melacak drag **dari Model Browser Kiri melintasi batas panel ke Signal Flow Canvas di atas**, maupun drag **antar blok di dalam Signal Flow Canvas**!
   - **Pemicu Gesture Ramah Mobile:**
     - Pada blok di *Signal Flow Canvas*: Tap cepat = pilih blok & buka Modul Pemilihan; Tap kedua pada blok terpilih = toggle *Action Popover* (Bypass / Focus / Remove); **Tekan-tahan (`detectDragGesturesAfterLongPress`)** = mulai drag blok (`ghost card` melayang mengikuti jari + slot sisip di kabel menyala neon putih/cyan + tombol zona `× Drop to Remove` muncul di tepi bawah kanvas).
     - Pada kartu model di *Model Browser*: Tap cepat = langsung pasang/ganti ke blok terpilih (atau slot kosong terpilih); **Tekan-tahan (`detectDragGesturesAfterLongPress`)** = mulai drag kartu model keluar dari browser ke kabel *Signal Flow* untuk menyisipkan blok baru atau mengganti blok target!
     - Saat *Stage Lock* aktif, drag & drop dinonaktifkan aman.
2. **Auto-Close Modul Pemilihan Amp/Efek (`isModelBrowserOpen` State):**
   - State eksplisit `isModelBrowserOpen: Boolean` (pada `StadiumUiState` atau `rememberSaveable`).
   - **Auto-Close Trigger:**
     - Tap pada area kosong hitam `#000000` di *Signal Flow Canvas* (non-blok, non-simpul split/merge) → **`isModelBrowserOpen = false`** (dan `selectedSlotTarget = null`).
     - Tap area non-blok pada header/background inspector di luar kontrol aktif → **`isModelBrowserOpen = false`**.
     - Saat tertutup, panel kiri meluncur menutup (`AnimatedVisibility`) dan **Parameter Inspector langsung melebar ke 100% lebar layar**!
   - **Re-Open Trigger:**
     - Tap blok efek mana pun di *Signal Flow Canvas* (atau tap slot kosong `+` di kabel) → memilih blok/slot tersebut & membuka `isModelBrowserOpen = true` (pada HP Portrait membuka *bottom sheet* / drawer pemilihan; pada Tablet/HP Landscape membuka panel 2-kolom kiri).
     - Tap judul model / ikon `+ / Browser` pada *Inspector Header* → men-toggle `isModelBrowserOpen`.
3. **Detail Visual 1:1 Gaya [jpeg.jpeg](file:///home/antigravity/jpeg.jpeg) (100% Clean-Room):**
   - **Top Instance Bar (`StadiumTopInstanceBar.kt`):**
     - Kiri: Knob rotary *Input Trim* (interaktif geser vertikal!) + `A → B` + `Undo` / `Redo`.
     - Tengah: Pill preset gelap `••• | ⇕ Space Cadet` + Kamera Snapshot `[1]` + Audition Clip + Info `(i)`.
     - Kanan: `Clock | 165.0 BPM` + Knob rotary *Output Master* + ikon gear Audio/Buffer (Opsi C) & tombol cepat ke `LIVE` / `Tuner`.
   - **Dual-Path Signal Flow Canvas (`SignalFlowCanvas.kt`):**
     - Kiri & Kanan: **`PrecisionDbMeterColumn`** dengan garis skala `+0.0, 0, -6, -12, -18, -24, -36, -48, -60` dan batang LED hijau-kuning-merah ramping `4dp` yang hanya me-recompose dirinya sendiri pada 60 Hz!
     - Kabel & Simpul digambar presisi dengan Compose `Canvas`:
       - Simpul bundar I/O (`wave` input, panah `↓` antar jalur, `wave` output), simpul bundar `Split` (`⊢`) & `Merge` (`⊣`), kabel `1.5dp` `#7A7A7A` dengan tikungan sudut membulat (`quadraticBezier` / `arcTo`) untuk cabang paralel `1B` & `2B`.
       - Blok kotak `44dp × 44dp` (sudut `8dp`) menampilkan **ikon vektor clean-room khusus per kategori** (bukan teks kotak besar!), tepi warna kategori + cincin putih `2dp` saat terpilih + ikon rantai emas `LINK` di bawah Amp & Cab + indikator titik `+` halus pada ruang antar blok saat mode drag/insert aktif.
   - **2-Column Model Selection Module (`ModelBrowserAndInspector.kt` + `ProceduralHardwareArt.kt`):**
     - Kolom kategori kiri (`~112dp`): `Search`, `× None`, `★ Favorites`, lalu 12 kategori (`Amp`, `Preamp`, `Cab`, `Distortion`, `Delay`, `Reverb`, `Modulation`, `Dynamics`, `EQ`, `Pitch/Synth`, `Wah/Filter`, `Volume/Pan`) dengan gradasi warna kategori saat terpilih.
     - Kolom model kanan: bar sub-filter (`Guitar / Bass / All`, tombol `List/Grid`, SAF `.nam/.wav`) + header pita warna kategori (`GUITAR AMP`) + **2-kolom kartu visual prosedural** (`ProceduralHardwareArt.kt` menggambar siluet vektor amp combo/head, kabinet 4x12/2x12, stompbox 3-knob, pedal ekspresi, rak modulasi/delay sesuai karakter model — 100% clean-room!).
   - **Category-Tinted Parameter Inspector (`ModelBrowserAndInspector.kt`):**
     - *Header* gradasi warna kategori (`catColor.copy(0.32f) → catColor.copy(0.10f)`): kiri ikon **Focus Aperture Ring**, tengah **`[Ikon] Kategori  Nama Model`**, kanan tombol **Browser Toggle | Snapshot/MIDI | Power `⏻`**.
     - **2-Kolom Parameter Bars** persis [jpeg.jpeg](file:///home/antigravity/jpeg.jpeg):
       - Baris atas: Nama parameter kiri (putih lembut) + nilai warna aksen kanan + `▲▼` *stepper*.
       - Baris bawah: Track datar ramping `16dp` berlatar gelap, isian warna kategori transparan (`alpha 0.35`), dan **jarum garis vertikal cerah `3dp`** tepat di posisi nilai.
       - Parameter diskret (`Channel`, `Z PrePost`, `Hype` mode, `Sag/Ripple` preset) dapat ditampilkan sebagai segmen pill (`Normal | Top Boost`) atau stepper rapi.
4. **Adaptivitas 5 Postur Tetap Terjaga:**
   - **Tablet Landscape & HP Landscape (`width >= 600dp` atau `height < 480dp`):** Layout 1:1 identik [jpeg.jpeg](file:///home/antigravity/jpeg.jpeg) (Canvas atas penuh + Split Kiri 2-kolom Browser & Kanan Inspector; Browser *auto-close* saat tap area kosong).
   - **Tablet Portrait (`600–839dp`):** Header + Dual-Path Canvas + Split/Stack Browser & Inspector + tombol Focus cepat.
   - **Phone Portrait (`< 600dp`):** Header ringkas + Dual-Path Canvas (scrollable horizontal) + Inspector penuh; saat tap blok/slot, **Model Browser 2-kolom muncul sebagai *bottom overlay sheet*** yang otomatis menutup saat tap area kosong kanvas/scrim!
   - **Foldable Tabletop:** Layar atas menampilkan Top Bar + Dual-Path Signal Flow, layar bawah menampilkan Inspector/Live Deck.

---

## 4. Keputusan Desain (*Builder Calls*) & Rencana Verifikasi (Gate 1–4)
- **Keputusan arsitektur yang ditetapkan untuk konsistensi:**
  1. **Batas Pool Multi-Instance:** Maksimal `2` instance simultan per famili efek (`2× Amp`, `2× Drive`, `2× Delay`, `2× Reverb`, `2× EQ`, `2× Vol/Pan`, `2× Mod`, `2× Dynamics`, `2× Wah`, `2× Cab Slot`, `1× Pitch`, `1× Clone`) dan hingga `8` blok per cabang (`1A/1B/2A/2B`) — cukup untuk mereplikasi seluruh patch *Space Cadet* dan patch panggung kompleks sambil menjaga konsumsi RAM `~12 MB` dan CPU `< 30%` pada 48 kHz. Jika user mencoba menambah instance ke-3 dari kategori yang sama, UI menampilkan pesan status jelas (`"Max 2 Delay blocks reached"`).
  2. **Pemicu Drag & Drop:** Menggunakan *long-press drag* (`detectDragGesturesAfterLongPress`) agar tidak pernah bentrok dengan tap pemilihan blok maupun scroll horizontal/vertikal pada layar sentuh HP.
  3. **Kompatibilitas Snapshot & Undo/Redo:** Seluruh aksi drag-move, drag-add, replace, dan `× None` tercatat pada `RigHistoryManager` (Undo/Redo 50 langkah) dan menyinkronkan `DualPathRoutingPlan` ke C++20 tanpa klik audio.
- **Rencana Verifikasi 4 Gerbang:**
  - **Gate 1 (C++20 Host):** Lulus seluruh 18 suite lama + **Test 19 (Dual-Instance Stacking `2× Delay`, `2× Reverb`, `2× Drive`, `2× EQ`, `2× Vol/Pan`)** & **Test 20 (Full 4-Branch `Path 1A/1B -> Path 2A/2B` Routing + Space Cadet CPU benchmark `< 35%` RT budget)**.
  - **Gate 2 (Python MNA):** Lulus `8/8` cek MNA `float64`.
  - **Gate 3 (Android JUnit & Multi-ABI Build):** Lulus seluruh 36+ JUnit + suite baru **`DualPathAndDragDropTest`** (insert, replace, cross-branch drag-move `1A↔1B↔2A↔2B`, `None` remove, max-2 instance limit, auto-close browser state) + `BUILD SUCCESSFUL` 0 warnings.
  - **Gate 4 (Live Android 12 Emulator):** Lulus `run_gate4_emulator_test.sh` (`Crashes: 0`, `ANR: 0`, `Oboe Starts: 1` lintas rotasi Phone Portrait ↔ Landscape ↔ Tablet `1920x1200`).
