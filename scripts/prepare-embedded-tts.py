"""Prepare only pinned, permissively licensed Chinese inference assets."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import urllib.request


ARCHIVE = 'kokoro-int8-multi-lang-v1_1.tar.bz2'
SHA256 = 'a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6'
URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/' + ARCHIVE
PREFIX = 'kokoro-int8-multi-lang-v1_1/'
SIZES = {'model.int8.onnx': 114299010, 'lexicon-zh.txt': 2119465, 'tokens.txt': 1111,
         'voices.bin': 53790720, 'LICENSE': 11358}
VOICE_INDEX = 3
VOICE_BYTES = 510 * 256 * 4


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def lexicon_bytes(tokens, lexicon):
    vocabulary = {}
    for line in tokens.decode('utf-8').splitlines():
        symbol, number = line.rsplit(' ', 1)
        vocabulary[symbol] = int(number)
    entries = {}
    for line in lexicon.decode('utf-8').splitlines():
        parts = line.split()
        if not parts:
            continue
        phones = tuple(vocabulary[value] for value in parts[1:] if value in vocabulary)
        if phones and parts[0] not in entries:
            entries[parts[0]] = phones
    result = io.BytesIO()
    result.write(b'DPLX1')
    result.write(struct.pack('>I', len(entries)))
    for word, phones in sorted(entries.items()):
        encoded = word.encode('utf-8')
        if not 0 < len(encoded) <= 512 or not 0 < len(phones) <= 256:
            raise ValueError('Unexpected Chinese lexicon entry')
        result.write(struct.pack('>H', len(encoded)))
        result.write(encoded)
        result.write(struct.pack('>H', len(phones)))
        result.write(struct.pack('>' + 'H' * len(phones), *phones))
    return result.getvalue(), len(entries)


def prepare(archive, output):
    if digest(archive) != SHA256:
        raise ValueError('Kokoro archive SHA-256 mismatch')
    output = Path(output) / 'tts' / 'kokoro-zh'
    output.mkdir(parents=True, exist_ok=True)
    selected = {}
    with tarfile.open(archive, 'r|bz2') as package:
        for member in package:
            name = member.name.removeprefix(PREFIX)
            if name not in SIZES:
                continue
            size = SIZES[name]
            if not member.isfile() or member.size != size:
                raise ValueError('Unexpected Kokoro package member')
            with package.extractfile(member) as source:
                selected[name] = source.read(size + 1)
            if len(selected[name]) != size:
                raise ValueError('Incomplete Kokoro package member')
    if set(selected) != set(SIZES):
        raise ValueError('Incomplete Kokoro archive')
    lexicon, count = lexicon_bytes(selected['tokens.txt'], selected['lexicon-zh.txt'])
    assets = {'model.int8.onnx': selected['model.int8.onnx'], 'lexicon.bin': lexicon,
              'voice.bin': selected['voices.bin'][VOICE_INDEX * VOICE_BYTES:(VOICE_INDEX + 1) * VOICE_BYTES],
              'APACHE-2.0.txt': selected['LICENSE']}
    notice = Path(__file__).resolve().parent.parent / 'docs' / 'licenses' / 'embedded-chinese-tts.txt'
    assets['NOTICE.txt'] = notice.read_bytes()
    manifest = {'archive': ARCHIVE, 'archive_sha256': SHA256, 'source': URL,
                'voice': 'zf_001', 'voice_index': VOICE_INDEX, 'sample_rate': 24000,
                'style_rows': 510, 'style_width': 256, 'lexicon_entries': count,
                'assets': {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()} for name, data in assets.items()}}
    assets['manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    for name, data in assets.items():
        with tempfile.NamedTemporaryFile(dir=output, delete=False) as temporary:
            temporary.write(data)
            temporary_path = Path(temporary.name)
        try:
            temporary_path.replace(output / name)
        finally:
            temporary_path.unlink(missing_ok=True)
    return manifest


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=root / '.tooling' / 'tts' / ARCHIVE)
    parser.add_argument('--output', type=Path, default=root / 'android' / 'sdk' / 'build' / 'generated' / 'ttsAssets')
    parser.add_argument('--download', action='store_true', help='Fetch the pinned archive only when it is missing')
    args = parser.parse_args()
    if not args.archive.is_file():
        if not args.download:
            parser.error('Pinned archive is missing; run once with --download before an offline build')
        args.archive.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.archive.with_suffix(args.archive.suffix + '.part')
        try:
            with urllib.request.urlopen(URL, timeout=30) as source, temporary.open('wb') as target:
                total = 0
                while block := source.read(1024 * 1024):
                    total += len(block)
                    if total > 160 * 1024 * 1024:
                        raise ValueError('Kokoro download exceeds expected bound')
                    target.write(block)
            if digest(temporary) != SHA256:
                raise ValueError('Kokoro download SHA-256 mismatch')
            temporary.replace(args.archive)
        finally:
            temporary.unlink(missing_ok=True)
    print(json.dumps(prepare(args.archive, args.output), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
