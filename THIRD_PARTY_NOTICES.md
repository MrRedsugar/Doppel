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
| PyYAML | 6.0.3 | MIT | https://github.com/yaml/pyyaml |
| jsonschema | 4.26.0 | MIT | https://github.com/python-jsonschema/jsonschema |
| referencing | 0.37.0 | MIT | https://github.com/python-jsonschema/referencing |
| python-multipart | 0.0.32 | Apache-2.0 | https://github.com/Kludex/python-multipart |
| deepseek-harness-sdk | 0.1.2rc1 | MIT | https://github.com/deepseek-ai/deepseek-harness |

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
| Vosk Android | 0.3.75 | Apache-2.0; local speech recognition runtime |
| JNA Android AAR | 5.18.1 | Apache-2.0 OR LGPL-2.1-or-later; Doppel uses the Apache-2.0 option |
| Kotlin plugin/stdlib | 2.1.20 | Apache-2.0 |
| Android Gradle Plugin | 8.9.2 | Apache-2.0; separate Android SDK components have their own terms |
| Gradle wrapper | 8.11.1 | Apache-2.0; wrapper scripts preserve upstream copyright/license headers |
| JUnit | 4.13.2 | EPL-1.0; test-only dependency |
| Gradle distribution | 8.11.1 | Apache-2.0 plus bundled third-party licenses; not vendored |

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
Model weights are not bundled in this source export or the APK.

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

These are the inspected direct dependencies, not a complete resolved SBOM.
Before distributing binaries, capture the exact installed Python dependency tree,
Gradle dependency reports, Harness runtime version and all transitive license
files. Include those files with the binary distribution. A future dependency
upgrade requires another review. The source export's tests verify module and file
boundaries; they do not provide a legal opinion or an exhaustive license scan.
