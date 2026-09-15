import base64
import hashlib
import io
import json
from pathlib import Path
import sys
import unittest

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from protocol import InputError, decode_request, parse_output


class ProtocolTest(unittest.TestCase):
    def request(self, **updates):
        stream = io.BytesIO()
        Image.new("RGB", (400, 800), "white").save(stream, "PNG")
        png = stream.getvalue()
        value = dict(capture_id="capture-1", image_base64=base64.b64encode(png).decode(),
                     image_sha256=hashlib.sha256(png).hexdigest(), width=400, height=800, target="保存")
        value.update(updates)
        return value

    def test_checked_png_identity_is_preserved(self):
        request = decode_request(self.request())
        self.assertEqual((400, 800), request.image.size)
        self.assertEqual("capture-1", request.capture_id)
        self.assertEqual("保存", request.target)

    def test_hash_dimensions_and_extra_urls_cannot_be_forged(self):
        for values in [dict(image_sha256="0" * 64), dict(width=401), dict(height=True),
                       dict(image_url="https://example.com/x.png"), dict(target=""), dict(capture_id="../bad")]:
            with self.subTest(values=values), self.assertRaises(InputError):
                decode_request(self.request(**values))

    def test_image_payload_must_be_strict_base64_png(self):
        for encoded in ["http://example.com/x.png", "@@@@", "data:image/png;base64,AAAA", ""]:
            with self.subTest(encoded=encoded), self.assertRaises(InputError):
                decode_request(self.request(image_base64=encoded))

    def test_mai_uses_999_and_returns_original_integer_pixels(self):
        result = parse_output("mai-ui-2b", '<answer>{"coordinate":[999,499]}</answer>', 400, 800)
        self.assertEqual((399, 399), result.point)

    def test_owl_uses_1000_without_conflating_mai_scale(self):
        result = parse_output("gui-owl-2b", '<tool_call>{"name":"computer_use","arguments":{"action":"left_click","coordinate":[500,250]}}</tool_call>', 400, 800)
        self.assertEqual((200, 200), result.point)

    def test_multiple_or_out_of_bounds_answers_are_not_executable(self):
        answers = ['<answer>{"coordinate":[-1,20]}</answer>', '<answer>{"coordinate":[1000,20]}</answer>',
                   '<answer>{"coordinate":[2.5,20]}</answer>', '<answer>{"coordinate":[true,20]}</answer>',
                   '<answer>{"coordinate":[20,20]}</answer>' * 2,
                   '<answer>{"coordinate":[20,20],"execute":"something"}</answer>', 'click(20,20)']
        for raw in answers:
            with self.subTest(raw=raw):
                self.assertIsNone(parse_output("mai-ui-2b", raw, 400, 800).point)

    def test_explicit_absence_is_distinguished_from_invalid_output(self):
        refusal = parse_output("gui-owl-2b", '<tool_call>{"name":"computer_use","arguments":{"action":"terminate","status":"failure"}}</tool_call>', 400, 800)
        self.assertIsNone(refusal.point)
        self.assertEqual("model_not_found", refusal.reason)
        self.assertEqual("invalid_output", parse_output("gui-owl-2b", "nonsense", 400, 800).reason)

    def test_arbitrary_generated_actions_are_never_accepted(self):
        for action in ["type", "key", "shell", "launch"]:
            raw = '<tool_call>' + json.dumps({"name": "computer_use", "arguments": {"action": action, "coordinate": [50, 50]}}) + '</tool_call>'
            self.assertIsNone(parse_output("gui-owl-2b", raw, 400, 800).point)


if __name__ == "__main__":
    unittest.main()
