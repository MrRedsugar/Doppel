import io

from fastapi import FastAPI
from fastapi.testclient import TestClient
from openpyxl import Workbook

from doppel import DoppelRuntime, RuntimeConfig, create_router


def test_import_is_a_new_owned_file_and_rejects_traversal_and_overwrite(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    stream = io.BytesIO()
    book = Workbook()
    book.active.append(["Month", "Amount"])
    book.active.append(["January", 25])
    book.save(stream)
    data = stream.getvalue()
    with TestClient(app) as client:
        assert client.post("/v1/documents", files={"file": ("../escape.xlsx", data)}).status_code == 422
        response = client.post("/v1/documents", files={"file": ("input.xlsx", data)})
        assert response.status_code == 201
        assert response.json()["download_uri"] == "doppel-document://input.xlsx"
        assert client.post("/v1/documents", files={"file": ("input.xlsx", data)}).status_code == 409
        assert client.get("/v1/documents/input.xlsx").content == data
        assert len(client.get("/v1/documents").json()["items"]) == 1
        assert client.delete("/v1/documents/input.xlsx").status_code == 200
        assert client.get("/v1/documents/input.xlsx").status_code == 404


def test_upload_replace_failure_does_not_leave_empty_destination(tmp_path, monkeypatch):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    stream = io.BytesIO()
    Workbook().save(stream)
    def fail_replace(*args):
        raise OSError("fixture disk failure")
    monkeypatch.setattr("doppel.document_api.os.replace", fail_replace)
    with TestClient(app, raise_server_exceptions=False) as client:
        assert client.post("/v1/documents", files={"file": ("input.xlsx", stream.getvalue())}).status_code == 500
        assert client.get("/v1/documents").json()["items"] == []
