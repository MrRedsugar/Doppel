"""Bounded screenshot input and strict, non-executable model-output adapters."""
import base64
from dataclasses import dataclass
import hashlib
import io
import json
import re
import warnings

from PIL import Image

from models import MODELS

MAX_BODY = 8 * 1024 * 1024
MAX_PNG = 6 * 1024 * 1024
MAX_SIDE = 4096
MAX_PIXELS = 12_000_000


class InputError(ValueError):
    pass


@dataclass
class GroundRequest:
    capture_id: str
    image_sha256: str
    width: int
    height: int
    target: str
    png: bytes
    image: Image.Image


@dataclass
class ParsedPoint:
    point: tuple[int, int] | None
    reason: str


def decode_request(value):
    keys = {"capture_id", "image_base64", "image_sha256", "width", "height", "target"}
    if not isinstance(value, dict) or set(value) != keys:
        raise InputError("invalid_fields")
    if not isinstance(value["capture_id"], str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", value["capture_id"]) or ".." in value["capture_id"]:
        raise InputError("invalid_capture_id")
    if not isinstance(value["image_sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", value["image_sha256"]):
        raise InputError("invalid_image_hash")
    width, height = value["width"], value["height"]
    if any(type(n) is not int or not 1 <= n <= MAX_SIDE for n in (width, height)) or width * height > MAX_PIXELS:
        raise InputError("invalid_dimensions")
    target = value["target"]
    if not isinstance(target, str) or not 1 <= len(target.strip()) <= 1000 or any(ord(c) < 32 for c in target):
        raise InputError("invalid_target")
    encoded = value["image_base64"]
    if not isinstance(encoded, str) or not 1 <= len(encoded) <= MAX_BODY:
        raise InputError("invalid_image")
    try:
        png = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as exc:
        raise InputError("invalid_image_encoding") from exc
    if not 8 <= len(png) <= MAX_PNG or not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise InputError("png_required")
    if hashlib.sha256(png).hexdigest() != value["image_sha256"]:
        raise InputError("image_hash_mismatch")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(png)) as source:
                if source.format != "PNG" or source.size != (width, height) or getattr(source, "n_frames", 1) != 1:
                    raise InputError("image_dimensions_mismatch")
                source.load()
                image = source.convert("RGB")
    except InputError:
        raise
    except Exception as exc:
        raise InputError("invalid_png") from exc
    return GroundRequest(value["capture_id"], value["image_sha256"], width, height, target.strip(), png, image)


def unique_json(text):
    def pairs(items):
        obj = {}
        for key, value in items:
            if key in obj:
                raise ValueError("duplicate_key")
            obj[key] = value
        return obj
    return json.loads(text, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite")))


def parse_output(alias, raw, width, height):
    if not isinstance(raw, str) or len(raw) > 16000:
        return ParsedPoint(None, "invalid_output")
    tag = "answer" if alias == "mai-ui-2b" else "tool_call"
    bodies = re.findall(fr"<{tag}>\s*(.*?)\s*</{tag}>", raw, flags=re.DOTALL)
    if len(bodies) != 1:
        return ParsedPoint(None, "invalid_output")
    try:
        body = unique_json(bodies[0])
        if not isinstance(body, dict):
            raise ValueError()
        if alias == "mai-ui-2b":
            if body == {"status": "not_found"} or body == {"coordinate": None}:
                return ParsedPoint(None, "model_not_found")
            if set(body) != {"coordinate"}:
                raise ValueError()
            coordinate = body["coordinate"]
        else:
            if set(body) != {"name", "arguments"} or body["name"] != "computer_use":
                raise ValueError()
            args = body["arguments"]
            if args == {"action": "terminate", "status": "failure"}:
                return ParsedPoint(None, "model_not_found")
            if set(args) != {"action", "coordinate"} or args["action"] not in {"left_click", "mouse_move"}:
                raise ValueError()
            coordinate = args["coordinate"]
        scale = MODELS[alias]["coordinate_scale"]
        if not isinstance(coordinate, list) or len(coordinate) != 2 or any(type(n) is not int or not 0 <= n <= scale for n in coordinate):
            raise ValueError()
        x, y = coordinate
        return ParsedPoint((min(width - 1, x * width // scale), min(height - 1, y * height // scale)), "point")
    except (ValueError, TypeError, KeyError):
        return ParsedPoint(None, "invalid_output")
