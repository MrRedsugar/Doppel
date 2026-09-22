"""Resolve data paths inside the host-authorized directory without following links."""

from pathlib import Path, PureWindowsPath


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
