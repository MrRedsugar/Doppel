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

Excluded: product/, Android commercial app, private service docs, internal
requirements/research/worklogs, local data, keys, credentials, screenshots,
databases, Gradle/Python caches, build outputs, toolchain downloads and binary
dependencies. No original repository history is copied. The mixed root LICENSE
expressly reserves private components; the public export receives the full
Apache-2.0 text as its root LICENSE.

Files over 2 MiB and exports over 10 MiB fail. Symlinks/junctions, known API-key or
private-key markers, and private package/account references in public source fail
the audit. The exact known fake provider-key literal is exempted only in the three
explicitly named model/internal/scope fixture files. Those checks catch known
errors and do not replace human secret/license
review. A new public module requires explicit allowlist review. Existing output
directories are never merged or overwritten; clean an obsolete export separately
after verifying its location and ownership.

Before release, create a fresh environment, install ./framework[test] from the
export, run its tests and start the authenticated developer gateway. Independently
build public Android modules using SDK 35 and JDK 17+. Inspect the final tree and
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
