"""Authenticated file import only; Skills never grant execution permissions."""

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from .gateway import CheckedRoute
from .skill_library import MAX_PACKAGE_BYTES, MAX_RESOURCE_BYTES, get_skill_library


def create_skill_router(runtime, owner_dependency):
    library = get_skill_library(runtime)
    router = APIRouter(prefix='/skills', route_class=CheckedRoute)

    @router.get('')
    def list_skills(owner=Depends(owner_dependency)):
        return {'items': library.list_skills(owner)}

    @router.post('/import', status_code=201)
    async def import_skill(request: Request, owner=Depends(owner_dependency)):
        media = request.headers.get('content-type', '').split(';', 1)[0].strip().lower()
        kind = {'application/zip': 'zip', 'text/markdown': 'markdown'}.get(media)
        if kind is None or request.headers.get('content-encoding', 'identity').lower() != 'identity':
            raise HTTPException(415, 'Import an uncompressed HTTP ZIP or SKILL.md body')
        limit = MAX_PACKAGE_BYTES if kind == 'zip' else MAX_RESOURCE_BYTES
        parts, size = [], 0
        try:
            async with asyncio.timeout(15):
                async for part in request.stream():
                    size += len(part)
                    if size > limit:
                        raise HTTPException(413, 'Skills upload size limit exceeded')
                    parts.append(part)
        except TimeoutError:
            raise HTTPException(408, 'Skills upload timed out') from None
        except ClientDisconnect:
            raise HTTPException(400, 'Skills upload interrupted') from None
        return await run_in_threadpool(library.import_package, owner, b''.join(parts), kind)

    @router.get('/{name}/resources')
    def resource(name: str, path: str = Query(min_length=1, max_length=240), owner=Depends(owner_dependency)):
        return {'content': library.read_resource(owner, name, path), 'trusted': False}

    @router.get('/{name}')
    def read_skill(name: str, owner=Depends(owner_dependency)):
        return library.read_skill(owner, name)

    @router.delete('/{name}')
    def delete_skill(name: str, owner=Depends(owner_dependency)):
        library.delete_skill(owner, name)
        return {'ok': True}

    return router
