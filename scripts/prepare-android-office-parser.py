"""Rebuild the pinned upstream POI Android adapter; uses only Python's standard library."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "a29c7a4cdd94175c2be43678c82afc818b60ff3f"
SOURCE_SHA256 = "cd5f327a614d32eff919d9843b4fb5177747682e9118894d3ffbbe35f267a785"
JAR_SHA256 = "04bdb9366d1e5cb8942cfbcaf50e402a8f6f8c4425399b0473591ee91aac8737"
JAR = ROOT / "android/sdk/libs/poi-android-5.5.1.jar"
MANIFEST = JAR.with_suffix(".provenance.json")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--gradle", help="Gradle executable; defaults to the repository's pinned wrapper")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"), help="JDK 21 directory; defaults to JAVA_HOME, then java on PATH")
    parser.add_argument("--gradle-user-home", default=os.environ.get("GRADLE_USER_HOME"), help="Gradle cache directory; defaults to GRADLE_USER_HOME or Gradle's standard cache")
    args = parser.parse_args()
    if args.verify_only:
        if not JAR.is_file() or digest(JAR) != JAR_SHA256:
            raise SystemExit("POI Android jar missing or SHA-256 mismatch; rebuild with this script.")
        print("POI Android 5.5.1 SHA-256 verified")
        return

    work = ROOT / ".tooling/poi-android-reuse"
    work.mkdir(parents=True, exist_ok=True)
    archive = work / f"{COMMIT}.zip"
    if not archive.exists():
        with urllib.request.urlopen(f"https://codeload.github.com/centic9/poi-on-android/zip/{COMMIT}", timeout=60) as response:
            archive.write_bytes(response.read())
    if digest(archive) != SOURCE_SHA256:
        raise SystemExit("Upstream source SHA-256 mismatch")
    upstream = work / "upstream"
    with zipfile.ZipFile(archive) as source:
        for member in source.infolist():
            resolved = (upstream / member.filename).resolve()
            if not resolved.is_relative_to(upstream.resolve()):
                raise SystemExit("Unsafe upstream archive path")
        source.extractall(upstream)
    source = upstream / f"poi-on-android-{COMMIT}" / "poishadow"
    project = work / "build"
    project.mkdir(exist_ok=True)
    shutil.copytree(source / "src", project / "src", dirs_exist_ok=True)
    build = (source / "build.gradle").read_text(encoding="utf-8")
    # The demo omits these general POI dependencies; retain them for arbitrary text extraction.
    for dependency in ("commons-codec:commons-codec", "org.apache.commons:commons-lang3"):
        build = build.replace(f"        exclude(dependency('{dependency}'))\n", "")
    build += "\n\ntasks.withType(AbstractArchiveTask).configureEach { preserveFileTimestamps = false; reproducibleFileOrder = true }\n"
    build += '\ntasks.register("dependencyInventory") { doLast { configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { println("DOPPEL_DEP " + it.moduleVersion.id + " " + it.file.absolutePath) } } }\n'
    (project / "build.gradle").write_text(build, encoding="utf-8")
    (project / "settings.gradle").write_text("pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name='doppel-poi-android-5.5.1'\n", encoding="utf-8")
    environment = os.environ.copy()
    if args.java_home:
        environment["JAVA_HOME"] = args.java_home
    if args.gradle:
        command = [args.gradle]
    elif os.name == "nt":
        command = [str(ROOT / "android/gradlew.bat")]
    else:
        command = ["sh", str(ROOT / "android/gradlew")]
    command.extend(["-p", str(project)])
    if args.gradle_user_home:
        command.extend(["-g", args.gradle_user_home])
    command.extend(["--no-daemon", "--console=plain", "shadowJar", "dependencyInventory"])
    print("Building pinned upstream Android adapter in an isolated Gradle project...", flush=True)
    run = subprocess.run(command,
                         env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    (work / "build.log").write_text(run.stdout, encoding="utf-8")
    if run.returncode:
        raise SystemExit(f"Build failed; see {work / 'build.log'}")
    built = project / "build/libs/doppel-poi-android-5.5.1-all.jar"
    if digest(built) != JAR_SHA256:
        raise SystemExit("Rebuilt jar differs from the reviewed hash. Review toolchain/dependencies before updating the pin.")
    licenses = ROOT / "android/sdk/src/main/assets/third_party/poi-android"
    licenses.mkdir(parents=True, exist_ok=True)
    dependencies = []
    for line in run.stdout.splitlines():
        if not line.startswith("DOPPEL_DEP "):
            continue
        _, coordinate, filename = line.split(" ", 2)
        artifact = Path(filename)
        included = not coordinate.startswith("com.github.virtuald:curvesapi:")
        notices = []
        if included:
            with zipfile.ZipFile(artifact) as source_jar:
                for name in source_jar.namelist():
                    if name.rsplit("/", 1)[-1].lower() in {"license", "license.txt", "license.md", "notice", "notice.txt", "notice.md", "copying", "copyright"}:
                        target = coordinate.replace(":", "_") + "_" + name.replace("/", "_") + ".txt"
                        (licenses / target).write_bytes(source_jar.read(name))
                        notices.append(target)
        dependencies.append({"coordinate": coordinate, "sha256": digest(artifact), "bundled": included, "notice_files": notices})
    JAR.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(built, JAR)
    MANIFEST.write_text(json.dumps({"upstream": f"https://github.com/centic9/poi-on-android/tree/{COMMIT}",
                                   "source_sha256": SOURCE_SHA256, "jar_sha256": JAR_SHA256,
                                   "toolchain": "Gradle 8.11.1; JDK 21.0.12.1+1; upstream Shadow 9.4.3",
                                   "dependencies": sorted(dependencies, key=lambda item: item["coordinate"])}, indent=2) + "\n", encoding="utf-8")
    print(f"Verified jar and {sum(len(item['notice_files']) for item in dependencies)} upstream notices written.")


if __name__ == "__main__":
    main()
