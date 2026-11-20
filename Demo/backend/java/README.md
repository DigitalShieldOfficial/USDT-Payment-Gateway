[English](README.md) | [中文](README.zh-CN.md)

# DSPay Java Mock Merchant

JDK 8+, zero external dependencies. It calls `POST /dspay/public/order/create`, redirects to the returned `checkoutUrl`, and verifies webhooks using the shared ASCII-sorted canonical field string.

Runtime baseline: JDK 8+ — no Maven or Gradle required; the source uses only the JDK standard library. `./start.sh` compiles to `build/` and runs `java -cp build DspayMockMerchant` using the same JDK 8+ installation. Verified versions are listed in the table below.

## Verified environments (end-to-end against the production DSPay API)

| Java version | Distribution | Platform | Result | Recommended |
|---|---|---|---|---|
| 8 (`1.8.0_504`) | Amazon Corretto | macOS arm64 | ✅ order created, 302 → cashier | — |
| 11 (`11.0.27`) | Microsoft OpenJDK | macOS arm64 | ✅ order created, 302 → cashier | — |
| 17 (`17.0.21.0.3`) | Eclipse Temurin (Homebrew) | macOS arm64 | ✅ order created, 302 → cashier | — |
| 21 (`21.0.11`) | Eclipse Temurin | macOS arm64 | ✅ order created, 302 → cashier | ⭐ Yes — current enterprise default |
| 25 (`25.0.5.0.3`) | Eclipse Temurin (Homebrew) | macOS arm64 | ✅ order created, 302 → cashier | ⭐ Yes — latest LTS |

Every row was verified twice with the full flow: `./start.sh` → storefront page → Pay Now → server-side order creation → `302` redirect to `https://cashier.ds.pro/checkout/<orderId>?x-digitalshield-temp-token=<token>`. All verified lines remain supported; "—" means still supported but not the first choice for new integrations. The JDK ships its own TLS stack (JSSE), so the host's OpenSSL version does not affect connectivity. Compare with your environment via `java -version`.

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders. Replace them with real values from the DSPay Merchant Portal before running. The DSPay API base URL defaults to `https://pay-api.ds.pro`; override it only when testing another environment.

The API address defaults to `https://pay-api.ds.pro` for both script and direct startup, so it is not prompted. Set `DSPAY_BASE_URL` explicitly only to test another environment. Java also accepts `-DdspayBase`, which takes precedence over the environment variable. API addresses must be complete HTTP(S) origins without a trailing slash, path, query, credentials, or whitespace; invalid explicit values are rejected rather than repaired.

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

Direct Java invocations also support system properties for some optional settings (place `-D` arguments before the main class):

| Environment variable | System property | Precedence / usage |
| --- | --- | --- |
| `PORT` | `-Dport=4000` | System property takes precedence |
| `DSPAY_BASE_URL` | `-DdspayBase=https://api.example.com` | System property takes precedence |
| `FRONT_END_DIR` | `-DfrontEndDir=/absolute/path/to/frontend` | System property takes precedence |
| `PUBLIC_BASE_URL` | None | Environment variable only |

Required credentials also support `-DmerchantNo` and `-DapiSecret`. The merchant number system property takes precedence. For the secret, the `API_SECRET` environment variable takes precedence (an explicit empty value is rejected); `-DapiSecret` is read only when the environment variable is absent. Prefer passing secrets through the environment.

```bash
PUBLIC_BASE_URL=https://shop.example.com java -Dport=4000 -cp build DspayMockMerchant
```

This direct startup example assumes compilation is complete and `MERCHANT_NO` and `API_SECRET` have already been supplied through the environment.

For ngrok, run `ngrok http 3000` (match the Demo port). Nginx can proxy to `http://127.0.0.1:3000`. Configure `PUBLIC_BASE_URL` explicitly for payment return links; proxy headers are not used to derive them. See the security deployment guidance for access restrictions.

```bash
cd Demo/backend/java
mkdir -p build && javac -d build src/DspayMockMerchant.java src/DspayTransport.java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -cp build DspayMockMerchant
```

For background use, run `./start.sh`. It prompts for `MERCHANT_NO` and `API_SECRET`; secret input is hidden. Set any of these environment variables beforehand to skip its prompt. `PORT` is optional and defaults to `3000`; `FRONT_END_DIR` optionally selects a directory containing `index.html` (default `../../frontend`; script-relative paths resolve from the Java Demo directory). Direct Java startup also accepts this environment variable; `-DfrontEndDir` takes precedence. Explicit empty values or a missing `index.html` cause startup to fail. The script does not save entered values, so enter them again on the next start or provide them through the environment. Non-interactive runs require the two credential variables.

- `GET /create`: create the order server-to-server and redirect to `checkoutUrl`
- `GET /query?orderNo=...` or `?outOrderNo=...`: signed authoritative query
- `POST /notify`: verify `X-DSPay-Signature` over the shared ASCII-sorted canonical field string
- `GET /` serves the frontend store page (same origin as the API; override the directory with `FRONT_END_DIR`)
- `returnUrl` redirects back to the store page; `successRedirectUrl` lands on `/query` showing the real order status — a redirect is never proof of payment

Compile and run the local test:

```bash
mkdir -p /tmp/dspay-java
javac -d /tmp/dspay-java src/DspayMockMerchant.java src/DspayTransport.java test/DspayMockMerchantTest.java test/DspayTransportTest.java
MERCHANT_NO=DSM001 API_SECRET=demo-secret java -cp /tmp/dspay-java DspayMockMerchantTest
java -cp /tmp/dspay-java DspayTransportTest
```

## Request and Response Logs

From the backend directory, run `tail -f logs/server.log` for live logs after `./start.sh`. Direct startup writes logs to the terminal.

`DEMO_REQUEST` / `DEMO_RESPONSE` record inbound create, query, and webhook parameters and final responses. `DSPAY_REQUEST` / `DSPAY_RESPONSE` record outgoing API parameters, response bodies, and HTTP status codes. `CALLBACK_REQUEST` records notification bodies; `CALLBACK_VERIFIED` indicates successful signature verification. Failed verification produces a logged HTTP 401 response; network failures use `DSPAY_ERROR`. A successful create flow logs HTTP 302 and the checkout redirect location.

Check both HTTP status and DSPay business codes. Verification or redirects do not prove merchant accounting or fulfillment. API secrets and JSON signature fields are redacted; other business parameters remain visible for integration debugging.

The Java client sends `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36` and `Accept: application/json`. For Cloudflare block HTML, inspect the HTTP status, `CF-Ray`, and response body in `DSPAY_RESPONSE` logs. Give the Ray ID to the API operator to identify the rule; an explicit client identifier does not guarantee acceptance by every firewall rule.

## Shared Behavior Across All Four Demos

`/create` requires `payAmount`, at least `0.01`, with at most 12 integer and 2 fractional digits. Missing `outOrderNo` generates a fresh ID; explicit empty or invalid values are rejected. Optional `productPrice` allows 14 integer and 6 fractional digits and uses USD; `productId` allows 64 characters. Missing optional product fields are not replaced by defaults. Unknown/repeated parameters, invalid identifiers, leading zeros, scientific notation, and amount whitespace return HTTP 400. Valid strings remain unchanged; optional empty-field signing exclusions follow the SDK contract.

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. Callback bodies are limited to 64 KiB; oversized bodies receive HTTP 413.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.

Requests use the Java 8 public `Socket` / `SSLSocket` APIs through `src/DspayTransport.java`, with HTTP/1.1 and the same Chrome 147 User-Agent as the other Demos. No JDK 11 API, third-party library, native binary, or automatic proxy configuration is required. HTTPS verifies the certificate chain and hostname and requires TLS 1.2 or newer. The 10-second deadline covers connection, TLS handshake, writes, and response reads. The client handles fixed-length, chunked, and connection-delimited responses, supports gzip/deflate, and does not follow redirects. This is a small POST implementation, not a complete backport of JDK 11 HttpClient.

Transport tests generate temporary local certificates with the JDK `keytool`; they do not disable TLS verification.

## Security configuration and deployment

Merchant credentials must be supplied explicitly; there are no fallback values. Missing credentials prevent execution. `PUBLIC_BASE_URL` is optional: when absent, return links are omitted and the payer stays on the checkout page. An explicit empty or invalid value is rejected. Callback bodies are limited to 64 KiB. Create/query endpoints remain demonstration endpoints; merchants must implement production authorization and order ownership checks. See [security deployment guidance](../../SECURITY-DEPLOYMENT.md).
