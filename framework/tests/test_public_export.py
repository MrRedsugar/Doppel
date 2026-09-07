import importlib.util
import ast
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]


def exporter():
    if not (ROOT / 'scripts' / 'export-public.py').is_file():
        pytest.skip('Export auditing requires the complete public source repository')
    spec = importlib.util.spec_from_file_location('public_export', ROOT / 'scripts' / 'export-public.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_public_export_is_independent_and_excludes_private_data(tmp_path):
    result = exporter().export_public(ROOT, tmp_path / 'public')
    output = Path(result['output'])
    paths = [p.relative_to(output).as_posix() for p in output.rglob('*') if p.is_file()]
    assert 'framework/pyproject.toml' in paths
    assert 'framework/python/doppel/cli.py' in paths
    assert 'framework/tests/conftest.py' in paths
    assert (output / 'framework/tests/conftest.py').read_bytes() == (ROOT / 'framework/tests/conftest.py').read_bytes()
    assert 'android/sdk/build.gradle.kts' in paths
    assert 'android/developer-app/build.gradle.kts' in paths
    assert 'android/test-app/build.gradle.kts' in paths
    assert 'android/sdk/src/main/assets/third_party/NOTICE.txt' in paths
    assert 'android/sdk/src/main/assets/third_party/Apache-2.0.txt' in paths
    assert 'LICENSE' in paths
    assert '.github/workflows/verify-public.yml' in paths
    assert not any(path.startswith(('product/', 'android/app/', '.local/', '.artifacts/', '.venv/')) for path in paths)
    assert not any('private-server' in path or 'worklogs/' in path or '/build/' in path for path in paths)
    assert not any(path.endswith(('.aar', '.so', '.zip')) for path in paths)
    assert (output / 'README.md').is_file()
    assert ':app' not in (output / 'android/settings.gradle.kts').read_text()
    assert result['files'] == len(paths)
    assert result['bytes'] < 5 * 1024 * 1024
    modules = {path.stem for path in (output / 'framework/python/doppel').glob('*.py')}
    for path in (output / 'framework/python/doppel').glob('*.py'):
        for node in ast.walk(ast.parse(path.read_text(encoding='utf-8'))):
            if isinstance(node, ast.ImportFrom) and node.level == 1 and node.module:
                assert node.module.split('.')[0] in modules, f'{path.name} dependency missing: {node.module}'
    with pytest.raises(FileExistsError):
        exporter().export_public(ROOT, output)


def test_export_rejects_secret_in_public_source_without_partial_output(tmp_path):
    module = exporter()
    public = tmp_path / 'public'
    fixture = tmp_path / 'source'
    path = fixture / 'framework/python/doppel/cli.py'
    path.parent.mkdir(parents=True)
    path.write_text('API_KEY = "sk-' + 'a' * 40 + '"\n')
    with pytest.raises(ValueError, match='secret'):
        module.export_public(fixture, public)
    assert not public.exists()


@pytest.mark.parametrize("source_text", [
    "from doppel_product.services import Services\n",
    "import doppel_product\n",
    "PACKAGE = 'dev.doppel.app'\n",
    "LOGIN = '/v1/auth/login'\n",
    "LOGIN = '/v1/auth/code'\n",
])
def test_export_still_rejects_private_product_dependencies(tmp_path, source_text):
    module = exporter()
    source, output = tmp_path / 'source', tmp_path / 'public'
    path = source / 'framework/python/doppel/cli.py'
    path.parent.mkdir(parents=True)
    path.write_text(source_text, encoding='utf-8')
    with pytest.raises(ValueError, match='Private product reference'):
        module.export_public(source, output)
    assert not output.exists()
