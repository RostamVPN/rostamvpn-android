#!/bin/bash
#
# Update AmneziaWG library (libawg.so) to latest version with i1-i5 support
#
# Requirements:
# - Go 1.21+ (for building amneziawg-go)
# - Android NDK (set ANDROID_NDK_HOME)
# - git
#
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/build/awg-update"
JNILIBS_DIR="$PROJECT_ROOT/gen/android/app/src/main/jniLibs"

# Android targets
TARGETS=(
    "arm64-v8a:android/arm64"
    "armeabi-v7a:android/arm"
)

echo "=== AmneziaWG Library Updater ==="
echo "Build dir: $BUILD_DIR"
echo "JNI libs dir: $JNILIBS_DIR"
echo ""

# Check prerequisites
if ! command -v go &> /dev/null; then
    echo "ERROR: Go is not installed. Install Go 1.21+ first."
    exit 1
fi

if [ -z "$ANDROID_NDK_HOME" ]; then
    # Try common locations
    if [ -d "$HOME/Library/Android/sdk/ndk/29.0.13846066" ]; then
        export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/29.0.13846066"
    elif [ -d "$HOME/Android/Sdk/ndk-bundle" ]; then
        export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk-bundle"
    else
        echo "ERROR: ANDROID_NDK_HOME not set and NDK not found in common locations"
        exit 1
    fi
fi
echo "Using NDK: $ANDROID_NDK_HOME"

# Clone or update amneziawg-android
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [ -d "amneziawg-android" ]; then
    echo "Updating existing amneziawg-android repo..."
    cd amneziawg-android
    git fetch origin
    git checkout master
    git pull origin master
    git submodule update --init --recursive
else
    echo "Cloning amneziawg-android..."
    git clone --recurse-submodules https://github.com/amnezia-vpn/amneziawg-android
    cd amneziawg-android
fi

# Build libawg.so for each architecture
echo ""
echo "=== Building libawg.so ==="

cd tunnel/tools/libwg-go

for target_pair in "${TARGETS[@]}"; do
    ABI="${target_pair%%:*}"
    GOARCH_FULL="${target_pair##*:}"
    GOOS="${GOARCH_FULL%%/*}"
    GOARCH="${GOARCH_FULL##*/}"

    echo ""
    echo "Building for $ABI (GOOS=$GOOS GOARCH=$GOARCH)..."

    OUTPUT_DIR="$BUILD_DIR/libs/$ABI"
    mkdir -p "$OUTPUT_DIR"

    # Set up cross-compilation environment
    export CGO_ENABLED=1
    export GOOS=android

    if [ "$GOARCH" = "arm64" ]; then
        export GOARCH=arm64
        export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang"
    elif [ "$GOARCH" = "arm" ]; then
        export GOARCH=arm
        export GOARM=7
        export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi21-clang"
    fi

    # Build the shared library
    go build -buildmode=c-shared -ldflags="-w -s" -o "$OUTPUT_DIR/libawg.so" .

    echo "Built: $OUTPUT_DIR/libawg.so"
    ls -la "$OUTPUT_DIR/libawg.so"
done

# Copy to project jniLibs
echo ""
echo "=== Installing to project ==="

for target_pair in "${TARGETS[@]}"; do
    ABI="${target_pair%%:*}"
    SRC="$BUILD_DIR/libs/$ABI/libawg.so"
    DST="$JNILIBS_DIR/$ABI/libawg.so"

    if [ -f "$SRC" ]; then
        echo "Installing $ABI..."
        cp "$SRC" "$DST"
        echo "  $DST"
    else
        echo "WARNING: $SRC not found!"
    fi
done

echo ""
echo "=== Done ==="
echo "New libawg.so installed with i1-i5 support."
echo ""
echo "Verify by checking awgVersion() in logcat after rebuild."
echo "Then re-enable i1/i2 in lib.rs build_awg_config()"
