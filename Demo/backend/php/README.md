[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP Mock Merchant

PHP 5.6+, no Composer. The backend calls the DSPay create/query APIs and redirects to the `checkoutUrl` returned by the create response.

Runtime baseline: PHP 5.6 minimum; all syntax checks, create-signature tests, and callback-verification tests were run with PHP CLI `5.6.40` and `8.5.10`. Composer is not required; only standard PHP extensions are used. `hash_equals()` requires PHP 5.6+.

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders and must be replaced. The API address defaults to the DSPay production API; override it only when testing another environment.

Run `./start.sh` and enter any missing `MERCHANT_NO` and `API_SECRET` values; secret input is hidden. `PORT` is optional and defaults to `3000`, and `FRONT_END_DIR` optionally overrides the served frontend directory (default `../../frontend`). Entered values are not saved. The server runs in the background (PID in `server.pid`); stop it with `./stop.sh`.

The API address defaults to `https://pay-api.ds.pro` for both script and direct startup, so it is not prompted. Set `DSPAY_BASE_URL` explicitly only to test another environment. API addresses must be complete HTTP(S) origins without a trailing slash, path, query, credentials, or whitespace; invalid explicit values are rejected rather than repaired.

Startup requires only the merchant number and API secret; no access URL configuration is needed. The default port is `3000`; use `PORT=4000 ./start.sh` to change it. Payment return links use the origin of the create-order request. When using ngrok/nginx, open the public URL to create orders. Register `<public URL>/notify` separately in the Merchant Portal for real webhooks.

For ngrok, run `ngrok http 3000` (match the Demo port). For nginx, proxy to `http://127.0.0.1:3000` and set `proxy_set_header Host $http_host;`, `proxy_set_header X-Forwarded-Proto $scheme;`, and `proxy_set_header X-Forwarded-Host $http_host;` to replace client-supplied forwarded hosts. The Demo trusts proxy headers only from loopback peers. Supply one correct host and protocol, not comma-separated lists, and access the public service through that proxy.

```bash
cd Demo/backend/php
./start.sh
```

For non-interactive use, set the environment variables first:

```bash
cd Demo/backend/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
php -S 0.0.0.0:3000 server.php
# background alternative: ./start.sh (prompts for missing variables, stop with ./stop.sh)
```

- `GET /create`: call `POST /dspay/public/order/create`, then 302 to returned `checkoutUrl`
- `GET /query?orderNo=...` or `?outOrderNo=...`: signed authoritative query
- `POST /notify`: verify `X-DSPay-Signature` over the shared ASCII-sorted canonical field string
- `GET /` serves the frontend store page (same origin as the API)
- `returnUrl` redirects back to the store page; `successRedirectUrl` lands on `/query` showing the real order status — a redirect is never proof of payment

Run local tests:

```bash
php test/ValidateApiBase.php
php test/ValidateNetworkError.php
php test/ValidateCreateOrder.php
php test/VerifyCallback.php
php test/ValidateHttpCreate.php  # skipped unless TEST_CREATE_URL is configured
```

## Request and Response Logs

From the backend directory, run `tail -f logs/server.log` for live logs after `./start.sh`. Direct startup writes logs to the terminal.

`DEMO_REQUEST` / `DEMO_RESPONSE` record inbound create, query, and webhook parameters and final responses. `DSPAY_REQUEST` / `DSPAY_RESPONSE` record outgoing API parameters, response bodies, and HTTP status codes. `CALLBACK_REQUEST` records notification bodies; `CALLBACK_VERIFIED` indicates successful signature verification. Failed verification produces a logged HTTP 401 response; network failures use `DSPAY_ERROR`. A successful create flow logs HTTP 302 and the checkout redirect location.

Check both HTTP status and DSPay business codes. Verification or redirects do not prove merchant accounting or fulfillment. API secrets and JSON signature fields are redacted; other business parameters remain visible for integration debugging.

The PHP client sends `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36` and `Accept: application/json`. Cloudflare HTTP 403 logs retain the response body, `cfRay`, and `contentType`; error messages also include `CF-Ray`. The API operator must inspect the matching security event using the Ray ID. A client identifier does not guarantee that the request will be accepted.

## Shared Behavior Across All Four Demos

`/create` requires `payAmount`, at least `0.01`, with at most 12 integer and 2 fractional digits. Missing `outOrderNo` generates a fresh ID; explicit empty or invalid values are rejected. Optional `productPrice` allows 14 integer and 6 fractional digits and uses USD; `productId` allows 64 characters. Missing optional product fields are not replaced by defaults. Unknown/repeated parameters, invalid identifiers, leading zeros, scientific notation, and amount whitespace return HTTP 400. Valid strings remain unchanged; optional empty-field signing exclusions follow the SDK contract.

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. The Demo imposes no extra fixed callback-body size cap.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.
