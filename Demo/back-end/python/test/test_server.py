import hashlib
import hmac
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
from server import Config, DemoHTTPServer, NoRedirect, handler_for, origin


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.calls = []
        cls.user_agents = []
        cls.mode = "success"

        class MockDSPay(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                cls.calls.append((self.path, body))
                cls.user_agents.append(self.headers.get("User-Agent"))
                if cls.mode == "redirect":
                    self.send_response(307)
                    self.send_header("Location", "/must-not-follow")
                    self.end_headers()
                    return
                self.send_response(403 if cls.mode == "gateway_block" else 500 if cls.mode == "http_error" else 200)
                if cls.mode == "gateway_block":
                    self.send_header("CF-Ray", "test-ray-LAX")
                self.end_headers()
                if cls.mode == "invalid_json":
                    self.wfile.write(b"not-json")
                    return
                if cls.mode == "missing_code":
                    result = {"data": {"checkoutUrl": "https://cashier.example/checkout/123"}}
                elif cls.mode == "business_error":
                    result = {"code": 50613, "data": None}
                elif cls.mode == "missing_checkout":
                    result = {"code": 0, "data": {}}
                else:
                    data = {"checkoutUrl": "https://cashier.example/checkout/1949695024925671424"} if self.path.endswith("create") else [{"status": "COMPLETED", "outOrderNo": body.get("outOrderNo")}]
                    result = {"code": 0, "data": data}
                self.wfile.write(json.dumps(result).encode())

        cls.upstream = DemoHTTPServer(("127.0.0.1", 0), MockDSPay)
        cls.upstream_thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.upstream_thread.start()
        cls.config = Config("http://127.0.0.1:" + str(cls.upstream.server_port),
                            "DSM001", "demo-secret")
        cls.server = DemoHTTPServer(("127.0.0.1", 0), handler_for(cls.config))
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)
        cls.opener = build_opener(NoRedirect)

    @classmethod
    def tearDownClass(cls):
        for server, thread in [(cls.server, cls.server_thread), (cls.upstream, cls.upstream_thread)]:
            server.shutdown()
            server.server_close()
            thread.join()

    def setUp(self):
        type(self).mode = "success"
        self.calls.clear()
        self.user_agents.clear()

    def request(self, path, body=None, headers=None):
        request = Request(self.base + path, data=body, headers=headers or {})
        try:
            response = self.opener.open(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.code, response.headers, response.read()

    def test_shared_storefront_and_path_traversal(self):
        status, _, body = self.request("/")
        self.assertEqual(status, 200)
        self.assertTrue(body.startswith(b"<!doctype html>"))
        for path in ["/../../../README.md", "/%2e%2e/%2e%2e/README.md", "/missing.html"]:
            self.assertEqual(self.request(path)[0], 404)

    def test_create_signs_original_values_and_redirects(self):
        status, headers, _ = self.request("/create?payAmount=0.02&productPrice=0.02&productPriceCurrency=USD&outOrderNo=ORDER-1&productId=%20item%20")
        self.assertEqual(status, 302)
        self.assertEqual(headers["Location"], "https://cashier.example/checkout/1949695024925671424")
        path, body = self.calls[0]
        self.assertEqual(path, "/dspay/public/order/create")
        self.assertEqual(body["payAmount"], "0.02")
        self.assertEqual(body["productId"], " item ")
        canonical = ('allowedPaymentMethods=&attach={"customerId":"CUST-1001","demo":"python"}'
                     '&merchantNo=DSM001&outOrderNo=ORDER-1&payAmount=0.02&productId= item '
                     '&productPrice=0.02&productPriceCurrency=USD'
                     f'&returnUrl={self.base}/payment/return?outOrderNo=ORDER-1'
                     f'&successRedirectUrl={self.base}/payment/success?outOrderNo=ORDER-1'
                     '&timestamp=' + str(body["timestamp"]))
        expected = hmac.new(b"demo-secret", canonical.encode(), hashlib.sha256).hexdigest()
        self.assertEqual(body["signature"], expected)

    def test_shared_frontend_create_url(self):
        html = (self.config.front_end / "index.html").read_text()
        path = re.search(r'const CREATE_ORDER_PATH = "([^"]+)"', html).group(1)
        status, _, _ = self.request(path + "&payAmount=0.01&productPrice=0.01&outOrderNo=ORDER-FRONTEND")
        self.assertEqual(status, 302)
        body = self.calls[0][1]
        self.assertEqual(body["productPriceCurrency"], "USD")
        self.assertEqual(body["productId"], "DIGITAL-SHIELD-LIFETIME-001")

    def test_create_and_query_identify_the_demo_client(self):
        self.assertEqual(self.request("/create?payAmount=0.01&outOrderNo=ORDER-UA")[0], 302)
        self.assertEqual(self.request("/query?outOrderNo=ORDER-UA")[0], 200)
        self.assertEqual(self.user_agents, ["Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"])

    def test_create_generates_fresh_order_numbers_only_when_absent(self):
        for _ in range(2):
            self.assertEqual(self.request("/create?payAmount=0.01")[0], 302)
        self.assertNotEqual(self.calls[0][1]["outOrderNo"], self.calls[1][1]["outOrderNo"])
        count = len(self.calls)
        for query in ["payAmount=0.01&outOrderNo=", "payAmount=%200.01", "payAmount=1e-2",
                      "payAmount=0.001", "payAmount=0.00", "payAmount=01.00", "payAmount=0.01&payAmount=1",
                      "outOrderNo=ORDER-1", "payAmount=0.01&outOrderNo=%20ORDER-1", "payAmount=0.01&unknown=1",
                      "payAmount=0.01&productPriceCurrency=%20USD", "payAmount=0.01&productPriceCurrency=EUR"]:
            status, _, raw = self.request("/create?" + query)
            self.assertEqual(status, 400, query)
            self.assertEqual(json.loads(raw)["code"], "PARAM_ERROR")
        self.assertEqual(len(self.calls), count)

    def test_query_and_redirects(self):
        status, _, raw = self.request("/query?orderNo=&outOrderNo=ORDER-1")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(raw)[0]["status"], "COMPLETED")
        body = self.calls[0][1]
        canonical = "merchantNo=DSM001&orderNo=&outOrderNo=ORDER-1&timestamp=" + str(body["timestamp"])
        self.assertEqual(body["signature"], hmac.new(b"demo-secret", canonical.encode(), hashlib.sha256).hexdigest())
        for query in ["", "orderNo=", "outOrderNo=%20ORDER-1", "orderNo=bad", "outOrderNo=A&outOrderNo=B"]:
            self.assertEqual(self.request("/query?" + query)[0], 400, query)
        self.assertEqual(self.request("/payment/return")[1]["Location"], "/")
        self.assertEqual(self.request("/payment/success?outOrderNo=ORDER-1")[1]["Location"], "/query?outOrderNo=ORDER-1")

    def test_upstream_failures_do_not_become_success_or_follow_redirects(self):
        for mode in ["http_error", "gateway_block", "business_error", "missing_code", "invalid_json", "missing_checkout", "redirect"]:
            type(self).mode = mode
            self.calls.clear()
            status, _, raw = self.request("/create?payAmount=0.01")
            self.assertEqual(status, 502, mode)
            self.assertEqual(json.loads(raw)["code"], "DSPAY_ERROR")
            if mode == "gateway_block":
                self.assertEqual(json.loads(raw)["message"], "DSPay HTTP 403; CF-Ray: test-ray-LAX")
            self.assertEqual(len(self.calls), 1)

    def test_http_callback_ack_and_failure(self):
        now = int(time.time() * 1000)
        raw = json.dumps({"timestamp": now, "status": "COMPLETED", "orderNo": "1949695024925671424"}).encode()
        canonical = "orderNo=1949695024925671424&status=COMPLETED&timestamp=" + str(now)
        signature = hmac.new(b"demo-secret", canonical.encode(), hashlib.sha256).hexdigest()
        status, _, body = self.request("/notify", raw, {"X-DSPay-Signature": signature})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["code"], "SUCCESS")
        self.assertEqual(self.request("/notify", raw, {"X-DSPay-Signature": "0" * 64})[0], 401)
        self.assertEqual(self.request("/notify", raw.replace(b"COMPLETED", b"REFUNDED"), {"X-DSPay-Signature": signature})[0], 401)
        expired = now - 600000
        old_body = json.dumps({"timestamp": expired, "status": "COMPLETED"}).encode()
        old_signature = hmac.new(b"demo-secret", ("status=COMPLETED&timestamp=" + str(expired)).encode(), hashlib.sha256).hexdigest()
        self.assertEqual(self.request("/notify", old_body, {"X-DSPay-Signature": old_signature})[0], 401)
        self.assertEqual(self.request("/notify", b"\xff")[0], 400)

    def test_config_rejects_invalid_original_values(self):
        env = {"DSPAY_BASE_URL": self.config.dspay_base, "PUBLIC_BASE_URL": "http://localhost:3000",
               "MERCHANT_NO": "DSM001", "API_SECRET": "demo-secret"}
        with patch.dict(os.environ, env, clear=True):
            self.assertEqual(Config.from_env().merchant_no, "DSM001")
        without_api = {key: value for key, value in env.items() if key != "DSPAY_BASE_URL"}
        with patch.dict(os.environ, without_api, clear=True):
            self.assertEqual(Config.from_env().dspay_base, "https://pay-api.ds.pro")
        for key, value in [("DSPAY_BASE_URL", self.config.dspay_base + "/"),
                           ("DSPAY_BASE_URL", ""),
                           ("DSPAY_BASE_URL", " http://localhost:3000"),
                           ("MERCHANT_NO", " DSM001"), ("PORT", "03000"),
                           ("PORT", "65536"), ("API_SECRET", ""), ("FRONT_END_DIR", "")]:
            with patch.dict(os.environ, dict(env, **{key: value}), clear=True):
                with self.assertRaises(ValueError, msg=key):
                    Config.from_env()

    def test_url_rejects_invalid_utf8_and_invisible_characters_at_startup(self):
        clean = "https://blurt-graduate-paternity.ngrok-free.dev"
        self.assertEqual(origin(clean, "PUBLIC_BASE_URL"), clean)
        self.assertEqual(origin("https://xn--fsqu00a.xn--0zwm56d", "PUBLIC_BASE_URL"),
                         "https://xn--fsqu00a.xn--0zwm56d")
        invalid_utf8 = os.fsdecode(b"\xef")
        for suffix in [invalid_utf8, "\ufffd", "\u200b", "\ufeff", " ", "\t", "\r\n", "\x7f"]:
            for field in ["DSPAY_BASE_URL"]:
                env = {"PUBLIC_BASE_URL": clean, "MERCHANT_NO": "DSM001", "API_SECRET": "demo-secret",
                       field: clean + suffix}
                with patch.dict(os.environ, env, clear=True):
                    with self.assertRaises(ValueError) as caught:
                        Config.from_env()
                message = str(caught.exception)
                self.assertIn(field, message)
                self.assertIn("ASCII http(s) origin", message)
                message.encode("utf-8")

    def test_background_start_stop_and_configuration_failure(self):
        with tempfile.TemporaryDirectory(prefix="dspay-python-demo-") as directory:
            demo = Path(directory) / "python"
            shutil.copytree(ROOT, demo, ignore=shutil.ignore_patterns("__pycache__", "logs", "server.pid"))
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            env = dict(os.environ, PYTHON_BIN=sys.executable, DSPAY_BASE_URL=self.config.dspay_base,
                       PUBLIC_BASE_URL="http://localhost:" + str(port), MERCHANT_NO="DSM001",
                       API_SECRET="demo-secret", PORT=str(port), FRONT_END_DIR=str(self.config.front_end))
            env.pop("DSPAY_BASE_URL")
            try:
                result = subprocess.run(["bash", str(demo / "start.sh")], env=env, stdin=subprocess.DEVNULL,
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertTrue((demo / "server.pid").is_file())
                self.assertIn("DSPay API: https://pay-api.ds.pro", result.stdout)
                self.assertNotIn("DSPay API base URL (DSPAY_BASE_URL)", result.stdout)
                self.assertIn("DSPay API: https://pay-api.ds.pro", (demo / "logs/server.log").read_text())
                with build_opener().open("http://127.0.0.1:" + str(port), timeout=5) as response:
                    self.assertEqual(response.status, 200)
                again = subprocess.run(["bash", str(demo / "start.sh")], env=env, stdin=subprocess.DEVNULL,
                                       capture_output=True, text=True, timeout=5)
                self.assertNotEqual(again.returncode, 0)
            finally:
                subprocess.run(["bash", str(demo / "stop.sh")], capture_output=True, timeout=5, check=True)
            self.assertFalse((demo / "server.pid").exists())
            bad_env = dict(env, MERCHANT_NO=" DSM001")
            failed = subprocess.run(["bash", str(demo / "start.sh")], env=bad_env, stdin=subprocess.DEVNULL,
                                    capture_output=True, text=True, timeout=15)
            self.assertNotEqual(failed.returncode, 0)
            self.assertIn("MERCHANT_NO", failed.stdout + failed.stderr)
            self.assertFalse((demo / "server.pid").exists())
            # Reproduce a pasted URL containing the exact invalid byte seen in a real startup.
            invalid_env = dict(env, DSPAY_BASE_URL="https://example.com" + os.fsdecode(b"\xef"))
            failed = subprocess.run(["bash", str(demo / "start.sh")], env=invalid_env, stdin=subprocess.DEVNULL,
                                    capture_output=True, text=True, errors="backslashreplace", timeout=15)
            self.assertNotEqual(failed.returncode, 0)
            self.assertIn("DSPAY_BASE_URL must be an ASCII http(s) origin", failed.stdout + failed.stderr)
            self.assertNotIn("Server started successfully", failed.stdout)
            self.assertFalse((demo / "server.pid").exists())


if __name__ == "__main__":
    unittest.main()
