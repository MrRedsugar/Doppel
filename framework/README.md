# Doppel Framework

Python gateway for controlled Android task execution, stable observation targets,
host-enforced approvals, remote MCP extensions and bounded workbook operations.
This package runs independently of the private Doppel product service.
Skills imports and model tools have been removed; existing user data is retained.
Document tools, remote MCP extensions and long-term memory remain available.

```sh
python -m pip install -e '.[test]'
doppel init --data-dir .local/developer
doppel serve --data-dir .local/developer --host 127.0.0.1 --port 8765
```

Python 3.12+ is required. The generated developer-token.txt is private. Configure
the same bearer token in the Android developer app and use a reachable gateway
address. An API key is optional for offline protocol tests; actual model tasks
require `--api-key-file /private/mimo-key.txt`. The default provider is
`xiaomi-mimo`: `mimo-v2.5-pro` makes primary text decisions and `mimo-v2.5`
handles auxiliary screenshots. Override with `--provider`, `--model` and
`--vision-model`; explicit `--provider deepseek` retains the original DeepSeek
main/vision defaults and requires its matching key. Keys stay in the gateway.
Legacy MiMo/DeepSeek thinking is disabled and output is capped at 1600 tokens per call. MiMo uses
automatic tool selection; task completion still requires server-validated
evidence. No account, SMS, purchased credit service or product module is required.

For Qwen, explicitly select `--provider qwen --api-key-file /private/qwen-key.txt`.
The defaults are `qwen3.8-flash` with thinking enabled at low effort and
`qwen3.8-max` with thinking disabled; both accept images. Use `qwen-intl` for
the international DashScope endpoint. An independent vision platform uses
`--vision-provider`, `--vision-api-key-file`, and optionally `--vision-endpoint`
for `custom`. `--no-vision-enhancement` follows the primary visual model.
Custom providers require an explicit model and HTTPS endpoint and default to
following the primary model. Credentials never change destinations based on
model output. See `docs/developer/model-connections.md` for the full configuration
and Android capability-verification contract.

The developer gateway is single-owner and intended for a trusted development
machine/network. It defaults to loopback; use --host 0.0.0.0 only on an authorized
network. See docs/developer/standalone.md in the public source repository for
installation, testing, network setup and limitations.

Original source: Apache-2.0. See LICENSE, NOTICE and the repository's
THIRD_PARTY_NOTICES.md. The installed Harness SDK/runtime has separate MIT and
third-party licenses; preserve its distributed notices.
