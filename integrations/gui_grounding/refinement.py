"""One bounded crop refinement; all externally returned points use original image pixels."""
from dataclasses import dataclass
import hashlib
import io
import time

from PIL import Image


def checked_point(point, width, height):
    if not isinstance(point, (tuple, list)) or len(point) != 2 or any(type(v) is not int for v in point):
        raise ValueError("invalid_point")
    if not 0 <= point[0] < width or not 0 <= point[1] < height:
        raise ValueError("point_out_of_bounds")
    return point


@dataclass(frozen=True)
class CropTransform:
    source_width: int
    source_height: int
    left: int
    top: int
    width: int
    height: int

    @classmethod
    def around(cls, width, height, point):
        if any(type(v) is not int or not 1 <= v <= 4096 for v in (width, height)):
            raise ValueError("invalid_dimensions")
        x, y = checked_point(point, width, height)
        crop_width, crop_height = max(1, width // 2), max(1, height // 2)
        return cls(width, height, min(max(x - crop_width // 2, 0), width - crop_width),
                   min(max(y - crop_height // 2, 0), height - crop_height), crop_width, crop_height)

    def image(self, png):
        with Image.open(io.BytesIO(png)) as source:
            if source.format != "PNG" or source.size != (self.source_width, self.source_height):
                raise ValueError("source_dimensions_mismatch")
            with source.crop((self.left, self.top, self.left+self.width, self.top+self.height)) as cropped:
                with cropped.resize((self.width*2, self.height*2), Image.Resampling.LANCZOS) as zoom:
                    stream = io.BytesIO()
                    zoom.save(stream, "PNG")
                    return stream.getvalue()

    def to_original(self, point):
        x, y = checked_point(point, self.width*2, self.height*2)
        result = (self.left + x // 2, self.top + y // 2)
        return tuple(checked_point(result, self.source_width, self.source_height))


def ground(model, png, target, width, height, refine=False):
    """Called inside one killable worker job, so both passes share its parent-enforced deadline."""
    started = time.perf_counter()
    first = model.infer(png=png, target=target, width=width, height=height)
    result = dict(first)
    detail = {"enabled": refine, "passes": 1, "coordinate_space": "original_image_pixels"}
    if first["point"] is not None:
        checked_point(first["point"], width, height)
    if refine and first["point"] is not None and width >= 2 and height >= 2:
        crop = CropTransform.around(width, height, first["point"])
        derived = crop.image(png)
        detail.update(passes=2, coarse_point=list(first["point"]),
                      crop_rect=[crop.left,crop.top,crop.left+crop.width,crop.top+crop.height],
                      scale=2, derived_sha256=hashlib.sha256(derived).hexdigest())
        second = model.infer(png=derived, target=target, width=crop.width*2, height=crop.height*2)
        result = dict(second)
        # Refusal and invalid output stay refused/invalid. Never reuse the coarse point as a fallback.
        if second["point"] is not None:
            detail["refined_point"] = list(checked_point(second["point"], crop.width*2, crop.height*2))
            result["point"] = crop.to_original(second["point"])
        for field in ("input_tokens", "output_tokens"):
            if field in first and field in second:
                result[field] = first[field] + second[field]
        for field in ("peak_allocated_bytes", "peak_reserved_bytes"):
            if field in first and field in second:
                result[field] = max(first[field], second[field])
        if "cold_inference" in first:
            result["cold_inference"] = first["cold_inference"]
    result["refinement"] = detail
    result["latency_ms"] = round((time.perf_counter()-started)*1000,3)
    return result
