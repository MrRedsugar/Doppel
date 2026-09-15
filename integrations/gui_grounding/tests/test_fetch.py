"""Regression for Hugging Face returning an incomplete existing local directory."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch


class FetchTest(unittest.TestCase):
    def prepare(self, root, template):
        folder = Path(root) / "mai-ui-2b"
        folder.mkdir()
        for name in ["config.json", "preprocessor_config.json", "tokenizer.json", "model.safetensors"]:
            (folder / name).write_bytes(b"existing download fixture")
        if template:
            (folder / "chat_template.jinja").write_text("{{ messages }}", encoding="utf-8")
        return folder

    def test_incomplete_local_fallback_cannot_be_declared_installed(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.prepare(root, template=False)
            with patch.object(fetch, "snapshot_download", return_value=str(folder)) as download:
                with patch.object(sys, "argv", ["fetch.py", "--weights", root, "--model", "mai-ui-2b"]):
                    with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, "incomplete"):
                        fetch.main()
            self.assertIn("*.jinja", download.call_args.kwargs["allow_patterns"])
            self.assertFalse((folder / "doppel-model-manifest.json").exists())

    def test_template_is_included_in_the_verified_file_inventory(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.prepare(root, template=True)
            with patch.object(fetch, "snapshot_download", return_value=str(folder)):
                with patch.object(sys, "argv", ["fetch.py", "--weights", root, "--model", "mai-ui-2b"]):
                    with contextlib.redirect_stdout(io.StringIO()):
                        fetch.main()
            manifest = json.loads((folder / "doppel-model-manifest.json").read_text(encoding="utf-8"))
            entry = next(item for item in manifest["files"] if item["name"] == "chat_template.jinja")
            self.assertEqual(hashlib.sha256((folder / entry["name"]).read_bytes()).hexdigest(), entry["sha256"])


if __name__ == "__main__":
    unittest.main()
