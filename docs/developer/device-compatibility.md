# Device capabilities and interruption handling

The Android host owns execution and interruption checks. Device-specific
information helps planning; it does not establish compatibility with every
manufacturer's system windows or authorize interactions with unrelated content.
These mechanisms are shared by gateway and developer phone-local tasks.

## Reported environment

### Screen capture by Android version

Android 11+ uses the accessibility screenshot API. Android 8–10 uses the system
MediaProjection consent flow, reached through **Settings → 屏幕识别** or onboarding.
Granting accessibility alone does not authorize this older-system capture source.
The companion reports `screen_capture_source` and `screen_capture_ready`; visual
gestures also require the platform gesture capability. Missing projection consent
returns `blocked` with `data.human_takeover=screen_capture_required`.

Projection runs in a visible foreground service with a stop control. Sharing
does not end when a temporary voice or task sheet removes its own Android task;
the notification stop control, system revocation and local consent withdrawal
end sharing explicitly. The service never restarts itself after process death. Consent data
is kept in memory, never exported or restored after process death. One virtual
display serves the authorized session. Idle images are immediately closed without
decoding or saving. Each read attaches a newly created ImageReader and output
surface to that display, giving static screens a fresh output queue without
redrawing or resizing the user's screen. The request timestamp boundary is armed
before attaching the new surface. After Doppel hides its visual feedback, a read accepts only
a new frame from that output generation and request; completed frames are never cached
for a later request. Geometry changes replace the reader and invalidate pending
reads. Cancellation or a read timeout ends the request; stopping screen sharing
or withdrawing consent releases the mirror itself. Android 11+ does not use this
legacy path. Device resolution and density are never reduced by
the capture feature; the image sent to the model may be resized independently.

Both sources share sensitive-login exclusion, empty-frame readiness, page and
geometry checks, screenshot provenance and pre-gesture pixel validation. A
successful capture is not permission to operate a protected page or evidence
that an action succeeded. Emulator coverage does not establish OEM compatibility.

An explicit observe command includes `device_profile` and `window_layers`.
The profile records manufacturer/model, Android API level, bounded build label,
display density and global actions. On API30+, actions are filtered against
`AccessibilityService.systemActions`; dispatch checks availability again.
Earlier APIs expose the known platform action set and rely on the actual
`performGlobalAction` return value. Split screen, quick settings and other
system actions may be absent or rejected by a particular device.

At most 12 window layers are reported with type, package, bounds and active/focus
state, excluding Doppel accessibility overlays. Observation prefers the focused
application/system root, then the active application root. Window identity,
focus and bounds changes invalidate stale node references. A successful global
action returns a settled observation; an accepted platform call does not prove
that the desired visible result occurred.

Node scroll support is also directional. A captured MuMu failure requested
ACTION_SCROLL_FORWARD (4096) from a node that advertised only backward (8192)
among legacy scroll directions; `isScrollable` alone had incorrectly admitted
that request. `ScrollCapabilities` now selects an advertised directional action
first and uses legacy up/down only when no directional actions are declared.
Legacy actions never imply horizontal support. The host rechecks the action list
before dispatch and returns a no-op if the direction is unavailable. Direction
declarations affect screen freshness and are returned as
`result.data.scroll_directions = {node_id: [directions]}`. They do not add fields
to the strict wire Node schema. The direct planner consumes a validated fresh
copy, labels missing metadata unknown, and cannot reuse a previous screen's
directions. An advertised action can still be rejected by a changing application;
an accepted action still requires observation of its visible result.

There are no assumed coordinates for a manufacturer's notification shade,
control center, floating window, minimize or close gesture. Visible accessible
controls may be used within the task's authorization. Unavailable controls or
uncertain intent require explicit user input. Reporting a window layer does
not mean every overlay has been classified or can be dismissed automatically.

## Interruption triggers

| Signal | Implemented response | Remaining limits |
| --- | --- | --- |
| Audio mode is ringtone, in-call or in-communication | Block device commands; API31+ mode-change listener also pauses an active worker | Communication mode can conservatively pause a non-call audio session; earlier APIs lack this listener |
| Recognized incoming-call UI | Package and visible-label checks pause and request user handling | Known dialer/telecom/SystemUI patterns are not a universal carrier or VoIP classifier |
| Recognized ringing alarm | Known clock/alarm/SystemUI packages and ringing labels pause | Ordinary alarm settings do not trigger; other languages or OEM layouts can be missed |
| Recognized CAPTCHA/verification UI | Pause for manual completion and explicit continuation | Recognition cannot guarantee detection of every protected or unlabeled challenge |
| Manual takeover touch | Invalidate planned action and pause through the existing touch guard | System gesture regions and vendor-specific protected windows need device validation |
| Unknown window or unreadable obstruction | Current-screen validation and model-visible context remain available | No universal unknown-obstruction detector, automatic ad closer or guaranteed pause exists |

The host does not automatically answer/reject calls, dismiss ringing alarms or
complete verification challenges. Pausing invalidates pending planning/actions
and removes execution feedback, but cannot undo an operation already accepted
by another application. Resume obtains a fresh observation. There is no blanket
promise of silent background operation, automatic unlocking or continuous
operation while a device is locked. Completion speech separately suppresses
playback during calls and lock transitions; that audio guard is not an unlock
or full device-execution policy.

## OEM permissions

Android overlay permission and a manufacturer's permission to open an Activity
from the background are distinct. Xiaomi/Redmi/POCO builds offer a user-facing
background-window settings entry. It tries the system permission editor for the
current package, then falls back to Android application details if unavailable
or inaccessible. Other manufacturers use application details.

The application cannot infer a granted background-window permission from the
standard overlay grant and does not write undocumented AppOps. APK replacement
can change vendor permission behavior. Notification/task-screen controls remain
alternative entry points, subject to the user's notification permissions and
the OEM's normal restrictions. Returning from a settings page is not evidence
that the user granted the requested capability.

## Verification scope

Alpha10 source includes `InterruptionPolicyTest` fixtures for recognized call/
alarm signals and false positives, plus direct-host interruption and stale-result
state tests. Those fixtures do not trigger a carrier call or a real alarm.
The integration environment includes MuMu Android15/API35 and Xiaomi14Pro
Android16/API36. Xiaomi background-window restrictions were reproduced during
that integration; this is evidence of a device-specific requirement, not a
cross-vendor compatibility pass.

No Huawei, Vivo, OPPO, Honor, Samsung or other OEM acceptance is established by
these two environments. A release claiming support must separately test actual
incoming/ongoing carrier and VoIP calls, ringing alarms, notification overlays,
permission revocation, lock/unlock, background launch and OEM window controls
on its supported versions. Interrupted tasks must preserve their state without
replaying uncertain mutations. See [Phone-local mode](phone-direct.md) for its
additional capability and billing limits.
