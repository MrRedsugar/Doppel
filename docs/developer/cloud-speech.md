# Cloud Speech API

The developer and product gateways expose `POST /v1/speech/transcriptions`.
It transcribes one complete recording without creating or executing a task.
The existing gateway owner Bearer authentication is required. Provider keys
stay on the server. The host must use the `xiaomi-mimo` provider and its
configured pay-as-you-go credential; this route selects `mimo-v2.5-asr`.

## Request and Result

- Body: complete RIFF/WAVE file, linear PCM format 1, mono, 16 kHz, 16 bits,
  byte rate 32000, block alignment 2. Audio must be nonempty and at most
  30 seconds. The entire HTTP body is limited to 1,000,000 actual bytes.
- `Content-Type: audio/wav`. Compressed HTTP request bodies are rejected.
- Optional `X-Speech-Final: true` or `false`, default `true`. Send `false` for
  a recording preview and `true` for the completed recording after release.
- No multipart wrapper, task ID, provider parameters, client-supplied usage,
  or provider key is accepted in the body.

Success returns only:

```json
{"text":"Recognized instruction","duration_ms":2800,"model":"mimo-v2.5-asr","elapsed_ms":1200}
```

`duration_ms` is the validated local audio duration rounded up to milliseconds.
`elapsed_ms` measures this gateway request, including upload; it is not a
latency guarantee. Empty, truncated, filtered, or malformed recognition does
not return an executable transcript. Clients must not submit a preview as a
final command unless it covers exactly the complete recording.

## Limits and Cancellation

The upload has a 10-second total deadline. The provider request has a
25-second total deadline and a bounded response. The gateway never retries a
paid request automatically and does not forward upstream error details.

The current single gateway process allows four concurrent requests, with two
per authenticated owner. An owner can have at most one preview in flight.
New previews are rejected when three requests are active, reserving the fourth
slot for a final request. Both kinds share 60 requests per minute globally and
24 per owner. Limits return HTTP 429 and `Retry-After: 3`; clients should
coalesce previews and must not automatically duplicate a final request.
Multiple replicas or gateways sharing an upstream account need a shared
limiter or explicitly divided account budget before production scaling.

HTTP 401 indicates missing/invalid gateway authentication; 402 insufficient
points; 408 upload timeout; 413 excess upload size; 415 unsupported encoding;
422 invalid audio or incomplete recognition; 503 missing provider setup;
502/504 unavailable provider results or uncertain consumption. A 429 can also
represent a sanitized upstream rate rejection.

Stopping client waiting does not prove that an already accepted provider
request was cancelled. Uvicorn may continue its handler after client
disconnection. Cancelled and obsolete snapshots can still incur usage; late
results must be discarded by the client's recording-generation guard.

## Audio Accounting

Each snapshot is a separate bill, including repeated prefixes and failed
recognition. Accounting version `speech-v1` maps one audio second to 100 token
equivalents, using the product's existing points conversion and minimum
one-point-per-call rule. At 300 token equivalents per point, one 30-second
request costs 10 points. These are application quota units, not the provider's
text-token count or monetary ASR price.

Before calling the provider, the gateway reserves
`ceil(local_audio_seconds) * 100` equivalents. A valid provider `usage.seconds`
receipt settles `ceil(max(local_audio_seconds, usage.seconds) * 100)`
equivalents. The local duration floor prevents incorrectly zero or partial
metering from creating free usage. Normal provider 4xx rejections without
metering release the reservation. Transport failures, cancellation, 5xx with
unknown usage, and missing/invalid metering keep the reservation for manual
reconciliation. A metered failed recognition is charged.

Product ledger rows identify `usage_kind: audio_seconds_equivalent` and
`equivalent_tokens_per_second: 100`. Developer unlimited mode still records
receipts and does not disable upload, concurrency, or rate limits.

The runtime's `speech_calls` table stores call ID, owner, model, final/preview,
validated duration, reservation, provider seconds, equivalent usage, status,
and timestamps. It stores no audio, transcript, key, or raw provider response.
Unknown receipts must be reconciled with the provider bill; they must not be
released automatically merely because a process restarted.

## Testing and Embedding

`runtime.speech_transport` can inject an `httpx.AsyncBaseTransport`; the route
falls back to the existing `runtime.upstream_transport`. `SpeechOptions`
controls total deadlines, and `SpeechLimiter` accepts an injectable monotonic
clock for deterministic scheduling tests. Production transport uses a fixed
audited HTTPS endpoint, with redirects and environment proxies disabled.

Contract and billing tests use synthetic WAV files, injected HTTP transports,
temporary databases, and the real gateway/product handlers. They do not
require a provider key or make external requests.
