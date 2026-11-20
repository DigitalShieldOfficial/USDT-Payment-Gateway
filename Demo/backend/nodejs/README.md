[English](README.md) | [中文](README.zh-CN.md)

# DSPay Node.js Mock Merchant

The service signs and calls `POST /dspay/public/order/create` and `/query`; it never builds a signed cashier URL in the browser.

Runtime baseline: Node.js `>=18.20.8` (declared in `package.json`; `.nvmrc` pins the dev version), zero third-party npm packages. Verified versions are listed in the table below.

## Verified environments (end-to-end against the production DSPay API)

| Node.js version | npm | Platform | Result | Recommended |
|---|---|---|---|---|
| 18.20.8 | 10.8.2 | macOS arm64 (nvm) | ✅ order created, 302 → cashier | — (EOL, migrate when possible) |
| 20.20.0 | 10.8.2 | macOS arm64 (nvm) | ✅ order created, 302 → cashier | — (EOL, migrate when possible) |
| 22.23.4 | 10.9.9 | macOS arm64 (nvm) | ✅ order created, 302 → cashier | ⭐ Yes — LTS |
| 24.22.2 | 11.19.1 | macOS arm64 (nvm) | ✅ order created, 302 → cashier | ⭐ Yes — LTS |
| 26.13.0 | 11.20.0 | macOS arm64 (nvm) | ✅ order created, 302 → cashier | — (newest, released recently) |

Every row was verified twice with the full flow: `./start.sh` → storefront page → Pay Now → server-side order creation → `302` redirect to `https://cashier.ds.pro/checkout/<orderId>?x-digitalshield-temp-token=<token>`. All verified lines remain supported; "—" means still supported but not the first choice for new integrations. Node ships its own TLS stack (BoringSSL-derived), so the host's OpenSSL version does not affect connectivity. Compare with your environment via `node -v`.

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

For ngrok, run `ngrok http 3000` (match the Demo port). Nginx can proxy to `http://127.0.0.1:3000`. Configure `PUBLIC_BASE_URL` explicitly for payment return links; proxy headers are not used to derive them. See the security deployment guidance for access restrictions.

```bash
nvm install
nvm use
node --version  # v22.23.4 (.nvmrc)
npm --version   # 10.9.9
```

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders. Replace them with real values from the DSPay Merchant Portal before running. The DSPay API base URL defaults to `https://pay-api.ds.pro`; override it only when testing another environment.

Run `./start.sh` for a background service. It prompts for missing `MERCHANT_NO` and `API_SECRET`; secret input is hidden. `PORT` is optional and defaults to `3000`; `FRONT_END_DIR` optionally overrides the served frontend directory (default `../../frontend`). Entered values are not saved, so provide them again on the next start or set environment variables first.

```bash
cd Demo/backend/nodejs
./start.sh
```

For non-interactive use, set the environment variables first:

```bash
cd Demo/backend/nodejs
MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO" API_SECRET="REPLACE_WITH_REAL_API_SECRET" \
npm start
# npm start is equivalent to node src/server.js; background alternative: ./start.sh (stop with ./stop.sh)
```

- `GET /create`: server-to-server create, then 302 to returned `checkoutUrl`
- `GET /query?orderNo=...` or `?outOrderNo=...`: signed authoritative query
- `POST /notify`: verify `X-DSPay-Signature` over the shared ASCII-sorted canonical field string
- `GET /` serves the frontend store page (same origin as the API; override the directory with `FRONT_END_DIR`)
- `returnUrl` redirects back to the store page; `successRedirectUrl` lands on `/query` showing the real order status — a redirect is never proof of payment

Run tests with `npm test`.

## Request and Response Logs

From the backend directory, run `tail -f logs/server.log` for live logs after `./start.sh`. Direct startup writes logs to the terminal.

`DEMO_REQUEST` / `DEMO_RESPONSE` record inbound create, query, and webhook parameters and final responses. `DSPAY_REQUEST` / `DSPAY_RESPONSE` record outgoing API parameters, response bodies, and HTTP status codes. `CALLBACK_REQUEST` records notification bodies; `CALLBACK_VERIFIED` indicates successful signature verification. Failed verification produces a logged HTTP 401 response; network failures use `DSPAY_ERROR`. A successful create flow logs HTTP 302 and the checkout redirect location.

Check both HTTP status and DSPay business codes. Verification or redirects do not prove merchant accounting or fulfillment. API secrets and JSON signature fields are redacted; other business parameters remain visible for integration debugging.

The Node.js client calls HTTPS APIs through built-in TLS/HTTP/2 support, using a fixed modern cipher order, a standard Chrome browser User-Agent, and `Accept: application/json`. The implementation in `src/transport.js` requires no npm installation, third-party packages, or native binaries. It neither configures a proxy nor reads system proxy settings. HTTPS upstreams must support HTTP/2; local HTTP mock APIs still use HTTP/1.1. Certificate verification stays enabled, with TLS 1.2 as the minimum version. For Cloudflare HTTP 403, logs retain the response body, `cfRay`, and `contentType`; the page error also includes `CF-Ray`. Give the Ray ID to the API operator to identify the blocking rule. Changing the User-Agent does not guarantee acceptance by every rule.

## Shared Behavior Across All Four Demos

`/create` requires `payAmount`, at least `0.01`, with at most 12 integer and 2 fractional digits. Missing `outOrderNo` generates a fresh ID; explicit empty or invalid values are rejected. Optional `productPrice` allows 14 integer and 6 fractional digits and uses USD; `productId` allows 64 characters. Missing optional product fields are not replaced by defaults. Unknown/repeated parameters, invalid identifiers, leading zeros, scientific notation, and amount whitespace return HTTP 400. Valid strings remain unchanged; optional empty-field signing exclusions follow the SDK contract.

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. Callback bodies are limited to 64 KiB; oversized bodies receive HTTP 413.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.

For network failures, `DSPAY_ERROR.details` retains the underlying Node.js cause chain and error codes such as `ECONNRESET`, `ENOTFOUND`, and `ETIMEDOUT`. Error responses also show the underlying cause; the 10-second timeout is reported explicitly. A connection error without an HTTP response is distinct from a Cloudflare HTTP 403 and must be diagnosed using its actual error code.

Run regression tests with `node --test` (or `npm test`). TLS tests generate temporary local certificates using OpenSSL; OpenSSL is a test tool only and is not required to run the Demo.

## Security configuration and deployment

Merchant credentials must be supplied explicitly; there are no fallback values. Missing credentials prevent execution. `PUBLIC_BASE_URL` is optional: when absent, return links are omitted and the payer stays on the checkout page. An explicit empty or invalid value is rejected. Callback bodies are limited to 64 KiB. Create/query endpoints remain demonstration endpoints; merchants must implement production authorization and order ownership checks. See [security deployment guidance](../../SECURITY-DEPLOYMENT.md).
