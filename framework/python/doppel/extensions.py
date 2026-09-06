"""Official MCP SDK adapter; config is a trusted host deployment boundary."""

import asyncio
from contextlib import AsyncExitStack
from dataclasses import dataclass
import json
import re
from urllib.parse import urlsplit

import httpx2
from mcp import ClientSession, StdioServerParameters, types
from mcp.client.stdio import stdio_client
from mcp.client.streamable_http import streamable_http_client


@dataclass(frozen=True)
class MCPServerConfig:
    name: str
    transport: str
    command: str | None = None
    args: tuple[str, ...] = ()
    url: str | None = None
    timeout_seconds: float = 30
    max_result_bytes: int = 262144
    max_tools: int = 100

    def __post_init__(self):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', self.name):
            raise ValueError('MCP server name must be a namespace segment')
        if not 0 < self.timeout_seconds <= 300 or not 0 < self.max_tools <= 1000 or self.max_result_bytes <= 0:
            raise ValueError('MCP limits must be bounded and positive')
        if self.transport == 'stdio':
            if not self.command or self.url or not isinstance(self.args, tuple) or any(not isinstance(arg, str) for arg in self.args):
                raise ValueError('stdio requires an administrator command and tuple of arguments')
        elif self.transport == 'streamable_http':
            parsed = urlsplit(self.url or '')
            if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or self.command or self.args:
                raise ValueError('Streamable HTTP requires a configured HTTP(S) URL')
        else:
            raise ValueError('Unsupported MCP transport')


class MCPConnector:
    def __init__(self, config: MCPServerConfig):
        self.config = config
        self._stack = None
        self._session = None
        self._tools = {}
        self.server_capabilities = {}

    async def __aenter__(self):
        if self._stack is not None:
            raise RuntimeError('Connector is already open')
        stack = AsyncExitStack()
        self._stack = stack
        try:
            if self.config.transport == 'stdio':
                transport = stdio_client(StdioServerParameters(command=self.config.command, args=list(self.config.args)))
            else:
                http_client = await stack.enter_async_context(httpx2.AsyncClient(trust_env=False, timeout=self.config.timeout_seconds))
                transport = streamable_http_client(self.config.url, http_client=http_client)
            streams = await stack.enter_async_context(transport)
            self._session = await stack.enter_async_context(ClientSession(streams[0], streams[1]))
            async with asyncio.timeout(self.config.timeout_seconds):
                initialized = await self._session.initialize()
            self.server_capabilities = initialized.capabilities.model_dump(mode='json', exclude_none=True)
            return self
        except BaseException:
            await stack.aclose()
            self._stack = self._session = None
            raise

    async def __aexit__(self, *args):
        try:
            await self._stack.aclose()
        finally:
            self._stack = self._session = None
            self._tools.clear()

    def _require_session(self):
        if self._session is None:
            raise RuntimeError('Use async with MCPConnector(config)')
        return self._session

    def _bounded(self, value):
        if len(json.dumps(value, ensure_ascii=False).encode()) > self.config.max_result_bytes:
            raise ValueError('MCP result byte limit exceeded')
        return value

    async def list_tools(self) -> list[dict]:
        session = self._require_session()
        items, names, cursor, seen = [], {}, None, set()
        async with asyncio.timeout(self.config.timeout_seconds):
            while True:
                result = await session.list_tools(params=types.PaginatedRequestParams(cursor=cursor))
                for tool in result.tools:
                    if not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', tool.name):
                        raise ValueError('Unsupported external tool name')
                    name = f'mcp.{self.config.name}.{tool.name}'
                    if name in names or len(items) >= self.config.max_tools:
                        raise ValueError('Duplicate tool or MCP tool count limit exceeded')
                    names[name] = tool.name
                    items.append({'name': name, 'description': tool.description or '', 'input_schema': tool.input_schema,
                                  'version': '1', 'permission': 'external', 'trusted': False})
                cursor = result.next_cursor
                if not cursor:
                    break
                if cursor in seen:
                    raise ValueError('MCP pagination repeated cursor')
                seen.add(cursor)
        self._bounded(items)
        self._tools = names
        return items

    async def call_tool(self, name: str, arguments: dict) -> dict:
        session = self._require_session()
        if name not in self._tools:
            raise ValueError('Tool must be discovered in this configured namespace')
        if not isinstance(arguments, dict):
            raise ValueError('Tool arguments must be an object')
        self._bounded(arguments)
        async with asyncio.timeout(self.config.timeout_seconds):
            result = await session.call_tool(self._tools[name], arguments=arguments)
        return self._bounded(result.model_dump(mode='json', by_alias=True, exclude_none=True))
