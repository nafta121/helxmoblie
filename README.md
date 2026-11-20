# Hx Mobile (`Stadium DSP`)

Prosesor multi-efek dan pemodel ampli gitar/bass Android **100% Clean-Room** (C++20 / ARM64 NEON + Google Oboe MMAP + Jetpack Compose Adaptive) dengan paritas arsitektur, presisi sirkuit analog, dan alur kerja *Helix Stadium Native v1.4* serta *LIVE Performance*.

## Dokumentasi & Arsitektur

- [Panduan Kerja & Peta Jalan Proyek](docs/PANDUAN_KERJA.md) — acuan lengkap arsitektur DSP, 82+ model sirkuit presisi hardware (`30` Ampli + `52+` FX + `12` Factory Cabs & `12` Studio Mics), 23 fungsi fondasi (`GAP-01..23`), 5 varian polimorfik *Focus View*, 5 postur adaptif Android, dan standar verifikasi 4-gerbang.
- [Skrip Setup Toolchain Android](scripts/setup_android_sdk.sh) — instalasi otomatis Android SDK 35, NDK `27.2.12479018`, CMake `3.22.1`, dan Gradle `8.11.1`.

## Verifikasi 3-Gerbang Otomatis

```bash
# Gate 1: Suite pengujian host DSP C++20 (18 suites)
cmake -B build_host -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=clang++
cmake --build build_host -j4
./build_host/stadium_dsp_tests .

# Gate 2: Solver referensi Kirchhoff MNA float64 (8 checks)
python3 tools/verify_hardware_precision.py

# Gate 3: Unit test JUnit (9 suites / 29 tests) & build APK multi-ABI (arm64-v8a + x86_64)
./gradlew testDebugUnitTest assembleDebug
```
