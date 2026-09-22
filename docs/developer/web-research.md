# Android public web research

`AndroidWebResearch(context)` supplies the same on-demand `search_web` and `read_web` tools to chat and the phone task planner. It does not make model calls. A URL in chat does not preload a document or inject its full text.

## Reused source and Android adaptation

The previous `r.jina.ai` hosted website call is replaced by local extraction. Jina Reader revision `1574bfd380d249c86c82db4dace0d9c8fe17e2b1` supplies the unmodified `src/services/markify.ts` converter. Mozilla Readability 0.6.0 selects article content, with a cleaned-body fallback. Both are bundled in `assets/third_party/jina-reader/reader-core.js`; source, pinned build dependencies, licenses, and reproducible smoke checks live in `tools/jina-reader-core/`.

The official Jina server depends on Node, Puppeteer/Chrome, native curl, and LibreOffice. These components are not Android libraries. Doppel instead uses the platform WebView for rendering in a private `:reader` service process. No Docker, Node runtime, public Jina service, or separate reader API key is needed by the APK. This is an adaptation of the extraction core, not a claim to reproduce all upstream service features.

Local and downloaded PDF/Office documents reuse `ChatDocumentText` with the existing PDFBox/POI dependencies. They are parsed on demand; originals are not sent to a document-conversion service. Scanned PDFs without a text layer are not OCRed. Search keeps the existing Apache-2.0 open-webSearch Bing/Sogou parsing adaptation; no new search engine is implemented.

## Call path

Chat: `DirectRuntime.conversationIntent -> ChatAttachmentContext.chat -> AndroidWebResearch.readPage -> AndroidPageReader -> ReaderService -> WebView -> Readability/Markify`.

Task: `SplitOutputSchema.read_web -> SplitTaskEngine local work -> DirectRuntime -> AndroidWebResearch.readPage`; the result returns to `acceptLocal` under the original task generation guard. Delayed results cannot resume a paused/cancelled task. Website screenshots are separately labelled external reference images before the current device screenshot.

The service is non-exported, runs a separate WebView profile, and exposes only a bounded public-HTTP bridge for page requests, with no device, file, settings or credential methods. Public page code has no API to invoke device controls, access model keys, or read the password vault. Source website content remains untrusted reference material.

## Tool contract

Run from a worker thread:

```kotlin
val web = AndroidWebResearch(context)
val excerpt = web.readPage(JSONObject()
    .put("url", "https://example.com/")
    .put("operation", "read").put("offset", 0).put("limit", 4000)) { taskIsCurrent() }
web.cancel()
```

| Operation | Result |
| --- | --- |
| `info` | Title, source, stored character count; no body excerpt. |
| `search` | Literal text search; at most 10 snippets, with offsets. |
| `read` | UTF-16 offset/limit, at most 10,000 characters; next_offset and has_more. |
| `screenshot` | Rendered viewport PNG, at most 900 by 1200 pixels; may be downscaled to fit IPC. |

A first text request loads/extracts a page; subsequent reads reuse the same bounded snapshot. Each instance keeps four snapshots of at most 200,000 characters. Cancellation invalidates the snapshots. Nonzero offsets against an expired/evicted snapshot return snapshot_expired; the model must locate the passage again instead of silently applying an old offset to new content. `read(url)` remains the fresh-first-excerpt convenience API; no-context construction supports search only.

Text references share the existing 20,000-character serialized context budget. Chat shares 12 tool calls across attachments/web/search. The task's existing progress/call budgets remain in force. Tools report truncation and unavailable content, rather than representing a partial read as complete.

Screenshot data travels only in the next multimodal model request. It is removed from result JSON before history, diagnostics or task persistence; the context explicitly says it is website reference material, not the current phone screen. Original imported image attachments keep their existing behavior.

## Network and lifecycle limits

All page/resource connections pass through the existing public-HTTPS validation and the actual OkHttp DNS resolver. Local/reserved destinations, private DNS answers, credentials in URLs and unsafe redirects are rejected. There are no browser cookies, authenticators, environment proxies or automatic redirects. Subresources use the same policy. WebView native network loads are disabled; an interceptor performs validated GETs for page assets. The upstream MSW fetch/XHR interceptors forward scripted GET/HEAD/POST/OPTIONS through the same bounded public-network transport; request bodies are capped at 128 KiB, and cookie/authentication headers are removed. This supports anonymous JSON POST APIs used by dynamic document websites without implementing XMLHttpRequest ourselves. CSP blocks forms, frames, workers and socket channels; there is no interactive browsing or login session.

Each operation has a 28-second deadline, per-response 5 MiB limit, per-page 24 MiB/180-resource limit and bounded redirects. The service admits at most two simultaneous readers for independent chat/task calls. Client cancellation unbinds its request and stops its WebView/network work. Navigation generations prevent an old load replacing a newer page. Renderer death produces a recoverable tool failure.

The cumulative page byte budget is consumed during response streaming, so an exhausted budget also stops later requests. Safe GET/HEAD/OPTIONS requests use OkHttp's existing connection recovery to try remaining validated DNS addresses after a connection failure. POST retries stay disabled. Recovery retains the original operation deadline; no custom retry loop or alternate unvalidated DNS path is used.

Each transport releases its private idle connection pool after the response closes. Otherwise a resource-heavy page leaves a separate five-minute keep-alive pool per resource. Debug builds can log host, connection event, exception type and elapsed time for diagnosis; they do not log paths, queries, headers or bodies.

Limits: this is public, read-only page viewing. Websites requiring authenticated sessions, synchronous POST XHR, embedded frames, persistent cookies, or complex browser features can return partial/no content. MSW browser interceptors require a current WebView with Promise.withResolvers/URL.canParse support. Failed subresources are reported in the Markdown warning. Viewport screenshots are not whole-page coverage. Page extraction/formatting does not imply that a requested device task was performed. Compatibility requires actual-device page evidence; build or mock test success alone is insufficient.
