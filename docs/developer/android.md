# Android SDK and Developer App

Public modules target Android 26+, compile/target SDK 35, Kotlin 2.1.20,
Android Gradle Plugin 8.9.2 and Gradle 8.11.1. Use JDK 17 or a newer compatible JDK.
The developer app connects directly to the standalone public gateway.

The alpha.2 workspace, visible execution, verification takeover, and local login
profiles are described in [Interaction and Login Assistance](interaction-login.md).

## Conversation Interface

The native client opens a conversation with a bottom composer and side navigation
for tasks, history, files and settings. It retains saved drafts, execution modes,
pause/resume/cancel, approval and human takeover controls. Translucent sheets and
restrained highlights share a neutral theme with the voice and login settings.

Android 31+ can apply system window background blur behind sheets when the device
and system settings support it. Earlier versions use the readable translucent
background and dimming fallback; no screenshot is taken to create the effect.
The LDPlayer validation target runs API 28 and exercises only that fallback, not
actual system background blur.

Primary interface icons are 24-unit Lucide vectors from official revision
`2bfb9bb1bae5d74f6a9f81640ddd8bccc2c71860`. The launcher uses Lucide's neutral
`layers-2` symbol in an Android adaptive icon. The original ISC license and
applicable Feather MIT notices are bundled in
`android/sdk/src/main/assets/third_party/Lucide-LICENSE.txt`; the neighboring
`Lucide-PROVENANCE.txt` records source URLs, SHA-256 hashes and the structural
SVG-to-VectorDrawable conversion. Both files are included in SDK AAR and client
APK assets. The vectors require no icon runtime dependency.

## Build and Connect

Set JAVA_HOME and ANDROID_HOME for your local JDK and Android SDK. From android/:

```sh
./gradlew :sdk:testDebugUnitTest :developer-app:assembleDebug :test-app:assembleDebug
```

On Windows use gradlew.bat. APKs appear in each module's build/outputs/apk/debug/.
Build an SDK AAR with `./gradlew :sdk:assembleRelease`; the output is
sdk/build/outputs/aar/sdk-release.aar. AAR consumers also need Kotlin stdlib and
AndroidX Core 1.15.0, Vosk Android 0.3.75 and JNA 5.18.1 (Android AAR); the AAR does
not embed transitive libraries. Gradle project consumers resolve these automatically.
They use debug signing and are not store-release artifacts. Install the developer
and isolated test apps on an authorized test device. Package IDs are
dev.doppel.developer and dev.doppel.testapp; these are development identifiers.

Start the standalone gateway as described in standalone.md. Enter the reachable
base URL (without /v1) and generated developer token in the developer app, save,
then bind the device. Grant accessibility, overlays, notifications and microphone
only through the corresponding Android settings/permission prompts. The task page
supports ask/assist/full modes, pause/cancel and explicit approval/input requests.

A standard Android Emulator normally reaches the host through 10.0.2.2. ADB reverse
is another authorized test setup: `adb -s <test-device> reverse tcp:8765 tcp:8765`,
then configure http://127.0.0.1:8765 on that device. Specify the test device ID;
do not accidentally change another connected phone. LAN development permits HTTP;
use HTTPS and appropriate authentication/network controls beyond a trusted LAN.

## Public Interfaces

ObservationProvider.observe returns bounded protocol-v1 JSON: up to 300 visible
nodes, depth 30, 400 characters per text/description and roughly 24 KiB text budget.
Password contents are redacted. screen_id hashes package, window identity,
navigation generation, dimensions and visible node state; node IDs are paths
within that observation. Up to 250 launchable apps are reported separately.
The host's list_apps(query="") tool optionally filters labels and package names
case-insensitively; an omitted query keeps full discovery. The framework sends
compact relevant context to models.

ActionExecutor.execute supports observe, launch, tap, long_press, type,
login_phone, login_code, scroll, back, home, wait, screenshot and open_document.
Tap/long_press/type/login_phone/login_code require a current screen_id and node
reference. Targets are reobserved before action; stale/invalid targets fail.
For dynamic screens, a bounded local history retains at most eight observations
for 30 seconds. It can revalidate a named target only when the package, window,
navigation generation, dimensions, target, relevant ancestors and subtree still
match exactly. Both observations must be complete. Tap/type revalidation excludes
unnamed, password, sensitive and committing controls such as send/delete/confirm;
scroll revalidation requires an unchanged scrollable target with a resource ID.
Dispatching an action consumes the revalidation history, and navigation or manual
touch invalidates it. Every current payment/password check still runs.
There is no arbitrary coordinate execution entry point or hidden coordinate
fallback. Payment/password targets are blocked in all modes, including common
ancestor/descendant labels. This is a conservative safeguard, not proof that every
unlabeled or malicious payment UI can be recognized. Payments remain manual.

API 30+ screenshots use Android's accessibility screenshot API and are resized
to a longest edge of 1,080 pixels. Protected surfaces, denied requests or timeouts
fail explicitly. All screenshot requests compare screen hashes
before and after capture and return stale without mismatched image data when the
page changes. Actions return a settled observation, sampling every 250 ms for up
to 1.5 seconds and seeking three matching snapshots without replaying the action.
API 26-29 does not claim screenshot support. OCR/vision and
privileged bridges are separate capabilities, not universally available services.

## Lifecycle and Documents

The foreground worker polls commands and checks current run state before
dispatch. The foreground service declares specialUse and a user-visible ongoing
notification with a pause action. This type does not bypass background-start,
microphone or notification restrictions; distribution channels still need their
own foreground-service/accessibility policy review.

Command outcomes are recorded in SQLite before execution as uncertain and replaced
with observed outcomes afterward. Unacknowledged duplicates return the persisted
result. After server acknowledgement, raw observations and images are removed;
command/run IDs and a result hash remain as a no-replay tombstone. An unexpected
redelivery of an acknowledged command pauses dispatch instead of repeating an
effect or claiming missing image bytes are available. SQLite secure_delete is
enabled. Persistence failure stops dispatch. Cancel/pause cannot undo operations
already performed in another app.

The touch-pause setting enables a transparent TYPE_ACCESSIBILITY_OVERLAY only
during a running task outside the client. The first touch pauses the worker and
removes the layer; that touch is consumed instead of reaching the underlying
control. The observer selects the underlying application window, and accessibility
ACTION_CLICK continues to work. Pause, terminal states and connection errors
remove the layer. This was checked on API 28 and a Xiaomi API 36 device, including
underlying observation and first-touch interception. System gestures, protected
windows and other vendor behavior still need verification. Touch exploration is
not enabled; ordinary accessibility touch events alone do not report every touch.
Overlay/client/notification pause controls remain available.

The draggable overlay snaps to an edge. Long press opens a separate bottom voice
panel; confirmation directly creates a run with the stored mode and dismisses back
to the preceding app. Missing connection settings lead to the main client. Speech
recognition starts only from the visible Activity after microphone permission;
text entry remains available when recognition cannot start. Provider availability
and UI flow tests do not establish speech accuracy.

### Local Chinese Speech

Settings > speech recognition offers automatic, local Chinese and system providers.
Automatic uses the installed local model, otherwise the system provider. A working
system recognizer remains selectable. The voice panel links directly to model
settings if the system provider is unavailable. Recognition is limited to 30 seconds
per recording, stops on leaving the visible voice Activity, and never saves raw audio.
Review or edit the resulting task text before confirmation.

The model installer downloads the official `vosk-model-small-cn-0.22.zip` from
https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip . It is 43,898,754
bytes (about 42 MiB), expands to about 65 MiB, and requires 160 MiB free storage
during installation. The pinned SHA-256 is
`3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`.
Downloads can be cancelled; checksum, archive paths and size bounds are checked
before installation. Models reside in the app's private no-backup runtime directory
and can be deleted from speech settings. The source export and APK contain no model
weights. Each separately installed application owns its own model copy. Once the
model is installed, local recognition requires no gateway or paid speech API.

The native engine is Vosk Android 0.3.75. Vosk and this model are Apache-2.0;
JNA 5.18.1 is used under its Apache-2.0 license option. The small model can misrecognize
names and brands. A decoded fixture WAV is distinct from actual microphone-route
validation and does not prove arbitrary dictation accuracy.

The file page imports authorized .xlsx streams through Android's document picker.
The gateway transforms copies and returns doppel-document://name.xlsx; the client
validates filenames, disables redirects, limits downloads to 20 MiB and shares
cache/documents through a read-only FileProvider grant, preferring WPS. A command
with package_name pins the receiver to that exact application and fails if it
cannot resolve the document; it does not fall back to another app. It neither
reads other apps' private directories nor overwrites the original document.

Settings offer screenshot retention and local document-cache cleanup. History
offers screenshot viewing/deletion and terminal-run deletion. Screenshot downloads
are limited to 8 MiB. Device cleanup outbox entries are processed even while task
dispatch is paused: raw local results and cached document copies are purged before
the acknowledgement, while deduplication tombstones survive. Deleting local files
also revokes their FileProvider read grants. Copies already made by another app
are outside this application's deletion boundary. See data-retention.md.

Remote MCP settings support configuration, discovery and explicit per-tool/read-only
grants. NativePlugin is a trusted in-process host interface with protocol version,
capability declarations and JSON Schema, cancellation and authorization hooks.
It is not an untrusted APK/script sandbox. See extensions.md and documents.md.

## Validation Scope

The independent exported project builds sdk/developer-app/test-app and includes
SDK JVM tests for payment/password rules, stale targets, stable hashes and durable
deduplication/storage failures. The isolated test app uses local data and no network
permission. These tests do not establish real WeChat, Meituan, WPS or multi-vendor
compatibility; actual target-app/device validation requires separately authorized
cases and evidence. Never send real social messages as part of these fixtures.

Device integration evidence also covers real accessibility payment/password
rejection, isolated icon sending, API 28 screenshot-unavailable results, touch
interception, SQLite acknowledgement/deletion tombstones and API 36 nonblank
screenshots. The fixture app includes a page with genuinely unlabeled icon buttons
for separately assessing visual grounding, and a dynamic page with changing text
and a stable named control for testing bounded target revalidation. Fixture
presence is not a claim that
every visual task or speech-recognition provider has passed an end-to-end test.

Runtime dependencies are Kotlin stdlib, AndroidX Core, Vosk Android and JNA.
JUnit is test-only.
Lucide notices accompany the icon assets. See THIRD_PARTY_NOTICES.md in the repository root
for licenses and the requirements for auditing redistributed binary dependencies.
