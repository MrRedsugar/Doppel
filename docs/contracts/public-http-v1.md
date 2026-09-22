# Public HTTP Protocol v1

JSON fields use snake_case. User-facing routes are prefixed /v1. The standalone
gateway requires Authorization: Bearer <developer-token> and binds the single owner
"developer". Embedded gateways must supply a verified owner dependency. Task tokens
are separate internal credentials and cannot configure user extensions or read
another owner's data. Health is GET /health with no token and contains no secrets.

Skills routes and model tools are retired. `/v1/skills` and its former child
routes return the normal unknown-route response; `list_skills`, `read_skill`,
`load_skill` and `read_skill_resource` are not registered tools. Existing imported
files are retained. Document operations and long-term memory are unchanged.

## Devices and Tasks

| Method and path after /v1 | Body/query | Result |
| --- | --- | --- |
| POST /devices | {installation_id,name} | {id,name,last_seen} |
| GET /devices | None | {items:[Device]} |
| GET /points | None | Developer gateway only: unlimited=true, retained input/output tokens and estimated used_points |
| GET /usage | None | Token-first usage dashboard: aggregate input/output tokens, request and screenshot counts, plus up to 30 daily `{date,input_tokens,output_tokens,requests,screenshots}` rows; no points field |
| POST /runs | {device_id,goal,mode,allowed_packages?,parent_run_id?,request_id?,defer_start?,source?,source_metadata?} | Run; HTTP 201 |
| GET /devices/{id}/queue | None | {items:[Run]} in persistent FIFO admission order, unfinished only |
| POST /runs/{id}/start | None | Atomically start the queued FIFO head; never bypass another unfinished task |
| GET /runs | None | {items:[Run]} |
| GET /runs/{id} | None | Run; includes a stable local `title` and `conversation_id` |
| POST /conversation/intent | {message,history?,device_available?} | Structured `{intent,confidence,task_goal,title,question,reply?}`; never creates a task |
| GET /runs/{id}/conversation | None | `{items:[{id,conversation_id,title,goal,message,status,created_at,messages:[{id,role,text,kind,created_at}]}]}` in chronological order |
| POST /runs/{id}/review | {question} (1–2000 chars) | Read-only model analysis of the recorded run |
| POST /runs/{id}/pause | None | Run |
| POST /runs/{id}/resume | None | Run |
| POST /runs/{id}/cancel | {expected_status?} | Run; an optional exact-state precondition prevents cancellation/promotion races |
| POST /runs/{id}/answer | {request_id,text?,approve?} | Run |
| GET /runs/{id}/events | after integer, default 0 | {items:[Event]} |
| GET /devices/{id}/commands | timeout seconds, 0..25 | {command:Command|null} |
| POST /devices/{id}/results | CommandResult | {ok:true} |
| DELETE /runs/{id} | None | Cleanup result; terminal/stopped only |

mode is ask, assist or full. allowed_packages is an optional array of up to forty
Android package names; an empty array leaves package selection to the authorized
task. The broker always preserves permission/payment checks. A device cannot have
two executing tasks. New work behind any unfinished task is `queued` and performs
no model work or device actions. `defer_start=true` also queues an idle first task
until the device dispatcher finishes readiness checks. Querying/operating a foreign
owned resource returns 403/404.

Manual and automatic sources share `queue_sequence` order; `queue_position` is the
current unfinished-task position. Completed, failed and cancelled tasks leave the
queue. Paused, awaiting-input and awaiting-approval tasks retain the head. The
dispatcher starts the next task only after device actions and protected-session
cleanup finish. Cancelling a queued task must not invalidate the executing task.
Automatic sources (`schedule`, `trigger`) always enter queued; their notice and
unlock preparation run at the head, not at submission time.

Optional `parent_run_id` continues a conversation from a run owned by
the same user on the same device. The new execution has its own mode, permissions,
commands and fresh observation. The conversation reader resolves retained source
records with a 12-entry/24000-character bound; deleting an ancestor truncates its
earlier history. Omit the parent for a new conversation. See
[continuous conversations](../developer/continuous-conversations.md).

`POST /runs/{id}/review` is a diagnostic conversation for the history screen. It
uses only bounded task metadata and event evidence, never screenshots, credentials,
commands or pending approvals. It cannot resume, pause, or mutate the run and is
available for paused or terminal runs. The response may be an OpenAI-shaped
completion (`choices[0].message.content`) so clients can render provider text.

```json
{
  "id":"run-id", "device_id":"device-id", "goal":"Inspect the fixture",
  "mode":"assist", "allowed_packages":["dev.doppel.testapp"],
  "status":"running", "message":"", "created_at":"2026-09-07T00:00:00Z",
  "pending_request":null
}
```

Status is queued/running/paused/awaiting_approval/awaiting_input/completed/failed/
cancelled. Pending requests contain id, kind="approval"|"input", message and the
specific command or extension invocation when applicable. Clients display exact
pending scope and submit its current request_id; they cannot substitute arguments.
An expired request or changed target/configuration returns 409.

Pausing an already paused task is idempotent: retain its specific `message` and
`pending_request` rather than replacing the cause with a generic pause message.
Clients must surface a non-user pause explanation and preserve it when opening
task controls. A missing cause must be shown as unknown, not interpreted as task
completion or proof of a particular permission or capability boundary.

Verification/login/payment takeover uses status=paused and a pending request with
manual_only=true and reason=verification|login|payment. Display the message and use
POST /runs/{id}/resume after the user has acted; do not submit an ordinary answer.
requires_fresh_observation gates resumed model work until a current screen is
received. For payment takeover, the user must check the order and charge outcome
before continuing. The interrupted mutation is not automatically replayed.

Events contain sequence, kind, message, data and created_at; use sequence as the
after cursor. Result status and subsequent observation determine actual progress,
not command acceptance alone. Completed tasks require host-generated evidence.

## Commands and Observation

```json
{
  "id":"command-id", "run_id":"run-id", "kind":"tap",
  "screen_id":"content-hash", "target":"node-reference"
}
```

Command.kind supports observe/launch/tap/pay/long_press/type/login_phone/login_code/
scroll/back/home/wait/screenshot/open_document. launch uses package_name; type uses text; scroll uses direction
up/down/left/right and optionally target; wait uses duration_ms; open_document uses
uri. Tap/pay/long_press/type/login_phone/login_code require target and screen_id.
Login commands require the current package_name and reject non-null text; local
profiles supply values. Their MCP tools accept only target and screen_id.
observe can request include_screenshot.
No raw-coordinate command is exposed. Current package scope and sensitive controls
are independently checked on host/device.

```json
{
  "command_id":"command-id", "run_id":"run-id", "status":"ok",
  "message":"", "observation":null, "data":{}
}
```

Result.status is ok/stale/blocked/error/cancelled. Observation includes screen_id,
package_name, width, height, nodes and captured_at, plus optional
payment_consent_id as described below. Each node has id, text,
description, role, bounds=[left,top,right,bottom], clickable, long_clickable, editable, enabled,
scrollable, password and resource_id. The device redacts password contents and
caps its tree/text size; clients must preserve node IDs instead of reconstructing
coordinates. Screenshot data contains image_base64 and mime_type when available;
unavailable protected/API surfaces produce an error rather than a blank success.

Client command IDs are durable deduplication keys. Persist the uncertain outcome
before dispatch. Identical result reposts are idempotent; changing an already
committed result returns 409. After local raw-data purge preserve the ID/hash or
uncertain tombstone and do not execute again. A lost/timeout result is uncertain,
not authorization for a blind retry.

## Delegated Payment Contract

`Observation.payment_consent_id` is an optional, device-owned string in the format
`payment-v1:<lowercase UUID>`; omitted or null means disabled. Unsupported versions,
malformed IDs and non-string values are rejected. The Android setting defaults to
off and requires three timed, local risk acknowledgements before enabling.
There is no model, MCP or user HTTP endpoint that creates local consent.

The model declares an actual payment with `act(action="pay")`, based on the current
screen and user intent. Browsing orders, payment history or checkout details uses
ordinary `tap`, without a new `safety` field. In Android's A/B flow, A chooses
`pay`; B only locates its tap target. Host/device code does not classify payment
from node labels, target-description keywords or other text on the page.

Only the runtime may copy the current device observation's consent ID onto a
`pay` command for a `full` task. `Command.mode` comes from the stored task.
An `act` caller cannot supply either `mode` or `payment_consent_id`, including null.
Legacy `tap` commands with consent are readable for migration but are blocked,
not replayed as payments. Other command kinds cannot carry consent.
Compact model observations report `delegated_payment=enabled|disabled` without
exposing the identifier; a read-only observation never enables the setting.

Payment requires both `full` task access and valid local consent. `ask`/`assist`
hand payment to the user even when the switch is on; ordinary action approval
cannot upgrade the task's payment authority. The gateway rechecks stored mode,
consent and target at dispatch; Android rechecks the persisted local generation
and current source context immediately before action.
Payment does not use relaxed target revalidation. Revocation or a new consent
generation invalidates old commands, even if the device is offline. Payment
credentials, financial OTPs, transfers and persistent debit settings remain manual.

Android claims a durable task/application attempt before clicking. A changed
command ID, button label, amount, resource ID or consent generation cannot justify
another payment attempt in that application during the same task. This may require
manual continuation for multi-step checkout. A new task does not deduplicate a
previous merchant transaction; inspect prior orders before trying again.

Payment result `data.payment_attempted=true` records an attempt claim, and
`payment_action_accepted` records whether Android accepted the click request.
Neither proves a charge or merchant acceptance, even when `status=ok`. Inspect
the subsequent screen/order state. A blocked payment can return
`data.human_takeover="payment"`; duplicate or storage failures may also include
`payment_guard`. Display the result and use the takeover/resume flow above.
Do not turn a blocked, stale, failed or uncertain attempt into an automatic retry.

Update Android and gateway together: old versions do not support `pay`; missing
consent remains disabled and legacy consent-stamped taps are not replayed. See
[Delegated Payment](../developer/payment-delegation.md) for the matching local
database/grant-file requirement, revocation failures and UI recognition limits.

## Documents, Extensions and Cleanup

POST /documents accepts multipart field file containing a new authorized .xlsx
copy, maximum 20 MiB. GET /documents lists owned files; GET/DELETE /documents/{name}
download/delete safe filenames. Existing output names return 409. Entry fields are
name, size and download_uri="doppel-document://name.xlsx". No arbitrary local or
SAF path is accepted. See documents.md for batch operation/tool contracts.

GET/POST /extensions list/create owner-scoped remote MCP configurations. PUT/DELETE
/extensions/{name} replace/remove; GET /extensions/{name}/tools discovers available
tools for explicit grant selection. Configuration accepts name, url, allowed_tools
and read_only_tools only. See extensions.md for policy, approval and schema details.

GET/PATCH /data-retention reads/sets {days}; default 7, zero forever. GET
/runs/{id}/screenshots lists metadata; GET/DELETE /runs/{id}/screenshots/{command_id}
views/removes raw images. Cleanup results contain status, cleanup_pending and
device_cleanup_required; HTTP 202 means pending cleanup, not completed erasure.
GET /devices/{id}/data-cleanup and POST /devices/{id}/data-cleanup/{cleanup_id}/ack
carry durable client purge requests. See data-retention.md for copy/backup limits.

## Errors and Embedding

400/422 indicate invalid parameters or unsupported operations; 401 missing/invalid
owner authentication; 403 denied capability; 404 inaccessible/missing owner
resource; 409 current-state/ownership/overwrite conflict; 413 body too large;
502/504 external transport failure/timeout. Error bodies use detail. Clients must
not expose provider credentials, base64 images or exception traces in ordinary logs.

Python entry points: RuntimeConfig(data_dir, api_key_file=None, model=None,
provider="xiaomi-mimo", vision_model=None, host_url="http://127.0.0.1:8765") and
DoppelRuntime(config) own execution state. Omitted models resolve to the provider's
defaults: mimo-v2.5-pro for main decisions and mimo-v2.5 for auxiliary vision.
create_router(runtime, owner_dependency) returns the full /v1 APIRouter. The CLI
creates a standalone FastAPI application. Internal /v1/internal/runs/{id}/tool and
/chat/completions routes are reserved for the trusted Harness adapter's task bearer
credential and are not user configuration APIs. Tool schemas are published by
the framework MCP adapter, not inferred from screen text.

Contract version is 1. New optional fields may be added with documented behavior;
breaking changes require a versioned contract. SDK consumers should ignore extra
response fields while validating required fields, and send only supported request
fields. All lifecycle, capabilities and platform limitations must be evaluated
with the actual deployed server/SDK version.
