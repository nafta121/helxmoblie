# Hx Mobile (`Stadium DSP`)

Prosesor multi-efek dan pemodel ampli gitar/bass Android **100% Clean-Room** (C++20 / ARM64 NEON + Google Oboe MMAP + Jetpack Compose) dengan paritas arsitektur, kualitas suara, dan alur kerja *Helix Stadium Native v1.4* serta *LIVE Performance*.

## Dokumentasi Utama

- [Panduan Kerja & Peta Jalan Proyek](docs/PANDUAN_KERJA.md) — acuan lengkap arsitektur DSP, 73+ model sirkuit presisi hardware, audit 23 kesenjangan fitur, desain UI/UX adaptif 5-postur, dan standar verifikasi 4-gerbang.

## Verifikasi Cepat (Host C++20 & Python MNA)

```bash
# Gate 1: Bangun dan jalankan suite pengujian DSP C++20
cmake -B build_host -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=clang++
cmake --build build_host -j4
./build_host/stadium_dsp_tests .

# Gate 2: Verifikasi referensi sirkuit MNA presisi float64
python3 tools/verify_hardware_precision.py

# Gate 3: Unit test JUnit & build APK multi-ABI (arm64-v8a + x86_64)
./gradlew testDebugUnitTest assembleDebug
```
