[English](README.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

A mock merchant backend for DSPay integration testing: visit `/create` to sign locally, build a cashier URL, and redirect the user; `/notify` receives DSPay callbacks and verifies signatures.

## Prerequisites

- Node.js 18+ (zero third-party dependencies — uses only built-in `http`, `crypto`, `url`, `fs`, `path`)

## Before You Start

Edit `src/server.js` and replace the placeholder credentials with your actual DSPay merchant values:

```js
// src/server.js line 62-63
const MERCHANT_NO = process.env.MERCHANT_NO || 'change-me-to-your-merchantNo';  // ← replace this
const API_SECRET = process.env.API_SECRET || 'change-me-to-your-apiSecret';    // ← replace this
```

You can also set them via environment variables instead of editing the file:

```bash
export MERCHANT_NO=M123456789
export API_SECRET=sk_yourApiSecret
```

**Security note:** `merchantNo` and `apiSecret` are hardcoded server-side. Query parameters for other merchant IDs or secrets are deliberately ignored — this prevents attackers from switching merchant identity via query params to bypass signature verification.

## How to Start

### Foreground (for development)

```bash
npm start
# or: node src/server.js
```

### Background (for production / long-running)

```bash
./start.sh    # start as daemon (nohup)
./stop.sh     # stop the daemon
```

The service listens on port `3000` by default. Override with:

```bash
PORT=4000 npm start
# or: PORT=4000 ./start.sh
```

### Viewing Logs

All request logs are written to `logs/server.log` with timestamps:

```bash
tail -f logs/server.log
```

## API Endpoints

### `GET /create` — Build signed cashier URL + redirect

Builds the signed cashier URL locally and returns an HTTP 302 redirect; chain/token selection and order creation happen in the Hosted Cashier.

**Query parameters**

| Param | Required | Default | Description |
|-------|----------|---------|-------------|
| `payAmount` | No | `0.01` | Positive plain decimal string with at most 2 decimal places; see the [SDK precision rule](../../../SDK/SDK.en-US.md#order-suffix-mechanism-in-depth) |
| `productPrice` | No | `0.01` | Product price |
| `productPriceCurrency` | No | `USD` | Price currency |
| `productId` | No | `NOVA-LIFETIME-001` | Product ID |

> **`payAmount` precision:** Stablecoins are treated as 6-decimal tokens. Merchants submit at most 2 decimal places and DSPay uses the remaining 4 for its order suffix. `100`, `100.1`, and `100.12` are valid; `100.123` is invalid. Use a plain decimal string, never JavaScript `number` or scientific notation. More than 2 decimal places returns [`50612`](../../../SDK/SDK.en-US.md#error-50612).

**Behavior**

1. Auto-generates `timestamp = Date.now()` and `outOrderNo` (timestamp-based)
2. Signs 4 fields with HMAC-SHA256 using the hardcoded `apiSecret`: `merchantNo → outOrderNo → payAmount → timestamp`
3. 302 redirects to `https://cashier.ds.pro/?merchantNo=...&outOrderNo=...&payAmount=...&timestamp=...&signature=...&productPrice=...&productPriceCurrency=...&productId=...`

**Examples**

```bash
# All defaults
curl -L 'http://localhost:3000/create'

# Custom amount
curl -L 'http://localhost:3000/create?payAmount=100'

# Custom amount and product
curl -L 'http://localhost:3000/create?payAmount=50&productId=MY-PRODUCT-001&productPrice=50'
```

### `POST /notify` — Receive DSPay callback + verify signature

Receives payment status callbacks from DSPay and verifies the `X-DSPay-Signature` header.

**Request headers**

| Header | Description |
|--------|-------------|
| `X-DSPay-Signature` | HMAC-SHA256 hex signature over the raw request body |

**Behavior**

1. Reads raw request body (no JSON re-serialization)
2. Computes HMAC-SHA256 over the raw body using the hardcoded `apiSecret`
3. Constant-time compares with `X-DSPay-Signature` header
4. Returns `{"code":"SUCCESS","msg":"ok"}` on match (must be strictly uppercase — DSPay retries otherwise)
5. Returns `401` with `{"code":"FAIL","msg":"signature invalid"}` on mismatch
6. Returns `500` if `API_SECRET` is not configured

**Local testing**

```bash
# Replace apiSecret with your actual secret
API_SECRET="sk_yourApiSecret"

BODY='{"orderNo":"DS001","status":"COMPLETED","payAmount":"0.01"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$API_SECRET" -hex | awk '{print $2}')
curl -X POST http://localhost:3000/notify \
  -H "Content-Type: application/json" \
  -H "X-DSPay-Signature: $SIG" \
  -d "$BODY"
```

## Signature Rules

### Order creation

Signed fields and order (**order-sensitive**):

```
merchantNo → outOrderNo → payAmount → timestamp
```

Canonical string format:

```
merchantNo={merchantNo}&outOrderNo={outOrderNo}&payAmount={payAmount}&timestamp={timestamp}
```

- `outOrderNo` is required and must not be blank. Include it in both the signature and cashier URL. Field names are case-sensitive: use `outOrderNo`, not `outOrderNO`.
- `payAmount` must be greater than 0, contain at most 2 decimal places, and remain a plain decimal string. Never convert it through JavaScript `number` or use scientific notation; violations return [`50612`](../../../SDK/SDK.en-US.md#error-50612).
- HMAC-SHA256 output is lowercase hex

### Callback verification

Signature is computed over the **raw request body** as-is. Do not JSON.parse then re-stringify — field order or whitespace changes break the byte sequence.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3000` | Server listen port |
| `CASHIER_BASE` | `https://cashier.ds.pro/` | Cashier base URL |
| `MERCHANT_NO` | `change-me-to-your-merchantNo` | Merchant ID (hardcoded fallback) |
| `API_SECRET` | `change-me-to-your-apiSecret` | Merchant apiSecret (hardcoded fallback) |

## Project Structure

```
dspay-mock-merchant/
├── src/
│   ├── server.js    # HTTP server, routes, logging
│   └── signer.js    # HMAC-SHA256 signOrder + verifyCallback
├── logs/
│   └── server.log   # Request logs (auto-created)
├── start.sh         # Background daemon start
├── stop.sh          # Background daemon stop
├── package.json
├── README.md
└── README.zh-CN.md
```
