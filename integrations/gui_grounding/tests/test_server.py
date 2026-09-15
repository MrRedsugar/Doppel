import base64
import hashlib
import http.client
import io
import json
from pathlib import Path
import sys
import socket
import threading
import time
import unittest

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import GroundServer
from inference import BusyError, CancelledError
from refinement import ground


class DeterministicBackend:
    """Inference boundary double; HTTP security and serialization remain real."""
    spec = {"repo": "test-model", "revision": "test-revision"}
    def infer(self, **kwargs):
        if kwargs["target"] == "busy":
            raise BusyError()
        if kwargs["target"] == "timeout":
            raise TimeoutError()
        return {"status": "point", "point": [20, 30], "reason": "point", "model": "test-model",
                "revision": "test-revision", "latency_ms": 2.5, "raw_output": "never expose this"}


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.server = GroundServer(("127.0.0.1", 0), "t" * 64, DeterministicBackend())
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        stream = io.BytesIO()
        Image.new("RGB", (100, 100), "white").save(stream, "PNG")
        png = stream.getvalue()
        self.payload = dict(capture_id="frame-1", image_sha256=hashlib.sha256(png).hexdigest(),
                            image_base64=base64.b64encode(png).decode(), width=100, height=100, target="button")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, payload=None, token="t" * 64, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        body = json.dumps(self.payload if payload is None else payload)
        fields = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        fields.update(headers or {})
        connection.request("POST", "/v1/ground", body, fields)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_authentication_is_required_before_grounding(self):
        status, body = self.request(token="wrong")
        self.assertEqual(401, status)
        self.assertNotIn("point", body)
        self.assertEqual(401, self.request(token="é" * 64)[0])

    def test_source_binding_and_integer_point_return_without_private_raw_text(self):
        status, body = self.request()
        self.assertEqual(200, status)
        self.assertEqual("frame-1", body["capture_id"])
        self.assertEqual(self.payload["image_sha256"], body["image_sha256"])
        self.assertEqual((20, 30), (body["x"], body["y"]))
        self.assertNotIn("raw_output", body)
        self.assertNotIn("confidence", body)

    def test_oversize_and_unknown_fields_reject_before_inference(self):
        self.assertEqual(413, self.request(headers={"Content-Length": "99999999"})[0])
        self.assertEqual(400, self.request({**self.payload, "model": "unreviewed"})[0])

    def test_busy_and_timeout_are_not_fabricated_not_found(self):
        self.assertEqual(429, self.request({**self.payload, "target": "busy"})[0])
        self.assertEqual(504, self.request({**self.payload, "target": "timeout"})[0])

    def test_malformed_content_lengths_are_rejected_without_handler_exception(self):
        for length in ["²", "1" * 5000, "-1"]:
            with self.subTest(length=length[:20]):
                self.assertEqual(400, self.request(headers={"Content-Length": length})[0])

    def test_partial_headers_cannot_hold_connections_past_absolute_deadline(self):
        self.server.read_timeout = .25
        connection = socket.create_connection(self.server.server_address, timeout=2)
        try:
            connection.sendall(b"POST /v1/ground HTTP/1.0\r\nX-Test: ")
            for _ in range(3):
                time.sleep(.1)
                try:
                    connection.sendall(b"a")
                except OSError:
                    break
            # Trickle traffic resets socket inactivity timeouts, but must not
            # reset the request's overall read deadline.
            try:
                self.assertEqual(b"", connection.recv(1))
            except (ConnectionAbortedError, ConnectionResetError):
                pass  # Windows reports shutdown as a reset rather than EOF.
        finally:
            connection.close()

    def test_crop_refinement_returns_original_capture_hash_and_size(self):
        class TwoPass:
            def __init__(self): self.calls=0
            def infer(self,**kwargs):
                self.calls+=1
                return dict(status="point",point=(90,90) if self.calls==1 else (20,30),reason="point",model="test",revision="pin",latency_ms=1)
        class RefinedBackend:
            spec={"repo":"test","revision":"pin"}
            def infer(self,png,target,width,height,cancelled):
                return ground(TwoPass(),png,target,width,height,refine=True)
        self.server.backend=RefinedBackend()
        status,body=self.request()
        self.assertEqual(200,status)
        self.assertEqual((60,65),(body["x"],body["y"]))
        self.assertEqual((100,100),(body["width"],body["height"]))
        self.assertEqual(self.payload["capture_id"],body["capture_id"])
        self.assertEqual(self.payload["image_sha256"],body["image_sha256"])
        self.assertEqual([50,50,100,100],body["refinement"]["crop_rect"])
        self.assertNotIn("image_base64",str(body["refinement"]))

    def test_disconnected_client_cancels_inference_without_delivering_a_result(self):
        started=threading.Event();stopped=threading.Event()
        class CancelBackend:
            spec={"repo":"test","revision":"pin"}
            def infer(self,cancelled,**kwargs):
                started.set()
                deadline=time.monotonic()+2
                while time.monotonic()<deadline:
                    if cancelled():
                        stopped.set()
                        raise CancelledError()
                    time.sleep(.01)
                raise AssertionError("disconnect was not observed")
        self.server.backend=CancelBackend()
        connection=socket.create_connection(self.server.server_address,timeout=2)
        body=json.dumps(self.payload).encode()
        headers=("POST /v1/ground HTTP/1.0\r\nAuthorization: Bearer "+"t"*64+"\r\nContent-Type: application/json\r\nContent-Length: "+str(len(body))+"\r\n\r\n").encode()
        connection.sendall(headers+body)
        self.assertTrue(started.wait(1))
        connection.close()
        self.assertTrue(stopped.wait(1))


if __name__ == "__main__":
    unittest.main()
