import os
import base64
import json

os.environ.setdefault("NIA_LOCAL_MODE", "true")

from nia_agent.service import NiaAgentService
from fastapi.testclient import TestClient
from nia_agent.api import app


def test_parse_structured_response():
    parsed = NiaAgentService._parse_response(
        '{"message":"Please wait.","goal_state":{"status":"active","known":{},"missing":[],"next_action":"wait"},"clarification_needed":false}'
    )
    assert parsed["message"] == "Please wait."
    assert parsed["clarification_needed"] is False


def test_malformed_response_fails_safe():
    parsed = NiaAgentService._parse_response("not json")
    assert parsed["clarification_needed"] is True
    assert parsed["goal_state"]["status"] == "clarifying"


def test_pubsub_push_is_acknowledged():
    payload = {"user_id": "u", "session_id": "s", "action": "follow_up", "reason": "test"}
    envelope = {
        "message": {
            "messageId": "m1",
            "data": base64.b64encode(json.dumps(payload).encode()).decode(),
        }
    }
    assert TestClient(app).post("/pubsub/events", json=envelope).status_code == 204
