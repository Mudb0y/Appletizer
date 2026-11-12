#!/usr/bin/env bash
# Build both engines for arm64 Linux and have every built-in voice speak on its
# engine under qemu, plus the texts that once failed whole utterances. Run
# inside `nix develop`, after fetch-firmware.sh.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware
build=$root/work/build/linux-arm64
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

"$root/scripts/build-engine.sh" linux-arm64 > /dev/null

# vesay ENGINE VOICES LANG TEXT: run vesay, printing its output.
vesay() {
    LD_LIBRARY_PATH=$AARCH64_LIBCXX qemu-aarch64 -L "$AARCH64_SYSROOT" "$build/vesay" \
        -e "$build/$1" -v "$2/$3" -c "$2/common" -o "$out/out.wav" "$4" 2>&1
}

failed=0 voices=0
for pair in "libve131.so:$fw/ios7/TTSResources" "libve151.so:$fw/ios15/TTSResources"; do
    engine=${pair%%:*} tts=${pair#*:}
    for dir in "$tts"/*/; do
        lang=$(basename "$dir")
        case $lang in common|Tones) continue ;; esac
        voices=$((voices + 1))
        log=$(vesay "$engine" "$tts" "$lang" "1, 2, 3.") || true
        n=$(sed -n 's/^vesay: \([0-9]*\) samples.*/\1/p' <<< "$log")
        [ "${n:-0}" -gt 0 ] || { echo "smoke-test: $engine $lang: ${log##*$'\n'}" >&2; failed=1; }
    done
    # vesay exits nonzero on silence, so judge empty text by the engine's verdict.
    if vesay "$engine" "$tts" en-US "" | grep -q "synthesis failed"; then
        echo "smoke-test: $engine: empty text failed" >&2
        failed=1
    fi
    if ! vesay "$engine" "$tts" en-US "Party 🎉 tonight." | grep -q " samples"; then
        echo "smoke-test: $engine: emoji text failed" >&2
        failed=1
    fi
done
[ "$failed" = 0 ] || exit 1
echo "smoke-test: $voices voices spoke on two engines, empty and emoji text ok"
