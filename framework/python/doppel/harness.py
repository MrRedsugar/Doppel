import asyncio
import json
import os
import subprocess
import sys

from .models import Run


class HarnessPort:
    async def run(self, runtime, run_id):
        run = Run.model_validate_json(runtime._row(run_id)["payload"])
        folder = runtime.config.data_dir / "harness" / run_id
        folder.mkdir(parents=True, exist_ok=True)
        keep = ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATH", "TEMP", "TMP", "PATHEXT")
        env = {name: os.environ[name] for name in keep if name in os.environ}
        env.update({
            "PYTHONPATH": str(__import__("pathlib").Path(__file__).resolve().parents[1]),
            "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1",
            "DOPPEL_RUN_ID": run_id, "DOPPEL_RUN_TOKEN": runtime.run_tokens[run_id],
            "DOPPEL_GATEWAY": runtime.config.host_url.rstrip("/"),
            "DOPPEL_HARNESS_HOME": str(folder.resolve()),
            "DOPPEL_MODEL": runtime.config.model,
            "DOPPEL_MAX_OUTPUT": str(runtime.config.max_output_tokens),
            "DOPPEL_GOAL": run.goal,
            "HOME": str(folder.resolve()), "USERPROFILE": str(folder.resolve()),
        })
        kwargs = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        process = await asyncio.create_subprocess_exec(sys.executable, "-m", "doppel.harness_worker", env=env,
                                                       stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, **kwargs)
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=3600)
        except (asyncio.CancelledError, asyncio.TimeoutError):
            if os.name == "nt":
                killer = await asyncio.create_subprocess_exec("taskkill", "/PID", str(process.pid), "/T", "/F", stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, **kwargs)
                await killer.wait()
            elif process.returncode is None:
                process.terminate()
            await process.wait()
            raise
        token = runtime.run_tokens.get(run_id, "")
        diagnostic = stderr.decode("utf-8", "replace").replace(token, "[task-token]")[-16000:]
        (folder / "worker.log").write_text(diagnostic, encoding="utf-8")
        result = None
        for line in stdout.decode("utf-8", "replace").splitlines():
            try:
                parsed = json.loads(line)
                if parsed.get("kind") == "result":
                    result = parsed
            except (ValueError, AttributeError):
                continue
        if process.returncode != 0 or result is None:
            runtime.set_status(run_id, "failed", "智能体运行异常，详情已记录到本地诊断")
            return
        current = Run.model_validate_json(runtime._row(run_id)["payload"])
        if current.status == "running":
            runtime.set_status(run_id, "failed", "智能体已结束，但未提交可验证的完成结果。" + result.get("message", "")[:11000])
