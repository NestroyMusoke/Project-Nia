import base64
import json

from fastapi import FastAPI, HTTPException, Request

from .models import InterpretRequest, InterpretResponse, MessageToSignRequest
from .service import NiaAgentService
from .storage import store


app = FastAPI(title="Project Nia Agent", version="0.1.0")
service = NiaAgentService(store)


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/interpret", response_model=InterpretResponse)
async def interpret(request: InterpretRequest) -> InterpretResponse:
    return await service.interpret(request)


@app.post("/v1/sign-message", response_model=InterpretResponse)
async def sign_message(request: MessageToSignRequest) -> InterpretResponse:
    return await service.sign_message(request)


@app.post("/pubsub/events", status_code=204)
async def pubsub_events(request: Request) -> None:
    envelope = await request.json()
    try:
        encoded = envelope["message"]["data"]
        payload = json.loads(base64.b64decode(encoded).decode("utf-8"))
        if not all(key in payload for key in ("user_id", "session_id", "action")):
            raise ValueError("missing required job fields")
    except (KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
        raise HTTPException(status_code=400, detail="Invalid Pub/Sub envelope") from error
    message_id = str(envelope["message"].get("messageId") or envelope["message"].get("id") or payload["action"])
    store.save_job(payload["user_id"], payload["session_id"], message_id, payload)
