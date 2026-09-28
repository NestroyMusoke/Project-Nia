#!/usr/bin/env python3
"""Create Nia's warm dark-skin, dark-clothing VRM appearance variant."""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path


JSON_CHUNK = 0x4E4F534A
SKIN_MATERIALS = {"Alicia_body", "Alicia_face"}
SKIN_TINT = [0.43, 0.25, 0.16, 1.0]
CLOTHING_MATERIALS = {"Alicia_body_wear", "Alicia_wear"}
CLOTHING_TINT = [0.07, 0.10, 0.16, 1.0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()

    data = args.source.read_bytes()
    magic, version, total_length = struct.unpack_from("<4sII", data)
    if magic != b"glTF" or version != 2 or total_length != len(data):
        raise SystemExit("Expected a valid GLB/VRM 2.0 container")

    chunks: list[tuple[int, bytes]] = []
    cursor = 12
    document = None
    while cursor < len(data):
        length, kind = struct.unpack_from("<II", data, cursor)
        cursor += 8
        payload = data[cursor:cursor + length]
        cursor += length
        if kind == JSON_CHUNK:
            document = json.loads(payload.rstrip(b" \0"))
        else:
            chunks.append((kind, payload))
    if document is None:
        raise SystemExit("VRM contains no JSON chunk")

    changed = set()
    for material in document.get("materials", []):
        if material.get("name") in SKIN_MATERIALS:
            material.setdefault("pbrMetallicRoughness", {})["baseColorFactor"] = SKIN_TINT
            changed.add(material["name"])
        elif material.get("name") in CLOTHING_MATERIALS:
            material.setdefault("pbrMetallicRoughness", {})["baseColorFactor"] = CLOTHING_TINT
            changed.add(material["name"])

    for material in document.get("extensions", {}).get("VRM", {}).get("materialProperties", []):
        if material.get("name") in SKIN_MATERIALS:
            material.setdefault("vectorProperties", {})["_Color"] = SKIN_TINT
        elif material.get("name") in CLOTHING_MATERIALS:
            material.setdefault("vectorProperties", {})["_Color"] = CLOTHING_TINT

    expected = SKIN_MATERIALS | CLOTHING_MATERIALS
    if changed != expected:
        raise SystemExit(f"Missing expected appearance materials: {sorted(expected - changed)}")

    encoded = json.dumps(document, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    encoded += b" " * ((-len(encoded)) % 4)
    output_chunks = [(JSON_CHUNK, encoded), *chunks]
    output_length = 12 + sum(8 + len(payload) for _, payload in output_chunks)
    output = bytearray(struct.pack("<4sII", b"glTF", 2, output_length))
    for kind, payload in output_chunks:
        output.extend(struct.pack("<II", len(payload), kind))
        output.extend(payload)
    args.destination.write_bytes(output)
    print(f"Wrote {args.destination} with appearance materials: {', '.join(sorted(changed))}")


if __name__ == "__main__":
    main()
