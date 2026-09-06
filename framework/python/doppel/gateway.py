import asyncio
import time

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import Field

from .errors import DoppelError
from .models import CommandResult, Model


class CheckedRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def checked(request):
            try:
                return await handler(request)
            except DoppelError as error:
                return JSONResponse({"detail": str(error)}, status_code=error.status_code)
            except ValueError as error:
                return JSONResponse({"detail": str(error)}, status_code=422)

        return checked


class DeviceInput(Model):
    installation_id: str = Field(min_length=1, max_length=256)
    name: str = Field(min_length=1, max_length=120)


class RunInput(Model):
    device_id: str
    goal: str = Field(min_length=1, max_length=12000)
    mode: str = "assist"
    allowed_packages: list[str] = Field(default_factory=list, max_length=40)


class AnswerInput(Model):
    request_id: str
    text: str | None = Field(default=None, max_length=12000)
    approve: bool | None = None


def create_router(runtime, owner_dependency):
    router = APIRouter(prefix="/v1", route_class=CheckedRoute)

    @router.post("/devices")
    def register(body: DeviceInput, owner=Depends(owner_dependency)):
        return runtime.register_device(owner, body.installation_id, body.name)

    @router.get("/devices")
    def devices(owner=Depends(owner_dependency)):
        return {"items": runtime.devices(owner)}

    @router.post("/runs", status_code=201)
    async def create_run(body: RunInput, owner=Depends(owner_dependency)):
        run = runtime.create_run(owner, body.device_id, body.goal, body.mode, body.allowed_packages)
        if runtime.config.auto_start:
            runtime.start_run(run.id)
        return run

    @router.get("/runs")
    def runs(owner=Depends(owner_dependency)):
        return {"items": runtime.runs(owner)}

    @router.get("/runs/{run_id}")
    def get_run(run_id: str, owner=Depends(owner_dependency)):
        return runtime.get_run(owner, run_id)

    @router.post("/runs/{run_id}/pause")
    def pause(run_id: str, owner=Depends(owner_dependency)):
        return runtime.pause_run(owner, run_id)

    @router.post("/runs/{run_id}/resume")
    def resume(run_id: str, owner=Depends(owner_dependency)):
        return runtime.resume_run(owner, run_id)

    @router.post("/runs/{run_id}/cancel")
    def cancel(run_id: str, owner=Depends(owner_dependency)):
        return runtime.cancel_run(owner, run_id)

    @router.post("/runs/{run_id}/answer")
    def answer(run_id: str, body: AnswerInput, owner=Depends(owner_dependency)):
        return runtime.answer(owner, run_id, body.request_id, body.text, body.approve)

    @router.get("/runs/{run_id}/events")
    def events(run_id: str, after: int = Query(0, ge=0), owner=Depends(owner_dependency)):
        return {"items": runtime.events(owner, run_id, after)}

    @router.get("/devices/{device_id}/commands")
    async def command(device_id: str, timeout: float = Query(20, ge=0, le=25), owner=Depends(owner_dependency)):
        deadline = time.monotonic() + timeout
        while True:
            item = runtime.next_command(owner, device_id)
            if item is not None or time.monotonic() >= deadline:
                return {"command": item}
            await asyncio.sleep(0.1)

    @router.post("/devices/{device_id}/results")
    def result(device_id: str, body: CommandResult, owner=Depends(owner_dependency)):
        runtime.submit_result(owner, device_id, body)
        return {"ok": True}

    from .internal import create_internal_router
    from .document_api import create_document_router
    from .retention import create_retention_router
    from .extension_api import create_extension_router
    router.include_router(create_internal_router(runtime))
    router.include_router(create_document_router(runtime, owner_dependency))
    router.include_router(create_retention_router(runtime, owner_dependency))
    router.include_router(create_extension_router(runtime, owner_dependency))
    return router
