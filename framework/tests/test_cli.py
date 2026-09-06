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
        assert client.get('/v1/devices', headers={'Authorization': 'Bearer wrong'}).status_code == 401
        client.headers['Authorization'] = 'Bearer ' + token
        device = client.post('/v1/devices', json={'installation_id': 'standalone', 'name': 'Test device'})
        assert device.status_code == 200
        run = client.post('/v1/runs', json={'device_id': device.json()['id'], 'goal': 'Observe test screen', 'mode': 'assist'})
        assert run.status_code == 201
        assert client.get('/v1/extensions').status_code == 200
        assert client.get('/v1/auth/code').status_code == 404
        assert client.get('/v1/points').status_code == 404
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
