#!/usr/bin/env bash
# Convert iOS 7.1.2's voiced into libvoiced.so for linux-arm64 or android-arm64,
# and on linux-arm64 also build vesay against it. Run inside `nix develop`.
set -euo pipefail

target=${1:?usage: build-engine.sh linux-arm64|android-arm64}
root=$(cd "$(dirname "$0")/.." && pwd)
voiced=${VOICED:-$root/work/ipsw/iPhone6,1_7.1.2/rootfs/Sochi11D257.N51OS/System/Library/PrivateFrameworks/VoiceServices.framework/Support/voiced}
macho2elf=${MACHO2ELF:-$root/scripts/macho2elf.py}
out=$root/work/build/$target
ndk_bin=${ANDROID_NDK_ROOT:-}/toolchains/llvm/prebuilt/linux-x86_64/bin

case $target in
linux-arm64)   os=linux;   cc=aarch64-unknown-linux-gnu-gcc ;;
android-arm64) os=android; cc=$ndk_bin/aarch64-linux-android26-clang ;;
*) echo "build-engine: unknown target $target" >&2; exit 2 ;;
esac

[ -f "$voiced" ] || { echo "build-engine: no voiced at $voiced" >&2; exit 1; }
rm -rf "$out"
mkdir -p "$out"

echo "build-engine: converting voiced for $target"
"$M2E_PYTHON" -I "$macho2elf" "$voiced" -o "$out/libvoiced.so" --workdir "$out/m2e" \
    --os "$os" --cc "$cc" --skip-initializers --trap-missing --no-cxx-runtime \
    > "$out/macho2elf.log" 2>&1 || { tail -20 "$out/macho2elf.log" >&2; exit 1; }

if [ "$target" = linux-arm64 ]; then
    $cc -O2 -Wall -Wextra -o "$out/vesay" "$root/native/vesay.c" "$root/native/engine.c" \
        -L"$out" -lvoiced -Wl,-rpath,'$ORIGIN' -lpthread
fi
echo "build-engine: $out/libvoiced.so"
