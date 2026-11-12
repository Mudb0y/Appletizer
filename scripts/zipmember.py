#!/usr/bin/env python3
"""Stream one member out of a remote zip (an IPSW) with HTTP range requests.

zipmember.py URL MEMBER OUT
"""
import struct
import sys
import time
import urllib.request
import zlib

url, member, out = sys.argv[1:4]


def fetch(start, end=None):
    rng = f"bytes={start}-" if end is None else f"bytes={start}-{end}"
    return urllib.request.urlopen(urllib.request.Request(url, headers={"Range": rng}), timeout=120)


def read(start, end):
    with fetch(start, end) as r:
        return r.read()


with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=120) as r:
    total = int(r.headers["Content-Length"])
tail = read(max(0, total - 70000), total - 1)
eocd = tail.rfind(b"PK\x05\x06")
cd_size, cd_off = struct.unpack_from("<II", tail, eocd + 12)
z64 = tail.rfind(b"PK\x06\x06")
if cd_off == 0xFFFFFFFF and z64 != -1:
    cd_size, cd_off = struct.unpack_from("<QQ", tail, z64 + 40)
cd = read(cd_off, cd_off + cd_size - 1)

found = None
p = 0
while cd[p:p + 4] == b"PK\x01\x02":
    method, = struct.unpack_from("<H", cd, p + 10)
    crc, csize, usize = struct.unpack_from("<III", cd, p + 16)
    nl, el, cl = struct.unpack_from("<HHH", cd, p + 28)
    loff, = struct.unpack_from("<I", cd, p + 42)
    name = cd[p + 46:p + 46 + nl].decode("utf-8", "replace")
    extra = cd[p + 46 + nl:p + 46 + nl + el]
    q = 0
    while q + 4 <= len(extra):
        hid, hl = struct.unpack_from("<HH", extra, q)
        if hid == 1:
            k = q + 4
            if usize == 0xFFFFFFFF:
                usize, = struct.unpack_from("<Q", extra, k); k += 8
            if csize == 0xFFFFFFFF:
                csize, = struct.unpack_from("<Q", extra, k); k += 8
            if loff == 0xFFFFFFFF:
                loff, = struct.unpack_from("<Q", extra, k)
        q += 4 + hl
    if name == member:
        found = (method, crc, csize, usize, loff)
        break
    p += 46 + nl + el + cl
if not found:
    sys.exit(f"zipmember: no {member} in {url}")
method, crc, csize, usize, loff = found
if method not in (0, 8):
    sys.exit(f"zipmember: {member} uses compression method {method}")

nl, el = struct.unpack_from("<HH", read(loff, loff + 29), 26)
start = loff + 30 + nl + el
inflate = zlib.decompressobj(-15) if method == 8 else None
got, written, check = 0, 0, 0
with open(out, "wb") as f:
    tries = 0
    while got < csize:
        try:
            with fetch(start + got, start + csize - 1) as r:
                while True:
                    block = r.read(1 << 20)
                    if not block:
                        break
                    got += len(block)
                    data = inflate.decompress(block) if inflate else block
                    check = zlib.crc32(data, check)
                    written += len(data)
                    f.write(data)
        except OSError as e:
            tries += 1
            if tries > 8:
                sys.exit(f"zipmember: {member}: {e}")
            time.sleep(5 * tries)
    if inflate:
        data = inflate.flush()
        check = zlib.crc32(data, check)
        written += len(data)
        f.write(data)
if written != usize or check != crc:
    sys.exit(f"zipmember: {member} came out {written} bytes, CRC {check:08x}; expected {usize}, {crc:08x}")
