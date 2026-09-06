"""Trusted local demonstration server, without filesystem or network tools."""

import asyncio
from mcp.server import MCPServer

server = MCPServer('Doppel arithmetic example')


@server.tool()
def add(left: float, right: float) -> float:
    """Add two numbers."""
    return left + right


@server.tool()
async def wait(seconds: float) -> str:
    """Wait briefly; useful for testing cancellation."""
    if not 0 <= seconds <= 30:
        raise ValueError('seconds must be between zero and thirty')
    await asyncio.sleep(seconds)
    return 'finished'


if __name__ == '__main__':
    server.run(transport='stdio')
