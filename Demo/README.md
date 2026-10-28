[English](README.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

This demo implements the merchant integration flow: the browser calls the merchant backend; the backend signs and calls DSPay create-order; only after receiving `checkoutUrl` does it redirect the customer. `apiSecret` and signed order fields are never exposed in the Checkout URL.

> **Secret handling**: keep `apiSecret` on the merchant server only. Never embed it in frontend code, browser storage, or URLs, or paste it into third-party online tools. Use dummy secrets for local signing and verification tests; use real secrets only on the merchant server for production API requests and real callback verification.

## Tested runtimes

| Demo | Minimum | Tested versions | Dependencies |
|------|---------|-----------------|--------------|
| Node.js | Node.js `18.20.8` | Node.js `18.20.8` + npm `10.8.2` | No npm dependencies; `.nvmrc` included |
| Java | JDK 8 | Verified on Corretto `1.8.0_504`, Microsoft OpenJDK `11.0.27`, and Temurin `17.0.21` / `21.0.11` | No Maven/Gradle dependencies |
| PHP | PHP 5.6 | PHP CLI `5.6.40` and `8.5.10` | No Composer dependency |
| Python | Python 3.10 | Python `3.12.14` and `3.14.3` | Standard library only; no pip dependencies |
| Frontend | A modern browser with `crypto.randomUUID()` | Chrome `151.0.7922.175` | One HTML file; no build step |

Historical validation for the existing demos includes macOS `15.1` and Docker Linux. Python tests were run locally on the two versions listed above. “Minimum” is the source compatibility baseline; “Tested versions” lists runtimes actually used to execute repository tests.

Check local versions before running a demo:

```bash
node --version && npm --version
java -version && javac -version
php --version
python3 --version
```

Versions below the listed minimum are unsupported. The demos do not use frameworks such as Express, Spring Boot, or Laravel, so there are no framework-version requirements.

All Node.js documentation and code use one baseline; multiple Node.js installations are not required:

```bash
cd Demo/back-end/nodejs
nvm install
nvm use
npm test
```

## Local Tests and Mainnet Integration Boundaries

- **Local signing tests**: run `DspayMockMerchantTest` as documented in the Java guide, `npm test` for Node.js, `php test/ValidateCreateOrder.php` for PHP, or `python3 -B -m unittest discover -s test -v` from `Demo/back-end/python` for Python. These tests use dummy secrets and require no merchant account or real transfer.
- **Local callback simulation**: `php test/VerifyCallback.php` constructs JSON and a signature and verifies the callback within the process. It does not call the real order API or test HTTP delivery, full replay protection, business idempotency, or actual order state. Never use this result to authorize fulfillment.
- **Python HTTP simulation**: the Python test suite runs a local mock DSPay HTTP service and exercises create/query, callback ACK/failure, redirects, and background start/stop without calling the production API. See the [Python guide](back-end/python/README.md).
- **Mainnet payment integration**: start the merchant Demo using the default production API, and supply real merchant configuration and a publicly reachable callback URL, then pay the final amount displayed by Checkout. This uses real tokens and network fees; synthetic callbacks cannot replace it.

This repository provides no public testnet, faucet, or API that generates a real payment result without a transfer. `supported-chains` lists networks and tokens supported by the configured API; it does not indicate a testnet environment.

All four demos default to `https://pay-api.ds.pro`; startup scripts do not prompt for the API address. Set `DSPAY_BASE_URL` explicitly only when testing another environment. Java also accepts `-DdspayBase`.

## Simple Startup (All Four Demos)

Startup requires only the merchant number and API secret; no access URL configuration is needed. The default port is `3000`; use `PORT=4000 ./start.sh` to change it. Payment return links use the origin of the create-order request. When using ngrok/nginx, open the public URL to create orders. Register `<public URL>/notify` separately in the Merchant Portal for real webhooks.

For ngrok, run `ngrok http 3000` (match the Demo port). For nginx, proxy to `http://127.0.0.1:3000` and set `proxy_set_header Host $http_host;`, `proxy_set_header X-Forwarded-Proto $scheme;`, and `proxy_set_header X-Forwarded-Host $http_host;` to replace client-supplied forwarded hosts. The Demo trusts proxy headers only from loopback peers. Supply one correct host and protocol, not comma-separated lists, and access the public service through that proxy.

Successful startup shows the listening port, local page, webhook URL format, and public forwarding steps.

Payment return links use the URL of the create-order request. Real webhook delivery requires a public URL registered separately in the Merchant Portal; localhost cannot receive callbacks from DSPay.

Maintainers with all four runtimes installed can run the cross-language startup checks from the repository root:

```bash
python3 -B -m unittest discover -s Demo/test -v
```

## Run Node.js

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders. Replace them with real values from the DSPay Merchant Portal before running. The DSPay API base URL defaults to `https://pay-api.ds.pro`; override it only when testing another environment.

```bash
cd Demo/back-end/nodejs
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
npm start        # equivalent to: node src/server.js
```

Every backend version also serves the store page itself: after `./start.sh`, open `http://localhost:3000` (or the public URL) and click Pay Now — page and API share the same origin, no extra static hosting needed. Opening `Demo/front-end/index.html` directly from disk also works locally (it falls back to `http://localhost:3000`). Expose port 3000 through ngrok or similar when testing webhooks, then configure that public `/notify` URL in the merchant portal. `FRONT_END_DIR` optionally points the backend at a different front-end directory (default: `../../front-end`).

The front end displays an editable Order ID (`outOrderNo`). The refresh button generates a new ID each time; Pay Now submits the displayed ID. Use a new ID for each new order. Reuse the original ID and identical business fields only when retrying the same order. The backend uses the supplied value, or generates one when none is supplied.

## Run Java

```bash
cd Demo/back-end/java
mkdir -p build && javac -d build src/DspayMockMerchant.java src/DspayTransport.java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -cp build DspayMockMerchant
```

## Run PHP

> Replace `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below with real values from the DSPay Merchant Portal. The DSPay API base URL defaults to `https://pay-api.ds.pro`; override it only when testing another environment. Do not copy Markdown link syntax into shell values.

```bash
cd Demo/back-end/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
php -S 0.0.0.0:3000 server.php
# background alternative: ./start.sh (prompts for missing variables, stop with ./stop.sh)
```

## Run Python

> Replace the merchant number and API secret below with real portal values. The API URL defaults to the production address. Python 3.10+ is required; no pip install is needed.

```bash
cd Demo/back-end/python
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
python3 -B src/server.py
# Background alternative: ./start.sh (prompts for missing values); stop with ./stop.sh
```

Use the shared storefront at `http://localhost:3000`. Direct create requests require `payAmount`, for example `/create?payAmount=0.01&outOrderNo=ORDER-1`. See the [Python guide](back-end/python/README.md) for configuration validation, callbacks, and local tests.

## Demo endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/create` | Create DSPay order server-to-server, then 302 to returned `checkoutUrl` |
| GET | `/query?orderNo=...` | All backends: signed authoritative order query |
| POST | `/notify` | Verify webhook using the shared ASCII-sorted canonical field string |
| GET | `/payment/return` | Return to the storefront after timeout; navigation is not proof of payment |
| GET | `/payment/success` | Success landing; query DSPay from the merchant backend |

In production, store the secret in KMS and assign a fresh `outOrderNo` to every new order. Only a network retry of the same logical create request should reuse its original `outOrderNo` and identical business fields. Review HTTP timeouts and configure bounded retries, process webhooks idempotently, and fulfill only after a verified webhook or server-side query reports `COMPLETED`. A browser redirect is never proof of payment. Both URLs are optional: `returnUrl` is used only when the order times out, while `successRedirectUrl` is used only after completion. Checkout becomes unviewable 180 days after order creation and must not be used as a permanent order-details URL.

## Request and Response Logs

From the backend directory, run `tail -f logs/server.log` for live logs after `./start.sh`. Direct startup writes logs to the terminal.

`DEMO_REQUEST` / `DEMO_RESPONSE` record inbound create, query, and webhook parameters and final responses. `DSPAY_REQUEST` / `DSPAY_RESPONSE` record outgoing API parameters, response bodies, and HTTP status codes. `CALLBACK_REQUEST` records notification bodies; `CALLBACK_VERIFIED` indicates successful signature verification. Failed verification produces a logged HTTP 401 response; network failures use `DSPAY_ERROR`. A successful create flow logs HTTP 302 and the checkout redirect location.

Check both HTTP status and DSPay business codes. Verification or redirects do not prove merchant accounting or fulfillment. API secrets and JSON signature fields are redacted; other business parameters remain visible for integration debugging.

## Shared Behavior Across All Four Demos

`/create` requires `payAmount`, at least `0.01`, with at most 12 integer and 2 fractional digits. Missing `outOrderNo` generates a fresh ID; explicit empty or invalid values are rejected. Optional `productPrice` allows 14 integer and 6 fractional digits and uses USD; `productId` allows 64 characters. Missing optional product fields are not replaced by defaults. Unknown/repeated parameters, invalid identifiers, leading zeros, scientific notation, and amount whitespace return HTTP 400. Valid strings remain unchanged; optional empty-field signing exclusions follow the SDK contract.

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. The Demo imposes no extra fixed callback-body size cap.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.
