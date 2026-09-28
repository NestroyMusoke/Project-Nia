#!/usr/bin/env python3
"""Extract, normalize, smooth, and package a PopSign training motion."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np


FILE_VERSION = 5
OUTPUT_FRAMES = 64
POSE_COUNT = 33
HAND_COUNT = 21
FACE_COUNT = 478


def landmarks(value, count: int) -> np.ndarray:
    output = np.full((count, 3), np.nan, dtype=np.float32)
    for index, point in enumerate(value[:count]):
        output[index] = point.x, point.y, point.z
    return output


def face_pose(face: np.ndarray) -> np.ndarray:
    if not np.isfinite(face[[33, 263], :2]).all():
        return np.array([0.0, 0.5, 1.0], dtype=np.float32)

    def distance(first: int, second: int) -> float:
        points = face[[first, second], :2]
        return float(np.linalg.norm(points[0] - points[1])) if np.isfinite(points).all() else math.nan

    width = max(distance(33, 263), 0.01)
    mouth_distance = distance(13, 14)
    eye_distances = [distance(159, 145), distance(386, 374)]
    brow_distances = [distance(105, 159), distance(334, 386)]
    mouth = np.clip((0.0 if math.isnan(mouth_distance) else mouth_distance) / width * 8.0, 0.0, 1.0)
    eye_values = [value for value in eye_distances if not math.isnan(value)]
    eye = np.clip(np.mean(eye_values) / width * 12.0, 0.15, 1.0) if eye_values else 1.0
    brow_values = [value for value in brow_distances if not math.isnan(value)]
    brow_ratio = np.mean(brow_values) / width if brow_values else 0.16
    brow = np.clip((brow_ratio - 0.08) * 6.0, 0.0, 1.0)
    return np.array([mouth, brow, eye], dtype=np.float32)


def normalize_group(group: np.ndarray, pose: np.ndarray) -> np.ndarray:
    left, right = pose[11], pose[12]
    if np.isfinite(left).all() and np.isfinite(right).all():
        dx = left[0] - right[0]
        dy = right[1] - left[1]
        scale = max(math.hypot(dx, dy), 0.05)
        anchor = (left + right) / 2.0
        shoulder_x, shoulder_y = dx / scale, dy / scale
    else:
        anchor = np.array([0.5, 0.42, 0.0], dtype=np.float32)
        scale, shoulder_x, shoulder_y = 0.28, 1.0, 0.0

    x = group[:, 0] - anchor[0]
    y = anchor[1] - group[:, 1]
    output = np.empty_like(group)
    output[:, 0] = np.clip((x * shoulder_x + y * shoulder_y) / scale, -2.2, 2.2)
    output[:, 1] = np.clip(0.55 + (-x * shoulder_y + y * shoulder_x) / scale, -1.9, 2.1)
    output[:, 2] = np.clip(-(group[:, 2] - anchor[2]) / scale, -0.9, 0.9)
    output[~np.isfinite(group).all(axis=1)] = np.nan
    return output


def fill_and_smooth(values: np.ndarray, minimum_coverage: float = 0.25) -> np.ndarray:
    result = values.copy()
    frame_axis = np.arange(len(result), dtype=np.float32)
    for point in range(result.shape[1]):
        valid_point = np.isfinite(result[:, point]).all(axis=1)
        if valid_point.mean() < minimum_coverage:
            result[:, point] = np.nan
            continue
        for coordinate in range(3):
            series = result[:, point, coordinate]
            valid = np.isfinite(series)
            series[:] = np.interp(frame_axis, frame_axis[valid], series[valid])
            padded = np.pad(series, (2, 2), mode="edge")
            series[:] = np.convolve(padded, np.array([1, 2, 3, 2, 1]) / 9.0, mode="valid")
    return result


def resample(values: np.ndarray, count: int = OUTPUT_FRAMES) -> np.ndarray:
    source = np.arange(len(values), dtype=np.float32)
    target = np.linspace(0, len(values) - 1, count, dtype=np.float32)
    output = np.full((count, *values.shape[1:]), np.nan, dtype=np.float32)
    flat_source = values.reshape(len(values), -1)
    flat_output = output.reshape(count, -1)
    for column in range(flat_source.shape[1]):
        series = flat_source[:, column]
        valid = np.isfinite(series)
        if valid.any():
            flat_output[:, column] = np.interp(target, source[valid], series[valid])
    return output


def write_java_utf(output, value: str) -> None:
    encoded = value.encode("utf-8")
    output.write(struct.pack(">H", len(encoded)))
    output.write(encoded)


def write_motion(path: Path, gloss: str, fps: int, pose, left, right, faces) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as output:
        output.write(struct.pack(">i", FILE_VERSION))
        write_java_utf(output, gloss)
        output.write(struct.pack(">?ii", False, fps, OUTPUT_FRAMES))
        for frame in range(OUTPUT_FRAMES):
            for group in (pose[frame], left[frame], right[frame]):
                for point in group:
                    output.write(struct.pack(">fff", *map(float, point)))
            output.write(struct.pack(">fff", *map(float, faces[frame])))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("gloss")
    parser.add_argument("--model", type=Path, default=Path("android/app/src/main/assets/holistic_landmarker.task"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output_path = args.output or Path(f"android/app/src/main/assets/avatar_motions/{args.gloss.lower()}.niamotion")

    capture = cv2.VideoCapture(str(args.input))
    source_fps = capture.get(cv2.CAP_PROP_FPS) or 30.0
    source_frames = []
    options = mp.tasks.vision.HolisticLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(args.model)),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        min_face_detection_confidence=0.35,
        min_face_landmarks_confidence=0.35,
        min_pose_detection_confidence=0.35,
        min_pose_landmarks_confidence=0.35,
        min_hand_landmarks_confidence=0.35,
    )
    with mp.tasks.vision.HolisticLandmarker.create_from_options(options) as detector:
        index = 0
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            scale = min(1.0, 960.0 / max(frame.shape[:2]))
            if scale < 1.0:
                frame = cv2.resize(frame, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            image = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb))
            result = detector.detect_for_video(image, round(index * 1000.0 / source_fps))
            source_frames.append((
                landmarks(result.pose_landmarks, POSE_COUNT),
                landmarks(result.left_hand_landmarks, HAND_COUNT),
                landmarks(result.right_hand_landmarks, HAND_COUNT),
                landmarks(result.face_landmarks, FACE_COUNT),
            ))
            index += 1
    capture.release()
    if len(source_frames) < 2:
        raise SystemExit("Video produced fewer than two frames")

    pose_raw = np.stack([frame[0] for frame in source_frames])
    left_raw = np.stack([frame[1] for frame in source_frames])
    right_raw = np.stack([frame[2] for frame in source_frames])
    face_values = np.stack([face_pose(frame[3]) for frame in source_frames])
    pose = np.stack([normalize_group(pose_raw[i], pose_raw[i]) for i in range(len(source_frames))])
    left = np.stack([normalize_group(left_raw[i], pose_raw[i]) for i in range(len(source_frames))])
    right = np.stack([normalize_group(right_raw[i], pose_raw[i]) for i in range(len(source_frames))])
    pose, left, right = map(fill_and_smooth, (pose, left, right))
    pose, left, right, face_values = map(resample, (pose, left, right, face_values[:, None, :]))
    face_values = face_values[:, 0, :]

    duration = len(source_frames) / source_fps
    output_fps = max(1, min(120, round(OUTPUT_FRAMES / duration)))
    write_motion(output_path, args.gloss.lower(), output_fps, pose, left, right, face_values)
    source_digest = hashlib.sha256(args.input.read_bytes()).hexdigest()
    signer_match = re.search(r"\.(\d+)-", args.input.name)
    report = {
        "gloss": args.gloss.lower(),
        "source_member": args.input.name,
        "source_split": "game/train",
        "source_signer": int(signer_match.group(1)) if signer_match else None,
        "source_sha256": source_digest,
        "source_frames": len(source_frames),
        "source_fps": source_fps,
        "output_frames": OUTPUT_FRAMES,
        "output_fps": output_fps,
        "pose_coverage": float(np.isfinite(pose_raw).all(axis=2).mean()),
        "left_hand_coverage": float(np.isfinite(left_raw).all(axis=2).mean()),
        "right_hand_coverage": float(np.isfinite(right_raw).all(axis=2).mean()),
        "output": str(output_path),
        "signer_validated": False,
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
