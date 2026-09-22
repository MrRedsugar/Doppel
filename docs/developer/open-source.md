# Public Export and Release

Run `python scripts/export-public.py /new/output/doppel-public` from the mixed
development checkout. The destination must not exist. The exporter reads a narrow
allowlist, checks sources, stages a new tree and publishes the result. It outputs
the resulting path, file count and size; export_public(source, output) also returns
per-file SHA-256 values for release evidence.

Included: the public framework modules and tests/examples, independent framework
pyproject, Android SDK/developer app/isolated test app sources, Gradle wrapper,
selected public developer docs, Apache license and third-party notices. Generated
Android settings explicitly include only those three public modules.

Document and webpage support includes explicitly reviewed Jina Reader browser
bundles and rebuild sources, their dependency licenses, PDFBox/POI notices,
POI provenance and rebuild script, and upstream Office test fixtures. The generated
POI jar is excluded; reconstruct it with `python scripts/prepare-android-office-parser.py`.
No wildcard grants access to adjacent tools, assets or downloaded packages.

The original completion MP3 is excluded; the exporter generates an original
`task_completed.wav` tone from sine waves under Apache-2.0, preserving the resource
name and notification behavior while changing the sound only in the public build.
See [completion sound provenance](../licenses/task-completed-sound.md).

Excluded: product/, docs/product/, Android commercial app, private service docs, internal
requirements/research/worklogs, local data, keys, credentials, screenshots,
databases, Gradle/Python caches, build outputs, toolchain downloads and binary
dependencies. No original repository history is copied. The mixed root LICENSE
expressly reserves private components; the public export receives the full
Apache-2.0 text as its root LICENSE.

Files over 2 MiB and exports over 10 MiB fail. Symlinks/junctions, known API-key or
private-key markers, and private package/account references in public source fail
the audit. The exact known fake provider-key literal is exempted only in the three
explicitly named model/internal/scope fixture files. Those checks catch known
errors. Two exact SDK literals are also reviewed: a sibling package identifier
used to exclude the app's own overlay from captures, and a mock HTTP login route
in a client test. These exceptions do not permit private imports or additional
account routes. Public SDK cloud clients remain optional; the private backend is
not included. These checks do not replace human secret/license
review. A new public module requires explicit allowlist review. Existing output
directories are never merged or overwritten; clean an obsolete export separately
after verifying its location and ownership.

Before release, create a fresh environment, install ./framework[test] from the
export, run its tests and start the authenticated developer gateway. Independently
build public Android modules using SDK 35 and JDK 21 (including the pinned Office
parser rebuild). Inspect the final tree and
dependency notices, including any separately downloaded Harness runtime and
transitive binary dependencies. Do not equate simulated tests with target-app
compatibility or a successful real-world task. No publishing step is automatic.

The mixed workspace's Android document includes private app testing notes. The
export substitutes the dedicated android-public.md content as developer/android.md
so public build and setup instructions never depend on the excluded private app.

The included GitHub Actions workflow installs the public Python package on Linux
and Windows, runs local fixtures, builds distributions and compiles public Android
modules on Linux. It requests read-only repository permissions and passes no model
API credentials. Package/toolchain downloads are still needed. Workflow execution
on GitHub is verified separately from the recorded local tests/builds.
