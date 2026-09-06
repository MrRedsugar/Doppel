"""Owner-scoped remote extension configuration and host permission enforcement."""

import asyncio
from contextlib import closing
import json
from pathlib import Path
import re
import secrets
import sqlite3

from jsonschema import Draft202012Validator, validators
from jsonschema.exceptions import SchemaError, ValidationError
from referencing import Registry
from referencing.exceptions import Unresolvable

from .errors import Conflict, NotFound, PermissionDenied
from .extensions import MCPConnector, MCPServerConfig


_DENIED = re.compile(
    r'payment|checkout|purchase|chargecard|chargemoney|paynow|makepayment|submitpayment|'
    r'fundtransfer|transferfund|sendmoney|grantpermission|setpermission|grantaccess|'
    r'escalateprivilege|setrole|grantrole|\bpay\b|\bpayment\b|\bgrant\b|'
    r'\u652f\u4ed8|\u4ed8\u6b3e|\u6263\u6b3e|\u8f6c\u8d26|\u63d0\u6743', re.IGNORECASE,
)


def _sensitive(value):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    words = re.sub(r'([a-z])([A-Z])', r'\1 \2', text).replace('_', ' ')
    return bool(_DENIED.search(words) or _DENIED.search(re.sub(r'[^\w\u4e00-\u9fff]', '', text).replace('_', '')))


def _snapshot(value, limit=65536):
    def bounded(item, depth=0):
        if depth > 20:
            raise ValueError('Extension data nesting limit exceeded')
        if isinstance(item, dict):
            for key, child in item.items():
                if not isinstance(key, str):
                    raise ValueError('Extension object keys must be strings')
                bounded(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                bounded(child, depth + 1)
    bounded(value)
    try:
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError) as error:
        raise ValueError('Extension data must be finite JSON') from error
    if len(encoded.encode()) > limit:
        raise ValueError('Extension data byte limit exceeded')
    return json.loads(encoded)


class ExtensionApprovalRequired(PermissionDenied):
    def __init__(self, name, arguments, configuration_revision=None):
        super().__init__('Approval required for external tool mutation')
        self.name = name
        self.arguments = _snapshot(arguments)
        self.configuration_revision = configuration_revision


class ExtensionManager:
    def __init__(self, runtime):
        self.runtime = runtime
        self.timeout_seconds = 30
        self.path = Path(runtime.config.data_dir) / 'extensions.sqlite3'
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.is_symlink():
            raise ValueError('Extension database cannot be a symlink')
        with closing(self._connect()) as db:
            db.execute('CREATE TABLE IF NOT EXISTS configurations(owner TEXT NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(owner,name))')
            db.commit()

    def _connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        return db

    @staticmethod
    def _owner(owner):
        if not isinstance(owner, str) or not owner or len(owner) > 256:
            raise PermissionDenied('Authenticated owner is required')
        return owner

    @staticmethod
    def _config(payload):
        payload = _snapshot(payload, 32768)
        if not isinstance(payload, dict) or set(payload) - {'name', 'url', 'allowed_tools', 'read_only_tools'}:
            raise ValueError('Only remote extension fields are permitted')
        if not {'name', 'url'} <= payload.keys() or not isinstance(payload['name'], str) or not isinstance(payload['url'], str) or len(payload['url']) > 2048:
            raise ValueError('Extension requires name and URL')
        MCPServerConfig(name=payload['name'], transport='streamable_http', url=payload['url'])
        for field in ['allowed_tools', 'read_only_tools']:
            items = payload.setdefault(field, [])
            if not isinstance(items, list) or len(items) > 100 or any(not isinstance(item, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', item) for item in items) or len(set(items)) != len(items):
                raise ValueError('Tool grants must be bounded arrays of distinct tool names')
            if any(_sensitive(item) for item in items):
                raise PermissionDenied('Payment and permission-grant tools cannot be granted')
        if not set(payload['read_only_tools']) <= set(payload['allowed_tools']):
            raise ValueError('Read-only tools must also be explicitly allowed')
        payload['revision'] = secrets.token_hex(16)
        return payload

    def list_configs(self, owner):
        with closing(self._connect()) as db:
            return [json.loads(row['payload']) for row in db.execute('SELECT payload FROM configurations WHERE owner=? ORDER BY name', (self._owner(owner),))]

    def get_config(self, owner, name):
        with closing(self._connect()) as db:
            row = db.execute('SELECT payload FROM configurations WHERE owner=? AND name=?', (self._owner(owner), name)).fetchone()
        if row is None:
            raise NotFound('Extension not found')
        return json.loads(row['payload'])

    def create_config(self, owner, payload):
        config = self._config(payload)
        with closing(self._connect()) as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT COUNT(*) FROM configurations WHERE owner=?', (self._owner(owner),)).fetchone()[0] >= 20:
                raise ValueError('Extension count limit exceeded')
            try:
                db.execute('INSERT INTO configurations VALUES(?,?,?)', (owner, config['name'], json.dumps(config)))
                db.commit()
            except sqlite3.IntegrityError as error:
                raise Conflict('Extension already exists') from error
        return config

    def update_config(self, owner, name, payload):
        config = self._config(payload)
        if config['name'] != name:
            raise ValueError('Extension name cannot change during update')
        with closing(self._connect()) as db:
            cursor = db.execute('UPDATE configurations SET payload=? WHERE owner=? AND name=?', (json.dumps(config), self._owner(owner), name))
            if not cursor.rowcount:
                raise NotFound('Extension not found')
            db.commit()
        return config

    def delete_config(self, owner, name):
        with closing(self._connect()) as db:
            cursor = db.execute('DELETE FROM configurations WHERE owner=? AND name=?', (self._owner(owner), name))
            if not cursor.rowcount:
                raise NotFound('Extension not found')
            db.commit()

    def _connector(self, config):
        return MCPConnector(MCPServerConfig(name=config['name'], transport='streamable_http', url=config['url'], timeout_seconds=self.timeout_seconds))

    async def discover_tools(self, owner, name):
        config = self.get_config(owner, name)
        async with asyncio.timeout(self.timeout_seconds):
            async with self._connector(config) as connector:
                tools = await connector.list_tools()
        result = []
        prefix = f'mcp.{name}.'
        for tool in tools:
            raw = tool['name'].removeprefix(prefix)
            result.append({**tool, 'allowed': raw in config['allowed_tools'],
                           'read_only': raw in config['read_only_tools'], 'blocked': _sensitive(raw),
                           'configuration_revision': config['revision']})
        return _snapshot(result, 262144)

    async def list_tools(self, owner):
        result = []
        for config in self.list_configs(owner):
            if config['allowed_tools']:
                result.extend(tool for tool in await self.discover_tools(owner, config['name']) if tool['allowed'] and not tool['blocked'])
        return _snapshot(result, 262144)

    @staticmethod
    def _validate(schema, arguments):
        schema = _snapshot(schema)
        def check_refs(item):
            if isinstance(item, dict):
                for key, value in item.items():
                    if key in {'$ref', '$dynamicRef', '$recursiveRef'} and (not isinstance(value, str) or not value.startswith('#')):
                        raise ValueError('Remote schema references are unsupported')
                    check_refs(value)
            elif isinstance(item, list):
                for value in item:
                    check_refs(value)
        check_refs(schema)
        try:
            validator = validators.validator_for(schema, default=Draft202012Validator)
            validator.check_schema(schema)
            validator(schema, registry=Registry()).validate(arguments)
        except (SchemaError, ValidationError, RecursionError, Unresolvable) as error:
            raise ValueError('Arguments or external JSON schema are invalid') from error

    async def call_tool(self, owner, name, arguments, mode, approved=False, *, expected_revision=None, before_dispatch=None):
        if mode not in {'ask', 'assist', 'full'} or not isinstance(approved, bool):
            raise ValueError('Invalid extension execution mode')
        if not isinstance(name, str) or not re.fullmatch(r'mcp\.[A-Za-z0-9_-]{1,64}\.[A-Za-z0-9_.-]{1,128}', name):
            raise ValueError('Expected a namespaced MCP tool')
        _, server, raw = name.split('.', 2)
        config = self.get_config(owner, server)
        if expected_revision is not None and config['revision'] != expected_revision:
            raise Conflict('Extension configuration changed after approval')
        if raw not in config['allowed_tools']:
            raise PermissionDenied('Tool was not explicitly granted by this owner')
        arguments = _snapshot(arguments)
        if not isinstance(arguments, dict):
            raise ValueError('Tool arguments must be an object')
        if _sensitive(raw) or _sensitive(arguments):
            raise PermissionDenied('Payment and permission-grant operations require manual handling')
        async with asyncio.timeout(self.timeout_seconds) as deadline:
            async with self._connector(config) as connector:
                tools = await connector.list_tools()
                descriptor = next((tool for tool in tools if tool['name'] == name), None)
                if descriptor is None:
                    raise NotFound('Granted tool is no longer advertised')
                self._validate(descriptor['input_schema'], arguments)
                if before_dispatch is not None:
                    deadline.reschedule(None)
                    await before_dispatch()
                    deadline.reschedule(asyncio.get_running_loop().time() + self.timeout_seconds)
                if self.get_config(owner, server)['revision'] != config['revision']:
                    raise Conflict('Extension configuration changed; retry discovery')
                if mode in {'ask', 'assist'} and raw not in config['read_only_tools'] and not approved:
                    raise ExtensionApprovalRequired(name, arguments, config['revision'])
                return _snapshot(await connector.call_tool(name, arguments), 262144)


def get_extension_manager(runtime):
    manager = getattr(runtime, 'extension_manager', None)
    if manager is None:
        manager = runtime.extension_manager = ExtensionManager(runtime)
    return manager
