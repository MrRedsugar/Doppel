# Standalone Developer Gateway

The public framework runs without product/ or doppel_product. It is a single-owner
development server, separate from commercial login, points and purchases.

## Install and Start

Use Python 3.12+ in a new virtual environment. From the public repository root:

```sh
python -m venv .venv
.venv/bin/python -m pip install -e './framework[test]'
.venv/bin/doppel init --data-dir .local/developer
.venv/bin/doppel serve --data-dir .local/developer --port 8765
```

On Windows use .venv\Scripts\python.exe and .venv\Scripts\doppel.exe. With uv,
`uv pip install --python .venv/Scripts/python.exe -e "./framework[test]"` installs
the same independent package. The framework has its own pyproject.toml; the mixed
workspace root project is unnecessary. `python -m doppel.cli` is also supported.

init creates a cryptographically random bearer token in developer-token.txt inside
the selected data directory and prints only its path. Repeated init preserves a
valid token. Keep this file private, configure its value in the Android developer
app, and never add it to source control. On Windows protect the data directory
with the account's filesystem ACLs; Unix token creation requests mode 0600. The
directory is host-owned and is not a sandbox against other privileged processes.

GET /health is unauthenticated and reports developer mode. /v1 devices, tasks,
documents and extension configuration require the bearer token. There is no
public token issuance endpoint, phone login, SMS or credit API. Every authenticated
request belongs to owner "developer". For multiple users provide a separate
verified owner dependency around the public create_router API.

## Android Connection

The server defaults to 127.0.0.1:8765. A physical phone needs the computer's LAN
address and an authorized firewall rule; launch with --host 0.0.0.0 on a trusted
network. The --gateway-url option controls the address local Harness workers use,
normally http://127.0.0.1:8765 even when the phone uses a LAN address. Do not expose
this HTTP development credential over the public Internet. Terminate HTTPS and
use suitable authentication/network controls for nonlocal deployments.

Build and install the public developer-app and test-app, enter gateway URL/token,
and grant the documented Android permissions. See android.md. The public SDK
contains reusable connection/task/file/extension UI; commercial account and
points UI belongs to the excluded private app.

## Model Configuration

```sh
doppel serve --data-dir .local/developer --api-key-file /private/deepseek-key.txt
```

The configured key is read by the gateway, not placed in Android clients. The
default main model is DeepSeek V4 Pro. The shipped Harness worker sets
`reasoning_effort="off"` for task runs; the public CLI and `RuntimeConfig` do not
expose a switch to enable reasoning. The official Harness SDK may require its
packaged/downloaded runtime and network on first use. Budget limits and usage
events are public framework behavior; this developer gateway has no commercial
credit ledger and uses the operator's provider account. Missing credentials cause
model tasks to fail explicitly. Health/protocol/offline tests do not need a key.

## Tests and Data

```sh
cd framework
python -m pytest -q
```

Tests use temporary databases, transport fixtures and workbook copies. Dedicated
Harness subprocess tests may initialize the official local runtime but use a
local fake model endpoint. Test evidence is separate from paid model calls and
real Android/WPS verification. Source copies/outputs, SQLite stores, logs,
screenshots and runtime caches belong under the private data directory.

The server cancels active workers on shutdown. A subsequent start marks interrupted
runs failed so uncertain side effects are not silently replayed. To rotate the
developer token, stop the service, replace its file with a fresh high-entropy
token, then restart and update the client; existing in-memory authentication
does not reread a token during operation.
