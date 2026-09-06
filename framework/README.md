# Doppel Framework

Python gateway for controlled Android task execution, stable observation targets,
host-enforced approvals, remote MCP extensions and bounded workbook operations.
This package runs independently of the private Doppel product service.

```sh
python -m pip install -e '.[test]'
doppel init --data-dir .local/developer
doppel serve --data-dir .local/developer --host 127.0.0.1 --port 8765
```

Python 3.12+ is required. The generated developer-token.txt is private. Configure
the same bearer token in the Android developer app and use a reachable gateway
address. An API key is optional for offline protocol tests; actual model tasks
require --api-key-file pointing to a private DeepSeek key file. No account,
SMS, purchased credit service or product module is required.

The developer gateway is single-owner and intended for a trusted development
machine/network. It defaults to loopback; use --host 0.0.0.0 only on an authorized
network. See docs/developer/standalone.md in the public source repository for
installation, testing, network setup and limitations.

Original source: Apache-2.0. See LICENSE, NOTICE and the repository's
THIRD_PARTY_NOTICES.md. The installed Harness SDK/runtime has separate MIT and
third-party licenses; preserve its distributed notices.
