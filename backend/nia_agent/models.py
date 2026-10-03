from pydantic import BaseModel, Field


SAFE_ID_PATTERN = r"^[A-Za-z0-9_-]+$"


def is_safe_identifier(value: object, max_length: int = 128) -> bool:
    if not isinstance(value, str) or not 1 <= len(value) <= max_length:
        return False
    return all(character.isascii() and (character.isalnum() or character in "_-") for character in value)


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
    user_corrected: bool = False


class InterpretRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128, pattern=SAFE_ID_PATTERN)
    session_id: str = Field(min_length=1, max_length=128, pattern=SAFE_ID_PATTERN)
    tokens: list[SignToken] = Field(min_length=1, max_length=20)
    avatar_vocabulary: list[str] = Field(default_factory=list, max_length=128)
    request_id: str = Field(min_length=1, max_length=128, pattern=SAFE_ID_PATTERN)


class MessageToSignRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128, pattern=SAFE_ID_PATTERN)
    session_id: str = Field(min_length=1, max_length=128, pattern=SAFE_ID_PATTERN)
    message: str = Field(min_length=1, max_length=2000)
    avatar_vocabulary: list[str] = Field(default_factory=list, max_length=128)
    request_id: str = Field(min_length=1, max_length=128, pattern=SAFE_ID_PATTERN)


class InterpretResponse(BaseModel):
    message: str
    sign_glosses: list[str] = Field(default_factory=list, max_length=40)
    goal_state: dict[str, object]
    clarification_needed: bool
    request_id: str
