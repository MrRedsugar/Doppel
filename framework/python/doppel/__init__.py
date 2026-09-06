from .runtime import DoppelRuntime, RuntimeConfig

__all__ = ["DoppelRuntime", "RuntimeConfig", "create_router"]


def create_router(runtime, owner_dependency):
    from .gateway import create_router as factory
    return factory(runtime, owner_dependency)

