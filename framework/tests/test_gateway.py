from fastapi import FastAPI, Header, HTTPException
from fastapi.testclient import TestClient

from doppel import DoppelRuntime, RuntimeConfig, create_router


def test_http_device_queue_requires_owner_and_returns_json_when_empty(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    app = FastAPI()

    def owner(authorization: str = Header()):
        if not authorization.startswith("Bearer "):
            raise HTTPException(401)
        return authorization[7:]

    app.include_router(create_router(runtime, owner))
    with TestClient(app) as client:
        headers = {"Authorization": "Bearer alice"}
        device = client.post("/v1/devices", json={"installation_id": "a", "name": "A"}, headers=headers).json()
        run = client.post("/v1/runs", json={"device_id": device["id"], "goal": "Inspect app", "mode": "full"}, headers=headers).json()
        assert run["status"] == "running"
        assert client.get(f"/v1/devices/{device['id']}/commands?timeout=0", headers=headers).json() == {"command": None}
        assert client.get(f"/v1/runs/{run['id']}", headers={"Authorization": "Bearer bob"}).status_code == 404
        runtime.queue_command(run["id"], "observe")
        command = client.get(f"/v1/devices/{device['id']}/commands?timeout=0", headers=headers).json()["command"]
        response = client.post(f"/v1/devices/{device['id']}/results", headers=headers, json={"command_id": command["id"], "run_id": run["id"], "status": "ok"})
        assert response.status_code == 200
        assert client.post(f"/v1/runs/{run['id']}/cancel", headers=headers).json()["status"] == "cancelled"

