"""Flatten a 32-bit ARM Mach-O engine into the image the emulation bridge loads.
Usage: armv7-image.py VOICED OUT.bin

Layout, all little-endian:
  "ATZG", u32 1
  u32 segments; each: u32 vaddr, u32 vmsize, u32 filesize, then filesize bytes
  u32 imports;  each: u32 slot, u16 length, name
  u32 symbols;  each: u32 address (Thumb bit included), u16 length, name
The image runs at its preferred addresses, so it needs no rebasing; imports
are slots the bridge fills with its own functions."""
import struct
import sys

import lief

fat = lief.MachO.parse(sys.argv[1])
b = next(fat.at(i) for i in range(fat.size) if fat.at(i).header.cpu_type == lief.MachO.Header.CPU_TYPE.ARM)
out = [b"ATZG", struct.pack("<I", 1)]

segs = [s for s in b.segments if s.name != "__PAGEZERO" and s.virtual_size]
out.append(struct.pack("<I", len(segs)))
for s in segs:
    data = bytes(s.content)
    out.append(struct.pack("<III", s.virtual_address, s.virtual_size, len(data)) + data)

binds = [(x.address, x.symbol.name) for x in b.dyld_info.bindings if x.has_symbol]
out.append(struct.pack("<I", len(binds)))
for slot, name in binds:
    n = name.encode()
    out.append(struct.pack("<IH", slot, len(n)) + n)

# Thumb functions carry bit 0 in the symbol table's n_desc, not in the value.
api = []
for s in b.symbols:
    if s.name.startswith(("_vauto_tts", "_vmobile_tts")) and s.value:
        thumb = 1 if s.description & 0x8 else 0
        api.append((s.value | thumb, s.name))
out.append(struct.pack("<I", len(api)))
for addr, name in api:
    n = name.encode()
    out.append(struct.pack("<IH", addr, len(n)) + n)

open(sys.argv[2], "wb").write(b"".join(out))
print(f"armv7-image: {len(segs)} segments, {len(binds)} imports, {len(api)} entry points")
