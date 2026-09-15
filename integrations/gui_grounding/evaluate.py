"""Private, paired offline screenshot evaluation; stores raw output without executing it."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import statistics
import time

from PIL import Image

from inference import ModelProcess
from models import MODELS
from protocol import decode_request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=MODELS, action="append")
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.repeat <= 3:
        parser.error("repeat must be 1..3")
    corpus = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    cases = corpus if isinstance(corpus, list) else corpus["cases"]
    args.output.mkdir(parents=True, exist_ok=False)
    summary = {"manifest_sha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
               "paired": True, "zoom": False, "max_pixels": 1048576, "models": {}, "small_sample_no_generalization": True}
    for alias in args.model or MODELS:
        model = ModelProcess(alias, args.weights.resolve(), timeout=60)
        rows = []
        try:
            for repeat in range(args.repeat):
                for index, case in enumerate(cases):
                    path = Path(case["image"])
                    png = path.read_bytes()
                    with Image.open(path) as image:
                        width, height = image.size
                    digest = hashlib.sha256(png).hexdigest()
                    if digest != case["sha256"] or (width, height) != (case["width"], case["height"]):
                        raise ValueError("corpus_image_changed")
                    request = decode_request(dict(capture_id=f"offline-{index}-{repeat}", image_base64=base64.b64encode(png).decode(),
                        image_sha256=digest, width=width, height=height, target=case["target"]))
                    started = time.perf_counter()
                    result = model.infer(png=request.png, target=request.target, width=width, height=height)
                    request.image.close()
                    point = result["point"]
                    absent = case.get("expected_not_found", False)
                    if absent:
                        correct = result["reason"] == "model_not_found"
                    else:
                        left, top, right, bottom = case["expected_bbox"]
                        correct = point is not None and left <= point[0] < right and top <= point[1] < bottom
                    row = {"case_id": case.get("id", str(index)), "repeat": repeat, "image_sha256": digest,
                           "target": case["target"], "expected_not_found": absent, "expected_bbox": case.get("expected_bbox"),
                           "correct": correct, "wall_ms": (time.perf_counter() - started) * 1000, **result}
                    rows.append(row)
                    with (args.output / f"{alias}.jsonl").open("a", encoding="utf-8") as stream:
                        stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                    print(json.dumps({"model": alias, "case": index + 1, "repeat": repeat, "correct": correct,
                                      "status": result["status"], "ms": result["latency_ms"]}), flush=True)
        finally:
            model.close()
        times = [row["latency_ms"] for row in rows if not row["cold_inference"]]
        summary["models"][alias] = {**MODELS[alias], "cases": len(rows), "correct": sum(row["correct"] for row in rows),
            "invalid_outputs": sum(row["reason"] == "invalid_output" for row in rows),
            "not_found": sum(row["reason"] == "model_not_found" for row in rows),
            "load_ms": rows[0]["load_ms"], "cold_inference_ms": rows[0]["latency_ms"],
            "warm_p50_ms": statistics.median(times) if times else None,
            "warm_p95_ms": sorted(times)[min(len(times) - 1, int(len(times) * .95))] if times else None,
            "peak_allocated_bytes": max(row["peak_allocated_bytes"] for row in rows),
            "peak_reserved_bytes": max(row["peak_reserved_bytes"] for row in rows)}
        (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
