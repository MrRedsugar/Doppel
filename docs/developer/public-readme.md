# Doppel

Open framework and Android SDK for an assistant that observes current screens,
uses stable targets, executes authorized actions and checks results. The public
package includes a standalone developer gateway, Android developer frontend,
isolated test app, MCP extensions and batch workbook tools.

The active Android direct path is `DirectRuntime → SplitTaskEngine`, using the
platforms and models selected in [Model Connections](docs/developer/model-connections.md).
With visual enhancement enabled, A decides and stateless B grounds the action;
with it disabled, A returns action coordinates directly. Phone-local orchestration
requires network access to the configured model APIs, but no always-on computer
or self-hosted kernel service. It does not run hosted MCP tools or workbook batching.

Phone-local use is BYOK: users supply their own model API credentials, encrypted
on the device with Android Keystore. Selected task text, screenshots and requested
reference excerpts are sent to those providers; local orchestration is not an
offline model and does not include a first-party model service or API quota.

Verification is version-specific. Historical test counts and real-app measurements
below belong to their named snapshots, not the current version; consult the
corresponding version's verification records for its results. See
[Phone Direct Mode](docs/developer/phone-direct.md) and
[Device Compatibility](docs/developer/device-compatibility.md) for current setup
and the distinction between fixture coverage and real-device acceptance.

The 15 retired implementation files contain 17 top-level classes, including
`DirectTaskEngine`, `VisualAgentLoop` and `LocalVisualMotor`, and now live in
`android/sdk/src/test/java/dev/doppel/sdk/legacy`. They retain package
`dev.doppel.sdk` for JVM regression tests and are not production APK/AAR classes.
The old `LearningLiveTest` and `PerceptionLiveTest` are archived in the private
workspace at `labs/legacy-direct-engine/androidTest`, outside the default
instrumentation build. `ObservationCoverage` collection, `CaptureObservationBinding`
and `FeedbackMotion` still have production callers. `ModelScreenSummary` remains
a public SDK API, without a current Split caller. These retained types do not
reactivate the old engine. The old MiMo/Artemis experiments and `continuous_agent` /
`feedback_operator` switches are not current runtime setup instructions.

Skills and application learning are no longer runtime features. Conversations
retain task context and message revisions; reusable user corrections and
preferences can become local long-term memory, editable in Settings. Memory is
reference context, not permission to change the user's task or bypass approval.
Imported images and documents, public web search, webpage excerpts and viewport
screenshots are available through bounded, on-demand tools. A reference alone
does not preload a whole document into the model context. Android renders pages
locally with WebView and the reused Jina Reader/Readability core; PDF/Office text
uses PDFBox/POI. The APK needs no Docker or hosted Jina service. See
[Public Web Research](docs/developer/web-research.md) for supported operations and
limits, and the [Android interface guide](docs/developer/android.md) for setup.

## Optional standalone gateway

The following commands start the optional standalone gateway on port 8765.
Phone direct mode does not require this service.

The SDK also retains an optional `dev.doppel.sdk.cloud` account/connection client
for host integrations. It requires an explicitly configured compatible backend;
this source export contains no private account/credit server, service credentials
or hosted deployment. That client is separate from both local BYOK and the public
standalone gateway below.

```sh
python -m pip install -e './framework[test]'
doppel init --data-dir .local/developer
doppel serve --data-dir .local/developer --port 8765
```

Python 3.12+ is required. Open http://127.0.0.1:8765/health to check startup. The
generated developer-token.txt is a private bearer credential, configured in the
Android developer app. Actual model tasks require your own private key file
through --api-key-file. Offline tests do not call paid model services.

The separate standalone gateway default main model is Xiaomi `mimo-v2.5-pro` (text), with `mimo-v2.5` for
on-demand screenshot understanding. Pass `--provider deepseek` and its separate
key file to use the legacy provider. The official DeepSeek Harness remains the
execution library; the upstream model is selected by the host proxy. Thinking
is explicitly disabled, outputs remain bounded, and task completion still
requires validated evidence even when MiMo chooses ordinary text over a tool.
Provider configuration is documented in [Standalone Setup](docs/developer/standalone.md).

Build Android with JDK 21, Android SDK 35 and the included Gradle wrapper:

```sh
python scripts/prepare-android-office-parser.py
python scripts/prepare-embedded-tts.py --download
python scripts/prepare-embedded-asr.py --download
cd android
./gradlew :sdk:testDebugUnitTest :developer-app:assembleDebug :test-app:assembleDebug
```

The Office preparation command rebuilds the pinned upstream Android POI adapter
and verifies its fixed source and jar SHA-256 values. The generated 22 MB jar is
not committed; preserve its provenance and license assets. See
[Office Parser Build](android/sdk/libs/README.md). JDK and Gradle installation
paths are not tied to the original development machine.

Speech preparation downloads pinned TTS and ASR archives and verifies their
SHA-256 values. Subsequent Gradle builds prepare both embedded Chinese speech
asset sets from those local archives without downloading them again. The source
export excludes model weights; built SDK/client artifacts include the TTS fallback
and offline ASR assets. See [Embedded Chinese Speech](docs/developer/embedded-chinese-tts.md)
and the [ASR license and model details](docs/licenses/embedded-chinese-asr.txt) for
licenses and asset requirements.

Use gradlew.bat on Windows. Configure the Android SDK path locally. Device
accessibility, screenshot capture, microphone and document permissions remain
explicit Android grants. Delegated payment is off by default. A user can enable
it in Android Settings after three timed risk acknowledgements; ask/assist still
require approval, while full access may attempt an ordinary payment within the
task. Each task/application pair permits at most one payment attempt. Passwords,
financial OTPs, transfers and persistent debit settings remain manual, and an
accepted click does not establish payment success. See
[Delegated Payment](docs/developer/payment-delegation.md) for the host contract,
revocation and multi-step checkout limitations. Avoid testing real social
messages. Simulated test-app results do not establish real-app compatibility.

## Historical verification

These records predate the alpha.60 cleanup. They document their original scope;
they do not establish that the current build, migrated tests or device flows pass.

Alpha.18-self-core SDK JVM tests passed 415 cases, including five rollback
migration checks. The developer APK and SDK AAR built successfully. The APK uses
versionCode18 and the same signing certificate as the original alpha16 package.
Its three self-core source files are unchanged by the rollback; the Artemis
Android classes are absent from its DEX. No phone was connected for installation,
so device deployment and migration have not been verified on a phone. No new
business tasks were run. WPS, complete cross-task factual recall and actual game
combat remain unaccepted. Hosted CI has not run for this unpublished update.

Alpha.4's historical Python integration suite passed 338 tests with one Windows
symlink-permission skip. It includes both provider transports, MiMo nullable tool
responses, main/vision model separation, and official Harness/MCP round trips.
The independent public source snapshot also passed 316 Python tests with the
same one permission skip. The SDK JVM suite passed 37 tests. Device results belong to the accompanying
release notes; the earlier alpha.3/alpha.2 counts below are historical.

A bounded MiMo Pro synthetic tool call returned valid arguments (600 tokens,
2.806s). A V2.5 geometric-image probe returned metered text but included an
irrelevant refusal; it failed semantic quality review despite matching shape
keywords. This is not evidence of successful MiMo WPS, messaging or gameplay.
No current business-task success or real-time performance guarantee is claimed.

For alpha.3, 30 SDK JVM tests and six LDPlayer device cases passed, covering
draft navigation/recreation, delayed creation and duplicate prevention, background
worker deferral, manual takeover, synthetic notification login, and voice task
confirmation with return to the previous app. Layouts were inspected at 411dp and
320dp with a visible software keyboard. API 28 uses the visual glass fallback;
API 31+ system blur and API 35 keyboard insets still need a matching device.
No paid model or real external-app task was used for this UI update.

For alpha.2, SDK tests cover 29 cases. LDPlayer instrumentation passed six
device cases, including synthetic SMS login, actual tap/long-press/scroll cursor
pixels, CAPTCHA takeover, payment boundaries, stable targets, and result privacy.
Two additional broker-dependent voice/worker tests were skipped because their
test-broker prerequisite was absent. Normal-width and 320dp Android layouts were
visually checked. Real carrier SMS and new live-model business runs were not
revalidated for alpha.2. The business measurements below belong to alpha.1.

Selected runs on 2026-09-07 used a live model with independent output assertions.
Numbers describe those runs, not a latency or token budget guarantee.

| Case | Environment and checked result | Model calls | Tokens | Seconds | Device commands |
| --- | --- | ---: | ---: | ---: | ---: |
| 5000-row workbook batch | Android 16 device, synthetic workbook opened in real WPS; sort, sums, styles and unchanged source checked | 6 | 24,445 | 72.67 | 1 |
| Meal selection and payment stop | Android 9 emulator, isolated test app; requested meal and note saved, payment untouched | 13 | 41,518 | 97.94 | 12 |
| Unlabeled icon actions | Android 16 device, isolated test app; cloud vision identified icons and final count was independently checked | 15 | 63,369 | 123.88 | 9 |
| Private and group replies | Android 9 emulator, isolated test app; one private and two group replies sent exactly once, all three conversations marked read | 22 | 145,967 | 187.03 | 19 |
| Real Meituan payment stop | Android 16, self-hosted developer gateway without daily quota; one serving at payment confirmation, payment untouched, order creation unconfirmed | 28 | 517,600 | 223.09 | 22 |

Workbook edits operate on imported copies through batch tools. This does not
represent thousands of WPS cell taps. The meal case used a simulated store and
order; it does not establish compatibility with a real delivery application.
Real social-app messaging compatibility has not been established.
The Meituan run's initial fixed-label check missed the actual payment label and
returned false. Subsequent independent structured UI and command-history checks
confirmed the payment stopping point. The model's claim that an order was placed
was unsupported; order creation was not confirmed.
This run used the self-hosted developer gateway without the private product's daily
quota. Earlier product-mode attempts failed. Its 517,600 tokens equal about
1,725.33 points at 300 tokens per point, exceeding the product's 800 daily points;
this is a token-only comparison, not an actual product charge. Provider usage
charges still apply in self-hosted mode.
Offline Android instrumentation also checks voice-flow navigation, consecutive
task polling and result privacy. Local Vosk decoded a fixed Chinese PCM fixture on
Android 9 and 16; the physical speaker-to-microphone path was not established.
Speech accuracy across users and environments was not measured.
The packaged Android builds use debug signing. Local gateway verification does
not establish Docker deployment; the container migration recipe has not been
build-tested in this environment.

The earlier independent public installation passed its local Python suite: 258 tests
passed and one Windows symlink-permission test skipped. The final vision-context
alignment subsequently passed 41 focused tests. Public Android builds
passed with 16 SDK JVM tests at that earlier snapshot. These local fixtures do not call paid model services.

The 2026-09-09 alpha20 development candidate separately passed 824 SDK JVM tests.
Its continuous transaction and stage-plan experiment was disabled by
default: actual Arknights navigation and WPS editing cases both reached the
predeclared 150-second limit without completing. Unit tests establish host and
protocol behavior, not end-to-end application reliability or real-time game
control. See [Continuous Conversations](docs/developer/continuous-conversations.md)
for that historical experiment's data contract and limits. Its switch does not
select an engine in the current Android runtime.

- [Standalone Setup](docs/developer/standalone.md)
- [Architecture](docs/architecture/public-system.md)
- [HTTP Protocol](docs/contracts/public-http-v1.md)
- [Android SDK](docs/developer/android.md)
- [Extensions](docs/developer/extensions.md)
- [Documents](docs/developer/documents.md)
- [Data Retention](docs/developer/data-retention.md)
- [Interaction and Login Assistance](docs/developer/interaction-login.md)
- [Phone Direct Mode](docs/developer/phone-direct.md)
- [Device Compatibility](docs/developer/device-compatibility.md)
- [Cloud Speech](docs/developer/cloud-speech.md)
- [Embedded Chinese Speech](docs/developer/embedded-chinese-tts.md)
- [Export and Release](docs/developer/open-source.md)

The links above are documentation topics under docs/developer in this repository.
The source includes no private account/credit service or commercial application.
Original public source is Apache-2.0; see LICENSE, NOTICE and
THIRD_PARTY_NOTICES.md. This is a development reference, with documented platform
and third-party provider limits, rather than a compatibility promise for every
Android device or application.
