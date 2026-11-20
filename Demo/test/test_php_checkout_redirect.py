"""Verify PHP preserves the upstream Checkout URL without real orders."""
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
import unittest
import urllib.request

CHECKOUT = ('https://cashier.example/checkout/1949695024925671424'
            '?x-digitalshield-temp-token=demo.token.value&extra=a%2Fb&extra=second')

class Upstream(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        body = json.dumps({'code': 0, 'data': {'checkoutUrl': CHECKOUT}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None

class CheckoutRedirectTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('php'), 'PHP required')
    def test_preserves_complete_upstream_url(self):
        with http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream) as upstream:
            thread = threading.Thread(target=upstream.serve_forever, daemon=True)
            thread.start()
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            env = dict(os.environ, MERCHANT_NO='DSM001', API_SECRET='demo-secret',
                       DSPAY_BASE_URL=f'http://127.0.0.1:{upstream.server_port}')
            env.pop('PUBLIC_BASE_URL', None)
            directory = Path(__file__).resolve().parents[1] / 'backend/php'
            process = subprocess.Popen(['php', '-S', f'127.0.0.1:{port}', 'server.php'],
                                       cwd=directory, env=env, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL)
            try:
                for _ in range(100):
                    try:
                        with socket.create_connection(('127.0.0.1', port), timeout=.1):
                            break
                    except OSError:
                        time.sleep(.05)
                opener = urllib.request.build_opener(NoRedirect)
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    opener.open(f'http://127.0.0.1:{port}/create?payAmount=0.01&outOrderNo=REDIRECT-TEST', timeout=10)
                self.assertEqual(caught.exception.code, 302)
                self.assertEqual(caught.exception.headers['Location'], CHECKOUT)
                caught.exception.close()
            finally:
                process.terminate()
                process.wait(timeout=5)
                upstream.shutdown()
                thread.join(timeout=5)

if __name__ == '__main__':
    unittest.main()
