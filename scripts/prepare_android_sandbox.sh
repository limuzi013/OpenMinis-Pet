#!/usr/bin/env bash
# Prepare the Android sandbox rootfs asset.
#
# PRoot itself is built from the pinned deps/proot submodule by
# ./deps/build_proot.sh. This script only downloads the pinned Alpine arm64
# minirootfs and verifies that the PRoot artifacts already exist, avoiding the
# retired Termux package URL used by older revisions.
#
# Usage (from repository root):
#   git submodule update --init --recursive
#   ./deps/build_proot.sh
#   ./scripts/prepare_android_sandbox.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"
JNILIBS_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"
ALPINE_SHA256="ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_ASSET="$ASSETS_DIR/proot-aarch64"
PROOT_JNILIB="$JNILIBS_DIR/libproot.so"
LOADER64="$JNILIBS_DIR/libproot-loader.so"

mkdir -p "$ASSETS_DIR"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

if [ ! -f "$ROOTFS_FILE" ]; then
    echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
    tmp="${ROOTFS_FILE}.part"
    rm -f "$tmp"
    curl -fL --retry 3 --retry-delay 2 -o "$tmp" "$ALPINE_URL"
    actual="$(sha256_file "$tmp")"
    if [ "$actual" != "$ALPINE_SHA256" ]; then
        rm -f "$tmp"
        echo "ERROR: Alpine rootfs SHA-256 mismatch" >&2
        echo "expected: $ALPINE_SHA256" >&2
        echo "actual:   $actual" >&2
        exit 1
    fi
    mv "$tmp" "$ROOTFS_FILE"
else
    actual="$(sha256_file "$ROOTFS_FILE")"
    if [ "$actual" != "$ALPINE_SHA256" ]; then
        echo "ERROR: existing Alpine rootfs has an unexpected SHA-256: $actual" >&2
        echo "Delete $ROOTFS_FILE and rerun this script." >&2
        exit 1
    fi
fi

echo "✓ Alpine rootfs: $ROOTFS_FILE"

missing=0
for artifact in "$PROOT_ASSET" "$PROOT_JNILIB" "$LOADER64"; do
    if [ ! -f "$artifact" ]; then
        echo "ERROR: missing PRoot artifact: $artifact" >&2
        missing=1
    fi
done
if [ "$missing" -ne 0 ]; then
    echo "Run ./deps/build_proot.sh first." >&2
    exit 1
fi

echo "✓ PRoot artifacts are present"
echo "Android sandbox assets are ready."
