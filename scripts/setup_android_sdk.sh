#!/usr/bin/env bash
set -euo pipefail

# Repeatable Android SDK + NDK + Proxy SSL TrustStore setup for Hx Mobile (Stadium DSP)
# Installs: platforms;android-35, build-tools;35.0.0, build-tools;34.0.0, cmake;3.22.1, ndk;27.2.12479018

SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_DETECTED="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
JAVA_CACERTS="$JAVA_HOME_DETECTED/lib/security/cacerts"
PROXY_CA="/etc/ssl/certs/ca-certificates.crt"
CUSTOM_TRUSTSTORE="/tmp/agy_cacerts.p12"

echo "==> [1/4] Preparing JVM SSL truststore ($CUSTOM_TRUSTSTORE)..."
if [[ -f "$PROXY_CA" ]]; then
    rm -f "$CUSTOM_TRUSTSTORE"
    if [[ -r "$JAVA_CACERTS" ]]; then
        keytool -importkeystore \
            -srckeystore "$JAVA_CACERTS" -srcstorepass changeit \
            -destkeystore "$CUSTOM_TRUSTSTORE" -deststoretype PKCS12 -deststorepass changeit -noprompt >/dev/null 2>&1 || true
    fi
    keytool -importcert -trustcacerts -alias agy-proxy-ca \
        -file "$PROXY_CA" \
        -keystore "$CUSTOM_TRUSTSTORE" -storetype PKCS12 -storepass changeit -noprompt >/dev/null 2>&1
    export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$CUSTOM_TRUSTSTORE -Djavax.net.ssl.trustStorePassword=changeit"
fi

# Extract proxy host/port from HTTPS_PROXY if present (format http://host:port)
PROXY_ARGS=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    PROXY_HOSTPORT="${HTTPS_PROXY#http://}"
    PROXY_HOSTPORT="${PROXY_HOSTPORT#https://}"
    PROXY_HOSTPORT="${PROXY_HOSTPORT%/}"
    PROXY_HOST="${PROXY_HOSTPORT%%:*}"
    PROXY_PORT="${PROXY_HOSTPORT##*:}"
    if [[ -n "$PROXY_HOST" && -n "$PROXY_PORT" ]]; then
        PROXY_ARGS=(--proxy=http --proxy_host="$PROXY_HOST" --proxy_port="$PROXY_PORT")
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT"
    fi
fi

echo "==> [2/4] Installing Android command-line tools into $SDK_DIR..."
mkdir -p "$SDK_DIR/cmdline-tools"
if [[ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
    TMP_ZIP="/tmp/cmdline-tools.zip"
    curl -fSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o "$TMP_ZIP"
    rm -rf "$SDK_DIR/cmdline-tools/latest" "$SDK_DIR/cmdline-tools/cmdline-tools"
    unzip -q "$TMP_ZIP" -d "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -f "$TMP_ZIP"
fi

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

echo "==> [3/4] Accepting licenses and installing Android 35 SDK, CMake 3.22.1, NDK 27.2.12479018..."
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" "${PROXY_ARGS[@]}" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$SDK_DIR" "${PROXY_ARGS[@]}" \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "build-tools;34.0.0" \
    "cmake;3.22.1" \
    "ndk;27.2.12479018"

echo "==> [4/4] Writing $REPO_ROOT/local.properties, ~/.gradle/gradle.properties, & installing Gradle 8.11.1..."
printf "sdk.dir=%s\n" "$SDK_DIR" > "$REPO_ROOT/local.properties"

mkdir -p "$HOME/.gradle"
{
    if [[ -f "$CUSTOM_TRUSTSTORE" ]]; then
        printf "systemProp.javax.net.ssl.trustStore=%s\n" "$CUSTOM_TRUSTSTORE"
        printf "systemProp.javax.net.ssl.trustStorePassword=changeit\n"
    fi
    if [[ -n "${PROXY_HOST:-}" && -n "${PROXY_PORT:-}" ]]; then
        printf "systemProp.http.proxyHost=%s\n" "$PROXY_HOST"
        printf "systemProp.http.proxyPort=%s\n" "$PROXY_PORT"
        printf "systemProp.https.proxyHost=%s\n" "$PROXY_HOST"
        printf "systemProp.https.proxyPort=%s\n" "$PROXY_PORT"
        printf "systemProp.http.nonProxyHosts=localhost|127.0.0.1\n"
    fi
} > "$HOME/.gradle/gradle.properties"

GRADLE_DIR="$HOME/.local/gradle-8.11.1"
if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
    mkdir -p "$HOME/.local"
    TMP_GRADLE="/tmp/gradle-8.11.1-bin.zip"
    curl -fSL "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip" -o "$TMP_GRADLE"
    unzip -q "$TMP_GRADLE" -d "$HOME/.local"
    rm -f "$TMP_GRADLE"
fi

echo "==> Android SDK/NDK + Gradle 8.11.1 setup complete!"
ls -la "$SDK_DIR/ndk/27.2.12479018" "$SDK_DIR/platforms/android-35" "$SDK_DIR/cmake/3.22.1"
