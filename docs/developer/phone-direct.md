# Developer Phone-Local Runtime

## Current Android path

The active phone runtime is `DirectRuntime → SplitTaskEngine`. It uses the
platforms and models saved in [model connections](model-connections.md), rather
than a fixed MiMo-only planner or the former `VisualAgentLoop`. Phone-local
orchestration still needs network access to the configured model APIs.

Use a developer build, open Settings / Model connection, configure the provider
and model, and enable phone-local mode. Grant the Android accessibility and
screen-capture capabilities required by the device. With visual enhancement on,
A decides the next action and stateless B grounds that action on a current image.
With it off, A returns the coordinates directly. Both paths use normalized
0–1000 gesture coordinates; A handles waiting, result review and recovery.
Do not enable the former `continuous_agent` or `feedback_operator` preferences
to select the current engine.

Current Skills are bundled or explicitly imported packages. Tasks do not
automatically accumulate or read the former learned/manual libraries; their
files and explicit SDK data access remain for compatibility. See
[Skills](direct-skills.md) and [legacy learning data](app-learning.md).

The 15 retired implementation files contain 17 top-level classes, including
`DirectTaskEngine`, `VisualAgentLoop` and `LocalVisualMotor`, and have moved to
`android/sdk/src/test/java/dev/doppel/sdk/legacy`. Their package remains
`dev.doppel.sdk`; they are JVM test sources, not classes in the production APK
or SDK AAR. The old `LearningLiveTest` and `PerceptionLiveTest` are archived under
`labs/legacy-direct-engine/androidTest`, outside the default Android test build.
`ObservationCoverage` collection, `CaptureObservationBinding` and `FeedbackMotion`
still have production callers. `ModelScreenSummary` remains a public SDK API,
but the current Split engine does not call it. Neither fact restores the retired
engine route.

The historical measurements below apply to their original snapshots, not the
current version. Consult each version's verification records for its results.

## Historical alpha.31 split behavior

The alpha.31 snapshot used `SplitTaskEngine` with provider routing (default A:
Qwen Flash without thinking; B: Qwen Max without thinking). See
[model connections](model-connections.md). The sections below describe the older
MiMo/DirectTaskEngine path; its image fallback and tool contracts must not be
assumed to apply to the current split engine. The Python gateway has not been
migrated to this split loop.

The shared alpha.31 swipe prompt separates content navigation from finger motion:
browsing down uses an upward finger path; an explicit left swipe uses right-to-left
motion. Revealing earlier content on the left normally requires dragging right.
A names the region, physical direction and expected visible change. Direct A and
B use 0–1000 paths (x right, y down); AB A still gives no coordinates. Physical
gestures such as pulling down a notification shade follow their actual path.
The executor does not flip directions. A reevaluates repeated ineffective swipes
and records only the directions actually tried.

With visual enhancement enabled, A plans from a screenshot. After its authorized semantic action is returned, the
host captures a **new image for stateless B**. Capture failure returns control to
A without using historical pixels. B receives the action, image, expected visible
effect and optional page relationship. B checks pre-action intent consistency and
returns concise observed facts. An inconsistent or uncertain result is returned to
A without dispatching coordinates. B remains stateless and cannot change the route.
Its assessment is included in A's action receipt, explicitly marked before_action;
actual outcome verification remains A's responsibility on the next screenshot.

With visual enhancement disabled, A produces normalized 0–1000 coordinates with
its action in one request, and B is never called. The same guarded executor checks
the action against A's original capture. There is no new B image, no second model
call and no fabricated B assessment. Waiting, cancellation, approval, result checks
and 500ms after confirmed actions remain available. Mode is stored per task as
execution_mode=ab/direct; historical tasks without the field retain AB behavior.

The current executor performs no extra pre-action screenshot or pixel comparison
for taps, double taps, long presses, swipes or swipe sequences. Flat regions,
background animation and pixel-verification age do not veto these gestures.
Receipts report `performed=false` with `action_pixel_check_disabled`, without
claiming `matches=true`. Native app/window/navigation, rotation, cancellation
and authorization checks still apply before dispatch. After a fully confirmed
action, `PostActionDelay` waits **500ms** before handing the result back for the
next screenshot. Cancellation during this wait preserves the completed action
receipt. A can still choose longer `wait` decisions when loading continues.

Receipts expose `action_completed_at_elapsed_ms`, `post_action_delay_ms` and
`visual_verification`; capture events record purpose and capture ID. These are
host diagnostics, not B prompt metadata. A judges the result and recovery route
from the post-action screenshot; a native accepted gesture is not proof of business completion.

## Historical MiMo implementation

The developer APK can run task orchestration on Android and connect directly
to Xiaomi MiMo. This is a separate connection mode. It does not require the
Python gateway, a server account, or a fabricated gateway bearer token.
Planning, screenshot understanding and ASR still call the remote MiMo API;
phone-local orchestration does not mean offline model inference.

That runtime linked user requests into a persistent conversation. Its
`DirectRuntime` preferred the visual actor when the device reported
available screen capture and visual gestures, excluding the assistant's own
surface. It requests a fresh screenshot when needed, then chooses an action or
result using that image and compact accessibility context. The SDK's
`DirectTaskEngine` constructor defaulted `visualControl` and
`preferVisualObservation` to `false`, preserving the semantic path for existing
callers. Without the required device capability, the app does not activate visual
execution. See [continuous conversations](continuous-conversations.md)
for scope, evidence boundaries and SDK calls. The older standalone read-only
interpretation/cache APIs remain compatible; see [perception efficiency](perception-efficiency.md).

Login-sensitive screens have a narrower, host-reported exception. Each current
Android observation can carry `screenshot_privacy` with source `android_host`,
reason `login_sensitive`, and matching screen ID, package and capture timestamp.
While that state matches, the engine uses the existing redacted semantic path,
clears current and historical images and pending visual questions, and omits
`inspect_screen`/`visual_action` from the offered tools. A new observation without
that matching state can restore normal visual preference. This state grants no
login, verification or payment permission, and accepted inputs are not replayed.

For a combined observation requesting pixels, the host may return only redacted
semantics with `data.screenshot_omitted_reason=login_sensitive`. The engine exempts
missing pixels only when that explicit reason and the current observation's
privacy state both match. Direct screenshot requests retain the existing privacy
rejection; other missing-image failures still pause. Required post-gesture visual
verification cannot be replaced by this omission. Local credential settings,
security verification and payment gates remain in force. This change has synthetic
regression coverage; it does not establish successful real-account login.

The developer app also provides [persistent schedules](schedules.md) for once,
interval and timezone-aware cron tasks, with a visible task-management entry.

That older runtime supported semantic-route learning and explicit demonstrations.
It is no longer connected to current tasks; [legacy learning data](app-learning.md)
documents the retained files and SDK compatibility boundary.

## Historical MiMo setup

On Android 8–10, also authorize **Settings → 屏幕识别** to recognize image-only
interfaces, including games. This uses the standard system screen-sharing prompt
and a foreground notification with a stop control. Authorize again after stopping
screen sharing or after the app process exits. Android 11+ uses accessibility
screenshots without this extra session. See [device compatibility](device-compatibility.md).

Use a debuggable APK whose application metadata contains
`dev.doppel.DEVELOPER_BUILD=true`. Product debug APKs and all release APKs
cannot activate this mode. In Settings / Model connection, save a MiMo API
key and enable phone-local mode. The current task must be finished before
switching connections. Switching back restores the previous server device
binding and preserves its URL and token.

The credential screen uses `FLAG_SECURE`, disables saved view state and
autofill for the key input, never reveals a stored key, and is excluded from
Doppel observation and screenshot execution while visible. Only AES-GCM
ciphertext and its nonce are written to preferences. Android Keystore owns
the encryption key. Provider credentials are not a release distribution or
shared billing mechanism; use the gateway for those scenarios.

## Historical MiMo flow

- Typed and long-press voice task creation; local task list, detail, events,
  deletion, pause, resume, cancellation, questions and approvals.
- Planning: `mimo-v2.5-pro`; auxiliary screenshot understanding:
  `mimo-v2.5`; complete-file ASR: `mimo-v2.5-asr`.
- One device command at a time through `DeviceWorkerService`, its durable
  `ResultStore` ledger, and `DoppelAccessibilityService.execute`. No second
  touch executor is introduced.
- Observe compact accessibility nodes, discover launchable apps, launch,
  tap, long press, text/local login input, scroll, wait, and supported global
  actions (back, home, recents, notifications, quick settings, split screen).
- On capable devices, screenshots and gesture tools are sent together to the
  MiMo V2.5 visual actor. A proposal directly describes one tap, long press or swipe.
  The host validates frame identity, geometry, pixels, authorization
  and a single-use execution permit before dispatch. Canvas support is implemented;
  actual game performance is assessed separately from unit tests.
- [Public web search/reading](web-research.md) and [on-demand Skills](direct-skills.md),
  including user-selected SKILL.md/ZIP imports and bundled Arknights references.

The planner receives at most 220 node rows, stopping after the row that reaches
the approximately 22,000-character screen budget. Action `target` enums contain
only enabled, non-password, actionable string IDs present in those same rows;
labels are not target references. IDs are deduplicated and limited to 120
characters. The `action` tool requires both `kind` and `target` for node operations;
a screen with no eligible targets omits this tool entirely. `navigate` requires
`kind` for observation, waiting or global navigation without a target. `launch`
requires `package_name`, enumerated from the device's launchable application list;
the tool is omitted when that list is empty. Inspection and questions remain
available. The provider can still return invalid arguments: the host rejects
missing references, unknown/disabled targets, unknown applications and mismatched
capabilities before dispatch. The host does not turn labels into target IDs or
silently replace an invalid node target with coordinates. Missing tool calls and
some visual-format errors can instead enter a bounded protocol-correction path:
the rejected action is not executed, a fresh observation is requested, and another
model call may be made. Those correction calls consume time, call budget and
provider credit.

Scroll directions come from each device node's advertised Android action IDs,
not from `scrollable=true` alone. A recorded failure requested down/forward
(4096) from a node that advertised backward (8192) but not forward. The executor
now selects a declared directional action, or a declared legacy up/down action
only when no directional actions exist; it never infers horizontal movement
from legacy forward/backward. Directions travel in `result.data.scroll_directions`
keyed by node ID. The direct host merges valid entries into a fresh planning copy,
discards old direction metadata and rejects unknown or unavailable directions.
Scroll-only targets without a known available direction are not offered. The
device checks capabilities again before execution; an unavailable direction
returns `no_op=true` and is not counted as a successful mutation. The wire
`observation.nodes` schema remains unchanged.

MCP/hosted extension execution, spreadsheet batching and hosted file
storage require the gateway. Server accounts, commercial quotas and purchases
additionally require the private product service; the standalone public gateway
does not supply those commercial features. Direct mode reports unsupported
endpoints unavailable. It never implicitly contacts the
previous gateway. Accepted screenshots are stored in a private no-backup local
gallery: default seven-day retention, at most 30 images per run and 64 MiB total.
The existing screenshot list/image/delete interfaces serve that gallery, and
deleting a task removes its images. Screenshots that older versions never stored
cannot be recovered.
The worker's existing result ledger retains its normal private recovery data;
deleting a task also uses the client's existing ledger deletion path.

## Historical engine state and authorization

`DirectTaskEngine` is an Android-independent state machine. `DirectRuntime`
persists bounded summaries/events using `AtomicFile` under `noBackupFilesDir`.
At most 50 recent tasks are retained; older terminal tasks are pruned earlier
when serialized state exceeds 1.5 MB. Screenshots and model credentials are
not part of this state. Recovered unfinished tasks require a fresh observation
on explicit resume. Already-paused tasks retain their recorded reason; old
pending requests and commands are removed. No uncertain action is automatically
replayed after process recovery.

Polling returns the same outstanding command ID until its result arrives.
This lets the worker defer for a visible voice/task window before claiming
its ledger. The ledger is responsible for not executing an already claimed
ID again. Unconfirmed results pause after 60 seconds. A stale result discards
the old command, approval and completion evidence, then requests a fresh
observation for new planning. It does not replay the old action. The third
consecutive stale result pauses; only a successful non-no-op mutation resets
this counter. Blocked, interrupted and failed results still pause immediately.
Successful mutation counts exclude observations, waits and already-set controls.

The planner and visual inspection share the references the planner has
already loaded, plus a separate bounded execution history. The host history keeps
the last eight accepted, non-no-op actions even when frequent observations
replace the last fourteen UI progress events. It records action kind, source
and result provenance, bounded target descriptions, and up to 1,000 characters
per associated visual interpretation. Command arguments, typed values, worker
messages, coordinates, screenshots and execution permits are not copied into
these receipts. Public run summaries omit this internal history.

`ModelActionHistory` merges accepted-action receipts with host effect records and
presents at most six recent actions to the model, with short effect descriptions.
Input values are omitted, and historical positions never authorize a new action.
This is a model-context projection; it does not truncate the full diagnostic
fields retained locally under the existing sanitization and retention limits.

The visual actor can also receive up to two historical source images, each paired
with the actual action whose non-no-op result the host accepted. Rejected proposals
are not action history, and these images are not independently captured result
frames. The image-byte budget may omit older images. Historical images are ordered
oldest first; the current image is always last. This short in-memory image history
is cleared on invalidation, including completion, pause and resume, and does not
expand the private screenshot gallery's retention policy.

`ActionProgress` suppresses a third tap or long press at the same target only when
the last two accepted actions reliably produced `unchanged` evidence and the
current page still matches. A host-bound supplemental capture can fill the latest
action's missing result frame. Unknown effects remain unknown; different pixels
on an opaque or animated page do not prove progress. This is neither a general
loop detector nor a verifier of complete list coverage, and page changes do not
prove business success. See [adaptive visual control](../architecture/adaptive-visual-control.md).

Visual interpretations remain untrusted historical data. A canvas can keep the
same accessibility screen ID while changing every pixel: attaching a preceding
interpretation therefore also requires a matching capture or image digest.
Post-action interpretations bind to the originating command and the exact
capture and observation evidence held by that model request. A supplemental
read can finish a missing result frame without creating another action receipt.
Ordinary inspections cannot overwrite that action's interpretation. Generation
checks discard late replies after pause or cancellation. History survives
process recovery but cannot restore commands, targets, approvals or completion
evidence. Older runs without this history start it from newly accepted actions;
human-readable progress messages are never migrated into accepted receipts.

Atomic action grounding has a separate input boundary: it receives the current
image, trusted image dimensions and one action intent, without the overall goal,
loaded references or execution history. Old visual interpretations can contain
incorrect position guesses even when marked untrusted. Keeping them outside this
request prevents their automatic reinjection into coordinate estimation; an
intent written by the planner can still contain a mistaken positional hint.

The model's three typed proposal tools return integer pixel positions within the
current image, including both endpoints for swipes. The host rejects fractional,
non-finite, negative and out-of-image positions, then converts them using the
device-owned image dimensions into the existing normalized `VisualGesture` wire
format. Model-supplied dimensions are not accepted. Unknown fields, extra tap
endpoints, frame identity, gesture bounds, authorization, single-use permits and
fresh pixel checks still apply. A schema-valid coordinate does not prove that
the model located the intended control; the resulting screen must be checked.

The engine's local interruption invalidates active planning responses and queued
actions. It preserves an already pending approval/question so that the existing
`TaskControl.answer` preflight suspension cannot lose the request. An explicit
pause/cancel control clears pending requests; resume plans from a fresh screen.
System interruption handling can issue that pause control. Approval
answers first obtain a fresh screen; changed screen or revoked consent
invalidates the approved action. Screen IDs, command IDs and payment consent
are host-owned. The model cannot stamp approval or financial authorization.
Completion must cite the current screen ID and the latest successful device
result ID. This validates evidence provenance, not whether a model's summary
is semantically correct; acceptance tasks must still verify actual outcomes.

Ask/assist/full modes retain device policy and host approval checks. Payment
consent is disabled by default and revalidated at approval and execution;
payment passwords, verification, transfers and persistent mandates require
manual handling. Real payment and personal social-account tests are excluded
from development verification.

Call/alarm detection and OEM capability checks use the shared Android executor;
they are bounded heuristics, not a universal interruption detector. See
[Device compatibility](device-compatibility.md) for API-version differences,
unknown-window limits and Xiaomi background-window permission requirements.

## Historical MiMo bounds and cost

Provider calls use the fixed audited HTTPS endpoint, redirects disabled,
bounded bodies/responses and a total disconnect watchdog (45 seconds for
planning/vision, 25 seconds for ASR). Failed transport requests are not
automatically retried. Protocol correction is separate: missing tool calls and
eligible visual-format errors each allow at most two consecutive corrections,
with the respective counter reset after a valid response. Re-observation and
the following paid model calls use the normal task budget; correction is not free.
Each task has a lifetime cap of 120 planner/vision calls and two hours.
Planning context includes a bounded current screen, installed launchable app
list and recent events. ASR accepts standard 44-byte-header PCM16 mono 16 kHz
WAV recordings up to 30 seconds, with two concurrent requests and at most one
preview. Raw audio is not persisted by the direct provider.

Direct calls are charged to the supplied MiMo account, outside Doppel points.
Transport interruption can leave provider billing uncertain. The runtime
pauses and asks for explicit continuation rather than assuming the request
was free. Local token counters reflect reported planner/vision usage and are
diagnostic, not an authoritative provider billing ledger.

## Historical verification

`DirectTaskEngineTest` covers dispatch, pre-claim polling, stale planning,
approval refresh, process recovery, payment authorization, missing toggle
state, unknown device results, interruptions, unsupported tools, paid-request
failure, finish evidence and ask-mode launch. `DirectPayloadTest` checks
provider adaptation, ASR shape and host-only authorization fields.
Payload fixtures also cover eligible target IDs, empty target sets, both screen
truncation limits, observation replacement and compatibility of the static schema.

The 2026-09-09 local reports for `ActionProgressTest`, `AdaptiveControlEngineTest`,
`AdaptiveVisualEngineTest`, `VisualActionHistoryTest`, `VisualAgentLoopTest` and
`ModelActionHistoryTest` pass the tested effect, history and recovery contracts.
These reports do not establish complete business-task success in third-party apps.
The separate LDPlayer [device-lab screenshot smoke](device-lab.md#已验证范围)
verifies capture and local evidence recording only.

Developer-app `DirectRuntimeFlowTest` uses a synthetic key, restores prior
preferences/Keystore presence, and verifies encryption, connection switching,
server-feature isolation and the secure screen. It does not invoke MiMo or
execute device gestures. Run it only with no unfinished developer task.
Android compilation, instrumentation, emulator and explicit real-device
acceptance are verified separately; source tests alone do not prove live API or
device workflow completion.

The final recorded MuMu Android15/API35 run used the real MiMo provider to open
Android Settings and report the device model. It completed in 9.187 seconds with
three model calls, one successful mutation and 11,138 reported tokens. The final
foreground package was `com.android.settings`, and the answer matched the
emulator's reported model `PFGM00`. This used no gateway fixture. MuMu reports an
OPPO device profile; this is still emulator evidence, not physical OPPO/OEM
acceptance or a latency guarantee. Temporary credentials, task state, consent,
preferences and accessibility settings were cleaned up or restored. The earlier
false-return failure was traced to requesting an unadvertised scroll direction;
capability reporting and preflight checks now enforce the declared directions.

## Pause explanations (alpha12)

Repeated `pause` on an already paused run preserves its original message and
pending explanation. Opening the companion or a notification does not replace an
error with a generic user pause. The client also reads the current status before
issuing a pause, including when connected to an older gateway.

`PausePresentation` produces a reason category, the recorded explanation and a
suggested next step. Known runtime limits, screen uncertainty, permission needs,
system interruptions and transport failures have distinct presentation. Unknown
or missing explanations remain explicitly unknown; presentation never grants a
permission, infers task success or resumes a task.

The device worker captures takeover and local execution failures before it stops
polling. Non-user pauses produce a persistent bottom action card and a private system
notification, subject to Android permissions. The card shows a pause icon, the
reason, contextual advice and two direct actions: End and Continue. Its full
bounded explanation scrolls while the actions remain visible. Outside taps and
switching applications do not dismiss it; there is no close or details button.
The companion shows a short reason without repeating the paused state in text;
the task panel and conversation show the full explanation. A bounded local stop
receipt supports the same task's details while the gateway is unreachable. It
contains no goal, input values, credentials or screenshot and cannot be applied to
another run or a terminal task. Explicit continuation clears that receipt and
notification; a later pause with the same reason can notify again. Deleting the
corresponding task through the client clears its local receipt.

Pause notices share the existing overlay capture hiding and lifecycle cancellation
with completion delivery. Polling does not re-announce an unchanged pause, and a
late callback cannot display an old notice after continuation or task replacement.
Both actions use the ordered task-control queue with single-flight UI feedback.
The card remains during acknowledgement and on failure; continuation starts the
worker only after a confirmed running state. Ending clears the active task and
its local pause receipt only after confirmation. Existing task authorization and
model limits remain in force. Notifications and the companion reveal the same
card; when overlay permission is unavailable the bottom Activity provides the
reason and the same two actions, and outside touches do not finish it.
System interruption notices are delivered locally before waiting for a pause
acknowledgement. A delayed or failed acknowledgement must not remove the notice;
repeating pause preserves queued and visible explanations, while continuation
and cancellation retain their normal dismissal behavior.
