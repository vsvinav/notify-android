#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the approved NDK 28.2.13676358 directory}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android28-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
# Android 15+ supports 16 KB pages; both ELF segments and APK packaging must align.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --remap-path-prefix=$HOME=/build"
cargo +1.91.0 build --locked --release --manifest-path "$ROOT/Cargo.toml" \
    --target aarch64-linux-android --target-dir "$ROOT/target"
mkdir -p "$ROOT/jniLibs/arm64-v8a"
cp "$ROOT/target/aarch64-linux-android/release/libnotify_android.so" \
    "$ROOT/jniLibs/arm64-v8a/libnotify_android.so"
"$TOOLCHAIN/llvm-readelf" -h -l "$ROOT/jniLibs/arm64-v8a/libnotify_android.so"
