import os
import base64
import json

os.environ.setdefault("NIA_LOCAL_MODE", "true")

from nia_agent.service import NiaAgentService
from fastapi.testclient import TestClient
from nia_agent.api import app


def test_health_contract_identifies_compatible_agent_service():
    response = TestClient(app).get("/healthz")
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "ok"
    assert payload["service"] == "project-nia-agent"
    assert payload["api_version"] == 1
    assert payload["mode"] == "local"
    assert payload["model"]


def test_parse_structured_response():
    parsed = NiaAgentService._parse_response(
        '{"message":"Please wait.","sign_glosses":["please","wait"],"goal_state":{"status":"active","known":{},"missing":[],"next_action":"wait"},"clarification_needed":false}'
    )
    assert parsed["message"] == "Please wait."
    assert parsed["sign_glosses"] == ["please", "wait"]
    assert parsed["clarification_needed"] is False


def test_malformed_response_fails_safe():
    parsed = NiaAgentService._parse_response("not json")
    assert parsed["clarification_needed"] is True
    assert parsed["sign_glosses"] == []
    assert parsed["goal_state"]["status"] == "clarifying"


def test_unavailable_avatar_gloss_fails_closed_instead_of_playing_a_fragment():
    parsed = {
        "message": "Please wait.",
        "sign_glosses": ["please", "invented", "wait"],
        "goal_state": {},
        "clarification_needed": False,
    }
    filtered = NiaAgentService._filter_sign_glosses(parsed, ["please", "wait"])
    assert filtered["sign_glosses"] == []
    assert filtered["clarification_needed"] is True
    assert filtered["goal_state"]["status"] == "avatar_vocabulary_insufficient"


def test_complete_supported_avatar_sequence_is_preserved():
    parsed = {
        "message": "Please wait.",
        "sign_glosses": ["please", "wait"],
        "goal_state": {},
        "clarification_needed": False,
    }
    filtered = NiaAgentService._filter_sign_glosses(parsed, ["please", "wait"])
    assert filtered["sign_glosses"] == ["please", "wait"]
    assert filtered["clarification_needed"] is False


def test_pubsub_push_is_acknowledged():
    payload = {"user_id": "u", "session_id": "s", "action": "follow_up", "reason": "test"}
    envelope = {
        "message": {
            "messageId": "m1",
            "data": base64.b64encode(json.dumps(payload).encode()).decode(),
        }
    }
    assert TestClient(app).post("/pubsub/events", json=envelope).status_code == 204


def test_pubsub_rejects_firestore_path_injection():
    payload = {"user_id": "../other-user", "session_id": "s", "action": "follow_up"}
    envelope = {
        "message": {
            "messageId": "m2",
            "data": base64.b64encode(json.dumps(payload).encode()).decode(),
        }
    }
    assert TestClient(app).post("/pubsub/events", json=envelope).status_code == 400


def test_api_rejects_unsafe_user_identifier_before_agent_execution():
    response = TestClient(app).post(
        "/v1/sign-message",
        json={
            "user_id": "../other-user",
            "session_id": "session-1",
            "message": "hello",
            "avatar_vocabulary": [],
            "request_id": "request-1",
        },
    )
    assert response.status_code == 422
