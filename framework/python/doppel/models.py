from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Node(Model):
    id: str = Field(min_length=1, max_length=128)
    text: str = Field(default="", max_length=4096)
    description: str = Field(default="", max_length=4096)
    role: str = "view"
    bounds: list[int] = Field(min_length=4, max_length=4)
    clickable: bool = False
    editable: bool = False
    enabled: bool = True
    scrollable: bool = False
    password: bool = False
    resource_id: str = ""

    @model_validator(mode="after")
    def redact_password(self):
        if self.password:
            self.text = ""
            self.description = ""
        if self.bounds[2] < self.bounds[0] or self.bounds[3] < self.bounds[1]:
            raise ValueError("Invalid node bounds")
        return self


class Observation(Model):
    screen_id: str = Field(min_length=1, max_length=128)
    package_name: str = Field(max_length=256)
    width: int = Field(gt=0, le=16384)
    height: int = Field(gt=0, le=16384)
    nodes: list[Node] = Field(default_factory=list, max_length=600)
    captured_at: int = 0


CommandKind = Literal["observe", "launch", "tap", "type", "scroll", "back", "home", "wait", "screenshot", "open_document"]


class Command(Model):
    id: str
    run_id: str
    kind: CommandKind
    screen_id: str | None = None
    target: str | None = None
    text: str | None = Field(default=None, max_length=12000)
    package_name: str | None = Field(default=None, max_length=256)
    direction: Literal["up", "down", "left", "right"] | None = None
    duration_ms: int | None = Field(default=None, ge=0, le=10000)
    include_screenshot: bool = False
    uri: str | None = None

    @model_validator(mode="after")
    def require_fields(self):
        if self.kind in {"tap", "type"} and (not self.target or not self.screen_id):
            raise ValueError("A current screen_id and target are required")
        if self.kind == "type" and self.text is None:
            raise ValueError("text is required")
        if self.kind == "launch" and not self.package_name:
            raise ValueError("package_name is required")
        if self.kind == "open_document" and not self.uri:
            raise ValueError("uri is required")
        return self


class CommandResult(Model):
    command_id: str
    run_id: str
    status: Literal["ok", "stale", "blocked", "error", "cancelled"]
    message: str = Field(default="", max_length=4000)
    observation: Observation | None = None
    data: dict[str, Any] = Field(default_factory=dict)


class Device(Model):
    id: str
    name: str
    last_seen: str | None = None


RunStatus = Literal["queued", "running", "paused", "awaiting_approval", "awaiting_input", "completed", "failed", "cancelled"]


class Run(Model):
    id: str
    device_id: str
    goal: str
    mode: Literal["ask", "assist", "full"]
    status: RunStatus
    message: str = ""
    created_at: str
    pending_request: dict[str, Any] | None = None
    allowed_packages: list[str] = Field(default_factory=list, max_length=40)


class Event(Model):
    sequence: int
    kind: str
    message: str
    data: dict[str, Any] = Field(default_factory=dict)
    created_at: str
