# Visible execution and local login assistance

## Device feedback

Android settings `action_feedback` defaults on. Validated tap/text targets get a
small ring, long press a double ring, and scroll an arrow inside the scrollable
node. Amber means attempted, green means Android accepted the accessibility
action, and red means failure. Acceptance is not proof of the business outcome.

The overlay is non-touchable and excluded from accessibility observations. It
expires within 900 ms and clears on pause, cancellation, teardown, or takeover.
Screenshots wait for overlay removal. Coordinates are clipped to the node and
display. Scroll arrows express the requested direction, not a recorded finger
trace. Long press uses `ACTION_LONG_CLICK`; Android controls its duration.

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

Open Login Assistance in Android Settings, save a common phone number, then add
an application. Each application requires opt-in and an SMS service signature
(the service name in its login message). An optional per-app phone overrides the
common number. Profiles are encrypted with an Android Keystore AES-GCM key and
remain on that device. Edit, disable, or delete them independently of cloud
account memory.

Enable notification access in Android system settings. The listener considers
only new notifications from the current default SMS app during an armed login
session. It does not read the SMS database or archive notification bodies. It
requires the expected foreground app, configured service signature, one
unambiguous 4-8 digit code, and a timestamp after the session began. Payment and
transfer messages are excluded. Hidden contents, unsupported providers, or
ambiguous messages require manual entry.

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
