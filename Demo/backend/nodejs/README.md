[English](README.md) | [中文](README.zh-CN.md)

# DSPay Node.js Mock Merchant

The single runtime baseline is Node.js `18.20.8` + npm `10.8.2`, with no npm dependencies. The service signs and calls `POST /dspay/public/order/create` and `/query`; it never builds a signed cashier URL in the browser.

The minimum and tested baseline are both Node.js `18.20.8` + npm `10.8.2`. The repository includes `.nvmrc`, and `package.json` declares `node >=18.20.8`. Multiple Node.js installations are not required, and no third-party npm packages are used.

The API address defaults to `https://pay-api.ds.pro` for both script and direct startup, so it is not prompted. Set `DSPAY_BASE_URL` explicitly only to test another environment. API addresses must be complete HTTP(S) origins without a trailing slash, path, query, credentials, or whitespace; invalid explicit values are rejected rather than repaired.

Startup requires only the merchant number and API secret; no access URL configuration is needed. The default port is `3000`; use `PORT=4000 ./start.sh` to change it. Payment return links use the origin of the create-order request. When using ngrok/nginx, open the public URL to create orders. Register `<public URL>/notify` separately in the Merchant Portal for real webhooks.

For ngrok, run `ngrok http 3000` (match the Demo port). For nginx, proxy to `http://127.0.0.1:3000` and set `proxy_set_header Host $http_host;`, `proxy_set_header X-Forwarded-Proto $scheme;`, and `proxy_set_header X-Forwarded-Host $http_host;` to replace client-supplied forwarded hosts. The Demo trusts proxy headers only from loopback peers. Supply one correct host and protocol, not comma-separated lists, and access the public service through that proxy.

```bash
nvm install
nvm use
node --version  # v18.20.8
npm --version   # 10.8.2
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

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. The Demo imposes no extra fixed callback-body size cap.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.

For network failures, `DSPAY_ERROR.details` retains the underlying Node.js cause chain and error codes such as `ECONNRESET`, `ENOTFOUND`, and `ETIMEDOUT`. Error responses also show the underlying cause; the 10-second timeout is reported explicitly. A connection error without an HTTP response is distinct from a Cloudflare HTTP 403 and must be diagnosed using its actual error code.

Run regression tests with `node --test` (or `npm test`). TLS tests generate temporary local certificates using OpenSSL; OpenSSL is a test tool only and is not required to run the Demo. Independent transport checks on older Node releases do not change the full Demo runtime baseline of `>=18.20.8`.
