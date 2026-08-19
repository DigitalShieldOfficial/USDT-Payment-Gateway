## 语言
[English](SDK.en-US.md) | [中文](SDK.zh-CN.md)

# DSPay 商户接入指南

> 本文档为 [DSPay](#term-dspay) 商户接入方的技术对接指南，覆盖注册认证、收款配置、订单创建、回调处理、对账运维、异常流程 SOP 全流程。每章按技术主题组织，章末附核心注意事项与常见坑点清单。

---

<a id="目录"></a>
## 目录

- [名词说明](#名词说明)
- [快速开始](#快速开始)
- [第 1 章：开始之前](#第-1-章开始之前)
- [第 2 章：注册与认证](#第-2-章注册与认证)
- [第 3 章：配置收款](#第-3-章配置收款)
- [第 4 章：创建第一笔订单](#第-4-章创建第一笔订单)
- [第 5 章：接收回调](#第-5-章接收回调)
- [第 6 章：对账与运维](#第-6-章对账与运维)
- [第 7 章：异常流程 SOP](#第-7-章异常流程-sop)
- [第 8 章：测试与联调](#第-8-章测试与联调)
- [第 9 章：FAQ](#第-9-章-faq)
- [附录 A：Java 综合接入示例](#附录-a-java-综合接入示例)
- [附录 B：Node.js 综合接入示例](#附录-b-nodejs-综合接入示例)
- [附录 C：错误码完整列表](#附录-c错误码完整列表)

---

<a id="名词说明"></a>
## 名词说明

> 本文档涉及的技术缩写与专有名词速查。初次接入时建议先过一遍，避免概念混淆。

| 术语 | 说明 |
|------|------|
| <a id="term-dspay"></a>**DSPay** | 多链稳定币收款网关（本服务） |
| <a id="term-siwe"></a>**SIWE** | Sign-In with Ethereum，基于 EIP-4361 的钱包登录标准；商户用 EVM 钱包对约定消息签名完成登录认证 |
| <a id="term-jwt"></a>**JWT** | JSON Web Token，[DSPay 后台](https://mcashier.ds.pro/login/)登录会话凭证（7 天滑动过期）；商户后端对接 API 不依赖 JWT，使用 `apiSecret` 签名（详见 [§2.2](#会话有效期)） |
| <a id="term-apisecret"></a>**apiSecret** | 商户 API 密钥，用于生成收银台链接签名和回调验签，在 [DSPay 后台](https://mcashier.ds.pro/login/)获取，妥善保管 |
| <a id="term-merchantno"></a>**merchantNo** | 商户业务编号（`DSM` 前缀，如 `DSM1`），生成收银台链接时必传；对外暴露的业务编码，**非 DB 自增主键**（避免业务量泄露 + 防枚举） |
| <a id="term-networkid"></a>**networkId** | 链的唯一标识（如 `evm--1` = Ethereum 主网），完整列表见 [§3.2](#networkid-速查表) |
| <a id="term-contractaddress"></a>**contractAddress** | 代币合约地址，创建订单时与 `networkId` 一起定位代币 |
| <a id="term-hmac-sha256"></a>**HMAC-SHA256** | 哈希消息认证码算法；DSPay 用 `apiSecret` 作 key 对规范化字符串计算，输出 hex 小写 |
| <a id="term-evm"></a>**EVM** | Ethereum Virtual Machine，以太坊虚拟机；EVM 系链指兼容以太坊智能合约的链（Ethereum / BSC / Polygon / Arbitrum / Base） |
| <a id="term-usdt"></a>**USDT** / <a id="term-usdc"></a>**USDC** | 与美元锚定的稳定币（Tether USD / Centre USD Coin） |
| **尾数（<a id="term-amountsuffix"></a>amountSuffix）** | DSPay 为区分同金额并发订单附加的小额尾数（如 100.001 中的 0.001）。稳定币按 6 位精度处理：商户金额最多使用前 2 位小数，后 4 位由 DSPay 生成尾数，详见 [§4.1](#订单尾数机制详解) |
| **补单（<a id="term-supplement"></a>supplement）** | CLOSED 订单后续链上到账时，由商户在后台确认到账、订单重开为 COMPLETED 的操作 |
| **回调（<a id="term-webhook"></a>webhook）** | DSPay 向商户 `notifyUrl` 发送的 HTTP POST 通知，事件类型：`CLOSED` / `COMPLETED` / `REFUNDED`；`CREATED` / `TIMEOUT` 仅推进订单状态，不发送回调 |
| <a id="term-notifyurl"></a>**notifyUrl** | 商户接收 DSPay 回调通知的 URL（必须公网可达） |
| <a id="term-ntp"></a>**NTP** | Network Time Protocol，网络时间协议；用于服务器时钟同步 |
| <a id="term-nonce"></a>**nonce** | 一次性随机数；SIWE 登录流程中用于防重放攻击 |

> 文档其他位置出现上述术语时，可点击跳转回此章节查看定义。

---

[↑ 返回目录](#目录)

<a id="快速开始"></a>
## 快速开始（5 分钟跑通首笔订单）

> 目标：用最少的步骤创建一笔订单，看到完整的 API 响应。如果你更喜欢先理解原理，可以直接跳到[第 1 章](#第-1-章开始之前)开始阅读。

**前置准备清单**：

| 条件 | 获取方式 | 用时 |
|------|---------|------|
| [DSPay](#term-dspay) 商户账号 | 在 [DSPay 后台](https://mcashier.ds.pro/login/)使用 [EVM](#term-evm) 钱包登录（参考 [§2.1](#商户注册与登录)） | 30 秒 |
| 收款地址（任一链） | 在 [DSPay 后台](https://mcashier.ds.pro/login/)配置（参考 [§3.3](#配置收款地址)） | 1 分钟 |
| [apiSecret](#term-apisecret) | 在 [DSPay 后台](https://mcashier.ds.pro/login/)启用回调后获取（参考 [§3.5](#配置回调-url-启用回调)） | — |
| 测试用稳定币 | 钱包准备 0.01 [USDT](#term-usdt) | — |

> ⚠️ 如果你还没有以上任一项，请先完成对应章节再回来。下面 Step 1 可以零依赖体验。

### Step 1：3 分钟体验收银台

以下 demo 使用【本地签名 + 收银台链接】方式：无需调用 [DSPay](#term-dspay) API，本地 HMAC-SHA256 签名后直接生成支付链接。用户打开链接后自行选择链/代币，收银台前端自动创建订单。


填入你的 `merchantNo` + `apiSecret`，保存为 `create-order.mjs`，运行 `node create-order.mjs`。

**Node.js（Node 18+，零第三方依赖）**：

```javascript
import crypto from 'node:crypto';

// ===== [1] 商户凭证（必须修改） =====
const MERCHANT_NO = 'DSM1';
const API_SECRET = '你的-apiSecret';  // 在商户后台获取

// ===== [2] 签名字段（参与 HMAC 计算） =====
const signed = {
  // 稳定币默认 6 位精度：商户最多提交 2 位小数，后 4 位由 DSPay 生成尾数。
  // 必须使用普通十进制字符串，不得使用科学计数法；违反限制返回 50612。
  payAmount: '10.00',
  outOrderNo: 'MY-ORDER-001',   // 必填：商户外部订单号，不能为空
};

// ===== [3] 展示参数（仅透传，不参与签名，可选） =====
const display = {
  productPrice: '9.99',
  productPriceCurrency: 'USD',
  productId: 'PROD-001',
};

// ================ 业务区（无需修改） ================

// ① 拼规范化字符串（固定顺序：merchantNo / outOrderNo / payAmount / timestamp，顺序敏感）
const timestamp = Date.now();
const canonical = `merchantNo=${MERCHANT_NO}&outOrderNo=${signed.outOrderNo}&payAmount=${signed.payAmount}&timestamp=${timestamp}`;

// ② HMAC-SHA256（secret 直接 getBytes，不 Base64 解码）
const signature = crypto.createHmac('sha256', API_SECRET)
  .update(canonical, 'utf8').digest('hex');

// ③ 拼接收银台 URL
const url = new URL('https://cashier.ds.pro/');
url.searchParams.set('merchantNo', MERCHANT_NO);
url.searchParams.set('outOrderNo', signed.outOrderNo);
url.searchParams.set('payAmount', signed.payAmount);
url.searchParams.set('timestamp', String(timestamp));
url.searchParams.set('signature', signature);
// 展示参数（可选）
if (display.productPrice) url.searchParams.set('productPrice', display.productPrice);
if (display.productPriceCurrency) url.searchParams.set('productPriceCurrency', display.productPriceCurrency);
if (display.productId) url.searchParams.set('productId', display.productId);

console.log('收银台链接:');
console.log(url.toString());
console.log('\n链接有效期 5 分钟，请在浏览器中打开。过期重新运行脚本即可。');
```

**其他语言完整版**：Java（JDK 11+）见 [§4.8](#java-端到端-demo)，Node.js 含错误处理见 [§4.9](#node.js-端到端-demo)。

### Step 3：看到效果 ✅

预期输出（每次运行签名和时间戳不同，链接不同）：

```
收银台链接:
https://cashier.ds.pro/?merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=10.00&timestamp=1717689600000&signature=7de3fafc...

链接有效期 5 分钟，请在浏览器中打开。过期重新运行脚本即可。
```

> 上述 URL 仅展示格式；`timestamp` 和 `signature` 是示例值，不能直接用于实际支付。请运行代码生成当前有效链接。

在浏览器打开收银台链接，能看到支付页面，用户自行选择链/代币后扫码或转账即可完成支付。

### 下一步

1. [接收回调](#第-5-章接收回调) —— 启动本地服务接收 [DSPay](#term-dspay) 的异步支付通知
2. [订单状态机](#订单状态机前置必读) —— 了解订单从 CREATED → TIMEOUT → CLOSED / COMPLETED 的全过程
3. [测试与联调](#第-8-章测试与联调) —— ngrok 本地调试 + 端到端验证 + 幂等测试

---

[↑ 返回目录](#目录)

<a id="第-1-章开始之前"></a>
## 第 1 章：开始之前

本章介绍 [DSPay](#term-dspay) 的核心概念与架构，包括**多链稳定币收款网关**定位、四角色定义、接入全景图与前置准备清单。

### 1.1 DSPay 是什么

[DSPay](#term-dspay) 是一个**多链稳定币收款网关**（hosted 服务，商户无需部署节点或后端）：

- 支持 **9 条主流区块链**（[EVM](#term-evm) 系 5 条 + Solana / SUI / Tron / Polkadot AssetHub）
- 支持 **18 种稳定币**（仅 [USDT](#term-usdt) / [USDC](#term-usdc)，覆盖各链主流币种）
- 商户只需注册 → 配置收款地址 → 创建订单 → 接收回调即可完成收款
- 用户付款到商户链上地址，[DSPay](#term-dspay) 负责链上检测、订单状态推进、回调通知

### 1.2 四角色定义

| 角色 | 说明 | 本文档面向 |
|------|------|-----------|
| **商户** | 接入 [DSPay](#term-dspay) 的收款方，配置回调 URL + 验签密钥，接收回调后标记订单已支付 | ✅ 本文档主要面向商户后端开发者 |
| **[DSPay](#term-dspay) 平台** | 本服务，负责创建订单、监听链上到账、向商户发送回调 | — |
| **用户** | 付款方，在钱包 App 中向商户收款地址转账 | — |
| **区块链** | [EVM](#term-evm) / Solana / Tron 等公链，交易的最终仲裁层 | — |

### 1.3 接入全景图

| 步骤 | 阶段 | 说明 | 产出 |
|:----:|------|------|------|
| 1 | **注册商户** | [SIWE](#term-siwe) 钱包登录 | 商户 [JWT](#term-jwt) |
| 2 | **配置收款** | 配置地址 + 回调 URL | ENABLED 地址 |
| 3 | **创建订单** | HMAC-SHA256 签名后下单 | orderNo + 收款地址 |
| 4 | **用户支付** | 钱包向收款地址转账 | 链上交易 |
| 5 | **链上检测** | DSPay 轮询确认到账 | COMPLETED 状态 |
| 6 | **回调商户** | POST 通知 + HMAC 验签 | 商户业务处理 |
| 7 | **对账** | 查询订单列表与统计 | 财务核对 |

每阶段的输出作为下一阶段的输入，详见各对应章节。


### 1.4 前置条件清单

开始接入前，请准备：

- **[DSPay 后台](https://mcashier.ds.pro/login/)**：登录或注册商户账号，获取 `merchantNo`，并配置收款地址、回调 URL 和 `apiSecret`
- **[EVM](#term-evm) 钱包**：用于 [SIWE](#term-siwe) 登录（MetaMask / Rabby / Trust 等均可，只需能 `personal_sign`）
- **公网可达的回调 URL**：用于接收 [DSPay](#term-dspay) 异步通知（本地开发可用 ngrok / cpolar）
- **[NTP](#term-ntp) 时钟同步**：签名验证有 ±5 分钟时间窗口，服务器时钟必须准确
- **JDK 11+、Node.js 18+ 或 PHP 5.6+**：运行对应语言 Demo（其他语言可按规范自行实现）
- **测试用稳定币**：各链主网小额稳定币（如 0.01 [USDT](#term-usdt)）用于端到端联调

### 1.5 核心接入速查表

**商户需要对接的入口**：

| API | 方法 | 用途 |
|---|---|---|
| 打开收银台 | `https://cashier.ds.pro/?...` | 使用商户后端生成的签名链接进入收银台并创建订单 |
| 接收回调 | 商户实现 webhook | 接收 [DSPay](#term-dspay) 的支付/关闭/退款通知 |
| 主动查询订单 | POST /dspay/order/query | 未成功收到回调时查询订单状态（需 HMAC 签名） |

> 其他操作（注册登录、配置收款地址、配置回调 URL、订单统计报表、退款、补单、密钥管理）均在 **[DSPay 后台](https://mcashier.ds.pro/login/) UI** 完成，无需调 API。
> 用户收银台前端的订单状态轮询由 [DSPay](#term-dspay) 团队实现，商户无需自行开发。

---

[↑ 返回目录](#目录)

<a id="第-2-章注册与认证"></a>
## 第 2 章：注册与认证

本章说明商户注册与认证机制，包括后台登录方式、会话有效期与 `apiSecret` 的获取。

<a id="商户注册与登录"></a>
### 2.1 商户注册与登录

商户在 [DSPay 后台](https://mcashier.ds.pro/login/)使用 [EVM](#term-evm) 钱包登录（[SIWE](#term-siwe) 签名认证）。**首次登录自动创建商户账号**，无需单独注册。

登录后可获取：
- **[merchantNo](#term-merchantno)**：商户业务编号（`DSM` 前缀，如 `DSM1`），生成收银台链接时必传
- **[apiSecret](#term-apisecret)**：API 密钥，用于收银台链接签名和回调验签（后台页面展示，妥善保管）

<a id="会话有效期"></a>
### 2.2 会话有效期

后台登录会话有效期 7 天（滑动过期：每次活跃自动续期 7 天）。闲置 7 天后需重新登录。

> 商户后端生成收银台链接和进行回调验签时使用 [apiSecret](#term-apisecret) 签名，不依赖 [JWT](#term-jwt) 会话，不受此限制。

### 2.3 ⚠️ 坑点（2 条）

1. **首次登录需完成配置**：首次登录后还没有 [apiSecret](#term-apisecret) / 收款地址 / 回调 URL，需在后台完成配置后才能正常收款。否则创建订单会报 [`50609`](#error-50609) `NO_ENABLED_ADDRESS`。

2. **[apiSecret](#term-apisecret) 妥善保管**：[apiSecret](#term-apisecret) 用于签名和验签，泄漏会导致伪造订单/回调。建议定期在后台轮换（参考 [§6.4 密钥轮换](#密钥定期轮换)）。

---

[↑ 返回目录](#目录)

<a id="第-3-章配置收款"></a>
## 第 3 章：配置收款

本章描述收款配置全流程，包括链与代币**白名单查询**、**收款地址配置**、回调 URL 设置与双开关组合矩阵。

<a id="查询支持链代币白名单"></a>
### 3.1 查询支持链/代币白名单

调用公开接口（无需登录、无需 [merchantNo](#term-merchantno)）：

```
GET /dspay/public/supported-chains
```

返回 [DSPay](#term-dspay) 平台支持的全部链和代币白名单（9 链 + 18 稳定币；全量不按商户过滤）。返回的 `networkId` + `address` 可直接用于创建订单的 `networkId` / `contractAddress` 参数。

**响应示例**：

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

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `networkId` | string | 链 [networkId](#term-networkid)（如 `evm--1`），创建订单 `networkId` 参数用此值 |
| `chainName` | string | 链显示名（如 `Ethereum`） |
| `chainLogo` | string\|null | 链 logo URL，DB 未配置时为 null（前端可用默认图标兜底） |
| `tokens[].symbol` | string | 代币符号（`USDT` / `USDC`） |
| `tokens[].address` | string | 代币合约地址 / mint 地址，创建订单 `contractAddress` 参数用此值 |
| `tokens[].logoUri` | string\|null | 代币 logo URL，DB 未配置时为 null |

> 只展示 [USDT](#term-usdt)/[USDC](#term-usdc)，完整列表以接口实际返回为准。

<a id="networkid-速查表"></a>
### 3.2 networkId 速查表

| 链 | [networkId](#term-networkid) | 类型 | 状态 |
|----|-----------|------|------|
| Ethereum | `evm--1` | [EVM](#term-evm) | 启用 |
| BSC | `evm--56` | [EVM](#term-evm) | 启用 |
| Polygon | `evm--137` | [EVM](#term-evm) | 启用 |
| Arbitrum | `evm--42161` | [EVM](#term-evm) | 启用 |
| Base | `evm--8453` | [EVM](#term-evm) | 启用 |
| Solana | `sol--101` | Solana | 启用 |
| SUI | `sui--mainnet` | SUI | 启用 |
| Tron | `tron--0x2b6653dc` | Tron | 启用 |
| Polkadot AssetHub | `dot--asset-hub` | Polkadot | 启用 |

> 完整列表以 `GET /dspay/public/supported-chains` 返回为准。

<a id="配置收款地址"></a>
### 3.3 配置收款地址

商户只需为**要收款的链**配置 `ENABLED` 状态的收款地址；不收款的链无需配置。创建订单时若该 [networkId](#term-networkid) 下无 `ENABLED` 地址，会报 [`50609`](#error-50609) `NO_ENABLED_ADDRESS`。

> 收款地址按**链级别**配置（不区分 token）：一个地址接收该链上所有 [DSPay](#term-dspay) 支持的稳定币（如 [USDT](#term-usdt) / [USDC](#term-usdc)）。

在 [DSPay 后台](https://mcashier.ds.pro/login/)为每条要收款的链配置收款地址。每个地址需指定：
- **[networkId](#term-networkid)**（链）
- **收款地址**
- **启用状态**（ENABLED / DISABLED）

> 创建订单时，[DSPay](#term-dspay) 会从该 [networkId](#term-networkid) 下 ENABLED 的地址中选一个作为收款地址。若没有 ENABLED 地址，创建订单报 [`50609`](#error-50609)。

> 地址管理（增删改查、启停）均在后台 UI 完成。

### 3.4 后台关键配置概览

[DSPay 后台](https://mcashier.ds.pro/login/)提供两类关键配置，商户需理解其用途与默认状态：

| 配置 | 位置 | 默认 | 用途与影响 |
|------|------|------|-----------|
| 回调设置 | 后台 → 回调配置 | **关闭** | 配置回调 URL + 开关；**必须手动启用**才能收到 `CLOSED` / `COMPLETED` / `REFUNDED` 通知（创建订单、用户付款都成功，但回调没开 = 商户后端收不到通知） |
| 密钥管理 | 后台 → 安全设置 | 正常 | 查看 [apiSecret](#term-apisecret) / 紧急冻结密钥（`apiSecretEnabled=false`，冻结后回调停发 + 创建订单报 [`50503`](#error-50503)）/ regenerate 换新 key |

**关键提醒**：
- **回调开关默认关闭**：新商户最容易遗漏这一步，必须在后台手动启用
- **[apiSecret](#term-apisecret) 首次获取**：首次启用回调时自动生成，在后台页面查看
- **创建订单始终依赖密钥**：[HMAC-SHA256](#term-hmac-sha256) 签名校验，密钥冻结后无法下单

<a id="配置回调-url-启用回调"></a>
### 3.5 配置回调 URL + 启用回调

在 [DSPay 后台](https://mcashier.ds.pro/login/)配置回调 URL（商户接收支付通知的 webhook 地址）并启用回调。

关键配置项：
- **回调 URL**：商户的回调接收地址（必须公网可达）
- **回调开关**：默认**关闭**，必须在后台**手动启用**

> 首次启用回调时，若商户无 [apiSecret](#term-apisecret) 会自动生成。[apiSecret](#term-apisecret) 在后台页面查看。

> 回调 URL 变更后需在后台同步更新。

### 3.6 配置联系链接（可选）

在 [DSPay 后台](https://mcashier.ds.pro/login/)可配置一个联系链接（客服 URL / Telegram / 邮件）。DSPay 收银台前端会查询并展示给用户，用户支付遇到问题时可据此联系商户。

> 这是可选配置，但对用户体验很重要。

### 3.7 ⚠️ 坑点（4 条）

1. **[`50707`](#error-50707) vs [`50609`](#error-50609) 语义差异**：
   - [`50707`](#error-50707) `CHAIN_NOT_SUPPORTED` = [networkId](#term-networkid) 不在 9 链白名单（平台级不支持）
   - [`50609`](#error-50609) `NO_ENABLED_ADDRESS` = 链支持但商户没配 ENABLED 收款地址（商户级未配置）
   排查方向完全不同：[`50707`](#error-50707) 检查 [networkId](#term-networkid) 拼写，[`50609`](#error-50609) 去商户后台配地址。

2. **地址白名单 vs 商户 ENABLED 地址**：
   - 平台白名单（以 `GET /dspay/public/supported-chains` 返回为准）：定义"[DSPay](#term-dspay) 支持哪些链"
   - 商户级收款地址（在 [DSPay 后台](https://mcashier.ds.pro/login/)配置）：定义"本商户在每条链上用哪个地址收款"
   创建订单时**两个条件都要满足**：[networkId](#term-networkid) 在白名单 + 商户有为该 [networkId](#term-networkid)（链）配 ENABLED 地址。

3. **回调开关默认关闭**：必须在 [DSPay 后台](https://mcashier.ds.pro/login/)手动启用回调，否则 [DSPay](#term-dspay) 永远不发回调。新商户登录后最容易遗漏这一步——创建订单成功、用户付款成功、但商户后端永远收不到通知。

4. **首次启用回调自动生成 [apiSecret](#term-apisecret)**：首次在后台启用回调且无旧密钥时自动生成 [apiSecret](#term-apisecret)；关闭再开启时旧密钥保留不重新生成。**商户必须在后台拿到这个 [apiSecret](#term-apisecret) 才能做创建订单签名和回调验签**。

---

[↑ 返回目录](#目录)

<a id="第-4-章创建第一笔订单"></a>
## 第 4 章：创建第一笔订单

本章介绍商户通过 **Hosted Cashier** 创建第一笔订单的完整流程，包括订单尾数机制、收银台 URL 参数、[HMAC-SHA256](#term-hmac-sha256) 签名、时间戳与金额精度，以及 Java/Node.js/PHP 示例。

<a id="订单尾数机制详解"></a>
### 4.1 订单尾数机制详解

**为什么商户传入 `payAmount=100`，收银台却显示 `100.001`？**

[DSPay](#term-dspay) 用**尾数机制**区分同金额的并发订单。例如多个商品都是 100 [USDT](#term-usdt) 的订单，[DSPay](#term-dspay) 会为每个订单附加一个唯一尾数（如 100.001 / 100.002 / 100.003），用户付款时金额精确到尾数，[DSPay](#term-dspay) 据此自动匹配订单。


**关键点**：
- 收银台链接中的 `payAmount` 是商户期望金额；收银台展示、回调和主动查询结果中的 `payAmount` 是 [DSPay](#term-dspay) 生成的含尾数金额
- 用户必须按收银台显示的 `payAmount`（含尾数）付款，否则链上检测不匹配

**输入精度限制**：

[DSPay](#term-dspay) 对接入的稳定币统一按 **6 位小数精度**处理，其中商户金额最多使用前 **2 位小数**，后 **4 位小数**由 DSPay 用于生成订单识别尾数。

- `payAmount` 必须大于 0，且最多 2 位小数：`100`、`100.1`、`100.12` 合法，`100.123` 不合法
- 必须使用普通十进制字符串，不能使用科学计数法，也不要先转换为浮点数
- 建议避免无意义尾随零，传 `100.1` 即可，无需传 `100.10`
- 超过 2 位小数时，收银台创建订单失败并返回 [`50612`](#error-50612)

<a id="收银台接入流程"></a>
### 4.2 收银台接入流程

商户不需要选择具体链或代币，也不需要直接请求订单创建接口。标准接入流程如下：

1. 商户后端准备 `merchantNo`、唯一的 `outOrderNo`、`payAmount` 和当前 `timestamp`
2. 商户后端使用 `apiSecret` 计算 [HMAC-SHA256](#term-hmac-sha256) 签名
3. 将必填字段、签名和可选商品字段进行 URL 编码，拼接到 `https://cashier.ds.pro/`
4. 返回 HTTP 302，或将生成的收银台链接返回给商户前端
5. 用户打开收银台，自行选择链和稳定币；收银台完成订单创建并展示含尾数的实际应付金额

> `apiSecret` 只能保存在商户后端，禁止发送到浏览器、移动端或写入收银台 URL。

**前置条件**：

- 商户已在 [DSPay 后台](https://mcashier.ds.pro/login/)获取 `merchantNo` 和 `apiSecret`
- 至少配置一条 `ENABLED` 收款地址
- 商户服务器已启用 [NTP](#term-ntp) 时钟同步

<a id="生成收银台链接"></a>
### 4.3 生成收银台链接

收银台基础地址：

```text
https://cashier.ds.pro/
```

签名完成后，将参数按 URL 查询参数编码：

```text
https://cashier.ds.pro/?merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=99.99&timestamp=1717689600000&signature=7de3fafc...
```

> 示例中的 `timestamp` 和 `signature` 仅用于展示格式。实际链接必须由商户后端实时生成，有效期为 5 分钟。

#### 收银台 URL 参数

| 字段 | 必填 | 约束 | 说明 |
|------|------|------|------|
| `merchantNo` | ✓ | 非空 | 商户编号；只能由商户后端配置 |
| `outOrderNo` | ✓ | 非空，≤64 字符 | 商户外部订单号；参与签名，字段名区分大小写，必须写作 `outOrderNo`；每笔业务订单使用唯一值 |
| `payAmount` | ✓ | 大于 0，最多 2 位小数 | 不含尾数的稳定币金额；必须是普通十进制字符串，禁止科学计数法 |
| `timestamp` | ✓ | 当前时间 ±5 分钟 | Unix 毫秒时间戳，防止链接重放 |
| `signature` | ✓ | HMAC-SHA256 hex 小写 | 按 [§4.4](#签名规范化字符串) 计算 |
| `productPrice` | ✗ | 可选 | 商品价格，仅用于记录和展示 |
| `productPriceCurrency` | ✗ | 可选 | 商品价格币种，如 `USD` / `CNY` / `EUR` |
| `productId` | ✗ | ≤64 字符 | 商户产品 ID，回调时原样返回 |

`networkId` 和 `contractAddress` 不需要由商户传入。用户进入收银台后选择链和代币，收银台根据商户已配置的收款地址完成订单创建。

#### 打开链接后

- 收银台校验 `timestamp` 和 `signature`
- 用户选择链和稳定币
- 收银台创建订单并展示收款地址、二维码、过期时间和**含尾数的实际应付金额**
- 商户通过第 5 章的回调接收订单状态；未成功收到回调时，使用 [§5.11](#商户主动查询订单回调兜底) 主动查询

商户不需要解析收银台内部创建订单的响应，也不要在商户前端自行拼接签名。Java、Node.js 和 PHP 的完整实现分别见 [§4.8](#java-端到端-demo)、[§4.9](#node.js-端到端-demo) 和 [PHP Demo](../Demo/back-end/php/README.zh-CN.md)。

<a id="签名规范化字符串"></a>
### 4.4 签名规范化字符串

将收银台签名参数按**固定顺序**用 `key=value&` 风格拼接。计算签名时 value 不做 URL 编码；签名完成后再编码为收银台 URL 查询参数。签名集固定为 4 段：

```
merchantNo={merchantNo}&outOrderNo={outOrderNo}&payAmount={payAmount}&timestamp={timestamp}
```

**示例**：
```
merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=99.99&timestamp=1717689600000
```

> ⚠️ **顺序敏感**
>
> HMAC-SHA256 是对**字节序列**求哈希，字段顺序错乱 → 字节序列不同 → 哈希不同 → 验签失败（[`50613`](#error-50613)）。**商户后端必须严格按上述顺序拼接**。
>
> | 位置 | 字段 | 空值处理 |
> |---|---|---|
> | 1 | `merchantNo` | 非空，直接拼 |
> | 2 | `outOrderNo` | 必填且非空，直接拼接；字段名区分大小写 |
> | 3 | `payAmount` | `BigDecimal.toPlainString()`，禁用科学计数法 |
> | 4 | `timestamp` | 毫秒 long，直接拼 |
>
> 签名使用 URL 编码前的原始字段值；不要对整个 URL 或 URL 编码后的 `%xx` 字符串计算签名。

> **重要**：
> - `outOrderNo` 必填且不能为空，必须同时参与签名并出现在收银台 URL 中。字段名区分大小写，必须写作 `outOrderNo`，不是 `outOrderNO`。
> - `merchantNo` / `outOrderNo` / `payAmount` / `timestamp` 四字段构成最小签名集（防冒充、防商户订单号被替换、防篡改链上金额、防重放）。
> - `productPrice` / `productPriceCurrency` / `productId` 是可选展示字段，不参与签名；商户如需保证其完整性，应在回调验签后与本地订单数据比对。
> - 链和代币由用户在收银台选择，不需要商户传入或签名 `networkId` / `contractAddress`。

### 4.5 签名算法

[HMAC-SHA256](#term-hmac-sha256)，输出 **hex 小写**（与回调签名一致）。

> **重要**：`apiSecret` 字符串**直接作为 HMAC key 使用**，`secret.getBytes(UTF_8)`，**不要先 Base64 解码**。Base64Url 只是密钥的存储编码，[HMAC-SHA256](#term-hmac-sha256) 对 key 字节序列没有格式要求。

<a id="时间戳窗口"></a>
### 4.6 时间戳窗口

`timestamp`（毫秒）必须在 [DSPay](#term-dspay) 服务端当前时间 **±5 分钟**内，否则收银台拒绝该链接并返回 [`50614`](#error-50614) `ORDER_TIMESTAMP_EXPIRED`。建议商户服务器开启 [NTP](#term-ntp) 时钟同步。

### 4.7 金额字符串序列化

签名字段 `payAmount` 必须用**纯数字字符串**（无科学计数法）：

| 语言 | 方法 |
|------|------|
| Java | 从字符串构造 `BigDecimal`，输出时使用 `toPlainString()` |
| Node.js | 保留原始字符串并校验最多 2 位小数；不要转为 `number` / `toFixed()` |
| PHP | 始终将 `payAmount` 保持为字符串，`trim()` 后直接使用；不要转换为 `float`。使用 `preg_match()` 校验正数普通十进制格式和最多 2 位小数，参考 [PHP Demo](../Demo/back-end/php/README.zh-CN.md) |

> 否则 `1e2` 与 `100` 的签名字节不同，会导致验签失败（[`50613`](#error-50613)）。

<a id="java-端到端-demo"></a>
### 4.8 Java 端到端 demo

Java 可运行 Demo 统一维护在 [`Demo/back-end/java`](../Demo/back-end/java/README.zh-CN.md)，SDK 不再复制源码，避免两份代码不一致。

**Hosted Cashier 标准流程**：

1. 本地生成 `outOrderNo` 和 `timestamp`
2. 本地按 `merchantNo → outOrderNo → payAmount → timestamp` 签名
3. 对签名字段和可选商品字段做 URL 编码，拼接收银台 URL
4. 返回 HTTP 302 跳转收银台，由用户选择链和代币并完成订单创建

> `outOrderNo` 必须非空，并同时用于签名和收银台 URL；建议每个商户订单使用唯一值。

唯一源码：

- [Java Demo 使用说明](../Demo/back-end/java/README.zh-CN.md)
- [可运行源码 `DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java)
- [启动脚本](../Demo/back-end/java/start.sh) / [停止脚本](../Demo/back-end/java/stop.sh)

<a id="node.js-端到端-demo"></a>
### 4.9 Node.js 端到端 demo

Node.js 可运行 Demo 统一维护在 [`Demo/back-end/nodejs`](../Demo/back-end/nodejs/README.zh-CN.md)，SDK 不再复制源码，避免两份代码不一致。

**Hosted Cashier 标准流程**：

1. 本地生成 `outOrderNo` 和 `timestamp`
2. 本地按 `merchantNo → outOrderNo → payAmount → timestamp` 签名
3. 使用 `URLSearchParams` 拼接收银台 URL
4. 返回 HTTP 302 跳转收银台，由用户选择链和代币并完成订单创建

> `outOrderNo` 必须非空，并同时用于签名和收银台 URL；建议每个商户订单使用唯一值。

唯一源码：

- [Node.js Demo 使用说明](../Demo/back-end/nodejs/README.zh-CN.md)
- [HTTP 服务与收银台 URL 构建 `server.js`](../Demo/back-end/nodejs/src/server.js)
- [签名与回调验签 `signer.js`](../Demo/back-end/nodejs/src/signer.js)
- [启动配置 `package.json`](../Demo/back-end/nodejs/package.json)

### 4.10 验签失败排查表

| 错误码 | 原因 | 排查方向 |
|--------|------|----------|
| [`50613`](#error-50613) `ORDER_SIGNATURE_INVALID` | 商户未配置 [apiSecret](#term-apisecret) | 先在 [DSPay 后台](https://mcashier.ds.pro/login/)启用回调（自动生成 [apiSecret](#term-apisecret)）或在后台执行 regenerate |
| [`50613`](#error-50613) `ORDER_SIGNATURE_INVALID` | 签名计算错误 | 检查规范化字符串字段顺序、金额字符串序列化、[apiSecret](#term-apisecret) 正确性 |
| [`50614`](#error-50614) `ORDER_TIMESTAMP_EXPIRED` | 时间戳超出 ±5 分钟 | 检查服务器时间是否同步（[NTP](#term-ntp)） |

### 4.11 ⚠️ 坑点（5 条）

1. **BigDecimal 必须 `toPlainString()`**：`new BigDecimal("1E+2").toString()` 输出 `1E+2`，`toPlainString()` 输出 `100`。规范化字符串不一致 → [`50613`](#error-50613)。`payAmount` 参与签名，Java 代码中必须使用普通十进制字符串。

2. **Node.js 金额字段必须用字符串**：`payAmount` 用字符串字面量 `'99.99'` 或 Big.js，不能用 JS number。JS number 超过 `Number.MAX_SAFE_INTEGER` 或极小 decimal 会进入科学计数法，导致规范化字符串不一致。

3. **timestamp ±5 分钟窗口**：服务器必须开 [NTP](#term-ntp) 同步。超窗口 → [`50614`](#error-50614)。Docker 容器尤其注意时钟是否与宿主机同步。

4. **订单尾数机制与精度约定**：收银台最终显示的 `payAmount` 含尾数（如 100.001），**不是**商户链接中的 100。稳定币统一按 6 位精度处理：商户最多提交 2 位小数，后 4 位由 [DSPay](#term-dspay) 生成尾数。超过 2 位小数时返回 [`50612`](#error-50612)。用户必须按收银台显示的金额付款；商户对账以回调或主动查询结果为准。

5. **签名字段集 + 顺序敏感**：签名规范化字符串必须严格按 `merchantNo → outOrderNo → payAmount → timestamp` 顺序拼接。HMAC-SHA256 对字节序列求哈希，**字段顺序错乱 → 验签失败（[`50613`](#error-50613)）**。`outOrderNo` 必填且不能为空，必须参与签名并出现在收银台 URL 中。`productPrice` / `productPriceCurrency` / `productId` 可放入收银台 URL，但不参与签名；链和代币由用户在收银台选择。商户必须自行保证 `outOrderNo` 唯一。

> **安全提示**：`productPrice` / `productPriceCurrency` / `productId` 移出签名集后可被中间人篡改，但 `payAmount` 仍签名（链上资金安全有保障），篡改仅影响商户法币统计/对账。如需法币侧完整性，建议在回调验签后额外比对这三个字段。

---

[↑ 返回目录](#目录)

<a id="第-5-章接收回调"></a>
## 第 5 章：接收回调

本章说明回调处理机制，包括**订单状态机**、**回调验签四步法**、防重放、幂等设计、严格响应规范、重试策略，以及未收到回调时的签名主动查询接口。

<a id="订单状态机前置必读"></a>
### 5.1 订单状态机（前置必读）

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

| 状态 | 含义 | 是否发回调 | 自动检测 | 可手动补单 |
|------|------|-----------|---------|-----------|
| `CREATED` | 待支付（10min 倒计时） | ❌ 不发送 | ✅ 扫描 | ✅ |
| `TIMEOUT` | 10min 未支付（仍可继续等） | ❌ 不发送 | ✅ 扫描 | ✅ |
| `CLOSED` | 40min 未支付，系统关闭 | ✅ 发 `CLOSED` | ❌ **停止** | ✅（重开，`reopened=true`） |
| `COMPLETED` | 链上到账 / 补单完成 | ✅ 发 `COMPLETED` | — | — |
| `REFUNDED` | 商户退款成功 | ✅ 发 `REFUNDED` | — | — |

> 当前仅在订单进入 `CLOSED` / `COMPLETED` / `REFUNDED` 时发送回调。用户侧待支付状态和倒计时由 DSPay 收银台展示；商户后端如需主动跟踪状态，使用[主动查询接口](#商户主动查询订单回调兜底)，不要等待 `CREATED` / `TIMEOUT` 回调。

**三个关键行为（容易被忽略）**：

1. **TIMEOUT 不发回调**：10 分钟未付只推进订单状态，订单仍继续等待链上到账并接受自动检测。订单最终走向 CLOSED（40min）或 COMPLETED（链上到账/补单）。商户不要依赖回调感知 TIMEOUT。
2. **CLOSED 后链上自动检测停止**：[DSPay](#term-dspay) 自动检测任务 仅扫描 `CREATED` / `TIMEOUT` 状态订单。订单一旦进入 `CLOSED`，即使后续链上真的到账，[DSPay](#term-dspay) 也不会自动确认——**必须在 [DSPay 后台](https://mcashier.ds.pro/login/)操作补单**（`reopened=true` 路径）。
3. **补单不强制金额匹配**：自动检测要求链上金额与 `payAmount` 精确匹配（`compareTo == 0`）；手动补单则不校验金额，仅记录差额（`amountDiff = 实际 - 应付`），由商户自行判断。

> 💡 **为什么 CLOSED 后停止自动检测？**
> CLOSED 是"40 分钟彻底放弃"的终态，订单已经回调过 `CLOSED` 事件给商户（商户可能已经取消订单、释放了库存）。如果 CLOSED 后还自动检测，会反复触发 `CLOSED → COMPLETED` 重开，造成对账混乱。所以设计为"CLOSED 后只能人工补单"，由商户判断是否要复活该订单。

### 5.2 何时会收到回调

当订单状态变更为 **`CLOSED` / `COMPLETED` / `REFUNDED`** 时，[DSPay](#term-dspay) 会向商户配置的 `notifyUrl` 发送 HTTP POST 通知。`CREATED` / `TIMEOUT` 不发送回调。

| 事件 eventType | 触发时机 | 商户建议处理 |
|---|---|---|
| `CLOSED` | 下单后 40min 仍未完成 | 取消订单 / 释放库存 |
| `COMPLETED` | 链上到账或补单成功 | 标记已支付 / 发货 |
| `REFUNDED` | 商户主动退款成功 | 更新退款状态 |

> 💡 用户侧待支付展示和 10 分钟倒计时由 DSPay 收银台处理。商户后端如需跟踪待支付状态，使用[主动查询接口](#商户主动查询订单回调兜底)，不要等待 `CREATED` / `TIMEOUT` 回调。

### 5.3 回调协议

| 项 | 值 |
|------|------|
| Method | POST |
| Content-Type | application/json |
| 编码 | UTF-8 |
| 超时建议 | [DSPay](#term-dspay) 端无限超时（RestTemplate 默认），**商户端建议设置 5s 超时** |
| 重定向 | 不跟随 |

### 5.4 请求头

| Header | 说明 |
|--------|------|
| `Content-Type` | `application/json` |
| `X-DSPay-Signature` | [HMAC-SHA256](#term-hmac-sha256) hex 小写签名（签名算法见 [5.6](#回调验签四步走)） |

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

**字段说明**：

| 字段 | 类型 | 必返 | 说明 |
|------|------|------|------|
| `orderNo` | string | 是 | 订单号，如 `DS2024...` |
| `outOrderNo` | string | 是 | 商户外部订单号（创建订单时必传，原样回传） |
| `eventType` | string | 是 | 事件类型：`CLOSED` / `COMPLETED` / `REFUNDED` |
| `status` | string | 是 | 订单当前状态枚举 |
| `payAmount` | string\|null | 是 | 实际支付金额（含尾数，Decimal 字符串） |
| `originPayAmount` | string\|null | 是 | 商品原价（不含尾数） |
| `amountSuffix` | string\|null | 是 | 订单识别尾数（用于精确匹配订单） |
| `actualReceivedAmount` | string\|null | 是 | 实际到账金额（扣除 gas 后） |
| `actualUsdAmount` | string\|null | 是 | 实际到账 USD 价值（完成时锁定：CHAIN_DETECTION 约等于 usdAmount；SUPPLEMENT=actual × 补单时现价） |
| `refundAmount` | string\|null | 是 | 退款代币金额（仅 REFUNDED 事件非 null） |
| `refundUsdAmount` | string\|null | 是 | 退款 USD 价值（退款时锁定 = refundAmount × 退款时现价） |
| `refundTxHash` | string\|null | 是 | 退款交易哈希（仅 REFUNDED 事件非 null） |
| `txHash` | string\|null | 是 | 链上交易哈希 |
| `tokenSymbol` | string | 是 | 代币符号，如 `USDT` |
| `contractAddress` | string\|null | 是 | 代币合约地址。原生币（如 ETH/BNB/SOL）为 null；代币（如 USDT/USDC）为对应链的合约地址。与创建订单时 `contractAddress` 参数一致 |
| `networkId` | string | 是 | 网络 ID，如 `evm--1` |
| `chainName` | string | 是 | 链显示名称。与 [`GET /dspay/public/supported-chains`](#查询支持链代币白名单) 返回的 `chainName` 字段**完全一致**（如 `Ethereum` / `BNB Chain` / `Polygon` / `Solana` 等），便于商户后台直接展示 |
| `reopened` | boolean | 是 | 是否为 `CLOSED` 后由商户手动补单重开的路径 |
| `timestamp` | long | 是 | [DSPay](#term-dspay) 发送时间戳（毫秒） |

> **金额字段说明（5.1.5）**：
> - `originPayAmount`：商品原价，与商户创建订单时传入的一致
> - `amountSuffix`：为区分同金额并发订单附加的尾数
> - `payAmount` = `originPayAmount` + `amountSuffix`，与链上实际转账金额一致
> - **金额精确匹配校验建议切到 `originPayAmount`**（与商品原价一致），或用 `payAmount`（含尾数，与链上金额一致）

<a id="回调验签四步走"></a>
### 5.6 回调验签四步走

**算法**：[HMAC-SHA256](#term-hmac-sha256)
**签名内容**：HTTP body 原始字节（payload raw bytes，未反序列化前的原始字符串）
**输出格式**：hex 小写

**四步验签流程**：

1. **提取 raw body**：用 HTTP body 原始字符串，**不能**反序列化后重新 `JSON.stringify()`（字段顺序变化 → 签名不一致）
2. **计算 [HMAC-SHA256](#term-hmac-sha256)**：`apiSecret.getBytes(UTF_8)` 作为 key（**不 Base64 解码**），对 raw body 计算
3. **对比签名**：与 `X-DSPay-Signature` header 常量时间比较（防 timing attack），大小写不敏感
4. **校验时间戳窗口**：payload 的 `timestamp` 字段必须在当前时间 ±5 分钟内（防重放）

#### Java 验签示例

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class DspaySignatureVerifier {
    /**
     * @param payload   HTTP body 原始字符串
     * @param signature X-DSPay-Signature header 值（hex 小写）
     * @param secret    apiSecret 字符串（直接使用，不要 Base64 解码）
     */
    public static boolean verify(String payload, String signature, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        // 关键：secret 直接 getBytes，不做 Base64 解码
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        String expectedHex = bytesToHex(expected);
        // 常量时间比较，防 timing attack
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

#### Node.js 验签示例

```javascript
const crypto = require('crypto');

function verifySignature(payload, signature, secret) {
    // 关键：secret 直接作为 key，不做 Base64 解码
    const expected = crypto
        .createHmac('sha256', secret)
        .update(payload, 'utf8')
        .digest('hex');
    // 使用 timingSafeEqual 防 timing attack
    const expectedBuf = Buffer.from(expected, 'utf8');
    const sigBuf = Buffer.from(signature.toLowerCase(), 'utf8');
    if (expectedBuf.length !== sigBuf.length) return false;
    return crypto.timingSafeEqual(expectedBuf, sigBuf);
}
```

### 5.7 防重放攻击

**攻击场景**：攻击者截获一条合法的 [DSPay](#term-dspay) 回调请求（包含正确签名），在后续重复发送给商户接口，企图让商户重复发货。

**防御方案**：校验 `timestamp` 字段偏移在合理窗口内。

```java
public class ReplayAttackGuard {
    private static final long TOLERANCE_MS = 5 * 60_000L; // 5 分钟

    /**
     * @param timestamp 回调 payload 中的 timestamp 字段（毫秒）
     * @return true=新鲜（5 分钟内），false=过期或超前（疑似重放）
     */
    public static boolean isFresh(long timestamp) {
        long now = System.currentTimeMillis();
        return Math.abs(now - timestamp) < TOLERANCE_MS;
    }
}
```

> **为何容忍 5 分钟偏移**：允许服务器时钟不完全同步 + [DSPay](#term-dspay) 发送到商户的网络延迟。超过 5 分钟视为异常。

<a id="幂等设计"></a>
### 5.8 幂等处理

[DSPay](#term-dspay) 的重试机制（共 11 次尝试，累计跨度约 43 小时 21 分 30 秒以上；见 [5.9](#响应规范严格模式)）可能导致同一事件被发送多次，商户**必须**基于 `orderNo + eventType` 做幂等处理。

单订单最多产生 3 种回调事件。重复发送仍使用相同的 `orderNo + eventType`，不会增加新的幂等键。建议使用该组合作为复合主键，并将幂等记录保留至少 30 天。

#### 推荐实现 A：DB 唯一键

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
        // INSERT IGNORE：重复插入返回 affected rows = 0
        int affected = jdbc.update(
            "INSERT IGNORE INTO notify_processed (order_no, event_type) VALUES (?, ?)",
            payload.getOrderNo(), payload.getEventType()
        );
        if (affected == 0) {
            // 重复回调，直接 ACK，避免 DSPay 持续重试
            return;
        }
        // 首次处理，执行业务逻辑
        fulfillOrder(payload.getOrderNo());
    } catch (Exception e) {
        // 业务异常 → 不 ACK，让 DSPay 重试
        throw e;
    }
}
```

#### 推荐实现 B：Redis SETNX

```java
public void handleNotify(NotifyPayload payload) {
    String key = "notify:" + payload.getOrderNo() + ":" + payload.getEventType();
    boolean firstTime = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(30));
    if (!firstTime) {
        // 重复回调，直接 ACK，避免 DSPay 重试
        return;
    }
    // 首次处理，执行业务逻辑
    fulfillOrder(payload.getOrderNo());
}
```

> **幂等维度选择**：`orderNo + eventType` 而非仅 `orderNo`。
> 因为同一订单可能依次收到 `CLOSED` → `COMPLETED` → `REFUNDED`，每个 eventType 语义不同，不应互相覆盖。单订单最多 3 条幂等记录。

<a id="响应规范严格模式"></a>
### 5.9 响应规范（严格模式）

**成功响应示例**：

```
HTTP 200
Content-Type: application/json

{"code":"SUCCESS","msg":"ok"}
```

**严格匹配规则**：

- HTTP 状态码必须是 2xx
- `code` 必须是字符串 `"SUCCESS"`（大写）
- ❌ `"success"`（小写）不接受
- ❌ `"Success"`（首字母大写）不接受
- ❌ `"SUCCESS "`（带空格）不接受
- ❌ `200`（数字类型）不接受
- ❌ `{"data":{"code":"SUCCESS"}}`（嵌套结构）不接受
- ✅ `{"code":"SUCCESS","extra":"x"}`（额外字段可容忍）
- ✅ `{"code":"SUCCESS","msg":"any message"}`（msg 内容不校验）

**失败响应**：非 2xx，或响应 body 不满足上述 JSON 规则，都会触发 [DSPay](#term-dspay) 重试。

**重试策略**：

总尝试 **11 次**，各阶梯延迟均从**上一次尝试失败的时间**开始计算。全部失败时，理论累计跨度约 **43 小时 21 分 30 秒**，实际时间还会叠加 HTTP 请求耗时和调度器扫描延迟。

以下假设第一次发送时间为 `D0 00:00:00`，且每次请求立即返回、调度器无额外延迟：

| 阶段 | 第几次 | 相对上次尝试延迟 | 时间示例 | 说明 |
|------|--------|------------------|----------|------|
| **IMMEDIATE**（立即连发） | 第 1 次 | 立即 | `D0 00:00:00` | 首次发送 |
| | 第 2 次 | 0 秒 | 约 `D0 00:00:00` | 应对瞬时抖动（服务重启、网络微抖动） |
| | 第 3 次 | 0 秒 | 约 `D0 00:00:00` | 同上 |
| **ESCALATION**（阶梯式异步补偿） | 第 4 次 | 30 秒 | `D0 00:00:30` | 切换为调度器异步补偿 |
| | 第 5 次 | 1 分钟 | `D0 00:01:30` | |
| | 第 6 次 | 5 分钟 | `D0 00:06:30` | |
| | 第 7 次 | 15 分钟 | `D0 00:21:30` | |
| | 第 8 次 | 1 小时 | `D0 01:21:30` | |
| | 第 9 次 | 6 小时 | `D0 07:21:30` | |
| | 第 10 次 | 12 小时 | `D0 19:21:30` | |
| | 第 11 次 | 24 小时 | `D1 19:21:30` | 最后一次自动发送 |
| **停止重试** | — | 第 11 次失败后 | `D1 19:21:30` 之后 | 商户需通过查询或对账补处理 |

> `D0` 表示首次发送当天，`D1` 表示次日。示例时间是理论最早时间；实际发送可能因网络耗时和调度器扫描周期略晚。

> 💡 **设计意图**：立即 3 次覆盖"瞬时抖动"（服务重启、网络微抖动），阶梯 8 次覆盖"较长时间不可用"（商户服务宕机、部署窗口、DNS 故障）。约 43 小时以上的累计跨度用于覆盖商户服务长时间不可用场景。

> ⚠️ **幂等表设计提示**：11 次尝试可能重复投递同一事件。强烈建议用 `orderNo + eventType` 联合 key 做幂等去重（参考 [§5.8 幂等设计](#幂等设计)），不要用自增 ID 或 timestamp。

> ⚠️ **旧事件取消语义**：若发送过程中订单状态升级（如 `CLOSED` 通知重试时订单已被补单为 `COMPLETED`），[DSPay](#term-dspay) 会停止发送旧事件，避免事件类型与最新订单状态冲突，并另行发送新状态事件（如 `COMPLETED` + `reopened=true`）。

### 5.10 事件类型说明

| eventType | 触发条件 | 商户建议处理 |
|-----------|----------|-------------|
| `CLOSED` | 下单后 40min 仍未完成 | 取消订单 / 释放库存 |
| `COMPLETED` | 链上确认到账 / 自动检测 / 补单完成 | 标记已支付 / 发货 |
| `REFUNDED` | 商户主动退款成功 | 更新退款状态 |
| `COMPLETED` + `reopened=true` | 商户对 CLOSED 订单执行手动补单，订单重开 | 区分人工补单完成与普通完成 |

> `CREATED` / `TIMEOUT` 不发送回调。

> `reopened=true` 场景：订单已处于 `CLOSED`，商户在后台核实链上到账并执行手动补单。[DSPay](#term-dspay) 将订单重开为 `COMPLETED` 并发送回调；商户可据此区分普通完成与人工补单完成。

<a id="商户主动查询订单回调兜底"></a>
### 5.11 商户主动查询订单（回调兜底）

如果商户没有成功收到回调，可以使用该接口主动查询订单最新状态。该接口是**回调失败时的兜底手段**，不能替代回调；建议结合本地订单状态和退避策略按需轮询，避免持续高频请求。

#### 接口

```http
POST /dspay/order/query
Content-Type: application/json
```

示例完整地址：

```text
https://wallet.ds.pro/dspay/order/query
```

#### 请求字段

| 字段 | 类型 | 必填 | 限制与说明 |
|------|------|------|------------|
| `merchantNo` | string | 是 | 商户编号，最长 32 字符；参与签名 |
| `orderNo` | string | 条件必填 | DSPay 订单号，最长 64 字符；与 `outOrderNo` 至少传一个；参与签名 |
| `outOrderNo` | string | 条件必填 | 商户外部订单号，最长 128 字符；与 `orderNo` 至少传一个；参与签名；可能查询出多条 |
| `timestamp` | long | 是 | 当前 Unix 毫秒时间戳，必须在服务端时间 ±5 分钟内 |
| `signature` | string | 是 | 规范化字符串的 HMAC-SHA256 hex 小写签名，最长 128 字符 |

查询规则：

- 只查询当前 `merchantNo` 名下订单。
- 仅传一个订单号时，按该字段精确匹配。
- 两个订单号同时传入时，按 `orderNo AND outOrderNo` 精确匹配。
- 未传的可选字段在签名原文中仍保留 key，value 使用空字符串。
- 查询结果按 `createAt` 倒序排列；查无结果返回空数组 `[]`，不返回 `ORDER_NOT_FOUND`。

#### 查询签名

字段固定顺序：

```text
merchantNo → orderNo → outOrderNo → timestamp
```

规范化字符串：

```text
merchantNo={merchantNo}&orderNo={orderNo}&outOrderNo={outOrderNo}&timestamp={timestamp}
```

例如仅按 `outOrderNo` 查询：

```text
merchantNo=DSM1&orderNo=&outOrderNo=MY-ORDER-20260715-001&timestamp=1717689600000
```

签名计算：

```text
signature = hex_lowercase(HMAC_SHA256(apiSecret UTF-8 bytes, canonical UTF-8 bytes))
```

> `orderNo` / `outOrderNo` 为 null、空字符串或纯空白时，签名 value 统一为空字符串；非空时先 `trim()`。`apiSecret` 直接作为 UTF-8 key 使用，不要 Base64 解码。每次轮询都必须生成新的 `timestamp` 和 `signature`。

#### Node.js 18+ 最小 Demo

```js
const crypto = require('node:crypto');

const DSPAY_BASE_URL = 'https://wallet.ds.pro';
const API_SECRET = '你的-apiSecret';

async function queryOrders() {
    // orderNo / outOrderNo 至少一个非空。本例按商户外部订单号查询。
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
        throw new Error(`响应格式错误: ${text}`);
    }
    console.log(JSON.stringify(orders, null, 2));
    return orders;
}

queryOrders().catch(console.error);
```

#### 响应示例

接口直接返回订单数组：

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

响应使用 `NON_NULL` 序列化：尚未产生值的字段会被省略，不一定以 `null` 返回。

| 字段 | 类型 | 说明 |
|------|------|------|
| `orderNo` | string | DSPay 订单号 |
| `outOrderNo` | string | 商户外部订单号 |
| `createAt` | long | 创建时间，Unix 毫秒 |
| `status` | string | `CREATED` / `TIMEOUT` / `CLOSED` / `COMPLETED` / `REFUNDED` |
| `statusDesc` | string | 订单状态描述 |
| `payAmount` | decimal | 应付代币金额，包含 DSPay 尾数 |
| `originPayAmount` | decimal | 商户原始金额，不含尾数；历史订单可能省略 |
| `amountSuffix` | decimal | 订单识别尾数；历史订单可能省略 |
| `usdAmount` | decimal | 创建订单时锁定的 USD 金额快照 |
| `tokenSymbol` | string | 代币符号，如 `USDT` |
| `networkId` | string | 链标识，如 `evm--1` |
| `receivingAddress` | string | 收款地址 |
| `payerAddress` | string | 付款地址；未付款时省略 |
| `txHash` | string | 链上交易哈希；未上链时省略 |
| `txLink` | string | 交易浏览器跳转链接；无法生成时省略 |
| `productPrice` | decimal | 商品价格；未提供时省略 |
| `productPriceCurrency` | string | 商品价格币种；未提供时省略 |
| `actualReceivedAmount` | decimal | 实际到账代币金额；未完成时省略 |
| `amountDiff` | decimal | `actualReceivedAmount - payAmount`；未完成时省略 |
| `actualUsdAmount` | decimal | 完成时锁定的实际到账 USD 价值；未完成时省略 |
| `paidSource` | string | `CHAIN_DETECTION`（链上检测）或 `SUPPLEMENT`（人工补单）；未完成时省略 |
| `paidAt` | long | 付款时间，Unix 毫秒；未付款时省略 |
| `completedAt` | long | 完成时间，Unix 毫秒；未完成时省略 |

常见错误：

| 错误码 | 原因 |
|--------|------|
| [`40001`](#error-40001) | `orderNo` 与 `outOrderNo` 均为空，或字段格式不合法 |
| [`50501`](#error-50501) | `merchantNo` 不存在 |
| [`50503`](#error-50503) | `apiSecret` 已冻结 |
| [`50613`](#error-50613) | 缺少签名、签名字段顺序错误或签名不匹配 |
| [`50614`](#error-50614) | `timestamp` 超出 ±5 分钟窗口 |

轮询建议：

- 正常路径以回调为主，仅在超过业务预期时间仍未收到回调时启用查询。
- 使用退避间隔，不要固定高频轮询。
- 每次查询重新生成时间戳和签名。
- 查询到目标状态后停止轮询；空数组表示当前没有匹配订单，可稍后按业务策略重试。
- 无论回调还是主动查询，订单状态更新都必须保持幂等。

### 5.12 ⚠️ 坑点（6 条）

1. **raw body 验签**：用 HTTP body 原始字符串，不能反序列化后重新 `JSON.stringify()`（字段顺序变化 → 签名不一致）。Java 用 `@RequestBody String rawBody`，Node.js 用 `raw-body` 包提取原始字节流。这是回调验签失败**最常见**的原因。

2. **HMAC secret 直接 getBytes，不 Base64 解码**：[apiSecret](#term-apisecret) 是 Base64Url 编码 43 字符，但作为 HMAC key 时直接 `secret.getBytes(UTF_8)`，**不要先 Base64 解码**。Base64Url 只是密钥的存储编码，[HMAC-SHA256](#term-hmac-sha256) 对 key 字节序列没有格式要求。

3. **SUCCESS 严格大小写敏感**：必须 `{"code":"SUCCESS"}` 大写。`"success"` / `"Success"` / `"SUCCESS "`（带空格）/ 数字 200 / 嵌套结构全部不接受 → 触发重试。`code` 字段值用 `equals` 比较，不做 `trim`，不做 `equalsIgnoreCase`。

4. **timestamp ±5 分钟防重放**：回调 payload 的 `timestamp` 需校验在 5 分钟窗口内。这是防重放攻击的核心——攻击者截获旧回调重发，timestamp 会超窗口。商户服务器必须开 [NTP](#term-ntp) 同步。

5. **仅三种事件发回调**：`CLOSED` / `COMPLETED` / `REFUNDED`。`CREATED` / `TIMEOUT` 只推进订单状态，不发送回调；用户侧待支付状态和倒计时由收银台处理，商户后端可通过[主动查询接口](#商户主动查询订单回调兜底)跟踪状态。

6. **幂等维度是 orderNo + eventType**：不是仅 orderNo。同一订单可能依次收到 `COMPLETED → REFUNDED`，两者语义不同不应互相覆盖。用 `(orderNo, eventType)` 作为唯一键，重复的 `(orderNo, eventType)` 组合才直接 ACK。

---

[↑ 返回目录](#目录)

<a id="第-6-章对账与运维"></a>
## 第 6 章：对账与运维

本章描述对账与运维要点，包括订单统计接口、**金额对账策略**（`originPayAmount` vs `payAmount`）、回调监控告警与密钥定期轮换流程。

### 6.1 订单列表与统计

订单列表、统计报表均在 **[DSPay 后台](https://mcashier.ds.pro/login/) UI** 查看，支持按时间筛选、金额汇总、7 日明细等。

> 订单统计按**创建时间维度**展示。跨时区运营注意"今日"定义以商户后台设置的时区为准。

### 6.2 金额对账策略

[DSPay](#term-dspay) 订单有三个金额字段，对账时必须选对：

| 字段 | 含义 | 用途 |
|------|------|------|
| `originPayAmount` | 商品原价（不含尾数） | **✅ 推荐对账用这个**——与商户业务系统金额一致 |
| `amountSuffix` | 订单识别尾数 | 用于精确匹配订单 |
| `payAmount` | `originPayAmount + amountSuffix`（含尾数） | 与链上实际转账金额一致 |

**对账建议**：
- 财务对账比对 `originPayAmount`（商品原价），避免尾数差异导致的"金额不匹配"
- 链上交易核对比对 `payAmount`（含尾数），与用户实际付款金额一致
- `actualReceivedAmount` 是扣除 gas 后的实际到账金额，用于计算净收入

### 6.3 回调日志监控告警

商户应在自己的回调接收服务中记录并监控：

- 收到回调时间、`orderNo`、`eventType` 和处理结果
- 验签失败次数
- 业务处理失败次数与耗时
- 重复事件次数（相同 `orderNo + eventType`）
- 最后一次成功接收回调时间

建议在验签失败或业务处理失败持续增长、长时间没有成功回调时告警。不要记录完整 `apiSecret`；生产日志如需记录签名，也应限制访问权限和保留周期。

<a id="密钥定期轮换"></a>
### 6.4 密钥定期轮换

建议定期（如每季度）在 [DSPay 后台](https://mcashier.ds.pro/login/)轮换 [apiSecret](#term-apisecret)。

**轮换步骤**：

1. 在**低峰期**在后台执行 regenerate
2. **立即**更新商户后端的 [apiSecret](#term-apisecret) 配置
3. 观察 5 分钟回调验签是否正常

> regenerate 后飞行中的回调用旧密钥签名，商户端可能短暂验签失败。[DSPay](#term-dspay) 会使用新密钥按阶梯式间隔自动重试（30s / 1min / 5min / ...，最多 11 次），商户端只需容忍短暂数分钟的验签失败窗口。

### 6.5 ⚠️ 坑点（5 条）

1. **金额对账用 `originPayAmount`**：`payAmount` 含尾数（如 100.001），`originPayAmount` 是商品原价（100）。对账比对 `originPayAmount`，否则会因尾数差异报"金额不匹配"。`payAmount` 仅用于链上交易核对。

2. **regenerate 后飞行中回调验签失败**：regenerate 瞬间已发出的回调用旧密钥签名，商户用新密钥验签会失败。[DSPay](#term-dspay) 使用新密钥按 30s/1min/5min/15min/1h/6h/12h/24h 的阶梯间隔重试 8 次。商户端容忍短暂数分钟验签失败，不要因此回滚到旧密钥。

3. **11 次发送均失败后停止自动重试**：3 次立即发送 + 8 次阶梯式补偿，理论累计跨度约 43 小时 21 分 30 秒，实际时间可能因请求耗时和调度器扫描更长。商户应通过订单查询或对账发现遗漏状态，并提供人工补处理流程。

4. **旧事件可能停止重试**：发送过程中订单状态升级（如 `CLOSED` 重试时订单被补单为 `COMPLETED`），DSPay 会取消旧事件发送，另行发送新状态事件。商户只需按 `orderNo + eventType` 幂等处理收到的事件。

5. **统计金额使用 `COALESCE(actual_usd_amount, usd_amount)`**：优先使用完成时锁定的实际到账 USD 价值（含补单时实时汇率），fallback 到订单创建时锁定的 `usd_amount` 快照。**影响**：补单场景实际金额可能因补单时现价波动而与创建时不一致；链上检测完成（CHAIN_DETECTION）场景两者基本相等。

---

[↑ 返回目录](#目录)

<a id="第-7-章异常流程-sop"></a>
## 第 7 章：异常流程 SOP

本章说明异常场景的标准处理流程（SOP），包括超时未支付、**CLOSED 后补单流程**、退款、以及密钥泄漏的 disable/regenerate 决策树。

### 7.1 超时未支付（TIMEOUT）

**现象**：用户下单后 10 分钟内未付款，订单进入 `TIMEOUT` 状态。

**[DSPay](#term-dspay) 行为**：
- ❌ **不发回调**（TIMEOUT 是中间过渡态）
- ✅ 订单仍可继续等待付款 + 链上自动检测
- ✅ 用户可在 40 分钟内继续支付（直到 CLOSED）

**商户处理**：
- 前端展示"支付超时，仍可继续支付"提示
- DSPay 收银台前端会自动轮询订单状态
- 不要依赖回调感知 TIMEOUT

### 7.2 CLOSED 后链上到账（补单流程）

**现象**：订单进入 `CLOSED` 后，用户才完成链上转账，链上确实到账了。

**[DSPay](#term-dspay) 行为**：
- ❌ **自动检测停止**：[DSPay](#term-dspay) 自动检测任务 只扫 `CREATED` / `TIMEOUT`，CLOSED 后不再自动确认
- ✅ 必须商户**手动补单**

**补单流程**（在 [DSPay 后台](https://mcashier.ds.pro/login/)操作）：

1. 在后台订单详情查看链上实际到账金额
2. 确认到账后执行补单操作（订单重开为 COMPLETED）
3. 商户收到 `COMPLETED` + `reopened=true` 回调

> **补单不校验金额匹配**：[DSPay](#term-dspay) 仅记录 `actualReceivedAmount` + `amountDiff`，由商户判断是否接受。建议补单前先在后台订单详情查看链上实际到账金额，金额差异大时人工审核。

### 7.3 退款流程

在 **[DSPay 后台](https://mcashier.ds.pro/login/)** 操作退款。

**约束**：
- 仅 `COMPLETED` 状态订单可退
- `REFUNDED` 是终态，不可逆
- 退款成功后发 `REFUNDED` 回调

### 7.4 密钥泄漏应急决策树

当 `apiSecret` 疑似泄漏时，根据场景选择：

| 场景 | 操作 | 效果 |
|------|------|------|
| 半夜发现、技术不在岗 | 后台 → 安全设置 → 冻结密钥 | 旧 key 立即失效（回调停发 + 创建订单报 [`50503`](#error-50503)），紧急止血，**不生成新 key** |
| 确认泄漏 | 后台 → 安全设置 → regenerate 换新 key | 旧 key 立即失效，生成新 key（需同步更新商户端配置） |
| 排查后确认未泄漏 | 后台 → 安全设置 → 恢复密钥 | 旧 key 恢复有效 |

> ⚠️ **关键规则**：
> - `regenerate` **不改冻结状态**：冻结状态下执行 regenerate 会生成新 key 但保持冻结，需在后台手动恢复后才能使用
> - 密钥**确认已泄漏**时强烈建议 regenerate 换新 key，**不要仅恢复旧 key**（攻击者仍持有旧 key）

### 7.5 ⚠️ 坑点（4 条）

1. **CLOSED 后链上自动检测停止**：[DSPay](#term-dspay) 自动检测任务 只扫 `CREATED` / `TIMEOUT`。CLOSED 后即使链上到账也不会自动确认，必须在 [DSPay 后台](https://mcashier.ds.pro/login/)操作补单（`reopened=true`）。商户需有"CLOSED 订单人工补处理"流程，或监控 CLOSED 订单的链上到账情况。

2. **补单不校验金额匹配**：手动补单记录 `actualReceivedAmount` + `amountDiff`，由商户判断。建议补单前先在后台订单详情查看链上实际到账金额，金额差异大时人工审核。[DSPay](#term-dspay) 不会因金额不一致拒绝补单。

3. **密钥泄漏：冻结 vs 换新**：半夜发现、技术不在岗 → 先在后台冻结密钥（止血）；确认泄漏 → 在后台 regenerate 换新 key。不要仅恢复旧 key——攻击者仍持有旧 key。

4. **regenerate 不改冻结状态**：冻结状态下执行 regenerate 生成新 key 但保持冻结，需在后台手动恢复后才能使用。两个操作要分开执行，不能指望 regenerate 自动解冻。

---

[↑ 返回目录](#目录)

<a id="第-8-章测试与联调"></a>
## 第 8 章：测试与联调

本章介绍测试与联调流程，包括本地环境准备、ngrok 回调测试、推荐测试顺序与常见验签失败排查。

### 8.1 本地环境准备

- 启动 [DSPay](#term-dspay) 服务：`mvn spring-boot:run -Dspring-boot.run.profiles=local`
- 测试链信息：本地默认使用各链主网 RPC（生产配置同）
- 测试代币：使用小额真实代币测试（如 0.01 [USDT](#term-usdt)）

### 8.2 回调测试（ngrok / cpolar）

本地联调回调需将内网服务暴露到公网，推荐工具：

- **ngrok**：`ngrok http 8080` → 获得公网 URL
- **cpolar**：国内更稳定

```bash
# 启动 ngrok
ngrok http 8080

# 获得 URL 后，在商户后台配置回调 URL 为 ngrok 地址并启用回调
```

### 8.3 推荐测试顺序

按以下顺序测试，避免跳步导致问题难定位：

1. **单独验签名算法**：用在线 [HMAC-SHA256](#term-hmac-sha256) 工具对比你的签名输出
   - 复制规范化字符串 + [apiSecret](#term-apisecret)
   - 在线工具计算 [HMAC-SHA256](#term-hmac-sha256) hex
   - 对比你的代码输出
2. **端到端创建订单**：用 [§4.8 / §4.9](#java-端到端-demo) 的 demo 创建订单，确认返回 `orderNo` + `payAmount`（含尾数）
3. **触发回调验签**：用真实代币付款，触发 `COMPLETED` 回调，验签通过
4. **测试幂等性**：手动重发同一回调请求，确认商户后端不会重复发货

### 8.4 测试代币

各链主网小额稳定币测试：
- Ethereum：0.01 [USDT](#term-usdt)（`0xdac17f958d2ee523a2206206994597c13d831ec7`）
- BSC：0.01 [USDT](#term-usdt)（`0x55d398326f99059fF775485246999027B3197955`）
- Tron：0.01 [USDT](#term-usdt)（`TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t`）

> 测试付款金额必须与收银台显示的 `payAmount`（含尾数）完全一致，否则链上检测不匹配。

### 8.5 常见验签失败排查表

| 原因 | 排查方法 |
|------|---------|
| payload 反序列化后重新序列化（字段顺序变） | 用 raw body 原始字符串验签 |
| secret 被 Base64 解码了 | 直接用 secret 字符串，不 Base64 解码 |
| 时间偏差大（防重放拦截） | 检查 `timestamp` 是否在 5 分钟内 |
| 密钥已 regenerate（旧密钥） | 在 [DSPay 后台](https://mcashier.ds.pro/login/)查看最新密钥 |

### 8.6 ⚠️ 坑点（3 条）

1. **测试顺序**：不要一上来就端到端测试。先单独验签名算法（用在线 HMAC 工具对比），确认签名计算正确后再端到端。跳步会导致"验签失败"无法定位是签名算法错还是传输层错。

2. **ngrok URL 变化**：免费版 URL 每次重启变化，需在 [DSPay 后台](https://mcashier.ds.pro/login/)重新配置。测试中途重启 ngrok 后，必须同步更新 [DSPay](#term-dspay) 的 `notifyUrl`，否则回调发到旧 URL 全部失败。

3. **本地 HTTP 客户端用 HTTP_1_1**：调 `http://localhost:<port>`（非 HTTPS）时 Java HttpClient 必须 `HTTP_1_1`，不能用 `HTTP_2`（明文 h2c 大多数服务不支持 → Connection reset）。HTTPS 才能用 HTTP_2。

---

[↑ 返回目录](#目录)

<a id="第-9-章-faq"></a>
## 第 9 章：FAQ

本章按认证 / 签名 / 订单 / 回调 / 配置五类组织常见问题，便于快速查找。

### 9.1 认证类

**Q: 后台登录会话过期了怎么办？**
A: 后台会话滑动 7 天续期——每次活跃自动续期 7 天。闲置 7 天后 session 过期，需重新在 [DSPay 后台](https://mcashier.ds.pro/login/)使用钱包登录。商户后端对接 [DSPay](#term-dspay) 的接口（创建订单、回调验签）使用 [apiSecret](#term-apisecret) 签名，不依赖后台会话，不受此限制。

**Q: 观察钱包（read-only）能登录吗？**
A: 不能。[SIWE](#term-siwe) 需要私钥签名，观察钱包无法 `personal_sign`。必须用有私钥的钱包登录。

### 9.2 签名类

**Q: 签名一直验签失败（[`50613`](#error-50613)）？**
A: 常见原因是 BigDecimal 科学计数法。`new BigDecimal("1E+2").toString()` 输出 `1E+2`，必须用 `toPlainString()` 输出 `100`。检查 `payAmount` 是否为最多 2 位小数的普通十进制字符串，以及规范化字符串中是否有 `E` 字符。

**Q: Node.js 金额精度怎么处理？**
A: `productPrice` 和 `payAmount` 用字符串字面量 `'99.99'` 或 Big.js，不能用 JS number。JS number 超过 `Number.MAX_SAFE_INTEGER` 或极小 decimal 会进入科学计数法。

**Q: [apiSecret](#term-apisecret) 需要 Base64 解码后使用吗？**
A: **不需要**。[apiSecret](#term-apisecret) 字符串直接作为 HMAC key 使用。Base64Url 只是密钥的存储编码，[HMAC-SHA256](#term-hmac-sha256) 对 key 字节序列没有格式要求。`secret.getBytes(UTF_8)` 直接传给 `SecretKeySpec`。

**Q: 签名字段顺序错了会怎样？**
A: 签名不一致 → [`50613`](#error-50613)。必须严格按 `merchantNo → outOrderNo → payAmount → timestamp` 顺序拼接，用 `&` 连接，**顺序敏感**。`outOrderNo` 必填且非空，必须同时出现在签名和收银台 URL 中。可选商品字段不参与签名；链和代币由用户在收银台选择。

### 9.3 订单类

**Q: 收银台显示的 payAmount 为什么不是链接中传入的金额？**
A: 尾数机制。[DSPay](#term-dspay) 为每个订单附加唯一尾数（如 100.001），用于区分同金额并发订单。用户必须按收银台显示的 `payAmount`（含尾数）付款，否则链上检测不匹配。商户对账以回调或主动查询结果为准。

**Q: 打开收银台提示 [`50609`](#error-50609) NO_ENABLED_ADDRESS？**
A: 链支持但商户没为该 [networkId](#term-networkid)（链）配 ENABLED 收款地址。去 [DSPay 后台](https://mcashier.ds.pro/login/)配置收款地址。

**Q: 收银台提示 [`50707`](#error-50707) CHAIN_NOT_SUPPORTED？**
A: 当前选择的链不在支持范围内。让用户返回收银台重新选择可用链；如仍出现，请联系 DSPay 支持人员。

**Q: 收银台创建订单提示 [`50610`](#error-50610)/[`50611`](#error-50611)/[`50612`](#error-50612)？**
A: 尾数机制并发或精度问题。[`50610`](#error-50610) `ORDER_CREATE_BUSY`（尾数锁冲突，重试即可）/ [`50611`](#error-50611) `SUFFIX_EXHAUSTED`（尾数槽位耗尽，等并发降）/ [`50612`](#error-50612) `SUFFIX_PRECISION_SATURATED`（商户提交的 `payAmount` 超过 2 位小数）。

### 9.4 回调类

**Q: 回调一直验签失败？**
A: 90% 是 payload 被 JSON 反序列化后重新序列化导致字段顺序变化。必须用 HTTP body 原始字符串验签，不能用反序列化后的对象再 `toJson()`。

**Q: 回调一直重试怎么办？**
A: 检查响应是否符合 `{"code":"SUCCESS"}` 严格格式。`code` 必须是大写字符串 `"SUCCESS"`，数字 200 / 小写 / 嵌套都不接受。

**Q: 重试策略是什么？**
A: 共尝试 11 次：3 次立即发送，再按相对上一次尝试 30s / 1min / 5min / 15min / 1h / 6h / 12h / 24h 阶梯补偿。理论累计跨度约 43 小时 21 分 30 秒，实际时间可能更长。11 次均失败后停止自动发送；商户应通过订单查询或对账补处理。

**Q: 旧事件重试期间订单状态升级会怎样？**
A: [DSPay](#term-dspay) 会停止发送旧事件，并另行发送新状态事件。例如 `CLOSED` 重试期间订单被补单为 `COMPLETED`，旧 `CLOSED` 停止发送，随后发送 `COMPLETED`。商户按 `orderNo + eventType` 幂等处理即可。

**Q: reopened=true 是什么场景？**
A: 订单处于 CLOSED 后，商户在后台核实链上到账并执行手动补单。[DSPay](#term-dspay) 将订单重开为 `COMPLETED`，并发送 `reopened=true` 回调。商户可据此区分普通完成与人工补单完成。

**Q: 幂等怎么做？**
A: 基于 `orderNo + eventType`（不是仅 orderNo）。同一订单可能依次收到 `COMPLETED → REFUNDED`，两者语义不同不应互相覆盖。用 DB 唯一键 `(order_no, event_type)` 或 Redis SETNX `notify:{orderNo}:{eventType}`。

**Q: TIMEOUT 会发回调吗？**
A: **不会**。`CREATED` / `TIMEOUT` 只推进订单状态，不发送回调。用户侧状态和倒计时由收银台处理；商户后端需要跟踪时使用订单查询接口。

### 9.5 配置类

**Q: 密钥泄漏怎么办？**
A: 半夜应急 → 在 [DSPay 后台](https://mcashier.ds.pro/login/)冻结密钥（止血）；确认泄漏 → 在后台 regenerate 换新 key。不要仅恢复旧 key。

**Q: 地址白名单和商户 ENABLED 地址什么区别？**
A: 平台白名单（以 `GET /dspay/public/supported-chains` 返回为准）定义"[DSPay](#term-dspay) 支持哪些链"；商户级收款地址定义"本商户在每条链上用哪个地址收款"（在 [DSPay 后台](https://mcashier.ds.pro/login/)配置）。创建订单时两个条件都要满足。

**Q: [`50503`](#error-50503) API_SECRET_DISABLED 怎么处理？**
A: 密钥已被冻结。如果是自己冻结的，排查完成后在后台恢复密钥；如果是他人操作，联系商户管理员。

---

[↑ 返回目录](#目录)

<a id="附录-a-java-综合接入示例"></a>
## 附录 A：Java 综合接入示例

Java 接入只维护一份权威实现：[`Demo/back-end/java`](../Demo/back-end/java/README.zh-CN.md)。该 Demo 同时覆盖本地签名生成收银台链接、回调 raw body 验签和严格 ACK。

| 文件 | 用途 |
|------|------|
| [`README.zh-CN.md`](../Demo/back-end/java/README.zh-CN.md) | 环境要求、配置、启动方式、接口与签名规则 |
| [`src/DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java) | 本地签名、URL 编码、收银台跳转、回调验签与严格响应 |
| [`start.sh`](../Demo/back-end/java/start.sh) / [`stop.sh`](../Demo/back-end/java/stop.sh) | 后台进程启停 |

创建流程为：**商户后端本地签名 → 拼接收银台 URL → 跳转用户**。商户后端不直接调用 DSPay 创建订单 API；用户在收银台选择链和代币后，由收银台完成订单创建。

> 附录不再复制源码。只需维护权威 Demo，此处始终引用最新实现。

---

[↑ 返回目录](#目录)

<a id="附录-b-nodejs-综合接入示例"></a>
## 附录 B：Node.js 综合接入示例

Node.js 接入只维护一份权威实现：[`Demo/back-end/nodejs`](../Demo/back-end/nodejs/README.zh-CN.md)。该 Demo 仅使用 Node.js 内置模块，同时覆盖收银台跳转和回调验签。

| 文件 | 用途 |
|------|------|
| [`README.zh-CN.md`](../Demo/back-end/nodejs/README.zh-CN.md) | 环境要求、配置、启动方式、接口与签名规则 |
| [`src/server.js`](../Demo/back-end/nodejs/src/server.js) | HTTP 路由、收银台 URL 构建、302 跳转与 raw body 回调处理 |
| [`src/signer.js`](../Demo/back-end/nodejs/src/signer.js) | 创建订单签名与常量时间回调验签 |
| [`package.json`](../Demo/back-end/nodejs/package.json) | 启动命令与运行时信息 |

创建流程为：**商户后端本地签名 → 拼接收银台 URL → 跳转用户**。商户后端不直接调用 DSPay 创建订单 API；用户在收银台选择链和代币后，由收银台完成订单创建。

> 附录不再复制源码。只需维护权威 Demo，此处始终引用最新实现。

---

[↑ 返回目录](#目录)

<a id="附录-c错误码完整列表"></a>
## 附录 C：错误码完整列表

来源：`DspayExceptionConstant.java`，按错误码段分组。

#### 通用错误（400xx）

| code | msg | 说明 |
|------|------|------|
| <a id="error-40001"></a>40001 | PARAM_ERROR | 参数校验失败 |
| <a id="error-40101"></a>40101 | UNAUTHORIZED | 未登录 |
| <a id="error-40301"></a>40301 | FORBIDDEN | 无权限 |
| <a id="error-40401"></a>40401 | NOT_FOUND | 资源不存在 |
| <a id="error-40901"></a>40901 | STATE_CONFLICT | 状态冲突 |
| <a id="error-50000"></a>50000 | INTERNAL_ERROR | 服务内部异常 |

#### 商户相关（505xx）

| code | msg | 说明 |
|------|------|------|
| <a id="error-50501"></a>50501 | MERCHANT_NOT_FOUND | 商户不存在 |
| <a id="error-50502"></a>50502 | MERCHANT_DISABLED | 商户已被禁用 |
| <a id="error-50503"></a>50503 | API_SECRET_DISABLED | 商户 API 密钥已冻结（回调停发，创建订单和主动查询等签名请求报错） |

#### 订单相关（506xx）

| code | msg | 说明 |
|------|------|------|
| <a id="error-50601"></a>50601 | ORDER_NOT_FOUND | 订单不存在 |
| <a id="error-50603"></a>50603 | ORDER_ALREADY_PAID | 订单已支付 |
| <a id="error-50604"></a>50604 | ORDER_EXPIRED | 订单已过期 |
| <a id="error-50605"></a>50605 | ORDER_STATUS_NOT_ALLOWED | 订单状态不允许此操作 |
| <a id="error-50606"></a>50606 | TX_HASH_INVALID | 交易哈希无效 |
| <a id="error-50608"></a>50608 | TX_HASH_ALREADY_USED | 交易哈希已被使用（仅 supplement 补单校验；refund 退款不再校验 refundTxHash 防重放） |
| <a id="error-50609"></a>50609 | NO_ENABLED_ADDRESS | 无可用收款地址（商户未为该 [networkId](#term-networkid)（链）配 ENABLED 地址） |
| <a id="error-50610"></a>50610 | ORDER_CREATE_BUSY | 订单创建繁忙（尾数锁冲突，重试即可） |
| <a id="error-50611"></a>50611 | SUFFIX_EXHAUSTED | 尾数槽位已耗尽 |
| <a id="error-50612"></a>50612 | SUFFIX_PRECISION_SATURATED | 尾数精度不足；稳定币场景下通常表示商户提交的 `payAmount` 超过 2 位小数 |
| <a id="error-50613"></a>50613 | ORDER_SIGNATURE_INVALID | 创建订单或主动查询的签名校验失败 |
| <a id="error-50614"></a>50614 | ORDER_TIMESTAMP_EXPIRED | 创建订单或主动查询的时间戳超出 ±5 分钟窗口 |

#### 地址相关（507xx）

| code | msg | 说明 |
|------|------|------|
| <a id="error-50702"></a>50702 | ADDRESS_FORMAT_INVALID | 地址格式无效 |
| <a id="error-50703"></a>50703 | ADDRESS_NOT_FOUND | 地址不存在 |
| <a id="error-50704"></a>50704 | ADDRESS_NOT_IN_WALLET | 地址不属于当前钱包 |
| <a id="error-50705"></a>50705 | ADDRESS_NETWORK_MISMATCH | 地址与网络不匹配 |
| <a id="error-50706"></a>50706 | CHAIN_ADDRESS_ALREADY_BOUND | 该链地址已被绑定 |
| <a id="error-50707"></a>50707 | CHAIN_NOT_SUPPORTED | 链不受支持（[networkId](#term-networkid) 不在 9 链白名单或链已禁用） |

#### JWT 认证相关（508xx）

| code | msg | 说明 |
|------|------|------|
| <a id="error-50801"></a>50801 | JWT_TOKEN_MISSING | 缺少 [JWT](#term-jwt) Token |
| <a id="error-50802"></a>50802 | JWT_TOKEN_INVALID | [JWT](#term-jwt) Token 无效 |
| <a id="error-50803"></a>50803 | JWT_TOKEN_EXPIRED | [JWT](#term-jwt) Token 已过期（含 session 过期） |

#### SIWE 签名认证相关（509xx）

| code | msg | 说明 |
|------|------|------|
| <a id="error-50901"></a>50901 | SIWE_NONCE_NOT_FOUND | [SIWE](#term-siwe) [nonce](#term-nonce) 不存在 |
| <a id="error-50902"></a>50902 | SIWE_NONCE_EXPIRED | [SIWE](#term-siwe) [nonce](#term-nonce) 已过期（TTL 5 分钟） |
| <a id="error-50903"></a>50903 | SIWE_SIGNATURE_INVALID | [SIWE](#term-siwe) 签名无效（ecrecover 恢复地址不匹配） |
| <a id="error-50904"></a>50904 | SIWE_DOMAIN_MISMATCH | [SIWE](#term-siwe) domain 不匹配 |
| <a id="error-50905"></a>50905 | SIWE_MESSAGE_INVALID | [SIWE](#term-siwe) 消息无效 |

[↑ 返回目录](#目录)
