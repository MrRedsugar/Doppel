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
from .providers import DEFAULT_PROVIDER, PROVIDERS


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
               host_url: str = 'http://127.0.0.1:8765', auto_start: bool = True,
               provider: str = DEFAULT_PROVIDER, model: str | None = None, vision_model: str | None = None,
               provider_endpoint: str | None = None, provider_headers_file: Path | None = None,
               vision_provider: str | None = None, vision_endpoint: str | None = None,
               vision_api_key_file: Path | None = None, vision_headers_file: Path | None = None,
               vision_enhancement_enabled: bool | None = None):
    token_file = initialize(data_dir)
    token_hash = hashlib.sha256(token_file.read_text(encoding='ascii').strip().encode()).digest()
    runtime = DoppelRuntime(RuntimeConfig(data_dir=Path(data_dir), api_key_file=Path(api_key_file) if api_key_file else None,
                                          host_url=host_url, auto_start=auto_start, provider=provider,
                                          model=model, vision_model=vision_model,
                                          provider_endpoint=provider_endpoint, provider_headers_file=provider_headers_file,
                                          vision_provider=vision_provider, vision_endpoint=vision_endpoint,
                                          vision_api_key_file=vision_api_key_file, vision_headers_file=vision_headers_file,
                                          vision_enhancement_enabled=vision_enhancement_enabled))

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
        return {'ok': True, 'mode': 'developer', 'model_configured': runtime.config.api_key_file is not None or runtime.config.provider_headers_file is not None,
                'provider': runtime.config.provider, 'model': runtime.config.model, 'vision_model': runtime.config.vision_model,
                'vision_provider': runtime.config.vision_provider, 'vision_enhancement_enabled': runtime.config.vision_enhancement_enabled}

    @app.get('/v1/points')
    def points(current_owner=Depends(owner)):
        usage = runtime.store.one("SELECT COUNT(*) AS calls, COALESCE(SUM(json_extract(e.data,'$.input_tokens')),0) AS input_tokens, COALESCE(SUM(json_extract(e.data,'$.output_tokens')),0) AS output_tokens, COALESCE(SUM(MAX(1,(json_extract(e.data,'$.input_tokens') + json_extract(e.data,'$.output_tokens') + 299) / 300)),0) AS used_points FROM events e JOIN runs r ON r.id=e.run_id WHERE r.owner=? AND e.kind='usage'", (current_owner,))
        return {'mode': 'developer', 'unlimited': True, 'remaining': None, 'daily_limit': None,
                'purchased': 0, 'debt': 0, 'reserved_points': 0, 'usage_period': 'retained_history',
                'tokens_per_point': 300, 'rate_version': 'v2', 'points_are_estimate': True, **dict(usage)}

    @app.get('/v1/usage')
    def usage(current_owner=Depends(owner)):
        """Token-first dashboard data; points are intentionally omitted."""
        rows = runtime.store.all("""SELECT substr(e.created_at,1,10) AS day,
            COUNT(*) AS requests,
            COALESCE(SUM(json_extract(e.data,'$.input_tokens')),0) AS input_tokens,
            COALESCE(SUM(json_extract(e.data,'$.output_tokens')),0) AS output_tokens,
            COALESCE(SUM(CASE WHEN e.kind IN ('screenshot','capture') THEN 1 ELSE 0 END),0) AS screenshots
            FROM events e JOIN runs r ON r.id=e.run_id
            WHERE r.owner=? AND e.kind='usage' GROUP BY day ORDER BY day DESC LIMIT 30""", (current_owner,))
        daily = [{'date': row['day'], 'requests': int(row['requests'] or 0),
                  'input_tokens': int(row['input_tokens'] or 0),
                  'output_tokens': int(row['output_tokens'] or 0),
                  'screenshots': int(row['screenshots'] or 0)} for row in reversed(rows)]
        return {'input_tokens': sum(int(row['input_tokens'] or 0) for row in daily),
                'output_tokens': sum(int(row['output_tokens'] or 0) for row in daily),
                'requests': sum(int(row['requests'] or 0) for row in daily),
                'screenshots': sum(int(row['screenshots'] or 0) for row in daily), 'daily': daily,
                'source': 'gateway_events'}

    app.include_router(create_router(runtime, owner))
    # Included router owns scheduler startup/shutdown before the runtime closes.
    app.state.scheduler = runtime._doppel_scheduler
    return app


def main(argv=None):
    parser = argparse.ArgumentParser(prog='doppel', description='Standalone Doppel developer gateway')
    commands = parser.add_subparsers(dest='command', required=True)
    initialize_command = commands.add_parser('init', help='Create a local developer token')
    initialize_command.add_argument('--data-dir', type=Path, default=Path('.local/developer'))
    serve = commands.add_parser('serve', help='Run the authenticated developer gateway')
    serve.add_argument('--data-dir', type=Path, default=Path('.local/developer'))
    serve.add_argument('--api-key-file', type=Path)
    serve.add_argument('--provider', choices=tuple(PROVIDERS), default=DEFAULT_PROVIDER)
    serve.add_argument('--model', help='Main model; defaults to the selected provider\'s main model')
    serve.add_argument('--vision-model', help='Screenshot model; defaults to the selected provider\'s vision model')
    serve.add_argument('--provider-endpoint', help='HTTPS base URL or chat/completions URL for the custom provider')
    serve.add_argument('--provider-headers-file', type=Path, help='Private JSON file with additional request headers')
    serve.add_argument('--vision-provider', choices=tuple(PROVIDERS), help='Optional independent vision platform')
    serve.add_argument('--vision-endpoint', help='HTTPS endpoint for an independent custom vision platform')
    serve.add_argument('--vision-api-key-file', type=Path, help='Private key file for the independent vision platform')
    serve.add_argument('--vision-headers-file', type=Path, help='Private JSON headers for the independent vision platform')
    serve.add_argument('--vision-enhancement', action=argparse.BooleanOptionalAction, default=None,
                       help='Use an independent vision model; --no-vision-enhancement follows the primary model')
    serve.add_argument('--host', default='127.0.0.1')
    serve.add_argument('--port', type=int, default=8765)
    serve.add_argument('--gateway-url', help='Address used by local Harness workers; defaults to loopback')
    args = parser.parse_args(argv)
    if args.command == 'init':
        print('Developer token file: ' + str(initialize(args.data_dir)))
        return 0
    if not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    for name in ('api_key_file', 'provider_headers_file', 'vision_api_key_file', 'vision_headers_file'):
        value = getattr(args, name)
        if value and not value.is_file():
            parser.error('--' + name.replace('_', '-') + ' must be an existing private file')
    app = create_app(args.data_dir, api_key_file=args.api_key_file,
                     host_url=args.gateway_url or f'http://127.0.0.1:{args.port}',
                     provider=args.provider, model=args.model, vision_model=args.vision_model,
                     provider_endpoint=args.provider_endpoint, provider_headers_file=args.provider_headers_file,
                     vision_provider=args.vision_provider, vision_endpoint=args.vision_endpoint,
                     vision_api_key_file=args.vision_api_key_file, vision_headers_file=args.vision_headers_file,
                     vision_enhancement_enabled=args.vision_enhancement)
    uvicorn.run(app, host=args.host, port=args.port, access_log=False)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
