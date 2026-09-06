# Public HTTP Protocol v1

JSON fields use snake_case. User-facing routes are prefixed /v1. The standalone
gateway requires Authorization: Bearer <developer-token> and binds the single owner
"developer". Embedded gateways must supply a verified owner dependency. Task tokens
are separate internal credentials and cannot configure user extensions or read
another owner's data. Health is GET /health with no token and contains no secrets.

## Devices and Tasks

| Method and path after /v1 | Body/query | Result |
| --- | --- | --- |
| POST /devices | {installation_id,name} | {id,name,last_seen} |
| GET /devices | None | {items:[Device]} |
| POST /runs | {device_id,goal,mode,allowed_packages?} | Run; HTTP 201 |
| GET /runs | None | {items:[Run]} |
| GET /runs/{id} | None | Run |
| POST /runs/{id}/pause | None | Run |
| POST /runs/{id}/resume | None | Run |
| POST /runs/{id}/cancel | None | Run |
| POST /runs/{id}/answer | {request_id,text?,approve?} | Run |
| GET /runs/{id}/events | after integer, default 0 | {items:[Event]} |
| GET /devices/{id}/commands | timeout seconds, 0..25 | {command:Command|null} |
| POST /devices/{id}/results | CommandResult | {ok:true} |
| DELETE /runs/{id} | None | Cleanup result; terminal/stopped only |

mode is ask, assist or full. allowed_packages is an optional array of up to forty
Android package names; an empty array leaves package selection to the authorized
task. The broker always preserves permission/payment checks. A device cannot have
two active tasks. Querying/operating a foreign owned resource returns 403/404.

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

Command.kind supports observe/launch/tap/type/scroll/back/home/wait/screenshot/
open_document. launch uses package_name; type uses text; scroll uses direction
up/down/left/right and optionally target; wait uses duration_ms; open_document uses
uri. Tap/type require target and screen_id. observe can request include_screenshot.
No raw-coordinate command is exposed. Current package scope and sensitive controls
are independently checked on host/device.

```json
{
  "command_id":"command-id", "run_id":"run-id", "status":"ok",
  "message":"", "observation":null, "data":{}
}
```

Result.status is ok/stale/blocked/error/cancelled. Observation includes screen_id,
package_name, width, height, nodes and captured_at. Each node has id, text,
description, role, bounds=[left,top,right,bottom], clickable, editable, enabled,
scrollable, password and resource_id. The device redacts password contents and
caps its tree/text size; clients must preserve node IDs instead of reconstructing
coordinates. Screenshot data contains image_base64 and mime_type when available;
unavailable protected/API surfaces produce an error rather than a blank success.

Client command IDs are durable deduplication keys. Persist the uncertain outcome
before dispatch. Identical result reposts are idempotent; changing an already
committed result returns 409. After local raw-data purge preserve the ID/hash or
uncertain tombstone and do not execute again. A lost/timeout result is uncertain,
not authorization for a blind retry.

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

Python entry points: RuntimeConfig(data_dir, api_key_file=None, model="deepseek-v4-pro",
host_url="http://127.0.0.1:8765") and DoppelRuntime(config) own execution state.
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
