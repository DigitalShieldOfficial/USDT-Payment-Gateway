[English](README.md) | [中文](README.zh-CN.md)

# DSPay Python Mock Merchant

Python 3.10+, zero third-party dependencies. The backend signs and calls `POST /dspay/public/order/create` and `/query`, redirects to the returned `checkoutUrl`, and verifies callbacks over the shared canonical field string.

## Runtime

Use `python3`; no pip install, virtual environment, or framework is required. Local tests are verified on Python `3.12.14` and `3.14.3`.

Startup requires only the merchant number and API secret; no access URL configuration is needed. The default port is `3000`; use `PORT=4000 ./start.sh` to change it. Payment return links use the origin of the create-order request. When using ngrok/nginx, open the public URL to create orders. Register `<public URL>/notify` separately in the Merchant Portal for real webhooks.

For ngrok, run `ngrok http 3000` (match the Demo port). For nginx, proxy to `http://127.0.0.1:3000` and set `proxy_set_header Host $http_host;`, `proxy_set_header X-Forwarded-Proto $scheme;`, and `proxy_set_header X-Forwarded-Host $http_host;` to replace client-supplied forwarded hosts. The Demo trusts proxy headers only from loopback peers. Supply one correct host and protocol, not comma-separated lists, and access the public service through that proxy.

```bash
python3 --version
```

## Run

> Replace `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` with values from the DSPay Merchant Portal. The production API URL is used by default. Keep `apiSecret` on the merchant server; never put it in frontend code, URLs, browser storage, or online tools.

```bash
cd Demo/backend/python
./start.sh
```

The API address defaults to `https://pay-api.ds.pro` in both the script and direct Python startup; no API address prompt is shown. Set `DSPAY_BASE_URL` explicitly only to test another environment. The script prompts for missing `MERCHANT_NO` and `API_SECRET`; secret input is hidden. Values are not saved. Set the variables first for non-interactive use:

```bash
cd Demo/backend/python
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
python3 -B src/server.py
# Background alternative: ./start.sh; stop with ./stop.sh
```

`PORT` defaults to `3000`; `PYTHON_BIN` optionally selects the interpreter for `start.sh`. `FRONT_END_DIR` optionally overrides the shared `Demo/frontend` directory; relative paths are resolved from the Python Demo directory when using `start.sh`. The DSPay API base URL must be complete ASCII HTTP(S) origins (use punycode for internationalized domains) without a trailing slash, path, query, fragment, credentials, or whitespace. Invalid configuration fails with the field and required format; it is not silently repaired. `MERCHANT_NO` must start with `DSM` or `M` followed by 1–19 digits.

The background process writes `server.pid` and `logs/server.log`. Open `http://localhost:3000` to use the shared storefront. For real webhook delivery, expose this port through a public URL and configure the merchant's public `/notify` endpoint in the portal.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| GET | `/` | Serve the shared storefront |
| GET | `/create?payAmount=0.02&outOrderNo=ORDER-1` | Sign and create an order, then redirect to the returned `checkoutUrl` |
| GET | `/query?orderNo=...` or `?outOrderNo=...` | Sign an authoritative query; return the `data` array |
| POST | `/notify` | Parse JSON, verify canonical HMAC and the ±5-minute timestamp window, then return the demo ACK |
| GET | `/payment/return` | Redirect to the storefront |
| GET | `/payment/success?outOrderNo=...` | Redirect to the signed order query |

`/create` requires `payAmount`: at least `0.01`, at most 12 integer and 2 fractional digits, without scientific notation, leading zeros, or whitespace. `outOrderNo` accepts 1–64 letters, digits, or hyphens. When it is absent, the Demo generates a fresh ID; an explicitly empty or invalid value is rejected. Optional `productPrice` accepts at most 14 integer and 6 fractional digits and uses USD; optional `productId` accepts at most 64 characters. Unknown or repeated query parameters are rejected. Query identifiers follow SDK §5.11.

Amounts remain decimal strings; callback JSON decimals use `Decimal`. Signing preserves non-blank strings without trimming. The optional empty-value exceptions and payment-method normalization follow SDK §4.4. Binary floating-point values are not accepted by the signer. JSON numbers exceeding the demo's 4096-digit limit and duplicate object keys are rejected. The original request objects are not mutated.

Upstream requests identify the client as `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36`, have a 10-second timeout, and do not follow redirects. HTTP errors retain `CF-Ray` when supplied by the gateway for troubleshooting. HTTP errors, invalid response envelopes, and nonzero business codes become HTTP 502; HTTP 200 alone is not success. Callbacks require UTF-8 JSON, `Content-Length`, and `X-DSPay-Signature`; chunked bodies are not supported. The Demo does not impose a fixed callback-body size cap; upstream responses are limited to 1 MiB. Valid, fresh callbacks receive HTTP 200 with `{"code":"SUCCESS","msg":"ok"}`; invalid signatures, timestamps, or JSON receive a failure response.

## Local Tests and Production Boundaries

```bash
cd Demo/backend/python
python3 -B -m unittest discover -s test -v
```

Tests use dummy secrets and an in-process mock DSPay HTTP service. They cover shared signing vectors, decimal precision, original string preservation, callback tampering and expired-timestamp rejection, storefront hosting, create/query requests, redirects, upstream failures, configuration validation, and background start/stop. They require no merchant account or real transfer and do not call the production API.

The live Demo uses mainnet and real tokens/network fees. Local tests do not prove chain detection, real DSPay webhook delivery, or actual payment completion. This is a mock merchant: it does not persist orders or fulfill goods, and its ACK is not a production business transaction. Before production, add persistent `orderNo + eventType` idempotency, transactional business updates before ACK, HTTPS, access control, monitoring, and secret management. A browser redirect is never proof of payment.

See the [SDK guide](../../../SDK/SDK.en-US.md) and [Demo overview](../../README.md).

## Request and Response Logs

From the backend directory, run `tail -f logs/server.log` for live logs after `./start.sh`. Direct startup writes logs to the terminal.

`DEMO_REQUEST` / `DEMO_RESPONSE` record inbound create, query, and webhook parameters and final responses. `DSPAY_REQUEST` / `DSPAY_RESPONSE` record outgoing API parameters, response bodies, and HTTP status codes. `CALLBACK_REQUEST` records notification bodies; `CALLBACK_VERIFIED` indicates successful signature verification. Failed verification produces a logged HTTP 401 response; network failures use `DSPAY_ERROR`. A successful create flow logs HTTP 302 and the checkout redirect location.

Check both HTTP status and DSPay business codes. Verification or redirects do not prove merchant accounting or fulfillment. API secrets and JSON signature fields are redacted; other business parameters remain visible for integration debugging.

## Shared Behavior Across All Four Demos

`/create` requires `payAmount`, at least `0.01`, with at most 12 integer and 2 fractional digits. Missing `outOrderNo` generates a fresh ID; explicit empty or invalid values are rejected. Optional `productPrice` allows 14 integer and 6 fractional digits and uses USD; `productId` allows 64 characters. Missing optional product fields are not replaced by defaults. Unknown/repeated parameters, invalid identifiers, leading zeros, scientific notation, and amount whitespace return HTTP 400. Valid strings remain unchanged; optional empty-field signing exclusions follow the SDK contract.

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. The Demo imposes no extra fixed callback-body size cap.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.
