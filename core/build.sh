#!/bin/sh
# Build the vendored 6.1 core with the Android NDK.
set -eu
ROOT=$(cd "$(dirname "$0")" && pwd)
CC=${CC:-${ANDROID_NDK_HOME:-/d/Android/Sdk/ndk/27.2.12479018}/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang}
"$CC" -O2 -static -pthread -Wall -Wextra "$ROOT/timer98260.c" -o "${1:-$ROOT/t98260}"
echo "built ${1:-$ROOT/t98260}"
