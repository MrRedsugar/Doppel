"""Explicit installation command. The serving process never downloads models."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time

from huggingface_hub import snapshot_download
from models import MODELS


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--model", choices=MODELS, action="append")
    args = parser.parse_args()
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_DISABLE_XET"] = "1"
    for alias in args.model or MODELS:
        spec = MODELS[alias]
        folder = args.weights / alias
        started = time.monotonic()
        print(json.dumps({"installing": alias, "revision": spec["revision"]}), flush=True)
        snapshot_download(repo_id=spec["repo"], revision=spec["revision"], local_dir=folder,
                          token=False, max_workers=3,
                          allow_patterns=["*.json", "*.jinja", "*.safetensors", "*.txt", "README.md", "LICENSE*", "NOTICE*"])
        # HF may return an existing partial local_dir after a network failure. That is not an installation.
        required = ["config.json", "preprocessor_config.json", "tokenizer.json", "model.safetensors",
                    "chat_template.jinja" if alias == "mai-ui-2b" else "chat_template.json"]
        if not all((folder / name).is_file() and (folder / name).stat().st_size > 0 for name in required):
            raise RuntimeError("Official model files are incomplete; installation is not ready")
        files = []
        for path in sorted(folder.iterdir()):
            if not path.is_file() or path.name == "doppel-model-manifest.json":
                continue
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
                    digest.update(chunk)
            files.append({"name": path.name, "bytes": path.stat().st_size, "sha256": digest.hexdigest()})
        manifest = {**spec, "alias": alias, "files": files, "install_seconds": time.monotonic() - started}
        (folder / "doppel-model-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print(json.dumps({"installed": alias, "files": len(files), "seconds": manifest["install_seconds"]}), flush=True)


if __name__ == "__main__":
    main()
