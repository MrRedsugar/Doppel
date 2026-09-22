# Android Office text extraction

`poi-android-5.5.1.jar` is built from the existing
[centic9/poi-on-android adapter](https://github.com/centic9/poi-on-android/tree/a29c7a4cdd94175c2be43678c82afc818b60ff3f),
commit `a29c7a4cdd94175c2be43678c82afc818b60ff3f`. Its source pins Apache POI 5.5.1
and provides the Android StAX/AWT adaptations. The old downloadable 5.2.5 jar is
not used. No Office binary or XML document parser is maintained by Doppel.

SHA-256: `04bdb9366d1e5cb8942cfbcaf50e402a8f6f8c4425399b0473591ee91aac8737`.
The resolved dependency versions, hashes, and upstream notice filenames are in
`poi-android-5.5.1.provenance.json`. Licenses ship as SDK assets under
`third_party/poi-android/`. The upstream AWT adapters support the text extraction
path; this is not an Office renderer or macro executor.

The generated jar is not committed. Before building Android, install JDK 21 and
Python 3.10 or later, set `JAVA_HOME` (or make Java available on `PATH`), and run
from the repository root:

```sh
python scripts/prepare-android-office-parser.py
```

The script uses the repository's pinned Gradle 8.11.1 wrapper on Windows and
Linux. No private `.tooling` JDK or Gradle installation is required. Network
access is needed for the pinned source archive, Gradle, and Maven dependencies.
The reviewed artifact was originally built with JDK 21.0.12.1+1; a different
toolchain must still reproduce the exact reviewed jar hash, never bypass it.

Verify the locally generated artifact:

```sh
python scripts/prepare-android-office-parser.py --verify-only
```

Explicit overrides are available as `--gradle`, `--java-home`, and
`--gradle-user-home`; `JAVA_HOME` and `GRADLE_USER_HOME` are respected by default.
The script verifies the pinned source archive, builds only the upstream adapter
in `.tooling/poi-android-reuse`, checks the resulting jar hash, and copies its
original transitive notices. It keeps commons-codec/commons-lang3 that the
upstream demo excludes, and sets deterministic ZIP timestamps and ordering.
No upstream Java implementation was modified. Do not update the expected hash
without reviewing the dependency change and rerunning Android validation.

`ChatDocumentText` retains only format selection, encoding, local resource bounds,
and calls to mature POI / PDFBox / jsoup readers. Office documents are parsed when
the model asks to read them; the resulting private cache is served through bounded
info/search/read tools. The cache is never automatically sent in full.

Spreadsheet formulas are returned as the original formulas with a clear label;
they are not evaluated. The ordinary POI extractor is intentional: the streaming
XLSX extractor omits formulas without cached values even when formula output is
requested. A regression test covers this case. Empty sheet names alone do not
count as document content.

Validation: six real DOC/DOCX/PPT/PPTX/XLS/XLSX samples passed a standalone Android
DEX/app_process probe on emulator-5554, including two successive spreadsheet reads
and uncached formulas. This dependency probe is distinct from the app's subsequent
JVM, UI, and release-shrinking checks.
