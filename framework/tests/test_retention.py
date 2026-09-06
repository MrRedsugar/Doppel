import asyncio
import base64
from datetime import datetime, timedelta, timezone
import json
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from doppel import DoppelRuntime, RuntimeConfig
from doppel.errors import Conflict, NotFound
from doppel.models import CommandResult
from doppel.retention import RetentionManager, create_retention_router


IMAGE = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jQz8AAAAASUVORK5CYII=')


def fixture(tmp_path, owner='alice'):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device(owner, 'fixture', 'Fixture')
    run = runtime.create_run(owner, device.id, 'Inspect', 'full')
    command = runtime.queue_command(run.id, 'screenshot')
    raw = base64.b64encode(IMAGE).decode()
    runtime.submit_result(owner, device.id, CommandResult(command_id=command.id, run_id=run.id, status='ok', data={'image_base64': raw, 'mime_type': 'image/png'}))
    with runtime.store.transaction() as db:
        runtime.store.event(db, run.id, 'fixture_image_copy', 'data:image/png;base64,' + raw, {'image_base64': raw})
    folder = tmp_path / 'harness' / run.id
    folder.mkdir(parents=True)
    (folder / 'session.json').write_text(json.dumps({'image': raw}))
    runtime.set_status(run.id, 'completed', 'Fixture finished')
    return runtime, device, run, command, folder


def test_terminal_task_delete_removes_records_files_token_and_queues_device_cleanup(tmp_path):
    runtime, device, run, command, folder = fixture(tmp_path)
    manager = RetentionManager(runtime)
    result = manager.delete_run('alice', run.id)
    assert result['status'] == 'deleted'
    assert not folder.exists()
    assert run.id not in runtime.run_tokens
    assert runtime.store.one('SELECT * FROM runs WHERE id=?', (run.id,)) is None
    assert runtime.store.one('SELECT * FROM commands WHERE run_id=?', (run.id,)) is None
    assert runtime.store.one('SELECT * FROM events WHERE run_id=?', (run.id,)) is None
    requests = manager.device_cleanup('alice', device.id)
    assert requests[0]['command_ids'] == [command.id]
    with pytest.raises(NotFound):
        manager.device_cleanup('bob', device.id)
    manager.ack_device_cleanup('alice', device.id, requests[0]['id'])
    assert manager.device_cleanup('alice', device.id) == []


def test_failed_filesystem_delete_is_recoverable_and_does_not_claim_completion(tmp_path, monkeypatch):
    runtime, device, run, command, folder = fixture(tmp_path)
    manager = RetentionManager(runtime)
    original = manager._remove_session

    def locked(run_id):
        raise PermissionError('Fixture file is locked')

    monkeypatch.setattr(manager, '_remove_session', locked)
    result = manager.delete_run('alice', run.id)
    assert result['status'] == 'pending'
    assert result['cleanup_pending'] is True
    assert folder.exists()
    assert runtime.store.one('SELECT * FROM retention_cleanup WHERE run_id=?', (run.id,))
    with pytest.raises(NotFound):
        manager.screenshots('alice', run.id)
    recovered = RetentionManager(runtime)
    assert recovered.recover()['completed'] == 1
    assert not folder.exists()
    assert not runtime.store.one('SELECT * FROM retention_cleanup WHERE run_id=?', (run.id,))


@pytest.mark.asyncio
async def test_deletion_refuses_running_or_unclosed_worker(tmp_path):
    runtime, device, run, command, folder = fixture(tmp_path)
    manager = RetentionManager(runtime)
    task = asyncio.create_task(asyncio.sleep(100))
    runtime.tasks[run.id] = task
    with pytest.raises(Conflict):
        manager.delete_run('alice', run.id)
    with pytest.raises(Conflict):
        manager.delete_screenshot('alice', run.id, command.id)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    second = runtime.create_run('alice', device.id, 'Still running', 'full')
    with pytest.raises(Conflict):
        manager.delete_run('alice', second.id)
    assert folder.exists()


def test_retention_defaults_forever_and_old_terminal_cleanup(tmp_path):
    runtime, device, run, command, folder = fixture(tmp_path)
    manager = RetentionManager(runtime)
    assert manager.settings('alice')['days'] == 7
    old = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
    with runtime.store.transaction() as db:
        db.execute('UPDATE events SET created_at=? WHERE run_id=?', (old, run.id))
    assert manager.update_settings('alice', 0)['days'] == 0
    assert manager.prune()['completed'] == 0
    assert folder.exists()
    manager.update_settings('alice', 7)
    assert manager.prune()['completed'] == 1
    assert not folder.exists()
    for days in [-1, True, 3660]:
        with pytest.raises(ValueError):
            manager.update_settings('alice', days)


def test_screenshot_delete_scrubs_all_server_copies_preserves_task_and_ledger(tmp_path):
    runtime, device, run, command, folder = fixture(tmp_path)
    runtime.billing = object()
    manager = RetentionManager(runtime)
    assert manager.screenshots('alice', run.id)[0]['id'] == command.id
    assert manager.screenshot('alice', run.id, command.id)[0] == IMAGE
    with pytest.raises(NotFound):
        manager.screenshot('bob', run.id, command.id)
    result = manager.delete_screenshot('alice', run.id, command.id)
    assert result['status'] == 'deleted'
    assert manager.screenshots('alice', run.id) == []
    assert not folder.exists()
    raw = base64.b64encode(IMAGE).decode()
    rows = runtime.store.all('SELECT result FROM commands WHERE run_id=?', (run.id,))
    events = runtime.store.all('SELECT message,data FROM events WHERE run_id=?', (run.id,))
    assert all(raw not in str(tuple(row)) for row in rows + events)
    assert runtime.get_run('alice', run.id).status == 'completed'
    assert runtime.command_result(command.id).data['image_base64'] is None
    assert runtime.billing is not None


def test_retention_http_routes_and_owner_checks(tmp_path):
    runtime, device, run, command, folder = fixture(tmp_path)
    app = FastAPI()
    app.include_router(create_retention_router(runtime, lambda: 'alice'), prefix='/v1')
    with TestClient(app) as client:
        assert client.get('/v1/data-retention').json()['days'] == 7
        assert client.patch('/v1/data-retention', json={'days': 0}).status_code == 200
        images = client.get(f'/v1/runs/{run.id}/screenshots').json()['items']
        image = client.get(f'/v1/runs/{run.id}/screenshots/{images[0]["id"]}')
        assert image.status_code == 200 and image.content == IMAGE
        assert image.headers['content-type'] == 'image/png'
        assert image.headers['cache-control'] == 'no-store'
        assert client.delete(f'/v1/runs/{run.id}/screenshots/{command.id}').status_code == 200
        assert client.delete(f'/v1/runs/{run.id}').status_code == 200


def test_checkpoint_failure_keeps_recovery_intent_after_logical_delete(tmp_path, monkeypatch):
    runtime, device, run, command, folder = fixture(tmp_path)
    manager = RetentionManager(runtime)

    def busy():
        raise OSError('Fixture reader blocks WAL checkpoint')

    monkeypatch.setattr(manager, '_flush_deleted_pages', busy)
    assert manager.delete_run('alice', run.id)['cleanup_pending'] is True
    assert runtime.store.one('SELECT * FROM runs WHERE id=?', (run.id,)) is None
    assert runtime.store.one('SELECT phase FROM retention_cleanup WHERE run_id=?', (run.id,))['phase'] == 'checkpoint'
    restored = RetentionManager(runtime)
    assert restored.recover()['completed'] == 1
    assert len(restored.device_cleanup('alice', device.id)) == 1


def test_concurrent_recovery_does_not_duplicate_device_purge_requests(tmp_path, monkeypatch):
    runtime, device, run, command, folder = fixture(tmp_path)
    manager = RetentionManager(runtime)
    manager._enqueue('alice', run.id, 'run', [])
    original = manager._remove_session

    def slow(run_id):
        import time
        time.sleep(0.03)
        original(run_id)

    monkeypatch.setattr(manager, '_remove_session', slow)
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(lambda _: manager.recover(), range(2)))
    assert len(manager.device_cleanup('alice', device.id)) == 1
    assert all(item['pending'] == 0 for item in outcomes)


def test_unrelated_screenshot_and_other_owner_data_are_preserved(tmp_path):
    runtime, device, run, command, folder = fixture(tmp_path)
    manager = RetentionManager(runtime)
    bob_device = runtime.register_device('bob', 'other', 'Other')
    bob_run = runtime.create_run('bob', bob_device.id, 'Other owner', 'full')
    bob_folder = tmp_path / 'harness' / bob_run.id
    bob_folder.mkdir()
    (bob_folder / 'session.json').write_text('private owner data')
    with pytest.raises(NotFound):
        manager.delete_run('bob', run.id)
    manager.delete_run('alice', run.id)
    assert runtime.get_run('bob', bob_run.id).goal == 'Other owner'
    assert bob_folder.exists()
    assert manager.settings('bob')['days'] == 7
    manager.update_settings('alice', 0)
    assert manager.settings('bob')['days'] == 7


def test_retention_setup_is_wired_to_main_gateway(tmp_path):
    from doppel import create_router
    runtime, device, run, command, folder = fixture(tmp_path)
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: 'alice'))
    with TestClient(app) as client:
        assert client.get('/v1/data-retention').status_code == 200
        assert client.get(f'/v1/devices/{device.id}/data-cleanup').json() == {'items': []}
        assert client.delete(f'/v1/runs/{run.id}').status_code == 200
        queue = client.get(f'/v1/devices/{device.id}/data-cleanup').json()['items']
        assert client.post(f'/v1/devices/{device.id}/data-cleanup/{queue[0]["id"]}/ack').status_code == 200


@pytest.mark.asyncio
async def test_delete_waits_for_cancelled_runs_inflight_model_accounting(tmp_path):
    import httpx
    from doppel.model_proxy import call_model
    from test_model_proxy import setup_proxy
    runtime, run, billing, client = setup_proxy(tmp_path)
    manager = RetentionManager(runtime)
    entered, release = asyncio.Event(), asyncio.Event()

    async def upstream(request):
        entered.set()
        await release.wait()
        return httpx.Response(200, json={'choices': [], 'usage': {'prompt_tokens': 40, 'completion_tokens': 3}})

    runtime.upstream_transport = httpx.MockTransport(upstream)
    pending = asyncio.create_task(call_model(runtime, run.id, {'messages': []}))
    try:
        await entered.wait()
        runtime.cancel_run('alice', run.id)
        with pytest.raises(Conflict):
            manager.delete_run('alice', run.id)
        assert run.id in runtime.run_tokens
        release.set()
        await pending
        assert billing.receipts[0][2:] == (40, 3)
        assert any(event.kind == 'usage' for event in runtime.events('alice', run.id))
        assert manager.delete_run('alice', run.id)['status'] == 'deleted'
        assert not runtime.active_model_calls
    finally:
        release.set()
        await asyncio.gather(pending, return_exceptions=True)


@pytest.mark.asyncio
async def test_cancelled_provider_request_audits_unknown_usage_before_allowing_cleanup(tmp_path):
    import httpx
    from doppel.model_proxy import call_model
    from test_model_proxy import setup_proxy
    runtime, run, billing, client = setup_proxy(tmp_path)
    entered = asyncio.Event()

    async def upstream(request):
        entered.set()
        await asyncio.Event().wait()

    runtime.upstream_transport = httpx.MockTransport(upstream)
    pending = asyncio.create_task(call_model(runtime, run.id, {'messages': []}))
    await entered.wait()
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert runtime.get_run('alice', run.id).status == 'failed'
    unknown = next(event for event in runtime.events('alice', run.id) if event.kind == 'usage_unknown')
    assert unknown.data['call_id'] == billing.reservations[0][1]
    assert not billing.receipts and not billing.releases
    assert not runtime.active_model_calls
    assert RetentionManager(runtime).delete_run('alice', run.id)['status'] == 'deleted'


@pytest.mark.asyncio
async def test_retention_guard_counts_each_concurrent_model_request(tmp_path):
    import httpx
    from doppel.model_proxy import call_model
    from test_model_proxy import setup_proxy
    runtime, run, billing, client = setup_proxy(tmp_path)
    entered, releases = [], [asyncio.Event(), asyncio.Event()]
    ready = asyncio.Event()

    async def upstream(request):
        index = len(entered)
        entered.append(request)
        if len(entered) == 2:
            ready.set()
        await releases[index].wait()
        return httpx.Response(200, json={'choices': [], 'usage': {'prompt_tokens': 10, 'completion_tokens': 1}})

    runtime.upstream_transport = httpx.MockTransport(upstream)
    pending = [asyncio.create_task(call_model(runtime, run.id, {'messages': []})) for _ in range(2)]
    try:
        await ready.wait()
        runtime.cancel_run('alice', run.id)
        releases[0].set()
        await pending[0]
        with pytest.raises(Conflict):
            RetentionManager(runtime).delete_run('alice', run.id)
        releases[1].set()
        await pending[1]
        assert len(billing.receipts) == 2
        assert RetentionManager(runtime).delete_run('alice', run.id)['status'] == 'deleted'
    finally:
        for release in releases:
            release.set()
        await asyncio.gather(*pending, return_exceptions=True)
