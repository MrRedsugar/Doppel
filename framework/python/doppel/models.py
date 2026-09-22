from datetime import datetime, timezone
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictStr, model_validator


PaymentConsentId = Annotated[StrictStr, Field(min_length=1, max_length=128,
    pattern=r"^payment-v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")]


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
    long_clickable: bool = False
    editable: bool = False
    enabled: bool = True
    scrollable: bool = False
    password: bool = False
    resource_id: str = ""
    checkable: bool | None = None
    checked: bool | None = None
    selected: bool | None = None
    state_description: str | None = Field(default=None, max_length=4096)

    @model_validator(mode="after")
    def redact_password(self):
        if self.password:
            self.text = ""
            self.description = ""
            self.state_description = None
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
    payment_consent_id: PaymentConsentId | None = None


CommandKind = Literal["observe", "launch", "tap", "pay", "long_press", "type", "login_phone", "login_code", "scroll", "back", "home", "recents", "notifications", "quick_settings", "split_screen", "wait", "screenshot", "open_document"]


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
    desired_checked: StrictBool | None = None
    mode: Literal["ask", "assist", "full"] = "assist"
    payment_consent_id: PaymentConsentId | None = None

    @model_validator(mode="after")
    def require_fields(self):
        if self.payment_consent_id is not None and self.kind not in {"tap", "pay"}:
            raise ValueError("payment_consent_id is only supported for pay (or legacy tap)")
        if self.desired_checked is not None and self.kind not in {"tap", "pay"}:
            raise ValueError("desired_checked is only supported for tap or pay")
        if self.kind in {"tap", "pay", "long_press", "type", "login_phone", "login_code"} and (not self.target or not self.screen_id):
            raise ValueError("A current screen_id and target are required")
        if self.kind == "type" and self.text is None:
            raise ValueError("text is required")
        if self.kind in {"login_phone", "login_code"}:
            if not self.package_name:
                raise ValueError("Local login requires the current application package")
            if any(value is not None for value in (self.text, self.uri, self.direction, self.duration_ms)) or self.include_screenshot:
                raise ValueError("Local login accepts only the current target and application package")
        if self.kind == "long_press" and self.duration_ms is not None:
            raise ValueError("Long press uses the Android accessibility action duration")
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


class ConversationMessage(Model):
    id: str
    role: Literal["user", "assistant", "system"]
    text: str = Field(max_length=12000)
    kind: str = Field(default="message", max_length=40)
    created_at: str


class TaskProgress(Model):
    """A revisable display of observed milestones, never an executable action plan."""
    plan: list[Annotated[StrictStr, Field(min_length=1, max_length=60)]] = Field(max_length=5)
    completed: Annotated[StrictInt, Field(ge=0, le=5)]
    total_known: StrictBool

    @model_validator(mode="after")
    def check_milestones(self):
        if self.completed > len(self.plan) or any(not item.strip() for item in self.plan) or not self.plan and self.total_known:
            raise ValueError("Invalid task progress milestones")
        return self


class TaskStateUpdate(Model):
    phase: Annotated[StrictStr, Field(max_length=120)] = ""
    progress: TaskProgress | None = None


class Run(Model):
    id: str
    device_id: str
    goal: str
    title: str = ""
    conversation_id: str | None = None
    # Background schedule/trigger runs are tasks only and do not participate in
    # the user conversation transcript.  Keep the flag explicit so clients can
    # distinguish those runs without inferring from an empty message list.
    conversation_enabled: bool = True
    source: Literal["user", "schedule", "trigger"] = "user"
    mode: Literal["ask", "assist", "full"]
    status: RunStatus
    message: str = ""
    created_at: str
    queue_sequence: int = Field(default=0, ge=0)
    queue_position: int = Field(default=0, ge=0)
    source_metadata: dict[str, Any] = Field(default_factory=dict)
    parent_run_id: str | None = None
    pending_request: dict[str, Any] | None = None
    requires_fresh_observation: bool = False
    allowed_packages: list[str] = Field(default_factory=list, max_length=40)
    conversation_messages: list[ConversationMessage] = Field(default_factory=list, max_length=80)
    task_state: dict[str, Any] = Field(default_factory=dict)


class Event(Model):
    sequence: int
    kind: str
    message: str
    data: dict[str, Any] = Field(default_factory=dict)
    created_at: str
