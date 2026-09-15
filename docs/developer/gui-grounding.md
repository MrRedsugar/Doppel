# Local GUI grounding service

This adapter turns one PNG screenshot and one element description into one original-image pixel coordinate. It does not perform clicks or other device actions. Android owns capture freshness, privacy, action authorization, coordinate validation and execution.

## Installed models and runtime

| Alias | Official repository | Fixed revision | Coordinate denominator |
|---|---|---|---|
| `mai-ui-2b` | `Tongyi-MAI/MAI-UI-2B` | `503050934809558c8dfd2ddedaf9621fa74ac2de` | 999 |
| `gui-owl-2b` | `mPLUG/GUI-Owl-1.5-2B-Instruct` | `528ceaec795bbfbe6103bd79e03db849feadfb24` | 1000 |

The verified local runtime is Windows, Python 3.12.10, PyTorch 2.9.1+cu128, Transformers 4.57.1 and an RTX 4070 with 12 GB VRAM. Both models use BF16, CUDA device 0 and SDPA. The pilot uses at most 1,048,576 image pixels, deterministic decoding, at most 256 new tokens, one resident model and one inference at a time. The 2B variants fit the machine alongside its desktop workload; this is not a claim about the 4B or larger variants.

Install from the workspace root using the commands in [the adapter README](../../integrations/gui_grounding/README.md). Downloads are an explicit installation step. Serving uses `local_files_only=True`, offline mode and `trust_remote_code=False`; it never fetches URLs supplied by a caller. `fetch.py` accepts only the two pinned model aliases and writes a file-size/SHA-256 manifest. A complete MAI installation requires its official `chat_template.jinja`; GUI-Owl requires `chat_template.json`.

The service token is generated locally at `.tooling/gui-grounding/service-token.txt`. It must be provisioned separately to the authorized client, never included in a source file, screenshot, command output or report. Weights and private artifacts remain outside source distribution. [Third-party notices and source licenses](../../integrations/gui_grounding/THIRD_PARTY_NOTICES.md) accompany the adapters.

## Start and stop

```powershell
powershell -ExecutionPolicy Bypass -File integrations/gui_grounding/start.ps1 -Model gui-owl-2b
powershell -ExecutionPolicy Bypass -File integrations/gui_grounding/stop.ps1
```

The start command launches a hidden process and returns immediately. Its PID, selected model and log locations are written to `.artifacts/gui-grounding-service/latest.json`. A launch message alone does not mean the model is ready: check authenticated `GET http://127.0.0.1:8791/health` for HTTP 200 and `ready: true`. `worker_alive` reports whether weights are currently resident; after cancellation or a hard timeout, the next new request reloads one worker under the inference lock. Stop the existing service before switching model. A named start mutex serializes launch commands, and the endpoint binds before GPU loading, so a second launch on the same port fails before allocating another model. On Windows the server joins a Job Object with `KILL_ON_JOB_CLOSE` before spawning the worker; terminating the server also terminates its children. The guard fails closed if Windows refuses Job assignment. A real subprocess test verifies child cleanup without loading models.

The server defaults to one coarse pass followed by at most one 2x local crop refinement. Use `start.ps1 -NoRefine` (or Python `--no-refine`) to select a single pass. Health and the service state expose `refine`. Offline `evaluate.py` remains single-pass by default, preserving the historical pilot contract.

Loopback is the default. A specific private IPv4 interface may be selected only with `-BindAddress <address> -AllowLan`; wildcard, public and IPv6 bindings are rejected. There is no TLS termination in this small local service, so the default local transport is the validated configuration. Android transport setup is separate from this adapter.

## HTTP contract

Send `POST /v1/ground`, `Authorization: Bearer <local token>`, `Content-Type: application/json`, and a bounded `Content-Length`. The request has exactly these fields:

```json
{
  "capture_id": "capture-123",
  "image_base64": "<base64 of PNG bytes, without a data-URL prefix>",
  "image_sha256": "<64 lowercase hex SHA-256 characters>",
  "width": 648,
  "height": 1440,
  "target": "蓝色圆形的下班打卡按钮"
}
```

This example describes offline screenshot localization only. It grants no attendance, account or device-operation permission.

Successful localization returns HTTP 200:

```json
{
  "capture_id": "capture-123",
  "image_sha256": "<same validated hash>",
  "width": 648,
  "height": 1440,
  "status": "point",
  "x": 324,
  "y": 883,
  "model": "Tongyi-MAI/MAI-UI-2B",
  "revision": "503050934809558c8dfd2ddedaf9621fa74ac2de",
  "latency_ms": 0.0
}
```

Coordinates and latency above illustrate the schema, not a measured prediction. `x` and `y` are integer pixels in the original supplied image, with a top-left origin. Each model pass converts using `floor(normalized * passImageSize / modelDenominator)`, capped at `passImageSize - 1`. The refinement crop takes half the original width/height around the coarse point and clamps it to image bounds, then enlarges it exactly 2x. A refined point is mapped back using `cropOrigin + floor(refinedPixel / 2)`. The response still carries the original capture ID, image hash and dimensions. Bounded `refinement` metadata contains pass count, crop rectangle, coarse/refined points and the derived image hash, without screenshot bytes or target text. Explicit model refusal at either stage returns `status: "not_found"` with no executable `x` or `y`; refinement failure never falls back to the coarse point. No probability/confidence or raw generated text is returned.

Android serializes the original target and its existing `screen_context` as separate data fields inside the same HTTP `target` string. Context is marked untrusted and truncated only as needed to keep the complete serialization within 1000 characters; the target label is never silently truncated. The original action label and safety classification remain host-owned. Optional same-capture `planner_point` metadata stays local and supports displacement diagnostics; it is not sent as a coordinate hint to the GUI model.

The caller must compare capture ID, hash and dimensions against the current screenshot before using the point. A model's valid coordinate does not establish that the point is correct or that the intended action is authorized.

| HTTP status | Meaning |
|---|---|
| 400 | Invalid or changed screenshot, dimensions, JSON, fields or length |
| 401 | Missing or invalid token |
| 413 | Payload exceeds the limit |
| 415 | JSON content type required |
| 422 | Model output violates the accepted grounding schema |
| 429 | Inference or connection capacity is occupied |
| 503 | Worker is unavailable |
| 504 | Total request inference budget expired; worker was stopped and can be reloaded by a later request |

Limits are 8 MiB HTTP body, 6 MiB PNG, 4096 pixels per side, 12 million source pixels and 1–1000 target characters. Duplicate JSON fields, nonfinite numbers, URL inputs, control characters, wrong hashes and dimension mismatches are rejected. There are at most four accepted connections, a ten-second request read limit and one in-flight request. Both passes share one 45-second deadline. A hard timeout or disconnected client stops its worker; a later new request can create a fresh worker under the same single-instance lock, with loading charged to that new request's deadline. The old request is never resent, and images/results are not cached across requests. Error statuses are never converted into fabricated `not_found` answers.

## Reproduce the private pilot

```powershell
.tooling/gui-grounding/venv/Scripts/python.exe -m unittest discover -s integrations/gui_grounding/tests -v
.tooling/gui-grounding/venv/Scripts/python.exe integrations/gui_grounding/evaluate.py --manifest .artifacts/perception-execution/20260909/gui/corpus/manifest.json --weights .tooling/gui-grounding/weights --output .artifacts/perception-execution/20260909/gui/new-paired-run
```

Stop the resident service first. The output directory must be new. Each model runs the same frozen cases in a separate process, with the same pixel/token limits. JSONL stores raw outputs, timings, original pixel points, annotation hits and CUDA peak allocation/reservation locally; the summary records input-manifest SHA-256 and model revisions. No output text is executed.

The corpus contains five existing real-app screenshots and twelve targets: eleven present and one absent. It includes calculator buttons, Markor icons, WPS controls, clock navigation and two offline DingTalk targets. Annotation was performed by visual inspection before model outputs. It has no independent second annotator, the boxes are visible rectangles rather than verified Android hit regions, and four screenshots retain the existing Doppel overlay. This convenience sample measures image-only sanity checks, not production accuracy or end-to-end app task success. The private DingTalk screenshot must not be published.

### Results measured on 2026-09-09

The final run is `.artifacts/perception-execution/20260909/gui/paired-v4-official/`. Input manifest SHA-256 is `173800cbc35b456e40054b171480e6c16531db609d2c6c0f00e894622ac151dd`. Both adapters use the exact official grounding prompts retained in `prompts.py`, including the official optional infeasibility prompt for GUI-Owl. MAI preserves text-before-image order; GUI-Owl preserves its official image-before-text order. Earlier attempts with an abbreviated Owl prompt and a different message order are superseded by this run.

| Measured on this twelve-target pilot | MAI-UI-2B | GUI-Owl-1.5-2B |
|---|---:|---:|
| Correct / all targets | 11/12 | 12/12 |
| Correct / eleven present targets | 11/11 | 11/11 |
| Correct refusal / one absent target | 0/1 | 1/1 |
| Invalid outputs | 0 | 0 |
| Model load | 5.66 s | 4.87 s |
| First inference after load | 3.50 s | 2.47 s |
| Subsequent inference median | 2.37 s | 2.17 s |
| Subsequent inference observed p95 | 2.53 s | 8.21 s |
| Peak CUDA allocated memory | 4.39 GiB | 4.53 GiB |
| Peak CUDA reserved memory | 4.41 GiB | 4.84 GiB |

GUI-Owl is the default resident model because it correctly refused the absent target and had no format errors under its official prompt in this pilot. Its longer observed tail latency must be allowed for by the client. The p95 is the nearest-rank value among just eleven subsequent calls, so it is also this small sample's maximum; it is not a service latency guarantee. GPU peaks are PyTorch allocator values for the model process and exclude other desktop GPU consumers. Model load time excludes Python/Transformers import and launcher overhead.

The restarted resident service also passed real authenticated HTTP checks. On the supplied 648×1440 offline DingTalk image, it returned the button point `(322,865)` in 2.20 s and navigation point `(320,1375)` in 1.76 s; both were inside their frozen annotation boxes. The absent calculator target returned `not_found` with no coordinates in 1.45 s. Capture identity fields matched; a wrong token returned 401 and a modified image hash returned 400. Private evidence is `http-smoke-final.json` beside the paired output. No device or account actions were performed. Duplicate startup was rejected without replacing the existing state, and the actual stop/start scripts were exercised successfully.

MAI returned a valid but wrong point for the absent square-root button. Neither model's coordinate is proof that the requested element exists; caller validation and action policy remain necessary. A twelve-target result cannot establish general accuracy or superiority across apps.

### Deterministic local inference fix

The stock Qwen3-VL patch projection hit a severe Conv3d slowdown in this Windows/CUDA runtime. Instrumentation measured 68.51 seconds in `visual.patch_embed`, while all subsequent visual blocks and language-model prefill together took less than one second. Two one-token calls still took 77.04 and 69.23 seconds, ruling out a one-time warmup explanation.

`LinearPatchProjection` replaces only a strictly checked non-overlapping `Conv3d` where each input item is one 3×2×16×16 patch and its kernel and stride exactly cover that patch. This computes the same dot product through `F.linear` and shares the original weight/bias parameters; no weights are modified or copied. CPU FP32/FP64 tests cover outputs, gradients, parameters and unsupported shapes. On all 3,948 patches of a real screenshot, the GPU comparison measured 52.72 s for Conv3d versus 6.89 ms for the equivalent projection. BF16 values were numerically close under `rtol=0.016, atol=0.016`: mean absolute difference 0.000197, maximum 0.03125. This is algebraic equivalence within floating-point tolerance, not bitwise equivalence. All final paired results above use the same replacement for both models.

The official MAI template was recovered verbatim from the previously saved Hugging Face API response for the pinned commit after direct network retrieval became unavailable. The 5,292-byte template has SHA-256 `3636d0f0bd6bef02654cdffdc447b79cb2cef8ab02cc75267345946291a489e4` and also matched the official ModelScope copy byte for byte. The installer now includes `.jinja` files and rejects a missing template, including when the Hugging Face client falls back to an incomplete local directory.

### Android automatic refinement

With GUI grounding enabled, planned-mode safe visual tap/long-press proposals automatically use the configured locator before host dispatch. The planner's target label and source image are retained, while the locator supplies the actual point. Semantic node actions and local visual action segments do not incur this extra network/model call. `not_found` triggers re-observation without using the planner's guessed point; transport or provenance errors disable automatic refinement for the current run and return a structured fallback reason. All accepted points still pass the existing authorization, fresh-frame, target/path-pixel and cancellation checks.

Perception routing is separate from persistent action fingerprints: a readable editable field does not make a native screen opaque. Each new scene selects semantic or pixel perception again; an explicit screenshot request feeds the next visual decision. Private screenshots and GUI service tokens remain outside the public export.
