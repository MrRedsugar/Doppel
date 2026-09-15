import importlib.util
import io
from pathlib import Path
import tarfile

import pytest


def module():
    spec = importlib.util.spec_from_file_location("embedded_asr", Path(__file__).parents[1] / "scripts/prepare-embedded-asr.py")
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def test_model_rejects_traversal_and_does_not_write_outside_output(tmp_path):
    mod = module()
    archive = tmp_path / "bad.tar.bz2"
    with tarfile.open(archive, "w:bz2") as tar:
        member = tarfile.TarInfo("../escaped")
        member.size = 3
        tar.addfile(member, io.BytesIO(b"bad"))
    with pytest.raises(ValueError, match="archive member"):
        mod.extract_model(archive, tmp_path / "out", {})
    assert not (tmp_path / "escaped").exists()


def test_model_rejects_links_and_missing_pinned_members(tmp_path):
    mod = module()
    archive = tmp_path / "bad.tar.bz2"
    with tarfile.open(archive, "w:bz2") as tar:
        member = tarfile.TarInfo("model/link")
        member.type = tarfile.SYMTYPE
        member.linkname = "../outside"
        tar.addfile(member)
    with pytest.raises(ValueError, match="archive member"):
        mod.extract_model(archive, tmp_path / "out", {})
    with tarfile.open(archive, "w:bz2"):
        pass
    with pytest.raises(ValueError, match="Missing"):
        mod.extract_model(archive, tmp_path / "out", {"tokens.txt": "a" * 64})


def test_bad_model_hash_preserves_existing_output(tmp_path):
    mod = module()
    target = tmp_path / "out"
    target.mkdir()
    (target / "existing").write_text("keep")
    archive = tmp_path / "model.tar.bz2"
    archive.write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="SHA-256"):
        mod.prepare(archive, target)
    assert (target / "existing").read_text() == "keep"
