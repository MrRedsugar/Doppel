"""Synthesize fixed public Chinese fixtures and inspect actual ONNX audio."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import time
import wave

import numpy as np
import onnxruntime as ort


def load_lexicon(path):
    source = io.BytesIO(path.read_bytes())
    assert source.read(5) == b'DPLX1'
    entries = {}
    for _ in range(struct.unpack('>I', source.read(4))[0]):
        word = source.read(struct.unpack('>H', source.read(2))[0]).decode()
        count = struct.unpack('>H', source.read(2))[0]
        entries[word] = struct.unpack('>' + 'H' * count, source.read(2 * count))
    assert source.read() == b''
    return entries


def tokens(text, lexicon):
    result, index = [], 0
    longest = max(map(len, lexicon))
    punctuation = {'，': 3, '。': 4, '！': 5, '？': 6}
    while index < len(text):
        if text[index] in punctuation:
            result.append(punctuation[text[index]])
            index += 1
            continue
        for count in range(min(longest, len(text) - index), 0, -1):
            word = text[index:index + count]
            if word in lexicon:
                result.extend(lexicon[word]); index += count
                break
        else:
            raise ValueError('Fixture contains an unsupported character')
    assert 0 < len(result) < 510
    return np.array([[0, *result, 0]], dtype=np.int64)


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, default=root / 'android/sdk/build/generated/ttsAssets/tts/kokoro-zh')
    parser.add_argument('--output', type=Path, default=root / '.artifacts/tts')
    args = parser.parse_args()
    manifest = json.loads((args.assets / 'manifest.json').read_text())
    for name, record in manifest['assets'].items():
        with (args.assets / name).open('rb') as source:
            assert hashlib.file_digest(source, 'sha256').hexdigest() == record['sha256']
    lexicon = load_lexicon(args.assets / 'lexicon.bin')
    styles = np.fromfile(args.assets / 'voice.bin', dtype='<f4').reshape(510, 256)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.enable_mem_pattern = False
    session = ort.InferenceSession(str(args.assets / 'model.int8.onnx'), options, providers=['CPUExecutionProvider'])
    assert session.get_modelmeta().custom_metadata_map['sample_rate'] == '24000'
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    for index, text in enumerate(['你好，任务已经完成。', '表格已经整理好了，请查看结果。']):
        ids = tokens(text, lexicon)
        started = time.monotonic()
        audio = session.run(['audio'], {'tokens': ids, 'style': styles[ids.size - 2][None, :], 'speed': np.array([1.0], np.float32)})[0]
        elapsed = time.monotonic() - started
        assert audio.ndim == 1 and 2400 < audio.size < 24000 * 60 and np.isfinite(audio).all()
        rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
        assert rms > 0.001
        destination = args.output / f'kokoro-zh-fixture-{index + 1}.wav'
        with wave.open(str(destination), 'wb') as output:
            output.setnchannels(1); output.setsampwidth(2); output.setframerate(24000)
            output.writeframes((np.clip(audio, -1, 1) * 32767).astype('<i2').tobytes())
        results.append({'fixture': index + 1, 'text': text, 'phonemes': ids.size - 2, 'samples': audio.size,
                        'audio_seconds': audio.size / 24000, 'inference_seconds': round(elapsed, 3), 'rms': rms,
                        'peak': float(np.max(np.abs(audio))), 'wav': str(destination)})
    report = {'runtime': ort.__version__, 'provider': 'CPUExecutionProvider', 'voice': 'zf_001', 'results': results}
    (args.output / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
