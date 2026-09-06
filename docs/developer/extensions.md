# Skills and MCP extensions

The public Python gateway requires Python 3.12+ and the official MCP SDK 2.1.x.
The SDK exposes MCPServer, snake_case Python model fields, and wire-format JSON
aliases. These interfaces do not assume Python or Node exists on Android.

## Skills

```python
from doppel.skills import SkillCatalog

catalog = SkillCatalog('framework/examples/skills')
metadata = catalog.list_skills()
instructions = catalog.read_skill('workbook-summary')
reference = catalog.read_resource('workbook-summary', 'references/operations.md')
```

Each immediate subdirectory has SKILL.md with YAML frontmatter. Required fields
are name (matching the folder) and description. Optional platforms is a string
array; dependencies is an object with runtimes and tools string arrays. Unknown
frontmatter fields are ignored; dependency keys outside this contract are rejected.
YAML object tags and aliases are rejected. No scripts are executed or imported.

list_skills returns compact dictionaries with name, description, platforms,
dependencies, runtime_status="unverified", and trusted=false. read_skill adds
instructions; read_resource returns UTF-8 text. Only the requested resource is
loaded. The host must independently verify dependency availability and use its
normal authorization policy for any requested capability. Metadata cannot claim
that a desktop runtime is available on a phone.

Default limits: 100 skills, 64 KiB per SKILL.md/resource, 32 strings per metadata
list, 256 characters per item and 1,024 characters per description. Constructor
limits can be lowered. Absolute paths, traversal, alternate data streams, symlinks
and Windows junctions are rejected. The configured root must be owned by the host;
these checks are not an OS sandbox against a hostile process modifying directories
concurrently. Malformed skill data raises ValueError; missing files raise OSError.

## MCP

Only trusted administrator deployment configuration may construct MCPServerConfig.
Do not expose command, args, URL or connector creation as model/task arguments.
stdio executes the exact configured executable without a shell. Administrators
must trust and isolate that process: MCP transport does not sandbox executables.
HTTP URLs may access administrator-approved local/private endpoints; this is not
an arbitrary model-provided URL fetcher. Environment HTTP proxies are disabled.

```python
import sys
from doppel.extensions import MCPConnector, MCPServerConfig

config = MCPServerConfig(
    name='arithmetic', transport='stdio', command=sys.executable,
    args=('framework/examples/mcp_server.py',), timeout_seconds=30,
)

async def run():
    async with MCPConnector(config) as connector:
        descriptors = await connector.list_tools()
        # The host validates input_schema and authorizes this exact invocation here.
        result = await connector.call_tool(
            'mcp.arithmetic.add', {'left': 3, 'right': 4},
        )
        return result
```

For Streamable HTTP use transport="streamable_http", url="http://host:port/mcp"
and omit command/args. The official SDK uses httpx2; authentication configuration
and OAuth are not exposed by this first connector. Deploy protected gateways
behind an administrator-controlled authenticated transport when required.

The async context initializes protocol negotiation; server_capabilities exposes
the server's advertised capability dictionary. list_tools follows bounded
pagination and returns descriptors with name="mcp.<server>.<tool>", description,
input_schema, version="1", permission="external", trusted=false. External
annotations never become permission decisions. Namespaces and discovery are
enforced; host policy, JSON Schema validation, audit and task lifecycle remain
the broker's responsibility. Never map an external readOnlyHint to host approval.

call_tool requires a discovered namespaced name and object arguments; returns
the SDK result as wire-format JSON (content, structuredContent where supplied,
isError). An isError result is a tool failure. Content is untrusted task data.
The adapter does not retry tool calls. timeout_seconds defaults to 30 (maximum
300); asyncio task cancellation propagates through the official SDK. Cancellation
does not undo an external side effect, and a timed-out result is uncertain.
Open and close a connector in the same async task; individual calls may be child
tasks. The SDK's context-manager lifecycle must not be transferred across tasks.

Default tool count is 100, argument/result serialization limit 256 KiB. Limits
are applied after SDK deserialization; this is not a hostile-server memory sandbox.
Protocol initialization, tools/list and tools/call are supported. Resources,
resource subscriptions, prompts, sampling, elicitation and task extensions are
not exposed. Advertising them does not enable them.

Tests launch the example as a real stdio process and a real loopback Streamable
HTTP server. They check initialization, discovery, calls, timeout, cancellation
and subsequent connection use without paid external services.

## Authenticated remote extension setup

create_extension_router(runtime, owner_dependency) returns an APIRouter without
a version prefix. Include it below the authenticated /v1 router. The owner
dependency must verify the actual account/session. Never substitute a model
provided owner identifier or expose these configuration routes as runtime tools.

| Route | Result |
| --- | --- |
| GET /extensions | Owner's configuration list under items |
| POST /extensions | Create remote configuration; 201, duplicate name 409 |
| PUT /extensions/{name} | Replace this owner's configuration, retaining name |
| DELETE /extensions/{name} | Delete and revoke future calls |
| GET /extensions/{name}/tools | Discover descriptors for user grant selection |

POST and PUT accept this exact JSON object; arrays default to empty:

```json
{
  "name": "reporting",
  "url": "http://127.0.0.1:9000/mcp",
  "allowed_tools": ["read_report", "create_report"],
  "read_only_tools": ["read_report"]
}
```

Names in these arrays are the server's original tool names, without the
mcp.reporting prefix. read_only_tools must be a subset of allowed_tools. Start
with empty grants, discover tools, then PUT explicitly selected permissions.
Discovery returns allowed, read_only, blocked and configuration_revision alongside
the public descriptor. Configurations are stored transactionally in
data_dir/extensions.sqlite3 with owner/name keys; no owner input becomes a path.
Limits are twenty configurations per owner, one hundred grants per configuration
and a 2,048-character URL. Foreign-owner lookups return 404. Discovery transport
failures return 502 and timeout returns 504, without exposing provider error text.

Only Streamable HTTP can be configured through the API. Unknown fields, including
command, args and transport, are rejected. Optional stdio providers can still be
constructed directly by administrator-owned Python startup configuration using
MCPServerConfig; there is no user/model command execution endpoint or automatic
loading of a task-provided file. URLs refer to providers explicitly trusted by the
user and may reach the gateway's network, including local services. Do not expose
this setup API to untrusted anonymous clients.

## Runtime policy integration

get_extension_manager(runtime) returns the cached runtime.extension_manager.
ExtensionManager(runtime) also works directly. Synchronous configuration methods
are list_configs(owner), get_config(owner, name), create_config(owner, payload),
update_config(owner, name, payload), and delete_config(owner, name). Async methods
are discover_tools(owner, server), list_tools(owner) and
call_tool(owner, namespaced_name, arguments, mode, approved=False).

list_tools exposes only tools explicitly granted to that owner. Calls rediscover
the actual schema, validate arguments with jsonschema, and recheck the persisted
configuration revision immediately before dispatch. Remote schema references are
rejected and the validator has no remote retrieval registry. Malformed/missing
schema references, non-finite JSON, overdeep data and oversized payloads fail.
Arguments are capped at 64 KiB and results at 256 KiB; the default thirty-second
timeout covers connection, discovery and execution. Cancellation propagates and
there are no automatic retries.

For ask and assist, a user-declared read-only tool can run directly. Other allowed
tools raise ExtensionApprovalRequired with name, arguments (a JSON snapshot), and
configuration_revision. The broker stores these with its pending request ID and
binds approval to the exact arguments, owner, run and revision. It must refuse
changed/revoked configuration before replaying with approved=True; this boolean
is a trusted broker input, never an untrusted model argument. Full mode permits
explicitly allowed nonpayment tools without the extra mutation approval. Revoking
configuration does not undo an operation already dispatched.

All modes reject recognized payment/permission-grant tool names and arguments,
including common separator/camel-case variants. Read-only grants and external
annotations cannot override that check. This conservative pattern check cannot
prove what a malicious or misleading provider does: only connect providers whose
implementation and credentials the user trusts. Payment interfaces must remain
manual, and the system does not claim arbitrary MCP servers are payment-safe.

Tests cover authenticated owner isolation, persistence, empty defaults, explicit
read-only classification, approval snapshots, JSON Schema rejection, timeout,
cancellation, pre-dispatch revocation and a real HTTP provider through the API and
manager. The local HTTP fixture uses one event loop and bounded Uvicorn graceful
shutdown to avoid leaving Windows Proactor/SSE request tasks in a server thread.
