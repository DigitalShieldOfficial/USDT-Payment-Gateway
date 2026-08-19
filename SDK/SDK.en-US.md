## language
[English](SDK.en-US.md) | [中文](SDK.zh-CN.md)

# DSPay Merchant Integration Guide

> This document is the technical onboarding guide for [DSPay](#term-dspay) merchant integrators. It covers the full lifecycle: authentication, payout configuration, order creation, webhook handling, reconciliation, operations, and exception-handling SOPs. Each chapter is organized by technical topic and closes with a list of key caveats and common pitfalls.

---

<a id="table-of-contents"></a>
## Table of Contents

- [Glossary](#glossary)
- [Quick Start](#quick-start)
- [Chapter 1: Before You Begin](#chapter-1-before-you-begin)
- [Chapter 2: Onboarding & Authentication](#chapter-2-onboarding-authentication)
- [Chapter 3: Payment Configuration](#chapter-3-payment-configuration)
- [Chapter 4: Creating Your First Order](#chapter-4-creating-your-first-order)
- [Chapter 5: Handling Webhooks](#chapter-5-handling-webhooks)
- [Chapter 6: Reconciliation & Operations](#chapter-6-reconciliation-operations)
- [Chapter 7: Exception-Handling SOPs](#chapter-7-exception-handling-sops)
- [Chapter 8: Testing & Integration](#chapter-8-testing--integration)
- [Chapter 9: FAQ](#chapter-9-faq)
- [Appendix A: Java Reference Integration](#appendix-a-java-reference-integration)
- [Appendix B: Node.js Reference Integration](#appendix-b-nodejs-reference-integration)
- [Appendix C: Error Code Reference](#appendix-c-error-code-reference)

---

<a id="glossary"></a>
## Glossary

> A quick reference for the acronyms and proper nouns used throughout this document. New integrators should skim this first to avoid terminology confusion.

| Term | Description |
|------|------|
| <a id="term-dspay"></a>**DSPay** | A multi-chain stablecoin payment gateway (this service). |
| <a id="term-siwe"></a>**SIWE** | Sign-In with Ethereum — the EIP-4361 wallet-login standard. Merchants authenticate by signing a well-formed message with an [EVM](#term-evm) wallet. |
| <a id="term-jwt"></a>**JWT** | JSON Web Token. Used as the DSPay merchant-portal session credential (7-day sliding expiry). Cashier-URL signing and webhook verification do **not** rely on JWT — they use [apiSecret](#term-apisecret) instead (see [§2.2](#session-lifetime)). |
| <a id="term-apisecret"></a>**apiSecret** | Merchant API secret. Used to sign cashier URLs and verify incoming webhooks. Obtain it from the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) and store it securely. |
| <a id="term-merchantno"></a>**merchantNo** | Merchant business identifier (`DSM` prefix, e.g. `DSM1`). Required when generating cashier URLs. This is the **public-facing business code**, not the DB auto-increment primary key (avoids leaking transaction volume and prevents enumeration attacks). |
| <a id="term-networkid"></a>**networkId** | Canonical chain identifier (e.g. `evm--1` = Ethereum mainnet). Full list in [§3.2](#networkid-cheat-sheet). |
| <a id="term-contractaddress"></a>**contractAddress** | Token contract address. Combined with [networkId](#term-networkid) to uniquely identify a token at order creation. |
| <a id="term-hmac-sha256"></a>**HMAC-SHA256** | Hash-based Message Authentication Code. DSPay uses [apiSecret](#term-apisecret) as the key over a canonical string and outputs lowercase hex. |
| <a id="term-evm"></a>**EVM** | Ethereum Virtual Machine. "EVM-compatible chains" are chains that support Ethereum smart contracts (Ethereum / BSC / Polygon / Arbitrum / Base). |
| <a id="term-usdt"></a>**USDT** / <a id="term-usdc"></a>**USDC** | USD-pegged stablecoins (Tether USD / Centre USD Coin). |
| **Fractional Suffix (<a id="term-amountsuffix"></a>amountSuffix)** | A tiny decimal appended by DSPay to differentiate concurrent orders of the same amount (e.g. the `0.001` in `100.001`). Stablecoins are treated as 6-decimal tokens: merchants use at most the first 2 decimal places and DSPay uses the remaining 4 for the suffix. See [§4.1](#order-suffix-mechanism-in-depth). |
| **Manual Fulfillment (<a id="term-supplement"></a>supplement)** | The operator-confirmed action that reopens a `CLOSED` order as `COMPLETED` after an on-chain payment lands post-timeout. |
| **Webhook (<a id="term-webhook"></a>webhook)** | The HTTP POST notification DSPay sends to the merchant's [notifyUrl](#term-notifyurl). Event types: `CLOSED` / `COMPLETED` / `REFUNDED`. `CREATED` / `TIMEOUT` advance order state but do not emit webhooks. |
| <a id="term-notifyurl"></a>**notifyUrl** | The public HTTPS endpoint on the merchant side that receives DSPay webhooks. Must be reachable from the public internet. |
| <a id="term-ntp"></a>**NTP** | Network Time Protocol. Used for server clock synchronization. |
| <a id="term-nonce"></a>**nonce** | One-time random value used during [SIWE](#term-siwe) login to prevent replay attacks. |

> Every occurrence of these terms elsewhere in the document links back to this section.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="quick-start"></a>
## Quick Start (Your First Order in 5 Minutes)

> **Goal:** create an order with the minimum number of steps and inspect the full API response. If you prefer to understand the architecture first, jump to [Chapter 1](#chapter-1-before-you-begin).

**Pre-flight checklist**:

| Requirement | How to obtain | Time |
|------|---------|------|
| [DSPay](#term-dspay) merchant account | Sign in to the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) with an [EVM](#term-evm) wallet (see [§2.1](#merchant-sign-up-sign-in)) | 30 seconds |
| Receiving address (any chain) | Configure it in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) (see [§3.3](#configure-receiving-addresses)) | 1 minute |
| [apiSecret](#term-apisecret) | Generated the first time you enable webhooks in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) (see [§3.5](#configure-webhook-url-enable-webhooks)) | — |
| Test stablecoins | Have your wallet hold 0.01 [USDT](#term-usdt) | — |

> ⚠️ If you're missing any of the above, complete the relevant section before returning. Step 1 below is dependency-free, so you can try it immediately.

### Step 1 — Try the cashier in 3 minutes

The demo below uses the **local-sign + cashier URL** approach: no [DSPay](#term-dspay) API call needed — sign locally with HMAC-SHA256 and get a payment link directly. Users open the link, pick their chain/token on the cashier page, and the cashier frontend creates the order automatically.

> The merchant signs the amount and order number; the payer selects the chain and token in the cashier.

Fill in your `merchantNo` + `apiSecret`, save as `create-order.mjs`, and run `node create-order.mjs`.

**Node.js (Node 18+, zero third-party dependencies)**:

```javascript
import crypto from 'node:crypto';

// ===== [1] Merchant credentials (must fill in) =====
const MERCHANT_NO = 'DSM1';
const API_SECRET = 'your-apiSecret';  // Obtain from the DSPay merchant portal

// ===== [2] Fields used in the HMAC signature =====
const signed = {
  // Stablecoins use 6 decimals: merchants submit at most 2 decimal places and
  // DSPay uses the remaining 4 for its suffix. Plain decimal string only; no scientific notation.
  payAmount: '10.00',
  outOrderNo: 'MY-ORDER-001',   // Required: merchant external order ID; must not be blank
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
const canonical = `merchantNo=${MERCHANT_NO}&outOrderNo=${signed.outOrderNo}&payAmount=${signed.payAmount}&timestamp=${timestamp}`;

// ② HMAC-SHA256 (use secret.getBytes directly; do NOT Base64-decode)
const signature = crypto.createHmac('sha256', API_SECRET)
  .update(canonical, 'utf8').digest('hex');

// ③ Build the cashier URL
const url = new URL('https://cashier.ds.pro/');
url.searchParams.set('merchantNo', MERCHANT_NO);
url.searchParams.set('outOrderNo', signed.outOrderNo);
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
https://cashier.ds.pro?merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=10.00&timestamp=1717689600000&signature=7de3fafc...

The link is valid for 5 minutes. Open it in your browser now. Re-run the script if it expires.
```

> The URL above demonstrates the format only. Its `timestamp` and `signature` are sample values and cannot be used for a real payment. Run the code to generate a currently valid URL.

Open the cashier URL in your browser — you will see the payment page. Users pick their chain and token, then scan or transfer to complete the payment.

> 💡 The same signed-cashier-URL flow is used in production: keep `apiSecret` on the merchant backend and redirect the payer to the generated URL.

### Next Steps

1. [Receive webhooks](#chapter-5-handling-webhooks) — stand up a local endpoint to consume asynchronous payment notifications from [DSPay](#term-dspay).
2. [Order state machine](#order-state-machine-read-this-first) — understand the full `CREATED → TIMEOUT → CLOSED / COMPLETED` lifecycle.
3. [Testing & integration](#chapter-8-testing--integration) — ngrok for local webhooks + end-to-end verification + idempotency testing.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-1-before-you-begin"></a>
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

- **[DSPay Merchant Portal](https://mcashier.ds.pro/login/)**: sign in or register, obtain your `merchantNo`, and configure receiving addresses, the webhook URL, and `apiSecret`.
- **An [EVM](#term-evm) wallet**: required for [SIWE](#term-siwe) login (MetaMask / Rabby / Trust all work — anything that supports `personal_sign`).
- **A publicly reachable webhook URL**: where [DSPay](#term-dspay) will POST notifications (for local dev, use ngrok / cpolar).
- **[NTP](#term-ntp)-synced system clock**: signature verification enforces a ±5-minute window; the server clock must be accurate.
- **JDK 11+, Node.js 18+, or PHP 5.6+**: to run the corresponding demos. Other languages can follow the specification.
- **Test stablecoins**: a small amount of mainnet stablecoin on each chain you intend to test (e.g. 0.01 [USDT](#term-usdt)) for end-to-end verification.

### 1.5 Core Integration Cheat Sheet

**Merchant integration entry points**:

| API | Method | Purpose |
|---|---|---|
| Open cashier | `https://cashier.ds.pro/?...` | Open a merchant-backend-generated signed URL and create the order in the cashier. |
| Webhook | Merchant implements | Receive payment / closure / refund notifications from [DSPay](#term-dspay). |
| Query orders | POST /dspay/order/query | Query order state when a webhook was not received successfully (HMAC-signed). |

> All other operations — sign-up & login, receiving-address management, webhook URL configuration, order reports, refunds, manual fulfillment, key rotation — are performed in the **[DSPay Merchant Portal](https://mcashier.ds.pro/login/) UI** and require no API calls.
> The user-facing checkout frontend's order-status polling is implemented by the [DSPay](#term-dspay) team; merchants do not need to build it themselves.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-2-onboarding-authentication"></a>
## Chapter 2: Onboarding & Authentication

This chapter describes the merchant sign-up and authentication model, including how the portal login works, session lifetime, and how to obtain `apiSecret`.

<a id="merchant-sign-up-sign-in"></a>
### 2.1 Merchant Sign-Up & Sign-In

Merchants sign in to the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) with an [EVM](#term-evm) wallet via [SIWE](#term-siwe) (signature-based authentication). **The first sign-in automatically provisions a merchant account** — no separate registration step is required.

After signing in, you can obtain:
- **[merchantNo](#term-merchantno)**: your merchant business identifier (`DSM` prefix, e.g. `DSM1`). Required when generating cashier URLs.
- **[apiSecret](#term-apisecret)**: your API secret, used to sign cashier URLs and verify incoming webhooks. Displayed in the portal — store it securely.

<a id="session-lifetime"></a>
### 2.2 Session Lifetime

Portal sessions are valid for 7 days using a sliding window: each interaction auto-extends the session by another 7 days. After 7 days of inactivity, you must sign in again.

> Merchant backend integrations with [DSPay](#term-dspay) (order creation, webhook verification) use [apiSecret](#term-apisecret) signing — they do **not** depend on the [JWT](#term-jwt) session and are unaffected by this limit.

### 2.3 ⚠️ Pitfalls (2)

1. **First sign-in requires setup**: a fresh account has no [apiSecret](#term-apisecret), no receiving addresses, and no webhook URL. You must complete configuration in the portal before you can accept payments. Otherwise, order creation fails with [`50609`](#error-50609) `NO_ENABLED_ADDRESS`.

2. **Guard your [apiSecret](#term-apisecret)**: it is used for both signing and verification — leakage enables forged orders and forged webhooks. Rotate it regularly from the portal (see [§6.4 Key Rotation](#scheduled-key-rotation)).

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-3-payment-configuration"></a>
## Chapter 3: Payment Configuration

This chapter walks through the full payout configuration: chain/token **whitelist discovery**, **receiving-address setup**, webhook URL configuration, and the dual-toggle combinatorics matrix.

<a id="query-supported-chains-tokens"></a>
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
    "chainLogo": "https://assets.ds.pro/server-service-indexer/evm--1/tokens/address--1721282106924.png",
    "tokens": [
      {
        "symbol": "USDT",
        "address": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "logoUri": "https://ds-oss-prod.s3.ap-east-1.amazonaws.com/ds-oss-prod/1752200492611b1fe218f-73d8-40e4-a6ae-5a2fc52740a1.png"
      },
      {
        "symbol": "USDC",
        "address": "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
        "logoUri": "https://ds-oss-prod.s3.ap-east-1.amazonaws.com/ds-oss-prod/1752200934488ce7052b9-831d-4954-8eaa-b76555b0cce8.png"
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

<a id="networkid-cheat-sheet"></a>
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

<a id="configure-receiving-addresses"></a>
### 3.3 Configure Receiving Addresses

Merchants only need to configure `ENABLED` receiving addresses for the **chains they actually want to accept payments on** — chains you don't intend to support can be skipped. If a [networkId](#term-networkid) has no `ENABLED` address at order-creation time, the request fails with [`50609`](#error-50609) `NO_ENABLED_ADDRESS`.

> Receiving addresses are configured at the **chain level** (not per-token): a single address receives every [DSPay](#term-dspay)-supported stablecoin on that chain (e.g. both [USDT](#term-usdt) and [USDC](#term-usdc)).

For each chain you want to accept payments on, configure the following in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/):
- **[networkId](#term-networkid)** (the chain)
- **The receiving address**
- **Status** (`ENABLED` / `DISABLED`)

> When an order is created, [DSPay](#term-dspay) picks one of the `ENABLED` addresses for that [networkId](#term-networkid) as the order's receiving address. If there is no `ENABLED` address, order creation fails with [`50609`](#error-50609).

> Address management (CRUD + enable/disable) is performed entirely in the portal UI.

### 3.4 Portal Configuration Overview

The [DSPay Merchant Portal](https://mcashier.ds.pro/login/) exposes two critical configuration surfaces — every merchant must understand their purpose and defaults:

| Configuration | Location | Default | Purpose & impact |
|------|------|------|-----------|
| Webhook settings | Portal → Webhook Config | **Off** | Configure the webhook URL + on/off toggle. **Must be manually enabled** to receive `CLOSED` / `COMPLETED` / `REFUNDED` notifications (orders succeed, users pay successfully, but without this toggle the merchant backend never receives a callback). |
| Key management | Portal → Security Settings | Active (`apiSecretEnabled`) | View [apiSecret](#term-apisecret) / freeze the key in emergencies (freezing suspends webhook delivery + order creation fails with [`50503`](#error-50503)) / regenerate a new key. |

**Critical reminders**:
- **The webhook toggle is OFF by default**: new merchants most often miss this — it must be flipped on manually in the portal.
- **First-time [apiSecret](#term-apisecret) provisioning**: auto-generated the first time you enable webhooks; viewable in the portal.
- **Order creation always requires the key**: [HMAC-SHA256](#term-hmac-sha256) signature verification is enforced — a frozen key means you cannot create orders.

<a id="configure-webhook-url-enable-webhooks"></a>
### 3.5 Configure Webhook URL + Enable Webhooks

Configure the webhook URL (the HTTPS endpoint that will receive payment notifications) in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) and enable webhooks.

Key settings:
- **Webhook URL**: your callback endpoint (must be reachable from the public internet).
- **Webhook toggle**: **OFF** by default. You must **flip it on manually** in the portal.

> The first time you enable webhooks, an [apiSecret](#term-apisecret) is auto-generated if none exists. View it in the portal.

> Whenever the webhook URL changes, update it in the portal in lockstep.

### 3.6 Configure Contact Link (Optional)

You may configure a contact link in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) (customer-service URL / Telegram / email). The DSPay checkout front-end queries and renders it for end users — if a payer runs into trouble, this is how they reach you.

> Optional, but strongly recommended for user experience.

### 3.7 ⚠️ Pitfalls (4)

1. **[`50707`](#error-50707) vs [`50609`](#error-50609) — different semantics**:
   - [`50707`](#error-50707) `CHAIN_NOT_SUPPORTED` = the [networkId](#term-networkid) is not in the platform-wide 9-chain whitelist (platform-level unsupported).
   - [`50609`](#error-50609) `NO_ENABLED_ADDRESS` = the chain is supported but the merchant has no `ENABLED` receiving address for it (merchant-level not configured).
   The remediation paths are completely different: for [`50707`](#error-50707), check the [networkId](#term-networkid) spelling; for [`50609`](#error-50609), configure an address in the portal.

2. **Platform whitelist vs merchant ENABLED addresses**:
   - The platform whitelist (the result of `GET /dspay/public/supported-chains`) defines "which chains [DSPay](#term-dspay) supports."
   - Merchant-level receiving addresses (configured in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/)) define "which address this merchant uses on each chain."
   At order creation **both conditions must hold**: the [networkId](#term-networkid) is in the whitelist **and** the merchant has an `ENABLED` address for that [networkId](#term-networkid).

3. **The webhook toggle is OFF by default**: you must manually enable webhooks in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/), otherwise [DSPay](#term-dspay) will never dispatch a callback. New merchants routinely miss this step — orders succeed, users pay, but the merchant backend never gets notified.

4. **First-time webhook enable auto-generates [apiSecret](#term-apisecret)**: the first time webhooks are toggled on and there is no existing key, an [apiSecret](#term-apisecret) is provisioned automatically. Toggling off and back on does **not** regenerate. **The merchant must retrieve this [apiSecret](#term-apisecret) from the portal** before they can sign order-creation requests or verify webhook signatures.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-4-creating-your-first-order"></a>
## Chapter 4: Creating Your First Order

This chapter explains how a merchant creates the first order through the **Hosted Cashier**, including the suffix mechanism, cashier URL parameters, [HMAC-SHA256](#term-hmac-sha256) signing, timestamp and amount rules, and Java / Node.js / PHP examples.

<a id="order-suffix-mechanism-in-depth"></a>
### 4.1 Order Suffix Mechanism (In Depth)

**Why does the cashier display `100.001` when the merchant supplied `payAmount=100`?**

[DSPay](#term-dspay) uses a **fractional-suffix mechanism** to differentiate concurrent orders of the same amount. If multiple orders are all priced at 100 [USDT](#term-usdt), [DSPay](#term-dspay) appends a unique suffix to each (e.g. `100.001` / `100.002` / `100.003`). The user is expected to pay the exact suffixed amount; [DSPay](#term-dspay) uses the suffix to auto-match the order on-chain.

**Key facts**:
- The cashier URL carries the merchant's intended amount. The cashier display, webhook, and merchant-query result carry the [DSPay](#term-dspay)-generated amount with the suffix.
- The payer must send the exact `payAmount` displayed by the cashier; otherwise on-chain detection will not match.

**Input precision requirement**:

DSPay treats supported stablecoins as having **6 decimal places**. Merchants may use at most the first **2 decimal places**; DSPay uses the remaining **4 decimal places** for the order-identification suffix.

- `payAmount` must be greater than 0 and contain at most 2 decimal places: `100`, `100.1`, and `100.12` are valid; `100.123` is invalid.
- Use a plain decimal string. Never use scientific notation or convert through floating-point types.
- Avoid unnecessary trailing zeros: send `100.1` instead of `100.10` when possible.
- More than 2 decimal places causes cashier order creation to fail with [`50612`](#error-50612).

<a id="cashier-integration-flow"></a>
### 4.2 Cashier Integration Flow

Merchants do not select a specific chain/token or call an order-creation endpoint directly. The standard flow is:

1. On the merchant backend, prepare `merchantNo`, a unique `outOrderNo`, `payAmount`, and the current `timestamp`.
2. Calculate the [HMAC-SHA256](#term-hmac-sha256) signature with `apiSecret`.
3. URL-encode the required fields, signature, and optional product fields and append them to `https://cashier.ds.pro/`.
4. Return an HTTP 302 redirect, or return the generated cashier URL to the merchant frontend.
5. The payer opens the cashier and selects the chain and stablecoin. The cashier creates the order and displays the actual suffixed amount.

> Keep `apiSecret` on the merchant backend only. Never send it to a browser/mobile client or include it in the cashier URL.

**Preconditions**:

- Obtain `merchantNo` and `apiSecret` from the [DSPay Merchant Portal](https://mcashier.ds.pro/login/).
- Configure at least one `ENABLED` receiving address.
- Keep the merchant server clock synchronized with [NTP](#term-ntp).

<a id="build-cashier-url"></a>
### 4.3 Build the Cashier URL

Cashier base URL:

```text
https://cashier.ds.pro/
```

After signing, URL-encode the parameters as query parameters:

```text
https://cashier.ds.pro/?merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=99.99&timestamp=1717689600000&signature=7de3fafc...
```

> The example `timestamp` and `signature` only demonstrate the format. Generate the real URL on the merchant backend at request time; it remains valid for 5 minutes.

#### Cashier URL Parameters

| Field | Required | Constraint | Description |
|------|------|------|------|
| `merchantNo` | Yes | non-empty | Merchant identifier; configure it only on the merchant backend. |
| `outOrderNo` | Yes | non-blank, ≤64 chars | Merchant external order ID; signed and case-sensitive. Use exactly `outOrderNo` and a unique value for each merchant order. |
| `payAmount` | Yes | greater than 0; at most 2 decimal places | Stablecoin amount excluding the suffix. Use a plain decimal string; scientific notation is forbidden. |
| `timestamp` | Yes | current time ±5 minutes | Unix timestamp in milliseconds; prevents replay of old links. |
| `signature` | Yes | lowercase HMAC-SHA256 hex | Calculate it as described in [§4.4](#signature-canonical-string). |
| `productPrice` | No | optional | Product price, used only for recording and display. |
| `productPriceCurrency` | No | optional | Product price currency such as `USD`, `CNY`, or `EUR`. |
| `productId` | No | ≤64 chars | Merchant product ID; echoed in callbacks. |

Merchants do not pass `networkId` or `contractAddress`. The payer selects the chain/token in the cashier, which creates the order using the merchant's configured receiving addresses.

#### After the URL Opens

- The cashier verifies `timestamp` and `signature`.
- The payer selects the chain and stablecoin.
- The cashier creates the order and displays the receiving address, QR code, expiry time, and **actual payable amount including the suffix**.
- The merchant receives state changes through Chapter 5 webhooks. If a webhook was not received successfully, query the order using [§5.11](#merchant-active-order-query-webhook-fallback).

Merchants do not parse the cashier's internal order-creation response and must not construct signatures in the frontend. Complete implementations are available in [Java §4.8](#java-end-to-end-demo), [Node.js §4.9](#node.js-end-to-end-demo), and the [PHP demo](../Demo/back-end/php/README.en-US.md).

<a id="signature-canonical-string"></a>
### 4.4 Signature Canonical String

Concatenate the cashier signing parameters in the **fixed order** below. Sign the original values before URL encoding, then encode them as cashier URL query parameters. The signing set is exactly 4 fields:

```
merchantNo={merchantNo}&outOrderNo={outOrderNo}&payAmount={payAmount}&timestamp={timestamp}
```

**Example**:
```
merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=99.99&timestamp=1717689600000
```

> ⚠️ **Order Sensitive**
>
> HMAC-SHA256 hashes the **byte sequence**; field-order mismatch → different bytes → different hash → verification failure ([`50613`](#error-50613)). **The merchant backend MUST concatenate fields in the exact order above.**
>
> | Position | Field | Empty handling |
> |---|---|---|
> | 1 | `merchantNo` | non-empty, concatenated as-is |
> | 2 | `outOrderNo` | required and non-blank; concatenate as-is; field name is case-sensitive |
> | 3 | `payAmount` | `BigDecimal.toPlainString()`, no scientific notation |
> | 4 | `timestamp` | millisecond long, concatenated as-is |
>
> Sign the original field values before URL encoding. Do not sign the full URL or the URL-encoded `%xx` representation.

> **Important**:
> - `outOrderNo` is required and must not be blank. Include it in both the signature and cashier URL. Field names are case-sensitive: use `outOrderNo`, not `outOrderNO`.
> - `merchantNo` / `outOrderNo` / `payAmount` / `timestamp` constitute the minimal signing set (anti-spoofing, anti-order-id-substitution, anti-amount-tampering, anti-replay).
> - `productPrice` / `productPriceCurrency` / `productId` are optional display fields and are not signed. If their integrity matters, compare them with local order data after webhook verification.
> - The payer selects the chain/token in the cashier, so merchants do not pass or sign `networkId` / `contractAddress`.

### 4.5 Signature Algorithm

[HMAC-SHA256](#term-hmac-sha256), output is **lowercase hex** (same as the webhook signature).

> **Important**: use the `apiSecret` string **directly as the HMAC key** — `secret.getBytes(UTF_8)` — **do NOT Base64-decode first**. Base64Url is only the storage encoding of the key; [HMAC-SHA256](#term-hmac-sha256) is encoding-agnostic about the key byte sequence.

<a id="timestamp-window"></a>
### 4.6 Timestamp Window

`timestamp` (milliseconds) must be within **±5 minutes** of the [DSPay](#term-dspay) server clock; otherwise the cashier rejects the link with [`50614`](#error-50614) `ORDER_TIMESTAMP_EXPIRED`. We strongly recommend enabling [NTP](#term-ntp) on the merchant server.

### 4.7 Amount String Serialization

The signing field `payAmount` must be sent as a **plain numeric string** (no scientific notation):

| Language | Method |
|------|------|
| Java | Construct `BigDecimal` from a string and output with `toPlainString()`. |
| Node.js | Preserve the original string and validate at most 2 decimal places; do not convert through `number` / `toFixed()`. |
| PHP | Keep `payAmount` as a string, call `trim()`, and use it directly; never convert it to `float`. Use `preg_match()` to validate positive plain-decimal format and at most 2 decimal places; see the [PHP demo](../Demo/back-end/php/README.en-US.md). |

> Otherwise `1e2` and `100` produce different signed bytes and signature verification fails ([`50613`](#error-50613)).

<a id="java-end-to-end-demo"></a>
### 4.8 Java End-to-End Demo

The runnable Java demo is maintained as a single source of truth under [`Demo/back-end/java`](../Demo/back-end/java/README.en-US.md). The SDK intentionally does not duplicate its source code.

**Hosted Cashier flow**:

1. Generate `outOrderNo` and `timestamp` locally.
2. Sign `merchantNo → outOrderNo → payAmount → timestamp` locally.
3. URL-encode the signed fields and optional product fields into the cashier URL.
4. Return an HTTP 302 redirect to the cashier, where the payer selects the chain/token and completes order creation.

> `outOrderNo` must be non-blank and used in both the signature and cashier URL. Use a unique value for each merchant order.

Canonical files:

- [Java demo README](../Demo/back-end/java/README.en-US.md)
- [Runnable source: `DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java)
- [Start script](../Demo/back-end/java/start.sh) / [stop script](../Demo/back-end/java/stop.sh)

<a id="node.js-end-to-end-demo"></a>
### 4.9 Node.js End-to-End Demo

The runnable Node.js demo is maintained as a single source of truth under [`Demo/back-end/nodejs`](../Demo/back-end/nodejs/README.en-US.md). The SDK intentionally does not duplicate its source code.

**Hosted Cashier flow**:

1. Generate `outOrderNo` and `timestamp` locally.
2. Sign `merchantNo → outOrderNo → payAmount → timestamp` locally.
3. Build the cashier URL with `URLSearchParams`.
4. Return an HTTP 302 redirect to the cashier, where the payer selects the chain/token and completes order creation.

> `outOrderNo` must be non-blank and used in both the signature and cashier URL. Use a unique value for each merchant order.

Canonical files:

- [Node.js demo README](../Demo/back-end/nodejs/README.en-US.md)
- [HTTP server and cashier URL builder: `server.js`](../Demo/back-end/nodejs/src/server.js)
- [Signing and callback verification: `signer.js`](../Demo/back-end/nodejs/src/signer.js)
- [Package scripts](../Demo/back-end/nodejs/package.json)

### 4.10 Signature-Failure Triage Table

| Error code | Root cause | Triage direction |
|--------|------|----------|
| [`50613`](#error-50613) `ORDER_SIGNATURE_INVALID` | No [apiSecret](#term-apisecret) configured for the merchant | Enable webhooks in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) (auto-generates [apiSecret](#term-apisecret)) or run `regenerate` from the portal. |
| [`50613`](#error-50613) `ORDER_SIGNATURE_INVALID` | Signature computed incorrectly | Inspect the canonical-string field order, BigDecimal serialization, and [apiSecret](#term-apisecret) accuracy. |
| [`50614`](#error-50614) `ORDER_TIMESTAMP_EXPIRED` | Timestamp outside ±5 minutes | Check server clock sync ([NTP](#term-ntp)). |

### 4.11 ⚠️ Pitfalls (5)

1. **BigDecimal must use `toPlainString()`**: `new BigDecimal("1E+2").toString()` emits `1E+2`, while `toPlainString()` emits `100`. Any mismatch in the canonical string → [`50613`](#error-50613). `payAmount` participates in the signature and must remain a plain decimal string.

2. **Node.js amount fields must be strings**: `payAmount` (in the signature) must be a string literal like `'99.99'` or use `Big.js`. Never use JS `number` — it drifts into scientific notation for values beyond `Number.MAX_SAFE_INTEGER` or for very small decimals, breaking the canonical string.

3. **±5-minute timestamp window**: the server must run [NTP](#term-ntp). Drift beyond the window → [`50614`](#error-50614). Docker containers: verify the guest clock is synced to the host.

4. **Order-suffix precision convention**: the cashier's final `payAmount` includes the suffix (e.g. `100.001`), **not** the `100` in the merchant URL. Stablecoins are treated as 6-decimal tokens: merchants submit at most 2 decimal places and DSPay uses the remaining 4 for the suffix. More than 2 decimal places returns [`50612`](#error-50612). The payer must use the cashier-displayed amount; merchant reconciliation uses webhook or active-query results.

5. **Signing field set + order sensitivity**: `merchantNo` / `outOrderNo` / `payAmount` / `timestamp` participate in the signature, **concatenated in this exact order**. HMAC-SHA256 hashes the byte sequence, so **any field-order mismatch → signature mismatch → [`50613`](#error-50613)**. `outOrderNo` is required and must appear in both the signature and cashier URL. Optional product fields may be added to the URL but are not signed; the payer selects the chain/token in the cashier. Merchants must keep `outOrderNo` unique.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-5-handling-webhooks"></a>
## Chapter 5: Handling Webhooks

This chapter covers the webhook handling pipeline: the **order state machine**, the **four-step signature verification**, replay-attack defense, idempotency design, the strict response contract, retry policy, and the signed active-query fallback for missed webhooks.

<a id="order-state-machine-read-this-first"></a>
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
| `CREATED` | Awaiting payment (10min countdown) | ❌ Not sent | ✅ Scanned | ✅ |
| `TIMEOUT` | 10min elapsed with no payment (still waiting) | ❌ Not sent | ✅ Scanned | ✅ |
| `CLOSED` | 40min elapsed; system closed the order | ✅ `CLOSED` sent | ❌ **Stopped** | ✅ (reopens, `reopened=true`) |
| `COMPLETED` | On-chain settlement / supplement complete | ✅ `COMPLETED` sent | — | — |
| `REFUNDED` | Merchant refund succeeded | ✅ `REFUNDED` sent | — | — |

> Webhooks are emitted only when an order enters `CLOSED`, `COMPLETED`, or `REFUNDED`. DSPay Cashier handles the payer-facing pending state and countdown. If the merchant backend needs active tracking, use the [active-query endpoint](#merchant-active-order-query-webhook-fallback) instead of waiting for `CREATED` / `TIMEOUT` webhooks.

**Three behaviors that are easily missed**:

1. **`TIMEOUT` does not emit a webhook**: the 10-minute transition only advances order state. The order continues waiting for on-chain settlement and auto-detection; its eventual state is either CLOSED (40min) or COMPLETED (on-chain / supplement). Do not rely on a webhook to detect TIMEOUT.

2. **Auto-detection stops once `CLOSED`**: the [DSPay](#term-dspay) auto-detection job only scans orders in `CREATED` / `TIMEOUT`. Once an order transitions to `CLOSED`, subsequent on-chain settlements are **not** auto-confirmed — the merchant must trigger **manual supplement** from the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) (the `reopened=true` path).

3. **Supplement does not require amount match**: auto-detection demands the on-chain amount match `payAmount` exactly (`compareTo == 0`); manual supplement does not check amount, it only records the difference (`amountDiff = actual − payable`) — the merchant decides whether to accept.

> 💡 **Why does auto-detection stop after `CLOSED`?**
> `CLOSED` is the terminal "40-minute give-up" state. The order has already dispatched a `CLOSED` event (the merchant may have already cancelled the order and released inventory). If auto-detection kept running, it would repeatedly trigger `CLOSED → COMPLETED` reopenings and reconciliation chaos would ensue. The design choice is therefore "after CLOSED, only manual supplement" — the merchant decides whether to revive the order.

### 5.2 When Will I Receive a Webhook

When an order transitions to **`CLOSED` / `COMPLETED` / `REFUNDED`**, [DSPay](#term-dspay) sends an HTTP POST to the merchant-configured `notifyUrl`. `CREATED` / `TIMEOUT` do not emit webhooks.

| Event `eventType` | Trigger | Recommended merchant handling |
|---|---|---|
| `CLOSED` | 40 min after creation with no settlement | Cancel the order / release inventory |
| `COMPLETED` | On-chain settlement or supplement success | Mark paid / ship |
| `REFUNDED` | Merchant-initiated refund succeeded | Update refund status |

> 💡 DSPay Cashier handles the payer-facing pending state and 10-minute countdown. Merchant backends that need active tracking should use the [active-query endpoint](#merchant-active-order-query-webhook-fallback), not wait for `CREATED` / `TIMEOUT` webhooks.

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
  "payAmount": "100.001",
  "originPayAmount": "100",
  "amountSuffix": "0.001",
  "actualReceivedAmount": "100.001",
  "actualUsdAmount": "100",
  "refundAmount": null,
  "refundUsdAmount": null,
  "refundTxHash": null,
  "txHash": "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
  "tokenSymbol": "USDT",
  "contractAddress": "0xdac17f958d2ee523a2206206994597c13d831ec7",
  "networkId": "evm--1",
  "chainName": "Ethereum",
  "reopened": false,
  "timestamp": 1717689600000
}
```

**Field reference**:

| Field | Type | Always returned | Description |
|------|------|------|------|
| `orderNo` | string | Yes | Order ID, e.g. `DS2024...` |
| `outOrderNo` | string | Yes | Merchant external order ID (required at order creation and echoed back). |
| `eventType` | string | Yes | Event type: `CLOSED` / `COMPLETED` / `REFUNDED`. |
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
| `contractAddress` | string\|null | Yes | Token contract address. null for native coins (e.g. ETH/BNB/SOL); for tokens (e.g. USDT/USDC) the contract address on that chain. Matches the `contractAddress` parameter used at order creation. |
| `networkId` | string | Yes | Network ID, e.g. `evm--1`. |
| `chainName` | string | Yes | Chain display name. **Identical** to the `chainName` field returned by [`GET /dspay/public/supported-chains`](#query-supported-chains-tokens) (e.g. `Ethereum` / `BNB Chain` / `Polygon` / `Solana`) — merchants can display it directly in their backend. |
| `reopened` | boolean | Yes | Whether this is a merchant-initiated manual-supplement reopen after `CLOSED`. |
| `timestamp` | long | Yes | [DSPay](#term-dspay)-side send timestamp (ms). |

> **Amount-field notes (§5.1.5)**:
> - `originPayAmount`: product price — identical to what the merchant supplied at order creation.
> - `amountSuffix`: the suffix appended to differentiate same-amount concurrent orders.
> - `payAmount` = `originPayAmount` + `amountSuffix`, and equals the actual on-chain transfer amount.
> - **For exact-match amount verification, prefer `originPayAmount`** (matches the product price) or `payAmount` (with suffix; matches the on-chain amount).

<a id="four-step-webhook-verification"></a>
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

<a id="idempotency"></a>
### 5.8 Idempotency

[DSPay](#term-dspay)'s retry policy (see [5.9](#response-contract-strict-mode)) may deliver the same event more than once. Merchants **must** de-duplicate on `orderNo + eventType`.

A single order emits at most three webhook event types. Retries reuse the same `orderNo + eventType` and therefore do not create new idempotency keys. Use that pair as the composite primary key and retain records for at least 30 days.

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

<a id="response-contract-strict-mode"></a>
### 5.9 Response Contract (Strict Mode)

**Success response example**:

```
HTTP 200
Content-Type: application/json

{"code":"SUCCESS","msg":"ok"}
```

**Strict matching rules**:

- HTTP status must be 2xx.
- `code` must be the string `"SUCCESS"` (uppercase)
- ❌ `"success"` (lowercase) — rejected
- ❌ `"Success"` (title-cased) — rejected
- ❌ `"SUCCESS "` (trailing whitespace) — rejected
- ❌ `200` (numeric) — rejected
- ❌ `{"data":{"code":"SUCCESS"}}` (nested) — rejected
- ✅ `{"code":"SUCCESS","extra":"x"}` (extra fields tolerated)
- ✅ `{"code":"SUCCESS","msg":"any message"}` (`msg` content is not checked)

**Failure response**: a non-2xx status, or a body that fails the JSON rules above, triggers a [DSPay](#term-dspay) retry.

**Retry policy** (escalating retry with async compensation):

Each escalation delay starts when the **previous attempt fails**. If every attempt fails, the theoretical elapsed time is about **43h 21m 30s**; HTTP duration and scheduler polling can make the actual time longer.

Example below assumes the first attempt occurs at `D0 00:00:00`, every request returns immediately, and the scheduler adds no delay:

| Attempt | Phase | Delay after previous attempt | Example time | Result |
|---------|-------|------------------------------|--------------|--------|
| 1st | IMMEDIATE | Immediate | `D0 00:00:00` | Fail → continue next attempt |
| 2nd | IMMEDIATE | 0 seconds | About `D0 00:00:00` | Fail → continue next attempt |
| 3rd | IMMEDIATE | 0 seconds | About `D0 00:00:00` | Fail → switch to ESCALATION phase |
| 4th | ESCALATION | 30 seconds | `D0 00:00:30` | Fail → continue next attempt |
| 5th | ESCALATION | 1 minute | `D0 00:01:30` | Fail → continue next attempt |
| 6th | ESCALATION | 5 minutes | `D0 00:06:30` | Fail → continue next attempt |
| 7th | ESCALATION | 15 minutes | `D0 00:21:30` | Fail → continue next attempt |
| 8th | ESCALATION | 1 hour | `D0 01:21:30` | Fail → continue next attempt |
| 9th | ESCALATION | 6 hours | `D0 07:21:30` | Fail → continue next attempt |
| 10th | ESCALATION | 12 hours | `D0 19:21:30` | Fail → continue next attempt |
| 11th | ESCALATION | 24 hours | `D1 19:21:30` | Fail → automatic delivery stops |

> `D0` is the day of the first attempt; `D1` is the following day. Times shown are theoretical earliest times. Network duration and scheduler polling may delay actual delivery.

> 💡 **Design intent**: 3 immediate attempts cover "transient jitter" (service restarts, micro network blips); 8 escalating attempts cover "extended unavailability" (merchant backend down, deployment windows, DNS outages). The cumulative span of about 43 hours covers prolonged merchant-service outages.
>
> 🔁 **Idempotency still required** (see [§5.8](#idempotency)): any single attempt may succeed on the merchant side but fail to ACK on the [DSPay](#term-dspay) side — always de-duplicate on `orderNo + eventType`.
>
> 🚫 **Old-event cancellation**: if the order's state advances mid-retry (for example, a `CLOSED` order is supplemented to `COMPLETED`), DSPay stops delivering the old event to avoid a conflict between its event type and the latest order state. The new-state event is sent separately.

### 5.10 Event Types

| eventType | Trigger | Recommended merchant handling |
|-----------|----------|-------------|
| `CLOSED` | 40 min after creation with no settlement. | Cancel the order / release inventory. |
| `COMPLETED` | On-chain settlement / auto-detection / supplement. | Mark paid / ship. |
| `REFUNDED` | Merchant-initiated refund succeeded. | Update refund status. |
| `COMPLETED` + `reopened=true` | Merchant manually supplements a `CLOSED` order and reopens it. | Distinguish manual-supplement completion from normal completion. |

> `CREATED` / `TIMEOUT` do not emit webhooks.

> `reopened=true` scenario: the order is already `CLOSED`, and the merchant verifies the on-chain settlement and performs a manual supplement in the portal. [DSPay](#term-dspay) reopens the order as `COMPLETED` and emits the webhook; merchants can distinguish manual-supplement completion from normal completion through this field.

<a id="merchant-active-order-query-webhook-fallback"></a>
### 5.11 Merchant-Initiated Order Query (Webhook Fallback)

If a webhook is not received successfully, the merchant can query the latest order state through this endpoint. This is a **fallback for missed webhooks**, not a replacement for webhooks. Poll only when needed, based on local order state and a backoff policy; avoid continuous high-frequency requests.

#### Endpoint

```http
POST /dspay/order/query
Content-Type: application/json
```

Example full URL:

```text
https://wallet.ds.pro/dspay/order/query
```

#### Request Fields

| Field | Type | Required | Constraints and description |
|------|------|----------|-----------------------------|
| `merchantNo` | string | Yes | Merchant ID, up to 32 characters; included in the signature |
| `orderNo` | string | Conditional | DSPay order ID, up to 64 characters; at least one of `orderNo` / `outOrderNo` is required; included in the signature |
| `outOrderNo` | string | Conditional | Merchant external order ID, up to 128 characters; at least one of `orderNo` / `outOrderNo` is required; included in the signature; may match multiple orders |
| `timestamp` | long | Yes | Current Unix timestamp in milliseconds; must be within ±5 minutes of server time |
| `signature` | string | Yes | Lowercase hex HMAC-SHA256 signature of the canonical string, up to 128 characters |

Query rules:

- Only orders owned by the supplied `merchantNo` are returned.
- With one order ID supplied, the endpoint performs an exact match on that field.
- With both supplied, it performs an exact `orderNo AND outOrderNo` match.
- An omitted optional field still keeps its key in the canonical string, with an empty value.
- Results are sorted by `createAt` descending. No match returns an empty array `[]`, not `ORDER_NOT_FOUND`.

#### Query Signature

Fixed field order:

```text
merchantNo → orderNo → outOrderNo → timestamp
```

Canonical string:

```text
merchantNo={merchantNo}&orderNo={orderNo}&outOrderNo={outOrderNo}&timestamp={timestamp}
```

Example querying only by `outOrderNo`:

```text
merchantNo=DSM1&orderNo=&outOrderNo=MY-ORDER-20260715-001&timestamp=1717689600000
```

Signature:

```text
signature = hex_lowercase(HMAC_SHA256(apiSecret UTF-8 bytes, canonical UTF-8 bytes))
```

> If `orderNo` / `outOrderNo` is null, empty, or whitespace-only, normalize its signature value to an empty string. Otherwise call `trim()` first. Use `apiSecret` directly as the UTF-8 HMAC key; do not Base64-decode it. Generate a fresh `timestamp` and `signature` for every poll.

#### Minimal Node.js 18+ Demo

```js
const crypto = require('node:crypto');

const DSPAY_BASE_URL = 'https://wallet.ds.pro';
const API_SECRET = 'your-apiSecret';

async function queryOrders() {
    // At least one of orderNo / outOrderNo must be non-empty.
    // This example queries by the merchant external order ID.
    const params = {
        merchantNo: 'DSM1',
        orderNo: '',
        outOrderNo: 'MY-ORDER-20260715-001',
    };

    const opt = (value) =>
        value == null || String(value).trim() === '' ? '' : String(value).trim();
    const timestamp = Date.now();
    const canonical = [
        `merchantNo=${params.merchantNo}`,
        `orderNo=${opt(params.orderNo)}`,
        `outOrderNo=${opt(params.outOrderNo)}`,
        `timestamp=${timestamp}`,
    ].join('&');
    const signature = crypto
        .createHmac('sha256', API_SECRET)
        .update(canonical, 'utf8')
        .digest('hex');

    const response = await fetch(`${DSPAY_BASE_URL}/dspay/order/query`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            ...params,
            orderNo: opt(params.orderNo),
            outOrderNo: opt(params.outOrderNo),
            timestamp,
            signature,
        }),
    });

    const text = await response.text();
    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${text}`);
    }

    const orders = JSON.parse(text);
    if (!Array.isArray(orders)) {
        throw new Error(`Unexpected response: ${text}`);
    }
    console.log(JSON.stringify(orders, null, 2));
    return orders;
}

queryOrders().catch(console.error);
```

#### Response Example

The endpoint returns an order array directly:

```json
[
  {
    "orderNo": "DS00000120260702000001",
    "outOrderNo": "MY-ORDER-20260715-001",
    "createAt": 1717689600000,
    "status": "COMPLETED",
    "statusDesc": "已完成",
    "payAmount": 100.001,
    "originPayAmount": 100,
    "amountSuffix": 0.001,
    "usdAmount": 100,
    "tokenSymbol": "USDT",
    "networkId": "evm--1",
    "receivingAddress": "0x1111111111111111111111111111111111111111",
    "payerAddress": "0x2222222222222222222222222222222222222222",
    "txHash": "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
    "txLink": "https://wallet.ds.pro/v1/eth/tx/0xabcdef...",
    "productPrice": 100,
    "productPriceCurrency": "USD",
    "actualReceivedAmount": 100.001,
    "amountDiff": 0,
    "actualUsdAmount": 100,
    "paidSource": "CHAIN_DETECTION",
    "paidAt": 1717689660000,
    "completedAt": 1717689720000
  }
]
```

The response uses `NON_NULL` serialization: fields without a value are omitted rather than necessarily returned as `null`.

| Field | Type | Description |
|------|------|-------------|
| `orderNo` | string | DSPay order ID |
| `outOrderNo` | string | Merchant external order ID |
| `createAt` | long | Creation time, Unix milliseconds |
| `status` | string | `CREATED` / `TIMEOUT` / `CLOSED` / `COMPLETED` / `REFUNDED` |
| `statusDesc` | string | Human-readable order-state description currently returned in Chinese |
| `payAmount` | decimal | Payable token amount including the DSPay suffix |
| `originPayAmount` | decimal | Merchant amount excluding the suffix; may be omitted for legacy orders |
| `amountSuffix` | decimal | Order-identification suffix; may be omitted for legacy orders |
| `usdAmount` | decimal | USD amount snapshot locked at order creation |
| `tokenSymbol` | string | Token symbol, e.g. `USDT` |
| `networkId` | string | Network identifier, e.g. `evm--1` |
| `receivingAddress` | string | Receiving address |
| `payerAddress` | string | Payer address; omitted before payment |
| `txHash` | string | On-chain transaction hash; omitted before an on-chain transaction exists |
| `txLink` | string | Blockchain-explorer redirect URL; omitted when unavailable |
| `productPrice` | decimal | Product price; omitted when not supplied |
| `productPriceCurrency` | string | Product-price currency; omitted when not supplied |
| `actualReceivedAmount` | decimal | Actual received token amount; omitted before completion |
| `amountDiff` | decimal | `actualReceivedAmount - payAmount`; omitted before completion |
| `actualUsdAmount` | decimal | Actual received USD value locked at completion; omitted before completion |
| `paidSource` | string | `CHAIN_DETECTION` or `SUPPLEMENT`; omitted before completion |
| `paidAt` | long | Payment time, Unix milliseconds; omitted before payment |
| `completedAt` | long | Completion time, Unix milliseconds; omitted before completion |

Common errors:

| Error code | Cause |
|------------|-------|
| [`40001`](#error-40001) | Both `orderNo` and `outOrderNo` are empty, or a field is invalid |
| [`50501`](#error-50501) | `merchantNo` does not exist |
| [`50503`](#error-50503) | `apiSecret` is frozen |
| [`50613`](#error-50613) | Signature missing, canonical field order incorrect, or signature mismatch |
| [`50614`](#error-50614) | `timestamp` is outside the ±5-minute window |

Polling guidance:

- Use webhooks as the normal path. Start querying only when no webhook arrives within the expected business window.
- Apply backoff; do not poll continuously at a fixed high frequency.
- Generate a fresh timestamp and signature for every query.
- Stop polling after reaching the target state. An empty array means no matching order currently exists and may be retried according to business policy.
- Keep state updates idempotent regardless of whether they come from a webhook or an active query.

### 5.12 ⚠️ Pitfalls (6)

1. **Verify the raw body**: use the original HTTP body string — never deserialize and re-`JSON.stringify()` (field-order changes break the signature). In Java use `@RequestBody String rawBody`; in Node.js use the `raw-body` package to capture the raw byte stream. This is the **single most common** cause of webhook verification failures.

2. **HMAC secret is used directly — do NOT Base64-decode**: [apiSecret](#term-apisecret) is a 43-char Base64Url string, but when used as an HMAC key, call `secret.getBytes(UTF_8)` directly. Base64Url is only the storage encoding — [HMAC-SHA256](#term-hmac-sha256) is encoding-agnostic about the key byte sequence.

3. **`SUCCESS` is case-sensitive**: must be exactly `{"code":"SUCCESS"}` uppercase. `"success"` / `"Success"` / `"SUCCESS "` (trailing whitespace) / numeric `200` / nested structures all get rejected and trigger retries. The `code` field is compared with `equals` — no `trim`, no `equalsIgnoreCase`.

4. **±5-minute timestamp window for replay defense**: the callback payload's `timestamp` must be within 5 minutes. This is the core replay defense — an attacker replaying an old webhook will fail this check. The merchant server must run [NTP](#term-ntp).

5. **Only three event types emit webhooks**: `CLOSED` / `COMPLETED` / `REFUNDED`. `CREATED` / `TIMEOUT` only advance order state. DSPay Cashier handles payer-facing state/countdown; merchant backends can track state through the [active-query endpoint](#merchant-active-order-query-webhook-fallback).

6. **Idempotency key is `orderNo + eventType`** — not just `orderNo`. The same order may sequentially receive `COMPLETED → REFUNDED`; they are semantically distinct and must not overwrite each other. Use `(orderNo, eventType)` as the unique key; only ACK duplicate `(orderNo, eventType)` pairs immediately.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-6-reconciliation-operations"></a>
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

In the merchant webhook receiver, record and monitor:

- Receipt time, `orderNo`, `eventType`, and handling result.
- Signature-verification failures.
- Business-handler failures and latency.
- Duplicate events with the same `orderNo + eventType`.
- Timestamp of the most recent successfully handled webhook.

Alert when verification or handler failures keep rising, or when no webhook succeeds for an unexpectedly long period. Never log the complete `apiSecret`; if signatures are logged in production, restrict access and retention.

<a id="scheduled-key-rotation"></a>
### 6.4 Scheduled Key Rotation

Rotate [apiSecret](#term-apisecret) regularly (e.g. quarterly) from the [DSPay Merchant Portal](https://mcashier.ds.pro/login/).

**Rotation steps**:

1. During a **low-traffic window**, run `regenerate` from the portal.
2. **Immediately** update the [apiSecret](#term-apisecret) configuration on the merchant backend.
3. Monitor webhook verification success rate for 5 minutes.

> In-flight webhooks at the moment of `regenerate` were signed with the old key — the merchant may see brief verification failures. [DSPay](#term-dspay) will auto-retry with the new key on an escalating schedule (30s / 1min / 5min / ... up to 11 attempts). The merchant only needs to tolerate a verification-failure window of a few minutes.

### 6.5 ⚠️ Pitfalls (5)

1. **Use `originPayAmount` for reconciliation**: `payAmount` includes the suffix (e.g. `100.001`), while `originPayAmount` is the product price (`100`). Compare against `originPayAmount` for reconciliation — otherwise the suffix difference raises spurious "amount mismatch" alerts. `payAmount` is for on-chain transaction audit only.

2. **In-flight webhooks fail verification after `regenerate`**: at the instant of `regenerate`, already-dispatched webhooks are signed with the old key — the merchant's verification with the new key will fail. [DSPay](#term-dspay) auto-retries on an escalating schedule (30s / 1min / 5min / ... up to 11 attempts) with the new key. Tolerate the brief failure window — **do not roll back to the old key**.

3. **Automatic delivery stops after all 11 attempts fail**: 3 immediate attempts plus 8 escalating attempts. The theoretical cumulative span is about 43h 21m 30s; request duration and scheduler polling may make it longer. Detect missed state through order queries or reconciliation, and provide a manual recovery path.

4. **An old event may stop retrying**: when an order's state advances mid-retry (for example, `CLOSED` is supplemented to `COMPLETED`), DSPay cancels delivery of the old event and sends the new-state event separately. Handle received events idempotently by `orderNo + eventType`.

5. **Statistics use `COALESCE(actual_usd_amount, usd_amount)`**: prefers the actual received USD value locked at completion, falls back to the creation-time `usd_amount` snapshot. The cumulative number reflects "locked" USD value, not real-time. Merchants needing real-time USD valuations must recompute as `on-chain amount × current rate` themselves.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-7-exception-handling-sops"></a>
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
- The DSPay checkout frontend polls the order status automatically.
- **Do not** rely on a webhook to detect `TIMEOUT`.

### 7.2 On-Chain Settlement After `CLOSED` (Supplement Flow)

**Symptom**: order transitioned to `CLOSED`, then the user completed the on-chain transfer — the settlement is real.

**[DSPay](#term-dspay) behavior**:
- ❌ **Auto-detection is stopped**: the [DSPay](#term-dspay) auto-detection job only scans `CREATED` / `TIMEOUT`. After `CLOSED`, no auto-confirmation.
- ✅ The merchant must trigger **manual supplement**.

**Supplement flow** (in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/)):

1. Inspect the actual on-chain settled amount on the order-detail page.
2. Confirm the settlement and trigger supplement (order reopens as `COMPLETED`).
3. Merchant receives a `COMPLETED` + `reopened=true` webhook.

> **Supplement does not enforce amount match**: [DSPay](#term-dspay) records `actualReceivedAmount` + `amountDiff` only — the merchant decides whether to accept. Before supplementing, inspect the actual on-chain amount in the portal; manually review large discrepancies.

### 7.3 Refund Flow

Refunds are initiated from the **[DSPay Merchant Portal](https://mcashier.ds.pro/login/)**.

**Constraints**:
- Only `COMPLETED` orders can be refunded.
- `REFUNDED` is terminal and irreversible.
- A successful refund triggers a `REFUNDED` webhook.

### 7.4 Key-Compromise Incident Decision Tree

When `apiSecret` is suspected of compromise, choose based on the scenario:

| Scenario | Action | Effect |
|------|------|------|
| After-hours, no engineer on call | Portal → Security Settings → **Freeze key** | Old key is invalidated immediately (webhook delivery suspended + order creation fails with [`50503`](#error-50503)). Emergency stop-bleed. **No new key is generated.** |
| Compromise confirmed | Portal → Security Settings → **Regenerate** | Old key invalidated, new key generated (the merchant backend must be updated in lockstep). |
| After triage, confirmed not leaked | Portal → Security Settings → **Restore key** | Old key becomes valid again. |

> ⚠️ **Critical rules**:
> - `regenerate` **does not unfreeze**: if the key is in frozen state, regenerate produces a new key but the frozen state persists — the merchant must explicitly restore it from the portal before it can be used.
> - If the key is **confirmed leaked**, strongly prefer `regenerate` to roll a fresh key — **do not simply restore the old one** (the attacker still has it).

### 7.5 ⚠️ Pitfalls (4)

1. **Auto-detection stops after `CLOSED`**: the [DSPay](#term-dspay) auto-detection job only scans `CREATED` / `TIMEOUT`. After `CLOSED`, even if the chain settles, no auto-confirmation — manual supplement from the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) is required (`reopened=true`). Merchants need a "manual re-process for `CLOSED` orders" runbook, or monitor closed orders for late settlements.

2. **Supplement does not enforce amount match**: manual supplement records `actualReceivedAmount` + `amountDiff`; the merchant decides whether to accept. Inspect the actual on-chain amount in the portal before supplementing; manually review large discrepancies. [DSPay](#term-dspay) will not refuse supplement over amount mismatch.

3. **Key compromise: freeze vs regenerate**: after-hours / no engineer → freeze from the portal (stop-bleed); once confirmed → `regenerate` for a fresh key. Do not simply restore the old key — the attacker still has it.

4. **`regenerate` does not unfreeze**: regenerating while frozen produces a new key but keeps the frozen state — the merchant must explicitly restore it from the portal. The two operations must be executed separately; do not expect `regenerate` to auto-unfreeze.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-8-testing--integration"></a>
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

> The test payment must exactly match the `payAmount` displayed by the cashier, including the suffix; otherwise chain detection will not match.

### 8.5 Common Signature-Verification Failure Triage

| Cause | Triage |
|------|---------|
| Payload was deserialized then re-serialized (field order changed) | Verify against the raw body string. |
| Secret was Base64-decoded | Use the secret string directly; do not Base64-decode. |
| Large clock skew (rejected by replay defense) | Check whether `timestamp` is within 5 minutes. |
| Key was regenerated (using old key) | View the latest key in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/). |

### 8.6 ⚠️ Pitfalls (3)

1. **Test sequence**: do not jump straight to end-to-end testing. Verify the signing algorithm in isolation first (compare against an online HMAC tool). Skipping this step makes "signature failed" impossible to attribute — is it the algorithm or the transport layer?

2. **ngrok URL churn**: the free-tier URL changes on every restart; you must re-configure it in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/). If ngrok restarts mid-test, update [DSPay](#term-dspay)'s `notifyUrl` in lockstep — otherwise webhooks go to the stale URL and fail.

3. **Local HTTP client uses `HTTP_1_1`**: when calling `http://localhost:<port>` (plain HTTP, not HTTPS), Java's HttpClient must be `HTTP_1_1`, not `HTTP_2` (plain-text h2c is unsupported by most servers → Connection reset). Reserve `HTTP_2` for HTTPS.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-9-faq"></a>
## Chapter 9: FAQ

This chapter organizes common questions into five categories — authentication, signing, orders, webhooks, configuration — for quick reference.

### 9.1 Authentication

**Q: What happens when the portal session expires?**
A: Portal sessions use a sliding 7-day window — each interaction auto-extends by 7 days. After 7 days of inactivity, the session expires and you must sign in again from the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) with your wallet. Merchant backend integrations (order creation, webhook verification) use [apiSecret](#term-apisecret) signing and do not depend on the portal session — they are unaffected.

**Q: Can a read-only (watch-only) wallet sign in?**
A: No. [SIWE](#term-siwe) requires a private-key signature; watch-only wallets cannot `personal_sign`. You must sign in with a wallet that holds the private key.

### 9.2 Signing

**Q: Signature verification keeps failing ([`50613`](#error-50613)) — why?**
A: A common cause is BigDecimal scientific notation. `new BigDecimal("1E+2").toString()` emits `1E+2`; use `toPlainString()` to get `100`. Confirm that `payAmount` is a plain decimal string with at most 2 decimal places and inspect the canonical string for `E` characters.

**Q: How do I handle amount precision in Node.js?**
A: `productPrice` and `payAmount` must be string literals like `'99.99'` or use Big.js — never JS `number`. JS `number` drifts into scientific notation past `Number.MAX_SAFE_INTEGER` or for very small decimals.

**Q: Does [apiSecret](#term-apisecret) need to be Base64-decoded before use?**
A: **No.** The [apiSecret](#term-apisecret) string is used directly as the HMAC key. Base64Url is only the storage encoding — [HMAC-SHA256](#term-hmac-sha256) is encoding-agnostic about the key byte sequence. Pass `secret.getBytes(UTF_8)` directly to `SecretKeySpec`.

**Q: What happens if the signature field order is wrong?**
A: The signature will not match → [`50613`](#error-50613). Concatenate `merchantNo → outOrderNo → payAmount → timestamp` in that exact order, joined with `&`. `outOrderNo` is required and must appear in both the signature and cashier URL. Optional product fields are not signed; the payer selects the chain/token in the cashier.

### 9.3 Orders

**Q: Why does the cashier display a different `payAmount` from the value in my URL?**
A: The suffix mechanism. [DSPay](#term-dspay) appends a unique suffix (e.g. `100.001`) to differentiate same-amount concurrent orders. The payer must send the cashier-displayed amount; merchant reconciliation uses webhook or active-query results.

**Q: The cashier shows [`50609`](#error-50609) `NO_ENABLED_ADDRESS`?**
A: The chain is supported, but the merchant has no `ENABLED` receiving address for that [networkId](#term-networkid) (chain). Configure a receiving address in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/).

**Q: The cashier shows [`50707`](#error-50707) `CHAIN_NOT_SUPPORTED`?**
A: The selected chain is not currently supported. Ask the payer to return to the cashier and choose an available chain; contact DSPay support if the error persists.

**Q: Cashier order creation shows [`50610`](#error-50610)/[`50611`](#error-50611)/[`50612`](#error-50612)?**
A: Suffix-mechanism concurrency or precision issue. [`50610`](#error-50610) `ORDER_CREATE_BUSY` (suffix-lock contention, retry); [`50611`](#error-50611) `SUFFIX_EXHAUSTED` (suffix slots exhausted, wait for concurrency to drain); [`50612`](#error-50612) `SUFFIX_PRECISION_SATURATED` (merchant `payAmount` has more than 2 decimal places).

### 9.4 Webhooks

**Q: Webhook signature verification keeps failing?**
A: 90% of the time the payload was deserialized then re-serialized, changing field order. Verify against the raw HTTP body string — never against `JSON.stringify(parsed_object)`.

**Q: Webhook keeps retrying — what do I do?**
A: Check that your response matches the strict `{"code":"SUCCESS"}` format. `code` must be the uppercase string `"SUCCESS"` — numeric `200`, lowercase, and nested structures are all rejected.

**Q: What's the retry policy?**
A: 11 total attempts per event: **3 immediate consecutive attempts**, then **8 escalating attempts**, each delayed from the previous failed attempt by 30s / 1min / 5min / 15min / 1h / 6h / 12h / 24h. The theoretical cumulative span is about 43h 21m 30s; actual time may be longer. Automatic delivery stops after all 11 attempts fail; use order queries or reconciliation for recovery.

**Q: What happens if order state advances while an old event is retrying?**
A: DSPay stops delivering the old event and sends the new-state event separately. For example, if a `CLOSED` order is supplemented to `COMPLETED`, the old `CLOSED` delivery stops and `COMPLETED` is sent. Handle received events idempotently by `orderNo + eventType`.

**Q: What does `reopened=true` mean?**
A: After an order is `CLOSED`, the merchant verifies the on-chain settlement and performs a manual supplement in the portal. [DSPay](#term-dspay) reopens the order as `COMPLETED` and emits a webhook with `reopened=true`, distinguishing manual-supplement completion from normal completion.

**Q: How do I do idempotency?**
A: De-duplicate on `orderNo + eventType` (not just `orderNo`). The same order may sequentially receive `COMPLETED → REFUNDED`; they are semantically distinct and must not overwrite each other. Use a DB unique key on `(order_no, event_type)`, or Redis SETNX on `notify:{orderNo}:{eventType}`.

**Q: Does `TIMEOUT` fire a webhook?**
A: **No.** `CREATED` / `TIMEOUT` advance order state but do not emit webhooks. DSPay Cashier handles payer-facing state/countdown; merchant backends should use the order-query API when tracking is required.

### 9.5 Configuration

**Q: What do I do if the key is leaked?**
A: After-hours emergency → freeze the key in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) (stop-bleed); once confirmed → `regenerate` for a fresh key. Do not simply restore the old key.

**Q: What's the difference between the platform whitelist and the merchant's ENABLED addresses?**
A: The platform whitelist (returned by `GET /dspay/public/supported-chains`) defines "which chains [DSPay](#term-dspay) supports"; the merchant-level receiving addresses (configured in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/)) define "which address this merchant uses on each chain." Both conditions must hold at order creation.

**Q: How do I handle [`50503`](#error-50503) `API_SECRET_DISABLED`?**
A: The key has been frozen. If you froze it yourself, restore it from the portal after triage; if someone else did, contact the merchant administrator.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="appendix-a-java-reference-integration"></a>
## Appendix A: Java Reference Integration

Java integration has one canonical implementation in [`Demo/back-end/java`](../Demo/back-end/java/README.en-US.md). It covers both directions: building a signed cashier redirect and verifying webhook signatures.

| File | Purpose |
|------|---------|
| [`README.en-US.md`](../Demo/back-end/java/README.en-US.md) | Requirements, configuration, run commands, endpoints, and signature rules |
| [`src/DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java) | Local signing, URL encoding, cashier redirect, raw-body webhook verification, and strict ACK response |
| [`start.sh`](../Demo/back-end/java/start.sh) / [`stop.sh`](../Demo/back-end/java/stop.sh) | Background process lifecycle |

The create flow is: **merchant backend signs locally → builds cashier URL → redirects user**. It does not call the DSPay create-order API directly; the cashier completes order creation after the user selects a chain and token.

> Source code is intentionally not copied into this appendix. Maintaining the canonical demo automatically keeps this reference current.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="appendix-b-nodejs-reference-integration"></a>
## Appendix B: Node.js Reference Integration

Node.js integration has one canonical implementation in [`Demo/back-end/nodejs`](../Demo/back-end/nodejs/README.en-US.md). It uses only built-in Node.js modules and covers cashier redirects plus webhook verification.

| File | Purpose |
|------|---------|
| [`README.en-US.md`](../Demo/back-end/nodejs/README.en-US.md) | Requirements, configuration, run commands, endpoints, and signature rules |
| [`src/server.js`](../Demo/back-end/nodejs/src/server.js) | HTTP routes, signed cashier URL construction, 302 redirect, and raw-body callback handling |
| [`src/signer.js`](../Demo/back-end/nodejs/src/signer.js) | Order signing and constant-time callback signature verification |
| [`package.json`](../Demo/back-end/nodejs/package.json) | Start command and runtime metadata |

The create flow is: **merchant backend signs locally → builds cashier URL → redirects user**. It does not call the DSPay create-order API directly; the cashier completes order creation after the user selects a chain and token.

> Source code is intentionally not copied into this appendix. Maintaining the canonical demo automatically keeps this reference current.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="appendix-c-error-code-reference"></a>
## Appendix C: Error Code Reference

Source: `DspayExceptionConstant.java`, grouped by error-code range.

#### General Errors (400xx)

| code | msg | Description |
|------|------|------|
| <a id="error-40001"></a>40001 | PARAM_ERROR | Parameter validation failed. |
| <a id="error-40101"></a>40101 | UNAUTHORIZED | Not authenticated. |
| <a id="error-40301"></a>40301 | FORBIDDEN | Insufficient permissions. |
| <a id="error-40401"></a>40401 | NOT_FOUND | Resource not found. |
| <a id="error-40901"></a>40901 | STATE_CONFLICT | State conflict. |
| <a id="error-50000"></a>50000 | INTERNAL_ERROR | Internal service error. |

#### Merchant (505xx)

| code | msg | Description |
|------|------|------|
| <a id="error-50501"></a>50501 | MERCHANT_NOT_FOUND | Merchant does not exist. |
| <a id="error-50502"></a>50502 | MERCHANT_DISABLED | Merchant has been disabled. |
| <a id="error-50503"></a>50503 | API_SECRET_DISABLED | Merchant API secret has been frozen (webhooks stop; signed requests such as order creation and active query fail). |

#### Order (506xx)

| code | msg | Description |
|------|------|------|
| <a id="error-50601"></a>50601 | ORDER_NOT_FOUND | Order does not exist. |
| <a id="error-50603"></a>50603 | ORDER_ALREADY_PAID | Order has already been paid. |
| <a id="error-50604"></a>50604 | ORDER_EXPIRED | Order has expired. |
| <a id="error-50605"></a>50605 | ORDER_STATUS_NOT_ALLOWED | Order status does not permit this operation. |
| <a id="error-50606"></a>50606 | TX_HASH_INVALID | Transaction hash is invalid. |
| <a id="error-50608"></a>50608 | TX_HASH_ALREADY_USED | Transaction hash has already been used (supplement only; refund no longer validates refundTxHash). |
| <a id="error-50609"></a>50609 | NO_ENABLED_ADDRESS | No `ENABLED` receiving address (merchant has not configured one for this [networkId](#term-networkid) / chain). |
| <a id="error-50610"></a>50610 | ORDER_CREATE_BUSY | Order creation busy (suffix-lock contention; retry). |
| <a id="error-50611"></a>50611 | SUFFIX_EXHAUSTED | Suffix slots exhausted. |
| <a id="error-50612"></a>50612 | SUFFIX_PRECISION_SATURATED | Insufficient suffix precision; for stablecoins this normally means merchant `payAmount` has more than 2 decimal places. |
| <a id="error-50613"></a>50613 | ORDER_SIGNATURE_INVALID | Signature verification failed for order creation or active query. |
| <a id="error-50614"></a>50614 | ORDER_TIMESTAMP_EXPIRED | Order-creation or active-query timestamp outside the ±5-minute window. |

#### Address (507xx)

| code | msg | Description |
|------|------|------|
| <a id="error-50702"></a>50702 | ADDRESS_FORMAT_INVALID | Address format is invalid. |
| <a id="error-50703"></a>50703 | ADDRESS_NOT_FOUND | Address does not exist. |
| <a id="error-50704"></a>50704 | ADDRESS_NOT_IN_WALLET | Address does not belong to the current wallet. |
| <a id="error-50705"></a>50705 | ADDRESS_NETWORK_MISMATCH | Address does not match the network. |
| <a id="error-50706"></a>50706 | CHAIN_ADDRESS_ALREADY_BOUND | Address on this chain is already bound. |
| <a id="error-50707"></a>50707 | CHAIN_NOT_SUPPORTED | Chain not supported ([networkId](#term-networkid) is not in the 9-chain whitelist or the chain is disabled). |

#### JWT Authentication (508xx)

| code | msg | Description |
|------|------|------|
| <a id="error-50801"></a>50801 | JWT_TOKEN_MISSING | Missing [JWT](#term-jwt) token. |
| <a id="error-50802"></a>50802 | JWT_TOKEN_INVALID | [JWT](#term-jwt) token is invalid. |
| <a id="error-50803"></a>50803 | JWT_TOKEN_EXPIRED | [JWT](#term-jwt) token has expired (includes session expiry). |

#### SIWE Authentication (509xx)

| code | msg | Description |
|------|------|------|
| <a id="error-50901"></a>50901 | SIWE_NONCE_NOT_FOUND | [SIWE](#term-siwe) [nonce](#term-nonce) does not exist. |
| <a id="error-50902"></a>50902 | SIWE_NONCE_EXPIRED | [SIWE](#term-siwe) [nonce](#term-nonce) has expired (TTL 5 minutes). |
| <a id="error-50903"></a>50903 | SIWE_SIGNATURE_INVALID | [SIWE](#term-siwe) signature is invalid (ecrecover-recovered address does not match). |
| <a id="error-50904"></a>50904 | SIWE_DOMAIN_MISMATCH | [SIWE](#term-siwe) domain mismatch. |
| <a id="error-50905"></a>50905 | SIWE_MESSAGE_INVALID | [SIWE](#term-siwe) message is invalid. |

[↑ Back to Table of Contents](#table-of-contents)
