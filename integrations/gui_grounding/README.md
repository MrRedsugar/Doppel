# Local GUI grounding

Official fixed-revision MAI-UI-2B and GUI-Owl-1.5-2B screenshot-to-point adapters.
The service never executes model output. All images stay local; the installed
models run offline. See [setup, HTTP contract and limitations](../../docs/developer/gui-grounding.md).

```powershell
uv venv .tooling/gui-grounding/venv --python 3.12
uv pip install --python .tooling/gui-grounding/venv/Scripts/python.exe torch==2.9.1 torchvision==0.24.1 --index-url https://download.pytorch.org/whl/cu128
uv pip install --python .tooling/gui-grounding/venv/Scripts/python.exe -r integrations/gui_grounding/requirements.txt
.tooling/gui-grounding/venv/Scripts/python.exe integrations/gui_grounding/fetch.py --weights .tooling/gui-grounding/weights
.tooling/gui-grounding/venv/Scripts/python.exe -m unittest discover -s integrations/gui_grounding/tests
powershell -ExecutionPolicy Bypass -File integrations/gui_grounding/start.ps1
```

The fetch command accepts only the two reviewed aliases and commits in `models.py`.
It records file hashes in each local weight directory. No weights, screenshots,
authentication tokens or evaluation raw outputs belong in the public source package.
