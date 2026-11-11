#!/usr/bin/env bash
# Render TEXT with VOICE on the connected phone through the system TTS path,
# pull the WAV and print whisper's transcription. Needs a debug build installed.
# Usage: device-selftest.sh VOICE_ID WHISPER_LANG TEXT
set -euo pipefail
voice=${1:?usage: device-selftest.sh VOICE_ID WHISPER_LANG TEXT}
lang=${2:?}
text=${3:?}
root=$(cd "$(dirname "$0")/.." && pwd)
pkg=com.mudb0y.appletizer
out=$root/work/out/device
mkdir -p "$out"
file=$voice.wav

adb logcat -c
adb shell am start -W -n "$pkg/.SelfTestActivity" --es voice "$voice" --es text "$(printf %q "$text")" --es out "$file" > /dev/null
for _ in $(seq 120); do
    line=$(adb logcat -d -s AppletizerTest:* | grep -E 'DONE|FAIL' | tail -1 || true)
    [ -n "$line" ] && break
    sleep 0.5
done
echo "device-selftest: $voice: ${line:-no result after 60s}"
case $line in *DONE*) ;; *) adb logcat -d -s Appletizer:* AndroidRuntime:* DEBUG:* | tail -30; exit 1 ;; esac
adb pull "/sdcard/Android/data/$pkg/files/$file" "$out/$file" > /dev/null
"$root/scripts/transcribe.sh" "$out/$file" "$lang"
