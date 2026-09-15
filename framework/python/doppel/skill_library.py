"""Owner-isolated, data-only Skills imports; packages never grant capabilities."""

import hashlib
import io
from pathlib import Path
import re
import shutil
import stat
import tempfile
import threading
import zipfile

from .errors import Conflict, NotFound, PermissionDenied
from .skills import SkillCatalog, contained_path


MAX_PACKAGE_BYTES = 2 * 1024 * 1024
MAX_RESOURCE_BYTES = 65536
MAX_MEMBERS = 128
MAX_FILES = 100
MAX_SKILLS = 100
_RESERVED = re.compile(r'^(CON|PRN|AUX|NUL|CLOCK\$|COM[1-9]|LPT[1-9])(?:\.|$)', re.I)


def _member_path(value):
    if not isinstance(value, str) or not value or len(value) > 240 or '\\' in value or ':' in value or any(ord(c) < 32 for c in value):
        raise ValueError('Unsafe Skills archive path')
    parts = value.split('/')
    if len(parts) > 8 or any(part in {'', '.', '..'} or part.endswith(('.', ' ')) or _RESERVED.match(part) for part in parts):
        raise ValueError('Unsafe Skills archive path')
    return parts


def _package(data, kind):
    if kind == 'markdown':
        if len(data) > MAX_RESOURCE_BYTES:
            raise ValueError('SKILL.md exceeds 64 KiB')
        files = {'SKILL.md': data}
    elif kind == 'zip':
        if len(data) > MAX_PACKAGE_BYTES:
            raise ValueError('Skills package exceeds 2 MiB')
        files, folders, seen, expanded = {}, set(), set(), 0
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as package:
                members = package.infolist()
                if not members or len(members) > MAX_MEMBERS:
                    raise ValueError('Skills archive member count exceeded')
                for member in members:
                    if member.orig_filename != member.filename:
                        raise ValueError('Ambiguous Skills archive filename')
                    name = member.filename[:-1] if member.is_dir() else member.filename
                    _member_path(name)
                    folded = name.casefold()
                    mode = stat.S_IFMT(member.external_attr >> 16)
                    if folded in seen or member.flag_bits & 1 or mode not in {0, stat.S_IFREG, stat.S_IFDIR}:
                        raise ValueError('Duplicate, encrypted or linked Skills member')
                    if (mode == stat.S_IFDIR) != member.is_dir() and mode != 0:
                        raise ValueError('Skills member type does not match path')
                    seen.add(folded)
                    if member.is_dir():
                        folders.add(name)
                        continue
                    if member.file_size > MAX_RESOURCE_BYTES or len(files) >= MAX_FILES:
                        raise ValueError('Skills file size or count exceeded')
                    expanded += member.file_size
                    if expanded > MAX_PACKAGE_BYTES:
                        raise ValueError('Expanded Skills archive exceeds 2 MiB')
                    with package.open(member) as source:
                        content = source.read(MAX_RESOURCE_BYTES + 1)
                    if len(content) != member.file_size or len(content) > MAX_RESOURCE_BYTES:
                        raise ValueError('Skills member size is inconsistent')
                    files[name] = content
        except (zipfile.BadZipFile, NotImplementedError, RuntimeError, EOFError) as error:
            raise ValueError('Invalid or unsupported Skills ZIP') from error
        roots = [name for name in files if name == 'SKILL.md' or name.endswith('/SKILL.md')]
        if len(roots) != 1 or len(roots[0].split('/')) > 2:
            raise ValueError('Import exactly one Skill with a root SKILL.md')
        prefix = roots[0][:-len('SKILL.md')]
        if prefix and any(not name.startswith(prefix) for name in files):
            raise ValueError('Every file must belong to the same Skill')
        if prefix and any(name != prefix[:-1] and not name.startswith(prefix) for name in folders):
            raise ValueError('Every directory must belong to the same Skill')
        folded_files = {name.casefold() for name in files}
        for name in [*files, *folders]:
            parts = name.casefold().split('/')
            if any('/'.join(parts[:index]) in folded_files for index in range(1, len(parts))):
                raise ValueError('Skills file and directory paths collide')
        files = {name.removeprefix(prefix): content for name, content in files.items()}
    else:
        raise ValueError('Import a SKILL.md or ZIP file')
    try:
        metadata = SkillCatalog.parse(files['SKILL.md'].decode('utf-8-sig'))
    except (KeyError, UnicodeError) as error:
        raise ValueError('SKILL.md must be UTF-8 with valid metadata') from error
    _member_path(metadata['name'])
    return metadata, files


class SkillLibrary:
    def __init__(self, runtime):
        self.data = Path(runtime.config.data_dir).resolve()
        self.data.mkdir(parents=True, exist_ok=True)
        self.host = contained_path(self.data, 'skills')
        self.imports = contained_path(self.data, 'imported-skills')
        self.host.mkdir(exist_ok=True)
        self.imports.mkdir(exist_ok=True)
        self.lock = threading.RLock()

    def _catalog(self, owner):
        if not isinstance(owner, str) or not owner or len(owner) > 256:
            raise PermissionDenied('Authenticated owner is required')
        folder = contained_path(self.imports, hashlib.sha256(owner.encode()).hexdigest())
        folder.mkdir(exist_ok=True)
        return SkillCatalog(folder, max_skills=MAX_SKILLS)

    def list_skills(self, owner):
        with self.lock:
            host = [dict(item, source='host') for item in SkillCatalog(self.host).list_skills()]
            names = {item['name'].casefold() for item in host}
            imported = [dict(item, source='imported') for item in self._catalog(owner).list_skills()
                        if item['name'].casefold() not in names]
            return sorted(host + imported, key=lambda item: item['name'].casefold())

    def _lookup(self, owner, name):
        imported = self._catalog(owner)
        imported._folder(name)
        host = SkillCatalog(self.host)
        for catalog, source in [(host, 'host'), (imported, 'imported')]:
            actual = next((item['name'] for item in catalog.list_skills() if item['name'].casefold() == name.casefold()), None)
            if actual is not None:
                return catalog, source, actual
        raise NotFound('Skill not found')

    def read_skill(self, owner, name):
        with self.lock:
            catalog, source, actual = self._lookup(owner, name)
            return dict(catalog.read_skill(actual), source=source)

    def read_resource(self, owner, name, path):
        with self.lock:
            _member_path(path)
            catalog, _, actual = self._lookup(owner, name)
            try:
                return catalog.read_resource(actual, path)
            except FileNotFoundError:
                raise NotFound('Skill resource not found') from None

    def import_package(self, owner, data, kind):
        metadata, files = _package(data, kind)
        name = metadata['name']
        with self.lock:
            catalog = self._catalog(owner)
            if any(item['name'].casefold() == name.casefold() for item in self.list_skills(owner)):
                raise Conflict('Skill name already exists; remove the import before replacing it')
            if len(catalog.list_skills()) >= MAX_SKILLS:
                raise ValueError('Imported Skills count limit exceeded')
            target = catalog._folder(name)
            staging = Path(tempfile.mkdtemp(prefix='.stage-', dir=self.imports))
            try:
                for relative, content in files.items():
                    destination = contained_path(staging, relative)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    with destination.open('xb') as output:
                        output.write(content)
                # A complete nonempty Skill directory is published with one rename.
                staging.rename(target)
            except FileExistsError:
                raise Conflict('Skill name already exists') from None
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
            metadata.pop('instructions')
            return dict(metadata, source='imported')

    def delete_skill(self, owner, name):
        with self.lock:
            folder = self._catalog(owner)._folder(name)
            if not (folder / 'SKILL.md').is_file():
                raise NotFound('Imported Skill not found')
            shutil.rmtree(folder)


def get_skill_library(runtime):
    library = getattr(runtime, 'skill_library', None)
    if library is None:
        library = runtime.skill_library = SkillLibrary(runtime)
    return library
