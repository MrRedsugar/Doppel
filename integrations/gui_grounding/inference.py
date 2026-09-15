"""Offline Qwen3-VL inference in a killable, single-GPU worker process."""
import io
import json
import multiprocessing as mp
import os
from pathlib import Path
import threading
import time

from models import MODELS
from protocol import parse_output
from refinement import ground

from prompts import MAI_PROMPT, OWL_PROMPT

class LocalModel:
    def __init__(self, alias, weights, max_pixels=1048576, linear_patch_projection=True):
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
        import torch
        from transformers import AutoProcessor, Qwen3VLForConditionalGeneration
        self.torch = torch
        torch.set_num_threads(4)
        if not torch.cuda.is_available():
            raise RuntimeError("cuda_required")
        self.alias = alias
        self.spec = MODELS[alias]
        folder = Path(weights) / alias
        manifest = json.loads((folder / "doppel-model-manifest.json").read_text(encoding="utf-8"))
        if manifest.get("revision") != self.spec["revision"] or manifest.get("repo") != self.spec["repo"]:
            raise RuntimeError("unreviewed_model")
        started = time.perf_counter()
        self.processor = AutoProcessor.from_pretrained(str(folder), local_files_only=True, trust_remote_code=False,
                                                       min_pixels=65536, max_pixels=max_pixels)
        self.model = Qwen3VLForConditionalGeneration.from_pretrained(str(folder), local_files_only=True,
            trust_remote_code=False, dtype=torch.bfloat16, attn_implementation="sdpa", device_map="cuda:0").eval()
        if linear_patch_projection:
            from patch_projection import LinearPatchProjection
            patch = self.model.model.visual.patch_embed
            patch.proj = LinearPatchProjection(patch.proj)
        self.projection_backend = "linear_equivalent" if linear_patch_projection else "original_conv3d"
        torch.cuda.synchronize()
        self.load_ms = (time.perf_counter() - started) * 1000
        self.max_pixels = max_pixels
        self.calls = 0

    def infer(self, png, target, width, height):
        from PIL import Image
        torch = self.torch
        with Image.open(io.BytesIO(png)) as source:
            picture = source.convert("RGB")
        # Preserve each official grounding adapter's image/text order.
        content = ([{"type": "text", "text": target + "\n"}, {"type": "image", "image": picture}]
                   if self.alias == "mai-ui-2b" else
                   [{"type": "image", "image": picture}, {"type": "text", "text": target}])
        messages = [{"role": "system", "content": MAI_PROMPT if self.alias == "mai-ui-2b" else OWL_PROMPT},
                    {"role": "user", "content": content}]
        torch.cuda.reset_peak_memory_stats()
        started = time.perf_counter()
        prompt = self.processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        inputs = self.processor(text=[prompt], images=[picture], return_tensors="pt").to("cuda:0")
        with torch.inference_mode():
            generated = self.model.generate(**inputs, max_new_tokens=256, do_sample=False, max_time=40.0,
                                            temperature=1.0, top_p=1.0, top_k=50)
        torch.cuda.synchronize()
        new_tokens = generated[:, inputs.input_ids.shape[1]:]
        raw = self.processor.batch_decode(new_tokens, skip_special_tokens=True, clean_up_tokenization_spaces=False)[0]
        elapsed = (time.perf_counter() - started) * 1000
        parsed = parse_output(self.alias, raw, width, height)
        self.calls += 1
        return {"status": "point" if parsed.point else "not_found", "point": parsed.point,
                "reason": parsed.reason, "model": self.spec["repo"], "revision": self.spec["revision"],
                "latency_ms": round(elapsed, 3), "raw_output": raw, "cold_inference": self.calls == 1,
                "load_ms": round(self.load_ms, 3), "input_tokens": inputs.input_ids.shape[1],
                "output_tokens": new_tokens.shape[1], "peak_allocated_bytes": torch.cuda.max_memory_allocated(),
                "peak_reserved_bytes": torch.cuda.max_memory_reserved(), "max_pixels": self.max_pixels,
                "projection_backend": self.projection_backend, "dtype": "bfloat16", "attention_backend": "sdpa"}


def worker(connection, alias, weights, max_pixels):
    try:
        model = LocalModel(alias, weights, max_pixels)
        connection.send({"ready": True, "load_ms": model.load_ms})
        while True:
            job = connection.recv()
            if job is None:
                return
            try:
                connection.send({"result": ground(model, **job)})
            except Exception as exc:
                connection.send({"error": type(exc).__name__})
    except Exception as exc:
        try:
            connection.send({"startup_error": type(exc).__name__, "detail": str(exc)[:2000]})
        except (BrokenPipeError, EOFError):
            pass
    finally:
        connection.close()


class BusyError(RuntimeError):
    pass


class CancelledError(RuntimeError):
    pass


class ModelProcess:
    recoverable = True
    def __init__(self, alias, weights, max_pixels=1048576, timeout=45, refine=False):
        self.lock = threading.Lock()
        self.alias = alias
        self.timeout = timeout
        self.refine = refine
        self.weights = str(weights)
        self.max_pixels = max_pixels
        self.spec = MODELS[alias]
        self.process = None
        self.connection = None
        self._start(time.monotonic()+180, lambda: False)

    def _start(self, deadline, cancelled):
        # Constructor is single-threaded; all later starts occur under infer's one-instance lock.
        context = mp.get_context("spawn")
        self.connection, child = context.Pipe()
        self.process = context.Process(target=worker, args=(child, self.alias, self.weights, self.max_pixels), daemon=True)
        self.process.start()
        child.close()
        try:
            self._wait(deadline, cancelled)
            ready = self.connection.recv()
            if not ready.get("ready"):
                raise RuntimeError(str(ready))
            self.load_ms = ready["load_ms"]
        except Exception:
            self.close()
            raise

    def _wait(self, deadline, cancelled):
        while True:
            if cancelled():
                raise CancelledError("request_cancelled")
            remaining = deadline-time.monotonic()
            if remaining <= 0:
                raise TimeoutError("inference_timeout_worker_stopped")
            if self.connection.poll(min(.05, remaining)):
                if cancelled():
                    raise CancelledError("request_cancelled")
                return

    def infer(self, png, target, width, height, cancelled=lambda: False):
        if not self.lock.acquire(blocking=False):
            raise BusyError("busy")
        deadline = time.monotonic()+self.timeout
        try:
            if cancelled():
                raise CancelledError("request_cancelled")
            if self.process is None or not self.process.is_alive():
                self.close()
                self._start(deadline, cancelled)
            if cancelled():
                raise CancelledError("request_cancelled")
            self.connection.send(dict(png=png, target=target, width=width, height=height, refine=self.refine))
            self._wait(deadline, cancelled)
            result = self.connection.recv()
            if "error" in result:
                raise RuntimeError(result["error"])
            return result["result"]
        except (CancelledError, TimeoutError, BrokenPipeError, EOFError, OSError):
            # The cancelled/timed-out request is never resent. A later request may create one new worker.
            try:
                self.close()
            finally:
                raise
        finally:
            self.lock.release()

    def close(self):
        if self.process is not None:
            if self.process.is_alive():
                self.process.terminate()
            self.process.join(timeout=1)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(timeout=1)
            if self.process.is_alive():
                raise RuntimeError("worker_stop_failed")
        if self.connection is not None:
            self.connection.close()
