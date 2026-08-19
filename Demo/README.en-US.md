## language
[English](README.en-US.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

A mock merchant system for quickly experiencing the DSPay payment flow — merchant frontend initiates payment → merchant backend signs and builds the cashier URL → redirect to DSPay cashier → payment callback notification.

## Project Structure

```
dspay-mock-merchant/
├── back-end/                  # Mock merchant backend
│   ├── java/                  # Java 11+ implementation (zero external dependencies)
│   │   └── src/DspayMockMerchant.java
│   ├── php/                   # PHP 5.6+ implementation (no Composer needed)
│   │   ├── server.php
│   │   └── start.sh
│   └── nodejs/                # Node.js 18+ implementation (zero npm dependencies)
│       └── src/
│           ├── server.js
│           └── signer.js
└── front-end/                 # Mock merchant frontend (pure static HTML)
    └── index.html
```

## Integration Flow

```
                                DSPay
┌─────────────────────┐     ┌──────────────┐
│ DSPay Merchant      │     │ DSPay Cashier │
│ Portal              │     │              │
│ ① Sign up           │     │ ④ User       │
│ ② Get merchantNo    │     │   completes  │
│    Generate apiSecret│     │   payment    │
└────────┬────────────┘     └──────▲───────┘
         │                         │
    Provide merchantNo      ⑤ Async notification
     + apiSecret             POST /notify
         │                         │
         ▼                         │
┌──────────────────────────────────────────────┐
│              Mock Merchant System             │
│                                              │
│  ┌─────────────┐         ┌──────────────┐   │
│  │  Backend     │◄────────│   Frontend   │   │
│  │  GET /create │  ③ Click│  index.html  │   │
│  │  POST /notify│  Pay Now│              │   │
│  │              │────────►│              │   │
│  │              │ 302     │              │   │
│  └──────────────┘ Redirect└──────────────┘   │
│   Java, Node.js, or PHP                       │
└──────────────────────────────────────────────┘
```

### Flow Description

| Step | Description |
|------|------|
| ① | Go to [DSPay Merchant Portal](https://mcashier.ds.pro/login/) to sign up as a merchant |
| ② | Get your `merchantNo` from the [Account page](https://mcashier.ds.pro/account) and your `apiSecret` (Payment Key) from the [Settings page](https://mcashier.ds.pro/settings) |
| ③ | Replace the placeholders in the backend code with your `merchantNo` and `apiSecret`, start the backend, then click "Pay Now"; the backend signs locally and returns a 302 cashier redirect without calling the create-order API |
| ④ | HTTP 302 redirects to the DSPay cashier, where the user completes payment |
| ⑤ | DSPay sends an async callback to `POST /notify`, the backend verifies the signature and logs the payment result |

## Quick Start

### Prerequisites: Sign Up and Get Credentials

1. Open the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) and sign up as a merchant
2. Go to the [Account page](https://mcashier.ds.pro/account) and copy your **merchantNo**
3. Go to the [Settings page](https://mcashier.ds.pro/settings) and get your **apiSecret** from the "Payment Key" section

> :warning: `merchantNo` and `apiSecret` are sensitive credentials. Keep them secure.

### Start the Backend (Choose a Language)

The backend provides Java, Node.js, and PHP implementations with identical functionality. Choose whichever you prefer.

#### Option A: Node.js (Recommended, Fastest Startup)

```bash
# 1. Navigate to the Node.js backend directory
cd back-end/nodejs

# 2. Set your merchant credentials (replace the placeholders)
export MERCHANT_NO="your-merchantNo"
export API_SECRET="your-apiSecret"

# 3. Start the backend (listens on localhost:3000)
node src/server.js
```

> Alternatively, directly edit lines 62-63 in `src/server.js`, replacing `change-me-to-your-merchantNo` and `change-me-to-your-apiSecret` with your actual values.

#### Option B: Java

```bash
# 1. Navigate to the Java backend directory
cd back-end/java

# 2. Set merchant credentials and start (JDK 11+)
java -DmerchantNo="your-merchantNo" -DapiSecret="your-apiSecret" DspayMockMerchant.java
```

> Alternatively, directly edit lines 47-50 in `src/DspayMockMerchant.java`, replacing the placeholders with your actual values.

#### Option C: PHP (No Composer Required)

The PHP demo includes all required source code, so Composer is not needed.

```bash
# 1. Navigate to the PHP backend directory
cd back-end/php

# 2. Set credentials and start (PHP 5.6+, listens on localhost:3000)
MERCHANT_NO="your-merchantNo" API_SECRET="your-apiSecret" ./start.sh
```

You can also run `php -S localhost:3000 server.php` directly. See the [PHP Demo README](back-end/php/README.en-US.md) for details.

### Start the Frontend

Open `front-end/index.html` directly in your browser. You will see the Nova Store mock product page.

### Make a Payment

Click the **"Pay Now"** button on the frontend page:

1. The frontend requests `GET http://localhost:3000/create` (with product parameters)
2. The backend generates a signed order and returns an HTTP 302 redirect to the DSPay cashier
3. Complete the payment on the cashier page
4. DSPay sends an async callback to `POST http://localhost:3000/notify`, the backend verifies the signature and logs the result

## API Reference

### GET /create

Create an order and redirect to the cashier.

| Parameter | Required | Description |
|------|------|------|
| `payAmount` | No | Payment amount, default `0.01`; use a positive plain decimal string with at most 2 decimal places |
| `productPrice` | No | Product price, default `0.01` |
| `productPriceCurrency` | No | Currency, default `USD` |
| `productId` | No | Product ID, default `NOVA-LIFETIME-001` |

> **`payAmount` precision:** Stablecoins are treated as 6-decimal tokens. Merchants submit at most 2 decimal places and DSPay uses the remaining 4 for its order suffix. `100`, `100.1`, and `100.12` are valid; `100.123` is invalid. Use a plain decimal string, never a number or scientific notation. More than 2 decimal places returns [`50612`](../SDK/SDK.en-US.md#error-50612). See the [order-suffix mechanism](../SDK/SDK.en-US.md#order-suffix-mechanism-in-depth).

Response: HTTP 302 redirect to the DSPay cashier (with signed parameters).

### POST /notify

Receives DSPay payment callbacks. The backend performs HMAC-SHA256 signature verification on the callback body using `apiSecret`, and logs the payment result upon successful verification.

## Roadmap

More language implementations (Python, Go, etc.) are planned for the `back-end/` directory. Contributions are welcome.
