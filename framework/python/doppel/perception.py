import base64
import binascii
import struct

from .models import Observation


def screenshot_coordinates(image: str, observation: Observation) -> dict:
    try:
        header = base64.b64decode(image, validate=True)
        if len(header) < 33 or header[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
            raise ValueError("Invalid PNG screenshot")
        width, height = struct.unpack(">II", header[16:24])
    except (binascii.Error, TypeError, struct.error):
        raise ValueError("Invalid PNG screenshot") from None
    if not 0 < width <= 16384 or not 0 < height <= 16384:
        raise ValueError("Invalid screenshot dimensions")
    return {
        "screen_id": observation.screen_id,
        "screen_width": observation.width, "screen_height": observation.height,
        "image_width": width, "image_height": height,
        "image_to_screen_scale_x": observation.width / width,
        "image_to_screen_scale_y": observation.height / height,
    }


def _contained_label(node, observation):
    left, top, right, bottom = node.bounds
    if (right - left) * (bottom - top) > observation.width * observation.height / 4:
        return ""
    labels = set()
    for other in observation.nodes:
        x1, y1, x2, y2 = other.bounds
        if other is node or not (left <= x1 < x2 <= right and top <= y1 < y2 <= bottom):
            continue
        if other.clickable or other.long_clickable or other.editable or other.scrollable or other.password:
            return ""
        label = " ".join((other.text or other.description).split())[:300]
        if label:
            labels.add(label)
    return next(iter(labels)) if len(labels) == 1 else ""


def compact_observation(observation: Observation, *, limit: int = 14000) -> str:
    lines = [f"App {observation.package_name}; screen {observation.screen_id}; {observation.width}x{observation.height}"]
    length = len(lines[0])
    for node in observation.nodes:
        if node.password:
            continue
        label = " ".join((node.text or node.description).split())[:300]
        if not label and (node.clickable or node.long_clickable):
            label = _contained_label(node, observation)
        if not label and not (node.clickable or node.long_clickable or node.editable or node.scrollable):
            continue
        properties = [node.role.rsplit(".", 1)[-1]]
        if node.clickable and node.enabled:
            properties.append("tap")
        if node.long_clickable and node.enabled:
            properties.append("long_press")
        if not (node.clickable or node.long_clickable or node.editable or node.scrollable):
            properties.append("read-only")
        if node.editable:
            properties.append("input")
        if node.scrollable:
            properties.append("scroll")
        if not node.enabled:
            properties.append("disabled")
        if not label:
            x1, y1, x2, y2 = node.bounds
            label = f"unlabeled at {(x1+x2)//2},{(y1+y2)//2}"
        line = f"{node.id} {label} ({','.join(properties)})"
        if length + len(line) + 1 > limit:
            lines.append("[observation truncated; inspect the relevant region]")
            break
        lines.append(line)
        length += len(line) + 1
    return "\n".join(lines)
