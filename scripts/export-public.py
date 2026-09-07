"""Create a reviewed public source tree using a narrow, fail-closed allowlist."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile


MODULES = {
    '__init__', 'cli', 'completion', 'document_api', 'documents', 'errors', 'extension_api',
    'extension_runtime', 'extensions', 'gateway', 'harness', 'harness_worker',
    'internal', 'mcp_server', 'model_context', 'model_proxy', 'models', 'perception', 'policy',
    'retention', 'runtime', 'skills', 'store', 'tool_outcomes',
}
FILES = {
    '.github/workflows/verify-public.yml',
    '.gitignore', 'framework/pyproject.toml', 'framework/MANIFEST.in', 'framework/README.md', 'framework/LICENSE',
    'framework/NOTICE', 'framework/THIRD_PARTY_NOTICES.md',
    'framework/tests/conftest.py',
    'android/build.gradle.kts', 'android/gradle.properties', 'android/gradlew',
    'android/gradlew.bat', 'android/gradle/wrapper/gradle-wrapper.jar',
    'android/gradle/wrapper/gradle-wrapper.properties',
    'docs/developer/android-public.md', 'docs/developer/extensions.md',
    'docs/developer/documents.md', 'docs/developer/standalone.md',
    'docs/developer/open-source.md', 'docs/developer/public-readme.md',
    'docs/developer/data-retention.md',
    'docs/developer/interaction-login.md',
    'docs/architecture/public-system.md', 'docs/contracts/public-http-v1.md',
    'scripts/export-public.py',
}
IGNORED = {'build', '__pycache__', '.gradle', '.kotlin', '.pytest_cache', '.git', 'node_modules'}
ANDROID_LICENSES = {
    'NOTICE.txt', 'Apache-2.0.txt', 'JNA-LICENSE.txt', 'OpenBLAS-LICENSE.txt',
    'CLAPACK-COPYING.txt', 'F2C-NOTICE.txt', 'libffi-LICENSE.txt',
    'libcxx-LICENSE.txt', 'libcxxabi-LICENSE.txt', 'libunwind-LICENSE.txt',
    'Lucide-LICENSE.txt', 'Lucide-PROVENANCE.txt',
}
ANDROID_SETTINGS = '''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "DoppelPublic"
include(":sdk", ":developer-app", ":test-app")
'''
SECRET = re.compile(rb'sk-[A-Za-z0-9_-]{24,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
PRIVATE_IMPORT = re.compile(rb'(?:from|import)\s+doppel_product\b|dev\.doppel\.app\b|/v1/auth/(?:login|code)')


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
    if name.startswith('framework/examples/'):
        return path.suffix in {'.py', '.md'}
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
            if relative.parts[:2] in [('framework', 'python'), ('android', 'sdk'), ('android', 'developer-app')] and PRIVATE_IMPORT.search(data):
                raise ValueError('Private product reference in public source: ' + str(relative))
            total += len(data)
            if total > 10 * 1024 * 1024:
                raise ValueError('Public source exceeds ten MiB limit')
            selected.append((relative, data))
    available = {path.as_posix() for path, _ in selected}
    required = {'framework/pyproject.toml', 'framework/LICENSE', 'framework/NOTICE', 'framework/THIRD_PARTY_NOTICES.md',
                'framework/python/doppel/cli.py', 'android/sdk/build.gradle.kts',
                'android/developer-app/build.gradle.kts', 'android/test-app/build.gradle.kts', 'docs/developer/public-readme.md'}
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
