# Visible execution and local login assistance

## Companion and voice controls

`CompanionOverlay` owns the draggable, edge-docked entry. A tap pauses local
dispatch and opens `VoiceActivity` over the current app with
`EXTRA_OPEN_KEYBOARD=true`. The composer requests editor focus and the keyboard
once its window gains focus. Returning from settings does not repeatedly show a
keyboard the user dismissed; a new companion tap is a new input request.
Activity recreation preserves the editor text, and reused windows consume new
entry intents. Text is not submitted by opening or focusing the composer.
The companion is temporarily invisible and non-touchable while the keyboard
editor is visible, so it cannot obscure editor controls. Hold mode retains the
original overlay pointer. Screenshot hiding and editor hiding are independent;
restoring a screenshot cannot reveal an entry hidden by the editor.

When a run exists, the composer's current-task icon opens `TaskPanelActivity`,
which remains reachable from the notification. It shows server state,
pause/resume/stop, and the current approval or question. Nonempty composer text
is transferred as pending input; an empty editor does not erase the existing
panel draft. Separate task affinities keep both surfaces independent of the host
conversation. A terminal run clears the matching active-run reference.

The unreleased alpha.5 source uses a slow chromatic border for idle and queued
states and rotating arcs plus a narrow, non-touchable screen perimeter for
execution. Local pause overrides an old running response. Pause, approval/input,
completion, failure and disconnection have stationary labels/accents; disconnected
workers do not retain the executing perimeter. A successful current-task poll
restores the connected state even when no device command is ready. These signals
represent task state, not completion percentage or business-result evidence.

`SpectrumSurface` keeps repaint scheduling local to its view and stops continuous
motion when detached, hidden, system animation is disabled, the display is not
interactive or power saving is active. State remains visible without motion.
Density changes rebuild companion dimensions; rotation redocks within current
screen bounds. Shared `UiDialog` and `UiTheme` controls give application-owned
selection, confirmation, settings and input surfaces consistent styling while
retaining their original actions, focus and accessibility semantics. Android's
permission and keyboard windows retain system behavior.

Only an explicit pending question accepts text through `/answer`; pending
approvals retain their request ID. Text entered at other stages stays in the
local `companion_draft` and is labelled unsent. There is no general protocol for
amending an executing goal. Manual verification/payment remains a takeover flow.

`TaskControl` first suspends local dispatch, then serializes pause/resume/cancel
and answer requests in one process. A generation token rejects superseded
requests before HTTP dispatch, after the resume state query, and before local
worker startup. Superseded callbacks still complete with a
superseded error so controls can leave their busy state. This does not cancel
an HTTP side effect already sent or establish ordering across devices.
If the server is running but the device is paused, both the main client and
panel offer explicit Continue. It first queries current state in the control
queue, posts resume only for a paused server run, and then starts the local
worker. Hidden or destroyed panels do
not resume execution from a delayed response. Dispatch is withheld while the
task panel or voice Activity is visible.

A companion hold hands one `VoiceGestureSession` to the visible voice Activity;
the overlay retains the original pointer. A valid release submits its matching
final text once, including a final callback that arrives after release. Sliding
left or right discards that hold. The threshold shortens toward a nearby screen
edge so a docked entry can cancel in either direction. Vertical movement alone
does not cancel. Release before recording starts,
permission interruption, or Activity teardown cannot submit a stale result.
Granting microphone permission requires a fresh hold. The hold surface contains
only status, transcription and activity feedback. Cancellation flashes a rose
accent, provides a haptic, and dismisses in the swipe direction; reduced motion
uses a brief static cancelled state. Tap-to-type retains its editable composer.

`VoiceHoldState` models holding/released/cancelled and one-time result consumption.
`SpeechCapture` rejects stale recognition generations and releases resources on
exit. Existing active tasks route voice text to the task panel, subject to its
pending-question/draft rules. `TaskSubmissionGate` is shared by the conversation
and voice pages; it prevents overlapping creation within one process, not server
duplicates after process death. Finalized hold text starts the foreground worker
with a submission intent while the Activity is visible, then immediately closes
the capture Activity. The service owns HTTP creation, keeps dispatch paused until
creation completes, retains a failed submission as a draft, and checks the control
generation before execution. Keyboard submission persists the pending
run ID and creation control generation; the resumed Activity fetches current
run state before starting a worker. A newer pause or stop invalidates automatic
startup even when an earlier response reports running. A different process
requires explicit Continue for the retained task. Queued voice runs remain
pending and terminal runs never start device execution.

Cloud microphone capture uses `mimo-v2.5-asr` through the configured authenticated
gateway. It buffers at most 30 seconds of 16 kHz mono PCM16 in memory. Complete WAV
snapshots supply provisional transcription while held; release recognizes the
complete captured audio and does not submit a partial hypothesis on failure.
MiMo streams text output, not continuous microphone input. Preview calls are
coalesced; cancellation disconnects requests and rejects late callbacks. Already
accepted upstream requests can still be billed. API keys never enter the APK.
Audio and transcriptions are not retained by the speech route; finalized task
text follows normal task retention. See `cloud-speech.md` for the HTTP contract,
limits, points conversion and validation evidence.

Sheet windows request Android background/behind blur on API 31+, including
Android 14, only when cross-window blur is available. A system listener updates
the opaque/dim fallback when availability changes; older versions use that
fallback directly. Text stays sharp and no screenshot is sampled. The compact
entry uses a styled drawable; its appearance is not evidence of compositor blur.

## Device feedback

Android settings `action_feedback` defaults on. Validated tap/text targets get a
small target dot and a thin chromatic ring, long press an inner arc, and scroll
an arrow with a short moving accent inside the scrollable node. The target dot
is cyan for an attempted action, green when Android accepts it and red on failure.
Acceptance is not proof of the business outcome. System-disabled animation keeps
the target and direction visible without continuous redraws.

The feedback overlay and executing perimeter are non-touchable and excluded from
accessibility observations. Action feedback expires within 900 ms and clears on
pause, cancellation, teardown, or takeover. Screenshots wait for feedback removal
and hide both companion and perimeter pixels. The transparent companion retains
its touch region during capture; a touch can still pause and open the composer.
Capture restores the companion in `finally`, and a worker/companion revision
change returns stale without image data. Coordinates are clipped to the node and
display. Scroll arrows express the requested direction, not a recorded finger
trace. Long press uses `ACTION_LONG_CLICK`; Android controls its duration, so the
decorative arc is not a measurement of finger dwell time.

## Verification takeover

Host and device check explicit CAPTCHA/security challenge instructions before
mutations. Detection pauses the task with `human_takeover = "verification"` and
removes touch interception. The user completes verification and explicitly
resumes. The host discards old observations and obtains a fresh screen before
model work or another action. A remaining challenge pauses the task again.

This is not a CAPTCHA solver or an anti-detection layer. Ordinary menu entries
and SMS login fields should not be treated as active challenges. Detection uses
observable labels, so image-only or unusual challenge pages are not guaranteed
to be detected. Existing scope, payment, and repetition guards remain active.

## Local profiles

Open Login Settings, unlock with the existing vault PIN (or create it on first
use), then add an application and choose **account/password** or **SMS code**.
New applications are enabled by default; each application's switch is on the
outer list. Editing preserves its enabled state. Switching methods preserves
both sets of saved information, while local execution permits only the selected
method. No SMS service name or signature is required. An optional per-app phone
overrides the common number. Password entries remain in the original encrypted
CredentialVault with the same PIN and keys; selected account IDs preserve
multiple saved accounts without choosing an arbitrary first account.

Existing single-method authorizations retain their choice and enabled state.
Conflicting legacy password and SMS authorizations require the user to choose a
method before automatic filling is enabled. Saving the merged configuration
migrates only method/authorization metadata, not password ciphertext.

The planner can report `manual_takeover` with structured `reason: "login"`.
Login interruptions show a **设置登录方式** button on the pause surface and task
details, including the first interruption. It opens this same protected page,
optionally targeting the observed application. Entering or leaving settings does
not resume a task; the user explicitly continues after configuration. Local
login takeover receipts preserve this reason and request identity across reloads.

Enable notification access in Android system settings. The listener considers
only new notifications from the current default SMS app during an armed login
session. It does not read the SMS database or archive notification bodies. It
requires the expected foreground app, a clearly identified 4-8 digit code, and a
timestamp after the session began. Code extraction handles incidental numbers
such as a phone suffix or validity duration. Company names need not equal the
application name. Multiple messages appear as redacted candidate contexts and
opaque IDs for A to select; raw OTPs remain local. Payment and transfer messages
are excluded. Hidden notification contents and unresolved ambiguity require manual entry.

In the current A/B and direct flows, A marks a send/resend login-code tap with
`request_login_code: true`. The existing dispatcher arms the five-minute app/run
session immediately before the actual gesture, including when the phone number
is already filled. Resending invalidates old candidates. Rejected or unconfirmed
gestures cancel only their own request window. This adds no model call; B still
only locates the requested control. Legacy `login_phone` callers retain their
existing session start as a compatibility fallback.

| MCP/Harness tool | Inputs | Device operation |
| --- | --- | --- |
| `device.login_phone` | `target`, `screen_id` | Fill an opted-in local phone; arm a five-minute app/run session |
| `device.login_code` | `target`, `screen_id` | Consume one fresh matching code and fill the login input |
| `device.act` with `long_press` | `target`, `screen_id` | Run a validated Android long-click action |

The host derives `package_name` from the current observation. Phone and OTP text
are not tool arguments. Login commands reject non-null `text`, foreign packages,
stale screens, sensitive inputs, and targets without phone/code field semantics. Missing profile/code
causes login takeover, not an automatic retry loop. Consumption happens before
dispatch, preventing blind replay after uncertain delivery.

Active phone/code values are redacted from observations. Code input text is
redacted independently of session expiry. Screenshots are blocked during an
active session or while any code field is visible. The local profile settings
window is protected from screenshots and automated edits; editable values are
excluded from observations. Codes remain only in
process memory: usable for five minutes, with a ten-minute redaction window and
periodic cleanup. They are never written to profiles. Notification matching is a
compatibility filter, not cryptographic proof of SMS sender or recipient.

## Tests and limits

`dev.doppel.testapp/.InteractionFixtureActivity` supplies synthetic login,
verification takeover, and gesture flows without network permission. Only debug
clients accept its notifications, and only while the same fixture is foreground.
Release clients accept the default SMS app.

SDK JVM tests and `LoginAssistTest`/`ActionFeedbackTest` instrumentation cover
matching, expiry/replay, redaction, real text insertion, long press, overlay
lifetime, and challenge blocking. Real carrier SMS and manufacturer-specific
notification behavior require physical-device acceptance.

The standalone developer gateway reports `unlimited: true` from authenticated
`GET /v1/points`. Usage totals cover retained task history and estimate points at
300 tokens per point. Unlimited points do not waive model-provider charges.
