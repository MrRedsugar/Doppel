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
    for required in (
        'framework/python/doppel/scheduler.py',
        'framework/tests/test_scheduler.py',
        'docs/developer/schedules.md',
        'docs/developer/model-connections.md',
        'docs/architecture/qwen-device-loop.md',
        'scripts/prepare-embedded-asr.py',
        'scripts/verify-embedded-asr.py',
        'tests/test_prepare_embedded_asr.py',
        'docs/licenses/embedded-chinese-asr.txt',
        'docs/developer/direct-skills.md',
        'docs/developer/app-learning.md',
        'docs/developer/continuous-conversations.md',
        'docs/developer/device-lab.md',
        'docs/architecture/adaptive-visual-control.md',
        'scripts/device-lab.py',
        'tests/test_device_lab.py',
        'scripts/device-lab-ui.py',
        'tests/test_device_lab_ui.py',
        'docs/architecture/planned-control.md',
        'docs/developer/local-visual-motor.md',
        'docs/developer/gui-grounding.md',
        'scripts/adb-shell-bridge.ps1',
        'scripts/adb-wireless-shell-bridge.ps1',
        'docs/developer/adb-shell-bridge.md',
        'docs/licenses/adb-shell-bridge.md',
        'android/sdk/src/main/java/dev/doppel/sdk/ConversationHistory.kt',
        'android/sdk/src/main/java/dev/doppel/sdk/LearningTrace.kt',
        'android/sdk/src/main/java/dev/doppel/sdk/LearnedSkillStore.kt',
        'android/sdk/src/main/java/dev/doppel/sdk/AppLearning.kt',
        'docs/developer/web-research.md',
        'android/sdk/src/main/java/dev/doppel/sdk/DirectSkills.kt',
        'android/sdk/src/main/assets/skills/arknights/SKILL.md',
        'android/sdk/src/main/assets/skills/arknights/references/stages-and-replay.md',
        'android/sdk/src/main/assets/skills/arknights/references/battle-observation.md',
        'android/sdk/src/main/assets/skills/arknights/references/sources.md',
        'android/sdk/src/main/assets/third_party/jsoup-LICENSE.txt',
        'android/sdk/src/main/assets/third_party/MPL-2.0.txt',
    ):
        assert required in paths
        assert (output / required).read_bytes() == (ROOT / required).read_bytes()
    for name in (
        'DirectTaskEngine', 'DirectExecutionContext', 'GuardedActionPlan', 'LocalVisualMotor',
        'VisualAgentLoop', 'FeedbackOperator', 'FeedbackOperatorContext', 'FeedbackToolContract',
        'ContinuousAgentContext', 'ActionProgress', 'PerceptionRouting', 'DirectVisualReadCache',
        'VisualActionHistory', 'SessionTrajectory', 'ModelActionHistory',
    ):
        relative = f'android/sdk/src/test/java/dev/doppel/sdk/legacy/{name}.kt'
        assert relative in paths
        assert (output / relative).read_bytes() == (ROOT / relative).read_bytes()
        assert f'android/sdk/src/main/java/dev/doppel/sdk/{name}.kt' not in paths
    for name in ('LearningLiveTest', 'PerceptionLiveTest'):
        assert f'android/developer-app/src/androidTest/java/dev/doppel/developer/{name}.kt' not in paths
    assert not any(path.startswith('labs/') for path in paths)
    gui_sources = {
        'README.md', 'THIRD_PARTY_NOTICES.md', 'requirements.txt', 'start.ps1', 'stop.ps1',
        'models.py', 'prompts.py', 'fetch.py', 'protocol.py', 'patch_projection.py',
        'process_guard.py', 'inference.py', 'server.py', 'evaluate.py',
        'tests/test_fetch.py', 'tests/test_protocol.py', 'tests/test_patch_projection.py',
        'tests/test_process_guard.py', 'tests/test_server.py', 'refinement.py',
        'tests/test_refinement.py', 'tests/test_inference_lifecycle.py',
        'licenses/MAI-UI-Apache-2.0.txt', 'licenses/MAI-UI-NOTICE.txt', 'licenses/GUI-Owl-MIT.txt',
    }
    actual_gui = {path.removeprefix('integrations/gui_grounding/') for path in paths if path.startswith('integrations/gui_grounding/')}
    assert actual_gui == gui_sources
    for name in gui_sources:
        relative = Path('integrations/gui_grounding') / name
        assert (output / relative).read_bytes() == (ROOT / relative).read_bytes()
    source_gui_modules = {path.stem for path in (ROOT / 'integrations/gui_grounding').glob('*.py')}
    exported_gui_modules = {path.stem for path in (output / 'integrations/gui_grounding').glob('*.py')}
    for path in (output / 'integrations/gui_grounding').rglob('*.py'):
        for node in ast.walk(ast.parse(path.read_text(encoding='utf-8'))):
            if isinstance(node, ast.ImportFrom) and node.module:
                dependency = node.module.split('.')[0]
                if dependency in source_gui_modules:
                    assert dependency in exported_gui_modules, f'{path.name} GUI dependency missing: {dependency}'
    assert 'scripts/prepare-embedded-tts.py' in paths
    assert 'scripts/verify-embedded-tts.py' in paths
    assert 'docs/licenses/embedded-chinese-tts.txt' in paths
    assert 'docs/developer/embedded-chinese-tts.md' in paths
    assert 'LICENSE' in paths
    assert '.github/workflows/verify-public.yml' in paths
    assert not any(path.startswith(('product/', 'android/app/', '.local/', '.artifacts/', '.tooling/', '.venv/')) for path in paths)
    assert not any('private-server' in path or 'worklogs/' in path or '/build/' in path for path in paths)
    assert not any(path.endswith(('.aar', '.so', '.zip', '.onnx', '.bin', '.wav', '.tar.bz2')) for path in paths)
    assert (output / 'README.md').is_file()
    assert ':app' not in (output / 'android/settings.gradle.kts').read_text()
    assert result['files'] == len(paths)
    assert result['bytes'] < 10 * 1024 * 1024
    modules = {path.stem for path in (output / 'framework/python/doppel').glob('*.py')}
    for path in (output / 'framework/python/doppel').glob('*.py'):
        for node in ast.walk(ast.parse(path.read_text(encoding='utf-8'))):
            if isinstance(node, ast.ImportFrom) and node.level == 1 and node.module:
                assert node.module.split('.')[0] in modules, f'{path.name} dependency missing: {node.module}'
    with pytest.raises(FileExistsError):
        exporter().export_public(ROOT, output)


@pytest.mark.parametrize('missing_doc', [
    'docs/developer/web-research.md',
    'docs/developer/direct-skills.md',
    'docs/developer/schedules.md',
    'scripts/prepare-embedded-asr.py',
    'docs/licenses/embedded-chinese-asr.txt',
    'docs/developer/gui-grounding.md',
    'integrations/gui_grounding/licenses/MAI-UI-NOTICE.txt',
    'integrations/gui_grounding/prompts.py',
    'integrations/gui_grounding/refinement.py',
    'integrations/gui_grounding/tests/test_inference_lifecycle.py',
])
def test_export_rejects_missing_runtime_documentation_without_partial_output(tmp_path, missing_doc):
    module = exporter()
    source = Path(module.export_public(ROOT, tmp_path / 'source')['output'])
    (source / missing_doc).unlink()
    output = tmp_path / 'incomplete-public'
    with pytest.raises(ValueError, match='Missing required public source') as failure:
        module.export_public(source, output)
    assert missing_doc in str(failure.value)
    assert not output.exists()


@pytest.mark.parametrize('private_path', [
    'integrations/gui_grounding/service-token.txt',
    'integrations/gui_grounding/credentials.py',
    'integrations/gui_grounding/model.safetensors',
    'integrations/gui_grounding/weights/model.safetensors',
    'integrations/gui_grounding/corpus/manifest.json',
    'integrations/gui_grounding/tests/private-screenshot.png',
    'integrations/gui_grounding/private-result.jsonl',
    'integrations/gui_grounding/licenses/unreviewed.txt',
    'integrations/other_service/server.py',
    '.tooling/gui-grounding/service-token.txt',
    '.artifacts/perception-execution/20260909/gui/corpus/manifest.json',
    'scripts/device-lab-ui-private.py',
    'tests/test_device_lab_ui_private.py',
])
def test_new_control_allowlist_denies_private_assets_and_unreviewed_siblings(private_path):
    assert not exporter().allowed(Path(private_path))


def test_device_lab_ui_has_exact_prospective_paths_without_directory_wildcards():
    module = exporter()
    assert module.allowed(Path('scripts/device-lab-ui.py'))
    assert module.allowed(Path('tests/test_device_lab_ui.py'))


def test_private_gui_files_are_not_copied_even_inside_an_exported_source_tree(tmp_path):
    module = exporter()
    source = Path(module.export_public(ROOT, tmp_path / 'source')['output'])
    denied = ['integrations/gui_grounding/service-token.txt', 'integrations/gui_grounding/corpus/manifest.json',
              'integrations/gui_grounding/weights/model.safetensors', 'integrations/gui_grounding/tests/private-screenshot.png']
    for name in denied:
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'private fixture: must not be copied')
    output = Path(module.export_public(source, tmp_path / 'public')['output'])
    assert all(not (output / name).exists() for name in denied)


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
