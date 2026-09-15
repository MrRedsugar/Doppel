import io
import stat
import zipfile

from fastapi import FastAPI, Header, HTTPException
from fastapi.testclient import TestClient
import pytest

from doppel import DoppelRuntime, RuntimeConfig, create_router


def skill(name="notes"):
    return f"---\nname: {name}\ndescription: Summarize notes\ntrusted: true\n---\nRead only the requested note.\n".encode()


def archive(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, content in entries:
            if isinstance(name, str):
                entry = zipfile.ZipInfo(name)
                entry.filename = name
                entry.compress_type = zipfile.ZIP_DEFLATED
            else:
                entry = name
            output.writestr(entry, content)
    return result.getvalue()


@pytest.fixture
def setup(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    app = FastAPI()

    def owner(authorization: str | None = Header(None)):
        if authorization not in {"Bearer alice", "Bearer bob"}:
            raise HTTPException(401, "Authentication required")
        return authorization.split()[1]

    app.include_router(create_router(runtime, owner))
    with TestClient(app) as client:
        yield runtime, client
    runtime.store.db.close()


def upload(client, content, kind="markdown", owner="alice"):
    return client.post("/v1/skills/import", content=content, headers={
        "Authorization": "Bearer " + owner,
        "Content-Type": "application/zip" if kind == "zip" else "text/markdown",
    })


def auth(owner="alice"):
    return {"Authorization": "Bearer " + owner}


def test_markdown_import_list_read_delete_is_owner_scoped_and_untrusted(setup):
    runtime, client = setup
    imported = upload(client, skill())
    assert imported.status_code == 201, imported.text
    assert imported.json()["trusted"] is False
    assert imported.json()["source"] == "imported"
    assert client.get("/v1/skills", headers=auth()).json()["items"][0]["name"] == "notes"
    assert client.get("/v1/skills", headers=auth("bob")).json() == {"items": []}
    assert client.get("/v1/skills/notes", headers=auth("bob")).status_code == 404
    assert client.delete("/v1/skills/notes", headers=auth("bob")).status_code == 404
    read = client.get("/v1/skills/notes", headers=auth()).json()
    assert "Read only" in read["instructions"] and read["runtime_status"] == "unverified"
    assert client.delete("/v1/skills/notes", headers=auth()).status_code == 200
    assert client.get("/v1/skills", headers=auth()).json() == {"items": []}
    assert client.get("/v1/skills/notes", headers=auth()).status_code == 404
    assert not runtime.store.all("SELECT id FROM runs")


def test_zip_resources_are_preserved_as_data_without_execution(setup):
    runtime, client = setup
    data = archive([("notes/SKILL.md", skill()), ("notes/references/example.md", b"Reference content"),
                    ("notes/scripts/example.py", b"raise RuntimeError('must not execute')")])
    response = upload(client, data, "zip")
    assert response.status_code == 201, response.text
    read = client.get("/v1/skills/notes/resources", params={"path": "references/example.md"}, headers=auth())
    assert read.json() == {"content": "Reference content", "trusted": False}
    assert client.get("/v1/skills/notes/resources", params={"path": "../other"}, headers=auth()).status_code == 422
    assert client.get("/v1/skills/notes/resources", params={"path": "NUL"}, headers=auth()).status_code == 422
    assert not runtime.store.all("SELECT id FROM runs")


@pytest.mark.parametrize("path", ["../outside", "/outside", "notes/../outside", "notes\\outside", "notes/C:stream",
    "notes/CON", "notes/name.", "notes/name ", "notes//bad", "notes/./bad", "notes/\x01bad"])
def test_unsafe_archive_path_rejects_whole_import(setup, path):
    _, client = setup
    result = upload(client, archive([("notes/SKILL.md", skill()), (path, b"bad")]), "zip")
    assert result.status_code == 422, result.text
    assert client.get("/v1/skills", headers=auth()).json() == {"items": []}


def test_symlink_member_is_rejected(setup):
    _, client = setup
    link = zipfile.ZipInfo("notes/link")
    link.create_system = 3
    link.external_attr = (stat.S_IFLNK | 0o777) << 16
    assert upload(client, archive([("notes/SKILL.md", skill()), (link, b"../../escape")]), "zip").status_code == 422


@pytest.mark.parametrize("extra", [
    [("notes/one", b"a"), ("notes/ONE", b"b")],
    [("other/SKILL.md", skill("other"))],
    [("notes/large", b"x" * 65537)],
    [(f"notes/item{i}", b"x") for i in range(101)],
    [(f"notes/item{i}", b"x" * 65536) for i in range(33)],
])
def test_archive_ambiguity_and_expansion_limits_are_atomic(setup, extra):
    _, client = setup
    assert upload(client, archive([("notes/SKILL.md", skill()), *extra]), "zip").status_code == 422
    assert client.get("/v1/skills", headers=auth()).json() == {"items": []}


def test_upload_limit_and_authentication_before_import(setup):
    _, client = setup
    assert client.post("/v1/skills/import", content=skill(), headers={"Content-Type": "text/markdown"}).status_code == 401
    assert upload(client, b"x" * (2 * 1024 * 1024 + 1), "zip").status_code == 413
    assert upload(client, b"x" * 65537).status_code == 413
    assert upload(client, b"not a zip", "zip").status_code == 422


def test_duplicates_cannot_replace_imported_or_host_skills(setup):
    runtime, client = setup
    assert upload(client, skill()).status_code == 201
    assert upload(client, skill().replace(b"Read only", b"Overwrite")).status_code == 409
    assert "Read only" in client.get("/v1/skills/notes", headers=auth()).json()["instructions"]
    folder = runtime.config.data_dir / "skills" / "host"
    folder.mkdir(parents=True)
    (folder / "SKILL.md").write_bytes(skill("host"))
    assert upload(client, skill("host")).status_code == 409
    assert client.delete("/v1/skills/host", headers=auth()).status_code == 404
    assert client.get("/v1/skills/host", headers=auth()).json()["source"] == "host"


def test_internal_tools_read_only_the_run_owners_imported_skill(setup):
    runtime, client = setup
    assert upload(client, skill()).status_code == 201
    for owner, expected in [("alice", ["notes"]), ("bob", [])]:
        device = runtime.register_device(owner, "fixture", "Fixture")
        run = runtime.create_run(owner, device.id, "Read a skill", "assist")
        result = client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": "list_skills", "arguments": {}},
                             headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]})
        assert result.status_code == 200, result.text
        assert [item["name"] for item in result.json()["items"]] == expected


def test_host_skill_added_later_takes_precedence_ignoring_case(setup):
    runtime, client = setup
    assert upload(client, skill()).status_code == 201
    folder = runtime.config.data_dir / "skills" / "NOTES"
    folder.mkdir(parents=True)
    (folder / "SKILL.md").write_bytes(skill("NOTES"))
    response = client.get("/v1/skills/notes", headers=auth())
    assert response.status_code == 200, response.text
    assert response.json()["source"] == "host" and response.json()["name"] == "NOTES"
