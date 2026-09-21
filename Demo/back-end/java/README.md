[English](README.md) | [中文](README.zh-CN.md)

# DSPay Java Mock Merchant

JDK 8+, zero external dependencies. It calls `POST /dspay/public/order/create`, redirects to the returned `checkoutUrl`, and verifies webhooks using the shared ASCII-sorted canonical field string.

Runtime baseline: JDK 8 minimum; verified end-to-end on Amazon Corretto `1.8.0_504` (JDK 8) and Eclipse Temurin `21.0.11`. Maven and Gradle are not required; the source uses only the JDK standard library. `./start.sh` compiles to `build/` and runs `java -cp build DspayMockMerchant` (works on JDK 8, where single-file source launch is unavailable).

> `REPLACE_WITH_REAL_MERCHANT_NO`, `REPLACE_WITH_REAL_API_SECRET`, and `REPLACE_WITH_REAL_DSPAY_API_HOST` below are placeholders. Replace them with real values from the DSPay Merchant Portal before running.

```bash
cd Demo/back-end/java
mkdir -p build && javac -d build src/DspayMockMerchant.java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -DdspayBase="https://REPLACE_WITH_REAL_DSPAY_API_HOST" -DpublicBase="http://localhost:3000" \
  -cp build DspayMockMerchant
```

For background use, run `./start.sh`. It prompts for `DSPAY_BASE_URL`, `PUBLIC_BASE_URL`, `MERCHANT_NO`, and `API_SECRET`; secret input is hidden. Set any of these environment variables beforehand to skip its prompt. `PORT` is optional and defaults to `3000`; `FRONT_END_DIR` optionally overrides the served front-end directory (default `../../front-end`). The script does not save entered values, so enter them again on the next start or provide them through the environment. Non-interactive runs require all four variables.

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
