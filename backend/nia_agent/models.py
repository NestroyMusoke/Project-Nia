from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    status: str
    service: str
    api_version: int
    mode: str
    model: str


class SignToken(BaseModel):
    label: str
    confidence: float = Field(ge=0.0, le=1.0)
    margin: float = Field(ge=-1.0, le=1.0)


class InterpretRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    session_id: str = Field(min_length=1, max_length=128)
    tokens: list[SignToken] = Field(min_length=1, max_length=20)
    avatar_vocabulary: list[str] = Field(default_factory=list, max_length=128)
    request_id: str = Field(min_length=1, max_length=128)


class MessageToSignRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    session_id: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=2000)
    avatar_vocabulary: list[str] = Field(default_factory=list, max_length=128)
    request_id: str = Field(min_length=1, max_length=128)


class InterpretResponse(BaseModel):
    message: str
    sign_glosses: list[str] = Field(default_factory=list, max_length=40)
    goal_state: dict[str, object]
    clarification_needed: bool
    request_id: str
