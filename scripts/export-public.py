"""Create a reviewed public source tree using a narrow, fail-closed allowlist."""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import tempfile
import wave


MODULES = {
    '__init__', 'cli', 'completion', 'document_api', 'documents', 'errors', 'extension_api',
    'extension_runtime', 'extensions', 'gateway', 'harness', 'harness_worker',
    'internal', 'intent', 'mcp_server', 'model_context', 'model_proxy', 'models', 'perception', 'policy',
    'paths', 'providers', 'retention', 'runtime', 'scheduler', 'speech_api', 'store', 'submission', 'tool_outcomes',
}
RUNTIME_SUPPORT_FILES = {
    'docs/licenses/task-completed-sound.md',
    'android/sdk/libs/README.md',
    'android/sdk/libs/poi-android-5.5.1.provenance.json',
    'android/sdk/poi-consumer-rules.pro',
    'android/sdk/src/main/assets/third_party/BouncyCastle-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/FONTBOX-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/FONTBOX-NOTICE.txt',
    'android/sdk/src/main/assets/third_party/PDFBOX-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/PDFBOX-NOTICE.txt',
    'android/sdk/src/main/assets/third_party/agent-reach/LICENSE',
    'android/sdk/src/main/assets/third_party/agent-reach/SOURCE.md',
    'android/sdk/src/main/assets/third_party/assistant-ui-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/deepseek-harness-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/DEBUG-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/JINA-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/MATHML-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/MS-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/MSW-INTERCEPTORS-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/OPEN-DRAFT-UNTIL-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/OUTVARIANT-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/READABILITY-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/RETTIME-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/SOURCE.md',
    'android/sdk/src/main/assets/third_party/jina-reader/XMLDOM-LICENSE.txt',
    'android/sdk/src/main/assets/third_party/jina-reader/reader-core.js',
    'android/sdk/src/main/assets/third_party/jina-reader/reader-network.js',
    'android/sdk/src/main/assets/third_party/poi-android/NOTICE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/com.fasterxml_aalto-xml_1.3.3_META-INF_LICENSE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/commons-codec_commons-codec_1.20.0_META-INF_LICENSE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/commons-codec_commons-codec_1.20.0_META-INF_NOTICE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/commons-io_commons-io_2.21.0_META-INF_LICENSE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/commons-io_commons-io_2.21.0_META-INF_NOTICE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-collections4_4.5.0_META-INF_LICENSE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-collections4_4.5.0_META-INF_NOTICE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-compress_1.28.0_META-INF_LICENSE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-compress_1.28.0_META-INF_NOTICE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-lang3_3.18.0_META-INF_LICENSE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-lang3_3.18.0_META-INF_NOTICE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-math3_3.6.1_META-INF_LICENSE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.commons_commons-math3_3.6.1_META-INF_NOTICE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.logging.log4j_log4j-api_2.26.1_META-INF_LICENSE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.logging.log4j_log4j-api_2.26.1_META-INF_NOTICE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi-ooxml-lite_5.5.1_META-INF_LICENSE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi-ooxml-lite_5.5.1_META-INF_NOTICE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi-ooxml_5.5.1_META-INF_LICENSE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi-ooxml_5.5.1_META-INF_NOTICE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi-scratchpad_5.5.1_META-INF_LICENSE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi-scratchpad_5.5.1_META-INF_NOTICE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi_5.5.1_META-INF_LICENSE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.poi_poi_5.5.1_META-INF_NOTICE.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.xmlbeans_xmlbeans_5.3.0_META-INF_LICENSE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.apache.xmlbeans_xmlbeans_5.3.0_META-INF_NOTICE.txt.txt',
    'android/sdk/src/main/assets/third_party/poi-android/org.codehaus.woodstox_stax2-api_4.2.2_META-INF_LICENSE.txt',
    'android/sdk/src/test/resources/attachments/NOTICE.txt',
    'android/sdk/src/test/resources/attachments/sample2.ppt',
    'android/sdk/src/test/resources/attachments/simple.doc',
    'android/sdk/src/test/resources/paraformer-fbank-golden.txt',
    'framework/python/doppel/submission.py',
    'scripts/prepare-android-office-parser.py',
    'tools/jina-reader-core/README.md',
    'tools/jina-reader-core/build.mjs',
    'tools/jina-reader-core/index.js',
    'tools/jina-reader-core/network-smoke.mjs',
    'tools/jina-reader-core/network.js',
    'tools/jina-reader-core/package-lock.json',
    'tools/jina-reader-core/package.json',
    'tools/jina-reader-core/smoke.mjs',
    'tools/jina-reader-core/vendor/LICENSE',
    'tools/jina-reader-core/vendor/markify.ts',
}
GUI_GROUNDING_FILES = {
    'integrations/gui_grounding/README.md',
    'integrations/gui_grounding/THIRD_PARTY_NOTICES.md',
    'integrations/gui_grounding/requirements.txt',
    'integrations/gui_grounding/start.ps1',
    'integrations/gui_grounding/stop.ps1',
    'integrations/gui_grounding/models.py',
    'integrations/gui_grounding/prompts.py',
    'integrations/gui_grounding/fetch.py',
    'integrations/gui_grounding/protocol.py',
    'integrations/gui_grounding/patch_projection.py',
    'integrations/gui_grounding/process_guard.py',
    'integrations/gui_grounding/inference.py',
    'integrations/gui_grounding/refinement.py',
    'integrations/gui_grounding/server.py',
    'integrations/gui_grounding/evaluate.py',
    'integrations/gui_grounding/tests/test_fetch.py',
    'integrations/gui_grounding/tests/test_protocol.py',
    'integrations/gui_grounding/tests/test_patch_projection.py',
    'integrations/gui_grounding/tests/test_process_guard.py',
    'integrations/gui_grounding/tests/test_server.py',
    'integrations/gui_grounding/tests/test_refinement.py',
    'integrations/gui_grounding/tests/test_inference_lifecycle.py',
    'integrations/gui_grounding/licenses/MAI-UI-Apache-2.0.txt',
    'integrations/gui_grounding/licenses/MAI-UI-NOTICE.txt',
    'integrations/gui_grounding/licenses/GUI-Owl-MIT.txt',
    'docs/developer/gui-grounding.md',
}
FILES = {
    '.github/workflows/verify-public.yml',
    '.gitignore', 'framework/pyproject.toml', 'framework/MANIFEST.in', 'framework/README.md', 'framework/LICENSE',
    'framework/NOTICE', 'framework/THIRD_PARTY_NOTICES.md',
    'framework/examples/mcp_server.py',
    'framework/tests/conftest.py',
    'android/build.gradle.kts', 'android/gradle.properties', 'android/gradlew',
    'android/gradlew.bat', 'android/gradle/wrapper/gradle-wrapper.jar',
    'android/gradle/wrapper/gradle-wrapper.properties',
    'docs/developer/android-public.md', 'docs/developer/extensions.md',
    'docs/developer/documents.md', 'docs/developer/standalone.md',
    'docs/developer/open-source.md', 'docs/developer/public-readme.md',
    'docs/developer/data-retention.md',
    'docs/developer/interaction-login.md',
    'docs/developer/payment-delegation.md',
    'docs/developer/cloud-speech.md',
    'docs/developer/phone-direct.md', 'docs/developer/release-hardening.md', 'docs/developer/schedules.md',
    'docs/developer/model-connections.md',
    'docs/developer/device-compatibility.md',
    'docs/developer/direct-skills.md', 'docs/developer/web-research.md',
    'docs/developer/perception-efficiency.md',
    'docs/developer/app-learning.md',
    'docs/developer/continuous-conversations.md',
    'docs/developer/device-lab.md', 'docs/architecture/adaptive-visual-control.md',
    'scripts/device-lab.py', 'tests/test_device_lab.py',
    'scripts/device-lab-ui.py', 'tests/test_device_lab_ui.py',
    'docs/architecture/planned-control.md', 'docs/developer/local-visual-motor.md',
    'scripts/adb-shell-bridge.ps1', 'scripts/adb-wireless-shell-bridge.ps1',
    'docs/developer/adb-shell-bridge.md', 'docs/licenses/adb-shell-bridge.md',
    'android/sdk/consumer-rules.pro',
    'scripts/prepare-embedded-tts.py', 'scripts/verify-embedded-tts.py',
    'scripts/prepare-embedded-asr.py', 'scripts/verify-embedded-asr.py', 'tests/test_prepare_embedded_asr.py',
    'docs/licenses/embedded-chinese-asr.txt', 'docs/architecture/qwen-device-loop.md',
    'docs/licenses/embedded-chinese-tts.txt', 'docs/developer/embedded-chinese-tts.md',
    'docs/architecture/public-system.md', 'docs/contracts/public-http-v1.md',
    'scripts/export-public.py',
} | GUI_GROUNDING_FILES | RUNTIME_SUPPORT_FILES
IGNORED = {'build', '__pycache__', '.gradle', '.kotlin', '.pytest_cache', '.git', 'node_modules'}
ANDROID_LICENSES = {
    'NOTICE.txt', 'Apache-2.0.txt', 'JNA-LICENSE.txt', 'OpenBLAS-LICENSE.txt',
    'CLAPACK-COPYING.txt', 'F2C-NOTICE.txt', 'libffi-LICENSE.txt',
    'libcxx-LICENSE.txt', 'libcxxabi-LICENSE.txt', 'libunwind-LICENSE.txt',
    'Lucide-LICENSE.txt', 'Lucide-PROVENANCE.txt',
    'jsoup-LICENSE.txt', 'MPL-2.0.txt',
}
ANDROID_SETTINGS = '''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "DoppelPublic"
include(":sdk", ":developer-app", ":test-app")
'''
SECRET = re.compile(rb'sk-[A-Za-z0-9_-]{24,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
PRIVATE_IMPORT = re.compile(rb'(?:from|import)\s+doppel_product\b|dev\.doppel\.app\b|/v1/auth/(?:login|code)')


# Reviewed literals are data, not dependencies on the excluded private product.
# Mask only these exact snippets for the audit; exported bytes stay unchanged.
PRIVATE_REFERENCE_LITERALS = {
    'android/sdk/src/main/java/dev/doppel/sdk/DoppelAccessibilityService.kt':
        b'pkg in setOf(packageName, "dev.doppel.app", "dev.doppel.developer")',
    'android/sdk/src/test/java/dev/doppel/sdk/cloud/CloudAccountClientTest.kt':
        b'"/v1/auth/login" -> {',
}


def has_private_reference(path: Path, data: bytes) -> bool:
    literal = PRIVATE_REFERENCE_LITERALS.get(path.as_posix())
    if literal:
        data = data.replace(literal, b'')
    return PRIVATE_IMPORT.search(data) is not None


def write_public_completion_sound(target: Path) -> None:
    """Original synthetic chime; never redistribute the private app's recording."""
    sample_rate = 22050
    samples = sample_rate // 4
    target.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(target), 'wb') as sound:
        sound.setparams((1, 2, sample_rate, 0, 'NONE', 'not compressed'))
        sound.writeframes(b''.join(
            struct.pack('<h', round(8000 * math.sin(2 * math.pi * 880 * i / sample_rate)
                                   * min(1, i / 220) * (1 - i / samples)))
            for i in range(samples)
        ))


def allowed(path: Path) -> bool:
    name = path.as_posix()
    if any(part in IGNORED or part.endswith('.egg-info') for part in path.parts):
        return False
    if name in FILES:
        return True
    if path.parent.as_posix() == 'android/sdk/src/main/assets/third_party':
        return path.name in ANDROID_LICENSES
    if path.parent.as_posix() == 'framework/python/doppel':
        return path.suffix == '.py' and path.stem in MODULES
    if name.startswith('framework/tests/'):
        return len(path.parts) == 3 and path.name.startswith('test_') and path.suffix == '.py'
    if len(path.parts) >= 3 and path.parts[:2] in [('android', 'sdk'), ('android', 'developer-app'), ('android', 'test-app')]:
        return (len(path.parts) == 3 and path.name == 'build.gradle.kts') or (
            path.parts[2] == 'src' and path.suffix in {'.kt', '.java', '.xml', '.png', '.webp'}
        )
    return False


def export_public(source: str | Path, output: str | Path) -> dict:
    source, output = Path(source).resolve(strict=True), Path(output).resolve()
    if output.exists():
        raise FileExistsError('Public output already exists; choose a new directory')
    if source.is_relative_to(output) or any(output.is_relative_to(source / part) for part in ['framework', 'android', 'docs', 'scripts']):
        raise ValueError('Export destination must be outside public source folders')
    selected = []
    total = 0
    for root, folders, names in os.walk(source, followlinks=False):
        directory = Path(root)
        folders[:] = [name for name in folders if name not in IGNORED and (not name.startswith('.') or name == '.github') and name not in {'product', 'exports', 'dist'}]
        for name in sorted(names):
            path = directory / name
            relative = path.relative_to(source)
            if not allowed(relative):
                continue
            if any(parent.is_symlink() or (hasattr(parent, 'is_junction') and parent.is_junction()) for parent in [path, *path.parents] if parent.is_relative_to(source)):
                raise ValueError('Public source links/junctions are not permitted')
            size = path.stat().st_size
            if size > 2 * 1024 * 1024:
                raise ValueError('Public file exceeds two MiB limit: ' + str(relative))
            data = path.read_bytes()
            matches = [match.group() for match in SECRET.finditer(data)]
            known_fixture = relative.as_posix() in {'framework/tests/test_model_proxy.py', 'framework/tests/test_internal_tools.py', 'framework/tests/test_scope_takeover.py'}
            if any(not (known_fixture and match == b'sk-' + b'fixture-not-a-real-provider-key') for match in matches):
                raise ValueError('Possible secret in public source: ' + str(relative))
            if relative.parts[:2] in [('framework', 'python'), ('android', 'sdk'), ('android', 'developer-app')] and has_private_reference(relative, data):
                raise ValueError('Private product reference in public source: ' + str(relative))
            total += len(data)
            if total > 10 * 1024 * 1024:
                raise ValueError('Public source exceeds ten MiB limit')
            selected.append((relative, data))
    available = {path.as_posix() for path, _ in selected}
    required = {'framework/pyproject.toml', 'framework/LICENSE', 'framework/NOTICE', 'framework/THIRD_PARTY_NOTICES.md',
                'framework/python/doppel/cli.py', 'framework/python/doppel/providers.py', 'android/sdk/build.gradle.kts',
                'android/developer-app/build.gradle.kts', 'android/test-app/build.gradle.kts', 'docs/developer/public-readme.md',
                'scripts/prepare-embedded-tts.py', 'scripts/verify-embedded-tts.py',
                'scripts/prepare-embedded-asr.py', 'scripts/verify-embedded-asr.py', 'tests/test_prepare_embedded_asr.py',
                'docs/licenses/embedded-chinese-asr.txt', 'docs/architecture/qwen-device-loop.md', 'docs/developer/model-connections.md',
                'docs/licenses/embedded-chinese-tts.txt', 'docs/developer/embedded-chinese-tts.md',
                'docs/developer/device-compatibility.md',
                'docs/developer/web-research.md', 'docs/developer/direct-skills.md', 'docs/developer/schedules.md',
                'docs/architecture/planned-control.md', 'docs/developer/local-visual-motor.md',
                'scripts/device-lab-ui.py', 'tests/test_device_lab_ui.py',
                'scripts/adb-shell-bridge.ps1', 'scripts/adb-wireless-shell-bridge.ps1',
                'docs/developer/adb-shell-bridge.md', 'docs/licenses/adb-shell-bridge.md'} | GUI_GROUNDING_FILES | RUNTIME_SUPPORT_FILES
    if not required <= available:
        raise ValueError('Missing required public source: ' + ', '.join(sorted(required - available)))
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='.doppel-public-', dir=output.parent))
    try:
        for relative, data in selected:
            target = staging / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        (staging / 'android/settings.gradle.kts').write_text(ANDROID_SETTINGS, encoding='utf-8')
        write_public_completion_sound(staging / 'android/sdk/src/main/res/raw/task_completed.wav')
        for source_name, target_name in [('framework/LICENSE', 'LICENSE'), ('framework/NOTICE', 'NOTICE'),
                                         ('framework/THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md'),
                                         ('docs/developer/public-readme.md', 'README.md'),
                                         ('docs/developer/android-public.md', 'docs/developer/android.md')]:
            shutil.copyfile(staging / source_name, staging / target_name)
        (staging / 'android/gradlew').chmod(0o755)
        # Refuse pre-existing output, including a concurrent writer's directory.
        if output.exists():
            raise FileExistsError('Public output was created during export')
        staging.rename(output)
        files = [path for path in output.rglob('*') if path.is_file()]
        return {'output': str(output), 'files': len(files), 'bytes': sum(path.stat().st_size for path in files),
                'sha256': {path.relative_to(output).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}}
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    result = export_public(args.source, args.output)
    print(json.dumps({key: value for key, value in result.items() if key != 'sha256'}, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
