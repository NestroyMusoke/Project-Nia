from __future__ import annotations

import json

from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types

from .agent import root_agent
from .models import InterpretRequest, InterpretResponse
from .storage import ConversationStore


APP_NAME = "project_nia"


class NiaAgentService:
    def __init__(self, store: ConversationStore) -> None:
        self.store = store
        self.sessions = InMemorySessionService()
        self.runner = Runner(agent=root_agent, app_name=APP_NAME, session_service=self.sessions)

    async def interpret(self, request: InterpretRequest) -> InterpretResponse:
        goal = self.store.get_goal(request.user_id, request.session_id)
        try:
            await self.sessions.create_session(
                app_name=APP_NAME,
                user_id=request.user_id,
                session_id=request.session_id,
                state={"goal_state": goal},
            )
        except Exception:
            pass

        prompt = json.dumps(
            {
                "recognized_tokens": [token.model_dump() for token in request.tokens],
                "goal_state": goal,
                "request_id": request.request_id,
                "user_id": request.user_id,
                "session_id": request.session_id,
            }
        )
        content = types.Content(role="user", parts=[types.Part(text=prompt)])
        final_text = ""
        async for event in self.runner.run_async(
            user_id=request.user_id,
            session_id=request.session_id,
            new_message=content,
        ):
            if event.is_final_response() and event.content and event.content.parts:
                final_text = "".join(part.text or "" for part in event.content.parts)

        parsed = self._parse_response(final_text)
        response = InterpretResponse(request_id=request.request_id, **parsed)
        self.store.save_turn(request.user_id, request.session_id, request.model_dump(), response.model_dump())
        return response

    @staticmethod
    def _parse_response(text: str) -> dict:
        cleaned = text.strip()
        if cleaned.startswith("```"):
            cleaned = cleaned.split("\n", 1)[1].rsplit("```", 1)[0]
        try:
            payload = json.loads(cleaned)
            return {
                "message": str(payload["message"]),
                "goal_state": dict(payload["goal_state"]),
                "clarification_needed": bool(payload["clarification_needed"]),
            }
        except (ValueError, KeyError, TypeError):
            return {
                "message": text or "I’m not sure yet. Could you repeat that sign?",
                "goal_state": {"status": "clarifying", "known": {}, "missing": ["intended meaning"], "next_action": "ask user"},
                "clarification_needed": True,
            }

