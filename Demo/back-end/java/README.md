[English](README.md) | [中文](README.zh-CN.md)

# DSPay Java Mock Merchant

JDK 8+, zero external dependencies. It calls `POST /dspay/public/order/create`, redirects to the returned `checkoutUrl`, and verifies webhooks using the shared ASCII-sorted canonical field string.

Runtime baseline: JDK 8 minimum; verified end-to-end on Amazon Corretto `1.8.0_504` (JDK 8) and Eclipse Temurin `21.0.11`. Maven and Gradle are not required; the source uses only the JDK standard library. `./start.sh` compiles to `build/` and runs `java -cp build DspayMockMerchant` (works on JDK 8, where single-file source launch is unavailable).

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders. Replace them with real values from the DSPay Merchant Portal before running. The DSPay API base URL defaults to `https://pay-api.ds.pro`; override it only when testing another environment.

The API address defaults to `https://pay-api.ds.pro` for both script and direct startup, so it is not prompted. Set `DSPAY_BASE_URL` explicitly only to test another environment. Java also accepts `-DdspayBase`, which takes precedence over the environment variable. API addresses must be complete HTTP(S) origins without a trailing slash, path, query, credentials, or whitespace; invalid explicit values are rejected rather than repaired.

Startup requires only the merchant number and API secret; no access URL configuration is needed. The default port is `3000`; use `PORT=4000 ./start.sh` to change it. Payment return links use the origin of the create-order request. When using ngrok/nginx, open the public URL to create orders. Register `<public URL>/notify` separately in the Merchant Portal for real webhooks.

For ngrok, run `ngrok http 3000` (match the Demo port). For nginx, proxy to `http://127.0.0.1:3000` and set `proxy_set_header Host $http_host;`, `proxy_set_header X-Forwarded-Proto $scheme;`, and `proxy_set_header X-Forwarded-Host $http_host;` to replace client-supplied forwarded hosts. The Demo trusts proxy headers only from loopback peers. Supply one correct host and protocol, not comma-separated lists, and access the public service through that proxy.

```bash
cd Demo/back-end/java
mkdir -p build && javac -d build src/DspayMockMerchant.java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -cp build DspayMockMerchant
```

For background use, run `./start.sh`. It prompts for `MERCHANT_NO` and `API_SECRET`; secret input is hidden. Set any of these environment variables beforehand to skip its prompt. `PORT` is optional and defaults to `3000`; `FRONT_END_DIR` optionally selects a directory containing `index.html` (default `../../front-end`; script-relative paths resolve from the Java Demo directory). Direct Java startup also accepts this environment variable; `-DfrontEndDir` takes precedence. Explicit empty values or a missing `index.html` cause startup to fail. The script does not save entered values, so enter them again on the next start or provide them through the environment. Non-interactive runs require the two credential variables.

- `GET /create`: create the order server-to-server and redirect to `checkoutUrl`
- `GET /query?orderNo=...` or `?outOrderNo=...`: signed authoritative query
- `POST /notify`: verify `X-DSPay-Signature` over the shared ASCII-sorted canonical field string
- `GET /` serves the front-end store page (same origin as the API; override the directory with `FRONT_END_DIR`)
- `returnUrl` redirects back to the store page; `successRedirectUrl` lands on `/query` showing the real order status — a redirect is never proof of payment

Compile and run the local test:

```bash
mkdir -p /tmp/dspay-java
javac -d /tmp/dspay-java src/DspayMockMerchant.java test/DspayMockMerchantTest.java
java -cp /tmp/dspay-java DspayMockMerchantTest
```

## Request and Response Logs

From the backend directory, run `tail -f logs/server.log` for live logs after `./start.sh`. Direct startup writes logs to the terminal.

`DEMO_REQUEST` / `DEMO_RESPONSE` record inbound create, query, and webhook parameters and final responses. `DSPAY_REQUEST` / `DSPAY_RESPONSE` record outgoing API parameters, response bodies, and HTTP status codes. `CALLBACK_REQUEST` records notification bodies; `CALLBACK_VERIFIED` indicates successful signature verification. Failed verification produces a logged HTTP 401 response; network failures use `DSPAY_ERROR`. A successful create flow logs HTTP 302 and the checkout redirect location.

Check both HTTP status and DSPay business codes. Verification or redirects do not prove merchant accounting or fulfillment. API secrets and JSON signature fields are redacted; other business parameters remain visible for integration debugging.

The Java client sends `User-Agent: DSPay-Java-Demo/1.0` and `Accept: application/json`. For Cloudflare block HTML, inspect the HTTP status, `CF-Ray`, and response body in `DSPAY_RESPONSE` logs. Give the Ray ID to the API operator to identify the rule; an explicit client identifier does not guarantee acceptance by every firewall rule.

## Shared Behavior Across All Four Demos

`/create` requires `payAmount`, at least `0.01`, with at most 12 integer and 2 fractional digits. Missing `outOrderNo` generates a fresh ID; explicit empty or invalid values are rejected. Optional `productPrice` allows 14 integer and 6 fractional digits and uses USD; `productId` allows 64 characters. Missing optional product fields are not replaced by defaults. Unknown/repeated parameters, invalid identifiers, leading zeros, scientific notation, and amount whitespace return HTTP 400. Valid strings remain unchanged; optional empty-field signing exclusions follow the SDK contract.

Callbacks preserve large JSON integers and decimal precision and reject duplicate object keys. `timestamp` must be a nonnegative integer in milliseconds within an inclusive ±5-minute window. Invalid signatures or timestamps return HTTP 401. The Demo imposes no extra fixed callback-body size cap.

Upstream requests explicitly use 10-second timeouts and refuse redirects. Success requires a top-level integer `code` equal to `0` and a `data` field; HTTP 200 or `header.resultCode` alone is insufficient. HTTP failures, nonzero business codes, malformed responses, and timeouts return HTTP 502 with diagnostic logs. HTTP libraries, default proxy behavior, and TLS characteristics remain runtime-specific.
