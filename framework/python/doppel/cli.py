"""Standalone single-owner developer gateway without product-service imports."""

import argparse
from contextlib import asynccontextmanager
import hashlib
import os
from pathlib import Path
import re
import secrets

from fastapi import Depends, FastAPI, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import uvicorn

from . import DoppelRuntime, RuntimeConfig, create_router


def initialize(data_dir: str | Path) -> Path:
    folder = Path(data_dir)
    folder.mkdir(parents=True, exist_ok=True)
    token_file = folder / 'developer-token.txt'
    if token_file.is_symlink():
        raise ValueError('Developer token cannot be a symlink')
    try:
        descriptor = os.open(token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(descriptor, 'w', encoding='ascii') as stream:
            stream.write(secrets.token_urlsafe(48) + '\n')
    with token_file.open('rb') as stream:
        value = stream.read(257).decode('ascii').strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]{40,128}', value):
        raise ValueError('Existing developer token is invalid; replace it with a random high-entropy token')
    return token_file.resolve()


def create_app(data_dir: str | Path, *, api_key_file: str | Path | None = None,
               host_url: str = 'http://127.0.0.1:8765', auto_start: bool = True):
    token_file = initialize(data_dir)
    token_hash = hashlib.sha256(token_file.read_text(encoding='ascii').strip().encode()).digest()
    runtime = DoppelRuntime(RuntimeConfig(data_dir=Path(data_dir), api_key_file=Path(api_key_file) if api_key_file else None,
                                          host_url=host_url, auto_start=auto_start))

    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            await runtime.close()

    app = FastAPI(title='Doppel developer gateway', lifespan=lifespan)
    app.state.runtime = runtime
    bearer = HTTPBearer(auto_error=False)

    def owner(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if credentials is None or credentials.scheme.lower() != 'bearer':
            raise HTTPException(401, 'Developer bearer token required')
        supplied = hashlib.sha256(credentials.credentials.encode()).digest()
        if not secrets.compare_digest(supplied, token_hash):
            raise HTTPException(401, 'Invalid developer token')
        return 'developer'

    @app.get('/health')
    def health():
        return {'ok': True, 'mode': 'developer', 'model_configured': runtime.config.api_key_file is not None}

    @app.get('/v1/points')
    def points(current_owner=Depends(owner)):
        usage = runtime.store.one("SELECT COUNT(*) AS calls, COALESCE(SUM(json_extract(e.data,'$.input_tokens')),0) AS input_tokens, COALESCE(SUM(json_extract(e.data,'$.output_tokens')),0) AS output_tokens, COALESCE(SUM(MAX(1,(json_extract(e.data,'$.input_tokens') + json_extract(e.data,'$.output_tokens') + 299) / 300)),0) AS used_points FROM events e JOIN runs r ON r.id=e.run_id WHERE r.owner=? AND e.kind='usage'", (current_owner,))
        return {'mode': 'developer', 'unlimited': True, 'remaining': None, 'daily_limit': None,
                'purchased': 0, 'debt': 0, 'reserved_points': 0, 'usage_period': 'retained_history',
                'tokens_per_point': 300, 'rate_version': 'v2', 'points_are_estimate': True, **dict(usage)}

    app.include_router(create_router(runtime, owner))
    return app


def main(argv=None):
    parser = argparse.ArgumentParser(prog='doppel', description='Standalone Doppel developer gateway')
    commands = parser.add_subparsers(dest='command', required=True)
    initialize_command = commands.add_parser('init', help='Create a local developer token')
    initialize_command.add_argument('--data-dir', type=Path, default=Path('.local/developer'))
    serve = commands.add_parser('serve', help='Run the authenticated developer gateway')
    serve.add_argument('--data-dir', type=Path, default=Path('.local/developer'))
    serve.add_argument('--api-key-file', type=Path)
    serve.add_argument('--host', default='127.0.0.1')
    serve.add_argument('--port', type=int, default=8765)
    serve.add_argument('--gateway-url', help='Address used by local Harness workers; defaults to loopback')
    args = parser.parse_args(argv)
    if args.command == 'init':
        print('Developer token file: ' + str(initialize(args.data_dir)))
        return 0
    if not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    if args.api_key_file and not args.api_key_file.is_file():
        parser.error('--api-key-file must be an existing private file')
    app = create_app(args.data_dir, api_key_file=args.api_key_file,
                     host_url=args.gateway_url or f'http://127.0.0.1:{args.port}')
    uvicorn.run(app, host=args.host, port=args.port, access_log=False)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
