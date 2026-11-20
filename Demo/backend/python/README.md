[English](README.md) | [中文](README.zh-CN.md)

# DSPay Python Mock Merchant

Python 3.10+, zero third-party dependencies. The backend signs and calls `POST /dspay/public/order/create` and `/query`, redirects to the returned `checkoutUrl`, and verifies callbacks over the shared canonical field string.

## Runtime

Use `python3`; no pip install, virtual environment, or framework is required. Verified versions are listed in the table below.

### Verified environments (end-to-end against the production DSPay API)

| Python version | Installation | Platform | Result | Recommended |
|---|---|---|---|---|
| 3.10.16 | pyenv (source build) | macOS arm64 | ✅ order created, 302 → cashier | — (EOL since 2026-10) |
| 3.11.13 | pyenv (source build) | macOS arm64 | ✅ order created, 302 → cashier | — |
| 3.12.3 | pyenv (source build) | macOS arm64 | ✅ order created, 302 → cashier | ⭐ Yes — production workhorse |
| 3.13.6 | pyenv (source build) | macOS arm64 | ✅ order created, 302 → cashier | ⭐ Yes |
| 3.14.3 | system python3 | macOS arm64 | ✅ order created, 302 → cashier | ⭐ Yes — preferred, current recommended release |
| 3.15.1 | pyenv (source build) | macOS arm64 | ✅ order created, 302 → cashier | — (newest, released 2026-10) |

Every row was verified twice with the full flow: `./start.sh` → storefront page → Pay Now → server-side order creation → `302` redirect to `https://cashier.ds.pro/checkout/<orderId>?x-digitalshield-temp-token=<token>`. All verified lines remain supported; "—" means still supported but not the first choice for new integrations. The standard-library `ssl` module tracks the interpreter's linked OpenSSL, and all tested builds (OpenSSL 1.1.1 / 3.x / 4.x eras) passed, so the OpenSSL version is not a compatibility factor for Python. Compare with your environment via `python3 -V`.

Startup requires only the merchant number and API secret; no access URL configuration is needed. The default port is `3000`; use `PORT=4000 ./start.sh` to change it. Payment return links are omitted by default. To enable them, optionally set `PUBLIC_BASE_URL` before startup to a trusted origin such as `https://shop.example.com` (no trailing slash or path). Return links use only this setting, never `Host` or `X-Forwarded-*`. Register `<public URL>/notify` separately in the Merchant Portal for real webhooks.

## Optional startup settings

Basic startup requires only `MERCHANT_NO` and `API_SECRET`; `./start.sh` prompts for missing credentials. All settings below are optional environment variables supplied before startup. No code changes are required.

| Setting | Default / behavior when absent | Purpose | Usage (from this backend directory) |
| --- | --- | --- | --- |
| `PUBLIC_BASE_URL` | Unset; return links are omitted | Trusted merchant origin used to generate `returnUrl` and `successRedirectUrl`. Does not change the listening address or register a webhook. | `PUBLIC_BASE_URL=https://shop.example.com ./start.sh` |
| `PORT` | `3000` | Demo listening port (`1–65535`). Match the tunnel or reverse proxy target port to this value. | `PORT=4000 ./start.sh` |
| `DSPAY_BASE_URL` | `https://pay-api.ds.pro` | DSPay API origin. Leave unchanged for normal integration; override only for another integration environment. | `DSPAY_BASE_URL=https://api.example.com ./start.sh` |
| `FRONT_END_DIR` | Bundled `Demo/frontend` directory | Serve another frontend directory containing `index.html`. An absolute path is recommended. | `FRONT_END_DIR=/absolute/path/to/frontend ./start.sh` |
| `PYTHON_BIN` | `python3` | Select a Python 3.10+ interpreter for `start.sh` only. Direct Python invocation uses the interpreter named in the command. | `PYTHON_BIN=python3.12 ./start.sh` |

URL settings must be complete HTTP(S) origins without a trailing slash, path, query, fragment, credentials, or whitespace. Use punycode for internationalized domains. An absent `PUBLIC_BASE_URL` omits return links; an explicitly empty or invalid value is rejected. Replace example domains and directories with actual values.

Combine settings as needed:

```bash
PUBLIC_BASE_URL=https://shop.example.com PORT=4000 ./start.sh
```

Alternatively, run `export PUBLIC_BASE_URL=https://shop.example.com` before `./start.sh`. Restart the service after changing configuration; run `./stop.sh` first if it is already running in the background. Register `<public URL>/notify` separately in the Merchant Portal for real callbacks.

For ngrok, run `ngrok http 3000` (match the Demo port). Nginx can proxy to `http://127.0.0.1:3000`. Configure `PUBLIC_BASE_URL` explicitly for payment return links; proxy headers are not used to derive them. See the security deployment guidance for access restrictions.

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

Upstream requests identify the client as `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36`, have a 10-second timeout, and do not follow redirects. HTTP errors retain `CF-Ray` when supplied by the gateway for troubleshooting. HTTP errors, invalid response envelopes, and nonzero business codes become HTTP 502; HTTP 200 alone is not success. Callbacks require UTF-8 JSON, `Content-Length`, and `X-DSPay-Signature`; chunked bodies are not supported. Callback bodies are limited to 64 KiB (HTTP 413 when exceeded); upstream responses are limited to 1 MiB. Valid, fresh callbacks receive HTTP 200 with `{"code":"SUCCESS","msg":"ok"}`; invalid signatures, timestamps, or JSON receive a failure response.

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

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. Callback bodies are limited to 64 KiB; oversized bodies receive HTTP 413.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.

## Security configuration and deployment

Merchant credentials must be supplied explicitly; there are no fallback values. Missing credentials prevent execution. `PUBLIC_BASE_URL` is optional: when absent, return links are omitted and the payer stays on the checkout page. An explicit empty or invalid value is rejected. Callback bodies are limited to 64 KiB. Create/query endpoints remain demonstration endpoints; merchants must implement production authorization and order ownership checks. See [security deployment guidance](../../SECURITY-DEPLOYMENT.md).
