#!/usr/bin/env python3
"""Find and package quality-gated PopSign training drafts for later signer review."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

try:
    from tools.fetch_popsign_training_sample import fetch_candidate
except ModuleNotFoundError:
    # Direct execution sets sys.path[0] to the tools directory.
    from fetch_popsign_training_sample import fetch_candidate


DEFAULT_SIGNS = ("hello", "please", "thankyou", "yes", "no", "wait")


def has_passing_motion(provenance_path: Path, gloss: str) -> bool:
    if not provenance_path.exists():
        return False
    document = json.loads(provenance_path.read_text(encoding="utf-8"))
    return any(
        motion.get("gloss") == gloss and motion.get("quality", {}).get("passed") is True
        for motion in document.get("motions", [])
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("signs", nargs="*", default=list(DEFAULT_SIGNS))
    parser.add_argument("--max-candidates", type=int, default=5)
    parser.add_argument("--max-members", type=int, default=200)
    parser.add_argument("--metadata", type=Path, default=Path("ml/frozen/metadata.json"))
    parser.add_argument("--source-dir", type=Path, default=Path(".cache/avatar-source"))
    parser.add_argument(
        "--motion-dir",
        type=Path,
        default=Path("android/app/src/main/assets/avatar_motions"),
    )
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.max_candidates < 1:
        raise SystemExit("--max-candidates must be at least one")

    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    vocabulary = set(metadata["vocabulary"])
    signs = list(dict.fromkeys(sign.lower() for sign in args.signs))
    unsupported = [sign for sign in signs if sign not in vocabulary]
    if unsupported:
        raise SystemExit(f"Not in the frozen vocabulary: {', '.join(unsupported)}")

    provenance = args.motion_dir / "provenance.json"
    reports = args.source_dir / "reports"
    failures: list[str] = []
    for sign in signs:
        if not args.force and has_passing_motion(provenance, sign):
            print(f"{sign}: keeping existing passing draft", flush=True)
            continue
        accepted = False
        for candidate_index in range(args.max_candidates):
            print(f"{sign}: evaluating candidate {candidate_index}", flush=True)
            try:
                source = fetch_candidate(sign, candidate_index, args.source_dir, args.max_members)
            except (RuntimeError, OSError) as error:
                print(f"{sign}: could not fetch candidate {candidate_index}: {error}", flush=True)
                continue
            report_file = reports / f"{sign}-{candidate_index}.json"
            command = [
                sys.executable,
                "tools/build_avatar_motion.py",
                source["local_file"],
                sign,
                "--output", str(args.motion_dir / f"{sign}.niamotion"),
                "--source-url", source["source_url"],
                "--source-member", source["source_member"],
                "--source-split", source["source_split"],
                "--provenance", str(provenance),
                "--report-file", str(report_file),
            ]
            result = subprocess.run(command, text=True, capture_output=True, check=False)
            if result.stdout:
                print(result.stdout.rstrip(), flush=True)
            if result.returncode == 0:
                print(f"{sign}: candidate {candidate_index} passed and remains unvalidated", flush=True)
                accepted = True
                break
            print(f"{sign}: candidate {candidate_index} rejected", flush=True)
        if not accepted:
            failures.append(sign)

    if failures:
        raise SystemExit(f"No quality-gated draft found for: {', '.join(failures)}")
    print("All requested signs have quality-gated drafts awaiting fluent-signer review")


if __name__ == "__main__":
    main()
