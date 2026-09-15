import asyncio
import base64
import io
import json
import struct
import wave
from dataclasses import replace

import httpx
import pytest
from fastapi import FastAPI, Header, HTTPException
from fastapi.testclient import TestClient

from doppel import DoppelRuntime, RuntimeConfig, create_router


def wav(seconds=1, *, channels=1, rate=16000, width=2):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as audio:
        audio.setnchannels(channels)
        audio.setsampwidth(width)
        audio.setframerate(rate)
        audio.writeframes(b"\0" * int(seconds * rate * channels * width))
    return buffer.getvalue()


def reply(*, text="打开测试应用", seconds=1, finish="stop"):
    return {"choices": [{"message": {"content": text}, "finish_reason": finish}], "usage": {"seconds": seconds}}


def duplicate_data_wav():
    audio = bytearray(wav())
    audio.extend(b"data" + struct.pack("<I", 32000) + b"\0" * 32000)
    struct.pack_into("<I", audio, 4, len(audio) - 8)
    return bytes(audio)


def malformed_format(offset, value, format):
    audio = bytearray(wav())
    struct.pack_into(format, audio, offset, value)
    return bytes(audio)


@pytest.fixture
def setup(tmp_path, monkeypatch):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    monkeypatch.setattr("doppel.model_proxy.provider_key", lambda config: "synthetic-provider-key")
    calls = []

    def upstream(request):
        calls.append(request)
        return httpx.Response(200, json=reply())

    runtime.upstream_transport = httpx.MockTransport(upstream)
    app = FastAPI()

    def owner(authorization: str | None = Header(None)):
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "Bearer required")
        return authorization[7:]

    app.include_router(create_router(runtime, owner))
    yield runtime, app, calls
    runtime.store.db.close()


def headers(owner="alice", final="true", **extra):
    return {"Authorization": "Bearer " + owner, "Content-Type": "audio/wav", "X-Speech-Final": final, **extra}


def test_complete_wav_uses_only_official_asr_schema_and_keeps_audio_out_of_receipts(setup):
    runtime, app, calls = setup
    data = wav()
    with TestClient(app) as client:
        response = client.post("/v1/speech/transcriptions", content=data, headers=headers())
    assert response.status_code == 200, response.text
    result = response.json()
    assert set(result) == {"text", "duration_ms", "model", "elapsed_ms"}
    assert result["text"] == "打开测试应用" and result["duration_ms"] == 1000
    assert result["model"] == "mimo-v2.5-asr" and result["elapsed_ms"] >= 0
    assert len(calls) == 1 and str(calls[0].url) == "https://api.xiaomimimo.com/v1/chat/completions"
    payload = json.loads(calls[0].content)
    assert payload == {"model": "mimo-v2.5-asr", "messages": [{"role": "user", "content": [
        {"type": "input_audio", "input_audio": {"data": "data:audio/wav;base64," + base64.b64encode(data).decode()}}
    ]}], "asr_options": {"language": "auto"}, "stream": False}
    records = [dict(row) for row in runtime.store.all("SELECT * FROM speech_calls")]
    assert len(records) == 1 and records[0]["status"] == "settled"
    assert records[0]["duration_ms"] == 1000 and records[0]["equivalent_tokens"] == 100
    assert result["text"] not in str(records) and "synthetic-provider-key" not in str(records)
    assert not runtime.store.all("SELECT id FROM runs")


def test_authentication_precedes_audio_processing(setup):
    _, app, calls = setup
    with TestClient(app) as client:
        response = client.post("/v1/speech/transcriptions", content=wav(), headers={"Content-Type": "audio/wav"})
    assert response.status_code == 401 and not calls


@pytest.mark.parametrize("data,extra,status", [
    (b"raw-pcm", {}, 422),
    (wav(channels=2), {}, 422),
    (wav(rate=8000), {}, 422),
    (wav(width=1), {}, 422),
    (wav(seconds=0), {}, 422),
    (wav(seconds=30.01), {}, 422),
    (wav()[:-2], {}, 422),
    (wav() + b"tail", {}, 422),
    (duplicate_data_wav(), {}, 422),
    (malformed_format(28, 1, "<I"), {}, 422),
    (malformed_format(32, 1, "<H"), {}, 422),
    (wav(), {"Content-Type": "audio/mpeg"}, 415),
    (wav(), {"Content-Encoding": "gzip"}, 415),
    (wav(), {"X-Speech-Final": "sometimes"}, 422),
], ids=["raw", "stereo", "sample-rate", "bit-depth", "empty", "duration", "truncated", "trailing", "duplicate-data", "byte-rate", "block-align", "mime", "encoding", "final-header"])
def test_invalid_audio_never_reaches_provider(setup, data, extra, status):
    _, app, calls = setup
    with TestClient(app) as client:
        response = client.post("/v1/speech/transcriptions", content=data, headers=headers(**extra))
    assert response.status_code == status, response.text
    assert not calls


async def test_actual_streamed_body_limit_does_not_trust_content_length(setup):
    _, app, calls = setup

    async def oversized():
        for _ in range(17):
            yield b"x" * 65536

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        response = await client.post("/v1/speech/transcriptions", content=oversized(), headers=headers(**{"Content-Length": "1"}))
    assert response.status_code == 413 and not calls


async def test_slow_upload_has_total_deadline_and_releases_concurrency(setup):
    runtime, app, calls = setup
    runtime.speech.options = replace(runtime.speech.options, upload_timeout=0.02)

    async def stalled():
        yield wav()[:10]
        await asyncio.sleep(0.2)
        yield wav()[10:]

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        response = await client.post("/v1/speech/transcriptions", content=stalled(), headers=headers())
        assert response.status_code == 408 and not calls
        valid = await client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
        assert valid.status_code == 200


@pytest.mark.parametrize("status", [400, 401, 403, 429, 500, 503])
def test_upstream_failures_are_sanitized_and_never_retried(setup, status):
    runtime, app, calls = setup

    def rejected(request):
        calls.append(request)
        return httpx.Response(status, json={"error": "private upstream secret and audio"})

    runtime.upstream_transport = httpx.MockTransport(rejected)
    with TestClient(app) as client:
        result = client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
    assert result.status_code == (429 if status == 429 else 502)
    assert "private upstream" not in result.text and len(calls) == 1
    receipt = runtime.store.one("SELECT status FROM speech_calls")
    assert receipt["status"] == ("released" if status < 500 else "usage_unknown")


@pytest.mark.parametrize("seconds", [None, True, "1", -1, 1000])
def test_invalid_metering_retains_estimate_without_returning_transcript(setup, seconds):
    runtime, app, _ = setup
    runtime.upstream_transport = httpx.MockTransport(lambda request: httpx.Response(200, json=reply(seconds=seconds)))
    with TestClient(app) as client:
        result = client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
    assert result.status_code == 502
    assert "打开测试应用" not in result.text
    assert runtime.store.one("SELECT status FROM speech_calls")["status"] == "usage_unknown"


@pytest.mark.parametrize("seconds", [float("nan"), float("inf"), 10 ** 320], ids=["nan", "infinity", "huge-int"])
def test_nonfinite_or_huge_provider_metering_is_sanitized(setup, seconds):
    runtime, app, _ = setup
    runtime.upstream_transport = httpx.MockTransport(lambda request: httpx.Response(200, content=json.dumps(reply(seconds=seconds))))
    with TestClient(app) as client:
        result = client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
    assert result.status_code == 502
    assert runtime.store.one("SELECT status FROM speech_calls")["status"] == "usage_unknown"


@pytest.mark.parametrize("body", [b"{" * 140000, b"[" * 5000 + b"]" * 5000], ids=["oversized", "too-deep"])
def test_oversized_or_deep_provider_response_is_sanitized(setup, body):
    runtime, app, calls = setup

    def invalid(request):
        calls.append(request)
        return httpx.Response(200, content=body)

    runtime.upstream_transport = httpx.MockTransport(invalid)
    with TestClient(app) as client:
        result = client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
    assert result.status_code == 502 and len(calls) == 1
    assert runtime.store.one("SELECT status FROM speech_calls")["status"] == "usage_unknown"


async def test_cancelled_request_retains_unknown_receipt_and_frees_final_slot(setup):
    runtime, app, calls = setup
    entered = asyncio.Event()

    async def cancelled(request):
        calls.append(request)
        entered.set()
        await asyncio.sleep(10)
        return httpx.Response(200, json=reply())

    runtime.upstream_transport = httpx.MockTransport(cancelled)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        task = asyncio.create_task(client.post("/v1/speech/transcriptions", content=wav(), headers=headers(final="false")))
        await asyncio.wait_for(entered.wait(), 2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert len(calls) == 1 and not runtime.speech.limiter.active
        assert runtime.store.one("SELECT status FROM speech_calls")["status"] == "usage_unknown"
        runtime.upstream_transport = httpx.MockTransport(lambda request: httpx.Response(200, json=reply()))
        final = await client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
        assert final.status_code == 200


@pytest.mark.parametrize("finish,text", [("length", "incomplete"), ("content_filter", "filtered"), ("stop", "  ")])
def test_incomplete_or_empty_transcription_is_not_a_command_but_usage_is_recorded(setup, finish, text):
    runtime, app, _ = setup
    runtime.upstream_transport = httpx.MockTransport(lambda request: httpx.Response(200, json=reply(text=text, finish=finish)))
    with TestClient(app) as client:
        result = client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
    assert result.status_code == 422
    assert runtime.store.one("SELECT status FROM speech_calls")["status"] == "settled"


async def test_provider_total_timeout_preserves_reservation_and_does_not_retry(setup):
    runtime, app, calls = setup
    runtime.speech.options = replace(runtime.speech.options, provider_timeout=0.02)

    async def stalled(request):
        calls.append(request)
        await asyncio.sleep(0.2)
        return httpx.Response(200, json=reply())

    runtime.upstream_transport = httpx.MockTransport(stalled)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        result = await client.post("/v1/speech/transcriptions", content=wav(), headers=headers())
    assert result.status_code == 504 and len(calls) == 1
    assert runtime.store.one("SELECT status FROM speech_calls")["status"] == "usage_unknown"


async def test_final_has_reserved_global_slot_and_can_overlap_own_preview(setup):
    runtime, app, calls = setup
    release = asyncio.Event()

    async def held(request):
        calls.append(request)
        await release.wait()
        return httpx.Response(200, json=reply())

    runtime.upstream_transport = httpx.MockTransport(held)
    tasks = []
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        async def begin(owner, final, count):
            task = asyncio.create_task(client.post("/v1/speech/transcriptions", content=wav(), headers=headers(owner, final)))
            tasks.append(task)
            async with asyncio.timeout(2):
                while len(calls) < count:
                    await asyncio.sleep(0.005)

        try:
            await begin("alice", "false", 1)
            duplicate_preview = await client.post("/v1/speech/transcriptions", content=wav(), headers=headers("alice", "false"))
            assert duplicate_preview.status_code == 429
            await begin("alice", "true", 2)
            await begin("bob", "false", 3)
            blocked_preview = await client.post("/v1/speech/transcriptions", content=wav(), headers=headers("carol", "false"))
            assert blocked_preview.status_code == 429
            await begin("carol", "true", 4)
            blocked_final = await client.post("/v1/speech/transcriptions", content=wav(), headers=headers("david", "true"))
            assert blocked_final.status_code == 429 and len(calls) == 4
        finally:
            release.set()
            results = await asyncio.gather(*tasks)
        assert all(result.status_code == 200 for result in results)


def test_owner_and_global_rate_windows_apply_to_all_snapshots(setup):
    from doppel.speech_api import SpeechLimiter
    runtime, app, calls = setup
    now = [0.0]
    runtime.speech.limiter = SpeechLimiter(global_rpm=3, owner_rpm=2, clock=lambda: now[0])
    with TestClient(app) as client:
        for _ in range(2):
            assert client.post("/v1/speech/transcriptions", content=wav(), headers=headers()).status_code == 200
        assert client.post("/v1/speech/transcriptions", content=wav(), headers=headers()).status_code == 429
        assert client.post("/v1/speech/transcriptions", content=wav(), headers=headers("bob")).status_code == 200
        assert client.post("/v1/speech/transcriptions", content=wav(), headers=headers("carol")).status_code == 429
        now[0] = 61
        assert client.post("/v1/speech/transcriptions", content=wav(), headers=headers("carol")).status_code == 200
    assert len(calls) == 4
