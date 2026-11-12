"""Write the downloadable voices from Apple's MobileAsset catalogs as the JSON
the app reads, each tagged with the engine its data runs on.
Usage: make-catalog.py VOCALIZER.xml COMBINED.xml OUT.json

  ve131  VoiceServicesVocalizerVoice FormatVersion 5: iOS 7.1's premium voices
  ve151  FormatVersion 7, compatibility 6: what iOS 9.3 to 15 download
         CombinedVocalizerVoices: the further voices iOS 10 to 15 offer

Names come from each pack's own broker header, read with HTTP range requests:
the catalogs name only the newer entries, and some voices were renamed or
replaced between iOS versions."""
import json
import plistlib
import re
import struct
import sys
import time
import urllib.request
import zlib


def get(url, start, end):
    req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except OSError:
            if attempt == 4:
                raise
            time.sleep(2 ** attempt)


def headers(url, size):
    """Every broker header inside a remote zip, as {voice folder: text}. The
    folder is the member's path below AssetData/<pack>/, which is how the app
    installs it: "" for a one-voice pack, "compact" or "premium" for the
    two-voice packs."""
    tail = get(url, max(0, size - 65558), size - 1)
    eocd = tail.rfind(b"PK\x05\x06")
    cd_size, cd_off = struct.unpack_from("<II", tail, eocd + 12)
    cd = get(url, cd_off, cd_off + cd_size - 1)
    found = {}
    p = 0
    while cd[p:p + 4] == b"PK\x01\x02":
        method, = struct.unpack_from("<H", cd, p + 10)
        csize, = struct.unpack_from("<I", cd, p + 20)
        nl, el, cl = struct.unpack_from("<HHH", cd, p + 28)
        loff, = struct.unpack_from("<I", cd, p + 42)
        name = cd[p + 46:p + 46 + nl].decode()
        parts = name.split("/")
        if parts[0] == "AssetData" and parts[-1] in ("broker.hdr.asset", "broker.hdr"):
            lnl, lel = struct.unpack_from("<HH", get(url, loff, loff + 29), 26)
            start = loff + 30 + lnl + lel
            data = get(url, start, start + csize - 1)
            text = (zlib.decompress(data, -15) if method == 8 else data).decode("utf-8", "replace")
            found["/".join(parts[2:-1])] = text
        p += 46 + nl + el + cl
    if not found:
        raise SystemExit(f"make-catalog: no broker header in {url}")
    return found


def tag(xml, name):
    m = re.search(rf"<{name}>([^<]*)</{name}>", xml)
    return m.group(1) if m else ""


def entry(engine, a, lang):
    url = a["__BaseURL"] + a["__RelativePath"]
    voices = []
    for folder, hdr in sorted(headers(url, a["_DownloadSize"]).items()):
        model = tag(hdr, "voicemodel")
        voices.append({
            "folder": folder,
            "name": tag(hdr, "voice"),
            "model": model,
            "footprint": "compact" if model.startswith("bet") else "premium",
        })
    return {
        "engine": engine,
        "language": lang,
        "name": voices[-1]["name"],
        "voices": voices,
        "url": url,
        "bytes": a["_DownloadSize"],
        "unpacked": a["_UnarchivedSize"],
        "sha1": a["_Measurement"].hex(),
    }


vocalizer = plistlib.load(open(sys.argv[1], "rb"))["Assets"]
combined = plistlib.load(open(sys.argv[2], "rb"))["Assets"]
packs = []
for a in vocalizer:
    if a.get("FormatVersion") == "5":
        packs.append(entry("ve131", a, a["Language"]))
    elif a.get("FormatVersion") == "7" and str(a.get("_CompatibilityVersion")) == "6":
        packs.append(entry("ve151", a, a["Languages"][0]))
for a in combined:
    packs.append(entry("ve151", a, a["Languages"][0]))
packs.sort(key=lambda p: (p["language"], p["engine"], p["name"]))
json.dump({"packs": packs}, open(sys.argv[3], "w"), indent=1, ensure_ascii=False)
print(f"make-catalog: {len(packs)} packs")
