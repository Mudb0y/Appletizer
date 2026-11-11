"""Write the iOS 7.1 premium voice entries of Apple's MobileAsset catalog as
the JSON the app reads. Usage: make-catalog.py CATALOG.xml OUT.json"""
import json
import plistlib
import sys

IOS7_FORMAT = "5"

assets = plistlib.load(open(sys.argv[1], "rb"))["Assets"]
# The iOS 9/10 entries are the only ones carrying voice names, and iOS 7's
# premium voices kept theirs.
names = {a["Languages"][0]: a["Name"].title() for a in assets if a.get("Name")}
voices = []
for a in assets:
    if a.get("FormatVersion") != IOS7_FORMAT:
        continue
    lang = a["Language"]
    voices.append({
        "language": lang,
        "name": names.get(lang, ""),
        "url": a["__BaseURL"] + a["__RelativePath"],
        "bytes": a["_DownloadSize"],
        "unpacked": a["_UnarchivedSize"],
        "sha1": a["_Measurement"].hex(),
    })
voices.sort(key=lambda v: v["language"])
json.dump({"format": IOS7_FORMAT, "voices": voices}, open(sys.argv[2], "w"), indent=1)
print(f"make-catalog: {len(voices)} voices")
