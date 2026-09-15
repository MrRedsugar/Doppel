"""Bounded progressive reading of untrusted skill instructions and resources."""

from pathlib import Path, PureWindowsPath
import re

import yaml


def contained_path(root: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not relative or '\x00' in relative or ':' in relative:
        raise ValueError('Invalid relative path')
    parts = relative.replace('\\', '/').split('/')
    if Path(relative).is_absolute() or PureWindowsPath(relative).is_absolute() or any(p in ('', '.', '..') for p in parts):
        raise ValueError('Path must stay within the authorized root')
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink() or (hasattr(current, 'is_junction') and current.is_junction()):
            raise ValueError('Links and junctions are not permitted')
    if not current.resolve().is_relative_to(root.resolve()):
        raise ValueError('Path escapes authorized root')
    return current


class SkillCatalog:
    def __init__(self, root: str | Path, *, max_skill_bytes: int = 65536, max_resource_bytes: int = 65536, max_skills: int = 100):
        self.root = Path(root).resolve(strict=True)
        self.max_skill_bytes = max_skill_bytes
        self.max_resource_bytes = max_resource_bytes
        self.max_skills = max_skills
        if min(max_skill_bytes, max_resource_bytes, max_skills) <= 0:
            raise ValueError('Limits must be positive')

    def _folder(self, name: str) -> Path:
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', name):
            raise ValueError('Invalid skill name')
        return contained_path(self.root, name)

    @staticmethod
    def _read(path: Path, limit: int) -> str:
        with path.open('rb') as stream:
            content = stream.read(limit + 1)
        if len(content) > limit:
            raise ValueError('Skill resource byte limit exceeded')
        return content.decode('utf-8-sig')

    def read_skill(self, name: str) -> dict:
        folder = self._folder(name)
        content = self._read(contained_path(folder, 'SKILL.md'), self.max_skill_bytes)
        return self.parse(content, name)

    @staticmethod
    def parse(content: str, expected_name: str | None = None) -> dict:
        lines = content.splitlines()
        if not lines or lines[0] != '---':
            raise ValueError('SKILL.md requires YAML frontmatter')
        try:
            end = lines.index('---', 1)
            # SafeLoader rejects object construction; aliases are rejected to keep metadata finite.
            frontmatter = '\n'.join(lines[1:end])
            if any(isinstance(event, yaml.AliasEvent) for event in yaml.parse(frontmatter)):
                raise ValueError('YAML aliases are not supported')
            meta = yaml.safe_load(frontmatter)
        except (yaml.YAMLError, ValueError, RecursionError) as exc:
            raise ValueError('Invalid skill frontmatter') from exc
        name = meta.get('name') if isinstance(meta, dict) else None
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', name) or (expected_name is not None and name != expected_name) or not isinstance(meta.get('description'), str):
            raise ValueError('Skill name must match folder and description must be text')
        dependencies = meta.get('dependencies', {})
        if not isinstance(dependencies, dict) or set(dependencies) - {'runtimes', 'tools'}:
            raise ValueError('Dependencies must declare runtimes and tools')
        platforms = meta.get('platforms', [])
        for values in [platforms, *dependencies.values()]:
            if not isinstance(values, list) or len(values) > 32 or any(not isinstance(v, str) or len(v) > 256 for v in values):
                raise ValueError('Metadata lists require bounded strings')
        return {'name': name, 'description': meta['description'][:1024], 'platforms': platforms,
                'dependencies': dependencies, 'runtime_status': 'unverified',
                'instructions': '\n'.join(lines[end + 1:]), 'trusted': False}

    def list_skills(self) -> list[dict]:
        names = []
        for folder in self.root.iterdir():
            if folder.is_dir() and (folder / 'SKILL.md').is_file():
                names.append(folder.name)
                if len(names) > self.max_skills:
                    raise ValueError('Skill catalog count limit exceeded')
        result = []
        for name in sorted(names):
            item = self.read_skill(name)
            item.pop('instructions')
            result.append(item)
        return result

    def read_resource(self, name: str, relative_path: str) -> str:
        return self._read(contained_path(self._folder(name), relative_path), self.max_resource_bytes)
