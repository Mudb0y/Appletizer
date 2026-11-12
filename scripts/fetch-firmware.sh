#!/usr/bin/env bash
# Fetch the pieces the build takes from Apple into work/firmware, each checked
# against pinned hashes. Run inside `nix develop`; parts already in place stay.
#   ios7   iOS 7.1.2, iPhone6,1: voiced (VE 1.3.1) and its built-in voices
#   ios10  iOS 10.3.3, iPhone9,1: TTSSpeechBundle, the VE 1.5.1 engine
#   ios15  iOS 15.8.8, iPhone9,1: the last built-in voices VE 1.5.1 shipped with
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware
vs=System/Library/PrivateFrameworks/VoiceServices.framework

tree_hash() {
    (cd "$1" 2> /dev/null && find . -type f | LC_ALL=C sort | xargs -d '\n' sha256sum | sha256sum | cut -d' ' -f1)
}
file_hash() { { sha256sum < "$1"; } 2> /dev/null | cut -d' ' -f1; }

mkdir -p "$fw"
tmp=$(mktemp -d "$fw/.fetch.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

# rootfs URL MEMBER: stream the root filesystem image out of an IPSW.
rootfs() {
    "$M2E_PYTHON" -I "$root/scripts/zipmember.py" "$1" "$2" "$tmp/rootfs.dmg"
}

# place NAME: replace work/firmware/NAME with what was staged in $tmp/out.
place() {
    rm -rf "${fw:?}/$1"
    mv "$tmp/out" "$fw/$1"
    rm -rf "$tmp"/*
    echo "fetch-firmware: $fw/$1"
}

if [ "$(file_hash "$fw/ios7/voiced")" != c612358d842b23ffb0de478d19808ee29a9050cd45eb1ab362299e3d59843d5c ] ||
   [ "$(tree_hash "$fw/ios7/TTSResources")" != 5ba0f3034f7cb917c923d0b264dc745efcdd772eebfad03ce231f714dfbaeb10 ]; then
    echo "fetch-firmware: downloading iOS 7.1.2"
    rootfs https://secure-appldnld.apple.com/iOS7.1/031-4821.20140627.ZhtJx/iPhone6,1_7.1.2_11D257_Restore.ipsw 058-4438-009.dmg
    dmg extract "$tmp/rootfs.dmg" "$tmp/rootfs.img" \
        -k ff95d392a307dfd6bb4f6d9ad4ef5db2ab50e015cee5366b5195090a1b4f4c84a86f30f5 > /dev/null
    rm "$tmp/rootfs.dmg"
    # The image holds symlinks pointing outside it; -snl keeps them as links.
    7zz x -y -snl -bso0 -bsp0 -o"$tmp/x" "$tmp/rootfs.img" "Sochi11D257.N51OS/$vs/*" 2> /dev/null || true
    mkdir "$tmp/out"
    mv "$tmp/x/Sochi11D257.N51OS/$vs/Support/voiced" "$tmp/x/Sochi11D257.N51OS/$vs/TTSResources" "$tmp/out/"
    [ "$(file_hash "$tmp/out/voiced")" = c612358d842b23ffb0de478d19808ee29a9050cd45eb1ab362299e3d59843d5c ] &&
    [ "$(tree_hash "$tmp/out/TTSResources")" = 5ba0f3034f7cb917c923d0b264dc745efcdd772eebfad03ce231f714dfbaeb10 ] ||
        { echo "fetch-firmware: iOS 7.1.2 files do not match the pinned hashes" >&2; exit 1; }
    place ios7
fi

if [ "$(file_hash "$fw/ios10/TTSSpeechBundle.arm64")" != c9edde136b44bd4f27ee94627bf79bf8596f8970f90f108dd190a9626fa59f28 ]; then
    echo "fetch-firmware: downloading iOS 10.3.3"
    rootfs http://appldnld.apple.com/ios10.3.3/091-23304-20170719-CA9AC9AC-6977-11E7-9507-349100BA0AE3/iPhone_7_10.3.3_14G60_Restore.ipsw 058-75436-063.dmg
    b=System/Library/TTSPlugins/TTSSpeechBundle.speechbundle
    7zz x -y -bso0 -bsp0 -o"$tmp/x" "$tmp/rootfs.dmg" "$b/TTSSpeechBundle*" 2> /dev/null || true
    rm "$tmp/rootfs.dmg"
    "$M2E_PYTHON" -I "$root/scripts/decmpfs.py" "$tmp/x"
    mkdir "$tmp/out"
    llvm-lipo -thin arm64 "$tmp/x/$b/TTSSpeechBundle" -output "$tmp/out/TTSSpeechBundle.arm64"
    [ "$(file_hash "$tmp/out/TTSSpeechBundle.arm64")" = c9edde136b44bd4f27ee94627bf79bf8596f8970f90f108dd190a9626fa59f28 ] ||
        { echo "fetch-firmware: iOS 10.3.3 engine does not match the pinned hash" >&2; exit 1; }
    place ios10
fi

if [ "$(tree_hash "$fw/ios15/TTSResources")" != 46866091ae04b5ac745c910e8c5e14445014f788b7b52cbb75c1175dded28ead ]; then
    echo "fetch-firmware: downloading iOS 15.8.8"
    rootfs https://updates.cdn-apple.com/2025FallFCS/fullrestores/122-77496/774C7319-F63C-4C05-9C6C-49F5F0966540/iPhone_4.7_P3_15.8.8_19H422_Restore.ipsw 098-68844-067.dmg
    7zz x -y -bso0 -bsp0 -o"$tmp/x" "$tmp/rootfs.dmg" "$vs/TTSResources/*" 2> /dev/null || true
    rm "$tmp/rootfs.dmg"
    "$M2E_PYTHON" -I "$root/scripts/decmpfs.py" "$tmp/x"
    mkdir "$tmp/out"
    mv "$tmp/x/$vs/TTSResources" "$tmp/out/"
    [ "$(tree_hash "$tmp/out/TTSResources")" = 46866091ae04b5ac745c910e8c5e14445014f788b7b52cbb75c1175dded28ead ] ||
        { echo "fetch-firmware: iOS 15.8.8 voices do not match the pinned hash" >&2; exit 1; }
    place ios15
fi
