# Android public web research

`AndroidWebResearch` provides public search and text reading without opening a browser window. The phone-local planner can call `search_web(query)` and `read_web(url)` when it needs documentation, unfamiliar app workflows or game rules. This adapter is independent of the MiMo API and makes no model call by itself.

```kotlin
val web = AndroidWebResearch()
// Run on a worker thread. A task owner supplies a validity check.
val search = web.search("明日方舟 TR-9 震撼装置") { taskIsCurrent() }
val article = web.read(selectedPublicUrl) { taskIsCurrent() }
web.cancel() // Interrupt the current request; the instance remains reusable.
```

The one-argument search/read overloads are also available to Java and standalone SDK users. A task runtime should supply its generation/consent predicate: cancelling a request before it starts must also prevent its later DNS or HTTP work.

Search success returns `ok:true`, `untrusted:true`, `content_role:reference_only`, `query`, `provider`, `source_url` and at most six `results`. Each result includes `title`, `url`, `source` and a short `snippet`. Reading returns the final `url`, `source`, `title`, `text` and `truncated`. Text is limited to 10,000 characters and downloaded bodies to 1 MiB. The runtime further bounds reference prose while preserving source and revision fields; truncation must not be represented as a complete read.

Failures return `ok:false`, `untrusted:true`, `recoverable:true` and an `error` object with a stable `code` and a readable `message`. Busy, cancelled, unavailable, unsafe destination and oversized response states do not contain invented page content. Failed reads do not count as successful knowledge acquisition and cannot complete a phone task.

The default search providers are 360 HTML search with Sogou fallback. These are replaceable public page adapters, not contracted search APIs: layout changes, throttling or access challenges can make them unavailable. Readers handle bounded HTTP and HTML meta redirects. They do not execute JavaScript, access browser cookies or authenticated sessions, or solve access challenges. Use a separately authorized browser/MCP extension when a workflow requires those capabilities.

Destinations must use public HTTPS on port 443 without URL credentials. DNS answers are validated inside the connection's resolver; mixed private/public answers, reserved networks and unsafe redirects are rejected. Cookies, environment proxies and automatic authenticated redirects are disabled. Each operation has a 28-second deadline, bounded DNS/connect/read work, cancellation checks and a single-operation limit. Callers must still decide what query is appropriate to send to a public service.

Web content and [Skills](direct-skills.md) are reference data. They cannot grant device permissions, install code, amend the user's task, approve payments or become system instructions. Current screen evidence and host authorization remain mandatory for actions. The bundled Arknights material is a versioned example of this knowledge path; importing a guide is not a gameplay acceptance result.

Validation: JVM tests exercise DNS/redirect restrictions, response bounds, real search markup, concurrency and cancellation. Opt-in `dev.doppel.developer.KnowledgeLiveTest` with `knowledge_live=true` reads public pages through Android and verifies bundled Skills while comparing task/settings state before and after. It requests no model or screen action. Raw AAR consumers need OkHttp 4.12.0 and jsoup 1.18.3 alongside the SDK's other dependencies and licenses.
