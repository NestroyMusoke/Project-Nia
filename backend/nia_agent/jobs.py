import json
from typing import Any

from google.cloud import pubsub_v1

from .config import settings


def publish_background_job(user_id: str, session_id: str, action: str, reason: str) -> dict[str, Any]:
    """Queue non-urgent follow-up work after the user-facing response is complete."""
    payload = {"user_id": user_id, "session_id": session_id, "action": action, "reason": reason}
    if settings.local_mode:
        return {"queued": False, "local_mode": True, "payload": payload}
    publisher = pubsub_v1.PublisherClient()
    topic = publisher.topic_path(settings.project_id, settings.pubsub_topic)
    message_id = publisher.publish(topic, json.dumps(payload).encode("utf-8")).result(timeout=10)
    return {"queued": True, "message_id": message_id}

