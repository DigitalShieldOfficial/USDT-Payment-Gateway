[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP Mock Merchant

A directly runnable Digital Shield pay mock merchant backend. The required SDK source is included in `src/`, so Composer is not needed.

## Requirements

- PHP 5.6+
- PHP `hash` and `json` extensions (normally enabled by default)

## Quick Start

```bash
cd Demo/back-end/php

MERCHANT_NO="your-merchantNo" \
API_SECRET="your-apiSecret" \
./start.sh
```

The service listens on `http://localhost:3000` by default. Open `Demo/front-end/index.html` in a browser and click **Pay Now** to initiate a payment.

You can also start it without the script:

```bash
MERCHANT_NO="your-merchantNo" API_SECRET="your-apiSecret" \
php -S localhost:3000 server.php
```

To use another port:

```bash
PORT=4000 MERCHANT_NO="your-merchantNo" API_SECRET="your-apiSecret" ./start.sh
```

> `merchantNo` and `apiSecret` are sensitive. The demo only reads them from server-side environment variables and never accepts credentials from request parameters.

## Endpoints

### `GET /create`

Creates a signed order and sends an HTTP 302 redirect to the DSPay cashier. The HTTP route generates a non-blank `outOrderNo` on the merchant backend before calling `Payment::createOrder()`. Query parameters `payAmount`, `productPrice`, `productPriceCurrency`, and `productId` are optional and use demo defaults when omitted.

```bash
curl -i 'http://localhost:3000/create?payAmount=0.01&productId=DEMO-001'
```

**Flow:**

1. Generate a unique, non-blank `outOrderNo` of at most 64 characters on the merchant backend.
2. Calculate HMAC-SHA256 in the fixed order `merchantNo → outOrderNo → payAmount → timestamp`.
3. Include the same `outOrderNo` in both the canonical signature string and cashier URL.
4. Return an HTTP 302 redirect to the cashier.

> `outOrderNo` is required and case-sensitive: use `outOrderNo`, not `outOrderNO`. Direct calls to `Payment::createOrder()` must provide it explicitly. Missing, blank, or over-64-character values throw `RequestBuilderException`.

> **`payAmount` precision:** Stablecoins are treated as 6-decimal tokens. Merchants submit at most 2 decimal places and DSPay uses the remaining 4 for its order suffix. `100`, `100.1`, and `100.12` are valid; `100.123` is invalid. Pass a positive plain decimal string; never use PHP `float` or scientific notation. More than 2 decimal places returns [`50612`](../../../SDK/SDK.en-US.md#error-50612).

### `POST /notify`

Receives a DSPay callback and verifies its HMAC-SHA256 signature using the raw request body and the `X-DSPay-Signature` header. A valid callback returns:

```json
{"code":"SUCCESS","msg":"ok"}
```

## Project Structure

```text
php/
├── server.php              # HTTP routes: /create and /notify
├── start.sh                # One-command startup script
├── src/
│   ├── bootstrap.php       # Local source loader; no Composer needed
│   ├── Client.php
│   ├── Payment.php
│   ├── RequestBuilder.php
│   └── RequestBuilderException.php
└── test/                   # Standalone signing examples
```

Run the standalone examples with:

```bash
php test/CreateOrder.php
php test/VerifyCallback.php
php test/ValidateCreateOrder.php
```
