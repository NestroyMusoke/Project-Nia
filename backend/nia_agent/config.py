from dataclasses import dataclass
import os


@dataclass(frozen=True)
class Settings:
    project_id: str = os.getenv("GOOGLE_CLOUD_PROJECT", "")
    model: str = os.getenv("NIA_GEMINI_MODEL", "gemini-2.5-flash")
    pubsub_topic: str = os.getenv("NIA_PUBSUB_TOPIC", "nia-background-jobs")
    local_mode: bool = os.getenv("NIA_LOCAL_MODE", "false").lower() == "true"


settings = Settings()

