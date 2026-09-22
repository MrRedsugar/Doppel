# Third-Party Notices

Doppel's original public code is Apache-2.0. Dependencies retain their own
licenses. This source export does not vendor Python packages, the Android SDK/JDK,
Gradle distributions, model weights or commercial application assets. Package
managers download dependencies separately. Preserve their license/NOTICE files
when redistributing wheels, APKs, containers or bundled runtimes.

## Direct Python Dependencies

Installed metadata inspected on 2026-09-07:

| Dependency | Inspected version | License | Upstream |
| --- | --- | --- | --- |
| FastAPI | 0.141.1 | MIT | https://github.com/fastapi/fastapi |
| Uvicorn | 0.52.4 | BSD-3-Clause | https://github.com/encode/uvicorn |
| HTTPX | 0.28.1 | BSD-3-Clause | https://github.com/encode/httpx |
| httpx2 | 2.12.0 | BSD-3-Clause | Installed distribution metadata; official MCP 2.x transport dependency |
| Pydantic | 2.13.5 | MIT | https://github.com/pydantic/pydantic |
| MCP Python SDK | 2.1.1 | MIT | https://github.com/modelcontextprotocol/python-sdk |
| openpyxl | 3.1.5 | MIT | https://openpyxl.readthedocs.io/ |
| jsonschema | 4.26.0 | MIT | https://github.com/python-jsonschema/jsonschema |
| referencing | 0.37.0 | MIT | https://github.com/python-jsonschema/referencing |
| python-multipart | 0.0.32 | Apache-2.0 | https://github.com/Kludex/python-multipart |
| deepseek-harness-sdk | 0.1.2rc1 | MIT | https://github.com/deepseek-ai/deepseek-harness |
| tzdata | 2026.3 | Apache-2.0 package; IANA data retains upstream terms | https://github.com/python/tzdata |

The official DeepSeek Harness integration was inspected at upstream revision
d347e703908d0406b7a7ef80e3a0e594d86b2215. The SDK may fetch/cache its executable
runtime and additional JavaScript dependencies. Those artifacts and their notices
are not part of this source export. Review the resolved runtime bundle's license
inventory before redistribution; the MIT SDK label does not relicense every
transitive dependency. Provider service terms and model pricing remain separate.

## Android and Build Dependencies

| Dependency | Version | License/scope |
| --- | --- | --- |
| AndroidX Core/Core-KTX | 1.15.0 | Apache-2.0; Android SDK dependency |
| OkHttp | 4.12.0 | Apache-2.0; its bundled Public Suffix List data is MPL-2.0 |
| jsoup | 1.18.3 | MIT; bounded HTML-to-readable-text processing |
| PDFBox-Android | 2.0.27.0 | Apache-2.0 Android port; includes upstream PDFBox/FontBox and resource-specific notices |
| Apache PDFBox / FontBox | 2.0.27 notices | Apache-2.0 code; preserve bundled Adobe/Unicode/font and other resource terms |
| Bouncy Castle | 1.72 | MIT-style license; PDFBox-Android's bcprov/bcpkix/bcutil dependencies |
| Apache POI / poi-on-android | POI 5.5.1; adapter `a29c7a4cdd94175c2be43678c82afc818b60ff3f` | Apache-2.0 adapter; transitive dependencies retain their individual notices |
| Android Hidden Api Bypass | 6.1 | Apache-2.0; own-overlay capture integration |
| Vosk Android | 0.3.75 | Apache-2.0; local speech recognition runtime |
| JNA Android AAR | 5.18.1 | Apache-2.0 OR LGPL-2.1-or-later; Doppel uses the Apache-2.0 option |
| ONNX Runtime Android | 1.22.0 | MIT; embedded Chinese speech inference |
| Kokoro-82M-v1.1-zh | int8 multi-language v1.1 archive | Apache-2.0; packaged Chinese completion-speech model and voice |
| Kotlin plugin/stdlib | 2.1.20 | Apache-2.0 |
| Android Gradle Plugin | 8.9.2 | Apache-2.0; separate Android SDK components have their own terms |
| Gradle wrapper | 8.11.1 | Apache-2.0; wrapper scripts preserve upstream copyright/license headers |
| JUnit | 4.13.2 | EPL-1.0; test-only dependency |
| Gradle distribution | 8.11.1 | Apache-2.0 plus bundled third-party licenses; not vendored |
| Lucide icons | `2bfb9bb1bae5d74f6a9f81640ddd8bccc2c71860` | ISC with upstream Feather-derived MIT notice; 27 vectors adapted for Android |

Lucide icons come from https://github.com/lucide-icons/lucide. Their original
paths and primitive geometry are preserved as Android VectorDrawables. The full
upstream license and individual source SHA-256 hashes are bundled in
`android/sdk/src/main/assets/third_party/Lucide-LICENSE.txt` and
`Lucide-PROVENANCE.txt`. The launcher's layers mark uses the same licensed geometry.

Android Hidden Api Bypass 6.1 is an unchanged Maven AAR, copyright (C) 2021-2025
LSPosed. Its upstream `v6.1` revision is
`71aaad4ce558530b4788da67d0e3d9bb3596e9d3` at
https://github.com/LSPosed/AndroidHiddenApiBypass . That source has an Apache-2.0
LICENSE and no separate NOTICE file; the full license matches the bundled
`android/sdk/src/main/assets/third_party/Apache-2.0.txt`. Its scope and attribution
also appear in that directory's `NOTICE.txt`. The AAR/classes.jar contain no
additional license/NOTICE files to reproduce.

The Gradle wrapper scripts and JAR are the sole vendored build-tool artifacts.
Copyright the original Gradle authors; obtain source/license at
https://github.com/gradle/gradle/tree/v8.11.1 . The wrapper distribution URL is
pinned with distributionSha256Sum in gradle-wrapper.properties. Preserve the
wrapper headers and this attribution when redistributing it. The full Apache-2.0
text accompanies Doppel in LICENSE.

The optional local Chinese speech model is vosk-model-small-cn-0.22, listed as
Apache-2.0 at https://alphacephei.com/vosk/models on 2026-09-07. Its separately
downloaded archive is 43,898,754 bytes, SHA-256
`3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`.
Vosk recognition weights are not bundled in this source export or the APK.

Completion speech includes the unchanged Kokoro Chinese int8 model, the zf_001
voice subset and a transformed Chinese pronunciation lexicon. The original
lexicon generator uses misaki (Apache-2.0) and pypinyin (MIT). Build preparation
verifies the official source archive SHA-256 before selecting those assets;
no sherpa-onnx runtime, espeak-ng runtime/data, other-language lexicons, Jieba
data or normalization FSTs are packaged. Full sources, change notices and MIT
license text are in `docs/licenses/embedded-chinese-tts.txt`; preparation copies
that notice and the complete Apache-2.0 license into the model's APK asset
directory. See `docs/developer/embedded-chinese-tts.md` for the pinned archive,
selection procedure and runtime limitations. Source exports exclude weights;
SDK/client binaries include approximately 116.5 MB of selected speech assets
before package compression. ONNX Runtime retains its MIT license and upstream
third-party notices independently of the model license.

Vosk's AAR has no bundled license text; JNA's classes.jar includes its dual-license
statement but not the full Apache license. Doppel therefore packages full license
texts and a native dependency inventory in the SDK's
`android/sdk/src/main/assets/third_party/` directory, included in APK assets.
Vosk's published native build uses Kaldi/OpenFst (Apache-2.0), OpenBLAS/CLAPACK
(BSD-3-Clause), libf2c (permissive notice), and the NDK C++ runtime. JNA includes
libffi (MIT-style license). The asset directory preserves those notices and LLVM
runtime license terms. The upstream prebuilt AAR does not provide exact source
revision provenance for every linked native object; the inventory follows its
published build script and is not a complete reproducible native SBOM.

Shizuku, OpenClaw and libadb were research references; their source/assets are not
vendored by the public export. No permission to their names/icons or to target
applications' brands/assets is implied. No platform-wide payment safety or
application compatibility claim follows from these license notices.

## Release Audit

The 2026-09-08 Android additions were checked against their resolved Maven POMs
and jars. SDK assets reproduce jsoup's exact MIT license and OkHttp's Public
Suffix List notice, along with full Apache-2.0 and MPL-2.0 texts.
See `android/sdk/src/main/assets/third_party/`.
Skills and their bundled references are no longer shipped with the current runtime.
Historical notices remain relevant when redistributing archived versions.

These are the inspected direct dependencies, not a complete resolved SBOM.
Before distributing binaries, capture the exact installed Python dependency tree,
Gradle dependency reports, Harness runtime version and all transitive license
files. Include those files with the binary distribution. A future dependency
upgrade requires another review. The source export's tests verify module and file
boundaries; they do not provide a legal opinion or an exhaustive license scan.

## Local Reading and Conversation Source Adaptations

The following source revisions and browser bundles are included in the current
Android source export. All paths below are relative to the repository root;
`assets` abbreviates `android/sdk/src/main/assets/third_party/`.

| Component | Pinned source/version | Reuse and original license |
| --- | --- | --- |
| Jina Reader | `1574bfd380d249c86c82db4dace0d9c8fe17e2b1` | Unchanged `tools/jina-reader-core/vendor/markify.ts`; Apache-2.0 in `tools/jina-reader-core/vendor/LICENSE` and `assets/jina-reader/JINA-LICENSE.txt` |
| Mozilla Readability | 0.6.0 | Browser article selection; Apache-2.0 in `assets/jina-reader/READABILITY-LICENSE.txt` |
| MSW Interceptors | 0.45.0 | Official browser Fetch/XHR interception; MIT in `assets/jina-reader/MSW-INTERCEPTORS-LICENSE.txt` |
| MathML to LaTeX / xmldom | `@nomagick/mathml-to-latex` 1.5.3 | Markify's MathML converter and its embedded XML parser; MIT in `assets/jina-reader/MATHML-LICENSE.txt` and `XMLDOM-LICENSE.txt` |
| assistant-ui | `5f21b37bee60458cf721b68369b251636cd939e3` | Kotlin adaptation of message repository branching/export algorithms and behavioral fixtures; MIT in `assets/assistant-ui-LICENSE.txt` |
| DeepSeek Harness file-reference guidance | `ddefc45fbc7f8e46dd73185e68295696d1297887` | Chinese adaptation in `ChatAttachmentContext.PROMPT`; MIT in `assets/deepseek-harness-LICENSE.txt`; distinct from the optional Python Harness runtime above |
| Agent-Reach website channel | `a19a171fa980a0785849596492e0af4db800c82f` | Remaining Markdown-envelope adaptation in `JinaReaderChannel.kt`; MIT in `assets/agent-reach/LICENSE`; provenance in `assets/agent-reach/SOURCE.md` |
| open-webSearch | `400678eac49521de8c3bb139f263c83d7300416e` | Kotlin/jsoup adaptation of Bing/Sogou search-result parsers; Apache-2.0 in `assets/Apache-2.0.txt`; source/change notice in `assets/NOTICE.txt` |

Jina/Readability/MSW browser build sources, smoke checks and the exact npm lockfile
are in `tools/jina-reader-core/`; `assets/jina-reader/SOURCE.md` describes the
adaptation. MSW's bundled MIT dependencies retain their full texts in
`OPEN-DRAFT-UNTIL-LICENSE.txt`, `OUTVARIANT-LICENSE.txt`, `RETTIME-LICENSE.txt`,
`DEBUG-LICENSE.txt`, and `MS-LICENSE.txt` under `assets/jina-reader/`.
The MathML package already embeds XML parser code; the separately locked xmldom
version does not establish that embedded parser's exact version.

The Android reader includes no Jina server, Puppeteer, LibreOffice or hosted
`r.jina.ai` dependency. It also includes no Agent-Reach social/video installers,
browser-cookie utilities, assistant-ui React runtime, or Harness Node runtime.
These adaptations do not confer rights to a third-party hosted service.

PDFBox/FontBox's complete upstream resource licenses and notices are preserved
in `assets/PDFBOX-LICENSE.txt`, `PDFBOX-NOTICE.txt`, `FONTBOX-LICENSE.txt`, and
`FONTBOX-NOTICE.txt`. These include Adobe glyph/CMap, Unicode, Liberation font
(SIL OFL), and TwelveMonkeys notices; applicable notices also remain inside the
upstream AAR resources. Bouncy Castle's original text is reproduced in
`assets/BouncyCastle-LICENSE.txt`.

The generated POI jar is not committed. `scripts/prepare-android-office-parser.py`
rebuilds the pinned upstream adapter and checks fixed source and jar hashes.
`android/sdk/libs/poi-android-5.5.1.provenance.json` records resolved dependency
versions, hashes and individual notice filenames. Preserve `assets/poi-android/`
including `NOTICE.txt` and all coordinate-prefixed original LICENSE/NOTICE files.
See `android/sdk/libs/README.md` for reproducible build instructions. The jar's
Apache-2.0 adapter license does not replace its bundled dependencies' own terms.
