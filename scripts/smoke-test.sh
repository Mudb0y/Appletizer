#!/usr/bin/env bash
# Build the engine for arm64 Linux and have every built-in voice speak under
# qemu, plus the texts that once failed whole utterances. Run inside
# `nix develop`, after fetch-firmware.sh.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
tts=${TTS_RESOURCES:-$root/work/ipsw/iPhone6,1_7.1.2/rootfs/Sochi11D257.N51OS/System/Library/PrivateFrameworks/VoiceServices.framework/TTSResources}
build=$root/work/build/linux-arm64
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

"$root/scripts/build-engine.sh" linux-arm64 > /dev/null

# Prints the sample count, or fails with vesay's last line.
say() {
    local log
    log=$(qemu-aarch64 -L "$AARCH64_SYSROOT" "$build/vesay" -v "$tts/$1" -c "$tts/common" \
        -o "$out/out.wav" "$2" 2>&1) || { echo "${log##*$'\n'}"; return 1; }
    sed -n 's/^vesay: \([0-9]*\) samples.*/\1/p' <<< "$log"
}

failed=0 voices=0
for dir in "$tts"/*/; do
    lang=$(basename "$dir")
    case $lang in common|Tones) continue ;; esac
    voices=$((voices + 1))
    n=$(say "$lang" "1, 2, 3.") || { echo "smoke-test: $lang: $n" >&2; failed=1; continue; }
    [ "${n:-0}" -gt 0 ] || { echo "smoke-test: $lang: no audio" >&2; failed=1; }
done
# vesay exits nonzero on silence, so judge empty text by the engine's verdict.
if qemu-aarch64 -L "$AARCH64_SYSROOT" "$build/vesay" -v "$tts/en-US" -c "$tts/common" \
    -o "$out/out.wav" "" 2>&1 | grep -q "synthesis failed"; then
    echo "smoke-test: empty text failed" >&2
    failed=1
fi
n=$(say en-US "Party 🎉 tonight.") || { echo "smoke-test: emoji text failed: $n" >&2; failed=1; }
[ "$failed" = 0 ] || exit 1
echo "smoke-test: $voices voices spoke, empty and emoji text ok"
