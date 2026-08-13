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

Creates a signed order and sends an HTTP 302 redirect to the DSPay cashier. Optional parameters are `payAmount`, `productPrice`, `productPriceCurrency`, and `productId`.

```bash
curl -i 'http://localhost:3000/create?payAmount=0.01&productId=DEMO-001'
```

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
```
