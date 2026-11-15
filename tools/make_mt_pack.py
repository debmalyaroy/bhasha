#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Packs the IndicTrans2 files into the single file the app downloads.

  python tools/make_mt_pack.py <folder with the exported model> <out.bhmt>

Layout: the 8 bytes "BHMT0001", then for each part a 32-byte name padded with
zeros, its size as a 64-bit little-endian number, and its bytes. The engine
(engine/src/tr_mt.cpp) reads the parts in whatever order they come.
"""
import hashlib
import pathlib
import struct
import sys

PARTS = ("encoder.int8.onnx", "decoder.int8.onnx", "model.SRC", "src_spm_to_model_id.txt", "tgt_id_to_piece.txt")

src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
digest = hashlib.sha256()
with open(out, "wb") as f:
    def put(b):
        f.write(b)
        digest.update(b)
    put(b"BHMT0001")
    for name in PARTS:
        data = (src / name).read_bytes()
        put(name.encode().ljust(32, b"\0") + struct.pack("<Q", len(data)))
        put(data)
print(out, out.stat().st_size, "bytes, sha256", digest.hexdigest())
