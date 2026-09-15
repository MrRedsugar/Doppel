# Public System Architecture

The public repository contains framework/ (Python), android/sdk/ (Kotlin),
android/developer-app/ (reference UI), android/test-app/ (isolated fixtures),
examples and developer docs. It runs independently with the developer gateway;
there are no account, billing or commercial-client modules in this source tree.

## Execution

An authenticated user creates a task for one owned device. DoppelRuntime persists
the goal, approval mode, optional package scope and lifecycle. HarnessPort starts
the official DeepSeek Harness with a minimal environment and registered host tools.
The Harness can call only the exposed MCP tool adapter; shell/filesystem/tool
plugins from its general runtime are disabled. A separate task bearer credential
authenticates internal model/tool requests and does not grant user configuration
access. The gateway holds the model credential and forwards metered requests.

The broker validates tool arguments, run state, permission mode, package scope and
current observation before queuing Android commands. One device has at most one
active task. Commands have IDs, run IDs and stable target/observation references.
Android independently checks stale targets, sensitive controls and supported API
capabilities, records uncertain command outcomes before execution, then sends the
observed result. Repeated IDs return recorded results without replaying side effects.

Task states are queued/running/paused/awaiting_approval/awaiting_input/completed/
failed/cancelled. Pause/cancel stop dispatch and propagate to the worker. They do
not undo an action already sent. Restart marks interrupted runs failed, preserving
the need to inspect uncertain external state. Approval binds a pending request ID
to exact arguments and observation or extension configuration revision. A later
screen/configuration change requires new observation/review.

## Observation and Completion

Android emits bounded semantic nodes with text, roles, interaction properties and
stable IDs. Password contents are redacted. The gateway presents compact context;
older screen content may be reduced while retaining the task and action outcomes.
Screenshot/auxiliary vision is an explicit capability and not universally available
on all supported Android versions. APIs report unavailable states truthfully.

Completion requires host-issued evidence associated with the same run and actual
successful tool results. Current device evidence and document identity are checked
again before finishing. Natural-language model confidence alone does not mark a
task completed. Once finished, host completion state can close the model loop
without issuing another paid model request.

## Permission Boundaries

ask requests approval for changing external state. assist applies deterministic
host/device policy within granted capabilities. full permits the user's authorized
scope while preserving payment/manual-takeover checks. External extension mutations
have their own explicit grants and approval policy. Package scope limits launch
and interaction; observation/recovery operations remain available as documented.

Screen text, document cells, Skills and provider replies are untrusted task data.
They cannot grant new tools, owners, file roots, permissions or model credentials.
MCP provider name/annotation checks cannot prove a malicious provider's actual
behavior; users must trust its implementation and credentials. Delegated payment
is off by default and can be enabled only through the local Settings risk flow.
With valid local consent, ask/assist retain operation approval and full may attempt
an ordinary purchase payment. Credentials, transfers and persistent debit settings
remain manual. The gateway stamps consent from the device observation; Android
rechecks the current generation and exact screen, and durably limits attempts to
one per task/application pair. Model and extension inputs cannot grant consent.
Accepted actions do not establish a charge; subsequent payment/order evidence is
required. Conservative keyword/context checks are not a complete classifier for
every unlabeled interface. See [Delegated Payment](../developer/payment-delegation.md)
for revocation, storage failure and multi-step checkout limits.

## Extensions and Documents

Skills load metadata first, then instructions/resources on demand; scripts are
never automatically executed. Python/Node dependencies remain unverified until
the host explicitly checks them. Remote Streamable HTTP extension configuration
is user-authenticated and owner-scoped, with empty grants by default. stdio is
administrator startup configuration only. Discovered tool schemas are validated
without remote schema fetching, and deadlines/cancellation are enforced.

Authorized Android document streams are copied into an owner workspace. Batch
workbook inspection returns headers/samples/statistics; structured transformations
create a new .xlsx, reopen it and return a new document URI. Unsupported macros,
external links and feature-preservation cases fail. No formula recalculation is
claimed. WPS opening/verification is a separate device action with explicit access.

## Persistence and Packaging

SQLite/WAL stores devices, tasks, commands and events. Owner-scoped retention
defaults to seven days for terminal data; cleanup uses a durable recovery queue,
removes Harness context copies and creates a device cache-purge outbox. Screenshot
reads use authenticated no-store responses. Documents and managed memory have
separate data-management boundaries. No claim is made about deleting remote-provider
copies or operating-system backups.

The public export uses an explicit allowlist, independent Python pyproject and
public-only Gradle settings. Original code is Apache-2.0; dependencies retain their
notices. Fresh-install Python tests and SDK/developer/test-app builds validate the
public boundary; simulated fixtures do not establish real-app/device compatibility.
See HTTP protocol v1 and individual developer documents for exact interfaces.
