"""Mock merchant: signed DSPay calls, shared storefront, and callback verification."""

import os
import re
import sys
import time
import uuid
from dataclasses import dataclass
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from socketserver import TCPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, unquote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from signer import canonical_json, parse_json, sign_create, sign_query, verify_callback

MAX_RESPONSE = 1024 * 1024
FRONT_END = Path(__file__).resolve().parents[3] / "front-end"


def log_event(event, secret="", **fields):
    # Log copies only; never change business values or expose credentials.
    fields = dict(fields)
    if isinstance(fields.get("body"), str):
        try:
            fields["body"] = parse_json(fields["body"])
        except (ValueError, RecursionError):
            pass
    text = canonical_json(fields)
    text = re.sub(r'("(?:signature|apiSecret|API_SECRET)"\s*:\s*)"(?:\\.|[^"\\])*"', r'\1"[REDACTED]"', text, flags=re.I)
    if secret:
        text = text.replace(canonical_json(secret)[1:-1], "[REDACTED]")
    print("[" + event + "] " + text, flush=True)


def origin(value, field):
    if not value.isascii():
        raise ValueError(field + " must be an ASCII http(s) origin; re-enter the URL without invalid or invisible characters (internationalized domains must use punycode). The supplied value is not repaired automatically")
    try:
        url = urlsplit(value)
        valid = (url.scheme in ("http", "https") and url.hostname and url.port != 0
                 and not url.username and not url.password and not url.path
                 and not url.query and not url.fragment)
    except ValueError:
        valid = False
    if not valid or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in value):
        raise ValueError(field + " must be an ASCII http(s) origin without a trailing slash, path, query, credentials, or whitespace")
    return value


def merchant_no(value):
    if not re.fullmatch(r"(?:DSM|M)[0-9]{1,19}", value):
        raise ValueError("MERCHANT_NO must start with DSM or M followed by 1–19 digits; whitespace is not allowed")
    return value


@dataclass(frozen=True)
class Config:
    dspay_base: str
    merchant_no: str
    api_secret: str
    port: int = 3000
    front_end: Path = FRONT_END

    @classmethod
    def from_env(cls):
        for name in ("MERCHANT_NO", "API_SECRET"):
            if name not in os.environ or os.environ[name] == "":
                raise ValueError(name + " is required")
        port = os.environ.get("PORT", "3000")
        if not re.fullmatch(r"[1-9][0-9]{0,4}", port) or int(port) > 65535:
            raise ValueError("PORT must be an integer from 1 to 65535")
        directory = os.environ.get("FRONT_END_DIR")
        front_end = FRONT_END if directory is None else Path(directory)
        if directory == "" or not (front_end / "index.html").is_file():
            raise ValueError("FRONT_END_DIR must contain index.html")
        return cls(origin(os.environ.get("DSPAY_BASE_URL", "https://pay-api.ds.pro"), "DSPAY_BASE_URL"),
                   merchant_no(os.environ["MERCHANT_NO"]), os.environ["API_SECRET"],
                   int(port), front_end.resolve())


class UpstreamError(Exception):
    pass


class DemoHTTPServer(ThreadingHTTPServer):
    def server_bind(self):
        # The demo does not use a reverse-DNS server name during local startup.
        TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def post(config, path, body):
    log_event("DSPAY_REQUEST", config.api_secret, method="POST", url=config.dspay_base + path, body=body)
    request = Request(config.dspay_base + path, data=canonical_json(body).encode("utf-8"),
                      headers={"Content-Type": "application/json", "Accept": "application/json",
                               "User-Agent": "DSPay-Python-Demo/1.0"},
                      method="POST")
    try:
        with build_opener(NoRedirect).open(request, timeout=10) as response:
            raw = response.read(MAX_RESPONSE + 1)
            log_event("DSPAY_RESPONSE", config.api_secret, path=path, status=response.status, body=raw.decode("utf-8", errors="backslashreplace"))
            if len(raw) > MAX_RESPONSE:
                raise UpstreamError("DSPay response exceeds 1 MiB")
        result = parse_json(raw.decode("utf-8"))
        if not isinstance(result, dict) or type(result.get("code")) is not int:
            raise UpstreamError("DSPay response must contain a top-level integer code")
        if result["code"] != 0:
            raise UpstreamError("DSPay business error: " + str(result["code"]))
        if "data" not in result:
            raise UpstreamError("DSPay response is missing data")
        return result["data"]
    except HTTPError as error:
        log_event("DSPAY_RESPONSE", config.api_secret, path=path, status=error.code, body=error.read(MAX_RESPONSE + 1).decode("utf-8", errors="backslashreplace"))
        ray = error.headers.get("CF-Ray")
        error.close()
        raise UpstreamError("DSPay HTTP " + str(error.code) + ("; CF-Ray: " + ray if ray else "")) from error
    except (URLError, OSError, ValueError, UnicodeError, RecursionError) as error:
        log_event("DSPAY_ERROR", config.api_secret, path=path, error=str(error))
        raise UpstreamError("DSPay request failed or returned invalid JSON") from error


def order_identifier(value, field):
    pattern = r"[A-Za-z0-9-]{1,64}" if field == "outOrderNo" else r"(?:DS)?[1-9][0-9]{14,18}"
    if not re.fullmatch(pattern, value):
        requirement = "1–64 letters, digits, or hyphens" if field == "outOrderNo" else "15–19 digits with a nonzero first digit, optionally prefixed by DS"
        raise ValueError(field + " must contain " + requirement)
    return value


def amount(value, field, integer_digits, decimal_digits, minimum=None):
    pattern = r"(?:0|[1-9][0-9]{0," + str(integer_digits - 1) + r"})(?:\.[0-9]{1," + str(decimal_digits) + r"})?"
    if not re.fullmatch(pattern, value) or (minimum is not None and Decimal(value) < minimum):
        raise ValueError(field + " must be a plain nonnegative decimal with at most "
                         + str(integer_digits) + " integer and " + str(decimal_digits)
                         + " fractional digits, no leading zeros or whitespace"
                         + ("; minimum " + str(minimum) if minimum is not None else ""))
    return value


def handler_for(config):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, format_string, *args):
            # Structured request/response events are emitted by the handlers.
            pass

        def respond(self, status, body):
            log_event("DEMO_RESPONSE", config.api_secret, path=urlsplit(self.path).path, status=status, body=body)
            raw = canonical_json(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def redirect(self, location):
            log_event("DEMO_RESPONSE", config.api_secret, path=urlsplit(self.path).path, status=302, location=location, body="")
            self.send_response(302)
            self.send_header("Location", location)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def parameters(self, allowed):
            parsed = parse_qs(urlsplit(self.path).query, keep_blank_values=True,
                              encoding="utf-8", errors="strict", max_num_fields=20)
            for key, values in parsed.items():
                if key not in allowed:
                    raise ValueError("Unsupported query field: " + key)
                if len(values) != 1:
                    raise ValueError(key + " must occur exactly once")
            return {key: values[0] for key, values in parsed.items()}

        def request_origin(self):
            local = self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")
            host = self.headers.get("X-Forwarded-Host") if local and "X-Forwarded-Host" in self.headers else self.headers.get("Host")
            proto = self.headers.get("X-Forwarded-Proto") if local and "X-Forwarded-Proto" in self.headers else "http"
            if proto not in ("http", "https"):
                raise ValueError("X-Forwarded-Proto must be http or https")
            return origin(proto + "://" + (host or ""), "Request origin")

        def do_GET(self):
            if urlsplit(self.path).path in ("/create", "/query"):
                log_event("DEMO_REQUEST", config.api_secret, method="GET", target=self.path)
            try:
                path = urlsplit(self.path).path
                if path == "/create":
                    args = self.parameters({"outOrderNo", "payAmount", "productPrice", "productPriceCurrency", "productId"})
                    if "productPriceCurrency" in args and args["productPriceCurrency"] != "USD":
                        raise ValueError("productPriceCurrency must be USD for this storefront")
                    if "payAmount" not in args:
                        raise ValueError("payAmount is required; minimum 0.01")
                    number = order_identifier(args["outOrderNo"], "outOrderNo") if "outOrderNo" in args else "DEMO-" + uuid.uuid4().hex
                    base = self.request_origin()
                    order = {"merchantNo": config.merchant_no, "outOrderNo": number,
                             "payAmount": amount(args["payAmount"], "payAmount", 12, 2, Decimal("0.01")),
                             "attach": {"demo": "python", "customerId": "CUST-1001"},
                             "allowedPaymentMethods": [], "timestamp": int(time.time() * 1000),
                             "returnUrl": base + "/payment/return?" + urlencode({"outOrderNo": number}),
                             "successRedirectUrl": base + "/payment/success?" + urlencode({"outOrderNo": number})}
                    if "productPrice" in args:
                        order["productPrice"] = amount(args["productPrice"], "productPrice", 14, 6)
                        order["productPriceCurrency"] = "USD"
                    if "productPriceCurrency" in args:
                        order["productPriceCurrency"] = args["productPriceCurrency"]
                    if "productId" in args:
                        if len(args["productId"]) > 64:
                            raise ValueError("productId must be at most 64 characters")
                        order["productId"] = args["productId"]
                    order["signature"] = sign_create(order, config.api_secret)[1]
                    result = post(config, "/dspay/public/order/create", order)
                    checkout = result.get("checkoutUrl") if isinstance(result, dict) else None
                    if not isinstance(checkout, str) or not checkout or any(c.isspace() or ord(c) < 32 for c in checkout):
                        raise UpstreamError("DSPay create response must contain a complete checkoutUrl")
                    try:
                        url = urlsplit(checkout)
                    except ValueError as error:
                        raise UpstreamError("DSPay checkoutUrl is invalid") from error
                    if url.scheme not in ("http", "https") or not url.hostname:
                        raise UpstreamError("DSPay checkoutUrl must be an http(s) URL")
                    self.redirect(checkout)
                elif path == "/query":
                    args = self.parameters({"orderNo", "outOrderNo"})
                    if not args.get("orderNo") and not args.get("outOrderNo"):
                        raise ValueError("orderNo or outOrderNo is required")
                    for key, value in args.items():
                        if value != "":
                            order_identifier(value, key)
                    query = dict(args, merchantNo=config.merchant_no, timestamp=int(time.time() * 1000))
                    query["signature"] = sign_query(query, config.api_secret)[1]
                    self.respond(200, post(config, "/dspay/public/order/query", query))
                elif path == "/payment/return":
                    self.redirect("/")
                elif path == "/payment/success":
                    args = self.parameters({"outOrderNo"})
                    if "outOrderNo" not in args:
                        raise ValueError("outOrderNo is required")
                    order_identifier(args["outOrderNo"], "outOrderNo")
                    self.redirect("/query?" + urlencode(args))
                else:
                    relative = "index.html" if path in ("/", "/index.html") else unquote(path, errors="strict").lstrip("/")
                    target = (config.front_end / relative).resolve()
                    if not target.is_relative_to(config.front_end.resolve()) or not target.is_file():
                        self.respond(404, {"code": "NOT_FOUND"})
                        return
                    raw = target.read_bytes()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8" if target.suffix == ".html" else "application/octet-stream")
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
            except (ValueError, UnicodeError) as error:
                self.respond(400, {"code": "PARAM_ERROR", "message": str(error)})
            except UpstreamError as error:
                self.respond(502, {"code": "DSPAY_ERROR", "message": str(error)})

        def do_POST(self):
            if urlsplit(self.path).path != "/notify":
                self.respond(404, {"code": "NOT_FOUND"})
                return
            log_event("DEMO_REQUEST", config.api_secret, method="POST", target=self.path, signature="[REDACTED]" if self.headers.get("X-DSPay-Signature") else "missing")
            size = self.headers.get("Content-Length", "")
            if self.headers.get("Transfer-Encoding") or not re.fullmatch(r"[0-9]+", size):
                self.respond(400, {"code": "FAIL", "msg": "Content-Length is required; chunked bodies are not supported"})
                return
            try:
                raw = self.rfile.read(int(size)).decode("utf-8")
            except UnicodeError:
                self.respond(400, {"code": "FAIL", "msg": "Callback must be UTF-8 JSON"})
                return
            log_event("CALLBACK_REQUEST", config.api_secret, body=raw)
            if not verify_callback(raw, self.headers.get("X-DSPay-Signature"), config.api_secret):
                self.respond(401, {"code": "FAIL", "msg": "Invalid signature, JSON, or timestamp (requires ±5 minutes)"})
                return
            log_event("CALLBACK_VERIFIED", config.api_secret, result="signature and timestamp valid")
            # Demo ACK only. Production must commit idempotent business state first.
            self.respond(200, {"code": "SUCCESS", "msg": "ok"})

    return Handler


def main():
    if sys.version_info < (3, 10):
        print("Python 3.10 or newer is required", file=sys.stderr)
        return 2
    try:
        config = Config.from_env()
        server = DemoHTTPServer(("0.0.0.0", config.port), handler_for(config))
    except (ValueError, OSError) as error:
        print("Startup failed: " + str(error), file=sys.stderr)
        return 2
    print("Mock merchant: " + "http://localhost:" + str(config.port), flush=True)
    print("DSPay API: " + config.dspay_base, flush=True)
    print("Demo page: http://localhost:" + str(config.port) + "/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
