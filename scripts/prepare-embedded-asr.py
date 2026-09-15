"""Build pinned offline Mandarin ASR assets. Downloads occur only with --download."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import urllib.request

MODEL_NAME = "sherpa-onnx-paraformer-zh-small-2024-03-09"
MODEL_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{MODEL_NAME}.tar.bz2"
MODEL_SHA256 = "da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450"
MODEL_FILES = {
    "model.int8.onnx": "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec",
    "tokens.txt": "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa",
}


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify(path: Path, sha256: str) -> None:
    if not path.is_file():
        raise ValueError(f"Missing pinned input {path}; run prepare-embedded-asr.py --download once before building")
    if digest(path) != sha256:
        raise ValueError(f"SHA-256 mismatch: {path.name}")


def extract_model(archive: Path, output: Path, expected: dict[str, str]) -> None:
    found = set()
    with tarfile.open(archive, "r:bz2") as source:
        for item in source:
            path = PurePosixPath(item.name)
            if path.is_absolute() or ".." in path.parts or "\\" in item.name or ":" in item.name or not (item.isfile() or item.isdir()):
                raise ValueError(f"Unsafe archive member: {item.name}")
            if not item.isfile() or path.name not in expected:
                continue
            if len(path.parts) != 2 or path.parts[0] != MODEL_NAME or path.name in found or item.size > 90 * 1024 * 1024:
                raise ValueError(f"Unexpected archive member: {item.name}")
            output.mkdir(parents=True, exist_ok=True)
            target = output / path.name
            with source.extractfile(item) as original, target.open("wb") as destination:
                shutil.copyfileobj(original, destination)
            verify(target, expected[path.name])
            found.add(path.name)
    if found != expected.keys():
        raise ValueError(f"Missing model members: {sorted(expected.keys() - found)}")


def prepare(archive: Path, output: Path) -> dict:
    # Verify before touching a previous successful output.
    verify(archive, MODEL_SHA256)
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    repo = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="asr-stage-", dir=output.parent) as temporary:
        stage = Path(temporary)
        assets = stage / "asr"
        extract_model(archive, assets, MODEL_FILES)
        notices = stage / "third_party"
        notices.mkdir(parents=True)
        shutil.copyfile(repo / "docs/licenses/embedded-chinese-asr.txt", notices / "embedded-chinese-asr.txt")
        manifest = {
            "model": MODEL_NAME, "model_archive_sha256": MODEL_SHA256,
            "runtime": "onnxruntime-android 1.22.0", "frontend": "Doppel Kotlin Kaldi-compatible log-mel",
            "provider": "cpu", "num_threads": 2, "sample_rate": 16000,
            "files": {name: {"sha256": value, "bytes": (assets / name).stat().st_size} for name, value in MODEL_FILES.items()},
        }
        (assets / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for source in stage.rglob("*"):
            if source.is_file():
                target = output / source.relative_to(stage)
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.is_file() or digest(target) != digest(source):
                    shutil.copyfile(source, target)
        return manifest


def download(path: Path, url: str, sha256: str, limit: int) -> None:
    if path.is_file():
        verify(path, sha256)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".part")
    try:
        with urllib.request.urlopen(url, timeout=30) as source, partial.open("wb") as destination:
            total = 0
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > limit:
                    raise ValueError("Download exceeds pinned size bound")
                destination.write(block)
        verify(partial, sha256)
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)


def main() -> None:
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=repo / ".tooling/asr/paraformer-small.tar.bz2")
    parser.add_argument("--output", type=Path, default=repo / "android/sdk/build/generated/asrAssets")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if args.download:
        download(args.archive, MODEL_URL, MODEL_SHA256, 77920048)
    print(json.dumps(prepare(args.archive, args.output), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
