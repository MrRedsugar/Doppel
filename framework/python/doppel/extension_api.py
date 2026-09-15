"""Authenticated user configuration routes, never runtime/model tool endpoints."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field

from .errors import DoppelError
from .extension_runtime import get_extension_manager
from .gateway import CheckedRoute
from .models import Model


class ExtensionInput(Model):
    name: str = Field(min_length=1, max_length=64)
    url: str = Field(min_length=1, max_length=2048)
    allowed_tools: list[str] = Field(default_factory=list, max_length=100)
    read_only_tools: list[str] = Field(default_factory=list, max_length=100)


class ExtensionUpdate(ExtensionInput):
    expected_revision: str | None = Field(default=None, pattern=r'^[a-f0-9]{32}$')


def create_extension_router(runtime, owner_dependency):
    manager = get_extension_manager(runtime)
    router = APIRouter(route_class=CheckedRoute)
    from .skill_api import create_skill_router
    router.include_router(create_skill_router(runtime, owner_dependency))

    @router.get('/extensions')
    def configurations(owner=Depends(owner_dependency)):
        return {'items': manager.list_configs(owner)}

    @router.post('/extensions', status_code=201)
    def create(body: ExtensionInput, owner=Depends(owner_dependency)):
        return manager.create_config(owner, body.model_dump())

    @router.put('/extensions/{name}')
    def update(name: str, body: ExtensionUpdate, owner=Depends(owner_dependency)):
        return manager.update_config(owner, name, body.model_dump(exclude={'expected_revision'}),
                                     expected_revision=body.expected_revision)

    @router.delete('/extensions/{name}')
    def delete(name: str, owner=Depends(owner_dependency)):
        manager.delete_config(owner, name)
        return {'ok': True}

    @router.get('/extensions/{name}/tools')
    async def tools(name: str, owner=Depends(owner_dependency)):
        try:
            return {'items': await manager.discover_tools(owner, name)}
        except (DoppelError, ValueError):
            raise
        except TimeoutError as error:
            raise HTTPException(504, 'Extension discovery timed out') from error
        except Exception as error:
            raise HTTPException(502, 'Extension discovery failed') from error

    return router
