import json

from tools.commission_avatar_vocabulary import has_passing_motion


def test_detects_existing_passing_motion(tmp_path):
    path = tmp_path / "provenance.json"
    path.write_text(json.dumps({
        "motions": [
            {"gloss": "hello", "quality": {"passed": True}},
            {"gloss": "please", "quality": {"passed": False}},
        ]
    }), encoding="utf-8")

    assert has_passing_motion(path, "hello") is True
    assert has_passing_motion(path, "please") is False
    assert has_passing_motion(path, "wait") is False
