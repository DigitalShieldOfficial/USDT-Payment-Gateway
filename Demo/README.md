[English](README.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

This demo implements the merchant integration flow: the browser calls the merchant backend; the backend signs and calls DSPay create-order; only after receiving `checkoutUrl` does it redirect the customer. `apiSecret` and signed order fields are never exposed in the Checkout URL.

## Tested runtimes

| Demo | Minimum | Tested versions | Dependencies |
|------|---------|-----------------|--------------|
| Node.js | Node.js `18.20.8` | Node.js `18.20.8` + npm `10.8.2` | No npm dependencies; `.nvmrc` included |
| Java | JDK 11 | Microsoft OpenJDK `11.0.27`; Temurin `21.0.11` | No Maven/Gradle dependencies |
| PHP | PHP 5.6 | PHP CLI `5.6.40` and `8.5.10` | No Composer dependency |
| Frontend | A modern browser with `crypto.randomUUID()` | Chrome `151.0.7922.175` | One HTML file; no build step |

Tests were run on macOS `15.1` and Docker Linux. “Minimum” is the source compatibility baseline; “Tested versions” lists runtimes actually used to execute repository tests.

Check local versions before running a demo:

```bash
node --version && npm --version
java -version && javac -version
php --version
```

Versions below the listed minimum are unsupported. The demos do not use frameworks such as Express, Spring Boot, or Laravel, so there are no framework-version requirements.

All Node.js documentation and code use one baseline; multiple Node.js installations are not required:

```bash
cd Demo/back-end/nodejs
nvm install
nvm use
npm test
```

## Run Node.js

> `REPLACE_WITH_REAL_MERCHANT_NO`, `REPLACE_WITH_REAL_API_SECRET`, and `REPLACE_WITH_REAL_DSPAY_API_HOST` below are placeholders. Replace all of them before running. Obtain `merchantNo` and `apiSecret` from the DSPay Merchant Portal.

```bash
cd Demo/back-end/nodejs
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST"
export PUBLIC_BASE_URL="http://localhost:3000"
node src/server.js
```

Open `Demo/front-end/index.html` and click Pay Now. Expose port 3000 through ngrok or similar when testing webhooks, then configure that public `/notify` URL in the merchant portal.

The frontend reuses one `outOrderNo` within the browser session so repeated create attempts exercise the idempotent create-order contract.

## Run Java

```bash
cd Demo/back-end/java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -DdspayBase="https://REPLACE_WITH_REAL_DSPAY_API_HOST" \
  -DpublicBase="http://localhost:3000" src/DspayMockMerchant.java
```

## Run PHP

> Replace only the two `REPLACE_WITH_REAL_*` credential placeholders below. The production API and local redirect base are already populated. Do not copy Markdown link syntax into shell values.

```bash
cd Demo/back-end/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://wallet.ds.pro"
export PUBLIC_BASE_URL="http://localhost:3000"
./start.sh
```

## Demo endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/create` | Create DSPay order server-to-server, then 302 to returned `checkoutUrl` |
| GET | `/query?orderNo=...` | Node/PHP demo: signed authoritative order query |
| POST | `/notify` | Verify webhook using the shared ASCII-sorted canonical field string |
| GET | `/payment/return` | Timeout landing; Node/PHP demo queries DSPay |
| GET | `/payment/success` | Success landing; Node/PHP demo queries DSPay |

In production, store the secret in KMS, add HTTP timeouts and bounded retries, reuse the same `outOrderNo` on retries, process webhooks idempotently, and fulfill only after a verified webhook or server-side query reports `COMPLETED`. A browser redirect is never proof of payment. Both URLs are optional: `returnUrl` is used only when the order times out, while `successRedirectUrl` is used only after completion. Checkout becomes unviewable 180 days after order creation and must not be used as a permanent order-details URL.
