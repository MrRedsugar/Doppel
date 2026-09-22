# Android SDK and Developer App

Public modules target Android 26+, compile/target SDK 35, Kotlin 2.1.20,
Android Gradle Plugin 8.9.2 and Gradle 8.11.1. Use JDK 21 for the complete build,
including the pinned Office parser; Android Java/Kotlin bytecode targets remain 17.
The developer app supports a phone-local model connection in developer debug
builds and the standalone public gateway. The phone-local path uses
`SplitTaskEngine`; the Python gateway retains its existing Harness executor.
Local use is BYOK: users configure their own provider API keys, with no bundled
first-party model service, free quota or private backend deployment.
See [model connections](model-connections.md) and the
[Android execution architecture](../architecture/qwen-device-loop.md).

The companion panel, voice lifecycle, visible execution, verification takeover,
and local login profiles are described in
[Interaction and Login Assistance](interaction-login.md).
Runtime environment reporting, call/alarm detection and OEM background-window
permission limits are documented in [Device Compatibility](device-compatibility.md).

[Persistent schedules](schedules.md) cover the developer schedule UI, Android
JobScheduler integration, gateway API and missed-execution/restart semantics.

## Task Interface

The native client opens a phone-task entry with a bottom composer. Side navigation
offers New task, Current task when a run exists, Schedules, Records and Settings;
the developer app also adds Usage. There is no Files navigation page. The current
client supports conversations, editable message versions and local long-term
memory. Users discuss corrections in chat and manage saved preferences in
Settings; memory cannot grant task permissions. Images and documents belong to
their conversation, and models request bounded info/search/read excerpts rather
than receiving entire documents automatically. Saved drafts, execution modes, pause/resume/cancel, approval and
human takeover controls remain available. Settings include model connections,
offline Chinese recognition, visual enhancement, extensions and editable long-term memory.
Skills and application learning are no longer runtime features.
Translucent sheets and restrained highlights share a neutral theme with the voice
and login settings.

The alpha.5 interface work adds state-driven blue/cyan/pink contour light to
the companion entry and a non-touchable perimeter indication while executing.
Paused, waiting, completed and failed states remain distinguishable with static
labels and accents; light is not a percentage or proof of task completion.
Application-owned selectors, confirmations and text-entry dialogs share
`UiDialog` and `UiTheme` surfaces. Android permission prompts and the keyboard
remain system-owned. See [Interaction and Login Assistance](interaction-login.md)
for state, motion and screenshot behavior. Release and device acceptance must be
verified separately from the presence of these source changes.

Android 31+ can apply system window background blur behind sheets when the device
and system settings support it. Earlier versions use glass highlights over an
opaque backing and dimming fallback, keeping underlying text from bleeding
through. No screenshot is taken to create the effect.
The earlier LDPlayer validation target runs API 28 and exercises only that
fallback. Android 14/API 34 supports these APIs, but device and system blur
availability and visual acceptance must be recorded for each release.

Primary interface icons are 24-unit Lucide vectors from official revision
`2bfb9bb1bae5d74f6a9f81640ddd8bccc2c71860`. The launcher uses Lucide's neutral
`layers-2` symbol in an Android adaptive icon. The original ISC license and
applicable Feather MIT notices are bundled in
`android/sdk/src/main/assets/third_party/Lucide-LICENSE.txt`; the neighboring
`Lucide-PROVENANCE.txt` records source URLs, SHA-256 hashes and the structural
SVG-to-VectorDrawable conversion. Both files are included in SDK AAR and client
APK assets. The vectors require no icon runtime dependency.

## Build and Connect

Set JAVA_HOME and ANDROID_HOME for JDK 21 and your Android SDK. From the
repository root, rebuild the pinned Office parser and prepare speech archives:

```sh
python scripts/prepare-android-office-parser.py
python scripts/prepare-embedded-tts.py --download
python scripts/prepare-embedded-asr.py --download
cd android
./gradlew :sdk:testDebugUnitTest :developer-app:assembleDebug :test-app:assembleDebug
```

The Office script uses the included Gradle wrapper and respects `JAVA_HOME` and
`GRADLE_USER_HOME`; it requires no private toolchain installation. It verifies
fixed upstream-source and rebuilt-jar hashes and preserves dependency notices.
The generated `android/sdk/libs/poi-android-5.5.1.jar` is excluded from source
exports and must be rebuilt before compiling. Recheck it with
`python scripts/prepare-android-office-parser.py --verify-only`. See
[Office parser provenance and build instructions](../../android/sdk/libs/README.md).

TTS preparation verifies its approximately 147 MB download before generating assets. Later builds
use the cached `.tooling/tts` archive, including after Gradle clean. The Gradle
`doppel.python` property can select a Python executable; the default is `python`
on Windows and `python3` elsewhere. Fully offline builds also require populated
Gradle dependency caches. See [Embedded Chinese Speech](embedded-chinese-tts.md).

Offline Mandarin recognition also requires the pinned Paraformer archive prepared
by `prepare-embedded-asr.py` (about 78 MB). The archive and extracted INT8 model
are checked against fixed SHA-256 values; subsequent builds use `.tooling/asr`.
Model weights are downloaded separately and are excluded from public source
exports. Preserve [the ASR model notice](../licenses/embedded-chinese-asr.txt).
`python -m pytest tests/test_prepare_embedded_asr.py` checks unsafe archives and
preservation of existing assets on a checksum failure. Desktop verification is
available in `scripts/verify-embedded-asr.py`; device performance requires a
separate Android run.

On Windows use gradlew.bat. APKs appear in each module's build/outputs/apk/debug/.
Build an SDK AAR with `./gradlew :sdk:assembleRelease`; the output is
sdk/build/outputs/aar/sdk-release.aar. AAR consumers also need Kotlin stdlib and
AndroidX Core 1.15.0, Vosk Android 0.3.75, JNA 5.18.1 (Android AAR) and ONNX Runtime
Android 1.22.0. Public web/document reading also requires
`com.squareup.okhttp3:okhttp:4.12.0`, `org.jsoup:jsoup:1.18.3` and
`com.tom-roush:pdfbox-android:2.0.27.0` (including its Bouncy Castle dependencies).
The rebuilt local POI jar is packaged by the library build. Overlay capture uses
`org.lsposed.hiddenapibypass:hiddenapibypass:6.1`.
Declare these Maven dependencies so their own dependencies
(including Okio for OkHttp) resolve; a standalone AAR does not embed transitive
libraries. Gradle project consumers resolve these automatically. Every host **application module** must add
`androidResources { noCompress += "onnx" }` inside its `android` block, keeping
the TTS and ASR ONNX assets uncompressed and allowing the TTS fallback to map its
model directly from the APK. The library module's packaging setting does
not propagate into the host APK. The included developer app already declares it;
external AAR/project consumers must also configure their application modules.
The SDK's consumer rules retain ONNX Runtime JNI classes. The TTS model, lexicon
and voice total 116,530,827 bytes (116.5 MB / 111.1 MiB); the ASR model and tokens
add 81,904,027 bytes (81.9 MB / 78.1 MiB). Together these inference assets total
approximately 198.4 MB / 189.2 MiB, excluding small notices and manifests. These are
raw asset sizes, not compressed APK download sizes or runtime memory measurements.
ASR also creates a verified private model copy on first use. Model weights are
excluded from the source export. Debug APKs use debug signing and are not
store-release artifacts. Install the developer
and isolated test apps on an authorized test device. Package IDs are
dev.doppel.developer and dev.doppel.testapp; these are development identifiers.

For phone-local execution, open Settings > Model settings (the page title is
Model connections), save your provider credentials, select the default and optional
independent visual model, verify both effective models' image capability, then
enable the local connection. The initial preset uses Qwen `qwen3.8-flash` and
optional independent `qwen3.8-max` visual enhancement; both disable thinking in
the current preset. Custom OpenAI-compatible platforms and separate enhancement
credentials are supported. Android Keystore encrypts the saved provider
configuration; task text and screenshots go to the selected model platforms.
See [model connections](model-connections.md) for setup and capability checks.

The optional `dev.doppel.sdk.cloud` client handles account sessions and remote
task connections only when a host configures a compatible backend. The source
export does not provide that private backend, hosted accounts, credentials or
model service. This integration is distinct from the public gateway below and
is not a prerequisite for phone-local BYOK execution.

For gateway execution, start the standalone gateway as described in
[standalone setup](standalone.md). Enter its reachable base URL (without /v1)
and generated developer token in the developer app, save, then bind the device.
Grant accessibility, overlays, notifications and microphone only through the
corresponding Android settings/permission prompts. The task page supports
ask/assist/full modes, pause/cancel and explicit approval/input requests.

A standard Android Emulator normally reaches the host through 10.0.2.2. ADB reverse
is another authorized test setup: `adb -s <test-device> reverse tcp:8765 tcp:8765`,
then configure http://127.0.0.1:8765 on that device. Specify the test device ID;
do not accidentally change another connected phone. LAN development permits HTTP;
use HTTPS and appropriate authentication/network controls beyond a trusted LAN.

## Preview consent and retained entry points

The client records the exact preview terms/privacy versions accepted by the
user. Missing, withdrawn or outdated consent routes retained voice/task-window
entry points back through onboarding. New task processing, recording and provider
or document/image attachment submission require accepted consent; read access, pause/cancel and
deletion/cleanup paths remain available. API credentials, Android permissions
and server authentication remain separate checks.

SDK integrations that use these client services must preserve the visible consent
flow. A developer test may temporarily accept its synthetic fixture and restore
the prior record in cleanup; production code must not use that test setup as
implied user consent. The bundled notices are preview documents. A publisher
must supply its own reviewed service identity, policies and consent versioning
before commercial distribution. Withdrawal cannot undo already dispatched
operations or retract data already received by a provider.

## Public Interfaces

The retained node-based SDK/gateway interfaces use protocol-v1 JSON.
ObservationProvider.observe returns up to 300 visible
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
These node-targeted operations do not silently fall back to coordinates. The
Android phone-local core separately uses `split_action`: the visual model returns
bounded normalized coordinates, and the host checks the action contract and
current screenshot provenance before dispatch. It returns the actual dispatch
result, then takes a fresh screenshot for the planner to assess. See the
[Android execution architecture](../architecture/qwen-device-loop.md) for action
types, image-missing recovery and state handling.
Without local payment consent, payment targets are blocked in all modes,
including common ancestor/descendant labels. The optional Settings consent flow
allows ordinary payment taps under the current task mode; passwords and financial
verification remain manual. This is a conservative safeguard, not proof that every
unlabeled or malicious payment UI can be recognized. See
[delegated payment](payment-delegation.md) for consent, revocation and retry limits.

The current screenshot path captures the full default display. API 30+ uses
Android's accessibility screenshot API; API 26–29 requires an explicitly authorized
foreground MediaProjection session. Retained shell-helper APIs are not selected
as a screenshot backend by this path. Images retain their aspect ratio and are
reduced only above a longest edge of 1,920 pixels. Original display dimensions,
image dimensions and rotation are retained for coordinate mapping.

On API 34+, `TemporaryScreenshotExclusion` temporarily sets `SKIP_SCREENSHOT` on
this process's application/accessibility overlay surfaces, waits for the surface
transaction, captures once, then clears the flags in cleanup, including on
cancellation or failure. The overlays stay visible and their input regions do
not change. It does not crop/compose individual app windows or reconstruct hidden
pixels from transparency. Other visible windows, including ordinary system
dialogs, remain part of the full-display capture. User screenshots or recordings
taken during this short interval can also omit these overlays. Missing/replaced
overlay surfaces or unavailable exclusion APIs return a stale/error result;
there is no silent switch to hiding overlays. API 26–33 uses full-display capture
without this exclusion, so overlay-free output is not guaranteed there.

Android may reject protected surfaces or redact them; the SDK does not bypass
that protection. Locked/noninteractive devices and sensitive settings or local
authentication are blocked, and known private regions are masked. Requests check
window identity, package/navigation, geometry and private-region state around
capture; inconsistent or cancelled results are not delivered as current images.
Semantic changes within the same window/navigation are recorded explicitly;
tree and pixels are not claimed to be an atomic read. These screen hashes
describe accessibility state, not game-frame identity.
The retained node-based executor can return a settled observation, sampling every
250 ms for up to 1.5 seconds and seeking three matching snapshots without replaying
the action. `SplitTaskEngine` does not introduce this automatic stability wait:
the planner can request a bounded wait and then obtain a new screenshot. OCR/vision
and privileged bridges remain separately configured capabilities.

## Lifecycle and Documents

The foreground worker obtains commands from the selected local runtime or gateway
and checks current run state before
dispatch. The foreground service declares specialUse and a user-visible ongoing
notification with a pause action. This type does not bypass background-start,
microphone or notification restrictions; distribution channels still need their
own foreground-service/accessibility policy review.

The worker's command journal records outcomes in SQLite before execution as uncertain and replaces them
with observed outcomes afterward. Unacknowledged duplicates return the persisted
result. After server acknowledgement, raw observations and images are removed;
command/run IDs and a result hash remain as a no-replay tombstone. An unexpected
redelivery of an acknowledged command pauses dispatch instead of repeating an
effect or claiming missing image bytes are available. SQLite secure_delete is
enabled. Persistence failure stops dispatch. Cancel/pause cannot undo operations
already performed in another app.

This command journal is separate from phone-local task state, which
`DirectRuntime` persists in `noBackupFilesDir/direct-runs-v1.json`; its screenshot
archive is `noBackupFilesDir/direct-screenshots-v1`. Python gateway/Harness storage
and its server cleanup rules are a separate deployment path.

The touch-pause setting enables transparent TYPE_ACCESSIBILITY_OVERLAY regions
during a running task outside the client. Regions exclude the companion entry
so it remains reachable. The first touch elsewhere pauses the worker and removes
the regions; that touch is consumed before reaching the underlying control.
The observer selects the underlying application window, and accessibility
ACTION_CLICK continues to work. Pause, terminal states and connection errors
remove the regions. Earlier API 28 and Xiaomi API 36 evidence covered the previous
full-screen layer; it does not establish acceptance of the new companion cutout.
System gestures, protected windows and vendor behavior require release checks.
Touch exploration is not enabled. Overlay/client/notification pause controls
remain available.

The draggable companion opens a task panel on tap and a voice session on hold.
A valid hold release submits its final text once; left/right cancellation, early
release and leaving the visible Activity cannot submit stale recognition.
The regular voice page retains editable text and confirmation. Pending questions
and conflicts with an existing task use the visible task controls. Missing
connection settings lead to the main client. Recognition
starts only from the visible Activity with microphone permission. Provider
availability and UI flow tests do not establish speech accuracy.

### Local Chinese Speech

Settings > Offline Chinese speech recognition describes the bundled Paraformer
Chinese INT8 model. Both direct and gateway client voice entry use `EmbeddedAsr`
through ONNX Runtime Android 1.22.0 on the CPU. Recognition needs no account,
network, gateway or paid speech API, and does not fall back to a system/cloud
recognizer. There is no provider chooser or Vosk download/delete control in the
current speech settings. A missing bundled model requires a complete installation.

Recording is limited to 30 seconds and stops when the visible voice Activity is
left. Audio stays in memory for local recognition; it is not uploaded or saved as
a raw recording. Submitted recognition text enters the task record and is used by
the configured model connection to execute the task. Diagnostic files contain
timing/signal metadata, not audio or transcript text. The ordinary voice page
supports reviewing text before confirmation; companion holds use release to submit
and left/right movement to cancel.

The bundled model and tokens occupy 81,904,027 bytes (about 78.1 MiB). First use
copies the ONNX model to the application's private no-backup directory and checks
its pinned hash. Each installed application owns its assets and runtime copy.
The Alibaba/DAMO model is Apache-2.0 and ONNX Runtime is MIT; preserve the
[ASR model notice](../licenses/embedded-chinese-asr.txt), including conversion and
repackaging provenance. The model archive is prepared during the build as above,
and model weights remain excluded from public source exports. The sherpa-onnx
runtime binaries are not shipped.

Vosk Android 0.3.75 and its installer remain for the legacy `LocalDictation` API.
Its separately downloaded `vosk-model-small-cn-0.22` weights are not bundled in the
APK. Vosk and that model are Apache-2.0; JNA 5.18.1 is used under its Apache-2.0
license option. These retained dependencies do not describe the current voice
entry. Model names, brands and English abbreviations may be misrecognized. Desktop
or emulator fixture decoding is distinct from real microphone/noise and ARM-phone
validation and does not establish arbitrary dictation accuracy.

### Completion Speech

Completion results use a system TextToSpeech engine when it supports Chinese.
When that engine is unavailable or fails, the packaged Kokoro Chinese model runs
locally through ONNX Runtime. The fallback reads task text and produces audio in
memory; it makes no network speech request and stores no user audio files.
Cancellation, phone calls, lock transitions and a newer result stop remaining
playback. Chinese phrase and number support is bounded; unsupported characters
and contextual pronunciations remain documented limitations. See
[Embedded Chinese Speech](embedded-chinese-tts.md) for reproducible preparation,
license sources and the distinction between desktop fixtures and device evidence.

Chat attachment imports support images and local text, PDF and Office files.
Documents are converted locally when their read tool is invoked, with bounded
search and character paging; screenshots and selected excerpts may then be sent
to the user's model provider. PDFBox and POI perform parsing without a separate
conversion server. Scanned PDFs without text require OCR not supplied by this
reader. Public webpages use an isolated local WebView with Jina Markify,
Readability and the MSW browser network interceptors. Local webpage rendering
requires Android 28+ and a current WebView; it is not an authenticated browser
session. See [public web research](web-research.md) for network, cancellation,
paging and screenshot limits.

The retained gateway document APIs support authorized .xlsx streams through Android's
document picker; the current task navigation has no Files page.
The gateway transforms copies and returns doppel-document://name.xlsx; the client
validates filenames, disables redirects, limits downloads to 20 MiB and shares
cache/documents through a read-only FileProvider grant, preferring WPS. A command
with package_name pins the receiver to that exact application and fails if it
cannot resolve the document; it does not fall back to another app. It neither
reads other apps' private directories nor overwrites the original document.

Settings offer screenshot retention and local document-cache cleanup. History
offers screenshot viewing/deletion and terminal-run deletion. Screenshot downloads
are limited to 8 MiB. Gateway device cleanup outbox entries are processed even while task
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

Earlier device integration evidence covers accessibility payment/password
rejection, isolated icon sending, the then-current API 28 screenshot-unavailable results, touch
interception, SQLite acknowledgement/deletion tombstones and API 36 nonblank
screenshots. Those historical runs do not validate the current split core or all
current screenshot backends. The fixture app includes a page with unlabeled icon buttons
for separately assessing visual grounding, and a dynamic page with changing text
and a stable named control for testing bounded target revalidation. Fixture
presence is not a claim that
every visual task or speech-recognition provider has passed an end-to-end test.

Runtime dependencies include Kotlin stdlib, AndroidX Core, Vosk Android, JNA,
ONNX Runtime Android, OkHttp, jsoup, PDFBox-Android, Bouncy Castle, the rebuilt
POI Android adapter and HiddenApiBypass. The Jina/Readability/MSW browser assets
and their licenses are included; no Node or Docker runtime is bundled.
See [public web research](web-research.md)
for the knowledge APIs and raw AAR dependency requirements.
JUnit is test-only.
Lucide notices accompany the icon assets. See THIRD_PARTY_NOTICES.md in the repository root
for licenses and the requirements for auditing redistributed binary dependencies.
