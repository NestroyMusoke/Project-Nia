#!/usr/bin/env python3
"""Fetch one sample from an official PopSign training tar via HTTP ranges."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import urllib.request
from pathlib import Path


BASE_URL = "https://signdata.cc.gatech.edu/data/popsign_v1_0/game/train"
BLOCK = 512


def fetch_range(url: str, start: int, end: int) -> bytes:
    request = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    expected = end - start + 1
    if len(data) != expected:
        raise RuntimeError(f"Range response was {len(data)} bytes; expected {expected}")
    return data


def parse_header(block: bytes) -> tuple[str, int]:
    if len(block) != BLOCK or not any(block):
        return "", 0
    name = block[0:100].split(b"\0", 1)[0].decode("utf-8")
    prefix = block[345:500].split(b"\0", 1)[0].decode("utf-8")
    if prefix:
        name = f"{prefix}/{name}"
    raw_size = block[124:136].strip(b"\0 ") or b"0"
    return name, int(raw_size, 8)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sign")
    parser.add_argument("--metadata", type=Path, default=Path("ml/frozen/metadata.json"))
    parser.add_argument("--output-dir", type=Path, default=Path(".cache/avatar-source"))
    parser.add_argument("--max-members", type=int, default=200)
    args = parser.parse_args()

    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    sign = args.sign.lower()
    if sign not in metadata["vocabulary"]:
        raise SystemExit(f"{sign!r} is not in the frozen 32-sign vocabulary")
    url = f"{BASE_URL}/{sign}.tar"

    offset = 0
    for _ in range(args.max_members):
        header = fetch_range(url, offset, offset + BLOCK - 1)
        name, size = parse_header(header)
        if not name:
            break
        match = re.search(r"\.(\d+)-", Path(name).name)
        signer = match.group(1) if match else None
        print(f"member={name} bytes={size} signer={signer or '-'}")
        if name.lower().endswith(".mp4"):
            payload_start = offset + BLOCK
            payload = fetch_range(url, payload_start, payload_start + size - 1)
            destination = args.output_dir / sign / Path(name).name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            print(json.dumps({
                "gloss": sign,
                "source_url": url,
                "source_member": name,
                "source_split": "game/train",
                "source_signer": int(signer),
                "source_sha256": digest,
                "local_file": str(destination),
            }, indent=2))
            return
        offset += BLOCK + ((size + BLOCK - 1) // BLOCK) * BLOCK

    raise SystemExit(f"No MP4 found in the first {args.max_members} members")


if __name__ == "__main__":
    main()
