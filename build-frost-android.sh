#!/bin/bash
set -euo pipefail

# Build spark-frost native library for Android targets using Docker
# Outputs:
#   lib/src/main/jniLibs/{abi}/libspark_frost.so
#   lib/src/main/kotlin/gy/pig/spark/frost/spark_frost.kt

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_JNILIB="$SCRIPT_DIR/lib/src/main/jniLibs"
OUTPUT_KOTLIN="$SCRIPT_DIR/lib/src/main/kotlin/gy/pig/spark/frost"

echo "==> Building spark-frost for Android in Docker (linux/amd64)..."

docker build --progress=plain --platform linux/amd64 \
    -t spark-frost-android -f - "$SCRIPT_DIR" <<'DOCKERFILE'
FROM --platform=linux/amd64 rust:1.88-bookworm

# Install protobuf compiler and Android NDK dependencies
RUN apt-get update && apt-get install -y \
    protobuf-compiler \
    unzip \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Install Android NDK
ENV ANDROID_NDK_VERSION=r27c
ENV ANDROID_NDK_HOME=/opt/android-ndk
RUN wget -q https://dl.google.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux.zip -O /tmp/ndk.zip \
    && unzip -q /tmp/ndk.zip -d /opt \
    && mv /opt/android-ndk-${ANDROID_NDK_VERSION} ${ANDROID_NDK_HOME} \
    && rm /tmp/ndk.zip

ENV ANDROID_NDK_TOOLCHAIN=${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64

WORKDIR /build

# Clone spark repo
RUN git clone --depth 1 https://github.com/buildonspark/spark.git /build/spark

# Ensure spark-frost-uniffi is in workspace
WORKDIR /build/spark/signer
RUN if ! grep -q 'spark-frost-uniffi' Cargo.toml; then \
        sed -i 's/members = \[/members = [\n    "spark-frost-uniffi",/' Cargo.toml; \
    fi

# First build for host - needed to generate bindings
# This may install a different Rust toolchain via rust-toolchain.toml
RUN cargo build --release -p spark-frost-uniffi

# Add Android targets to whatever toolchain cargo is now using
RUN rustup target add \
    aarch64-linux-android \
    armv7-linux-androideabi \
    x86_64-linux-android \
    i686-linux-android

# Generate Kotlin bindings using the crate's own uniffi-bindgen binary
RUN cargo run --release -p spark-frost-uniffi --bin uniffi-bindgen -- generate \
        spark-frost-uniffi/src/spark_frost.udl \
        --language kotlin \
        --out-dir /build/kotlin-bindings/

# Write cargo config INSIDE the project (so it applies regardless of toolchain)
# AND set CC/CXX/AR env vars for cc-rs crate
RUN mkdir -p /build/spark/signer/.cargo && cat > /build/spark/signer/.cargo/config.toml <<CARGOEOF
[target.aarch64-linux-android]
ar = "${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar"
linker = "${ANDROID_NDK_TOOLCHAIN}/bin/aarch64-linux-android24-clang"

[target.armv7-linux-androideabi]
ar = "${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar"
linker = "${ANDROID_NDK_TOOLCHAIN}/bin/armv7a-linux-androideabi24-clang"

[target.x86_64-linux-android]
ar = "${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar"
linker = "${ANDROID_NDK_TOOLCHAIN}/bin/x86_64-linux-android24-clang"

[target.i686-linux-android]
ar = "${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar"
linker = "${ANDROID_NDK_TOOLCHAIN}/bin/i686-linux-android24-clang"
CARGOEOF

# Set CC/CXX/AR env vars for cc-rs crate (build scripts)
ENV CC_aarch64_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/aarch64-linux-android24-clang \
    CXX_aarch64_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/aarch64-linux-android24-clang++ \
    AR_aarch64_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar \
    CC_armv7_linux_androideabi=${ANDROID_NDK_TOOLCHAIN}/bin/armv7a-linux-androideabi24-clang \
    CXX_armv7_linux_androideabi=${ANDROID_NDK_TOOLCHAIN}/bin/armv7a-linux-androideabi24-clang++ \
    AR_armv7_linux_androideabi=${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar \
    CC_x86_64_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/x86_64-linux-android24-clang \
    CXX_x86_64_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/x86_64-linux-android24-clang++ \
    AR_x86_64_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar \
    CC_i686_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/i686-linux-android24-clang \
    CXX_i686_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/i686-linux-android24-clang++ \
    AR_i686_linux_android=${ANDROID_NDK_TOOLCHAIN}/bin/llvm-ar

# Android 15+ requires native .so files to be 16 KB page aligned.
# Applies to every Rust target build below so the resulting
# libspark_frost.so / libuniffi_spark_frost.so pass the Play Store
# 16 KB ELF alignment check (mandatory Nov 1, 2025).
ENV RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

# Build for each Android target separately
RUN echo "==> Building for aarch64-linux-android..." && \
    cargo build --release -p spark-frost-uniffi --target aarch64-linux-android

RUN echo "==> Building for armv7-linux-androideabi..." && \
    cargo build --release -p spark-frost-uniffi --target armv7-linux-androideabi

RUN echo "==> Building for x86_64-linux-android..." && \
    cargo build --release -p spark-frost-uniffi --target x86_64-linux-android

RUN echo "==> Building for i686-linux-android..." && \
    cargo build --release -p spark-frost-uniffi --target i686-linux-android

# Copy outputs to a known location
RUN mkdir -p /output/jniLibs/arm64-v8a \
             /output/jniLibs/armeabi-v7a \
             /output/jniLibs/x86_64 \
             /output/jniLibs/x86 \
             /output/kotlin && \
    cp target/aarch64-linux-android/release/libspark_frost.so /output/jniLibs/arm64-v8a/ && \
    cp target/armv7-linux-androideabi/release/libspark_frost.so /output/jniLibs/armeabi-v7a/ && \
    cp target/x86_64-linux-android/release/libspark_frost.so /output/jniLibs/x86_64/ && \
    cp target/i686-linux-android/release/libspark_frost.so /output/jniLibs/x86/ && \
    cp -r /build/kotlin-bindings/* /output/kotlin/

# Show what we built
RUN echo "=== Native libraries ===" && \
    find /output/jniLibs -name "*.so" -exec ls -lh {} \; && \
    echo "=== Kotlin bindings ===" && \
    find /output/kotlin -name "*.kt" -exec ls -lh {} \;
DOCKERFILE

echo "==> Extracting build artifacts..."

# Create output directories
mkdir -p "$OUTPUT_JNILIB"/{arm64-v8a,armeabi-v7a,x86_64,x86}
mkdir -p "$OUTPUT_KOTLIN"

# Copy artifacts from Docker image
CONTAINER_ID=$(docker create spark-frost-android)
docker cp "$CONTAINER_ID:/output/jniLibs/." "$OUTPUT_JNILIB/"
docker cp "$CONTAINER_ID:/output/kotlin/." "$OUTPUT_KOTLIN/"
docker rm "$CONTAINER_ID"

# The generated Kotlin bindings call Native.load("uniffi_spark_frost"),
# but the Rust cdylib name is "spark_frost". Provide both on disk so
# JNA can resolve either — and so the 16 KB-aligned build propagates
# to libuniffi_spark_frost.so instead of leaving a stale copy behind.
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    cp -f "$OUTPUT_JNILIB/$abi/libspark_frost.so" \
          "$OUTPUT_JNILIB/$abi/libuniffi_spark_frost.so"
done

echo ""
echo "==> Build complete!"
echo "Native libraries:"
find "$OUTPUT_JNILIB" -name "*.so" -exec ls -lh {} \;
echo ""
echo "Kotlin bindings:"
find "$OUTPUT_KOTLIN" -name "*.kt" -exec ls -lh {} \;
