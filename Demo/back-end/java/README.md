# DSPay mock merchant (Java)

Mock merchant backend for DSPay integration: `/create` generates a signed order and redirects to the cashier; `/notify` receives DSPay callbacks and verifies signatures.

Functionally identical to the [Node.js version](../js).

## Requirements

- JDK 11+
- Zero external dependencies (JDK built-in `com.sun.net.httpserver` + `javax.crypto`)

## Quick start

### Option 1: Background run (recommended)

```bash
cd back-end/java
./start.sh   # start in background
./stop.sh    # stop
```

### Option 2: Single-file run (foreground, Java 11+)

```bash
cd back-end/java
java src/DspayMockMerchant.java
```

Custom port:

```bash
java -Dport=4000 src/DspayMockMerchant.java
```

## Configuration

All configurable via `-D` system properties:

| Property | Default | Description |
|---|---|---|
| `port` | `3000` | Server port |
| `cashierBase` | `https://cashier.ds.pro/` | Cashier base URL |
| `merchantNo` | `change-me-to-your-merchantNo` | Your merchant ID |
| `apiSecret` | `change-me-to-your-apiSecret` | Your API secret (for signing & verification) |

Example:

```bash
java -Dport=4000 -DmerchantNo=M2079022817467412481 -DapiSecret=my-secret \
     src/DspayMockMerchant.java
```

## Endpoints

### `GET /create` — Create order + redirect to cashier

Credentials (`merchantNo` + `apiSecret`) are **hardcoded on the server side** — they are NOT accepted via query params (prevents identity spoofing). Only business fields are overridable.

**Query params (all optional, fallback to defaults):**

| Param | Default | Description |
|---|---|---|
| `payAmount` | `0.01` | Payment amount |
| `productPrice` | `0.01` | Product price |
| `productPriceCurrency` | `USD` | Price currency |
| `productId` | `NOVA-LIFETIME-001` | Product ID |

**Behavior:**

1. Generates `outOrderNo = String(System.currentTimeMillis())` and `timestamp`
2. Signs 4 fields (order-sensitive): `merchantNo → outOrderNo → payAmount → timestamp`
3. 302 redirects to `{cashierBase}?merchantNo=...&outOrderNo=...&payAmount=...&timestamp=...&signature=...&productPrice=...&productPriceCurrency=...&productId=...`

**Example:**

```
http://localhost:3000/create
http://localhost:3000/create?payAmount=0.05&productId=NOVA-001
```

### `POST /notify` — Receive DSPay callback + verify signature

**Header:** `X-DSPay-Signature: <hmac-sha256-hex>`

**Behavior:**

1. Reads raw request body (no JSON parse/reserialize)
2. Computes HMAC-SHA256 over raw body using the hardcoded `apiSecret`
3. Constant-time comparison against `X-DSPay-Signature` header (`MessageDigest.isEqual`)
4. On success: `200 {"code":"SUCCESS","msg":"ok"}` (must be strictly uppercase `SUCCESS`, otherwise DSPay retries)
5. On failure: `401 {"code":"FAIL","msg":"signature invalid"}`

**Local test:**

```bash
BODY='{"orderNo":"DS001","status":"COMPLETED","payAmount":"0.01"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "change-me-to-your-apiSecret" -hex | awk '{print $NF}')
curl -X POST http://localhost:3000/notify \
  -H "Content-Type: application/json" \
  -H "X-DSPay-Signature: $SIG" \
  -d "$BODY"
```

## Signature rules

**Order creation (merchant → DSPay):**

```
canonical = merchantNo={m}&outOrderNo={o}&payAmount={p}&timestamp={t}
signature = HMAC-SHA256(canonical, apiSecret) → lowercase hex
```

- Field order is **sensitive** — wrong order causes error code 50613
- `outOrderNo` can be empty (key kept, value empty: `&outOrderNo=`)
- `payAmount` must be a plain numeric string (no scientific notation)

**Callback verification (DSPay → merchant):**

```
expected = HMAC-SHA256(rawBody, apiSecret)
compare expected == X-DSPay-Signature (constant-time)
```

- Must use the **raw body string** as-is. Do NOT `JSON.parse` then `JSON.stringify` — field order or whitespace changes would alter the byte sequence.

## Logging

Logs are written to both console and `logs/server.log`.
