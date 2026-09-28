import json

from tools.build_avatar_motion import evaluate_motion_quality, update_rejected_provenance


def test_motion_quality_accepts_well_tracked_candidate():
    quality = evaluate_motion_quality(
        source_frames=60,
        source_fps=30.0,
        pose_coverage=0.95,
        left_hand_coverage=0.10,
        right_hand_coverage=0.85,
        face_coverage=0.90,
    )
    assert quality["passed"] is True
    assert quality["issues"] == []


def test_motion_quality_rejects_missing_primary_hand():
    quality = evaluate_motion_quality(
        source_frames=60,
        source_fps=30.0,
        pose_coverage=1.0,
        left_hand_coverage=0.0,
        right_hand_coverage=0.35,
        face_coverage=1.0,
    )
    assert quality["passed"] is False
    assert any("hand coverage" in issue for issue in quality["issues"])


def test_rejected_candidate_provenance_is_auditable(tmp_path):
    quality = evaluate_motion_quality(60, 30.0, 1.0, 0.0, 0.35, 1.0)
    report = {
        "gloss": "hello",
        "source_url": "https://example.test/hello.tar",
        "source_member": "hello/example.mp4",
        "source_split": "game/train",
        "source_signer": 1,
        "source_sha256": "a" * 64,
        "source_frames": 60,
        "source_fps": 30.0,
        "pose_coverage": 1.0,
        "left_hand_coverage": 0.0,
        "right_hand_coverage": 0.35,
        "face_coverage": 1.0,
        "quality": quality,
    }
    path = tmp_path / "provenance.json"
    update_rejected_provenance(path, report)
    saved = json.loads(path.read_text(encoding="utf-8"))
    assert saved["motions"] == []
    assert saved["rejected_candidates"][0]["source_sha256"] == "a" * 64
    assert "hand coverage" in saved["rejected_candidates"][0]["rejection_reason"]
