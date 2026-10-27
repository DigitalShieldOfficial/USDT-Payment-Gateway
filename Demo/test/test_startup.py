"""Cross-language startup acceptance tests. Requires Java, Node.js, PHP, and Python."""

import json
import hmac
import hashlib
import importlib.util
import os
import pty
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from socketserver import TCPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, HTTPRedirectHandler, build_opener

ROOT = Path(__file__).resolve().parents[2]
LANGUAGES = ("java", "nodejs", "php", "python")


class LocalServer(ThreadingHTTPServer):
    def server_bind(self):
        TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class StartupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        missing = [name for name in ("java", "javac", "node", "php", "bash") if not shutil.which(name)]
        if missing:
            raise unittest.SkipTest("Install runtimes before cross-language tests: " + ", ".join(missing))
        cls.directory = tempfile.TemporaryDirectory(prefix="dspay-startup-acceptance-")
        cls.demo = Path(cls.directory.name) / "Demo"
        shutil.copytree(ROOT / "Demo/front-end", cls.demo / "front-end")
        for language in LANGUAGES:
            shutil.copytree(ROOT / "Demo/back-end" / language, cls.demo / "back-end" / language,
                            ignore=shutil.ignore_patterns("logs", "server.pid", "build", "node_modules",
                                                         "__pycache__", ".env*"))
        cls.orders = []
        cls.api_headers = []

        class MockDSPay(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                cls.api_headers.append(dict(self.headers))
                cls.orders.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                raw = json.dumps({"code": 0, "data": {"checkoutUrl": "https://checkout.example/checkout/1949695024925671424"}}).encode()
                mode = cls.orders[-1].get("outOrderNo")
                if mode == 'RESET':
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                if mode == 'SLOW': time.sleep(11)
                cases = {
                    'BUSINESS': b'{"code":50100,"data":{"checkoutUrl":"https://should-not-redirect.example"}}',
                    'MISSING-CODE': b'{"data":{}}', 'HEADER-ONLY': b'{"header":{"resultCode":0},"data":{}}',
                    'STRING-CODE': b'{"code":"0","data":{}}', 'FLOAT-CODE': b'{"code":0.0,"data":{}}',
                    'MISSING-DATA': b'{"code":0}', 'INVALID-JSON': b'{"code":0,"data":{},"code":0}',
                    'PRECISION': b'{"code":0,"data":{"orderNo":90071992547409931234567890,"amount":0.12345678901234567890123456789}}',
                    'NO-CHECKOUT': b'{"code":0,"data":{}}'
                }
                raw = cases.get(mode, raw)
                self.send_response({"FAIL": 503, "BLOCKED": 403, "REDIRECT": 307}.get(mode, 200))
                if mode == "REDIRECT": self.send_header("Location", "/dspay/public/order/query")
                self.send_header("CF-Ray", "test-ray-id")
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                try: self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError): pass

        cls.upstream = LocalServer(("127.0.0.1", 0), MockDSPay)
        cls.thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.thread.start()
        cls.opener = build_opener(NoRedirect)

    @classmethod
    def tearDownClass(cls):
        cls.upstream.shutdown()
        cls.upstream.server_close()
        cls.thread.join()
        cls.directory.cleanup()

    def environment(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        env = dict(os.environ, PORT=str(port), MERCHANT_NO="DSM001", API_SECRET="demo-secret",
                   DSPAY_BASE_URL="http://127.0.0.1:" + str(self.upstream.server_port), PYTHON_BIN=sys.executable)
        env.pop("PUBLIC_BASE_URL", None)
        env.pop("FRONT_END_DIR", None)
        return env, port

    def assert_order_urls(self, port, public, headers=None):
        with self.opener.open("http://127.0.0.1:" + str(port) + "/", timeout=5) as response:
            self.assertEqual(response.status, 200)
        before = len(self.orders)
        try:
            self.opener.open(Request("http://127.0.0.1:" + str(port)
                             + "/create?payAmount=0.01&productPrice=0.01&productPriceCurrency=USD&outOrderNo=STARTUP-TEST", headers=headers or {}), timeout=5)
        except HTTPError as error:
            with error:
                self.assertEqual(error.code, 302, error.read().decode("utf-8"))
        else:
            self.fail("Create must redirect to checkoutUrl")
        self.assertEqual(len(self.orders), before + 1)
        order = self.orders[-1]
        self.assertEqual(order["returnUrl"], public + "/payment/return?outOrderNo=STARTUP-TEST")
        self.assertEqual(order["successRedirectUrl"], public + "/payment/success?outOrderNo=STARTUP-TEST")

    def start_script(self, language, env, entered=None):
        path = self.demo / "back-end" / language / "start.sh"
        if entered is None:
            return subprocess.run(["bash", str(path)], env=env, stdin=subprocess.DEVNULL,
                                  capture_output=True, text=True, timeout=40)
        master, slave = pty.openpty()
        try:
            process = subprocess.Popen(["bash", str(path)], env=env, stdin=slave,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            os.close(slave)
            slave = None
            os.write(master, entered)
            try:
                stdout, stderr = process.communicate(timeout=40)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise
            return subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)
        finally:
            os.close(master)
            if slave is not None:
                os.close(slave)

    def stop_script(self, language):
        demo = self.demo / "back-end" / language
        result = subprocess.run(["bash", str(demo / "stop.sh")], capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((demo / "server.pid").exists())

    def test_01_scripts_default_and_public_addresses(self):
        for language in LANGUAGES:
            with self.subTest(language=language):
                env, port = self.environment()
                # Retired configuration must have no effect.
                env["PUBLIC_BASE_URL"] = "https://obsolete.example"
                try:
                    result = self.start_script(language, env)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn("Local page: http://localhost:" + str(port) + "/", result.stdout)
                    self.assertIn("Listening port: " + str(port), result.stdout)
                    self.assertIn("Merchant Portal callback URL: <your public URL>/notify", result.stdout)
                    self.assertNotIn("Demo access URL", result.stdout + result.stderr)
                    self.assertNotIn("demo-secret", result.stdout + result.stderr)
                    self.assert_order_urls(port, "http://127.0.0.1:" + str(port))
                    self.assert_order_urls(port, "https://example.ngrok-free.dev",
                                           {"Host": "example.ngrok-free.dev", "X-Forwarded-Proto": "https"})
                    self.assert_order_urls(port, "https://proxy.example",
                                           {"X-Forwarded-Host": "proxy.example", "X-Forwarded-Proto": "https"})
                    before = len(self.orders)
                    with self.assertRaises(HTTPError) as caught:
                        self.opener.open(Request("http://127.0.0.1:" + str(port) + "/create?payAmount=0.01", headers={"X-Forwarded-Proto": "https,http"}), timeout=5)
                    caught.exception.close()
                    self.assertEqual(len(self.orders), before)
                    base = "http://127.0.0.1:" + str(port)
                    for number in ("QUERY-TEST", "FAIL", "BLOCKED"):
                        try:
                            with self.opener.open(base + "/query?outOrderNo=" + number, timeout=5) as response:
                                response.read()
                        except HTTPError as error:
                            error.close()
                    stamp = int(time.time() * 1000)
                    callback = {"orderNo": "1949695024925671424", "status": "COMPLETED", "timestamp": stamp}
                    canonical = "orderNo=1949695024925671424&status=COMPLETED&timestamp=" + str(stamp)
                    signature = hmac.new(b"demo-secret", canonical.encode(), hashlib.sha256).hexdigest()
                    for signed in (signature, "invalid-signature"):
                        request = Request(base + "/notify", data=json.dumps(callback).encode(),
                                          headers={"Content-Type": "application/json", "X-DSPay-Signature": signed})
                        try:
                            with self.opener.open(request, timeout=5) as response:
                                self.assertEqual(response.status, 200)
                                self.assertEqual(json.load(response)["code"], "SUCCESS")
                        except HTTPError as error:
                            with error:
                                self.assertEqual(signed, "invalid-signature")
                                self.assertEqual(error.code, 401)
                    log = (self.demo / "back-end" / language / "logs/server.log").read_text()
                    if language == "java":
                        self.assertEqual(self.api_headers[-1].get("User-Agent"), "DSPay-Java-Demo/1.0")
                        self.assertEqual(self.api_headers[-1].get("Accept"), "application/json")
                        self.assertIn("CF-Ray=test-ray-id", log)
                    if language == "nodejs":
                        self.assertEqual({key.lower(): value for key, value in self.api_headers[-1].items()}.get("user-agent"), "DSPay-Nodejs-Demo/1.0")
                        self.assertEqual(self.api_headers[-1].get("Accept"), "application/json")
                        self.assertIn('"cfRay":"test-ray-id"', log)
                        self.assertIn("DSPay HTTP 403; CF-Ray: test-ray-id", log)
                    if language == "php":
                        self.assertEqual(self.api_headers[-1].get("User-Agent"), "DSPay-PHP-Demo/1.0")
                        self.assertEqual(self.api_headers[-1].get("Accept"), "application/json")
                        self.assertIn('"cfRay":"test-ray-id"', log)
                        self.assertIn("DSPay HTTP 403; CF-Ray: test-ray-id", log)
                    for marker in ("DEMO_REQUEST", "DEMO_RESPONSE", "DSPAY_REQUEST", "DSPAY_RESPONSE", "CALLBACK_REQUEST", "CALLBACK_VERIFIED"):
                        self.assertIn("[" + marker + "]", log)
                    for detail in ("QUERY-TEST", "COMPLETED", "503", "401", "REDACTED", "checkout.example"):
                        self.assertIn(detail, log)
                    self.assertNotIn("demo-secret", log)
                    self.assertNotIn(signature, log)
                    self.assertNotIn(self.orders[0]["signature"], log)
                finally:
                    self.stop_script(language)

    def test_02_explicit_invalid_configuration_is_not_defaulted(self):
        for language in LANGUAGES:
            for field, value in [("PORT", ""), ("PORT", "65536")]:
                with self.subTest(language=language, field=field, value=repr(value)):
                    env, _ = self.environment()
                    env[field] = value
                    result = self.start_script(language, env)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(field, result.stdout + result.stderr)
                    self.assertNotIn("Server started successfully", result.stdout)
                    self.assertFalse((self.demo / "back-end" / language / "server.pid").exists())

    def test_03_direct_start_uses_the_listening_port(self):
        for language in LANGUAGES:
            with self.subTest(language=language):
                demo = self.demo / "back-end" / language
                env, port = self.environment()
                if language == "java":
                    (demo / "build").mkdir(exist_ok=True)
                    subprocess.run(["javac", "-d", "build", "src/DspayMockMerchant.java"], cwd=demo, check=True)
                    command = ["java", "-DmerchantNo=DSM001", "-cp", "build", "DspayMockMerchant"]
                elif language == "nodejs":
                    command = ["node", "src/server.js"]
                elif language == "php":
                    command = ["php", "-S", "127.0.0.1:" + str(port), "server.php"]
                else:
                    command = [sys.executable, "-B", "-u", "src/server.py"]
                process = subprocess.Popen(command, cwd=demo, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    deadline = time.monotonic() + 10
                    while True:
                        try:
                            with self.opener.open("http://127.0.0.1:" + str(port) + "/", timeout=0.5):
                                break
                        except (URLError, OSError):
                            if process.poll() is not None or time.monotonic() > deadline:
                                self.fail("Direct server did not start: " + language)
                            time.sleep(0.05)
                    self.assert_order_urls(port, "http://127.0.0.1:" + str(port))
                finally:
                    process.terminate()
                    process.communicate(timeout=5)

    def test_04_consistent_inputs_signatures_callbacks_and_api_errors(self):
        spec = importlib.util.spec_from_file_location('reference_signer', ROOT / 'Demo/back-end/python/src/signer.py')
        reference = importlib.util.module_from_spec(spec); spec.loader.exec_module(reference)
        for language in LANGUAGES:
            with self.subTest(language=language):
                env, port = self.environment()
                demo = self.demo / 'back-end' / language
                base = 'http://127.0.0.1:' + str(port)
                def request(path, body=None, signature=None, timeout=15):
                    headers = {'Content-Type': 'application/json'} if body is not None else {}
                    if signature is not None: headers['X-DSPay-Signature'] = signature
                    try: response = self.opener.open(Request(base + path, data=body, headers=headers), timeout=timeout)
                    except HTTPError as error: response = error
                    with response: return response.status, response.read()
                try:
                    started = self.start_script(language, env)
                    self.assertEqual(started.returncode, 0, started.stdout + started.stderr)
                    invalid = ['/create', '/create?payAmount=', '/create?payAmount=0', '/create?payAmount=0.001',
                               '/create?payAmount=01.00', '/create?payAmount=1e-2', '/create?payAmount=%200.01',
                               '/create?payAmount=0.01%20', '/create?payAmount=1000000000000',
                               '/create?payAmount=0.01&outOrderNo=', '/create?payAmount=0.01&outOrderNo=%20ORDER',
                               '/create?payAmount=0.01&payAmount=0.02', '/create?payAmount=0.01&unknown=1',
                               '/create?payAmount=0.01&productPriceCurrency=usd', '/create?payAmount=0.01&productPrice=',
                               '/create?payAmount=0.01&productPrice=0.0000001', '/create?payAmount=0.01&productId=' + 'a'*65,
                               '/query', '/query?orderNo=', '/query?orderNo=invalid', '/query?outOrderNo=%20ORDER',
                               '/query?outOrderNo=A&outOrderNo=B', '/query?outOrderNo=A&unknown=1', '/payment/success', '/payment/success?outOrderNo=']
                    for path in invalid:
                        before = len(self.orders)
                        status, raw = request(path)
                        self.assertEqual(status, 400, language + ': ' + path + ': ' + raw.decode())
                        self.assertEqual(json.loads(raw)['code'], 'PARAM_ERROR')
                        self.assertEqual(len(self.orders), before, 'Invalid input must not call DSPay')
                    for tail in ['', '&productId=', '&productId=%20SKU%20', '&productPrice=1.230000', '&productId=%C2%85', '&productId=%EF%BB%BF']:
                        status, raw = request('/create?payAmount=0.01&outOrderNo=VALID' + tail)
                        self.assertEqual(status, 302, raw.decode())
                        order = self.orders[-1]
                        self.assertEqual(order['signature'], reference.sign_create(order, 'demo-secret')[1])
                        if not tail:
                            self.assertNotIn('productPrice', order)
                            self.assertNotIn('productId', order)
                        if 'SKU' in tail: self.assertEqual(order['productId'], ' SKU ')
                    status, raw = request('/query?orderNo=&outOrderNo=VALID')
                    self.assertEqual(status, 200)
                    self.assertEqual(self.orders[-1]['signature'], reference.sign_query(self.orders[-1], 'demo-secret')[1])
                    for mode in ['BUSINESS', 'MISSING-CODE', 'HEADER-ONLY', 'STRING-CODE', 'FLOAT-CODE', 'MISSING-DATA', 'INVALID-JSON', 'FAIL', 'BLOCKED', 'REDIRECT']:
                        before = len(self.orders)
                        status, raw = request('/query?outOrderNo=' + mode)
                        self.assertEqual(len(self.orders), before + 1, 'Must not follow redirects or retry automatically')
                        self.assertEqual(status, 502, language + ': ' + mode + ': ' + raw.decode())
                        self.assertEqual(json.loads(raw)['code'], 'DSPAY_ERROR')
                    self.assertEqual(request('/create?payAmount=0.01&outOrderNo=NO-CHECKOUT')[0], 502)
                    status, raw = request('/query?outOrderNo=PRECISION')
                    self.assertEqual(status, 200)
                    self.assertIn(b'90071992547409931234567890', raw)
                    self.assertIn(b'0.12345678901234567890123456789', raw)
                    now = int(time.time()*1000)
                    precision = '{"timestamp":' + str(now) + ',"payAmount":1.2300,"note":" keep spaces ","attach":{"z":90071992547409931234567890,"a":0.12345678901234567890123456789,"tiny":1e-30,"list":[{},[],null,-0.0],"unicode":"中文","\\ud800\\udc00":1,"\\ue000":2,"lines":"\\u2028"}}'
                    good = [precision, '{"timestamp":' + str(now) + ',"status":"COMPLETED"}',
                            '{"timestamp":' + str(now-299000) + ',"status":"COMPLETED"}',
                            '{"timestamp":' + str(now+299000) + ',"status":"COMPLETED"}']
                    bad = ['{"status":"COMPLETED"}', '{"timestamp":' + str(now-600000) + '}',
                           '{"timestamp":' + str(now+600000) + '}', '{"timestamp":"' + str(now) + '"}',
                           '{"timestamp":' + str(now) + '.0}', '{"timestamp":' + str(now) + ',"timestamp":' + str(now) + '}',
                           '{"timestamp":' + str(now) + '} trailing', '{"timestamp":' + str(now) + ',"attach":{"a":1,"a":2}}']
                    for raw in good:
                        signature = reference.digest(reference.canonical_fields(reference.parse_json(raw)), 'demo-secret')
                        self.assertEqual(request('/notify', raw.encode(), signature)[0], 200, language + ': ' + raw[:100])
                    for raw in bad:
                        try: signature = reference.digest(reference.canonical_fields(reference.parse_json(raw)), 'demo-secret')
                        except ValueError: signature = '0'*64
                        self.assertEqual(request('/notify', raw.encode(), signature)[0], 401, language + ': ' + raw[:100])
                    self.assertEqual(request('/notify', precision.encode(), '0'*64)[0], 401)
                    self.assertEqual(request('/notify', b'\xff', '0'*64)[0], 400)
                    # More than 1 MiB is accepted when the complete signed callback is valid.
                    large = json.dumps({'timestamp': int(time.time()*1000), 'padding': 'x'*(1024*1024+1)}, separators=(',', ':'))
                    signature = reference.digest(reference.canonical_fields(reference.parse_json(large)), 'demo-secret')
                    self.assertEqual(request('/notify', large.encode(), signature)[0], 200, language)
                    started_at = time.monotonic()
                    status, raw = request('/query?outOrderNo=SLOW')
                    elapsed = time.monotonic() - started_at
                    self.assertEqual(status, 502, raw.decode())
                    self.assertTrue(raw.startswith(b'{'), raw.decode())
                    self.assertEqual(json.loads(raw)['code'], 'DSPAY_ERROR')
                    if language == 'nodejs':
                        self.assertEqual(json.loads(raw)['message'], 'DSPay request timed out after 10 seconds')
                    self.assertGreater(elapsed, 8)
                    self.assertLess(elapsed, 13)
                finally:
                    self.stop_script(language)

    def test_05_node_network_error_retains_underlying_cause(self):
        env, port = self.environment()
        try:
            started = self.start_script('nodejs', env)
            self.assertEqual(started.returncode, 0, started.stdout + started.stderr)
            with self.assertRaises(HTTPError) as caught:
                self.opener.open('http://127.0.0.1:' + str(port) + '/query?outOrderNo=RESET', timeout=5)
            with caught.exception as response:
                self.assertEqual(response.code, 502)
                body = json.load(response)
            self.assertEqual(body['code'], 'DSPAY_ERROR')
            self.assertIn('fetch failed', body['message'])
            self.assertRegex(body['message'], r'UND_ERR_SOCKET|ECONNRESET')
            log = (self.demo / 'back-end/nodejs/logs/server.log').read_text()
            self.assertIn('"cause":', log)
            self.assertRegex(log, r'UND_ERR_SOCKET|ECONNRESET')
            self.assertNotIn('demo-secret', log)
        finally:
            self.stop_script('nodejs')

    def test_06_java_frontend_directory_configuration(self):
        demo = self.demo / 'back-end/java'
        custom = self.demo / 'custom front end'
        custom.mkdir(exist_ok=True)
        page = b'<!doctype html><title>Configured Java storefront</title>'
        (custom / 'index.html').write_bytes(page)
        for directory in (str(custom), '../../custom front end'):
            with self.subTest(startup='script', directory=directory):
                env, port = self.environment()
                env['FRONT_END_DIR'] = directory
                try:
                    started = self.start_script('java', env)
                    self.assertEqual(started.returncode, 0, started.stdout + started.stderr)
                    with self.opener.open('http://127.0.0.1:' + str(port) + '/', timeout=5) as response:
                        self.assertEqual(response.read(), page)
                finally:
                    self.stop_script('java')
        for override in (False, True):
            with self.subTest(startup='direct', property_override=override):
                env, port = self.environment()
                env['FRONT_END_DIR'] = str(custom) if not override else str(custom / 'missing')
                command = ['java', '-DmerchantNo=DSM001']
                if override: command.append('-DfrontEndDir=' + str(custom))
                command += ['-cp', 'build', 'DspayMockMerchant']
                process = subprocess.Popen(command, cwd=demo, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    deadline = time.monotonic() + 5
                    while True:
                        try:
                            with self.opener.open('http://127.0.0.1:' + str(port) + '/', timeout=0.5) as response:
                                self.assertEqual(response.read(), page)
                            break
                        except (URLError, OSError):
                            if process.poll() is not None or time.monotonic() > deadline:
                                self.fail('Java did not serve the configured directory')
                            time.sleep(0.05)
                finally:
                    process.terminate()
                    process.communicate(timeout=5)
        for invalid in ('', str(custom / 'missing')):
            with self.subTest(invalid=invalid):
                env, _ = self.environment()
                env['FRONT_END_DIR'] = invalid
                started = self.start_script('java', env)
                self.assertNotEqual(started.returncode, 0)
                self.assertIn('FRONT_END_DIR', started.stdout + started.stderr)
                self.assertNotIn('Server started successfully', started.stdout)
                self.assertFalse((demo / 'server.pid').exists())


if __name__ == "__main__":
    unittest.main()
