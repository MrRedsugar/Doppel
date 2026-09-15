import io
from pathlib import Path
import sys
import unittest

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from refinement import CropTransform, ground


def png(width=864, height=1920):
    stream = io.BytesIO()
    image=Image.new("RGB", (width, height), "white")
    ImageDraw.Draw(image).line((0,0,width-1,height-1),fill="black",width=2)
    image.save(stream, "PNG")
    return stream.getvalue()


class Backend:
    def __init__(self, results):
        self.results = iter(results)
        self.calls = []

    def infer(self, **kwargs):
        self.calls.append(kwargs)
        result = next(self.results)
        if isinstance(result, Exception):
            raise result
        return dict(model="test", revision="pinned", latency_ms=1.0, **result)


def point(x, y):
    return dict(status="point", point=(x, y), reason="point")


class RefinementTest(unittest.TestCase):
    def test_crop_edges_and_odd_dimensions_map_to_original_without_scaling_twice(self):
        for width, height, seed in [(864,1920,(107,278)), (865,1921,(864,1920)), (101,77,(50,38))]:
            crop = CropTransform.around(width,height,seed)
            self.assertGreaterEqual(crop.left,0); self.assertGreaterEqual(crop.top,0)
            self.assertLessEqual(crop.left+crop.width,width); self.assertLessEqual(crop.top+crop.height,height)
            self.assertEqual((crop.left,crop.top),crop.to_original((0,0)))
            self.assertEqual((crop.left+crop.width-1,crop.top+crop.height-1),crop.to_original((crop.width*2-1,crop.height*2-1)))
        crop=CropTransform.around(864,1920,(107,278))
        self.assertEqual((0,0,432,960),(crop.left,crop.top,crop.width,crop.height))
        self.assertEqual((103,293),crop.to_original((207,587)))

    def test_refinement_validates_exact_source_dimensions_and_point_types(self):
        for seed in [(0.5,1),(-1,1),(864,1),(True,1)]:
            with self.assertRaises(ValueError): CropTransform.around(864,1920,seed)
        crop=CropTransform.around(100,100,(50,50))
        with self.assertRaises(ValueError): crop.image(png(101,100))
        for invalid in [(100,1),(1,-1),(1.5,1)]:
            with self.assertRaises(ValueError): crop.to_original(invalid)

    def test_two_pass_result_maps_a1_and_retains_original_source_metadata_only(self):
        backend=Backend([point(107,278),point(207,587)])
        result=ground(backend,png(),"private target",864,1920,refine=True)
        self.assertEqual((103,293),result["point"])
        self.assertEqual(2,len(backend.calls))
        self.assertEqual("private target",backend.calls[1]["target"])
        self.assertEqual((864,1920),(backend.calls[1]["width"],backend.calls[1]["height"]))
        self.assertNotEqual(backend.calls[0]["png"],backend.calls[1]["png"])
        self.assertEqual([0,0,432,960],result["refinement"]["crop_rect"])
        self.assertEqual([107,278],result["refinement"]["coarse_point"])
        self.assertEqual(2,result["refinement"]["passes"])
        self.assertNotIn("private",str(result["refinement"]))

    def test_absence_invalid_output_and_refinement_failure_never_fall_back_to_coarse(self):
        absent=dict(status="not_found",point=None,reason="model_not_found")
        invalid=dict(status="not_found",point=None,reason="invalid_output")
        for first,second,expected in [(absent,None,1),(invalid,None,1),(point(30,30),absent,2),(point(30,30),invalid,2)]:
            backend=Backend([first]+([second] if second else []))
            result=ground(backend,png(),"target",864,1920,refine=True)
            self.assertIsNone(result["point"])
            self.assertEqual(expected,len(backend.calls))
            self.assertEqual((second or first)["reason"],result["reason"])
        backend=Backend([point(30,30),TimeoutError()])
        with self.assertRaises(TimeoutError): ground(backend,png(),"target",864,1920,refine=True)
        self.assertEqual(2,len(backend.calls))

    def test_optional_single_pass_and_one_pixel_image_do_not_add_an_unhelpful_call(self):
        for width,height,refine in [(864,1920,False),(1,1,True)]:
            backend=Backend([point(0,0)])
            result=ground(backend,png(width,height),"target",width,height,refine=refine)
            self.assertEqual(1,len(backend.calls))
            self.assertEqual((0,0),result["point"])

    def test_no_cross_request_crop_or_point_is_cached(self):
        backend=Backend([point(107,278),point(207,587),point(800,1800),point(100,100)])
        first=ground(backend,png(),"first",864,1920,refine=True)
        second=ground(backend,png(),"second",864,1920,refine=True)
        self.assertNotEqual(first["refinement"]["crop_rect"],second["refinement"]["crop_rect"])
        self.assertEqual("second",backend.calls[3]["target"])


if __name__=="__main__": unittest.main()
