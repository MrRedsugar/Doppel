class DoppelError(RuntimeError):
    status_code = 400


class NotFound(DoppelError):
    status_code = 404


class Conflict(DoppelError):
    status_code = 409


class PermissionDenied(DoppelError):
    status_code = 403


class ScopeDenied(PermissionDenied):
    """A mutation targeted an observed screen outside the task's app scope."""
