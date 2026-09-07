# Doppel

Open framework and Android SDK for an assistant that observes current screens,
uses stable targets, executes authorized actions and checks results. The public
package includes a standalone developer gateway, Android developer frontend,
isolated test app, MCP extensions and batch workbook tools.

Alpha.3 introduces a conversation-first Android interface with a pinned glass
composer, sidebar navigation, a voice sheet and Lucide icons. Drafts and created
tasks survive Activity recreation; a task that finishes creation in the background
waits for the visible client before starting its device worker. See the
[Android interface guide](docs/developer/android.md).

The alpha.2 Android workspace adds visible tap/long-press/scroll feedback,
explicit verification takeover, and encrypted device-local phone profiles with
one-use SMS notification assistance. The developer usage page shows unlimited
test points and retained actual token usage; provider charges still apply.
See [Interaction and Login Assistance](docs/developer/interaction-login.md).

```sh
python -m pip install -e './framework[test]'
doppel init --data-dir .local/developer
doppel serve --data-dir .local/developer --port 8765
```

Python 3.12+ is required. Open http://127.0.0.1:8765/health to check startup. The
generated developer-token.txt is a private bearer credential, configured in the
Android developer app. Actual model tasks require your own private key file
through --api-key-file. Offline tests do not call paid model services.

Build Android with JDK 17+, Android SDK 35 and the included Gradle wrapper:

```sh
cd android
./gradlew :sdk:testDebugUnitTest :developer-app:assembleDebug :test-app:assembleDebug
```

Use gradlew.bat on Windows. Configure the Android SDK path locally. Device
accessibility, screenshot capture, microphone and document permissions remain
explicit Android grants. Payment is always manual; avoid testing real social
messages. Simulated test-app results do not establish real-app compatibility.

## Observed verification

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

The independent public installation passed its full local Python suite: 258 tests
passed and one Windows symlink-permission test skipped. The final vision-context
alignment subsequently passed 41 focused tests. Public Android builds
passed with 16 SDK JVM tests. These local fixtures do not call paid model services.

- [Standalone Setup](docs/developer/standalone.md)
- [Architecture](docs/architecture/public-system.md)
- [HTTP Protocol](docs/contracts/public-http-v1.md)
- [Android SDK](docs/developer/android.md)
- [Extensions](docs/developer/extensions.md)
- [Documents](docs/developer/documents.md)
- [Data Retention](docs/developer/data-retention.md)
- [Interaction and Login Assistance](docs/developer/interaction-login.md)
- [Export and Release](docs/developer/open-source.md)

The links above are documentation topics under docs/developer in this repository.
The source includes no private account/credit service or commercial application.
Original public source is Apache-2.0; see LICENSE, NOTICE and
THIRD_PARTY_NOTICES.md. This is a development reference, with documented platform
and third-party provider limits, rather than a compatibility promise for every
Android device or application.
