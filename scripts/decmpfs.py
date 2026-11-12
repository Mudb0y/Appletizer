#!/usr/bin/env python3
"""Inflate APFS/HFS transparently compressed files that 7-Zip extracted as empty files.

7-Zip writes FILE (0 bytes), FILE:com.apple.decmpfs and FILE:com.apple.ResourceFork.
decmpfs.py ROOT [SUBPATH...]  -> rewrites each FILE with its decompressed content and
removes the two side files. Handles zlib (3/4), LZVN (7/8), LZFSE (11/12) and raw (9/10).
"""
import ctypes
import ctypes.util
import os
import struct
import sys
import zlib
from concurrent.futures import ProcessPoolExecutor

LIB = os.environ.get('LIBLZFSE', 'liblzfse.so')
lz = ctypes.CDLL(LIB)
lz.lzfse_decode_buffer.restype = ctypes.c_size_t
lz.lzfse_decode_buffer.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p]


def lzfse_stream(src, raw_size):
    dst = ctypes.create_string_buffer(raw_size + 4096)
    n = lz.lzfse_decode_buffer(dst, len(dst), src, len(src), None)
    return dst.raw[:n]


def lzvn(src, raw_size):
    hdr = b'bvxn' + struct.pack('<II', raw_size, len(src))
    return lzfse_stream(hdr + src + b'bvx$', raw_size)


def chunk(c, raw_size, kind):
    if not c:
        return b''
    if kind == 'zlib':
        if c[0] & 0x0f == 0x0f:
            return c[1:]
        return zlib.decompress(c)
    if c[0] == 0x06:
        return c[1:]
    if kind == 'lzfse' or c[:3] == b'bvx':
        return lzfse_stream(c, raw_size)
    return lzvn(c, raw_size)


def table_fork(fork, size, kind):
    first = struct.unpack_from('<I', fork, 0)[0]
    n = first // 4 - 1
    offs = struct.unpack_from(f'<{n + 1}I', fork, 0)
    out = []
    for i in range(n):
        raw = min(0x10000, size - i * 0x10000)
        out.append(chunk(fork[offs[i]:offs[i + 1]], raw, kind))
    return b''.join(out)


def zlib_fork(fork, size):
    data_off = struct.unpack_from('>I', fork, 0)[0]
    base = data_off + 4
    n = struct.unpack_from('<I', fork, base)[0]
    out = []
    for i in range(n):
        o, l = struct.unpack_from('<II', fork, base + 4 + 8 * i)
        out.append(chunk(fork[base + o:base + o + l], min(0x10000, size - i * 0x10000), 'zlib'))
    return b''.join(out)


def inflate(path):
    attr = path + ':com.apple.decmpfs'
    forkp = path + ':com.apple.ResourceFork'
    try:
        a = open(attr, 'rb').read()
        magic, typ, size = struct.unpack_from('<IIQ', a, 0)
        body = a[16:]
        fork = open(forkp, 'rb').read() if os.path.exists(forkp) else b''
        if typ == 1:
            data = body
        elif typ == 3:
            data = chunk(body, size, 'zlib')
        elif typ == 4:
            data = zlib_fork(fork, size)
        elif typ == 7:
            data = chunk(body, size, 'lzvn')
        elif typ == 8:
            data = table_fork(fork, size, 'lzvn')
        elif typ == 9:
            data = body[1:] if body else b''
        elif typ == 10:
            data = table_fork(fork, size, 'raw')
        elif typ == 11:
            data = chunk(body, size, 'lzfse')
        elif typ == 12:
            data = table_fork(fork, size, 'lzfse')
        else:
            return f'unsupported type {typ}: {path}'
        if len(data) != size:
            return f'size mismatch {len(data)} != {size} (type {typ}): {path}'
        st = os.stat(path)
        with open(path, 'wb') as f:
            f.write(data)
        os.utime(path, (st.st_atime, st.st_mtime))
        os.remove(attr)
        if fork:
            os.remove(forkp)
        return None
    except Exception as e:
        return f'error {e!r}: {path}'


def main():
    root = sys.argv[1]
    subs = sys.argv[2:] or ['']
    todo = []
    for sub in subs:
        for d, _, files in os.walk(os.path.join(root, sub)):
            for fn in files:
                if fn.endswith(':com.apple.decmpfs'):
                    todo.append(os.path.join(d, fn[:-len(':com.apple.decmpfs')]))
    bad = 0
    with ProcessPoolExecutor(16) as ex:
        for r in ex.map(inflate, todo, chunksize=64):
            if r:
                bad += 1
                if bad <= 20:
                    print(r)
    print(f'inflated {len(todo) - bad} of {len(todo)} files under {root}')


if __name__ == '__main__':
    main()
