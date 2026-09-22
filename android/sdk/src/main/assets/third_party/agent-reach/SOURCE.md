# Agent Reach website channel

- Upstream: https://github.com/Panniantong/Agent-Reach
- Commit: `a19a171fa980a0785849596492e0af4db800c82f`
- Version at this commit: `1.5.0`
- License: MIT, copyright (c) 2025 Agent Eyes; see `LICENSE`.
- Referenced source: `agent_reach/channels/web.py` and `agent_reach/utils/url.py`.

The upstream website channel sends a public URL to `https://r.jina.ai/<URL>` and receives
Markdown from the hosted Jina Reader service. Agent Reach is not an embedded
browser or a local website extraction engine. Its website `check()` reports
configuration readiness without making a network request; it is not a reachability
or success guarantee.

Doppel originally adapted this remote channel; the remaining Markdown envelope
handling is retained in JinaReaderChannel.kt. The current Android app replaces
the remote call with the Jina extraction core running in a local WebView.
Public URL restrictions, cancellation and bounded results remain enforced. The social/video command-line installers,
browser-cookie tools, and unrelated channel implementations are not included.
All returned website content remains untrusted reference data.
