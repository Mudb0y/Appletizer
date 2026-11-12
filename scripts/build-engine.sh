#!/usr/bin/env bash
# Convert both engines for linux-arm64 or android-arm64, and on linux-arm64
# also build vesay. Run inside `nix develop`, after fetch-firmware.sh.
#   libve131.so  VE 1.3.1 from iOS 7.1.2's voiced: iOS 7 and 8 voices
#   libve151.so  VE 1.5.1 from iOS 10.3.3's TTSSpeechBundle: iOS 9 to 15 voices
set -euo pipefail

target=${1:?usage: build-engine.sh linux-arm64|android-arm64}
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware
macho2elf=${MACHO2ELF:-$root/scripts/macho2elf.py}
out=$root/work/build/$target
ndk_bin=${ANDROID_NDK_ROOT:-}/toolchains/llvm/prebuilt/linux-x86_64/bin

# Gradle packages everything under jniLibs; vesay finds the engines next to it.
case $target in
linux-arm64)
    os=linux cc=aarch64-unknown-linux-gnu-gcc libs=$out
    cxx=(--lib-dir "$AARCH64_LIBCXX") ;;
android-arm64)
    os=android cc=$ndk_bin/aarch64-linux-android26-clang libs=$out/jniLibs/arm64-v8a
    cxx=() ;;
*) echo "build-engine: unknown target $target" >&2; exit 2 ;;
esac

rm -rf "$out"
mkdir -p "$libs"

# convert LIB SOURCE MACHO2ELF_FLAGS...
convert() {
    local lib=$1 src=$2 log=$out/${1%.so}.log
    shift 2
    [ -f "$src" ] || { echo "build-engine: no $src; run fetch-firmware.sh" >&2; exit 1; }
    echo "build-engine: converting $lib for $target"
    "$M2E_PYTHON" -I "$macho2elf" "$src" -o "$libs/$lib" --workdir "$out/m2e-${lib%.so}" \
        --os "$os" --cc "$cc" --trap-missing "$@" > "$log" 2>&1 || { tail -20 "$log" >&2; exit 1; }
}

# VE 1.3.1's own code calls only libc; Apple's daemon code around it never runs.
convert libve131.so "$fw/ios7/voiced" --skip-initializers --no-cxx-runtime
# VE 1.5.1 runs Apple's C++ Gryphon components, whose globals its initializers build.
convert libve151.so "$fw/ios10/TTSSpeechBundle.arm64" "${cxx[@]}"

if [ "$target" = linux-arm64 ]; then
    $cc -O2 -Wall -Wextra -o "$out/vesay" "$root/native/vesay.c" "$root/native/engine.c" \
        -Wl,-rpath,'$ORIGIN' -ldl -lpthread
fi
echo "build-engine: $(ls "$libs"/*.so | tr '\n' ' ')"
