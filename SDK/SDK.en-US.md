## language
[English](https://github.com/DigitalShieldOfficial/DigitalShieldPay/blob/main/SDK.en-US.md) | [中文](https://github.com/DigitalShieldOfficial/DigitalShieldPay/blob/main/SDK.zh-CN.md)

# DSPay Merchant Integration Guide

> This document is the technical onboarding guide for [DSPay](#term-dspay) merchant integrators. It covers the full lifecycle: authentication, payout configuration, order creation, webhook handling, reconciliation, operations, and exception-handling SOPs. Each chapter is organized by technical topic and closes with a list of key caveats and common pitfalls.

---

## Glossary

> A quick reference for the acronyms and proper nouns used throughout this document. New integrators should skim this first to avoid terminology confusion.

| Term | Description |
|------|------|
| <a id="term-dspay"></a>**DSPay** | A multi-chain stablecoin payment gateway (this service). |
| <a id="term-siwe"></a>**SIWE** | Sign-In with Ethereum — the EIP-4361 wallet-login standard. Merchants authenticate by signing a well-formed message with an [EVM](#term-evm) wallet. |
| <a id="term-jwt"></a>**JWT** | JSON Web Token. Used as the DSPay merchant-portal session credential (7-day sliding expiry). Merchant backend integrations (order creation, webhook verification) do **not** rely on JWT — they use [apiSecret](#term-apisecret) signing instead (see [§2.2](#session-lifetime)). |
| <a id="term-apisecret"></a>**apiSecret** | Merchant API secret. Used to sign `POST /dspay/order/create` requests and verify incoming webhooks. Obtain it from the DSPay merchant portal and store it securely. |
| <a id="term-merchantno"></a>**merchantNo** | Merchant business identifier (`DSM` prefix, e.g. `DSM1`). Required when creating orders. This is the **public-facing business code**, not the DB auto-increment primary key (avoids leaking transaction volume and prevents enumeration attacks). |
| <a id="term-networkid"></a>**networkId** | Canonical chain identifier (e.g. `evm--1` = Ethereum mainnet). Full list in [§3.2](#networkid-cheat-sheet). |
| <a id="term-contractaddress"></a>**contractAddress** | Token contract address. Combined with [networkId](#term-networkid) to uniquely identify a token at order creation. |
| <a id="term-hmac-sha256"></a>**HMAC-SHA256** | Hash-based Message Authentication Code. DSPay uses [apiSecret](#term-apisecret) as the key over a canonical string and outputs lowercase hex. |
| <a id="term-evm"></a>**EVM** | Ethereum Virtual Machine. "EVM-compatible chains" are chains that support Ethereum smart contracts (Ethereum / BSC / Polygon / Arbitrum / Base). |
| <a id="term-usdt"></a>**USDT** / <a id="term-usdc"></a>**USDC** | USD-pegged stablecoins (Tether USD / Centre USD Coin). |
| **Fractional Suffix (<a id="term-amountsuffix"></a>amountSuffix)** | A tiny decimal appended by DSPay to differentiate concurrent orders of the same amount (e.g. the `0.001` in `100.001`). See [§4.1](#order-suffix-mechanism-in-depth). |
| **Manual Fulfillment (<a id="term-supplement"></a>supplement)** | The operator-confirmed action that reopens a `CLOSED` order as `COMPLETED` after an on-chain payment lands post-timeout. |
| **Webhook (<a id="term-webhook"></a>webhook)** | The HTTP POST notification DSPay sends to the merchant's [notifyUrl](#term-notifyurl). Event types: `COMPLETED` / `CLOSED` / `REFUNDED`. |
| <a id="term-notifyurl"></a>**notifyUrl** | The public HTTPS endpoint on the merchant side that receives DSPay webhooks. Must be reachable from the public internet. |
| <a id="term-ntp"></a>**NTP** | Network Time Protocol. Used for server clock synchronization. |
| <a id="term-nonce"></a>**nonce** | One-time random value used during [SIWE](#term-siwe) login to prevent replay attacks. |

> Every occurrence of these terms elsewhere in the document links back to this section.

---

## Quick Start (Your First Order in 5 Minutes)

> **Goal:** create an order with the minimum number of steps and inspect the full API response. If you prefer to understand the architecture first, jump to [Chapter 1](#chapter-1-before-you-begin).

**Pre-flight checklist**:

| Requirement | How to obtain | Time |
|------|---------|------|
| [DSPay](#term-dspay) merchant account | Sign in to the [DSPay](#term-dspay) merchant portal with an [EVM](#term-evm) wallet (see [§2.1](#merchant-sign-up-sign-in)) | 30 seconds |
| Receiving address (any chain) | Configure it in the [DSPay](#term-dspay) portal (see [§3.3](#configure-receiving-addresses)) | 1 minute |
| [apiSecret](#term-apisecret) | Generated the first time you enable webhooks in the [DSPay](#term-dspay) portal (see [§3.5](#configure-webhook-url-enable-webhooks)) | — |
| Test stablecoins | Have your wallet hold 0.01 [USDT](#term-usdt) | — |

> ⚠️ If you're missing any of the above, complete the relevant section before returning. Step 1 below is dependency-free, so you can try it immediately.

### Step 1 — Try the cashier in 3 minutes

The demo below uses the **local-sign + cashier URL** approach: no [DSPay](#term-dspay) API call needed — sign locally with HMAC-SHA256 and get a payment link directly. Users open the link, pick their chain/token on the cashier page, and the cashier frontend creates the order automatically.

> To create an order server-side with a **specific chain/token**, see [§4.3 Create Order API](#create-order-endpoint).

Fill in your `merchantNo` + `apiSecret`, save as `create-order.mjs`, and run `node create-order.mjs`.

**Node.js (Node 18+, zero third-party dependencies)**:

```javascript
import crypto from 'node:crypto';

// ===== [1] Merchant credentials (must fill in) =====
const MERCHANT_NO = 'DSM1';
const API_SECRET = 'your-apiSecret';  // Obtain from the DSPay merchant portal

// ===== [2] Fields used in the HMAC signature =====
const signed = {
  payAmount: '10.00',
  outOrderNo: '',   // Optional; leave empty if unused
};

// ===== [3] Display fields (forwarded to the cashier page; NOT part of the signature) =====
const display = {
  productPrice: '9.99',
  productPriceCurrency: 'USD',
  productId: 'PROD-001',
};

// ================ Business logic (no changes needed below) ================

// ① Build the canonical string (fixed order: merchantNo / outOrderNo / payAmount / timestamp, order-sensitive)
const timestamp = Date.now();
const opt = (v) => (v == null || String(v).trim() === '') ? '' : String(v).trim();
const canonical = `merchantNo=${MERCHANT_NO}&outOrderNo=${opt(signed.outOrderNo)}&payAmount=${signed.payAmount}&timestamp=${timestamp}`;

// ② HMAC-SHA256 (use secret.getBytes directly; do NOT Base64-decode)
const signature = crypto.createHmac('sha256', API_SECRET)
  .update(canonical, 'utf8').digest('hex');

// ③ Build the cashier URL
const url = new URL('https://cashier.ds.pro/');
url.searchParams.set('merchantNo', MERCHANT_NO);
url.searchParams.set('outOrderNo', opt(signed.outOrderNo));
url.searchParams.set('payAmount', signed.payAmount);
url.searchParams.set('timestamp', String(timestamp));
url.searchParams.set('signature', signature);
// Display fields (optional)
if (display.productPrice) url.searchParams.set('productPrice', display.productPrice);
if (display.productPriceCurrency) url.searchParams.set('productPriceCurrency', display.productPriceCurrency);
if (display.productId) url.searchParams.set('productId', display.productId);

console.log('Cashier URL:');
console.log(url.toString());
console.log('\nThe link is valid for 5 minutes. Open it in your browser now. Re-run the script if it expires.');
```

**Full demos in other languages**: Java (JDK 11+) in [§4.8](#java-end-to-end-demo), Node.js with full error handling in [§4.9](#node.js-end-to-end-demo).

### Step 3 — Inspect the output ✅

Expected output (the signature and timestamp change with each run, so the URL varies):

```
Cashier URL:
https://cashier.ds.pro?merchantNo=DSM1&outOrderNo=&payAmount=10.00&timestamp=1717689600000&signature=7de3fafc...

The link is valid for 5 minutes. Open it in your browser now. Re-run the script if it expires.
```

Open the cashier URL in your browser — you will see the payment page. Users pick their chain and token, then scan or transfer to complete the payment.

> 💡 **Why the cashier URL instead of the API?** The cashier URL approach is faster for a quick start — zero network calls and no need to understand chain/token concepts. For production use where you need to specify a chain and token server-side, see [§4.3 Create Order API](#create-order-endpoint).

### Next Steps

1. [Receive webhooks](#chapter-5-handling-webhooks) — stand up a local endpoint to consume asynchronous payment notifications from [DSPay](#term-dspay).
2. [Order state machine](#order-state-machine-read-this-first) — understand the full `CREATED → TIMEOUT → CLOSED / COMPLETED` lifecycle.
3. [Testing & integration](#chapter-8-testing--integration) — ngrok for local webhooks + end-to-end verification + idempotency testing.

---

## Chapter 1: Before You Begin

This chapter introduces the core concepts and architecture of [DSPay](#term-dspay), including its positioning as a **multi-chain stablecoin payment gateway**, the four roles in the flow, an end-to-end integration diagram, and the prerequisites checklist.

### 1.1 What is DSPay

[DSPay](#term-dspay) is a **multi-chain stablecoin payment gateway** (a hosted service — merchants never need to run nodes or maintain a backend for chain indexing):

- Supports **9 leading blockchains** (5 [EVM](#term-evm)-compatible chains + Solana / SUI / Tron / Polkadot AssetHub; Aptos / TON not yet enabled).
- Supports **18 stablecoin venues** ([USDT](#term-usdt) / [USDC](#term-usdc) only, covering the dominant asset on each chain).
- Merchants only need to sign up → configure receiving addresses → create orders → consume webhooks to start accepting payments.
- Users pay on-chain to merchant-controlled addresses; [DSPay](#term-dspay) handles chain detection, order-state transitions, and webhook delivery.

### 1.2 The Four Roles

| Role | Description | Audience |
|------|------|-----------|
| **Merchant** | The payment recipient integrating with [DSPay](#term-dspay). Configures webhook URLs + verification secrets, marks orders as paid upon webhook receipt. | ✅ Primary audience for this document — merchant backend engineers. |
| **[DSPay](#term-dspay) Platform** | This service. Creates orders, monitors on-chain settlements, dispatches webhooks to merchants. | — |
| **End User** | The payer. Uses a wallet app to transfer funds to the merchant's receiving address. | — |
| **Blockchain** | [EVM](#term-evm) / Solana / Tron and other public chains. The ultimate settlement layer. | — |

### 1.3 Integration Overview

| Step | Phase | Description | Output |
|:----:|-------|-------------|--------|
| 1 | **Sign up** | [SIWE](#term-siwe) wallet login | Merchant [JWT](#term-jwt) |
| 2 | **Configure payouts** | Addresses + webhook URL | ENABLED addresses |
| 3 | **Create order** | HMAC-signed request | orderNo + address |
| 4 | **User pays** | Wallet transfer to address | On-chain tx |
| 5 | **Chain detection** | Poll for settlement | COMPLETED |
| 6 | **Notify merchant** | POST + HMAC verify | Business logic |
| 7 | **Reconcile** | Order list + reports | Financial close |

Each stage's output feeds the next. See the relevant sections for details.


### 1.4 Prerequisites Checklist

Before you start integrating, have the following ready:

- **An [EVM](#term-evm) wallet**: required for [SIWE](#term-siwe) login (MetaMask / Rabby / Trust all work — anything that supports `personal_sign`).
- **A publicly reachable webhook URL**: where [DSPay](#term-dspay) will POST notifications (for local dev, use ngrok / cpolar).
- **[NTP](#term-ntp)-synced system clock**: signature verification enforces a ±5-minute window; the server clock must be accurate.
- **JDK 11+ or Node.js 18+**: to run the code samples (Python / Go / other languages can follow the spec to implement their own).
- **Test stablecoins**: a small amount of mainnet stablecoin on each chain you intend to test (e.g. 0.01 [USDT](#term-usdt)) for end-to-end verification.

### 1.5 Core API Cheat Sheet

**APIs merchants must integrate against**:

| API | Method | Purpose |
|---|---|---|
| Supported chains/tokens | GET /dspay/public/supported-chains | Fetch the platform-wide chain and token whitelist. |
| Create order | POST /dspay/order/create | Create a payment order (requires HMAC signature). |
| Webhook | Merchant implements | Receive payment / closure / refund notifications from [DSPay](#term-dspay). |

> All other operations — sign-up & login, receiving-address management, webhook URL configuration, order queries & reports, refunds, manual fulfillment, key rotation — are performed in the **[DSPay](#term-dspay) merchant portal UI** and require no API calls.
> The user-facing checkout frontend's order-status polling is implemented by the [DSPay](#term-dspay) team; merchants do not need to build it themselves.

---

## Chapter 2: Onboarding & Authentication

This chapter describes the merchant sign-up and authentication model, including how the portal login works, session lifetime, and how to obtain `apiSecret`.

### 2.1 Merchant Sign-Up & Sign-In

Merchants sign in to the [DSPay](#term-dspay) portal with an [EVM](#term-evm) wallet via [SIWE](#term-siwe) (signature-based authentication). **The first sign-in automatically provisions a merchant account** — no separate registration step is required.

After signing in, you can obtain:
- **[merchantNo](#term-merchantno)**: your merchant business identifier (`DSM` prefix, e.g. `DSM1`). Required when creating orders.
- **[apiSecret](#term-apisecret)**: your API secret, used to sign `POST /dspay/order/create` requests and verify incoming webhooks. Displayed in the portal — store it securely.

### 2.2 Session Lifetime

Portal sessions are valid for 7 days using a sliding window: each interaction auto-extends the session by another 7 days. After 7 days of inactivity, you must sign in again.

> Merchant backend integrations with [DSPay](#term-dspay) (order creation, webhook verification) use [apiSecret](#term-apisecret) signing — they do **not** depend on the [JWT](#term-jwt) session and are unaffected by this limit.

### 2.3 ⚠️ Pitfalls (2)

1. **First sign-in requires setup**: a fresh account has no [apiSecret](#term-apisecret), no receiving addresses, and no webhook URL. You must complete configuration in the portal before you can accept payments. Otherwise, order creation fails with `50609 NO_ENABLED_ADDRESS`.

2. **Guard your [apiSecret](#term-apisecret)**: it is used for both signing and verification — leakage enables forged orders and forged webhooks. Rotate it regularly from the portal (see [§6.4 Key Rotation](#scheduled-key-rotation)).

---

## Chapter 3: Payment Configuration

This chapter walks through the full payout configuration: chain/token **whitelist discovery**, **receiving-address setup**, webhook URL configuration, and the dual-toggle combinatorics matrix.

### 3.1 Query Supported Chains / Tokens

Call the public endpoint (no auth, no [merchantNo](#term-merchantno) required):

```
GET /dspay/public/supported-chains
```

Returns the full platform-wide whitelist of chains and tokens supported by [DSPay](#term-dspay) (9 chains + 18 stablecoins; Aptos/TON not yet enabled; not filtered per-merchant). The returned `networkId` + `address` map 1-to-1 to the `networkId` / `contractAddress` parameters used in order creation.

**Sample response**:

```json
[
  {
    "networkId": "evm--1",
    "chainName": "Ethereum",
    "chainLogo": "https://cdn.example.com/chain/ethereum.png",
    "tokens": [
      {
        "symbol": "USDT",
        "address": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "logoUri": "https://cdn.example.com/token/usdt.png"
      },
      {
        "symbol": "USDC",
        "address": "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
        "logoUri": "https://cdn.example.com/token/usdc.png"
      }
    ]
  }
]
```

**Field reference**:

| Field | Type | Description |
|------|------|------|
| `networkId` | string | Chain [networkId](#term-networkid) (e.g. `evm--1`). Use this value for the `networkId` parameter when creating orders. |
| `chainName` | string | Display name (e.g. `Ethereum`). |
| `chainLogo` | string\|null | Logo URL; null when the DB has no logo configured (front-ends can fall back to a default icon). |
| `tokens[].symbol` | string | Token symbol (`USDT` / `USDC`). |
| `tokens[].address` | string | Token contract / mint address. Use this value for the `contractAddress` parameter when creating orders. |
| `tokens[].logoUri` | string\|null | Token logo URL; null when the DB has no logo configured. |

> Only [USDT](#term-usdt) / [USDC](#term-usdc) are listed. Always defer to the live API response for the authoritative list.

### 3.2 networkId Cheat Sheet

| Chain | [networkId](#term-networkid) | Type | Status |
|----|-----------|------|------|
| Ethereum | `evm--1` | [EVM](#term-evm) | Enabled |
| BSC | `evm--56` | [EVM](#term-evm) | Enabled |
| Polygon | `evm--137` | [EVM](#term-evm) | Enabled |
| Arbitrum | `evm--42161` | [EVM](#term-evm) | Enabled |
| Base | `evm--8453` | [EVM](#term-evm) | Enabled |
| Solana | `sol--101` | Solana | Enabled |
| SUI | `sui--mainnet` | SUI | Enabled |
| Tron | `tron--0x2b6653dc` | Tron | Enabled |
| Polkadot AssetHub | `dot--asset-hub` | Polkadot | Enabled |

> The authoritative list is whatever `GET /dspay/public/supported-chains` returns.

### 3.3 Configure Receiving Addresses

Merchants only need to configure `ENABLED` receiving addresses for the **chains they actually want to accept payments on** — chains you don't intend to support can be skipped. If a [networkId](#term-networkid) has no `ENABLED` address at order-creation time, the request fails with `50609 NO_ENABLED_ADDRESS`.

> Receiving addresses are configured at the **chain level** (not per-token): a single address receives every [DSPay](#term-dspay)-supported stablecoin on that chain (e.g. both [USDT](#term-usdt) and [USDC](#term-usdc)).

For each chain you want to accept payments on, configure the following in the [DSPay](#term-dspay) portal:
- **[networkId](#term-networkid)** (the chain)
- **The receiving address**
- **Status** (`ENABLED` / `DISABLED`)

> When an order is created, [DSPay](#term-dspay) picks one of the `ENABLED` addresses for that [networkId](#term-networkid) as the order's receiving address. If there is no `ENABLED` address, order creation fails with `50609`.

> Address management (CRUD + enable/disable) is performed entirely in the portal UI.

### 3.4 Portal Configuration Overview

The [DSPay](#term-dspay) portal exposes two critical configuration surfaces — every merchant must understand their purpose and defaults:

| Configuration | Location | Default | Purpose & impact |
|------|------|------|-----------|
| Webhook settings | Portal → Webhook Config | **Off** | Configure the webhook URL + on/off toggle. **Must be manually enabled** to receive payment notifications (orders succeed, users pay successfully, but without this toggle the merchant backend never receives a callback). |
| Key management | Portal → Security Settings | Active (`apiSecretEnabled`) | View [apiSecret](#term-apisecret) / freeze the key in emergencies (freezing suspends webhook delivery + order creation fails with `50503`) / regenerate a new key. |
| Order signature toggle | Portal → Merchant Settings | **On** (`orderSignatureEnabled=true`) | Controls whether `timestamp + signature` are validated on order creation. When disabled, the merchant can omit these fields, lowering the integration barrier but reducing security (recommended for test environments only). There is also a global toggle `signatureCheckGlobalEnabled` (ops-controlled, default true). |

**Critical reminders**:
- **The webhook toggle is OFF by default**: new merchants most often miss this — it must be flipped on manually in the portal.
- **First-time [apiSecret](#term-apisecret) provisioning**: auto-generated the first time you enable webhooks; viewable in the portal.
- **Order creation always requires the key**: [HMAC-SHA256](#term-hmac-sha256) signature verification is enforced — a frozen key means you cannot create orders.

### 3.5 Configure Webhook URL + Enable Webhooks

Configure the webhook URL (the HTTPS endpoint that will receive payment notifications) in the [DSPay](#term-dspay) portal and enable webhooks.

Key settings:
- **Webhook URL**: your callback endpoint (must be reachable from the public internet).
- **Webhook toggle**: **OFF** by default. You must **flip it on manually** in the portal.

> The first time you enable webhooks, an [apiSecret](#term-apisecret) is auto-generated if none exists. View it in the portal.

> Whenever the webhook URL changes, update it in the portal in lockstep.

### 3.6 Configure Contact Link (Optional)

You may configure a contact link in the [DSPay](#term-dspay) portal (customer-service URL / Telegram / email). The [DSPay](#term-dspay) checkout front-end queries and renders it for end users — if a payer runs into trouble, this is how they reach you.

> Optional, but strongly recommended for user experience.

### 3.7 ⚠️ Pitfalls (4)

1. **`50707` vs `50609` — different semantics**:
   - `50707 CHAIN_NOT_SUPPORTED` = the [networkId](#term-networkid) is not in the platform-wide 9-chain whitelist (platform-level unsupported).
   - `50609 NO_ENABLED_ADDRESS` = the chain is supported but the merchant has no `ENABLED` receiving address for it (merchant-level not configured).
   The remediation paths are completely different: for `50707`, check the [networkId](#term-networkid) spelling; for `50609`, configure an address in the portal.

2. **Platform whitelist vs merchant ENABLED addresses**:
   - The platform whitelist (the result of `GET /dspay/public/supported-chains`) defines "which chains [DSPay](#term-dspay) supports."
   - Merchant-level receiving addresses (configured in the [DSPay](#term-dspay) portal) define "which address this merchant uses on each chain."
   At order creation **both conditions must hold**: the [networkId](#term-networkid) is in the whitelist **and** the merchant has an `ENABLED` address for that [networkId](#term-networkid).

3. **The webhook toggle is OFF by default**: you must manually enable webhooks in the [DSPay](#term-dspay) portal, otherwise [DSPay](#term-dspay) will never dispatch a callback. New merchants routinely miss this step — orders succeed, users pay, but the merchant backend never gets notified.

4. **First-time webhook enable auto-generates [apiSecret](#term-apisecret)**: the first time webhooks are toggled on and there is no existing key, an [apiSecret](#term-apisecret) is provisioned automatically. Toggling off and back on does **not** regenerate. **The merchant must retrieve this [apiSecret](#term-apisecret) from the portal** before they can sign order-creation requests or verify webhook signatures.

---

## Chapter 4: Creating Your First Order

This chapter covers the end-to-end order creation flow: **querying supported chains & tokens**, the **fractional-suffix mechanism**, the **[HMAC-SHA256](#term-hmac-sha256) signature algorithm**, `BigDecimal` precision handling, and Java / Node.js end-to-end samples.

> **Signature validation toggle**: Merchant-level `orderSignatureEnabled` (default true) controls whether `timestamp + signature` are validated. When disabled, the merchant can omit these fields, lowering the integration barrier but reducing security (recommended for test environments only). There is also a global toggle `signatureCheckGlobalEnabled` (ops-controlled, default true).

### 4.1 Order Suffix Mechanism (In Depth)

**Why does the response's `payAmount` come back as `100.001` instead of the `100` the merchant sent?**

[DSPay](#term-dspay) uses a **fractional-suffix mechanism** to differentiate concurrent orders of the same amount. If multiple orders are all priced at 100 [USDT](#term-usdt), [DSPay](#term-dspay) appends a unique suffix to each (e.g. `100.001` / `100.002` / `100.003`). The user is expected to pay the exact suffixed amount; [DSPay](#term-dspay) uses the suffix to auto-match the order on-chain.

**Key facts**:
- Merchant submits `productPrice=100` (2 decimal places), response returns `payAmount=100.001` (3 decimal places, suffix included).
- The request's `payAmount` parameter and the response's `payAmount` **are not the same**: the request is the merchant's intended amount; the response is the [DSPay](#term-dspay)-generated amount with the suffix baked in.
- The user must pay the **response's** `payAmount` (with suffix); otherwise chain detection will not match.

### 4.2 Query Supported Chains & Tokens

Before creating an order, call the following public endpoint to get the chain and token whitelist currently supported by [DSPay](#term-dspay):

```
GET /dspay/public/supported-chains
```

**Request**: No parameters (public endpoint — no [JWT](#term-jwt) or [merchantNo](#term-merchantno) required).

**Response**: JSON array, each element containing a chain and its supported stablecoins.

**curl Example**:

```bash
curl https://dspay.example.com/dspay/public/supported-chains
```

```json
[
  {
    "networkId": "evm--1",
    "chainName": "Ethereum",
    "chainLogo": "https://cdn.example.com/chain/ethereum.png",
    "tokens": [
      {
        "symbol": "USDT",
        "address": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "logoUri": "https://cdn.example.com/token/usdt.png"
      },
      {
        "symbol": "USDC",
        "address": "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
        "logoUri": "https://cdn.example.com/token/usdc.png"
      }
    ]
  }
]
```

**Relationship to Order Creation**: The response's `networkId` and `tokens[].address` map directly to the create-order endpoint's `networkId` and `contractAddress` parameters.

| Response Field | Create-Order Parameter | Description |
|---------------|------------------------|-------------|
| `networkId` | `networkId` | Chain identifier — use directly in the create-order request. |
| `tokens[].address` | `contractAddress` | Token contract address — use directly in the create-order request. |
| `tokens[].symbol` | — | Token symbol (e.g. `USDT`) for display confirmation. |

Full field descriptions in [§3.1](#query-supported-chains-tokens), networkId reference table in [§3.2](#networkid-cheat-sheet).

### 4.3 Create-Order Endpoint

`POST /dspay/order/create` is a **public endpoint** (no [JWT](#term-jwt) required). To prevent third parties from impersonating the merchant, **every create-order request must carry an [HMAC-SHA256](#term-hmac-sha256) signature**.

**Preconditions**:
- You have an `apiSecret` (viewable in the [DSPay](#term-dspay) portal).
- The target [networkId](#term-networkid) (chain) has at least one `ENABLED` receiving address.
- The server clock is [NTP](#term-ntp)-synced (±5-minute window).

#### Request Body

| Field | Type | Required | Constraint | Description |
|------|------|------|------|------|
| `merchantNo` | string | ✓ | non-empty | Your merchant identifier. |
| `productPrice` | decimal | ✗ | optional | Product price (optional; recorded only, not used in computation). |
| `productPriceCurrency` | string | ✗ | optional | Price currency (optional, any fiat e.g. USD/CNY/EUR; recorded only, not used in computation). |
| `networkId` | string | ✓ | in 9-chain whitelist | Chain [networkId](#term-networkid), e.g. `evm--1` (see [§3.1](#query-supported-chains-tokens)). |
| `contractAddress` | string | ✓ | in token whitelist | Token contract address (see [§3.1](#query-supported-chains-tokens)). |
| `payAmount` | decimal | ✓ | ≥0.0000000001 | Amount payable (**excluding suffix**; [DSPay](#term-dspay) appends it automatically). |
| `outOrderNo` | string | ✗ | ≤64 chars | Merchant's own order ID (**included in signature**, empty value when not provided; echoed back in callbacks). |
| `productId` | string | ✗ | ≤64 chars | Merchant product ID (not included in signature; recorded + echoed in callbacks). |
| `timestamp` | long | **Conditional** | within ±5min window | Unix-epoch millisecond timestamp (required when `orderSignatureEnabled=true`; see [§4.6](#timestamp-window)). |
| `signature` | string | **Conditional** | ≤128 chars | [HMAC-SHA256](#term-hmac-sha256) hex lowercase (required when `orderSignatureEnabled=true`; see [§4.4](#signature-canonical-string)). |

#### Response Body

| Field | Type | Description |
|------|------|------|
| `orderNo` | string | [DSPay](#term-dspay) order ID (e.g. `DS00000120260702000001`). |
| `outOrderNo` | string\|null | Merchant's original order ID (echoed back; null if not supplied). |
| `productId` | string\|null | Merchant product ID (returned as-is, null when not provided). |
| `productPrice` | decimal\|null | Product price (optional, null when not provided). |
| `productPriceCurrency` | string\|null | Price currency (optional, null when not provided). |
| `networkId` | string | Chain [networkId](#term-networkid). |
| `contractAddress` | string | Token contract address. |
| `tokenSymbol` | string | Token symbol (e.g. `USDT`). |
| `payAmount` | decimal | **Actual payable amount (with suffix)** — the user must pay exactly this. |
| `originPayAmount` | decimal | Product price (suffix excluded). |
| `amountSuffix` | decimal | Order-identification suffix (0 = no suffix). |
| `usdAmount` | decimal | USD snapshot (locked at creation; used for reporting). |
| `exchangeRate` | decimal\|null | Merchant-supplied rate snapshot (1 token = X fiat), null when `productPrice` is not provided. |
| `receivingAddress` | string | Receiving address (where the user sends funds). |
| `qrCodeUrl` | string\|null | QR-code payload (currently not returned; merchants can construct the checkout URL as `${payPageBaseUrl}?orderNo=xxx`). |
| `expireAt` | long | Expiry time (Unix-epoch millisecond timestamp). |
| `createAt` | long | Creation time (Unix-epoch millisecond timestamp). |
| `status` | string | Order status (`CREATED` upon creation). |

> 💡 The user must pay the response's `payAmount` (with suffix), not the request's `payAmount`. See [§4.1](#order-suffix-mechanism-in-depth).

### 4.4 Signature Canonical String

Concatenate the request parameters in the **fixed order** shown below, using `key=value&` style (values are **not** URL-encoded). The **minimal signing set is 4 fields**:

```
merchantNo={merchantNo}&outOrderNo={outOrderNo}&payAmount={payAmount}&timestamp={timestamp}
```

**Example**:
```
merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=99.99&timestamp=1717689600000
```

> ⚠️ **Order Sensitive**
>
> HMAC-SHA256 hashes the **byte sequence**; field-order mismatch → different bytes → different hash → verification failure (`50613`). **Clients MUST concatenate in the exact order above.** The server-side `DspayOrderSignatureSupport.buildPayload` hard-codes this order via `String.join("&", ...)`.
>
> | Position | Field | Empty handling |
> |---|---|---|
> | 1 | `merchantNo` | non-empty, concatenated as-is |
> | 2 | `outOrderNo` | null/blank → empty string (key retained: `outOrderNo=`) |
> | 3 | `payAmount` | `BigDecimal.toPlainString()`, no scientific notation |
> | 4 | `timestamp` | millisecond long, concatenated as-is |
>
> The JSON field order in the HTTP body does **not** affect the signature (the canonical string is used, not the JSON body).

> **Important**:
> - `outOrderNo` is optional but **included in signature**. When not provided or blank, the value is an empty string (the key is retained), e.g. `outOrderNo=`.
> - `merchantNo` / `outOrderNo` / `payAmount` / `timestamp` constitute the minimal signing set (anti-spoofing, anti-order-id-substitution, anti-amount-tampering, anti-replay).
> - **Removed**: `productPrice` / `productPriceCurrency` / `productId` — fiat-side / merchant-internal fields that don't affect on-chain fund safety; removing them lowers integration complexity.
> - **Removed**: `networkId` / `contractAddress` — allows frontend users to switch chain/token freely without re-signing.
> - **Security note**: After removing `productPrice` / `productPriceCurrency` / `productId`, these three fields are vulnerable to MITM tampering. Since `payAmount` is still signed (on-chain fund safety is guaranteed), tampering only affects merchant-side fiat statistics/reconciliation, not on-chain transfers. Merchants who require integrity of fiat-side data should additionally compare these three fields against the values at order creation after webhook verification.

### 4.5 Signature Algorithm

[HMAC-SHA256](#term-hmac-sha256), output is **lowercase hex** (same as the webhook signature).

> **Important**: use the `apiSecret` string **directly as the HMAC key** — `secret.getBytes(UTF_8)` — **do NOT Base64-decode first**. Base64Url is only the storage encoding of the key; [HMAC-SHA256](#term-hmac-sha256) is encoding-agnostic about the key byte sequence.

### 4.6 Timestamp Window

`timestamp` (milliseconds) must be within **±5 minutes** of the [DSPay](#term-dspay) server clock, otherwise the request is rejected with `50614 ORDER_TIMESTAMP_EXPIRED`. We strongly recommend enabling [NTP](#term-ntp) on the merchant server.

### 4.7 BigDecimal Serialization

The signing field `payAmount` must be sent as a **plain numeric string** (no scientific notation):

| Language | Method |
|------|------|
| Java | `BigDecimal.toPlainString()` |
| Node.js | `toFixed(decimalPlaces)` or the `Big.js` library |
| Python | `format(value, 'f')` |

> Otherwise `1e-10` and `0.0000000001` will produce different canonical strings and signature verification will fail (`50613`).

### 4.8 Java End-to-End Demo

> **Environment**: JDK 11+ (required for `java.net.http.HttpClient`). Pure JDK, zero third-party dependencies.
>
> **Build & Run**:
> ```bash
> # No dependencies needed — compile and run directly
> javac DspayCreateOrderDemo.java && java DspayCreateOrderDemo
> ```

```java
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * DSPay create-order end-to-end demo.
 * Pure JDK 11+, zero third-party dependencies. JSON built manually to avoid Jackson.
 */
public class DspayCreateOrderDemo {

    private static final String DSPAY_BASE_URL = "https://dspay.example.com";
    private static final String CASHIER_BASE_URL = "https://cashier.ds.pro";
    private static final String API_SECRET = "your-apiSecret"; // Obtain from the DSPay merchant portal

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // Plain HTTP must be 1.1 (not h2c)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        // 1. Business parameters (BigDecimal for deterministic serialization)
        String merchantNo = "DSM1";
        BigDecimal productPrice = new BigDecimal("99.99");        // order field (not in signature)
        String currency = "USD";                                  // order field (not in signature)
        String networkId = "evm--1";                              // order field (not in signature)
        String contractAddress = "0xdac17f958d2ee523a2206206994597c13d831ec7"; // same as above
        BigDecimal payAmount = new BigDecimal("100");             // signing field
        String outOrderNo = "MY-ORDER-001";                       // signing field (optional; empty value when not provided)
        String productId = "PROD-001";                            // order field (not in signature)

        // 2. Generate timestamp + signature (4 signing fields, fixed order: merchantNo / outOrderNo / payAmount / timestamp)
        long timestamp = System.currentTimeMillis();
        String signature = signOrder(merchantNo, outOrderNo,
                payAmount, timestamp, API_SECRET);

        // 3. Manually build JSON body (pure JDK, zero third-party deps)
        //    Amount fields use toPlainString() to avoid scientific notation
        String jsonBody = "{" +
                "\"merchantNo\":\"" + esc(merchantNo) + "\"," +
                "\"productPrice\":" + (productPrice != null ? productPrice.toPlainString() : "null") + "," +
                "\"productPriceCurrency\":" + (currency != null ? "\"" + esc(currency) + "\"" : "null") + "," +
                "\"networkId\":\"" + esc(networkId) + "\"," +
                "\"contractAddress\":\"" + esc(contractAddress) + "\"," +
                "\"payAmount\":" + payAmount.toPlainString() + "," +
                "\"outOrderNo\":\"" + esc(outOrderNo) + "\"," +
                "\"productId\":\"" + esc(productId) + "\"," +
                "\"timestamp\":" + timestamp + "," +
                "\"signature\":\"" + esc(signature) + "\"" +
                "}";

        // 4. Send the HTTP request
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DSPAY_BASE_URL + "/dspay/order/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP " + resp.statusCode());
        String body = resp.body();
        System.out.println(body);

        // 5. Extract orderNo and build the cashier URL
        String orderNo = extractField(body, "orderNo");
        if (orderNo != null) {
            String cashierUrl = CASHIER_BASE_URL + "?orderNo=" + orderNo;
            System.out.println("\nCashier URL: " + cashierUrl);
            System.out.println("Open this link to pay on the cashier page.");
        }
    }

    /**
     * Canonical string → HMAC-SHA256 → lowercase hex.
     * 4 fields (fixed order: merchantNo / outOrderNo / payAmount / timestamp, order-sensitive).
     */
    static String signOrder(String merchantNo, String outOrderNo,
                            BigDecimal payAmount, long timestamp,
                            String secret) throws Exception {
        String canonical = String.join("&",
                "merchantNo=" + merchantNo,
                "outOrderNo=" + normalizeOpt(outOrderNo),
                "payAmount=" + payAmount.toPlainString(),
                "timestamp=" + timestamp);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Escape backslash and double-quote for JSON string values. */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** null or blank → "", else trim (aligned with server-side normalizeOptional). */
    static String normalizeOpt(String s) {
        return (s == null || s.trim().isEmpty()) ? "" : s.trim();
    }

    /** Extract a string field from JSON by key (simple regex, demo use only). */
    static String extractField(String json, String key) {
        java.util.regex.Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
```

### 4.9 Node.js End-to-End Demo

> **Environment**: Node.js 18+ (uses built-in `fetch`). Zero third-party dependencies.
>
> **Run**:
> ```bash
> # No npm install needed — run directly
> node create-order.js
> ```

```javascript
const crypto = require('crypto');

const DSPAY_BASE_URL = 'https://dspay.example.com';
const CASHIER_BASE_URL = 'https://cashier.ds.pro';
const API_SECRET = 'your-apiSecret'; // Obtain from the DSPay merchant portal

/**
 * Create-order end-to-end demo (Node 18+ has a built-in fetch).
 * Amount fields MUST be strings — avoid number precision loss / scientific notation.
 */
async function createOrder() {
    // 1. Business parameters (amounts as strings)
    //    Signing fields: merchantNo / payAmount / outOrderNo (+ timestamp)
    //    productPrice/productPriceCurrency/productId/networkId/contractAddress are order fields (not in signature)
    const params = {
        merchantNo: 'DSM1',
        productPrice: '99.99',
        productPriceCurrency: 'USD',
        networkId: 'evm--1',
        contractAddress: '0xdac17f958d2ee523a2206206994597c13d831ec7',
        payAmount: '100',
        outOrderNo: 'MY-ORDER-001',   // optional, but included in signature
        productId: 'PROD-001',         // order field (not in signature)
    };

    // 2. Generate timestamp + signature
    const timestamp = Date.now();
    const signature = signOrder(params, timestamp, API_SECRET);

    // 3. Send the HTTP request
    const resp = await fetch(`${DSPAY_BASE_URL}/dspay/order/create`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...params, timestamp, signature }),
    });

    const body = await resp.json();
    console.log('HTTP', resp.status);
    console.log(JSON.stringify(body, null, 2));

    // 4. Extract orderNo and build the cashier URL
    const orderNo = body?.data?.orderNo;
    if (orderNo) {
        const cashierUrl = `${CASHIER_BASE_URL}?orderNo=${orderNo}`;
        console.log('\nCashier URL:', cashierUrl);
        console.log('Open this link to pay on the cashier page.');
    }
}

function signOrder(p, timestamp, secret) {
    const opt = (v) => (v == null || String(v).trim() === '') ? '' : String(v).trim();
    const canonical = [
        `merchantNo=${p.merchantNo}`,
        `outOrderNo=${opt(p.outOrderNo)}`,
        `payAmount=${p.payAmount}`,
        `timestamp=${timestamp}`,
    ].join('&');

    return crypto.createHmac('sha256', secret)
        .update(canonical, 'utf8')
        .digest('hex');
}

createOrder().catch(console.error);
```

### 4.10 Signature-Failure Triage Table

| Error code | Root cause | Triage direction |
|--------|------|----------|
| `50613 ORDER_SIGNATURE_INVALID` | No [apiSecret](#term-apisecret) configured for the merchant | Enable webhooks in the [DSPay](#term-dspay) portal (auto-generates [apiSecret](#term-apisecret)) or run `regenerate` from the portal. |
| `50613 ORDER_SIGNATURE_INVALID` | Signature computed incorrectly | Inspect the canonical-string field order, BigDecimal serialization, and [apiSecret](#term-apisecret) accuracy. |
| `50614 ORDER_TIMESTAMP_EXPIRED` | Timestamp outside ±5 minutes | Check server clock sync ([NTP](#term-ntp)). |

### 4.11 ⚠️ Pitfalls (5)

1. **BigDecimal must use `toPlainString()`**: `new BigDecimal("0.0000000001").toString()` may emit `1E-10`, while `toPlainString()` emits `0.0000000001`. Any mismatch in the canonical string → `50613`. `payAmount` participates in the signature; in Java you **must** use `.toPlainString()` — never `toString()`.

2. **Node.js amount fields must be strings**: `payAmount` (in the signature) must be a string literal like `'99.99'` or use `Big.js`. Never use JS `number` — it drifts into scientific notation for values beyond `Number.MAX_SAFE_INTEGER` or for very small decimals, breaking the canonical string.

3. **±5-minute timestamp window**: the server must run [NTP](#term-ntp). Drift beyond the window → `50614`. Docker containers: verify the guest clock is synced to the host.

4. **Order-suffix mechanism**: the response's `payAmount` includes the suffix (e.g. `100.001`), **not** the `100` the merchant submitted. `suffixScale = originScale + maxOrderDigits`; when `suffixScale > Math.min(tokenDecimals, 18)` (precision saturated), [DSPay](#term-dspay) throws `50612`. Your checkout UI must display the **response's** `payAmount` — never the request parameter.

5. **Signing field set + order sensitivity**: `merchantNo` / `outOrderNo` / `payAmount` / `timestamp` participate in the signature, **concatenated in this exact order**. HMAC-SHA256 hashes the byte sequence, so **any field-order mismatch → signature mismatch → `50613`**. `outOrderNo` is optional but **included in signature** (when not provided or blank, the value is an empty string while the key is retained, e.g. `&outOrderNo=`). `productPrice` / `productPriceCurrency` / `productId` / `networkId` / `contractAddress` are **NOT included in signature**, but are still submitted as order fields and echoed verbatim. Use `outOrderNo` to cross-link to your own order ID — [DSPay](#term-dspay) does not enforce uniqueness; the merchant must.

---

## Chapter 5: Handling Webhooks

This chapter covers the webhook handling pipeline: the **order state machine**, the **four-step signature verification**, replay-attack defense, idempotency design, the strict response contract, and retry policy.

### 5.1 Order State Machine (Read This First)

```
                    ┌──(10min)──→ TIMEOUT ──┐
                    │                        │
 CREATED ───────────┴────────────────────────┴──(40min from create)──→ CLOSED
   │                                                                   │
   │                                                                   │
   └──────────────(chain detection / supplement)──────────────────────┘
                              ↓
                          COMPLETED ──(refund)──→ REFUNDED
```

| State | Meaning | Webhook? | Auto-detect? | Manual supplement? |
|------|------|-----------|---------|-----------|
| `CREATED` | Awaiting payment (10min countdown) | — | ✅ Scanned | ✅ |
| `TIMEOUT` | 10min elapsed with no payment (still waiting) | ❌ **No** | ✅ Scanned | ✅ |
| `CLOSED` | 40min elapsed; system closed the order | ✅ `CLOSED` sent | ❌ **Stopped** | ✅ (reopens, `reopened=true`) |
| `COMPLETED` | On-chain settlement / supplement complete | ✅ `COMPLETED` sent | — | — |
| `REFUNDED` | Merchant refund succeeded | ✅ `REFUNDED` sent | — | — |

> The create-order response includes `expireAt = createAt + 10min`; the front end should drive its countdown off this field instead of polling for the `TIMEOUT` transition.

**Three behaviors that are easily missed**:

1. **`TIMEOUT` does not fire a webhook**: the 10-minute mark is a transitional state; the order is still waiting for payment. The [DSPay](#term-dspay) checkout frontend polls the order status and renders a "Payment timed out" UI on its own — merchants do not need to poll.

2. **Auto-detection stops once `CLOSED`**: the [DSPay](#term-dspay) auto-detection job only scans orders in `CREATED` / `TIMEOUT`. Once an order transitions to `CLOSED`, subsequent on-chain settlements are **not** auto-confirmed — the merchant must trigger **manual supplement** from the [DSPay](#term-dspay) portal (the `reopened=true` path).

3. **Supplement does not require amount match**: auto-detection demands the on-chain amount match `payAmount` exactly (`compareTo == 0`); manual supplement does not check amount, it only records the difference (`amountDiff = actual − payable`) — the merchant decides whether to accept.

> 💡 **Why does auto-detection stop after `CLOSED`?**
> `CLOSED` is the terminal "40-minute give-up" state. The order has already dispatched a `CLOSED` event (the merchant may have already cancelled the order and released inventory). If auto-detection kept running, it would repeatedly trigger `CLOSED → COMPLETED` reopenings and reconciliation chaos would ensue. The design choice is therefore "after CLOSED, only manual supplement" — the merchant decides whether to revive the order.

### 5.2 When Will I Receive a Webhook

When an order transitions into **`COMPLETED` / `CLOSED` / `REFUNDED`**, [DSPay](#term-dspay) sends an HTTP POST to the merchant-configured `notifyUrl`.

> ⚠️ **The `CREATED → TIMEOUT` transition does NOT fire a webhook.** `TIMEOUT` is a transitional state; the order is still waiting for payment and chain detection.

### 5.3 Webhook Protocol

| Item | Value |
|------|------|
| Method | POST |
| Content-Type | application/json |
| Encoding | UTF-8 |
| Timeout guidance | [DSPay](#term-dspay) side has no timeout (RestTemplate default); **merchant side should set a 5s timeout**. |
| Redirects | Not followed. |

### 5.4 Request Headers

| Header | Description |
|--------|------|
| `Content-Type` | `application/json` |
| `X-DSPay-Signature` | [HMAC-SHA256](#term-hmac-sha256) lowercase hex signature (algorithm in [5.6](#four-step-webhook-verification)). |

### 5.5 Payload Schema

```json
{
  "orderNo": "DS202406071234567890",
  "outOrderNo": "MY-ORDER-20260715-001",
  "eventType": "COMPLETED",
  "status": "COMPLETED",
  "payAmount": "99.99",
  "originPayAmount": "100.00",
  "amountSuffix": "0.01",
  "actualReceivedAmount": "99.98",
  "actualUsdAmount": "99.98",
  "refundAmount": null,
  "refundUsdAmount": null,
  "refundTxHash": null,
  "txHash": "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
  "tokenSymbol": "USDT",
  "networkId": "evm--1",
  "reopened": false,
  "timestamp": 1717689600000
}
```

**Field reference**:

| Field | Type | Always returned | Description |
|------|------|------|------|
| `orderNo` | string | Yes | Order ID, e.g. `DS2024...` |
| `outOrderNo` | string\|null | Yes | Merchant's original order ID (echoed from create-order; null if not supplied). |
| `eventType` | string | Yes | Event type: `COMPLETED` / `CLOSED` / `REFUNDED`. |
| `status` | string | Yes | Current order status enum. |
| `payAmount` | string\|null | Yes | Actual paid amount (suffix included; Decimal string). |
| `originPayAmount` | string\|null | Yes | Product price (suffix excluded). |
| `amountSuffix` | string\|null | Yes | Order-identification suffix (used for exact on-chain matching). |
| `actualReceivedAmount` | string\|null | Yes | Actual settled amount (after gas deductions). |
| `actualUsdAmount` | string\|null | Yes | Actual received USD value (locked at completion: CHAIN_DETECTION ≈ usdAmount; SUPPLEMENT = actual × supplement-time price). |
| `refundAmount` | string\|null | Yes | Refund token amount (non-null only for REFUNDED events). |
| `refundUsdAmount` | string\|null | Yes | Refund USD value (locked at refund = refundAmount × refund-time price). |
| `refundTxHash` | string\|null | Yes | Refund transaction hash (non-null only for REFUNDED events). |
| `txHash` | string\|null | Yes | On-chain transaction hash. |
| `tokenSymbol` | string | Yes | Token symbol, e.g. `USDT`. |
| `networkId` | string | Yes | Network ID, e.g. `evm--1`. |
| `reopened` | boolean | Yes | Whether this is the post-`CLOSED` reopen path (settlement arrived after the order had already timed out). |
| `timestamp` | long | Yes | [DSPay](#term-dspay)-side send timestamp (ms). |

> **Amount-field notes (§5.1.5)**:
> - `originPayAmount`: product price — identical to what the merchant supplied at order creation.
> - `amountSuffix`: the suffix appended to differentiate same-amount concurrent orders.
> - `payAmount` = `originPayAmount` + `amountSuffix`, and equals the actual on-chain transfer amount.
> - **For exact-match amount verification, prefer `originPayAmount`** (matches the product price) or `payAmount` (with suffix; matches the on-chain amount).

### 5.6 Four-Step Webhook Verification

**Algorithm**: [HMAC-SHA256](#term-hmac-sha256)
**Signed content**: the raw HTTP body bytes (the exact string before deserialization)
**Output format**: lowercase hex

**Four-step verification flow**:

1. **Extract the raw body**: use the original HTTP body string. **Never** deserialize and re-`JSON.stringify()` — field-order changes will break the signature.
2. **Compute [HMAC-SHA256](#term-hmac-sha256)**: use `apiSecret.getBytes(UTF_8)` as the key (**no Base64 decode**) over the raw body.
3. **Compare signatures**: constant-time comparison against the `X-DSPay-Signature` header (defends against timing attacks); case-insensitive.
4. **Validate the timestamp window**: the payload's `timestamp` field must be within ±5 minutes of the current time (replay defense).

#### Java Verification Sample

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class DspaySignatureVerifier {
    /**
     * @param payload   The raw HTTP body string.
     * @param signature The X-DSPay-Signature header value (lowercase hex).
     * @param secret    The apiSecret string (used directly; do NOT Base64-decode).
     */
    public static boolean verify(String payload, String signature, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        // Critical: use secret.getBytes directly — do NOT Base64-decode.
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        String expectedHex = bytesToHex(expected);
        // Constant-time compare to defend against timing attacks.
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
```

#### Node.js Verification Sample

```javascript
const crypto = require('crypto');

function verifySignature(payload, signature, secret) {
    // Critical: use secret directly as the key — do NOT Base64-decode.
    const expected = crypto
        .createHmac('sha256', secret)
        .update(payload, 'utf8')
        .digest('hex');
    // Use timingSafeEqual to defend against timing attacks.
    const expectedBuf = Buffer.from(expected, 'utf8');
    const sigBuf = Buffer.from(signature.toLowerCase(), 'utf8');
    if (expectedBuf.length !== sigBuf.length) return false;
    return crypto.timingSafeEqual(expectedBuf, sigBuf);
}
```

### 5.7 Replay-Attack Defense

**Threat model**: an attacker intercepts a legitimate [DSPay](#term-dspay) webhook (valid signature included) and replays it against the merchant endpoint to trick the merchant into shipping twice.

**Defense**: validate that the `timestamp` field is within an acceptable window.

```java
public class ReplayAttackGuard {
    private static final long TOLERANCE_MS = 5 * 60_000L; // 5 minutes

    /**
     * @param timestamp The callback payload's `timestamp` field (ms).
     * @return true = fresh (within 5 min); false = expired or skew-too-large (likely replay).
     */
    public static boolean isFresh(long timestamp) {
        long now = System.currentTimeMillis();
        return Math.abs(now - timestamp) < TOLERANCE_MS;
    }
}
```

> **Why 5 minutes of tolerance**: it accommodates imperfect server clock sync plus [DSPay](#term-dspay)-side network latency. Anything beyond 5 minutes is treated as anomalous.

### 5.8 Idempotency

[DSPay](#term-dspay)'s retry policy (see [5.9](#response-contract-strict-mode)) may deliver the same event more than once. Merchants **must** de-duplicate on `orderNo + eventType`.

#### Recommended Implementation A — DB Unique Key

```sql
CREATE TABLE notify_processed (
    order_no   VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_no, event_type)
);
```

```java
public void handleNotify(NotifyPayload payload) {
    try {
        // INSERT IGNORE: a duplicate insert returns affected rows = 0
        int affected = jdbc.update(
            "INSERT IGNORE INTO notify_processed (order_no, event_type) VALUES (?, ?)",
            payload.getOrderNo(), payload.getEventType()
        );
        if (affected == 0) {
            // Duplicate callback — ACK immediately to stop DSPay from retrying.
            return;
        }
        // First-time handling — execute business logic.
        fulfillOrder(payload.getOrderNo());
    } catch (Exception e) {
        // Business exception — do NOT ACK; let DSPay retry.
        throw e;
    }
}
```

#### Recommended Implementation B — Redis SETNX

```java
public void handleNotify(NotifyPayload payload) {
    String key = "notify:" + payload.getOrderNo() + ":" + payload.getEventType();
    boolean firstTime = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(30));
    if (!firstTime) {
        // Duplicate callback — ACK immediately to stop DSPay from retrying.
        return;
    }
    // First-time handling — execute business logic.
    fulfillOrder(payload.getOrderNo());
}
```

> **Why `orderNo + eventType` and not just `orderNo`?**
> The same order may sequentially receive `COMPLETED` → `REFUNDED`; the two events are semantically distinct and must not overwrite each other.

### 5.9 Response Contract (Strict Mode)

**Success response**:

```
HTTP 200
Content-Type: application/json

{"code":"SUCCESS","msg":"ok"}
```

**Strict matching rules**:

- `code` must be the string `"SUCCESS"` (uppercase)
- ❌ `"success"` (lowercase) — rejected
- ❌ `"Success"` (title-cased) — rejected
- ❌ `"SUCCESS "` (trailing whitespace) — rejected
- ❌ `200` (numeric) — rejected
- ❌ `{"data":{"code":"SUCCESS"}}` (nested) — rejected
- ✅ `{"code":"SUCCESS","extra":"x"}` (extra fields tolerated)
- ✅ `{"code":"SUCCESS","msg":"any message"}` (`msg` content is not checked)

**Failure response**: anything else triggers a [DSPay](#term-dspay) retry.

**Retry policy**:

| Attempt | Delay |
|--------|------|
| 1st | Immediate |
| 2nd | +1 minute |
| 3rd | +5 minutes |
| Terminal | After 3 failures → `FAILED` (no more retries). |

### 5.10 Event Types

| eventType | Trigger | Recommended merchant handling |
|-----------|----------|-------------|
| `COMPLETED` | On-chain settlement confirmed; order complete. | Mark paid / ship. |
| `CLOSED` | Order timed out and was closed by the system. | Cancel the order / mark expired. |
| `REFUNDED` | Merchant-initiated refund succeeded. | Update refund status. |
| `COMPLETED` + `reopened=true` | Post-`CLOSED` settlement, order reopened. | Edge case — handle per business policy. |

> `reopened=true` scenario: the user paid only after the order had timed out and gone to `CLOSED`, but the on-chain settlement is real. [DSPay](#term-dspay) reopens the order and emits a `COMPLETED` event with `reopened=true`; merchants can use this signal for special handling (request a top-up from the user or escalate for manual review).

### 5.11 ⚠️ Pitfalls (6)

1. **Verify the raw body**: use the original HTTP body string — never deserialize and re-`JSON.stringify()` (field-order changes break the signature). In Java use `@RequestBody String rawBody`; in Node.js use the `raw-body` package to capture the raw byte stream. This is the **single most common** cause of webhook verification failures.

2. **HMAC secret is used directly — do NOT Base64-decode**: [apiSecret](#term-apisecret) is a 43-char Base64Url string, but when used as an HMAC key, call `secret.getBytes(UTF_8)` directly. Base64Url is only the storage encoding — [HMAC-SHA256](#term-hmac-sha256) is encoding-agnostic about the key byte sequence.

3. **`SUCCESS` is case-sensitive**: must be exactly `{"code":"SUCCESS"}` uppercase. `"success"` / `"Success"` / `"SUCCESS "` (trailing whitespace) / numeric `200` / nested structures all get rejected and trigger retries. The `code` field is compared with `equals` — no `trim`, no `equalsIgnoreCase`.

4. **±5-minute timestamp window for replay defense**: the callback payload's `timestamp` must be within 5 minutes. This is the core replay defense — an attacker replaying an old webhook will fail this check. The merchant server must run [NTP](#term-ntp).

5. **`TIMEOUT` does not fire a webhook**: the 10-minute mark is a transitional state; the order keeps waiting for payment. The [DSPay](#term-dspay) checkout frontend polls the status on its own. **Only `CLOSED` / `COMPLETED` / `REFUNDED` fire webhooks.**

6. **Idempotency key is `orderNo + eventType`** — not just `orderNo`. The same order may sequentially receive `COMPLETED → REFUNDED`; they are semantically distinct and must not overwrite each other. Use `(orderNo, eventType)` as the unique key; only ACK duplicate `(orderNo, eventType)` pairs immediately.

---

## Chapter 6: Reconciliation & Operations

This chapter covers day-2 operations: order reporting, **amount reconciliation strategy** (`originPayAmount` vs `payAmount`), webhook monitoring & alerting, and scheduled key rotation.

### 6.1 Order List & Reporting

The order list and reporting dashboards are available in the **[DSPay](#term-dspay) merchant portal UI**, supporting time-range filters, amount roll-ups, 7-day breakdowns, etc.

> Reports aggregate on the **order-creation time** dimension. For cross-timezone operations, note that "today" is defined by the timezone configured in the merchant portal.

### 6.2 Amount Reconciliation Strategy

[DSPay](#term-dspay) orders expose three amount fields — pick the right one for the reconciliation scenario:

| Field | Meaning | Use case |
|------|------|------|
| `originPayAmount` | Product price (suffix excluded) | **✅ Recommended for finance reconciliation** — matches the amount in the merchant's business system. |
| `amountSuffix` | Order-identification suffix | Used for exact order matching. |
| `payAmount` | `originPayAmount + amountSuffix` (with suffix) | Matches the actual on-chain transfer amount. |

**Reconciliation guidance**:
- For **finance reconciliation**, compare against `originPayAmount` (product price) — otherwise the suffix will trigger spurious "amount mismatch" alerts.
- For **on-chain transaction audit**, compare against `payAmount` (with suffix) — matches the user's actual transfer.
- `actualReceivedAmount` is the post-gas settled amount — use this for net-revenue calculations.

### 6.3 Webhook Log Monitoring & Alerting

**Monitoring SQL** (wire into your alerting system):

```sql
-- # of webhooks in FAILED state over the past hour
SELECT COUNT(*) FROM dspay_notify_log
WHERE status = 'FAILED'
  AND completed_at > NOW() - INTERVAL 1 HOUR;
```

**Alert rules**:

| Metric | Threshold | Meaning |
|------|------|------|
| FAILED count in last 1h | > 5 | Merchant webhook URL unhealthy or merchant backend down. |
| RETRYING backlog (current) | > 50 | Merchant service degraded — webhook consumption rate too slow. |
| Consecutive FAILED for a single merchant | > 3 | Configuration error specific to that merchant. |

### 6.4 Scheduled Key Rotation

Rotate [apiSecret](#term-apisecret) regularly (e.g. quarterly) from the [DSPay](#term-dspay) portal.

**Rotation steps**:

1. During a **low-traffic window**, run `regenerate` from the portal.
2. **Immediately** update the [apiSecret](#term-apisecret) configuration on the merchant backend.
3. Monitor webhook verification success rate for 5 minutes.

> In-flight webhooks at the moment of `regenerate` were signed with the old key — the merchant may see brief verification failures. [DSPay](#term-dspay) will auto-retry (1min / 5min) with the new key. The merchant only needs to tolerate a verification-failure window of a few minutes.

### 6.5 ⚠️ Pitfalls (4)

1. **Use `originPayAmount` for reconciliation**: `payAmount` includes the suffix (e.g. `100.001`), while `originPayAmount` is the product price (`100`). Compare against `originPayAmount` for reconciliation — otherwise the suffix difference raises spurious "amount mismatch" alerts. `payAmount` is for on-chain transaction audit only.

2. **In-flight webhooks fail verification after `regenerate`**: at the instant of `regenerate`, already-dispatched webhooks are signed with the old key — the merchant's verification with the new key will fail. [DSPay](#term-dspay) auto-retries (1min / 5min) with the new key. Tolerate the brief failure window — **do not roll back to the old key**.

3. **3 retries → `FAILED` is terminal**: no more auto-retries. Inspect unprocessed orders in the [DSPay](#term-dspay) portal or set an alert (FAILED > 5 in 1h). The merchant backend should have a "manual re-process for FAILED webhooks" runbook.

4. **Statistics use `COALESCE(actual_usd_amount, usd_amount)`**: prefers the actual received USD value locked at completion, falls back to the creation-time `usd_amount` snapshot. The cumulative number reflects "locked" USD value, not real-time. Merchants needing real-time USD valuations must recompute as `on-chain amount × current rate` themselves.

---

## Chapter 7: Exception-Handling SOPs

This chapter covers the standard operating procedures for exception scenarios: timeout-no-pay, **post-`CLOSED` supplement**, refunds, and the disable/regenerate decision tree for key-compromise incidents.

### 7.1 Timeout — No Payment (`TIMEOUT`)

**Symptom**: 10 minutes elapsed after order creation with no payment; order transitions to `TIMEOUT`.

**[DSPay](#term-dspay) behavior**:
- ❌ **No webhook** (`TIMEOUT` is a transitional state).
- ✅ The order keeps waiting for payment + chain auto-detection.
- ✅ The user has up to 40 minutes to pay (until `CLOSED`).

**Merchant handling**:
- Frontend: render "Payment timed out — you can still pay" messaging.
- The [DSPay](#term-dspay) checkout frontend polls the order status automatically.
- **Do not** rely on a webhook to detect `TIMEOUT`.

### 7.2 On-Chain Settlement After `CLOSED` (Supplement Flow)

**Symptom**: order transitioned to `CLOSED`, then the user completed the on-chain transfer — the settlement is real.

**[DSPay](#term-dspay) behavior**:
- ❌ **Auto-detection is stopped**: the [DSPay](#term-dspay) auto-detection job only scans `CREATED` / `TIMEOUT`. After `CLOSED`, no auto-confirmation.
- ✅ The merchant must trigger **manual supplement**.

**Supplement flow** (in the [DSPay](#term-dspay) portal):

1. Inspect the actual on-chain settled amount on the order-detail page.
2. Confirm the settlement and trigger supplement (order reopens as `COMPLETED`).
3. Merchant receives a `COMPLETED` + `reopened=true` webhook.

> **Supplement does not enforce amount match**: [DSPay](#term-dspay) records `actualReceivedAmount` + `amountDiff` only — the merchant decides whether to accept. Before supplementing, inspect the actual on-chain amount in the portal; manually review large discrepancies.

### 7.3 Refund Flow

Refunds are initiated from the **[DSPay](#term-dspay) portal**.

**Constraints**:
- Only `COMPLETED` orders can be refunded.
- `REFUNDED` is terminal and irreversible.
- A successful refund triggers a `REFUNDED` webhook.

### 7.4 Key-Compromise Incident Decision Tree

When `apiSecret` is suspected of compromise, choose based on the scenario:

| Scenario | Action | Effect |
|------|------|------|
| After-hours, no engineer on call | Portal → Security Settings → **Freeze key** | Old key is invalidated immediately (webhook delivery suspended + order creation fails with `50503`). Emergency stop-bleed. **No new key is generated.** |
| Compromise confirmed | Portal → Security Settings → **Regenerate** | Old key invalidated, new key generated (the merchant backend must be updated in lockstep). |
| After triage, confirmed not leaked | Portal → Security Settings → **Restore key** | Old key becomes valid again. |

> ⚠️ **Critical rules**:
> - `regenerate` **does not unfreeze**: if the key is in frozen state, regenerate produces a new key but the frozen state persists — the merchant must explicitly restore it from the portal before it can be used.
> - If the key is **confirmed leaked**, strongly prefer `regenerate` to roll a fresh key — **do not simply restore the old one** (the attacker still has it).

### 7.5 ⚠️ Pitfalls (4)

1. **Auto-detection stops after `CLOSED`**: the [DSPay](#term-dspay) auto-detection job only scans `CREATED` / `TIMEOUT`. After `CLOSED`, even if the chain settles, no auto-confirmation — manual supplement from the [DSPay](#term-dspay) portal is required (`reopened=true`). Merchants need a "manual re-process for `CLOSED` orders" runbook, or monitor closed orders for late settlements.

2. **Supplement does not enforce amount match**: manual supplement records `actualReceivedAmount` + `amountDiff`; the merchant decides whether to accept. Inspect the actual on-chain amount in the portal before supplementing; manually review large discrepancies. [DSPay](#term-dspay) will not refuse supplement over amount mismatch.

3. **Key compromise: freeze vs regenerate**: after-hours / no engineer → freeze from the portal (stop-bleed); once confirmed → `regenerate` for a fresh key. Do not simply restore the old key — the attacker still has it.

4. **`regenerate` does not unfreeze**: regenerating while frozen produces a new key but keeps the frozen state — the merchant must explicitly restore it from the portal. The two operations must be executed separately; do not expect `regenerate` to auto-unfreeze.

---

## Chapter 8: Testing & Integration

This chapter covers the testing & integration workflow: local environment setup, ngrok-based webhook testing, recommended test sequence, and common signature-verification triage.

### 8.1 Local Environment Setup

- Start the [DSPay](#term-dspay) service: `mvn spring-boot:run -Dspring-boot.run.profiles=local`.
- Test-chain info: local defaults to each chain's mainnet RPC (same as production).
- Test tokens: use small amounts of real mainnet tokens (e.g. 0.01 [USDT](#term-usdt)).

### 8.2 Webhook Testing (ngrok / cpolar)

Local webhook debugging requires exposing your internal service to the public internet. Recommended tools:

- **ngrok**: `ngrok http 8080` → obtain a public URL.
- **cpolar**: more stable inside mainland China.

```bash
# Start ngrok
ngrok http 8080

# Once you have the URL, configure it as the webhook URL in the DSPay portal and enable webhooks.
```

### 8.3 Recommended Test Sequence

Test in the order below — skipping ahead makes failures hard to attribute:

1. **Verify the signing algorithm in isolation**: compare your signature output against an online [HMAC-SHA256](#term-hmac-sha256) tool.
   - Copy the canonical string + [apiSecret](#term-apisecret).
   - Compute [HMAC-SHA256](#term-hmac-sha256) hex in the online tool.
   - Compare against your code's output.
2. **End-to-end order creation**: use the demos in [§4.8 / §4.9](#java-end-to-end-demo) to create an order; confirm the response contains `orderNo` + `payAmount` (with suffix).
3. **Trigger webhook verification**: pay with real tokens; trigger the `COMPLETED` webhook; confirm verification passes.
4. **Test idempotency**: manually replay a webhook; confirm the merchant backend does not double-ship.

### 8.4 Test Tokens

Use small amounts of mainnet stablecoins per chain:
- Ethereum: 0.01 [USDT](#term-usdt) (`0xdac17f958d2ee523a2206206994597c13d831ec7`).
- BSC: 0.01 [USDT](#term-usdt) (`0x55d398326f99059fF775485246999027B3197955`).
- Tron: 0.01 [USDT](#term-usdt) (`TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t`).

> The amount must match the response's `payAmount` exactly (with suffix); otherwise chain detection will not match.

### 8.5 Common Signature-Verification Failure Triage

| Cause | Triage |
|------|---------|
| Payload was deserialized then re-serialized (field order changed) | Verify against the raw body string. |
| Secret was Base64-decoded | Use the secret string directly; do not Base64-decode. |
| Large clock skew (rejected by replay defense) | Check whether `timestamp` is within 5 minutes. |
| Key was regenerated (using old key) | View the latest key in the [DSPay](#term-dspay) portal. |

### 8.6 ⚠️ Pitfalls (3)

1. **Test sequence**: do not jump straight to end-to-end testing. Verify the signing algorithm in isolation first (compare against an online HMAC tool). Skipping this step makes "signature failed" impossible to attribute — is it the algorithm or the transport layer?

2. **ngrok URL churn**: the free-tier URL changes on every restart; you must re-configure it in the [DSPay](#term-dspay) portal. If ngrok restarts mid-test, update [DSPay](#term-dspay)'s `notifyUrl` in lockstep — otherwise webhooks go to the stale URL and fail.

3. **Local HTTP client uses `HTTP_1_1`**: when calling `http://localhost:xxxx` (plain HTTP, not HTTPS), Java's HttpClient must be `HTTP_1_1`, not `HTTP_2` (plain-text h2c is unsupported by most servers → Connection reset). Reserve `HTTP_2` for HTTPS.

---

## Chapter 9: FAQ

This chapter organizes common questions into five categories — authentication, signing, orders, webhooks, configuration — for quick reference.

### 9.1 Authentication

**Q: What happens when the portal session expires?**
A: Portal sessions use a sliding 7-day window — each interaction auto-extends by 7 days. After 7 days of inactivity, the session expires and you must sign in again from the [DSPay](#term-dspay) portal with your wallet. Merchant backend integrations (order creation, webhook verification) use [apiSecret](#term-apisecret) signing and do not depend on the portal session — they are unaffected.

**Q: Can a read-only (watch-only) wallet sign in?**
A: No. [SIWE](#term-siwe) requires a private-key signature; watch-only wallets cannot `personal_sign`. You must sign in with a wallet that holds the private key.

### 9.2 Signing

**Q: Signature verification keeps failing (`50613`) — why?**
A: 90% of the time it's BigDecimal scientific notation. `new BigDecimal("0.0000000001").toString()` may emit `1E-10`; you must call `toPlainString()` to get `0.0000000001`. Inspect the canonical string for any `E` characters in the amount fields.

**Q: How do I handle amount precision in Node.js?**
A: `productPrice` and `payAmount` must be string literals like `'99.99'` or use Big.js — never JS `number`. JS `number` drifts into scientific notation past `Number.MAX_SAFE_INTEGER` or for very small decimals.

**Q: Does [apiSecret](#term-apisecret) need to be Base64-decoded before use?**
A: **No.** The [apiSecret](#term-apisecret) string is used directly as the HMAC key. Base64Url is only the storage encoding — [HMAC-SHA256](#term-hmac-sha256) is encoding-agnostic about the key byte sequence. Pass `secret.getBytes(UTF_8)` directly to `SecretKeySpec`.

**Q: What happens if the signature field order is wrong?**
A: The signature will not match → `50613`. You must concatenate in the strict order `merchantNo → outOrderNo → payAmount → timestamp` (4 fields), joined with `&`. HMAC-SHA256 hashes the byte sequence, so any field-order mismatch breaks the signature. Note: `outOrderNo` is optional but **included in signature** (empty string when not provided). `productPrice` / `productPriceCurrency` / `productId` and `networkId` / `contractAddress` are **NOT included** in the signature.

### 9.3 Orders

**Q: Why is the response's `payAmount` different from what I sent?**
A: The suffix mechanism. [DSPay](#term-dspay) appends a unique suffix (e.g. `100.001`) to differentiate same-amount concurrent orders. The user must pay the response's `payAmount` (with suffix); otherwise chain detection will not match. For reconciliation, use `originPayAmount` (product price).

**Q: Order creation fails with `50609 NO_ENABLED_ADDRESS`?**
A: The chain is supported, but the merchant has no `ENABLED` receiving address for that [networkId](#term-networkid) (chain). Configure a receiving address in the [DSPay](#term-dspay) portal.

**Q: Order creation fails with `50707 CHAIN_NOT_SUPPORTED`?**
A: The [networkId](#term-networkid) is not in the 9-chain whitelist. Check the [networkId](#term-networkid) spelling (e.g. `evm--1`, not `ethereum-mainnet`). Defer to `GET /dspay/public/supported-chains` for the authoritative list.

**Q: Concurrent order creation fails with `50610`/`50611`/`50612`?**
A: Suffix-mechanism concurrency issue. `50610 ORDER_CREATE_BUSY` (suffix-lock contention, retry); `50611 SUFFIX_EXHAUSTED` (suffix slots exhausted, wait for concurrency to drain); `50612 SUFFIX_PRECISION_SATURATED` (precision saturated, `suffixScale > Math.min(tokenDecimals, 18)`).

### 9.4 Webhooks

**Q: Webhook signature verification keeps failing?**
A: 90% of the time the payload was deserialized then re-serialized, changing field order. Verify against the raw HTTP body string — never against `JSON.stringify(parsed_object)`.

**Q: Webhook keeps retrying — what do I do?**
A: Check that your response matches the strict `{"code":"SUCCESS"}` format. `code` must be the uppercase string `"SUCCESS"` — numeric `200`, lowercase, and nested structures are all rejected.

**Q: What's the retry policy?**
A: Immediate / +1min / +5min — 3 attempts; after that, the webhook transitions to `FAILED` (terminal, no more retries). Inspect unprocessed orders in the [DSPay](#term-dspay) portal.

**Q: What does `reopened=true` mean?**
A: The user paid only after the order had timed out and gone to `CLOSED`, but the on-chain settlement is real. [DSPay](#term-dspay) reopens the order and emits a `COMPLETED` + `reopened=true` webhook. Merchants can use this signal for special handling (request a top-up or escalate for manual review).

**Q: How do I do idempotency?**
A: De-duplicate on `orderNo + eventType` (not just `orderNo`). The same order may sequentially receive `COMPLETED → REFUNDED`; they are semantically distinct and must not overwrite each other. Use a DB unique key on `(order_no, event_type)`, or Redis SETNX on `notify:{orderNo}:{eventType}`.

**Q: Does `TIMEOUT` fire a webhook?**
A: **No.** `TIMEOUT` is a transitional state — 10 minutes elapsed with no payment; the order is still waiting. Only `CLOSED` / `COMPLETED` / `REFUNDED` fire webhooks. The [DSPay](#term-dspay) checkout frontend polls the order status on its own.

### 9.5 Configuration

**Q: What do I do if the key is leaked?**
A: After-hours emergency → freeze the key in the [DSPay](#term-dspay) portal (stop-bleed); once confirmed → `regenerate` for a fresh key. Do not simply restore the old key.

**Q: What's the difference between the platform whitelist and the merchant's ENABLED addresses?**
A: The platform whitelist (returned by `GET /dspay/public/supported-chains`) defines "which chains [DSPay](#term-dspay) supports"; the merchant-level receiving addresses (configured in the [DSPay](#term-dspay) portal) define "which address this merchant uses on each chain." Both conditions must hold at order creation.

**Q: How do I handle `50503 API_SECRET_DISABLED`?**
A: The key has been frozen. If you froze it yourself, restore it from the portal after triage; if someone else did, contact the merchant administrator.

---

## Appendix A: Java Reference integration

> Production-ready snippets you can copy directly into your codebase: a utility class (signing + verification), create-order client, webhook controller.
>
> **Environment**: JDK 11+.
> - **A.1 (Signing Utility)**: Pure JDK, zero dependencies — copy into any Java project.
> - **A.2/A.3 (Spring integration)**: Requires Spring Boot 2.7+ / 3.x + Lombok.
>
> **Maven dependencies** (only needed for A.2/A.3; A.1 requires none):
> ```xml
> <dependency>
>     <groupId>org.springframework.boot</groupId>
>     <artifactId>spring-boot-starter-web</artifactId>
> </dependency>
> <dependency>
>     <groupId>org.projectlombok</groupId>
>     <artifactId>lombok</artifactId>
>     <optional>true</optional>
> </dependency>
> ```

#### A.1 Signing Utility (one file, both directions)

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * DSPay HMAC-SHA256 signing utility (drop into your merchant backend).
 *
 * <p>Both directions share the same apiSecret:
 * <ul>
 *   <li>{@link #signOrder}      — Merchant → DSPay: signs the canonical string at order creation.</li>
 *   <li>{@link #verifyCallback} — DSPay → Merchant: verifies the raw body of an incoming webhook.</li>
 * </ul>
 */
public class DspaySigner {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Order-creation signing: canonical string → HMAC-SHA256 → lowercase hex.
     * 4 fields (fixed order: merchantNo / outOrderNo / payAmount / timestamp, order-sensitive).
     */
    public static String signOrder(String merchantNo, String outOrderNo,
                                   BigDecimal payAmount, long timestamp,
                                   String apiSecret) {
        String canonical = String.join("&",
                "merchantNo=" + merchantNo,
                "outOrderNo=" + normalizeOptional(outOrderNo),
                "payAmount=" + payAmount.toPlainString(),
                "timestamp=" + timestamp);
        return hmacSha256Hex(canonical, apiSecret);
    }

    /** Webhook verification: HMAC over the raw body, constant-time compare against X-DSPay-Signature (timing-attack safe). */
    public static boolean verifyCallback(String rawBody, String signature, String apiSecret) {
        if (apiSecret == null || apiSecret.isEmpty() || rawBody == null || signature == null) {
            return false;
        }
        String expected = hmacSha256Hex(rawBody, apiSecret);
        if (expected.isEmpty()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** null or blank → "", else trim (aligned with server-side normalizeOptional). */
    static String normalizeOptional(String s) {
        return (s == null || s.trim().isEmpty()) ? "" : s.trim();
    }
}
```

#### A.2 Build Cashier Payment URL

> Use case: the merchant backend receives a checkout request from the front end → signs locally → builds a cashier URL → returns the URL to the front end (302 redirect or return for display).
> A full runnable demo is in [§4.8](#java-end-to-end-demo); this is a Spring `Service` integration sample.
>
> **Environment**: JDK 11+, Spring Boot 2.7+ / 3.x, Lombok.
>
> **Maven dependencies** (usually already present in a Spring Boot project):
> ```xml
> <dependency>
>     <groupId>org.springframework.boot</groupId>
>     <artifactId>spring-boot-starter-web</artifactId>
> </dependency>
> <dependency>
>     <groupId>org.projectlombok</groupId>
>     <artifactId>lombok</artifactId>
>     <optional>true</optional>
> </dependency>
> ```

```java
// Signing utility DspaySigner is in A.1 — copy it directly into your project
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class DspayCashierService {

    // Base URL of the cashier page
    private static final String CASHIER_BASE_URL = "https://cashier.ds.pro";

    @Value("${dspay.api-secret}")
    private String apiSecret;

    /**
     * Build a cashier payment URL. The front end redirects or embeds this URL
     * in an iframe. The signature covers 4 fields only (anti-spoof / amount
     * integrity / replay protection). productPrice / productPriceCurrency /
     * productId are optional and for display only.
     */
    public String buildCashierUrl(String merchantNo, BigDecimal productPrice,
                                   String currency, BigDecimal payAmount,
                                   String outOrderNo, String productId) {
        long timestamp = System.currentTimeMillis();
        String signature = DspaySigner.signOrder(merchantNo, outOrderNo,
                payAmount, timestamp, apiSecret);

        // Build cashier URL (values are URL-encoded; parameter order is irrelevant)
        StringBuilder url = new StringBuilder(CASHIER_BASE_URL).append("?");
        url.append("merchantNo=").append(URLEncoder.encode(merchantNo, StandardCharsets.UTF_8));
        url.append("&outOrderNo=").append(URLEncoder.encode(
                outOrderNo != null ? outOrderNo : "", StandardCharsets.UTF_8));
        url.append("&payAmount=").append(URLEncoder.encode(
                payAmount.toPlainString(), StandardCharsets.UTF_8));
        url.append("&timestamp=").append(timestamp);
        url.append("&signature=").append(URLEncoder.encode(signature, StandardCharsets.UTF_8));
        if (productPrice != null) {
            url.append("&productPrice=").append(URLEncoder.encode(
                    productPrice.toPlainString(), StandardCharsets.UTF_8));
        }
        if (currency != null) {
            url.append("&productPriceCurrency=").append(URLEncoder.encode(
                    currency, StandardCharsets.UTF_8));
        }
        if (productId != null) {
            url.append("&productId=").append(URLEncoder.encode(
                    productId, StandardCharsets.UTF_8));
        }
        return url.toString();
    }
}
```

> **Flow**: the merchant does NOT call the DSPay order-creation API directly. Instead, it signs locally and generates a cashier URL. The user opens the cashier page, selects a chain/token, and then the cashier front end calls the DSPay API to create the order. The signature guarantees that the merchant ID, amount, and timestamp cannot be tampered with by the front end.

#### A.3 Webhook Handler (DSPay → merchant backend)

> **Environment**: Same as A.2 (JDK 11+, Spring Boot 2.7+ / 3.x, Lombok).
> Deserialization uses Jackson (already included in `spring-boot-starter-web`).

```java
// Signing utility DspaySigner is in A.1 — copy it directly into your project
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Slf4j
public class DspayNotifyController {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Replay window: ±5 minutes, aligned with DSPay server */
    private static final long REPLAY_WINDOW_MS = 5 * 60 * 1000;

    @Value("${dspay.api-secret}")
    private String apiSecret;

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> handleNotify(
            @RequestBody String rawBody,
            @RequestHeader("X-DSPay-Signature") String signature) {

        // 1. Verify signature (against the raw body string; see utility A.1)
        if (!DspaySigner.verifyCallback(rawBody, signature, apiSecret)) {
            log.warn("Signature verification failed signature={}", signature);
            return ResponseEntity.status(401).build();
        }

        // 2. Parse the payload (Jackson is bundled in spring-boot-starter-web)
        NotifyPayload payload;
        try {
            payload = JSON.readValue(rawBody, NotifyPayload.class);
        } catch (Exception e) {
            log.error("JSON parse failed", e);
            return ResponseEntity.badRequest().build();
        }

        // 3. Replay defense (±5 minute window)
        long now = System.currentTimeMillis();
        if (Math.abs(now - payload.getTimestamp()) > REPLAY_WINDOW_MS) {
            log.warn("timestamp expired — likely replay orderNo={}", payload.getOrderNo());
            return ResponseEntity.status(401).build();
        }

        // 4. Idempotent processing + business logic
        try {
            handleNotifyBusiness(payload);
        } catch (Exception e) {
            log.error("Business handling failed — let DSPay retry orderNo={}", payload.getOrderNo(), e);
            return ResponseEntity.status(500).build();
        }

        // 5. Return the strict success response
        return ResponseEntity.ok(Map.of("code", "SUCCESS", "msg", "ok"));
    }
}
```

> **Critical points**:
> - Use `@RequestBody String rawBody` to capture the original string — **do not** let Spring deserialize into `NotifyPayload` first (any subsequent `toJson()` would reorder fields and break verification).
> - Deserialize into business objects **only after** verification using Jackson `readValue()`.

---

## Appendix B: Node.js reference integration

> Production-ready snippets you can copy directly into your codebase: a utility module, create-order route, webhook handler.
>
> **Environment**: Node.js 18+.
>
> **Install dependencies** (B.2/B.3 need Express):
> ```bash
> npm install express raw-body
> # B.1 signing module needs no install — crypto is built into Node.js
> ```

#### B.1 Signing Utility Module (`dspay-signer.js`, one file, both directions)

```javascript
const crypto = require('crypto');

/**
 * DSPay HMAC-SHA256 signing utility (drop into your merchant backend).
 *
 * Both directions share the same apiSecret:
 *   - signOrder      Merchant → DSPay: signs the canonical string at order creation.
 *   - verifyCallback DSPay → Merchant: verifies the raw body of an incoming webhook.
 */

/**
 * Order-creation signing: canonical string → HMAC-SHA256 → lowercase hex.
 * 4 fields (fixed order: merchantNo / outOrderNo / payAmount / timestamp, order-sensitive).
 * Note: amount fields must be strings (avoid number precision loss).
 */
function signOrder({ merchantNo, payAmount, outOrderNo },
                   timestamp, apiSecret) {
    const opt = (v) => (v == null || String(v).trim() === '') ? '' : String(v).trim();
    const canonical = [
        `merchantNo=${merchantNo}`,
        `outOrderNo=${opt(outOrderNo)}`,
        `payAmount=${payAmount}`,
        `timestamp=${timestamp}`,
    ].join('&');

    return crypto.createHmac('sha256', apiSecret)
        .update(canonical, 'utf8')
        .digest('hex');
}

/**
 * Webhook verification: HMAC over the raw body, constant-time compare against X-DSPay-Signature.
 * Uses crypto.timingSafeEqual to defend against timing attacks.
 */
function verifyCallback(rawBody, signature, apiSecret) {
    if (!apiSecret || !rawBody || !signature) return false;

    const expected = crypto.createHmac('sha256', apiSecret)
        .update(rawBody, 'utf8')
        .digest('hex');

    const expectedBuf = Buffer.from(expected, 'utf8');
    const sigBuf = Buffer.from(signature.toLowerCase(), 'utf8');
    if (expectedBuf.length !== sigBuf.length) return false;
    return crypto.timingSafeEqual(expectedBuf, sigBuf);
}

module.exports = { signOrder, verifyCallback };
```

#### B.2 Build Cashier Payment URL

> A full runnable demo is in [§4.9](#node.js-end-to-end-demo); this is an Express route integration sample.

```javascript
const express = require('express');
const { signOrder } = require('./dspay-signer');

const router = express.Router();
// Base URL of the cashier page
const CASHIER_BASE_URL = 'https://cashier.ds.pro';
const API_SECRET = process.env.DSPAY_API_SECRET;

router.post('/api/cashier-url', (req, res) => {
    const { merchantNo, productPrice, currency,
            payAmount, outOrderNo, productId } = req.body;

    // Generate timestamp + signature
    const timestamp = Date.now();
    const signature = signOrder(
        { merchantNo, outOrderNo, payAmount },
        timestamp, API_SECRET);

    // Build cashier URL (signing params + optional display params)
    const params = new URLSearchParams();
    params.set('merchantNo', merchantNo);
    params.set('outOrderNo', outOrderNo != null ? outOrderNo : '');
    params.set('payAmount', payAmount);
    params.set('timestamp', String(timestamp));
    params.set('signature', signature);
    if (productPrice != null) params.set('productPrice', productPrice);
    if (currency != null) params.set('productPriceCurrency', currency);
    if (productId != null) params.set('productId', productId);

    const cashierUrl = CASHIER_BASE_URL + '?' + params.toString();
    res.json({ cashierUrl });
});

module.exports = router;
```

> **Flow**: the merchant does NOT call the DSPay order-creation API directly. Instead, it signs locally and generates a cashier URL. The user opens the cashier page, selects a chain/token, and then the cashier front end calls the DSPay API to create the order. The signature guarantees that the merchant ID, amount, and timestamp cannot be tampered with by the front end.

#### B.3 Webhook Handler (DSPay → merchant backend)

```javascript
const express = require('express');
const rawBody = require('raw-body');
const { verifyCallback } = require('./dspay-signer');

const app = express();
const API_SECRET = process.env.DSPAY_API_SECRET;

// Signature verification MUST use the raw body — do not let express.json() consume it first.
app.post('/notify', async (req, res) => {
    const raw = (await rawBody(req)).toString('utf8');
    const signature = req.headers['x-dspay-signature'];

    // 1. Verify signature (see utility B.1)
    if (!verifyCallback(raw, signature, API_SECRET)) {
        return res.status(401).json({ code: 'FAIL', msg: 'Signature verification failed' });
    }

    // 2. Parse + replay defense + idempotency
    const payload = JSON.parse(raw);
    const now = Date.now();
    if (Math.abs(now - payload.timestamp) > 5 * 60_000) {
        return res.status(401).json({ code: 'FAIL', msg: 'timestamp expired' });
    }

    // 3. Business logic
    try {
        await handleOrder(payload);
    } catch (e) {
        return res.status(500).json({ code: 'FAIL', msg: 'business exception' });
    }

    // 4. Strict success response
    res.json({ code: 'SUCCESS', msg: 'ok' });
});
```

> **Critical point**: use `raw-body` to capture the raw byte stream for signature verification — **do not** use `JSON.stringify(req.body)` after `express.json()` (field-order changes will break the signature).

---

## Appendix C: Error Code Reference

Source: `DspayExceptionConstant.java`, grouped by error-code range.

#### General Errors (400xx)

| code | msg | Description |
|------|------|------|
| 40001 | PARAM_ERROR | Parameter validation failed. |
| 40101 | UNAUTHORIZED | Not authenticated. |
| 40301 | FORBIDDEN | Insufficient permissions. |
| 40401 | NOT_FOUND | Resource not found. |
| 40901 | STATE_CONFLICT | State conflict. |
| 50000 | INTERNAL_ERROR | Internal service error. |

#### Merchant (505xx)

| code | msg | Description |
|------|------|------|
| 50501 | MERCHANT_NOT_FOUND | Merchant does not exist. |
| 50502 | MERCHANT_DISABLED | Merchant has been disabled. |
| 50503 | API_SECRET_DISABLED | Merchant API secret has been frozen (webhook delivery suspended + order creation fails. Used for emergency key-compromise freeze). |

#### Order (506xx)

| code | msg | Description |
|------|------|------|
| 50601 | ORDER_NOT_FOUND | Order does not exist. |
| 50603 | ORDER_ALREADY_PAID | Order has already been paid. |
| 50604 | ORDER_EXPIRED | Order has expired. |
| 50605 | ORDER_STATUS_NOT_ALLOWED | Order status does not permit this operation. |
| 50606 | TX_HASH_INVALID | Transaction hash is invalid. |
| 50608 | TX_HASH_ALREADY_USED | Transaction hash has already been used (supplement only; refund no longer validates refundTxHash). |
| 50609 | NO_ENABLED_ADDRESS | No `ENABLED` receiving address (merchant has not configured one for this [networkId](#term-networkid) / chain). |
| 50610 | ORDER_CREATE_BUSY | Order creation busy (suffix-lock contention; retry). |
| 50611 | SUFFIX_EXHAUSTED | Suffix slots exhausted. |
| 50612 | SUFFIX_PRECISION_SATURATED | Suffix precision saturated (`suffixScale > Math.min(tokenDecimals, 18)`). |
| 50613 | ORDER_SIGNATURE_INVALID | Order-creation signature verification failed. |
| 50614 | ORDER_TIMESTAMP_EXPIRED | Order-creation timestamp outside ±5-minute window. |

#### Address (507xx)

| code | msg | Description |
|------|------|------|
| 50702 | ADDRESS_FORMAT_INVALID | Address format is invalid. |
| 50703 | ADDRESS_NOT_FOUND | Address does not exist. |
| 50704 | ADDRESS_NOT_IN_WALLET | Address does not belong to the current wallet. |
| 50705 | ADDRESS_NETWORK_MISMATCH | Address does not match the network. |
| 50706 | CHAIN_ADDRESS_ALREADY_BOUND | Address on this chain is already bound. |
| 50707 | CHAIN_NOT_SUPPORTED | Chain not supported ([networkId](#term-networkid) is not in the 9-chain whitelist or the chain is disabled). |

#### JWT Authentication (508xx)

| code | msg | Description |
|------|------|------|
| 50801 | JWT_TOKEN_MISSING | Missing [JWT](#term-jwt) token. |
| 50802 | JWT_TOKEN_INVALID | [JWT](#term-jwt) token is invalid. |
| 50803 | JWT_TOKEN_EXPIRED | [JWT](#term-jwt) token has expired (includes session expiry). |

#### SIWE Authentication (509xx)

| code | msg | Description |
|------|------|------|
| 50901 | SIWE_NONCE_NOT_FOUND | [SIWE](#term-siwe) [nonce](#term-nonce) does not exist. |
| 50902 | SIWE_NONCE_EXPIRED | [SIWE](#term-siwe) [nonce](#term-nonce) has expired (TTL 5 minutes). |
| 50903 | SIWE_SIGNATURE_INVALID | [SIWE](#term-siwe) signature is invalid (ecrecover-recovered address does not match). |
| 50904 | SIWE_DOMAIN_MISMATCH | [SIWE](#term-siwe) domain mismatch. |
| 50905 | SIWE_MESSAGE_INVALID | [SIWE](#term-siwe) message is invalid. |

