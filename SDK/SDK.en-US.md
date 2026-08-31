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
| <a id="term-apisecret"></a>**apiSecret** | Merchant API secret used to sign create/query requests and verify incoming webhooks. Obtain it from the Merchant Portal and keep it on the merchant server only. |
| <a id="term-merchantno"></a>**merchantNo** | Merchant business identifier (`DSM` prefix, e.g. `DSM1`), required by public create/query APIs. |
| **orderNo** | Unique DSPay order identifier returned after creation; used for Checkout addressing, queries, webhooks and reconciliation. |
| **checkoutUrl** | Complete Checkout address returned by create: `{payPageBaseUrl}/checkout/{orderNo}`. Redirect to it as returned. |
| <a id="term-networkid"></a>**networkId** | Canonical chain identifier (e.g. `evm--1` = Ethereum mainnet). Full list in [§3.2](#networkid-cheat-sheet). |
| <a id="term-contractaddress"></a>**contractAddress** | Token contract or mint address. Combined with `networkId` in `allowedPaymentMethods` to restrict Checkout choices. |
| <a id="term-hmac-sha256"></a>**HMAC-SHA256** | Hash-based Message Authentication Code. DSPay uses [apiSecret](#term-apisecret) as the key over a canonical string and outputs lowercase hex. |
| <a id="term-evm"></a>**EVM** | Ethereum Virtual Machine. "EVM-compatible chains" are chains that support Ethereum smart contracts (Ethereum / BSC / Polygon / Arbitrum / Base). |
| <a id="term-usdt"></a>**USDT** / <a id="term-usdc"></a>**USDC** | USD-pegged stablecoins (Tether USD / Centre USD Coin). |
| **Fractional Suffix (<a id="term-amountsuffix"></a>amountSuffix)** | A tiny decimal appended by DSPay to differentiate concurrent orders of the same amount (e.g. the `0.001` in `100.001`). Stablecoins are treated as 6-decimal tokens: merchants use at most the first 2 decimal places and DSPay uses the remaining 4 for the suffix. See [§4.1](#order-suffix-mechanism-in-depth). |
| **Manual Fulfillment (<a id="term-supplement"></a>supplement)** | The operator-confirmed action that reopens a `CLOSED` order as `COMPLETED` after an on-chain payment lands post-timeout. |
| **Webhook (<a id="term-webhook"></a>webhook)** | The HTTP POST notification DSPay sends to the merchant's [notifyUrl](#term-notifyurl). Event types: `CLOSED` / `COMPLETED` / `REFUNDED`. `CREATED` / `TIMEOUT` advance order state but do not emit webhooks. |
| <a id="term-notifyurl"></a>**notifyUrl** | Publicly reachable merchant endpoint that receives DSPay webhooks. Both `http://` and `https://` are accepted; use HTTPS in production. |
| <a id="term-ntp"></a>**NTP** | Network Time Protocol. Used for server clock synchronization. |

> Every occurrence of these terms elsewhere in the document links back to this section.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="quick-start"></a>
## Quick Start (Your First Order in 5 Minutes)

> **Goal:** create an order server-to-server, then redirect the customer to the returned `checkoutUrl`.

**Prerequisites**:

| Requirement | Where to get it |
|---|---|
| DSPay merchant account | Sign in to the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) |
| `merchantNo` and `apiSecret` | Merchant Portal security settings |
| At least one enabled receiving address | Merchant Portal receiving-address settings |
| `notifyUrl` | Optional for the first request; required before production |

### Step 1 — Build and sign the request on your server

The following example uses the unified Node.js `18.20.8` baseline and only required fields. Optional signed fields remain in the canonical string as empty `key=` entries.

> Before running it, replace `baseUrl`, `merchantNo`, and `apiSecret` below with real values. Obtain `merchantNo` and `apiSecret` from the DSPay Merchant Portal; the placeholders cannot be used in an actual request.

```javascript
import crypto from 'node:crypto';

const baseUrl = 'https://wallet.ds.pro'; // Replace with the actual DSPay API base URL.
const merchantNo = 'replace-with-real-merchantNo'; // Required: Merchant Portal merchantNo.
const apiSecret = 'replace-with-real-apiSecret';   // Required: Merchant Portal apiSecret.
const outOrderNo = `ORDER-${Date.now()}`;
const payAmount = '0.01';
const timestamp = Date.now();
const canonical = [
  `merchantNo=${merchantNo}`,
  `outOrderNo=${outOrderNo}`,
  'productPrice=',
  'productPriceCurrency=',
  'productId=',
  'attach=',
  `payAmount=${payAmount}`,
  'allowedPaymentMethods=',
  'returnUrl=',
  'successRedirectUrl=',
  `timestamp=${timestamp}`,
].join('&');
const signature = crypto.createHmac('sha256', apiSecret)
  .update(canonical, 'utf8').digest('hex');

const response = await fetch(`${baseUrl}/dspay/public/order/create`, {
  method: 'POST',
  headers: {'content-type': 'application/json'},
  body: JSON.stringify({merchantNo, outOrderNo, payAmount, timestamp, signature}),
});
const result = await response.json();
if (result.code !== 0) throw new Error(result.message || result.header?.message || 'DSPay error');
console.log(result.data.orderNo, result.data.checkoutUrl);
```

### Step 2 — Redirect to Checkout

Redirect the customer to `data.checkoutUrl`. DSPay Checkout handles network/token selection, payment-method locking, QR rendering and payment-state display.

- The signature window is 5 minutes and protects only the create request from replay.
- The independent 10-minute payment deadline starts when the order is created.
- If the customer never confirms a payment method, the order still reaches `TIMEOUT` after 10 minutes.

### Next Steps

- Read [Chapter 4](#chapter-4-creating-your-first-order) for every request/response field, signing and idempotency.
- Implement [Chapter 5](#chapter-5-handling-webhooks) before production.
- Run the maintained [Java, Node.js or PHP demos](../Demo/README.md).

---


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
| 1 | **Sign up** | [SIWE](#term-siwe) wallet login | Merchant account |
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
- **JDK 11+, Node.js 18.20.8, or PHP 5.6+**: to run the corresponding demos. Other languages can follow the specification.
- **Test stablecoins**: keep at least 0.02 [USDT](#term-usdt) on each test chain to cover a 0.01 USDT order plus its matching suffix; fund the wallet's gas token separately.

#### Demo Runtime Versions

The following versions were used to execute the corresponding tests. When copying a sample, prefer a tested version below. “Minimum” is the lowest language/API baseline used by the source.

| Demo | Minimum | Tested versions | External dependencies |
|------|---------|-----------------|-----------------------|
| Inline Node.js samples and Node.js Demo | Node.js `18.20.8` | Node.js `18.20.8` + npm `10.8.2` | No npm dependencies; built-in Node.js modules only |
| Java Demo and Java verification samples | JDK 11 | Microsoft OpenJDK `11.0.27` and Eclipse Temurin `21.0.11` | No Maven/Gradle dependencies; JDK standard library only |
| PHP Demo | PHP 5.6 | PHP `5.6.40` CLI | No Composer dependencies; PHP standard extensions only |
| Frontend Demo | A modern browser supporting `URL`, `sessionStorage`, and `crypto.randomUUID()` | Google Chrome `151.0.7922.175` | One HTML file; no framework or build tool |

> Only one Node.js installation is needed. The unified debugging baseline is Node.js `18.20.8` + npm `10.8.2`; the Node.js Demo includes `.nvmrc`, so run `nvm use` in that directory. Validation environments include macOS `15.1` and Docker Linux. The Java Demo uses JDK 11 single-file source launch; the PHP minimum is PHP 5.6 because callback verification uses `hash_equals()`.

### 1.5 Core Integration Cheat Sheet

**Merchant integration entry points**:

| Action | Public endpoint | Purpose |
|---|---|---|
| List payment methods | `GET /dspay/public/supported-chains` | Retrieve platform-supported networks and tokens; no signature required. |
| Create order | `POST /dspay/public/order/create` | Server-side signed pre-order; returns `orderNo` and full `checkoutUrl`. |
| Query order | `POST /dspay/public/order/query` | Retrieve authoritative state/details with HMAC signing. |
| Receive webhook | Merchant `notifyUrl` | Receive `CLOSED`, `COMPLETED`, and `REFUNDED` notifications. |

> All other operations — sign-up & login, receiving-address management, webhook URL configuration, order reports, refunds, manual fulfillment, key rotation — are performed in the **[DSPay Merchant Portal](https://mcashier.ds.pro/login/) UI** and require no API calls.
> Loading Checkout details, confirming a payment method, rendering the QR code and polling the user-facing state are internal DSPay interactions. Merchants do not integrate with those endpoints.

### 1.6 Common Public-API Response

```json
{"code":0,"data":{},"header":{"resultCode":0}}
```

- Evaluate only the top-level `code`: `0` means business success; any non-zero value means failure.
- `header.resultCode` duplicates `code` for envelope compatibility and does not need a second check.
- Business data is in `data`. On failure, read top-level `message`, falling back to `header.message` when absent.
- HTTP 200 means the request reached the service; it does not prove business success.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-2-onboarding-authentication"></a>
## Chapter 2: Onboarding & Authentication

This chapter describes the merchant sign-up and authentication model, including how the portal login works, session lifetime, and how to obtain `apiSecret`.

<a id="merchant-sign-up-sign-in"></a>
### 2.1 Merchant Sign-Up & Sign-In

Merchants sign in to the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) with an [EVM](#term-evm) wallet via [SIWE](#term-siwe) (signature-based authentication). **The first sign-in automatically provisions a merchant account** — no separate registration step is required.

After signing in, you can obtain:
- **[merchantNo](#term-merchantno)**: your merchant business identifier (`DSM` prefix, e.g. `DSM1`). Required by public create/query requests.
- **[apiSecret](#term-apisecret)**: your API secret, used to sign public create/query requests and verify incoming webhooks. Store it securely on the merchant server.

<a id="session-lifetime"></a>
### 2.2 Session Lifetime

Portal sessions are valid for 7 days using a sliding window: each interaction auto-extends the session by another 7 days. After 7 days of inactivity, you must sign in again.

> Merchant server create/query calls and webhook verification use [apiSecret](#term-apisecret); they do not depend on the Merchant Portal login session.

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

```http
GET /dspay/public/supported-chains
```

No parameters or signature.

```json
{
  "code": 0,
  "data": [{
    "networkId": "evm--56",
    "chainName": "BNB Smart Chain",
    "chainLogoUrl": "https://static.ds.pro/chains/bsc.png",
    "tokens": [{
      "symbol": "USDT",
      "address": "0x55d398326f99059ff775485246999027b3197955",
      "logoUrl": "https://static.ds.pro/tokens/usdt.png"
    }]
  }],
  "header": {"resultCode": 0}
}
```

`networkId + tokens[].address` may be used in `allowedPaymentMethods`. This is the platform list only. Checkout intersects it with the merchant's enabled receiving-address list and, when provided, the order restriction.
| Field | Type | Nullable | Description |
|---|---|---|---|
| `networkId` | string | No | Platform network identifier; use as `allowedPaymentMethods[].networkId`. |
| `chainName` | string | No | Network display name. |
| `chainLogoUrl` | string | Yes | Complete network-logo URL; the field is still returned with `null` when not configured. |
| `tokens[].symbol` | string | No | Token symbol. |
| `tokens[].address` | string | No | Contract or mint address; use as `allowedPaymentMethods[].contractAddress`. |
| `tokens[].logoUrl` | string | Yes | Complete token-logo URL; the field is still returned with `null` when not configured. |


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

Merchants only need to configure `ENABLED` receiving addresses for chains they accept. Pre-order creation does not lock a network, token, or address; DSPay allocates and locks an enabled receiving address only when the customer confirms a payment method in Checkout.

> Receiving addresses are configured at the **chain level** (not per-token): a single address receives every [DSPay](#term-dspay)-supported stablecoin on that chain (e.g. both [USDT](#term-usdt) and [USDC](#term-usdc)).

For each chain you want to accept payments on, configure the following in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/):
- **[networkId](#term-networkid)** (the chain)
- **The receiving address**
- **Status** (`ENABLED` / `DISABLED`)

> A network without an enabled receiving address does not appear as an available Checkout method. If no address remains available at confirmation time, DSPay returns `NO_ENABLED_ADDRESS`.

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

Configure the webhook URL that receives payment notifications in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/) and enable webhooks. Both HTTP and HTTPS are accepted; HTTPS is recommended in production.

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
   A Checkout method must be supported by the platform, have an enabled merchant receiving address, and—when the merchant supplied restrictions—appear in `allowedPaymentMethods`.

3. **The webhook toggle is OFF by default**: you must manually enable webhooks in the [DSPay Merchant Portal](https://mcashier.ds.pro/login/), otherwise [DSPay](#term-dspay) will never dispatch a callback. New merchants routinely miss this step — orders succeed, users pay, but the merchant backend never gets notified.

4. **First-time webhook enable auto-generates [apiSecret](#term-apisecret)**: the first time webhooks are toggled on and there is no existing key, an [apiSecret](#term-apisecret) is provisioned automatically. Toggling off and back on does **not** regenerate. **The merchant must retrieve this [apiSecret](#term-apisecret) from the portal** before they can sign order-creation requests or verify webhook signatures.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="chapter-4-creating-your-first-order"></a>
## Chapter 4: Creating Your First Order

This chapter covers server-to-server pre-order creation, request signing, idempotency and Checkout redirect behavior. Merchants call only public APIs and redirect to the returned `checkoutUrl`; DSPay owns all Checkout interactions.

<a id="order-suffix-mechanism-in-depth"></a>
### 4.1 Order Suffix Mechanism

DSPay uses a small fractional suffix to distinguish concurrent orders that share the same network, token, receiving address and original amount.

- `originPayAmount`: amount submitted at creation.
- `amountSuffix`: matching suffix allocated when the customer confirms a payment method.
- `payAmount = originPayAmount + amountSuffix`: final amount shown in Checkout and matched on-chain.
- Create returns only `originPayAmount`; payment method, address and final amount are locked later.

<a id="cashier-integration-flow"></a>
### 4.2 Standard Integration Flow

1. Optionally call `GET /dspay/public/supported-chains`.
2. Generate a unique `outOrderNo` and sign the complete create request on the merchant server.
3. Call `POST /dspay/public/order/create`.
4. DSPay creates a `CREATED` order and returns `orderNo`, `checkoutUrl`, `createAt` and `expireAt`.
5. Redirect the customer to `checkoutUrl`.
6. The customer chooses a network/token and confirms payment; DSPay locks the method, address, suffix and final amount.
7. DSPay detects the transfer and sends a webhook; the merchant may query the public order endpoint as fallback.

> The 5-minute signature window applies only to request replay protection. The independent 10-minute payment deadline begins when the order is created, even if the customer never confirms a payment method.

### 4.3 Create Order API

```http
POST /dspay/public/order/create
Content-Type: application/json
```

| Field | Type | Required | Signed | Description |
|---|---|---:|---:|---|
| `merchantNo` | string | Yes | Yes | DSPay merchant ID; non-empty, maximum string length 32 characters |
| `outOrderNo` | string | Yes | Yes | Merchant order ID; non-empty, maximum string length 64 characters; unique per merchant and used as the idempotency key |
| `productPrice` | decimal | No | Yes | Fiat display price; at most 14 integer digits and 6 fractional digits |
| `productPriceCurrency` | string | No | Yes | Fiat currency; maximum string length 16 characters |
| `productId` | string | No | Yes | Merchant product ID; maximum string length 64 characters |
| `attach` | object | No | Yes | Merchant JSON metadata; canonical JSON UTF-8 encoding is limited to 4096 bytes, with a maximum nesting depth of 3 |
| `payAmount` | decimal | Yes | Yes | Original token amount; minimum `0.0000000001`, at most 12 integer digits and 18 fractional digits |
| `allowedPaymentMethods` | array | No | Yes | Maximum 50 `{networkId,contractAddress}` entries; absent or empty means no extra restriction |
| `returnUrl` | string | No | Yes | Optional; redirects when the order reaches `TIMEOUT`. Must be a complete URL beginning with `http://` or `https://`; ports, paths, and query parameters are allowed. The entire URL is limited to 8192 characters. When absent, sign it as an empty string and retain `returnUrl=` |
| `successRedirectUrl` | string | No | Yes | Optional; redirects only when the order reaches `COMPLETED`. Must be a complete URL beginning with `http://` or `https://`; ports, paths, and query parameters are allowed. The entire URL is limited to 8192 characters. When absent, sign it as an empty string and retain `successRedirectUrl=`; Checkout stays on the DSPay success page |
| `timestamp` | long | Yes | Yes | Unix timestamp in milliseconds; absolute difference from DSPay server time must not exceed 300000 milliseconds (5 minutes) |
| `signature` | string | Yes | No | Lowercase HMAC-SHA256 hexadecimal string, exactly 64 characters; the field itself is not signed |

Each `allowedPaymentMethods[]` entry requires a non-empty `networkId` and `contractAddress`. `networkId` has a maximum string length of 64 characters, while `contractAddress` has a maximum string length of 128 characters. Together they must identify a payment method returned by `supported-chains`. When restrictions are supplied, available methods are:

```text
platform list ∩ merchant enabled addresses ∩ allowedPaymentMethods
```

Merchant order is preserved. Without restrictions, receiving-address creation time determines display order.

```json
{
  "merchantNo": "DSM2080260022215368706",
  "outOrderNo": "ORDER-20260821-001",
  "productPrice": "100.00",
  "productPriceCurrency": "USD",
  "productId": "PROD-001",
  "attach": {"customerId":"CUST-1001"},
  "payAmount": "100.00",
  "allowedPaymentMethods": [{"networkId":"evm--56","contractAddress":"0x55d398326f99059ff775485246999027b3197955"}],
  "returnUrl": "https://merchant.example.com/orders/ORDER-20260821-001",
  "successRedirectUrl": "https://merchant.example.com/pay/success",
  "timestamp": 1787292000000,
  "signature": "a1b2c3d4..."
}
```

Response:

```json
{
  "code": 0,
  "data": {
    "orderNo": "1949695024925671424",
    "outOrderNo": "ORDER-20260821-001",
    "checkoutUrl": "https://cashier.ds.pro/checkout/1949695024925671424",
    "status": "CREATED",
    "originPayAmount": "100.00",
    "createAt": 1787292001000,
    "expireAt": 1787292601000
  },
  "header": {"resultCode": 0}
}
```

Create does not return network, token, final amount, receiving address or QR payload. They do not exist until Pay Now; Checkout generates the QR code client-side.

### 4.4 Signing and Idempotency

Create-signature field order is fixed. Optional fields remain as `key=` when absent:

```text
merchantNo -> outOrderNo -> productPrice -> productPriceCurrency -> productId
-> attach -> payAmount -> allowedPaymentMethods -> returnUrl
-> successRedirectUrl -> timestamp
```

```text
signature = lowercaseHex(HMAC_SHA256(apiSecret, canonical UTF-8 string))
```

- Trim strings; format decimals without scientific notation.
- Canonicalize `attach` recursively by sorted object keys and compact JSON; normalize numeric zero to `0` and remove insignificant trailing zeros.
- Preserve `allowedPaymentMethods` order, remove duplicates, join each entry as `networkId|contractAddress`, comma-separated; lowercase `0x` addresses.

`merchantNo + outOrderNo` is the idempotency key. An identical retry returns the original `orderNo/checkoutUrl/expireAt`. A retry with different business fields returns code `40901` with “Merchant order number has already been used”. Always reuse the original `outOrderNo` when retrying.

### 4.5 Checkout Behavior and Redirects

- Use the returned `checkoutUrl`; do not build it from business parameters.
- Pay Now locks once and does not extend the 10-minute payment deadline.
- In multiple tabs, the first successful Pay Now wins; later calls return the same payment data.
- Timed-out/closed links cannot restart an order.
- Checkout links cannot be viewed 180 days after order creation; this does not change the order state.

| State | Redirect behavior |
|---|---|
| `COMPLETED` with `successRedirectUrl` | Redirect to `successRedirectUrl` |
| `COMPLETED` without it | Stay on the DSPay success page; never fall back to `returnUrl` |
| Order reaches `TIMEOUT` and `returnUrl` is configured | Redirect to `returnUrl` |
| Order reaches `TIMEOUT` without `returnUrl` | Stay on the DSPay timeout page |
| Any other case | Do not redirect through `returnUrl` |

Redirects are navigation only, never proof of payment. Query DSPay server-to-server before fulfillment.

<a id="java-end-to-end-demo"></a>
### 4.6 Java End-to-End Demo

The maintained [Java Demo](../Demo/back-end/java/README.md) builds and canonicalizes the complete request on the merchant server, calls the public create endpoint, checks the top-level `code`, redirects to `checkoutUrl`, verifies raw-body webhooks and performs signed public queries.

- [`DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java)
- [`start.sh`](../Demo/back-end/java/start.sh) / [`stop.sh`](../Demo/back-end/java/stop.sh)

<a id="nodejs-end-to-end-demo"></a>
### 4.7 Node.js / PHP Demos

- [Node.js Demo guide](../Demo/back-end/nodejs/README.md)
- [Node.js server](../Demo/back-end/nodejs/src/server.js)
- [Node.js signer](../Demo/back-end/nodejs/src/signer.js)
- [PHP Demo guide](../Demo/back-end/php/README.md)

### 4.8 Create-Request Triage

| Code | Cause | Check |
|---|---|---|
| `40001` | Parameter validation failed | Lengths, decimal format, URLs, and payment-method array |
| `40901` | Merchant order number has already been used | Same `outOrderNo` was retried with changed business fields |
| `50501` | Merchant not found | `merchantNo` |
| `50503` | API secret disabled | Key status in Merchant Portal |
| `50609` | No enabled receiving address | Enable an address for an eligible network |
| `50613` | Invalid signature | Field order, empty fields, JSON/array canonicalization, and key |
| `50614` | Expired timestamp | NTP and the five-minute window |

### 4.9 ⚠️ Pitfalls

1. Every create business field is signed, including product data, `attach`, payment restrictions, and both redirect URLs. Keep absent optional fields as empty keys.
2. Never serialize money through binary floating point; use plain decimal strings.
3. The signature window is not the order lifetime.
4. `CREATED` does not imply that a payment method has already been locked.
5. Idempotent retries must not change business fields.
6. Never fulfill from a browser redirect; trust only a verified `COMPLETED` webhook or server-to-server query.

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
| `CREATED` | Created; payment method may be unlocked or locked | ❌ Not sent | Scanned only when payment fields are complete | Allowed only when payment fields are complete |
| `TIMEOUT` | 10min elapsed without completion | ❌ Not sent | Scanned only when payment fields are complete | Allowed only when payment fields are complete |
| `CLOSED` | 40min elapsed; system closed the order | ✅ `CLOSED` sent | ❌ **Stopped** | ✅ (reopens, `reopened=true`) |
| `COMPLETED` | On-chain settlement / supplement complete | ✅ `COMPLETED` sent | — | — |
| `REFUNDED` | Merchant refund succeeded | ✅ `REFUNDED` sent | — | — |

> Webhooks are emitted only when an order enters `CLOSED`, `COMPLETED`, or `REFUNDED`. DSPay Cashier handles the payer-facing pending state and countdown. If the merchant backend needs active tracking, use the [active-query endpoint](#merchant-active-order-query-webhook-fallback) instead of waiting for `CREATED` / `TIMEOUT` webhooks.

**Three behaviors that are easily missed**:

1. **`TIMEOUT` does not emit a webhook**: the 10-minute transition only advances order state. The order continues waiting for on-chain settlement and auto-detection; its eventual state is either CLOSED (40min) or COMPLETED (on-chain / supplement). Do not rely on a webhook to detect TIMEOUT.

2. **Unlocked orders are excluded from detection and supplement**: DSPay scans only `CREATED` / `TIMEOUT` orders with complete payment fields. Supplementing an unlocked order is rejected. Auto-detection stops after `CLOSED`; later settlements require manual review and supplement in the Merchant Portal.

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
  "attach": {"customerId": "CUST-1001", "source": "web"},
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
  "receivingAddress": "0x1111111111111111111111111111111111111111",
  "networkId": "evm--1",
  "chainName": "Ethereum",
  "reopened": false,
  "timestamp": 1717689600000
}
```

**Field reference**:

| Field | Type | Always returned | Nullable | Description |
|------|------|-----------------|----------|------|
| `orderNo` | string | Yes | No | Order ID, e.g. `DS2024...`. |
| `outOrderNo` | string | Yes | No | Merchant external order ID (required at order creation and echoed back). |
| `attach` | object | Conditional | No | Echoed unchanged when supplied at creation; otherwise omitted rather than returned as `null`. |
| `eventType` | string | Yes | No | Event type: `CLOSED` / `COMPLETED` / `REFUNDED`. |
| `status` | string | Yes | No | Current order status enum. |
| `payAmount` | string | Yes | Yes | Final amount due, including the suffix (Decimal string); `null` if the order closes before payment confirmation. |
| `originPayAmount` | string | Yes | No | Original amount submitted by the merchant at order creation, excluding the suffix. |
| `amountSuffix` | string | Yes | No | Order-identification suffix; `"0"` when no suffix is added. |
| `actualReceivedAmount` | string | Yes | Yes | Actual received amount; `null` until payment is confirmed. |
| `actualUsdAmount` | string | Yes | Yes | Actual received USD value; `null` until payment is confirmed. |
| `refundAmount` | string | Yes | Yes | Refund token amount; populated only for `REFUNDED`, otherwise `null`. |
| `refundUsdAmount` | string | Yes | Yes | Refund USD value; populated only for `REFUNDED`, otherwise `null`. |
| `refundTxHash` | string | Yes | Yes | Refund transaction hash; populated only for `REFUNDED`, otherwise `null`. |
| `txHash` | string | Yes | Yes | On-chain transaction hash; `null` until payment is confirmed. |
| `tokenSymbol` | string | Yes | Yes | Selected token symbol, e.g. `USDT`; `null` if the order closes before payment confirmation. |
| `contractAddress` | string | Yes | Yes | Selected token contract address; `null` before payment confirmation or for a native coin. |
| `receivingAddress` | string | Yes | Yes | Merchant receiving address locked after payment confirmation; `null` before confirmation. |
| `networkId` | string | Yes | Yes | Selected network ID, e.g. `evm--1`; `null` if the order closes before payment confirmation. |
| `chainName` | string | Yes | Yes | Selected chain display name; `null` if the order closes before payment confirmation. When present, it matches [`GET /dspay/public/supported-chains`](#query-supported-chains-tokens). |
| `reopened` | boolean | Yes | No | Whether this is a merchant-initiated manual-supplement reopen after `CLOSED`. |
| `timestamp` | long | Yes | No | [DSPay](#term-dspay)-side send timestamp (Unix milliseconds). |

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
        if (payload == null || secret == null || signature == null
                || !signature.matches("(?i)^[0-9a-f]{64}$")) {
            return false;
        }
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
    if (typeof signature !== 'string' || !/^[0-9a-f]{64}$/i.test(signature)) {
        return false;
    }
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
        // Avoid Math.abs(now - timestamp): Long.MIN_VALUE can overflow and bypass validation.
        return timestamp >= 0
                && timestamp <= now + TOLERANCE_MS
                && now - timestamp <= TOLERANCE_MS;
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
    order_no   VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_no, event_type)
);
```

```java
@Transactional(rollbackFor = Exception.class)
public void handleNotify(NotifyPayload payload) throws Exception {
    try {
        // The idempotency row and fulfillment must share one DB transaction.
        // INSERT IGNORE: a duplicate insert returns affected rows = 0.
        int affected = jdbc.update(
            "INSERT IGNORE INTO notify_processed (order_no, event_type) VALUES (?, ?)",
            payload.getOrderNo(), payload.getEventType()
        );
        if (affected == 0) {
            // Duplicate callback — ACK immediately to stop DSPay from retrying.
            return;
        }
        // On failure, the transaction rolls back both fulfillment and the idempotency row.
        fulfillOrder(payload.getOrderNo());
    } catch (Exception e) {
        // Propagate the error; do not return SUCCESS.
        throw e;
    }
}
```

#### Recommended Implementation B — Redis SETNX

```java
public void handleNotify(NotifyPayload payload) throws Exception {
    String key = "notify:" + payload.getOrderNo() + ":" + payload.getEventType();
    // Use a short PROCESSING TTL so a crashed worker does not block retries for days.
    boolean firstTime = redis.opsForValue()
            .setIfAbsent(key, "PROCESSING", Duration.ofMinutes(5));
    if (!firstTime) {
        // Duplicate callback — ACK immediately to stop DSPay from retrying.
        return;
    }
    try {
        // First-time handling — execute business logic.
        fulfillOrder(payload.getOrderNo());
        // Preserve DONE beyond the complete retry window; at least 30 days is recommended.
        redis.opsForValue().set(key, "DONE", Duration.ofDays(30));
    } catch (Exception e) {
        // Release the reservation so the next DSPay retry can process it again.
        redis.delete(key);
        throw e;
    }
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
> 🚫 **Previous-event cancellation**: if the order state changes mid-retry (for example, a `CLOSED` order is supplemented to `COMPLETED`), DSPay stops delivering the previous event and sends the new-state event separately.

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

```http
POST /dspay/public/order/query
```

#### Request fields

| Field | Required | Signed | Constraints and meaning |
|---|---:|---:|---|
| `merchantNo` | Yes | Yes | Non-empty merchant ID; maximum 32 characters |
| `orderNo` | Conditional | When non-empty | At least one of `orderNo/outOrderNo`; maximum 64 characters |
| `outOrderNo` | Conditional | When non-empty | At least one of `orderNo/outOrderNo`; maximum 64 characters |
| `timestamp` | Yes | Yes | Unix milliseconds; absolute server-time difference must not exceed 300000 ms |
| `signature` | Yes | No | Lowercase HMAC-SHA256 hexadecimal string, exactly 64 characters |

When both identifiers are present, the query uses an AND match. Sign only non-empty optional identifier fields, in this order:

```text
merchantNo -> orderNo(if present) -> outOrderNo(if present) -> timestamp
```

Example when querying by `orderNo`:

```text
merchantNo=DSM2080260022215368706&orderNo=1949695024925671424&timestamp=1787292500000
signature = lowercaseHex(HMAC_SHA256(apiSecret, canonical UTF-8 string))
```

Public APIs use the common envelope. Top-level `code = 0` means success; `data` is the order array (`data=[]` when not found).

| Field | Always | Meaning |
|---|---:|---|
| `orderNo` | Yes | DSPay order number |
| `outOrderNo` | Yes | Merchant order number |
| `createAt` | Yes | Creation time, Unix milliseconds |
| `status` | Yes | Current order state |
| `originPayAmount` | Yes | Original amount submitted at creation |
| `amountSuffix` | Yes | Matching suffix; `0` before Pay Now or when unused |
| `payAmount` | No | Final amount after Pay Now |
| `networkId` | No | Locked network |
| `tokenAddress` | No | Locked contract or mint address |
| `receivingAddress` | No | Merchant receiving address locked for the order |
| `payerAddress` | No | Detected payer address |
| `txHash` | No | Payment transaction hash |
| `txLink` | No | Payment transaction explorer link |
| `productPrice` | No | Fiat product price supplied at creation |
| `productPriceCurrency` | No | Product-price currency |
| `productId` | No | Merchant product ID |
| `attach` | No | Original attached JSON |
| `actualReceivedAmount` | No | Actual token amount received |
| `paidSource` | No | `CHAIN_DETECTION` or `SUPPLEMENT` |
| `paidAt` | No | Detection time, Unix milliseconds |
| `completedAt` | No | Completion time, Unix milliseconds |
| `refundTxHash` | No | Refund transaction hash |
| `refundTxLink` | No | Refund transaction explorer link |
| `refundAmount` | No | Refunded token amount |
| `refundAt` | No | Refund completion time |
| `refundRemark` | No | Refund remark |

Internal USD snapshots, `amountDiff`, `statusDesc` and `tokenSymbol` are not returned.

Use webhooks as the normal path. Query with backoff only after a missed webhook, a customer redirect, or during reconciliation. Generate a fresh `timestamp` and `signature` for every query, and keep all local state transitions idempotent.

### 5.12 ⚠️ Pitfalls (6)

1. **Verify the raw body**: use the original HTTP body string — never deserialize and re-`JSON.stringify()` (field-order changes break the signature). A Java web framework can expose the original request body directly; this repository's Node.js Demo accumulates raw bytes from the built-in HTTP request stream and does not require `raw-body` or any other npm package. This is the **single most common** cause of webhook verification failures.

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

4. **A previous event may stop retrying**: when an order state changes mid-retry, DSPay cancels delivery of the previous event and sends the new-state event separately. Handle events idempotently by `orderNo + eventType`.

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
- Test tokens: keep at least 0.02 [USDT](#term-usdt) to cover a 0.01 USDT test order plus its matching suffix, and fund the chain's gas token separately.

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
- Ethereum: at least 0.02 [USDT](#term-usdt) (`0xdac17f958d2ee523a2206206994597c13d831ec7`), plus ETH for gas.
- BSC: at least 0.02 [USDT](#term-usdt) (`0x55d398326f99059fF775485246999027B3197955`), plus BNB for gas.
- Tron: at least 0.02 [USDT](#term-usdt) (`TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t`), plus TRX or sufficient energy/bandwidth.

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
A: Compare the complete canonical string byte-for-byte. Frequent causes are field order, omitted empty keys, decimal scientific notation, or inconsistent `attach` / `allowedPaymentMethods` canonicalization. Java decimals should use `BigDecimal.toPlainString()`.

**Q: How do I handle amount precision in Node.js?**
A: `productPrice` and `payAmount` must be string literals like `'99.99'` or use Big.js — never JS `number`. JS `number` drifts into scientific notation past `Number.MAX_SAFE_INTEGER` or for very small decimals.

**Q: Does [apiSecret](#term-apisecret) need to be Base64-decoded before use?**
A: **No.** The [apiSecret](#term-apisecret) string is used directly as the HMAC key. Base64Url is only the storage encoding — [HMAC-SHA256](#term-hmac-sha256) is encoding-agnostic about the key byte sequence. Pass `secret.getBytes(UTF_8)` directly to `SecretKeySpec`.

**Q: What happens if the signature field order is wrong?**
A: The signature will not match → [`50613`](#error-50613). Use the complete fixed order: `merchantNo → outOrderNo → productPrice → productPriceCurrency → productId → attach → payAmount → allowedPaymentMethods → returnUrl → successRedirectUrl → timestamp`. Every business field is signed; absent optional fields remain as `key=`.

### 9.3 Orders

**Q: Why does Checkout display a different `payAmount` from my `originPayAmount`?**
A: The suffix mechanism. Create records the original amount; DSPay allocates a matching suffix when the customer confirms a payment method. The payer must send the final Checkout amount; use webhook or public-query results for reconciliation.

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

**Q: What happens if order state changes while a previous event is retrying?**
A: DSPay stops the previous delivery and sends the new-state event separately. For example, if a `CLOSED` order is supplemented to `COMPLETED`, `CLOSED` delivery stops and `COMPLETED` is sent. Handle events idempotently by `orderNo + eventType`.

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
A: The platform list defines supported methods; enabled merchant addresses define which networks the merchant can receive on; `allowedPaymentMethods` may further restrict one order. Checkout shows their intersection.

**Q: How do I handle [`50503`](#error-50503) `API_SECRET_DISABLED`?**
A: The key has been frozen. If you froze it yourself, restore it from the portal after triage; if someone else did, contact the merchant administrator.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="appendix-a-java-reference-integration"></a>
## Appendix A: Java Reference Integration

Java integration has one canonical implementation in [`Demo/back-end/java`](../Demo/back-end/java/README.md). It covers signed public create/query calls, redirecting to the returned `checkoutUrl`, raw-body webhook verification, and strict acknowledgement.

| File | Purpose |
|------|---------|
| [`README.md`](../Demo/back-end/java/README.md) | Requirements, configuration, run commands, endpoints, and signature rules |
| [`src/DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java) | Create/query signing, public API calls, Checkout redirect, raw-body verification, and strict ACK |
| [`start.sh`](../Demo/back-end/java/start.sh) / [`stop.sh`](../Demo/back-end/java/stop.sh) | Background process lifecycle |

The create flow is: **merchant backend signs → calls the public create API → receives `checkoutUrl` → redirects the customer**. The customer then confirms a network and token in DSPay Checkout.

> Source code is intentionally not copied into this appendix. Maintaining the canonical demo automatically keeps this reference current.

---

[↑ Back to Table of Contents](#table-of-contents)

<a id="appendix-b-nodejs-reference-integration"></a>
## Appendix B: Node.js Reference Integration

Node.js integration has one canonical implementation in [`Demo/back-end/nodejs`](../Demo/back-end/nodejs/README.md). It uses built-in Node.js modules and covers signed public create/query calls, Checkout redirects, and webhook verification.

| File | Purpose |
|------|---------|
| [`README.md`](../Demo/back-end/nodejs/README.md) | Requirements, configuration, run commands, endpoints, and signature rules |
| [`src/server.js`](../Demo/back-end/nodejs/src/server.js) | HTTP routes, public create/query API calls, 302 redirect, and raw-body webhook handling |
| [`src/signer.js`](../Demo/back-end/nodejs/src/signer.js) | Order signing and constant-time callback signature verification |
| [`package.json`](../Demo/back-end/nodejs/package.json) | Start command and runtime metadata |

The create flow is: **merchant backend signs → calls the public create API → receives `checkoutUrl` → redirects the customer**. The customer then confirms a network and token in DSPay Checkout.

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
| <a id="error-40901"></a>40901 | STATE_CONFLICT | Merchant order number has already been used; the same merchant retried an `outOrderNo` with different business fields. |
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

#### SIWE Authentication (509xx)

| code | msg | Description |
|------|------|------|
| <a id="error-50901"></a>50901 | SIWE_NONCE_NOT_FOUND | [SIWE](#term-siwe) nonce does not exist. |
| <a id="error-50902"></a>50902 | SIWE_NONCE_EXPIRED | [SIWE](#term-siwe) nonce has expired (TTL 5 minutes). |
| <a id="error-50903"></a>50903 | SIWE_SIGNATURE_INVALID | [SIWE](#term-siwe) signature is invalid (ecrecover-recovered address does not match). |
| <a id="error-50904"></a>50904 | SIWE_DOMAIN_MISMATCH | [SIWE](#term-siwe) domain mismatch. |
| <a id="error-50905"></a>50905 | SIWE_MESSAGE_INVALID | [SIWE](#term-siwe) message is invalid. |

[↑ Back to Table of Contents](#table-of-contents)
