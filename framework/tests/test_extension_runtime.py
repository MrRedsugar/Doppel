import asyncio
from copy import deepcopy

import pytest

from doppel import DoppelRuntime, RuntimeConfig
from doppel.errors import NotFound, PermissionDenied
from doppel.extension_runtime import ExtensionManager, ExtensionApprovalRequired


class FixtureConnector:
    calls = []
    delay = 0
    enter_delay = 0
    schema = {'type': 'object', 'properties': {'value': {'type': 'integer'}}, 'required': ['value'], 'additionalProperties': False}

    def __init__(self, config):
        self.config = config

    async def __aenter__(self):
        await asyncio.sleep(self.enter_delay)
        return self

    async def __aexit__(self, *args):
        pass

    async def list_tools(self):
        return [{'name': f'mcp.{self.config.name}.{name}', 'description': 'Fixture tool', 'input_schema': deepcopy(self.schema),
                 'annotations': {'readOnlyHint': True}, 'permission': 'external', 'trusted': False}
                for name in ['read', 'write', 'pay', 'grant_permission']]

    async def call_tool(self, name, arguments):
        await asyncio.sleep(self.delay)
        self.calls.append((name, deepcopy(arguments)))
        return {'isError': False, 'structuredContent': {'result': arguments['value']}}


@pytest.fixture
def manager(tmp_path, monkeypatch):
    monkeypatch.setattr('doppel.extension_runtime.MCPConnector', FixtureConnector)
    FixtureConnector.calls = []
    FixtureConnector.delay = 0
    FixtureConnector.enter_delay = 0
    FixtureConnector.schema = {'type': 'object', 'properties': {'value': {'type': 'integer'}}, 'required': ['value'], 'additionalProperties': False}
    return ExtensionManager(DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False)))


def configure(manager, allowed=None, read_only=None):
    return manager.create_config('alice', {'name': 'sample', 'url': 'http://127.0.0.1:9876/mcp',
                                          'allowed_tools': allowed or [], 'read_only_tools': read_only or []})


@pytest.mark.asyncio
async def test_empty_grants_and_owner_isolation(manager):
    configure(manager)
    assert await manager.list_tools('alice') == []
    assert len(await manager.discover_tools('alice', 'sample')) == 4
    assert manager.list_configs('bob') == []
    with pytest.raises(NotFound):
        await manager.discover_tools('bob', 'sample')
    with pytest.raises(PermissionDenied):
        await manager.call_tool('alice', 'mcp.sample.read', {'value': 1}, 'full')
    assert FixtureConnector.calls == []


@pytest.mark.asyncio
async def test_approval_is_independent_of_external_annotations(manager):
    configure(manager, ['read', 'write'], ['read'])
    assert len(await manager.list_tools('alice')) == 2
    assert (await manager.call_tool('alice', 'mcp.sample.read', {'value': 2}, 'ask'))['structuredContent']['result'] == 2
    for mode in ['ask', 'assist']:
        args = {'value': 3}
        with pytest.raises(ExtensionApprovalRequired) as pending:
            await manager.call_tool('alice', 'mcp.sample.write', args, mode)
        args['value'] = 99
        assert pending.value.name == 'mcp.sample.write'
        assert pending.value.arguments == {'value': 3}
    await manager.call_tool('alice', 'mcp.sample.write', {'value': 4}, 'assist', approved=True)
    await manager.call_tool('alice', 'mcp.sample.write', {'value': 5}, 'full')
    assert len(FixtureConnector.calls) == 3


@pytest.mark.asyncio
async def test_schema_and_payment_permission_denial(manager):
    configure(manager, ['write'])
    with pytest.raises(ValueError):
        await manager.call_tool('alice', 'mcp.sample.write', {'value': 'not a number'}, 'full')
    for name in ['pay', 'grant_permission']:
        with pytest.raises(PermissionDenied):
            manager.update_config('alice', 'sample', {'name': 'sample', 'url': 'http://localhost/mcp', 'allowed_tools': [name], 'read_only_tools': []})
    FixtureConnector.schema = {'type': 'object', 'properties': {'action': {'type': 'string'}}}
    with pytest.raises(PermissionDenied):
        await manager.call_tool('alice', 'mcp.sample.write', {'action': 'make_payment'}, 'full', approved=True)
    FixtureConnector.schema = {'$ref': 'http://127.0.0.1:9876/remote-schema'}
    with pytest.raises(ValueError, match='schema'):
        await manager.call_tool('alice', 'mcp.sample.write', {'value': 1}, 'full')
    assert FixtureConnector.calls == []


@pytest.mark.asyncio
async def test_timeout_cancellation_and_persistent_revocation(manager):
    configure(manager, ['read'], ['read'])
    FixtureConnector.delay = 10
    manager.timeout_seconds = 0.05
    with pytest.raises(TimeoutError):
        await manager.call_tool('alice', 'mcp.sample.read', {'value': 1}, 'full')
    manager.timeout_seconds = 30
    pending = asyncio.create_task(manager.call_tool('alice', 'mcp.sample.read', {'value': 1}, 'full'))
    await asyncio.sleep(0.01)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    restored = ExtensionManager(manager.runtime)
    assert restored.list_configs('alice')[0]['allowed_tools'] == ['read']
    restored.delete_config('alice', 'sample')
    with pytest.raises(NotFound):
        await manager.call_tool('alice', 'mcp.sample.read', {'value': 1}, 'full')
    assert FixtureConnector.calls == []


def test_untrusted_commands_and_read_only_subset_rejected(manager):
    with pytest.raises(ValueError):
        manager.create_config('alice', {'name': 'bad', 'url': 'file:///secret', 'allowed_tools': [], 'read_only_tools': []})
    with pytest.raises(ValueError):
        manager.create_config('alice', {'name': 'bad', 'url': 'http://localhost/mcp', 'command': 'cmd.exe'})
    with pytest.raises(ValueError):
        configure(manager, ['read'], ['write'])


@pytest.mark.parametrize('name', ['pay_invoice', 'payInvoice', 'grant', 'charge_card'])
def test_sensitive_tool_name_variants_rejected(manager, name):
    with pytest.raises(PermissionDenied):
        configure(manager, [name])


@pytest.mark.asyncio
async def test_connect_timeout_and_config_change_before_dispatch(manager, monkeypatch):
    configure(manager, ['write'])
    FixtureConnector.enter_delay = 0.2
    manager.timeout_seconds = 0.02
    with pytest.raises(TimeoutError):
        await manager.call_tool('alice', 'mcp.sample.write', {'value': 1}, 'full')
    FixtureConnector.enter_delay = 0
    manager.timeout_seconds = 30
    original = FixtureConnector.list_tools

    async def revoke(self):
        result = await original(self)
        manager.update_config('alice', 'sample', {'name': 'sample', 'url': 'http://localhost/changed', 'allowed_tools': [], 'read_only_tools': []})
        return result

    monkeypatch.setattr(FixtureConnector, 'list_tools', revoke)
    from doppel.errors import Conflict
    with pytest.raises(Conflict):
        await manager.call_tool('alice', 'mcp.sample.write', {'value': 1}, 'full', approved=True)
    assert FixtureConnector.calls == []
