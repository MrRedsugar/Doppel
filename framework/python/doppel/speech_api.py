"""Bounded complete-file ASR; no microphone stream, task, or audio retention."""

import asyncio
import base64
from collections import deque
from contextlib import contextmanager
from dataclasses import dataclass
import io
import json
import math
import secrets
import struct
import threading
import time
from typing import Literal
import wave

import httpx
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from starlette.requests import ClientDisconnect

from . import model_proxy
from .models import utc_now
from .providers import PROVIDERS


MODEL = "mimo-v2.5-asr"
MAX_AUDIO_SECONDS = 30
MAX_WAV_BYTES = 1_000_000
MAX_RESPONSE_BYTES = 131_072
EQUIVALENT_TOKENS_PER_SECOND = 100


@dataclass(frozen=True)
class SpeechOptions:
    upload_timeout: float = 10
    provider_timeout: float = 25


class SpeechLimiter:
    """One gateway process owns this budget; replicas need a shared limiter."""

    def __init__(self, *, global_rpm=60, owner_rpm=24, clock=time.monotonic):
        self.global_rpm, self.owner_rpm, self.clock = global_rpm, owner_rpm, clock
        self.lock = threading.Lock()
        self.recent = deque()
        self.active = {}

    @contextmanager
    def admit(self, owner, final):
        with self.lock:
            now = self.clock()
            while self.recent and self.recent[0][0] <= now - 60:
                self.recent.popleft()
            total = sum(state[0] for state in self.active.values())
            current, previews = self.active.get(owner, (0, 0))
            if (total >= 4 or current >= 2 or not final and (total >= 3 or previews >= 1) or
                    len(self.recent) >= self.global_rpm or sum(item[1] == owner for item in self.recent) >= self.owner_rpm):
                raise HTTPException(429, "Speech service is busy; no request was sent", headers={"Retry-After": "3"})
            self.recent.append((now, owner))
            self.active[owner] = (current + 1, previews + int(not final))
        try:
            yield
        finally:
            with self.lock:
                current, previews = self.active[owner]
                if current == 1:
                    del self.active[owner]
                else:
                    self.active[owner] = (current - 1, previews - int(not final))


async def read_audio(request, timeout):
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "audio/wav":
        raise HTTPException(415, "Speech input must be audio/wav")
    if request.headers.get("content-encoding", "identity").lower() != "identity":
        raise HTTPException(415, "Compressed HTTP speech bodies are unsupported")
    declared = request.headers.get("content-length")
    if declared is not None:
        if not declared.isascii() or not declared.isdigit():
            raise HTTPException(400, "Invalid audio content length")
        if len(declared) > 10 or int(declared) > MAX_WAV_BYTES:
            raise HTTPException(413, "Speech upload is too large")
    parts, size = [], 0
    try:
        async with asyncio.timeout(timeout):
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_WAV_BYTES:
                    raise HTTPException(413, "Speech upload is too large")
                parts.append(chunk)
    except TimeoutError:
        raise HTTPException(408, "Speech upload timed out") from None
    except ClientDisconnect:
        raise HTTPException(400, "Speech upload was interrupted") from None
    return b"".join(parts)


def wav_duration_ms(data):
    try:
        if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE" or struct.unpack_from("<I", data, 4)[0] != len(data) - 8:
            raise ValueError
        # Duplicate data/format chunks can make local and provider duration disagree.
        offset, seen = 12, set()
        while offset < len(data):
            name, size = struct.unpack_from("<4sI", data, offset)
            end = offset + 8 + size
            if end > len(data) or name in {b"fmt ", b"data"} and name in seen:
                raise ValueError
            if name == b"fmt " and (size < 16 or struct.unpack_from("<HHIIHH", data, offset + 8) != (1, 1, 16000, 32000, 2, 16)):
                raise ValueError
            seen.add(name)
            offset = end + size % 2
        if offset != len(data) or not {b"fmt ", b"data"}.issubset(seen):
            raise ValueError
        with wave.open(io.BytesIO(data), "rb") as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, 16000, "NONE"):
                raise ValueError
            frames = audio.getnframes()
            if not 0 < frames <= MAX_AUDIO_SECONDS * 16000 or len(audio.readframes(frames + 1)) != frames * 2:
                raise ValueError
        return (frames * 1000 + 15999) // 16000
    except (ValueError, wave.Error, EOFError, struct.error):
        raise HTTPException(422, "Speech requires a complete nonempty PCM16 mono 16 kHz WAV of at most 30 seconds") from None


class SpeechService:
    def __init__(self, runtime, *, options=None, limiter=None):
        self.runtime = runtime
        self.options = options or SpeechOptions()
        self.limiter = limiter or SpeechLimiter()
        with runtime.store.transaction() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS speech_calls(
                call_id TEXT PRIMARY KEY, owner TEXT NOT NULL, model TEXT NOT NULL,
                duration_ms INTEGER NOT NULL, final INTEGER NOT NULL,
                status TEXT NOT NULL, reserved_equivalent_tokens INTEGER NOT NULL,
                equivalent_tokens INTEGER, provider_seconds REAL,
                created_at TEXT NOT NULL, finished_at TEXT)""")

    def reserve(self, owner, duration_ms, final):
        call_id = "speech-v1:" + secrets.token_hex(20)
        estimate = math.ceil(duration_ms / 1000) * EQUIVALENT_TOKENS_PER_SECOND
        billing = self.runtime.billing
        if billing:
            try:
                billing.reserve(owner, call_id, estimate, 0)
            except RuntimeError as error:
                raise HTTPException(getattr(error, "status_code", 402), "Insufficient speech quota") from None
        try:
            with self.runtime.store.transaction() as db:
                db.execute("INSERT INTO speech_calls(call_id,owner,model,duration_ms,final,status,reserved_equivalent_tokens,created_at) VALUES(?,?,?,?,?,'reserved',?,?)",
                           (call_id, owner, MODEL, duration_ms, int(final), estimate, utc_now()))
        except BaseException:
            if billing:
                billing.release(owner, call_id)
            raise
        return call_id

    def finish(self, owner, call_id, status, *, seconds=None, equivalent=None):
        billing = self.runtime.billing
        if billing:
            if status == "settled":
                billing.settle(owner, call_id, equivalent, 0)
            elif status == "released":
                billing.release(owner, call_id)
        with self.runtime.store.transaction() as db:
            db.execute("UPDATE speech_calls SET status=?,equivalent_tokens=?,provider_seconds=?,finished_at=? WHERE call_id=? AND owner=?",
                       (status, equivalent, seconds, utc_now(), call_id, owner))

    async def provider_response(self, payload, key):
        transport = getattr(self.runtime, "speech_transport", getattr(self.runtime, "upstream_transport", None))
        async with asyncio.timeout(self.options.provider_timeout):
            async with httpx.AsyncClient(timeout=httpx.Timeout(self.options.provider_timeout, connect=min(10, self.options.provider_timeout)),
                                         transport=transport, trust_env=False, follow_redirects=False) as client:
                async with client.stream("POST", PROVIDERS["xiaomi-mimo"].endpoint,
                                         headers={"Authorization": "Bearer " + key}, json=payload) as response:
                    content = bytearray()
                    async for chunk in response.aiter_bytes():
                        if len(content) + len(chunk) > MAX_RESPONSE_BYTES:
                            raise ValueError("Speech response exceeds bound")
                        content.extend(chunk)
                    try:
                        reply = json.loads(content)
                    except (ValueError, UnicodeError, RecursionError):
                        if 400 <= response.status_code < 500:
                            reply = {}
                        else:
                            raise ValueError("Speech response unavailable") from None
                    return response.status_code, reply

    async def transcribe(self, owner, request, final):
        started = time.monotonic()
        with self.limiter.admit(owner, final):
            data = await read_audio(request, self.options.upload_timeout)
            duration_ms = wav_duration_ms(data)
            if self.runtime.config.provider != "xiaomi-mimo":
                raise HTTPException(503, "Speech requires a configured Xiaomi MiMo provider")
            key = model_proxy.provider_key(self.runtime.config)
            payload = {"model": MODEL, "messages": [{"role": "user", "content": [
                {"type": "input_audio", "input_audio": {"data": "data:audio/wav;base64," + base64.b64encode(data).decode("ascii")}}
            ]}], "asr_options": {"language": "auto"}, "stream": False}
            call_id = self.reserve(owner, duration_ms, final)
            try:
                status, reply = await self.provider_response(payload, key)
            except asyncio.CancelledError:
                self.finish(owner, call_id, "usage_unknown")
                raise
            except (TimeoutError, httpx.TimeoutException):
                self.finish(owner, call_id, "usage_unknown")
                raise HTTPException(504, "Speech provider timed out; reserved usage requires reconciliation") from None
            except (httpx.HTTPError, ValueError):
                self.finish(owner, call_id, "usage_unknown")
                raise HTTPException(502, "Speech provider unavailable; reserved usage requires reconciliation") from None

            usage = reply.get("usage") if isinstance(reply, dict) else None
            seconds = usage.get("seconds") if isinstance(usage, dict) else None
            valid_usage = type(seconds) in (float, int) and 0 <= seconds <= MAX_AUDIO_SECONDS + 1 and math.isfinite(seconds)
            if valid_usage:
                equivalent = math.ceil(max(duration_ms / 1000, seconds) * EQUIVALENT_TOKENS_PER_SECOND)
                self.finish(owner, call_id, "settled", seconds=seconds, equivalent=equivalent)
            elif 400 <= status < 500:
                self.finish(owner, call_id, "released")
            else:
                self.finish(owner, call_id, "usage_unknown")
                raise HTTPException(502, "Speech provider omitted valid metering; reserved usage requires reconciliation")
            if status == 429:
                raise HTTPException(429, "Speech provider is busy; request was not retried", headers={"Retry-After": "3"})
            if not 200 <= status < 300:
                raise HTTPException(502, "Speech provider rejected the request")
            choices = reply.get("choices")
            choice = choices[0] if isinstance(choices, list) and len(choices) == 1 and isinstance(choices[0], dict) else {}
            message = choice.get("message")
            text = message.get("content") if isinstance(message, dict) else None
            if choice.get("finish_reason") != "stop" or not isinstance(text, str) or not text.strip() or len(text) > 8000:
                raise HTTPException(422, "Speech result was empty or incomplete; no command was submitted")
            return {"text": text.strip(), "duration_ms": duration_ms, "model": MODEL,
                    "elapsed_ms": max(0, round((time.monotonic() - started) * 1000))}


def create_speech_router(runtime, owner_dependency):
    router = APIRouter(prefix="/speech")
    if not hasattr(runtime, "speech"):
        runtime.speech = SpeechService(runtime)

    @router.post("/transcriptions", openapi_extra={"requestBody": {"required": True,
        "content": {"audio/wav": {"schema": {"type": "string", "format": "binary"}}}}})
    async def transcription(request: Request, owner=Depends(owner_dependency),
                            final: Literal["true", "false"] = Header("true", alias="X-Speech-Final")):
        return await runtime.speech.transcribe(owner, request, final == "true")

    return router
