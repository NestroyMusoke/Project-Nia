from pydantic import BaseModel, Field


class SignToken(BaseModel):
    label: str
    confidence: float = Field(ge=0.0, le=1.0)
    margin: float = Field(ge=-1.0, le=1.0)


class InterpretRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    session_id: str = Field(min_length=1, max_length=128)
    tokens: list[SignToken] = Field(min_length=1, max_length=20)
    request_id: str = Field(min_length=1, max_length=128)


class InterpretResponse(BaseModel):
    message: str
    goal_state: dict[str, object]
    clarification_needed: bool
    request_id: str

