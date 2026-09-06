from pathlib import Path

import pytest

from doppel.skills import SkillCatalog


def make_skill(root):
    folder = root / 'totals'
    folder.mkdir()
    (folder / 'SKILL.md').write_text('---\nname: totals\ndescription: Summarize sales\nplatforms: [desktop]\ndependencies:\n  runtimes: [python]\n  tools: [documents.transform]\n---\nRead references/notes.txt when needed.\n', encoding='utf-8')
    (folder / 'references').mkdir()
    (folder / 'references' / 'notes.txt').write_text('Use authorized copies.', encoding='utf-8')
    return folder


def test_progressive_read_and_runtime_is_unknown(tmp_path):
    make_skill(tmp_path)
    catalog = SkillCatalog(tmp_path)
    entry = catalog.list_skills()[0]
    assert entry['name'] == 'totals'
    assert 'instructions' not in entry
    loaded = catalog.read_skill('totals')
    assert 'Read references' in loaded['instructions']
    assert loaded['dependencies']['runtimes'] == ['python']
    assert loaded['runtime_status'] == 'unverified'
    assert catalog.read_resource('totals', 'references/notes.txt') == 'Use authorized copies.'


@pytest.mark.parametrize('path', ['../other', '/etc/passwd', 'C:\\secret', 'references/../../secret', 'notes.txt:stream'])
def test_resource_paths_rejected(tmp_path, path):
    make_skill(tmp_path)
    with pytest.raises(ValueError):
        SkillCatalog(tmp_path).read_resource('totals', path)


def test_resource_size_and_symlink_rejected(tmp_path):
    folder = make_skill(tmp_path)
    (folder / 'large').write_text('x' * 100)
    with pytest.raises(ValueError, match='limit'):
        SkillCatalog(tmp_path, max_resource_bytes=50).read_resource('totals', 'large')
    try:
        (folder / 'linked').symlink_to(folder / 'large')
    except OSError:
        pytest.skip('This Windows token cannot create symlinks')
    with pytest.raises(ValueError):
        SkillCatalog(tmp_path).read_resource('totals', 'linked')


def test_invalid_yaml_is_not_executed(tmp_path):
    folder = make_skill(tmp_path)
    (folder / 'SKILL.md').write_text('---\nname: !!python/object/apply:os.system [echo unsafe]\n---\nbody')
    with pytest.raises(ValueError):
        SkillCatalog(tmp_path).read_skill('totals')
