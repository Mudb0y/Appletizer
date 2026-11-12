#!/usr/bin/env bash
# Prepare everything the Android build takes from Apple: both converted engines
# and each engine's built-in voices. Run inside `nix develop`, after
# fetch-firmware.sh.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware
assets=$root/android/app/src/main/assets/voices

"$root/scripts/build-engine.sh" android-arm64

rm -rf "$assets"
for pair in vauto:ios6 ve131:ios7 ve151:ios15; do
    gen=${pair%%:*} tts=$fw/${pair#*:}/TTSResources
    mkdir -p "$assets/$gen"
    for dir in "$tts"/*/; do
        name=$(basename "$dir")
        [ "$name" = Tones ] && continue
        cp -r "$dir" "$assets/$gen/$name"
    done
    (cd "$assets/$gen" && ls -d */ | tr -d / | grep -vx common) > "$assets/$gen/index.txt"
    echo "stage-android: $gen: $(wc -l < "$assets/$gen/index.txt") built-in voices"
done
echo "stage-android: $(du -sh "$assets" | cut -f1) of voices"
