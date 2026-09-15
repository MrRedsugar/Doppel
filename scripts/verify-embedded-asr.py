"""Compare production Kotlin features + local ONNX inference on fixed WAV fixtures.

Run with a Python containing numpy and onnxruntime. No network or device calls.
Measurements are Windows desktop evidence, not ARM Android performance claims.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import wave

import numpy as np
import onnxruntime as ort


RUNNER = r'''
package dev.doppel.sdk
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main(args: Array<String>) {
    for (name in args) {
        val source = File(name)
        val wav = source.readBytes()
        val samples = ShortArray((wav.size - 44) / 2)
        ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val started = System.nanoTime()
        val result = EmbeddedAsrFrontend.filterbank(samples)
        val elapsed = (System.nanoTime() - started) / 1000000.0
        val output = ByteBuffer.allocate(result.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        result.forEach { output.putFloat(it) }
        File(name + ".fbank").writeBytes(output.array())
        println("${source.name}\t$elapsed")
    }
}
'''


def normalized(text: str) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]", "", text).lower()


def distance(a: str, b: str) -> int:
    row = list(range(len(b) + 1))
    for i, left in enumerate(a):
        nxt = [i + 1]
        for j, right in enumerate(b):
            nxt.append(min(nxt[-1] + 1, row[j + 1] + 1, row[j] + (left != right)))
        row = nxt
    return row[-1]


def decode_words(words: list[str]) -> str:
    result = ""
    merge = False
    previous_ascii = False
    for raw in words:
        if raw == "</s>":
            break
        if raw in ("<blank>", "<s>", ""):
            continue
        continued = raw.endswith("@@")
        token = raw[:-2] if continued else raw
        ascii_token = ord(token[0]) < 128
        if result and not merge and (ascii_token or previous_ascii):
            result += " "
        result += token
        merge = continued
        previous_ascii = ascii_token
    return result.strip()


def main() -> None:
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=repo / ".tooling/asr/fixtures")
    parser.add_argument("--model", type=Path, default=repo / "android/sdk/build/generated/asrAssets/asr")
    parser.add_argument("--output", type=Path, default=repo / ".tooling/asr/desktop-kotlin-onnx-report.json")
    args = parser.parse_args()
    expected = json.loads((args.fixtures / "expected.json").read_text(encoding="utf-8-sig"))
    paths = [args.fixtures / f"{i}.wav" for i in range(len(expected))]
    # Make fixture input match the capture's canonical 44-byte WAV header.
    for path in paths:
        with wave.open(str(path), "rb") as source:
            assert source.getnchannels() == 1 and source.getsampwidth() == 2 and source.getframerate() == 16000
            pcm = source.readframes(source.getnframes())
        with wave.open(str(path), "wb") as output:
            output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000); output.writeframes(pcm)
    tooling = repo / ".tooling/asr"
    runner = tooling / "FrontendDump.kt"
    runner.write_text(RUNNER, encoding="utf-8")
    java = next((repo / ".tooling/jdk21").glob("*/bin/java.exe"))
    lib = next((repo / ".tooling/gradle-cache/wrapper/dists").glob("gradle-8.11.1-bin/*/gradle-8.11.1/lib"))
    stdlib = lib / "kotlin-stdlib-2.0.20.jar"
    jar = tooling / "frontend-benchmark.jar"
    subprocess.run([str(java), "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-classpath", str(stdlib), "-d", str(jar), str(repo / "android/sdk/src/main/java/dev/doppel/sdk/EmbeddedAsrFrontend.kt"), str(runner)], check=True)
    run = subprocess.run([str(java), "-cp", os.pathsep.join([str(jar), str(stdlib)]), "dev.doppel.sdk.FrontendDumpKt", *map(str, paths)], check=True, capture_output=True, text=True)
    feature_ms = {line.split("\t")[0]: float(line.split("\t")[1]) for line in run.stdout.splitlines()}
    sys.path.insert(0, str(tooling / "python"))
    import psutil
    process = psutil.Process()
    baseline = process.memory_info().rss
    peak = [baseline]
    stopped = threading.Event()
    def sample():
        while not stopped.wait(0.01):
            peak[0] = max(peak[0], process.memory_info().rss)
    monitor = threading.Thread(target=sample, daemon=True)
    monitor.start()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.enable_cpu_mem_arena = False
    options.enable_mem_pattern = False
    start = time.perf_counter()
    session = ort.InferenceSession(str(args.model / "model.int8.onnx"), options, providers=["CPUExecutionProvider"])
    load_ms = (time.perf_counter() - start) * 1000
    metadata = session.get_modelmeta().custom_metadata_map
    mean = np.asarray(metadata["neg_mean"].split(","), dtype=np.float32)
    inverse_std = np.asarray(metadata["inv_stddev"].split(","), dtype=np.float32)
    tokens = [line.rsplit(" ", 1)[0] for line in (args.model / "tokens.txt").read_text(encoding="utf-8").splitlines()]
    rows = []
    for index, path in enumerate(paths):
        bank = np.fromfile(str(path) + ".fbank", dtype="<f4").reshape(-1, 80)
        stack = np.concatenate([bank[i:i + 7].reshape(1, 560) for i in range(0, len(bank) - 6, 6)])
        stack = (stack + mean) * inverse_std
        start = time.perf_counter()
        logits, count = session.run(None, {"speech": stack[None], "speech_lengths": np.asarray([len(stack)], dtype=np.int32)})
        inference_ms = (time.perf_counter() - start) * 1000
        words = [tokens[i] for i in logits[0, :int(count[0])].argmax(axis=-1)]
        text = decode_words(words)
        wanted = normalized(expected[index]); actual = normalized(text)
        with wave.open(str(path)) as wav:
            duration = wav.getnframes() / wav.getframerate()
        rows.append({"fixture": path.name, "expected": expected[index], "text": text,
                     "exact_normalized": wanted == actual, "character_errors": distance(wanted, actual),
                     "reference_characters": len(wanted), "duration_ms": duration * 1000,
                     "kotlin_frontend_ms": feature_ms[path.name], "onnx_inference_ms": inference_ms})
    stopped.set(); monitor.join()
    report = {"platform": "Windows desktop CPU; not Android", "onnxruntime": ort.__version__,
              "android_runtime_version": "1.22.0; device evidence separate", "threads": 2,
              "load_ms": load_ms, "baseline_rss_bytes": baseline, "peak_rss_bytes": peak[0],
              "rss_scope": "Python ONNX host process; Kotlin frontend measured in a separate JVM",
              "model_bytes": (args.model / "model.int8.onnx").stat().st_size,
              "exact_phrases": sum(row["exact_normalized"] for row in rows), "phrases": len(rows),
              "cer": sum(row["character_errors"] for row in rows) / sum(row["reference_characters"] for row in rows),
              "rows": rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
