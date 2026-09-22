"""Bounded retry identities; accepted tasks do not expire with their submission keys."""

import re
import secrets

RETENTION_MS = 30 * 24 * 60 * 60 * 1000
FUTURE_SKEW_MS = 5 * 60 * 1000
PATTERN = re.compile(r"q2:(0|[1-9][0-9]{0,15}):[A-Za-z0-9_.:-]{1,120}")


def create(at, nonce=None):
    key = f"q2:{at}:{nonce or secrets.token_hex(16)}"
    if len(key) > 160 or not PATTERN.fullmatch(key):
        raise ValueError("Invalid submission id")
    return key


def floor(now):
    return max(0, now - RETENTION_MS + 1)


def validate(key, now, minimum_issued_at=0):
    if key is None or not key.startswith("q2:"):
        return None
    match = PATTERN.fullmatch(key)
    if not match:
        raise ValueError("Invalid submission id")
    issued = int(match[1])
    if issued < max(minimum_issued_at, floor(now)):
        raise ValueError("Submission receipt expired; check device time and create a new task. Old tasks are never replayed")
    if issued - now > FUTURE_SKEW_MS:
        raise ValueError("Submission time is in the future; check device time")
    return issued
