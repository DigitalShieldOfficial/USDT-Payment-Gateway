[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP Mock Merchant

For production, use the latest patched PHP 8.4/8.5. An integration baseline or historical test pass does not imply ongoing official security support. PHP 8.1 and earlier are compatibility records only, not production recommendations.

PHP 8.1+, no Composer. The backend calls the DSPay create/query APIs and redirects to the `checkoutUrl` returned by the create response.

Runtime baseline: PHP 8.1+, zero third-party dependencies — no Composer required; only standard PHP extensions are used. Verified versions and the PHP 7.4 environment caveat are listed in the table below.

## Verified environments (end-to-end against the production DSPay API)

| PHP version | OpenSSL | Environment | Result | Recommended |
|---|---|---|---|---|
| 8.5.10 | OpenSSL 3.x | Homebrew, macOS arm64 | ✅ order created, 302 → cashier | ⭐ Yes — newest |
| 8.4.27 | OpenSSL 3.x | Docker `php:8.4-cli` (Debian bookworm) | ✅ order created, 302 → cashier | ⭐ Yes |
| 8.3.6 | OpenSSL 3.6.3 | self-built, macOS arm64 | ✅ order created, 302 → cashier | ⭐ Yes |
| 8.2.35 | OpenSSL 3.x | Docker `php:8.2-cli` (Debian bookworm) | ✅ order created, 302 → cashier | ⭐ Yes |
| 8.1.34 | OpenSSL 1.1.1 | Docker `php:8.1-cli` (Debian bullseye) | ✅ order created, 302 → cashier | Compatibility only; end-of-life, not recommended for production |
| 7.4.33 | OpenSSL 4.0.3 | Homebrew shivammathur/php bottle | ✅ order created, 302 → cashier | Only with an OpenSSL 4 build |
| 7.4.33 | OpenSSL 3.0.20 | sury bookworm image | ⚠️ blocked by the gateway WAF (HTTP 403) | ❌ No — blocked by old TLS fingerprint |
| 7.4.33 | OpenSSL 1.1.1n | Docker `php:7.4-cli` (Debian bullseye) | ⚠️ blocked by the gateway WAF (HTTP 403) | ❌ No — blocked by old TLS fingerprint |

Every ✅ row was verified twice with the full flow: `./start.sh` → storefront page → Pay Now → server-side order creation → `302` redirect to `https://cashier.ds.pro/checkout/<orderId>?x-digitalshield-temp-token=<token>`.

**What this means for your environment.** PHP **8.1+** works on any modern OpenSSL (1.1.1 / 3.x / 4.x — all tested) — just check `php -v`. PHP **7.4** is the only version sensitive to the linked OpenSSL: builds with OpenSSL 4 (e.g. Homebrew `shivammathur/php` bottles since 2026-10) pass, while older builds (OpenSSL 1.1.1 / 3.0, i.e. distro packages and the official Docker image) are challenged by the gateway WAF with `DSPay HTTP 403; CF-Ray: ...`. The demo code itself has no defects on 7.4 (verified against a local mock). Check your build with:

```bash
php -v && php -r 'echo OPENSSL_VERSION_TEXT, PHP_EOL;'
```

If you are on PHP ≤ 7.4 with an older OpenSSL, upgrade to PHP 8.1+, or point `DSPAY_BASE_URL` at a local newer-stack proxy (any PHP 8 / Python 3 process on the same host), and hand the `CF-Ray` value from the error to DSPay support for tracing.

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders and must be replaced. The API address defaults to the DSPay production API; override it only when testing another environment.

Run `./start.sh` and enter any missing `MERCHANT_NO` and `API_SECRET` values; secret input is hidden. `PORT` is optional and defaults to `3000`, and `FRONT_END_DIR` optionally overrides the served frontend directory (default `../../frontend`). Entered values are not saved. The server runs in the background (PID in `server.pid`); stop it with `./stop.sh`.

The API address defaults to `https://pay-api.ds.pro` for both script and direct startup, so it is not prompted. Set `DSPAY_BASE_URL` explicitly only to test another environment. API addresses must be complete HTTP(S) origins without a trailing slash, path, query, credentials, or whitespace; invalid explicit values are rejected rather than repaired.

Startup requires only the merchant number and API secret; no access URL configuration is needed. The default port is `3000`; use `PORT=4000 ./start.sh` to change it. Payment return links are omitted by default. To enable them, optionally set `PUBLIC_BASE_URL` before startup to a trusted origin such as `https://shop.example.com` (no trailing slash or path). Return links use only this setting, never `Host` or `X-Forwarded-*`. Register `<public URL>/notify` separately in the Merchant Portal for real webhooks.

## Optional startup settings

Basic startup requires only `MERCHANT_NO` and `API_SECRET`; `./start.sh` prompts for missing credentials. All settings below are optional environment variables supplied before startup. No code changes are required.

| Setting | Default / behavior when absent | Purpose | Usage (from this backend directory) |
| --- | --- | --- | --- |
| `PUBLIC_BASE_URL` | Unset; return links are omitted | Trusted merchant origin used to generate `returnUrl` and `successRedirectUrl`. Does not change the listening address or register a webhook. | `PUBLIC_BASE_URL=https://shop.example.com ./start.sh` |
| `PORT` | `3000` | Demo listening port (`1–65535`). Match the tunnel or reverse proxy target port to this value. | `PORT=4000 ./start.sh` |
| `DSPAY_BASE_URL` | `https://pay-api.ds.pro` | DSPay API origin. Leave unchanged for normal integration; override only for another integration environment. | `DSPAY_BASE_URL=https://api.example.com ./start.sh` |
| `FRONT_END_DIR` | Bundled `Demo/frontend` directory | Serve another frontend directory containing `index.html`. An absolute path is recommended. | `FRONT_END_DIR=/absolute/path/to/frontend ./start.sh` |

URL settings must be complete HTTP(S) origins without a trailing slash, path, query, fragment, credentials, or whitespace. Use punycode for internationalized domains. An absent `PUBLIC_BASE_URL` omits return links; an explicitly empty or invalid value is rejected. Replace example domains and directories with actual values.

Combine settings as needed:

```bash
PUBLIC_BASE_URL=https://shop.example.com PORT=4000 ./start.sh
```

Alternatively, run `export PUBLIC_BASE_URL=https://shop.example.com` before `./start.sh`. Restart the service after changing configuration; run `./stop.sh` first if it is already running in the background. Register `<public URL>/notify` separately in the Merchant Portal for real callbacks.

`PORT` is consumed by `start.sh`. With a direct PHP development server invocation, specify the port using `php -S 0.0.0.0:4000 server.php`. Prefer `start.sh` to validate configuration before opening the listening port.

For ngrok, run `ngrok http 3000` (match the Demo port). Nginx can proxy to `http://127.0.0.1:3000`. Configure `PUBLIC_BASE_URL` explicitly for payment return links; proxy headers are not used to derive them. See the security deployment guidance for access restrictions.

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

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. Callback bodies are limited to 64 KiB; oversized bodies receive HTTP 413.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.

## Security configuration and deployment

Merchant credentials must be supplied explicitly; there are no fallback values. Missing credentials prevent execution. `PUBLIC_BASE_URL` is optional: when absent, return links are omitted and the payer stays on the checkout page. An explicit empty or invalid value is rejected. Callback bodies are limited to 64 KiB. Create/query endpoints remain demonstration endpoints; merchants must implement production authorization and order ownership checks. See [security deployment guidance](../../SECURITY-DEPLOYMENT.md).
