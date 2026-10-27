#!/usr/bin/env bash
# ============================================================================
# RostamVPN Android Release Script
# ============================================================================
#
# Usage:
#   ./release.sh                    # Full release (build + deploy to device)
#   ./release.sh --publish          # Full release + publish to Play Store + rostam.app
#   ./release.sh --ota-only         # OTA config publish only (no APK build)
#   ./release.sh --device-only      # Build + deploy to test device only
#   ./release.sh --dry-run          # Run all checks without building
#
# Prerequisites:
#   - Android NDK 28.x at $ANDROID_NDK_HOME or default path
#   - adb connected device (for --device-only / default)
#   - ~/.config/gplay/service-account.json (for --publish)
#   - SSH access to axion-master (for OTA publish)
#   - SSH access to rostam.app (for APK hosting)
#
set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/.."
GEN_DIR="$ANDROID_DIR/gen/android"
CONFIG_SRC="$SCRIPT_DIR/../../../config/runtime-config.json"
CONFIG_DST="$GEN_DIR/app/src/main/assets/default-config.json"
APK_DIR="$GEN_DIR/app/build/outputs/apk/release"
APK="$APK_DIR/app-arm64-v8a-release.apk"
AAB="$GEN_DIR/app/build/outputs/bundle/release/app-release.aab"
NDK_DEFAULT="/Users/architect/Library/Android/sdk/ndk/28.0.13004108"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$NDK_DEFAULT}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ── Parse args ─────────────────────────────────────────────────────────────
MODE="device"  # default: build + deploy to device
DRY_RUN=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --publish)    MODE="publish"; shift ;;
        --ota-only)   MODE="ota"; shift ;;
        --device-only) MODE="device"; shift ;;
        --dry-run)    DRY_RUN=true; shift ;;
        -h|--help)
            sed -n '2,/^set/p' "$0" | grep '^#' | sed 's/^# \?//'
            exit 0 ;;
        *) echo -e "${RED}Unknown arg: $1${NC}"; exit 1 ;;
    esac
done

step=0
pass=0
fail=0

check() {
    step=$((step + 1))
    local desc="$1"
    shift
    printf "${CYAN}[%02d]${NC} %-50s " "$step" "$desc"
    if "$@" >/dev/null 2>&1; then
        echo -e "${GREEN}OK${NC}"
        pass=$((pass + 1))
        return 0
    else
        echo -e "${RED}FAIL${NC}"
        fail=$((fail + 1))
        return 1
    fi
}

run() {
    step=$((step + 1))
    local desc="$1"
    shift
    printf "\n${BOLD}[%02d] %s${NC}\n" "$step" "$desc"
    if $DRY_RUN; then
        echo -e "  ${YELLOW}(dry-run) would execute: $*${NC}"
        return 0
    fi
    "$@"
}

# ── Read version from build.gradle.kts ─────────────────────────────────────
VERSION_NAME=$(grep 'versionName' "$GEN_DIR/app/build.gradle.kts" | head -1 | grep -o '"[^"]*"' | tr -d '"')
VERSION_CODE=$(grep 'versionCode' "$GEN_DIR/app/build.gradle.kts" | head -1 | grep -o '[0-9]*')
CONFIG_VERSION=$(python3 -c "import json; print(json.load(open('$CONFIG_SRC'))['version'])" 2>/dev/null || echo "?")

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║         RostamVPN Android Release                ║${NC}"
echo -e "${BOLD}╠══════════════════════════════════════════════════╣${NC}"
echo -e "${BOLD}║${NC}  App version:    ${CYAN}${VERSION_NAME}${NC} (code ${VERSION_CODE})"
echo -e "${BOLD}║${NC}  Config version: ${CYAN}${CONFIG_VERSION}${NC}"
echo -e "${BOLD}║${NC}  Mode:           ${CYAN}${MODE}${NC}"
echo -e "${BOLD}║${NC}  Dry run:        ${CYAN}${DRY_RUN}${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════╝${NC}"
echo ""

# ============================================================================
# PHASE 1: Pre-flight checks
# ============================================================================
echo -e "${BOLD}── Pre-flight Checks ──${NC}"

check "Config file exists"            test -f "$CONFIG_SRC"
check "Embedded config exists"        test -f "$CONFIG_DST"
check "Config in sync"                diff -q "$CONFIG_SRC" "$CONFIG_DST"

if [[ "$MODE" != "ota" ]]; then
    check "Android NDK exists"        test -d "$ANDROID_NDK_HOME"
    check "Rust toolchain"            bash -c 'rustup show | grep -q aarch64'
    check "cargo-ndk installed"       command -v cargo-ndk
    check "Gradle wrapper exists"     test -f "$GEN_DIR/gradlew"
fi

if [[ "$MODE" == "device" || "$MODE" == "publish" ]]; then
    check "adb connected"             adb get-state
fi

if [[ "$MODE" == "publish" ]]; then
    check "Play Store SA key"         test -f "${HOME}/.config/gplay/service-account.json"
    check "SSH to rostam.app"         ssh -o ConnectTimeout=5 -p 822 root@rostam.app true
fi

if [[ "$MODE" == "ota" || "$MODE" == "publish" ]]; then
    check "SSH to axion-master"       ssh -o ConnectTimeout=5 axion-master true
fi

echo ""
echo -e "  Checks: ${GREEN}${pass} passed${NC}, ${RED}${fail} failed${NC}"
if [[ $fail -gt 0 ]]; then
    echo -e "${RED}Fix the above failures before proceeding.${NC}"
    exit 1
fi

# ============================================================================
# PHASE 2: Sync config
# ============================================================================
echo ""
echo -e "${BOLD}── Phase 2: Sync Config ──${NC}"

if ! diff -q "$CONFIG_SRC" "$CONFIG_DST" >/dev/null 2>&1; then
    run "Copy config to APK assets" cp "$CONFIG_SRC" "$CONFIG_DST"
else
    echo -e "  ${GREEN}Config already in sync${NC}"
fi

# ============================================================================
# PHASE 3: Build (skip for OTA-only)
# ============================================================================
if [[ "$MODE" != "ota" ]]; then
    echo ""
    echo -e "${BOLD}── Phase 3: Build ──${NC}"

    run "Build Rust (release, chrome-tls)" \
        bash -c "cd '$ANDROID_DIR' && ANDROID_NDK_HOME='$ANDROID_NDK_HOME' cargo ndk -t arm64-v8a -o gen/android/app/src/main/jniLibs build --lib --release --features chrome-tls"

    run "Build APK (release)" \
        bash -c "cd '$GEN_DIR' && ./gradlew assembleRelease -x buildRustRelease"

    # Verify APK exists and show size
    if [[ -f "$APK" ]] && ! $DRY_RUN; then
        APK_SIZE=$(ls -lh "$APK" | awk '{print $5}')
        echo -e "  APK: ${GREEN}${APK}${NC} (${APK_SIZE})"
    fi

    # Build AAB for Play Store
    if [[ "$MODE" == "publish" ]]; then
        run "Build AAB (release)" \
            bash -c "cd '$GEN_DIR' && ./gradlew bundleRelease -x buildRustRelease"
        if [[ -f "$AAB" ]] && ! $DRY_RUN; then
            AAB_SIZE=$(ls -lh "$AAB" | awk '{print $5}')
            echo -e "  AAB: ${GREEN}${AAB}${NC} (${AAB_SIZE})"
        fi
    fi
fi

# ============================================================================
# PHASE 4: Deploy to test device
# ============================================================================
if [[ "$MODE" == "device" || "$MODE" == "publish" ]]; then
    echo ""
    echo -e "${BOLD}── Phase 4: Deploy to Device ──${NC}"
    run "Install APK on device" adb install -r "$APK"
    run "Launch app" adb shell am start -n com.rostamvpn.android/.MainActivity
    echo -e "  ${YELLOW}Test the app now. Verify:${NC}"
    echo "    1. App launches without crash"
    echo "    2. Server list populated (not empty)"
    echo "    3. Auto-connect works (DNSTT for Iran timezone)"
    echo "    4. Manual transport selection works"
    echo "    5. Settings sheet shows correct version: ${VERSION_NAME}"
fi

# ============================================================================
# PHASE 5: OTA Publish
# ============================================================================
if [[ "$MODE" == "ota" || "$MODE" == "publish" ]]; then
    echo ""
    echo -e "${BOLD}── Phase 5: OTA Config Publish ──${NC}"

    run "Upload config to axion-master" \
        scp "$CONFIG_SRC" axion-master:/tmp/runtime-config.json

    run "Publish to DNS TXT" \
        ssh axion-master "python3 /tmp/publish_config.py /tmp/runtime-config.json"
fi

# ============================================================================
# PHASE 6: Production Publish (Play Store + rostam.app)
# ============================================================================
if [[ "$MODE" == "publish" ]]; then
    echo ""
    echo -e "${BOLD}── Phase 6: Production Publish ──${NC}"
    echo -e "  ${YELLOW}Confirm you have tested the APK on device before continuing.${NC}"
    echo -n "  Publish v${VERSION_NAME} to Play Store + rostam.app? [y/N] "
    read -r confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        echo "  Aborted."
        exit 0
    fi

    # Play Store (AAB)
    run "Deploy to Google Play (production)" \
        bash -c "cd '$(dirname "$0")/../../deploy/gplay' && ./deploy-gplay.sh --track production --aab '$AAB'"

    # rostam.app (APK)
    run "Upload APK to rostam.app" \
        bash -c "
            VER='${VERSION_NAME}'
            scp -P 822 '$APK' root@rostam.app:/var/www/rostamvpn/public/site/dist/RostamVPN-\${VER}.apk
            ssh -p 822 root@rostam.app \"
                cd /var/www/rostamvpn/public/site/dist &&
                cp RostamVPN-\${VER}.apk RostamVPN.apk &&
                cp RostamVPN-\${VER}.apk RostamVPN-latest.apk &&
                sha256sum RostamVPN-\${VER}.apk > RostamVPN-\${VER}.apk.sha256
            \"
        "
    echo -e "  ${GREEN}APK published to https://rostam.app/dist/RostamVPN-${VERSION_NAME}.apk${NC}"
fi

# ============================================================================
# Done
# ============================================================================
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║  Release complete: v${VERSION_NAME} (code ${VERSION_CODE})${NC}"
echo -e "${BOLD}║  Config: ${CONFIG_VERSION}${NC}"
echo -e "${BOLD}║  Mode: ${MODE}${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════╝${NC}"
