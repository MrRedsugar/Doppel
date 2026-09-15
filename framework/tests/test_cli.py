import asyncio
import hashlib
from pathlib import Path

from fastapi.testclient import TestClient
import pytest

from doppel.cli import create_app, initialize, main


def test_standalone_auth_bootstrap_and_gateway(tmp_path):
    token_path = initialize(tmp_path)
    token = token_path.read_text().strip()
    assert len(token) >= 40
    assert initialize(tmp_path).read_text().strip() == token
    app = create_app(tmp_path, auto_start=False)
    with TestClient(app) as client:
        assert client.get('/health').json()['mode'] == 'developer'
        assert client.get('/v1/devices').status_code == 401
        assert client.get('/v1/points').status_code == 401
        assert client.get('/v1/devices', headers={'Authorization': 'Bearer wrong'}).status_code == 401
        client.headers['Authorization'] = 'Bearer ' + token
        device = client.post('/v1/devices', json={'installation_id': 'standalone', 'name': 'Test device'})
        assert device.status_code == 200
        run = client.post('/v1/runs', json={'device_id': device.json()['id'], 'goal': 'Observe test screen', 'mode': 'assist'})
        assert run.status_code == 201
        assert client.get('/v1/extensions').status_code == 200
        assert client.get('/v1/auth/code').status_code == 404
        points = client.get('/v1/points').json()
        assert points['unlimited'] is True and points['mode'] == 'developer'
        assert points['remaining'] is None and points['daily_limit'] is None
        with app.state.runtime.store.transaction() as db:
            app.state.runtime.store.event(db, run.json()['id'], 'usage', 'Synthetic usage',
                                          {'call_id': 'one', 'input_tokens': 601, 'output_tokens': 9, 'model': 'fixture'})
            app.state.runtime.store.event(db, run.json()['id'], 'usage', 'Synthetic usage',
                                          {'call_id': 'two', 'input_tokens': 290, 'output_tokens': 10, 'model': 'fixture'})
        points = client.get('/v1/points').json()
        assert points['input_tokens'] == 891 and points['output_tokens'] == 19
        assert points['used_points'] == 4 and points['calls'] == 2
        assert points['usage_period'] == 'retained_history'
        assert client.put('/v1/points', json={'unlimited': False}).status_code == 405
        assert client.post('/v1/runs/' + run.json()['id'] + '/cancel').status_code == 200
        assert app.state.runtime.billing is None


def test_bootstrap_rejects_weak_existing_token_and_cli_keeps_secret_off_stdout(tmp_path, capsys):
    assert main(['init', '--data-dir', str(tmp_path)]) == 0
    token_file = tmp_path / 'developer-token.txt'
    token = token_file.read_text().strip()
    assert token not in capsys.readouterr().out
    token_file.write_text('weak')
    with pytest.raises(ValueError):
        create_app(tmp_path, auto_start=False)


def test_standalone_cli_exposes_provider_and_vision_configuration(tmp_path, monkeypatch):
    from doppel import cli
    served = []
    monkeypatch.setattr(cli.uvicorn, "run", lambda app, **kwargs: served.append(app))
    assert main(["serve", "--data-dir", str(tmp_path), "--provider", "deepseek",
                 "--model", "deepseek-v4-flash", "--vision-model", "deepseek-v4-flash-vision-exp"]) == 0
    runtime = served[0].state.runtime
    assert runtime.config.provider == "deepseek"
    assert runtime.config.model == "deepseek-v4-flash"
    assert runtime.config.vision_model == "deepseek-v4-flash-vision-exp"
    with TestClient(served[0]) as client:
        health = client.get("/health").json()
        assert health["provider"] == "deepseek" and health["model"] == "deepseek-v4-flash"
        assert "token" not in health and "api_key_file" not in health


def test_standalone_health_identifies_current_default_model(tmp_path):
    with TestClient(create_app(tmp_path, auto_start=False)) as client:
        health = client.get("/health").json()
        assert health["provider"] == "xiaomi-mimo"
        assert health["model"] == "mimo-v2.5-pro" and health["vision_model"] == "mimo-v2.5"


def test_cli_configures_custom_enhancement_with_separate_secret_file(tmp_path, monkeypatch):
    from doppel import cli
    served = []
    key = tmp_path / "vision-key.txt"; key.write_text("fixture-private-key")
    monkeypatch.setattr(cli.uvicorn, "run", lambda app, **kwargs: served.append(app))
    assert main(["serve", "--data-dir", str(tmp_path / "state"), "--provider", "qwen",
        "--vision-provider", "custom", "--vision-model", "vision-fixture", "--vision-endpoint", "https://fixture.invalid/v1",
        "--vision-api-key-file", str(key)]) == 0
    with TestClient(served[0]) as client:
        config = client.app.state.runtime.config
        assert config.model == "qwen3.8-flash"
        assert config.vision_provider == "custom" and config.vision_model == "vision-fixture"
        assert config.vision_api_key_file == key
        assert "fixture-private-key" not in client.get("/health").text
