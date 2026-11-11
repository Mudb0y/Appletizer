#!/usr/bin/env bash
# Prepare everything the Android build takes from Apple: the converted engine
# and the built-in compact voices from the 7.1.2 firmware. Run inside
# `nix develop`, after the firmware is extracted under work/.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
tts=${TTS_RESOURCES:-$root/work/ipsw/iPhone6,1_7.1.2/rootfs/Sochi11D257.N51OS/System/Library/PrivateFrameworks/VoiceServices.framework/TTSResources}
assets=$root/android/app/src/main/assets

"$root/scripts/build-engine.sh" android-arm64

echo "stage-android: copying compact voices"
rm -rf "$assets/voices"
mkdir -p "$assets/voices"
for dir in "$tts"/*/; do
    name=$(basename "$dir")
    [ "$name" = Tones ] && continue
    cp -r "$dir" "$assets/voices/$name"
done
(cd "$assets/voices" && ls -d */ | tr -d / | grep -vx common) > "$assets/voices/index.txt.tmp"
mv "$assets/voices/index.txt.tmp" "$assets/voices/index.txt"
echo "stage-android: $(wc -l < "$assets/voices/index.txt") languages, $(du -sh "$assets/voices" | cut -f1)"
