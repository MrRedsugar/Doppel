"""Authenticated, bounded local screenshot grounding. No action execution endpoints."""
import argparse
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import secrets
import select
import socket
import threading

from inference import BusyError, CancelledError, ModelProcess
from models import MODELS
from protocol import InputError, MAX_BODY, decode_request, unique_json
from process_guard import install_process_guard


class GroundServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 4

    def __init__(self, address, token, backend, read_timeout=10):
        self.token = token
        self.backend = backend
        self.read_timeout = read_timeout
        self.connections = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)

    def get_request(self):
        request, address = super().get_request()
        request.settimeout(self.read_timeout)
        return request, address

    def process_request(self, request, client_address):
        if not self.connections.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.0 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connections.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "DoppelGrounding/1"
    sys_version = ""
    protocol_version = "HTTP/1.0"

    def setup(self):
        super().setup()
        # A socket timeout alone restarts after every received fragment. The
        # absolute deadline also bounds a client that trickles headers/body.
        self.read_deadline = threading.Timer(self.server.read_timeout, self.expire_read)
        self.read_deadline.daemon = True
        self.read_deadline.start()

    def expire_read(self):
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def finish(self):
        self.read_deadline.cancel()
        super().finish()

    def handle(self):
        try:
            super().handle()
        except (ConnectionError, socket.timeout):
            pass  # Disconnected or deadline-expired clients have no response channel.

    def log_message(self, *_):
        pass  # Never log HTTP headers, targets or screenshot payloads.

    def reply(self, status, body):
        raw = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        values = self.headers.get_all("Authorization", [])
        valid = len(values) == 1 and len(values[0]) <= 128 and values[0].isascii() and hmac.compare_digest(values[0], "Bearer " + self.server.token)
        if not valid:
            self.reply(401, {"error": "unauthorized"})
        return valid

    def do_GET(self):
        self.read_deadline.cancel()
        if not self.authorized():
            return
        if self.path != "/health":
            self.reply(404, {"error": "not_found"})
            return
        process = getattr(self.server.backend, "process", None)
        worker_alive = process is None or process.is_alive()
        ready = worker_alive or getattr(self.server.backend, "recoverable", False)
        self.reply(200 if ready else 503, {"ready": ready, "model": self.server.backend.spec["repo"],
                                           "revision": self.server.backend.spec["revision"],
                                           "worker_alive": worker_alive, "refine": getattr(self.server.backend,"refine",False)})

    def disconnected(self):
        try:
            readable, _, _ = select.select([self.connection], [], [], 0)
            return bool(readable) and self.connection.recv(1, socket.MSG_PEEK) == b""
        except OSError:
            return True

    def do_POST(self):
        if not self.authorized():
            return
        if self.path != "/v1/ground":
            self.reply(404, {"error": "not_found"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or len(lengths[0]) > 8 or not lengths[0].isascii() or not lengths[0].isdigit():
            self.reply(400, {"error": "content_length_required"})
            return
        length = int(lengths[0])
        if not 0 < length <= MAX_BODY:
            self.reply(413, {"error": "body_too_large"})
            return
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip() != "application/json":
            self.reply(415, {"error": "json_required"})
            return
        try:
            raw = self.rfile.read(length)
            self.read_deadline.cancel()
            if len(raw) != length:
                raise InputError("incomplete_body")
            request = decode_request(unique_json(raw.decode("utf-8")))
        except (ValueError, UnicodeError, socket.timeout):
            self.reply(400, {"error": "invalid_request"})
            return
        try:
            result = self.server.backend.infer(png=request.png, target=request.target,
                                               width=request.width, height=request.height, cancelled=self.disconnected)
            if self.disconnected():
                return
            if result.get("reason") == "invalid_output":
                self.reply(422, {"error": "invalid_model_output"})
                return
            response = {"capture_id": request.capture_id, "image_sha256": request.image_sha256,
                        "width": request.width, "height": request.height,
                        "status": result["status"], "model": result["model"],
                        "revision": result["revision"], "latency_ms": result["latency_ms"]}
            if result["point"] is not None:
                x, y = result["point"]
                if type(x) is not int or type(y) is not int or not 0 <= x < request.width or not 0 <= y < request.height:
                    raise RuntimeError("invalid_worker_point")
                response.update(x=x, y=y)
            detail = result.get("refinement")
            if isinstance(detail, dict):
                # Generated by the trusted crop adapter; do not copy arbitrary worker fields or raw text.
                response["refinement"] = {key: detail[key] for key in (
                    "enabled", "passes", "coordinate_space", "coarse_point", "crop_rect", "scale", "derived_sha256", "refined_point") if key in detail}
            self.reply(200, response)
        except CancelledError:
            return  # The disconnected caller cannot receive a point or trigger a replay.
        except BusyError:
            self.reply(429, {"error": "busy"})
        except TimeoutError:
            self.reply(504, {"error": "inference_timeout"})
        except Exception:
            self.reply(503, {"error": "worker_unavailable"})
        finally:
            request.image.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=MODELS, default="gui-owl-2b")
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--allow-lan", action="store_true")
    parser.add_argument("--port", type=int, default=8791)
    parser.add_argument("--timeout", type=int, default=45)
    parser.add_argument("--max-pixels", type=int, default=1048576)
    parser.add_argument("--no-refine", action="store_true", help="Use only the coarse pass (default: one additional 2x crop refinement)")
    args = parser.parse_args()
    address = ipaddress.ip_address(args.host)
    if address.version != 4 or address.is_unspecified or not (address.is_loopback or args.allow_lan and address.is_private):
        parser.error("Select one loopback IPv4 address, or explicitly allow one private LAN IPv4 address")
    if not 1024 <= args.port <= 65535 or not 5 <= args.timeout <= 60 or not 65536 <= args.max_pixels <= 2097152:
        parser.error("Invalid bounded server parameters")
    args.token_file.parent.mkdir(parents=True, exist_ok=True)
    if not args.token_file.exists():
        with args.token_file.open("x", encoding="ascii") as stream:
            stream.write(secrets.token_hex(32))
    token = args.token_file.read_text(encoding="ascii").strip()
    if len(token) != 64 or any(c not in "0123456789abcdef" for c in token):
        parser.error("Token file must contain a separately generated 32-byte hex token")
    install_process_guard()
    # Reserve the endpoint before allocating GPU memory. A second same-port launch
    # must fail immediately, including while the first model is still loading.
    server = GroundServer((args.host, args.port), token, None)
    backend = None
    try:
        backend = ModelProcess(args.model, args.weights.resolve(), args.max_pixels, args.timeout, refine=not args.no_refine)
        server.backend = backend
        print(json.dumps({"ready": True, "host": args.host, "port": args.port, "model": backend.spec["repo"],
                          "revision": backend.spec["revision"], "token_file": str(args.token_file.resolve()),
                          "load_ms": backend.load_ms, "refine": backend.refine}), flush=True)
        server.serve_forever()
    finally:
        server.server_close()
        if backend is not None:
            backend.close()


if __name__ == "__main__":
    main()
