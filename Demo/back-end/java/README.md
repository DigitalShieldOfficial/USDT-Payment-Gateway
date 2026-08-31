[English](README.md) | [中文](README.zh-CN.md)

# DSPay Java Mock Merchant

JDK 11+, zero external dependencies. It calls `POST /dspay/public/order/create`, redirects to the returned `checkoutUrl`, and verifies raw-body webhooks.

Runtime baseline: JDK 11 minimum; tested with Microsoft OpenJDK `11.0.27` and Eclipse Temurin `21.0.11`. Maven and Gradle are not required; the source uses only the JDK standard library and supports JDK 11 single-file source launch.

> `REPLACE_WITH_REAL_MERCHANT_NO`, `REPLACE_WITH_REAL_API_SECRET`, and `REPLACE_WITH_REAL_DSPAY_API_HOST` below are placeholders. Replace them with real values from the DSPay Merchant Portal before running.

```bash
cd Demo/back-end/java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -DdspayBase="https://REPLACE_WITH_REAL_DSPAY_API_HOST" -DpublicBase="http://localhost:3000" \
  src/DspayMockMerchant.java
```

- `GET /create`: create the order server-to-server and redirect
- `POST /notify`: verify `X-DSPay-Signature` over the exact raw body
- the timeout landing page is informational only; query `POST /dspay/public/order/query` before fulfillment

Compile and run the local test:

```bash
javac -d /tmp/dspay-java src/DspayMockMerchant.java test/DspayMockMerchantTest.java
java -cp /tmp/dspay-java DspayMockMerchantTest
```
