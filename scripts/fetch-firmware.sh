#!/usr/bin/env bash
# Fetch the iOS 7.1.2 pieces the build takes from Apple: voiced and the
# built-in voices under VoiceServices.framework. Run inside `nix develop`.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
dest=$root/work/ipsw/iPhone6,1_7.1.2
volume=Sochi11D257.N51OS
framework=System/Library/PrivateFrameworks/VoiceServices.framework
url=https://secure-appldnld.apple.com/iOS7.1/031-4821.20140627.ZhtJx/iPhone6,1_7.1.2_11D257_Restore.ipsw
ipsw_sha1=d4b2280d210ec61c14a8c82ddde50d8da3b40e31
rootfs=058-4438-009.dmg
rootfs_key=ff95d392a307dfd6bb4f6d9ad4ef5db2ab50e015cee5366b5195090a1b4f4c84a86f30f5
voiced_sha256=c612358d842b23ffb0de478d19808ee29a9050cd45eb1ab362299e3d59843d5c
voices_sha256=5ba0f3034f7cb917c923d0b264dc745efcdd772eebfad03ce231f714dfbaeb10

verify() {
    local fw=$1/$volume/$framework
    [ -f "$fw/Support/voiced" ] && [ -d "$fw/TTSResources" ] || return 1
    [ "$(sha256sum < "$fw/Support/voiced" | cut -d' ' -f1)" = "$voiced_sha256" ] || return 1
    [ "$(cd "$fw/TTSResources" && find . -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -d' ' -f1)" = "$voices_sha256" ]
}

if verify "$dest/rootfs"; then
    echo "fetch-firmware: iOS 7.1.2 already in place"
    exit 0
fi

mkdir -p "$root/work"
tmp=$(mktemp -d "$root/work/fetch.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
echo "fetch-firmware: downloading iOS 7.1.2 for iPhone6,1"
curl -fsSL --retry 5 --retry-all-errors -o "$tmp/fw.ipsw" "$url"
[ "$(sha1sum < "$tmp/fw.ipsw" | cut -d' ' -f1)" = "$ipsw_sha1" ] || { echo "fetch-firmware: IPSW checksum mismatch" >&2; exit 1; }
7zz e -y -bso0 -bsp0 -o"$tmp" "$tmp/fw.ipsw" "$rootfs"
rm "$tmp/fw.ipsw"
dmg extract "$tmp/$rootfs" "$tmp/rootfs.img" -k "$rootfs_key" > /dev/null
rm "$tmp/$rootfs"
# The image holds symlinks pointing outside it; -snl keeps them as links.
7zz x -y -snl -bso0 -bsp0 -o"$tmp/rootfs" "$tmp/rootfs.img" "$volume/$framework/*" 2> /dev/null || true
rm "$tmp/rootfs.img"
verify "$tmp/rootfs" || { echo "fetch-firmware: extracted files do not match the pinned checksums" >&2; exit 1; }
mkdir -p "$dest/rootfs/$volume/$(dirname "$framework")"
rm -rf "$dest/rootfs/$volume/$framework"
mv "$tmp/rootfs/$volume/$framework" "$dest/rootfs/$volume/$framework"
echo "fetch-firmware: $dest/rootfs/$volume/$framework"
