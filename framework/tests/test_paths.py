import pytest

from doppel.paths import contained_path


@pytest.mark.parametrize("relative", [None, "", "../other", "/etc/passwd", "C:\\secret", "a/../../secret", "notes:stream", "a//b", "./notes", "a\x00b"])
def test_data_paths_reject_escape_and_ambiguous_names(tmp_path, relative):
    with pytest.raises(ValueError):
        contained_path(tmp_path, relative)


def test_data_paths_keep_nested_files_and_reject_linked_parents(tmp_path):
    assert contained_path(tmp_path, "harness/run/log") == tmp_path / "harness/run/log"
    outside = tmp_path / "outside"
    outside.mkdir()
    try:
        (tmp_path / "linked").symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("This Windows token cannot create symlinks")
    with pytest.raises(ValueError):
        contained_path(tmp_path, "linked/log")
