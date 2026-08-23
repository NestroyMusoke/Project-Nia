from __future__ import annotations

from datetime import datetime, timezone
from threading import Lock
from typing import Any, Protocol

from google.cloud import firestore

from .config import settings


class ConversationStore(Protocol):
    def get_goal(self, user_id: str, session_id: str) -> dict[str, Any]: ...
    def save_turn(self, user_id: str, session_id: str, request: dict[str, Any], response: dict[str, Any]) -> None: ...
    def save_job(self, user_id: str, session_id: str, job_id: str, payload: dict[str, Any]) -> None: ...


class InMemoryConversationStore:
    def __init__(self) -> None:
        self._lock = Lock()
        self._goals: dict[tuple[str, str], dict[str, Any]] = {}
        self.turns: list[dict[str, Any]] = []
        self.jobs: list[dict[str, Any]] = []

    def get_goal(self, user_id: str, session_id: str) -> dict[str, Any]:
        with self._lock:
            return dict(self._goals.get((user_id, session_id), {"status": "discovering", "known": {}, "missing": []}))

    def save_turn(self, user_id: str, session_id: str, request: dict[str, Any], response: dict[str, Any]) -> None:
        with self._lock:
            self._goals[(user_id, session_id)] = dict(response.get("goal_state", {}))
            self.turns.append({"user_id": user_id, "session_id": session_id, "request": request, "response": response})

    def save_job(self, user_id: str, session_id: str, job_id: str, payload: dict[str, Any]) -> None:
        with self._lock:
            self.jobs.append({"user_id": user_id, "session_id": session_id, "job_id": job_id, "payload": payload})


class FirestoreConversationStore:
    def __init__(self) -> None:
        self.client = firestore.Client(project=settings.project_id or None)

    def _session(self, user_id: str, session_id: str):
        return self.client.collection("nia_users").document(user_id).collection("sessions").document(session_id)

    def get_goal(self, user_id: str, session_id: str) -> dict[str, Any]:
        snapshot = self._session(user_id, session_id).get()
        if not snapshot.exists:
            return {"status": "discovering", "known": {}, "missing": []}
        return snapshot.to_dict().get("goal_state", {})

    def save_turn(self, user_id: str, session_id: str, request: dict[str, Any], response: dict[str, Any]) -> None:
        session = self._session(user_id, session_id)
        session.set(
            {
                "goal_state": response.get("goal_state", {}),
                "updated_at": datetime.now(timezone.utc),
            },
            merge=True,
        )
        session.collection("turns").document(request["request_id"]).set(
            {"request": request, "response": response, "created_at": datetime.now(timezone.utc)}
        )

    def save_job(self, user_id: str, session_id: str, job_id: str, payload: dict[str, Any]) -> None:
        self._session(user_id, session_id).collection("jobs").document(job_id).set(
            {"payload": payload, "status": "received", "processed_at": datetime.now(timezone.utc)}
        )


store: ConversationStore = InMemoryConversationStore() if settings.local_mode else FirestoreConversationStore()
