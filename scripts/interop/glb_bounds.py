#!/usr/bin/env python3
"""Prints accessors[0].min/max of a GLB and optionally compares them with an expectation.

Usage: glb_bounds.py file.glb [--expect-min x y z] [--expect-max x y z] [--tol 1e-4]
No third-party dependencies; reads nothing but the file given.
"""
import json
import struct
import sys


def read_accessor0(path):
    with open(path, "rb") as f:
        data = f.read()
    magic, _version, _length = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67:
        raise SystemExit(f"{path}: not a GLB file")
    json_length, _json_type = struct.unpack_from("<II", data, 12)
    document = json.loads(data[20:20 + json_length].decode("utf-8"))
    return document["accessors"][0]


def main(argv):
    if not argv:
        raise SystemExit(__doc__)
    path = argv[0]
    tol = 1e-4
    expect = {}
    i = 1
    while i < len(argv):
        if argv[i] in ("--expect-min", "--expect-max"):
            expect[argv[i][9:]] = [float(v) for v in argv[i + 1:i + 4]]
            i += 4
        elif argv[i] == "--tol":
            tol = float(argv[i + 1])
            i += 2
        else:
            raise SystemExit(f"unknown argument {argv[i]}")
    accessor = read_accessor0(path)
    print("min", accessor["min"], "max", accessor["max"], "vertices", accessor["count"])
    ok = True
    for key, wanted in expect.items():
        actual = accessor[key]
        good = all(abs(a - w) <= tol for a, w in zip(actual, wanted))
        print(("OK   " if good else "FAIL ") + key, actual, "expected", wanted)
        ok = ok and good
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
