#!/usr/bin/env bash
# Print what whisper hears in a WAV: an independent check that synthesis says
# the right words. Usage: transcribe.sh FILE.wav [LANG]  (LANG en uses base.en)
set -euo pipefail
wav=${1:?usage: transcribe.sh FILE.wav [LANG]}
lang=${2:-en}
root=$(cd "$(dirname "$0")/.." && pwd)
models=$root/work/models
model=$models/ggml-small.bin
[ "$lang" = en ] && model=$models/ggml-base.en.bin
tmp=$(mktemp --suffix=.wav)
trap 'rm -f "$tmp"' EXIT
nix shell nixpkgs#sox -c sox "$wav" -r 16000 -c 1 -b 16 "$tmp"
whisper-cli -m "$model" -l "$lang" -nt -np -f "$tmp" 2>/dev/null | sed '/^\s*$/d'
