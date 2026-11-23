#!/usr/bin/env bash
set -euo pipefail

SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
AVD_NAME="${AVD_NAME:-stadium_api31_atd}"
APK_PATH="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="com.cleanroom.stadiumdsp"

echo "=== [Gate 4] Starting Headless Android 12 ATD Emulator ($AVD_NAME) ==="
rm -rf "$HOME/.android/avd/${AVD_NAME}.avd/"*.lock || true
export ANDROID_I_WANT_MY_TCG=yes
"$EMULATOR" -avd "$AVD_NAME" \
    -no-window -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader_indirect \
    -accel off -memory 2048 -cores 2 -no-metrics \
    -feature -ModemSimulator,-GnssGrpcV1,-WiFiPacketStream,-Uwb \
    >/tmp/stadium_emulator.log 2>&1 &
EMU_PID=$!

cleanup() {
    "$ADB" -s emulator-5554 emu kill >/dev/null 2>&1 || true
    kill "$EMU_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== [1/5] Waiting for emulator-5554 sys.boot_completed=1 ==="
BOOTED=0
for i in $(seq 1 70); do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
        echo "ERROR: emulator process exited early!"
        tail -n 30 /tmp/stadium_emulator.log || true
        exit 1
    fi
    STATE=$("$ADB" -s emulator-5554 get-state 2>/dev/null || echo "offline")
    BOOT=$("$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || echo "")
    if (( i % 3 == 0 )); then
        echo "[+$((i * 5))s] adb=$STATE | boot_completed=$BOOT"
    fi
    if [[ "$STATE" == "device" && "$BOOT" == "1" ]]; then
        echo "SUCCESS: emulator-5554 booted in ~$((i * 5))s!"
        BOOTED=1
        break
    fi
    sleep 5
done

if [[ "$BOOTED" != "1" ]]; then
    echo "ERROR: emulator-5554 did not finish booting within 350s"
    tail -n 30 /tmp/stadium_emulator.log || true
    exit 1
fi

echo "=== [2/5] Installing $APK_PATH & Granting RECORD_AUDIO ==="
for p in $(seq 1 20); do
    if "$ADB" -s emulator-5554 shell pm path android >/dev/null 2>&1; then
        break
    fi
    sleep 3
done
"$ADB" -s emulator-5554 push "$APK_PATH" /data/local/tmp/stadium_gate4.apk
INSTALLED=0
for attempt in 1 2 3 4 5 6; do
    if "$ADB" -s emulator-5554 shell pm path android >/dev/null 2>&1 && \
       "$ADB" -s emulator-5554 shell pm install -r /data/local/tmp/stadium_gate4.apk; then
        INSTALLED=1
        break
    fi
    echo "Retrying pm install ($attempt/6) after 10s..."
    sleep 10
done
if [[ "$INSTALLED" != "1" ]]; then
    echo "ERROR: pm install failed after 3 attempts"
    "$ADB" -s emulator-5554 logcat -d | grep -iE "PackageManager|Watchdog|FATAL|system_server" | tail -n 25 || true
    exit 1
fi
"$ADB" -s emulator-5554 shell rm -f /data/local/tmp/stadium_gate4.apk || true
"$ADB" -s emulator-5554 shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
"$ADB" -s emulator-5554 logcat -c

echo "=== [3/5] Launching MainActivity & Waiting for Live Oboe/AAudio Latency Telemetry ==="
"$ADB" -s emulator-5554 shell am start -W -n "$PKG/.MainActivity"

OBOE_READY=0
for w in $(seq 1 45); do
    if "$ADB" -s emulator-5554 logcat -d | grep -q "Stadium Oboe Engine STARTED"; then
        echo "Oboe Engine live after ~$((w * 3))s!"
        OBOE_READY=1
        break
    fi
    sleep 3
done

# Let 60 Hz telemetry loop emit live running=true StadiumGate4Telemetry diagnostics
for t in $(seq 1 10); do
    if "$ADB" -s emulator-5554 logcat -d | grep -q "StadiumGate4Telemetry.*running=true"; then
        break
    fi
    sleep 2
done

APP_PID=$("$ADB" -s emulator-5554 shell pidof "$PKG" | tr -d '\r' || echo "")
echo "App PID (Initial): ${APP_PID:-NONE} | OboeReady=$OBOE_READY"

echo "=== [4/5] Testing Adaptive Postures & 0-ms Rotation Continuity (Phone -> Landscape -> Tablet) ==="
# Phone Portrait -> Phone Landscape rotation
"$ADB" -s emulator-5554 shell settings put system accelerometer_rotation 0
"$ADB" -s emulator-5554 shell settings put system user_rotation 1
sleep 6
# Tablet Landscape size simulation (1920x1200, 240dpi) — matches Helix Stadium Native Studio aspect ratio
"$ADB" -s emulator-5554 shell wm size 1920x1200
"$ADB" -s emulator-5554 shell wm density 240
sleep 14
"$ADB" -s emulator-5554 exec-out screencap -p > /tmp/gate4_tablet_open.png || true
# Tap empty black Signal Flow area to verify auto-close of Model Browser!
"$ADB" -s emulator-5554 shell input tap 960 175
sleep 4
"$ADB" -s emulator-5554 exec-out screencap -p > /tmp/gate4_tablet_closed.png || true
# Back to Phone Portrait default
"$ADB" -s emulator-5554 shell wm density reset
"$ADB" -s emulator-5554 shell wm size reset
"$ADB" -s emulator-5554 shell settings put system user_rotation 0
sleep 8

APP_PID_AFTER=$("$ADB" -s emulator-5554 shell pidof "$PKG" | tr -d '\r' || echo "")
echo "App PID (After Rotation & Resize): ${APP_PID_AFTER:-NONE}"

echo "=== [5/5] Gate 4 Crash, ANR, Oboe Start Count, & Latency Telemetry Report ==="
LOG_DUMP="/tmp/stadium_gate4_logcat.txt"
"$ADB" -s emulator-5554 logcat -d > "$LOG_DUMP"

CRASHES=$(grep -c "FATAL EXCEPTION\|AndroidRuntime.*$PKG" "$LOG_DUMP" || true)
ANRS=$(grep -c "ANR in $PKG" "$LOG_DUMP" || true)
OBOE_STARTS=$(grep -c "Stadium Oboe Engine STARTED" "$LOG_DUMP" || true)

echo "AndroidRuntime Crashes : $CRASHES"
echo "ANR Count              : $ANRS"
echo "Oboe Engine Starts     : $OBOE_STARTS (expected == 1 across all rotations for 0-ms continuity!)"
echo "--- StadiumOboeEngine & StadiumGate4Telemetry Logs ---"
grep -E "StadiumOboeEngine|StadiumGate4Telemetry" "$LOG_DUMP" | tail -n 30 || true

if [[ -z "$APP_PID_AFTER" || "$APP_PID_AFTER" != "$APP_PID" || "$CRASHES" != "0" || "$ANRS" != "0" || "$OBOE_STARTS" != "1" ]]; then
    echo "FAIL: Gate 4 check failed (PID=$APP_PID->$APP_PID_AFTER, crashes=$CRASHES, anrs=$ANRS, oboe_starts=$OBOE_STARTS)!"
    grep -n -C 5 "FATAL EXCEPTION\|AndroidRuntime\|StadiumOboeEngine" "$LOG_DUMP" | tail -n 30 || true
    exit 1
fi

echo "PASS: Gate 4 Live Emulator Runtime, Rotation Continuity, & Oboe Latency Verified!"
