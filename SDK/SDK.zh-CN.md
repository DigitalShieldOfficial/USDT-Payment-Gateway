## 语言
[English](https://github.com/DigitalShieldOfficial/DigitalShieldPay/blob/main/SDK.en-US.md) | [中文](https://github.com/DigitalShieldOfficial/DigitalShieldPay/blob/main/SDK.zh-CN.md)

# DSPay 商户接入指南

> 本文档为 [DSPay](#term-dspay) 商户接入方的技术对接指南，覆盖注册认证、收款配置、订单创建、回调处理、对账运维、异常流程 SOP 全流程。每章按技术主题组织，章末附核心注意事项与常见坑点清单。

---

## 名词说明

> 本文档涉及的技术缩写与专有名词速查。初次接入时建议先过一遍，避免概念混淆。

| 术语 | 说明 |
|------|------|
| <a id="term-dspay"></a>**DSPay** | 多链稳定币收款网关（本服务） |
| <a id="term-siwe"></a>**SIWE** | Sign-In with Ethereum，基于 EIP-4361 的钱包登录标准；商户用 EVM 钱包对约定消息签名完成登录认证 |
| <a id="term-jwt"></a>**JWT** | JSON Web Token，DSPay 后台登录会话凭证（7 天滑动过期）；商户后端对接 API 不依赖 JWT，使用 `apiSecret` 签名（详见 [§2.2](#会话有效期)） |
| <a id="term-apisecret"></a>**apiSecret** | 商户 API 密钥，用于创建订单签名 + 回调验签，在 DSPay 后台获取，妥善保管 |
| <a id="term-merchantno"></a>**merchantNo** | 商户业务编号（`DSM` 前缀，如 `DSM1`），创建订单时必传；对外暴露的业务编码，**非 DB 自增主键**（避免业务量泄露 + 防枚举） |
| <a id="term-networkid"></a>**networkId** | 链的唯一标识（如 `evm--1` = Ethereum 主网），完整列表见 [§3.2](#networkid-速查表) |
| <a id="term-contractaddress"></a>**contractAddress** | 代币合约地址，创建订单时与 `networkId` 一起定位代币 |
| <a id="term-hmac-sha256"></a>**HMAC-SHA256** | 哈希消息认证码算法；DSPay 用 `apiSecret` 作 key 对规范化字符串计算，输出 hex 小写 |
| <a id="term-evm"></a>**EVM** | Ethereum Virtual Machine，以太坊虚拟机；EVM 系链指兼容以太坊智能合约的链（Ethereum / BSC / Polygon / Arbitrum / Base） |
| <a id="term-usdt"></a>**USDT** / <a id="term-usdc"></a>**USDC** | 与美元锚定的稳定币（Tether USD / Centre USD Coin） |
| **尾数（<a id="term-amountsuffix"></a>amountSuffix）** | DSPay 为区分同金额并发订单附加的小额尾数（如 100.001 中的 0.001），详见 [§4.1](#订单尾数机制详解) |
| **补单（<a id="term-supplement"></a>supplement）** | CLOSED 订单后续链上到账时，由商户在后台确认到账、订单重开为 COMPLETED 的操作 |
| **回调（<a id="term-webhook"></a>webhook）** | DSPay 向商户 `notifyUrl` 发送的 HTTP POST 通知，事件类型：`COMPLETED` / `CLOSED` / `REFUNDED` |
| <a id="term-notifyurl"></a>**notifyUrl** | 商户接收 DSPay 回调通知的 URL（必须公网可达） |
| <a id="term-ntp"></a>**NTP** | Network Time Protocol，网络时间协议；用于服务器时钟同步 |
| <a id="term-nonce"></a>**nonce** | 一次性随机数；SIWE 登录流程中用于防重放攻击 |

> 文档其他位置出现上述术语时，可点击跳转回此章节查看定义。

---

## 快速开始（5 分钟跑通首笔订单）

> 目标：用最少的步骤创建一笔订单，看到完整的 API 响应。如果你更喜欢先理解原理，可以直接跳到[第 1 章](#第-1-章开始之前)开始阅读。

**前置准备清单**：

| 条件 | 获取方式 | 用时 |
|------|---------|------|
| [DSPay](#term-dspay) 商户账号 | 在 [DSPay](#term-dspay) 后台使用 [EVM](#term-evm) 钱包登录（参考 [§2.1](#商户注册与登录)） | 30 秒 |
| 收款地址（任一链） | 在 [DSPay](#term-dspay) 后台配置（参考 [§3.3](#配置收款地址)） | 1 分钟 |
| [apiSecret](#term-apisecret) | 在 [DSPay](#term-dspay) 后台启用回调后获取（参考 [§3.5](#配置回调-url-启用回调)） | — |
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
const API_SECRET = '你的-apiSecret';  // 在 DSPay 后台获取

// ===== [2] 签名字段（参与 HMAC 计算） =====
const signed = {
  payAmount: '10.00',
  outOrderNo: '',   // 可选，传空串
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
const opt = (v) => (v == null || String(v).trim() === '') ? '' : String(v).trim();
const canonical = `merchantNo=${MERCHANT_NO}&outOrderNo=${opt(signed.outOrderNo)}&payAmount=${signed.payAmount}&timestamp=${timestamp}`;

// ② HMAC-SHA256（secret 直接 getBytes，不 Base64 解码）
const signature = crypto.createHmac('sha256', API_SECRET)
  .update(canonical, 'utf8').digest('hex');

// ③ 拼接收银台 URL
const url = new URL('https://cashier.ds.pro/');
url.searchParams.set('merchantNo', MERCHANT_NO);
url.searchParams.set('outOrderNo', opt(signed.outOrderNo));
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
https://cashier.ds.pro/?merchantNo=DSM1&outOrderNo=&payAmount=10.00&timestamp=1717689600000&signature=7de3fafc...

链接有效期 5 分钟，请在浏览器中打开。过期重新运行脚本即可。
```

在浏览器打开收银台链接，能看到支付页面，用户自行选择链/代币后扫码或转账即可完成支付。

### 下一步

1. [接收回调](#第-5-章接收回调) —— 启动本地服务接收 [DSPay](#term-dspay) 的异步支付通知
2. [订单状态机](#订单状态机前置必读) —— 了解订单从 CREATED → TIMEOUT → CLOSED / COMPLETED 的全过程
3. [测试与联调](#第-8-章测试与联调) —— ngrok 本地调试 + 端到端验证 + 幂等测试

---

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

- **[EVM](#term-evm) 钱包**：用于 [SIWE](#term-siwe) 登录（MetaMask / Rabby / Trust 等均可，只需能 `personal_sign`）
- **公网可达的回调 URL**：用于接收 [DSPay](#term-dspay) 异步通知（本地开发可用 ngrok / cpolar）
- **[NTP](#term-ntp) 时钟同步**：签名验证有 ±5 分钟时间窗口，服务器时钟必须准确
- **JDK 11+ 或 Node.js 18+**：运行代码示例（Python / Go / 其他语言可按规范自行实现）
- **测试用稳定币**：各链主网小额稳定币（如 0.01 [USDT](#term-usdt)）用于端到端联调

### 1.5 核心接口速查表

**商户需要对接的 API**：

| API | 方法 | 用途 |
|---|---|---|
| 查询支持链/代币 | GET /dspay/public/supported-chains | 获取可用的链和代币白名单 |
| 创建订单 | POST /dspay/order/create | 创建支付订单（需 HMAC 签名） |
| 接收回调 | 商户实现 webhook | 接收 [DSPay](#term-dspay) 的支付/关闭/退款通知 |

> 其他操作（注册登录、配置收款地址、配置回调 URL、订单查询统计、退款、补单、密钥管理）均在 **[DSPay](#term-dspay) 后台 UI** 完成，无需调 API。
> 用户收银台前端的订单状态轮询由 [DSPay](#term-dspay) 团队实现，商户无需自行开发。

---

## 第 2 章：注册与认证

本章说明商户注册与认证机制，包括后台登录方式、会话有效期与 `apiSecret` 的获取。

### 2.1 商户注册与登录

商户在 [DSPay](#term-dspay) 后台使用 [EVM](#term-evm) 钱包登录（[SIWE](#term-siwe) 签名认证）。**首次登录自动创建商户账号**，无需单独注册。

登录后可获取：
- **[merchantNo](#term-merchantno)**：商户业务编号（`DSM` 前缀，如 `DSM1`），创建订单时必传
- **[apiSecret](#term-apisecret)**：API 密钥，用于创建订单签名和回调验签（后台页面展示，妥善保管）

### 2.2 会话有效期

后台登录会话有效期 7 天（滑动过期：每次活跃自动续期 7 天）。闲置 7 天后需重新登录。

> 商户后端对接 [DSPay](#term-dspay) 的接口（创建订单、回调验签）使用 [apiSecret](#term-apisecret) 签名，不依赖 [JWT](#term-jwt) 会话，不受此限制。

### 2.3 ⚠️ 坑点（2 条）

1. **首次登录需完成配置**：首次登录后还没有 [apiSecret](#term-apisecret) / 收款地址 / 回调 URL，需在后台完成配置后才能正常收款。否则创建订单会报 `50609 NO_ENABLED_ADDRESS`。

2. **[apiSecret](#term-apisecret) 妥善保管**：[apiSecret](#term-apisecret) 用于签名和验签，泄漏会导致伪造订单/回调。建议定期在后台轮换（参考 [§6.4 密钥轮换](#密钥定期轮换)）。

---

## 第 3 章：配置收款

本章描述收款配置全流程，包括链与代币**白名单查询**、**收款地址配置**、回调 URL 设置与双开关组合矩阵。

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

### 3.3 配置收款地址

商户只需为**要收款的链**配置 `ENABLED` 状态的收款地址；不收款的链无需配置。创建订单时若该 [networkId](#term-networkid) 下无 `ENABLED` 地址，会报 `50609 NO_ENABLED_ADDRESS`。

> 收款地址按**链级别**配置（不区分 token）：一个地址接收该链上所有 [DSPay](#term-dspay) 支持的稳定币（如 [USDT](#term-usdt) / [USDC](#term-usdc)）。

在 [DSPay](#term-dspay) 后台为每条要收款的链配置收款地址。每个地址需指定：
- **[networkId](#term-networkid)**（链）
- **收款地址**
- **启用状态**（ENABLED / DISABLED）

> 创建订单时，[DSPay](#term-dspay) 会从该 [networkId](#term-networkid) 下 ENABLED 的地址中选一个作为收款地址。若没有 ENABLED 地址，创建订单报 `50609`。

> 地址管理（增删改查、启停）均在后台 UI 完成。

### 3.4 后台关键配置概览

[DSPay](#term-dspay) 后台提供两类关键配置，商户需理解其用途与默认状态：

| 配置 | 位置 | 默认 | 用途与影响 |
|------|------|------|-----------|
| 回调设置 | 后台 → 回调配置 | **关闭** | 配置回调 URL + 开关；**必须手动启用**才能收到支付通知（创建订单、用户付款都成功，但回调没开 = 商户后端收不到通知） |
| 密钥管理 | 后台 → 安全设置 | 正常 | 查看 [apiSecret](#term-apisecret) / 紧急冻结密钥（`apiSecretEnabled=false`，冻结后回调停发 + 创建订单报 `50503`）/ regenerate 换新 key |
| 订单签名开关 (`orderSignatureEnabled`) | 后台 → 安全设置 | true（开） | 关闭后该商户创建订单请求跳过 `timestamp + signature` 校验，降低接入门槛但减弱安全性。仅建议测试环境关闭 |

**关键提醒**：
- **回调开关默认关闭**：新商户最容易遗漏这一步，必须在后台手动启用
- **[apiSecret](#term-apisecret) 首次获取**：首次启用回调时自动生成，在后台页面查看
- **创建订单始终依赖密钥**：[HMAC-SHA256](#term-hmac-sha256) 签名校验，密钥冻结后无法下单

### 3.5 配置回调 URL + 启用回调

在 [DSPay](#term-dspay) 后台配置回调 URL（商户接收支付通知的 webhook 地址）并启用回调。

关键配置项：
- **回调 URL**：商户的回调接收地址（必须公网可达）
- **回调开关**：默认**关闭**，必须在后台**手动启用**

> 首次启用回调时，若商户无 [apiSecret](#term-apisecret) 会自动生成。[apiSecret](#term-apisecret) 在后台页面查看。

> 回调 URL 变更后需在后台同步更新。

### 3.6 配置联系链接（可选）

在 [DSPay](#term-dspay) 后台可配置一个联系链接（客服 URL / Telegram / 邮件）。[DSPay](#term-dspay) 收银台前端会查询并展示给用户，用户支付遇到问题时可据此联系商户。

> 这是可选配置，但对用户体验很重要。

### 3.7 ⚠️ 坑点（4 条）

1. **50707 vs 50609 语义差异**：
   - `50707 CHAIN_NOT_SUPPORTED` = [networkId](#term-networkid) 不在 9 链白名单（平台级不支持）
   - `50609 NO_ENABLED_ADDRESS` = 链支持但商户没配 ENABLED 收款地址（商户级未配置）
   排查方向完全不同：50707 检查 [networkId](#term-networkid) 拼写，50609 去商户后台配地址。

2. **地址白名单 vs 商户 ENABLED 地址**：
   - 平台白名单（以 `GET /dspay/public/supported-chains` 返回为准）：定义"[DSPay](#term-dspay) 支持哪些链"
   - 商户级收款地址（在 [DSPay](#term-dspay) 后台配置）：定义"本商户在每条链上用哪个地址收款"
   创建订单时**两个条件都要满足**：[networkId](#term-networkid) 在白名单 + 商户有为该 [networkId](#term-networkid)（链）配 ENABLED 地址。

3. **回调开关默认关闭**：必须在 [DSPay](#term-dspay) 后台手动启用回调，否则 [DSPay](#term-dspay) 永远不发回调。新商户登录后最容易遗漏这一步——创建订单成功、用户付款成功、但商户后端永远收不到通知。

4. **首次启用回调自动生成 [apiSecret](#term-apisecret)**：首次在后台启用回调且无旧密钥时自动生成 [apiSecret](#term-apisecret)；关闭再开启时旧密钥保留不重新生成。**商户必须在后台拿到这个 [apiSecret](#term-apisecret) 才能做创建订单签名和回调验签**。

---

## 第 4 章：创建第一笔订单

本章介绍订单创建的完整流程，包括**查询链代币白名单**、**订单尾数机制**、**[HMAC-SHA256](#term-hmac-sha256) 签名算法**、`BigDecimal` 精度处理，以及 Java/Node.js 端到端示例。

> **签名校验开关**：商户级 `orderSignatureEnabled`（默认 true）控制是否校验 `timestamp + signature`。关闭后该商户创建订单请求可省略这两字段，降低接入门槛但减弱安全性（仅建议测试环境关闭）。另有全局总闸 `signatureCheckGlobalEnabled`（运维侧控制，默认 true）。

### 4.1 订单尾数机制详解

**为什么响应的 `payAmount` 是 100.001 而非商户传入的 100？**

[DSPay](#term-dspay) 用**尾数机制**区分同金额的并发订单。例如多个商品都是 100 [USDT](#term-usdt) 的订单，[DSPay](#term-dspay) 会为每个订单附加一个唯一尾数（如 100.001 / 100.002 / 100.003），用户付款时金额精确到尾数，[DSPay](#term-dspay) 据此自动匹配订单。


**关键点**：
- 商户传 `payAmount` 参数和响应的 `payAmount` **不同**：请求参数是商户期望金额，响应是 [DSPay](#term-dspay) 实际生成的含尾数金额
- 用户必须按响应的 `payAmount`（含尾数）付款，否则链上检测不匹配

### 4.2 查询支持的链与代币

创建订单前，先调用以下公开接口获取 [DSPay](#term-dspay) 平台当前支持的链和代币白名单：

```
GET /dspay/public/supported-chains
```

**请求**：无请求参数（公开接口，无需 [JWT](#term-jwt)、无需 [merchantNo](#term-merchantno)）。

**响应**：JSON 数组，每个元素包含一条链及其支持的稳定币。

**curl 示例**：

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

**与创建订单的关系**：响应的 `networkId` 和 `tokens[].address` 直接对应创建订单的 `networkId` 和 `contractAddress` 参数。

| 响应字段 | 创建订单参数 | 说明 |
|---------|-------------|------|
| `networkId` | `networkId` | 链标识，直接填入创建订单请求 |
| `tokens[].address` | `contractAddress` | 代币合约地址，直接填入创建订单请求 |
| `tokens[].symbol` | — | 代币符号（如 `USDT`），用于展示确认 |

完整字段说明见 [§3.1](#查询支持链代币白名单)，networkId 速查表见 [§3.2](#networkid-速查表)。

### 4.3 创建订单接口

创建订单接口 `POST /dspay/order/create` 是**公开接口**（无需 [JWT](#term-jwt)），为防止第三方冒用商户身份创建订单，**所有创建订单请求必须携带 [HMAC-SHA256](#term-hmac-sha256) 签名**。

**前置条件**：
- 商户已获取 `apiSecret`（在 [DSPay](#term-dspay) 后台查看）
- 目标 [networkId](#term-networkid)（链）已配置 ENABLED 收款地址
- 服务器时钟已 [NTP](#term-ntp) 同步（±5 分钟窗口）

#### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|------|------|------|------|------|
| `merchantNo` | string | ✓ | 非空 | 商户编号 |
| `productPrice` | decimal | ✗ | 可选 | 商品价格（可选，仅记录不参与计算；未传时为 null） |
| `productPriceCurrency` | string | ✗ | 可选 | 价格币种（可选，任意法币如 USD/CNY/EUR，仅记录不参与计算；未传时为 null） |
| `networkId` | string | ✓ | 9 链白名单 | 链 [networkId](#term-networkid)，如 `evm--1`（参考 [§3.1](#查询支持链代币白名单)） |
| `contractAddress` | string | ✓ | 代币白名单 | 代币合约地址（参考 [§3.1](#查询支持链代币白名单)） |
| `payAmount` | decimal | ✓ | ≥0.0000000001 | 应付代币金额（**不含尾数**，[DSPay](#term-dspay) 自动追加） |
| `outOrderNo` | string | ✗ | ≤64 字符 | 商户原始订单号（**参与签名**，未传时签名串 value 为空；回调原样回传） |
| `productId` | string | ✗ | ≤64 字符 | 商户产品 ID（不参与签名，仅记录；回调原样回传） |
| `timestamp` | long | **条件必填** | ±5min 窗口 | 毫秒时间戳；`orderSignatureEnabled=true` 时必填（参考 [§4.6](#时间戳窗口)） |
| `signature` | string | **条件必填** | ≤128 字符 | [HMAC-SHA256](#term-hmac-sha256) hex 小写；`orderSignatureEnabled=true` 时必填（参考 [§4.4](#签名规范化字符串)） |

#### 响应体

| 字段 | 类型 | 说明 |
|------|------|------|
| `orderNo` | string | [DSPay](#term-dspay) 订单号（如 `DS00000120260702000001`） |
| `outOrderNo` | string\|null | 商户原始订单号（原样回传，未传时为 null） |
| `productId` | string\|null | 商户产品 ID（原样回传，未传时为 null） |
| `productPrice` | decimal\|null | 商品价格（可选，未传时为 null） |
| `productPriceCurrency` | string\|null | 价格币种（可选，未传时为 null） |
| `networkId` | string | 链 [networkId](#term-networkid) |
| `contractAddress` | string | 代币合约地址 |
| `tokenSymbol` | string | 代币符号（如 `USDT`） |
| `payAmount` | decimal | **实际应付金额（含尾数）** — 用户必须按此金额付款 |
| `originPayAmount` | decimal | 商品原价（不含尾数） |
| `amountSuffix` | decimal | 订单识别尾数（0=无尾数） |
| `usdAmount` | decimal | USD 金额快照（订单创建时锁定，统计用） |
| `exchangeRate` | decimal\|null | 商户传入汇率快照（1 代币 = X 法币），`productPrice` 未传时为 null |
| `receivingAddress` | string | 收款地址（用户向此地址付款） |
| `qrCodeUrl` | string\|null | 二维码内容（当前版本不返回，商户可基于 `orderNo` 自行构造收银台 URL `${payPageBaseUrl}?orderNo=xxx`） |
| `expireAt` | long | 过期时间（Unix 毫秒时间戳） |
| `createAt` | long | 创建时间（Unix 毫秒时间戳） |
| `status` | string | 订单状态（创建后为 `CREATED`） |

> 💡 用户付款必须按响应的 `payAmount`（含尾数），不能用请求参数中的 `payAmount`。详见 [§4.1](#订单尾数机制详解)。

### 4.4 签名规范化字符串

将请求参数按**固定顺序**用 `key=value&` 风格拼接（value 不做 URL encode），最小签名集为 4 段：

```
merchantNo={merchantNo}&outOrderNo={outOrderNo}&payAmount={payAmount}&timestamp={timestamp}
```

**示例**：
```
merchantNo=DSM1&outOrderNo=MY-ORDER-001&payAmount=99.99&timestamp=1717689600000
```

> ⚠️ **顺序敏感**
>
> HMAC-SHA256 是对**字节序列**求哈希，字段顺序错乱 → 字节序列不同 → 哈希不同 → 验签失败（`50613`）。**客户端必须严格按上述顺序拼接**，服务端 `DspayOrderSignatureSupport.buildPayload` 用 `String.join("&", ...)` 硬编码了此顺序。
>
> | 位置 | 字段 | 空值处理 |
> |---|---|---|
> | 1 | `merchantNo` | 非空，直接拼 |
> | 2 | `outOrderNo` | null/blank → 空串（key 保留：`outOrderNo=`） |
> | 3 | `payAmount` | `BigDecimal.toPlainString()`，禁用科学计数法 |
> | 4 | `timestamp` | 毫秒 long，直接拼 |
>
> HTTP body 的 JSON 字段顺序不影响签名（签名用的是规范化字符串，不是 JSON）。

> **重要**：
> - `outOrderNo` 是可选字段但**参与签名**。未传或纯空白时 value 为空字符串（key 保留），例：`outOrderNo=`。
> - `merchantNo` / `outOrderNo` / `payAmount` / `timestamp` 四字段构成最小签名集（防冒充、防商户订单号被替换、防篡改链上金额、防重放）。
> - **已移除**：`productPrice` / `productPriceCurrency` / `productId`（法币侧/商户内部字段，不影响链上资金安全，移出签名集降低商户接入复杂度）。
> - **已移除**：`networkId` / `contractAddress`（让前端用户能自由切换链/代币，无需重新签名）。
> - **安全提示**：移除 `productPrice` / `productPriceCurrency` / `productId` 后，这三个字段可被中间人篡改。由于 `payAmount` 仍签名（链上资金安全有保障），篡改仅影响商户的法币统计/对账，不影响链上转账。商户如对法币侧数据完整性有要求，建议在回调验签后额外比对这三个字段是否与创建时一致。

### 4.5 签名算法

[HMAC-SHA256](#term-hmac-sha256)，输出 **hex 小写**（与回调签名一致）。

> **重要**：`apiSecret` 字符串**直接作为 HMAC key 使用**，`secret.getBytes(UTF_8)`，**不要先 Base64 解码**。Base64Url 只是密钥的存储编码，[HMAC-SHA256](#term-hmac-sha256) 对 key 字节序列没有格式要求。

### 4.6 时间戳窗口

`timestamp`（毫秒）必须在 [DSPay](#term-dspay) 服务端当前时间 **±5 分钟**内，否则返回 `50614 ORDER_TIMESTAMP_EXPIRED`。建议商户服务器开启 [NTP](#term-ntp) 时钟同步。

### 4.7 BigDecimal 序列化

签名字段 `payAmount` 必须用**纯数字字符串**（无科学计数法）：

| 语言 | 方法 |
|------|------|
| Java | `BigDecimal.toPlainString()` |
| Node.js | `toFixed(decimalPlaces)` 或 `Big.js` 库 |
| Python | `format(value, 'f')` |

> 否则 `1e-10` 与 `0.0000000001` 不匹配会导致验签失败（`50613`）。

### 4.8 Java 端到端 demo

> **环境要求**：JDK 11+（最低 11，使用 `java.net.http.HttpClient`），纯 JDK 零第三方依赖。
>
> **编译运行**：
> ```bash
> # 无需任何依赖，直接编译运行
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
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * DSPay 创建订单端到端 demo。
 * 纯 JDK 11+，零第三方依赖。JSON 序列化手写避免引入 Jackson。
 */
public class DspayCreateOrderDemo {

    private static final String DSPAY_BASE_URL = "https://dspay.example.com";
    private static final String CASHIER_BASE_URL = "https://cashier.ds.pro";
    private static final String API_SECRET = "你的-apiSecret"; // 在 DSPay 后台获取

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // 明文 HTTP 必须 1.1（非 h2c）
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        // 1. 业务参数（金额用 BigDecimal 保证确定性序列化）
        String merchantNo = "DSM1";
        BigDecimal productPrice = new BigDecimal("99.99");        // 订单字段（不参与签名）
        String currency = "USD";                                  // 订单字段（不参与签名）
        String networkId = "evm--1";                              // 订单字段（不参与签名）
        String contractAddress = "0xdac17f958d2ee523a2206206994597c13d831ec7"; // 同上
        BigDecimal payAmount = new BigDecimal("100");             // 签名字段
        String outOrderNo = "MY-ORDER-001";                       // 签名字段（可选，未传时 value 为空）
        String productId = "PROD-001";                            // 订单字段（不参与签名）

        // 2. 生成 timestamp + 签名（仅 4 个签名字段参与，固定顺序）
        long timestamp = System.currentTimeMillis();
        String signature = signOrder(merchantNo, outOrderNo,
                payAmount, timestamp, API_SECRET);

        // 3. 手写 JSON 请求体（纯 JDK，零第三方依赖）
        //    注意：金额字段用 toPlainString() 避免科学计数法
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

        // 4. 发送 HTTP 请求
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DSPAY_BASE_URL + "/dspay/order/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP " + resp.statusCode());
        String body = resp.body();
        System.out.println(body);

        // 5. 提取 orderNo，拼接收银台链接
        String orderNo = extractField(body, "orderNo");
        if (orderNo != null) {
            String cashierUrl = CASHIER_BASE_URL + "?orderNo=" + orderNo;
            System.out.println("\n收银台链接: " + cashierUrl);
            System.out.println("用户打开此链接即可在收银台页面付款。");
        }
    }

    /**
     * 规范化字符串 → HMAC-SHA256 → hex 小写。
     * 固定顺序：merchantNo → outOrderNo → payAmount → timestamp（顺序敏感），4 段最小签名集。
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

    /** JSON 字符串转义（反斜杠和双引号）。 */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** null 或纯空白 → ""，否则 trim（与服务端 normalizeOptional 对齐）。 */
    static String normalizeOpt(String s) {
        return (s == null || s.trim().isEmpty()) ? "" : s.trim();
    }

    /** 从 JSON 中提取指定字段的字符串值（简单正则解析，仅用于 demo）。 */
    static String extractField(String json, String key) {
        java.util.regex.Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
```

### 4.9 Node.js 端到端 demo

> **环境要求**：Node.js 18+（使用内置 `fetch`），零第三方依赖。
>
> **运行**：
> ```bash
> # 无需 npm install，直接运行
> node create-order.js
> ```

```javascript
const crypto = require('crypto');

const DSPAY_BASE_URL = 'https://dspay.example.com';
const CASHIER_BASE_URL = 'https://cashier.ds.pro';
const API_SECRET = '你的-apiSecret'; // 在 DSPay 后台获取

/**
 * 创建订单端到端 demo（Node 18+ 内置 fetch）。
 * 金额字段必须用字符串，避免 number 精度丢失 / 科学计数法。
 */
async function createOrder() {
    // 1. 业务参数（金额用字符串）
    //    productPrice/productPriceCurrency/productId 是订单字段（不参与签名）
    //    networkId/contractAddress 是订单字段（不参与签名）
    //    签名字段仅：merchantNo / outOrderNo / payAmount（+ timestamp）
    const params = {
        merchantNo: 'DSM1',
        productPrice: '99.99',
        productPriceCurrency: 'USD',
        networkId: 'evm--1',
        contractAddress: '0xdac17f958d2ee523a2206206994597c13d831ec7',
        payAmount: '100',
        outOrderNo: 'MY-ORDER-001',   // 可选，但参与签名
        productId: 'PROD-001',         // 订单字段，不参与签名
    };

    // 2. 生成 timestamp + 签名
    //    固定顺序：merchantNo → outOrderNo → payAmount → timestamp（顺序敏感）
    const timestamp = Date.now();
    const signature = signOrder(params, timestamp, API_SECRET);

    // 3. 发送 HTTP 请求
    const resp = await fetch(`${DSPAY_BASE_URL}/dspay/order/create`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...params, timestamp, signature }),
    });

    const body = await resp.json();
    console.log('HTTP', resp.status);
    console.log(JSON.stringify(body, null, 2));

    // 4. 提取 orderNo，拼接收银台链接
    const orderNo = body?.data?.orderNo;
    if (orderNo) {
        const cashierUrl = `${CASHIER_BASE_URL}?orderNo=${orderNo}`;
        console.log('\n收银台链接:', cashierUrl);
        console.log('用户打开此链接即可在收银台页面付款。');
    }
}

function signOrder(p, timestamp, secret) {
    // opt 与服务端 isBlank 对齐：null 或纯空白 → ""，否则 trim
    const opt = (v) => (v == null || String(v).trim() === '') ? '' : String(v).trim();
    // 固定顺序：merchantNo → outOrderNo → payAmount → timestamp（顺序敏感！）
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

### 4.10 验签失败排查表

| 错误码 | 原因 | 排查方向 |
|--------|------|----------|
| `50613 ORDER_SIGNATURE_INVALID` | 商户未配置 [apiSecret](#term-apisecret) | 先在 [DSPay](#term-dspay) 后台启用回调（自动生成 [apiSecret](#term-apisecret)）或在后台执行 regenerate |
| `50613 ORDER_SIGNATURE_INVALID` | 签名计算错误 | 检查规范化字符串字段顺序、BigDecimal 序列化、[apiSecret](#term-apisecret) 正确性 |
| `50614 ORDER_TIMESTAMP_EXPIRED` | 时间戳超出 ±5 分钟 | 检查服务器时间是否同步（[NTP](#term-ntp)） |

### 4.11 ⚠️ 坑点（5 条）

1. **BigDecimal 必须 `toPlainString()`**：`new BigDecimal("0.0000000001").toString()` 可能输出 `1E-10`，`toPlainString()` 输出 `0.0000000001`。规范化字符串不一致 → `50613`。`payAmount` 参与签名，Java 代码中 `payAmount.toPlainString()` 是必须的，不能用 `toString()`。

2. **Node.js 金额字段必须用字符串**：`payAmount` 用字符串字面量 `'99.99'` 或 Big.js，不能用 JS number。JS number 超过 `Number.MAX_SAFE_INTEGER` 或极小 decimal 会进入科学计数法，导致规范化字符串不一致。

3. **timestamp ±5 分钟窗口**：服务器必须开 [NTP](#term-ntp) 同步。超窗口 → `50614`。Docker 容器尤其注意时钟是否与宿主机同步。

4. **订单尾数机制**：响应的 `payAmount` 含尾数（如 100.001），**不是**商户传入的 100。`suffixScale = originScale + maxOrderDigits`，精度饱和时（`suffixScale > Math.min(tokenDecimals, 18)`）抛 `50612`。商户前端展示"应付金额"必须用响应的 `payAmount`，不能用请求参数。

5. **签名字段集 + 顺序敏感**：签名规范化字符串必须严格按 `merchantNo → outOrderNo → payAmount → timestamp` 顺序拼接。HMAC-SHA256 对字节序列求哈希，**字段顺序错乱 → 验签失败（`50613`）**。`outOrderNo` 可选但**参与签名**（未传或纯空白时 value 为空字符串，key 保留，例：`&outOrderNo=`）。其他字段（`productPrice` / `productPriceCurrency` / `productId` / `networkId` / `contractAddress`）**不参与签名**，但仍作为订单字段提交并原样回传。商户可用 `outOrderNo` 关联自己的业务订单号，[DSPay](#term-dspay) 不校验唯一性（商户自己保证）。

> **安全提示**：`productPrice` / `productPriceCurrency` / `productId` 移出签名集后可被中间人篡改，但 `payAmount` 仍签名（链上资金安全有保障），篡改仅影响商户法币统计/对账。如需法币侧完整性，建议在回调验签后额外比对这三个字段。

---

## 第 5 章：接收回调

本章说明回调处理机制，包括**订单状态机**、**回调验签四步法**、防重放、幂等设计、严格响应规范与重试策略。

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
| `CREATED` | 待支付（10min 倒计时） | — | ✅ 扫描 | ✅ |
| `TIMEOUT` | 10min 未支付（仍可继续等） | ❌ **不发** | ✅ 扫描 | ✅ |
| `CLOSED` | 40min 未支付，系统关闭 | ✅ 发 `CLOSED` | ❌ **停止** | ✅（重开，`reopened=true`） |
| `COMPLETED` | 链上到账 / 补单完成 | ✅ 发 `COMPLETED` | — | — |
| `REFUNDED` | 商户退款成功 | ✅ 发 `REFUNDED` | — | — |

> 订单创建响应包含 `expireAt = createAt + 10min` 字段，前端可用此字段做倒计时展示，而非盲等 TIMEOUT 状态变更。

**三个关键行为（容易被忽略）**：

1. **TIMEOUT 不发回调**：10 分钟超时只是中间过渡态，订单仍可继续等待付款。[DSPay](#term-dspay) 收银台前端会自动轮询订单状态并展示"超时"，商户无需主动查询。
2. **CLOSED 后链上自动检测停止**：[DSPay](#term-dspay) 自动检测任务 仅扫描 `CREATED` / `TIMEOUT` 状态订单。订单一旦进入 `CLOSED`，即使后续链上真的到账，[DSPay](#term-dspay) 也不会自动确认——**必须在 [DSPay](#term-dspay) 后台操作补单**（`reopened=true` 路径）。
3. **补单不强制金额匹配**：自动检测要求链上金额与 `payAmount` 精确匹配（`compareTo == 0`）；手动补单则不校验金额，仅记录差额（`amountDiff = 实际 - 应付`），由商户自行判断。

> 💡 **为什么 CLOSED 后停止自动检测？**
> CLOSED 是"40 分钟彻底放弃"的终态，订单已经回调过 `CLOSED` 事件给商户（商户可能已经取消订单、释放了库存）。如果 CLOSED 后还自动检测，会反复触发 `CLOSED → COMPLETED` 重开，造成对账混乱。所以设计为"CLOSED 后只能人工补单"，由商户判断是否要复活该订单。

### 5.2 何时会收到回调

当订单状态变更为 **`COMPLETED` / `CLOSED` / `REFUNDED`** 三个状态时，[DSPay](#term-dspay) 会向商户配置的 `notifyUrl` 发送 HTTP POST 通知。

> ⚠️ **`CREATED → TIMEOUT` 状态推进不发回调**。TIMEOUT 是中间过渡态，订单仍可继续等待付款与链上检测。

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

**字段说明**：

| 字段 | 类型 | 必返 | 说明 |
|------|------|------|------|
| `orderNo` | string | 是 | 订单号，如 `DS2024...` |
| `outOrderNo` | string\|null | 是 | 商户原始订单号（创建订单时传入，原样回传）；未传时为 null |
| `eventType` | string | 是 | 事件类型：`COMPLETED` / `CLOSED` / `REFUNDED` |
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
| `networkId` | string | 是 | 网络 ID，如 `evm--1` |
| `reopened` | boolean | 是 | 是否 CLOSED 后重开路径（到账晚于超时关闭） |
| `timestamp` | long | 是 | [DSPay](#term-dspay) 发送时间戳（毫秒） |

> **金额字段说明（5.1.5）**：
> - `originPayAmount`：商品原价，与商户创建订单时传入的一致
> - `amountSuffix`：为区分同金额并发订单附加的尾数
> - `payAmount` = `originPayAmount` + `amountSuffix`，与链上实际转账金额一致
> - **金额精确匹配校验建议切到 `originPayAmount`**（与商品原价一致），或用 `payAmount`（含尾数，与链上金额一致）

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

### 5.8 幂等处理

[DSPay](#term-dspay) 的重试机制（见 [5.9](#响应规范严格模式)）可能导致同一事件被发送多次，商户**必须**基于 `orderNo + eventType` 做幂等处理。

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
> 因为同一订单可能依次收到 `COMPLETED` → `REFUNDED`，两者语义不同，不应互相覆盖。

### 5.9 响应规范（严格模式）

**成功响应**：

```
HTTP 200
Content-Type: application/json

{"code":"SUCCESS","msg":"ok"}
```

**严格匹配规则**：

- `code` 必须是字符串 `"SUCCESS"`（大写）
- ❌ `"success"`（小写）不接受
- ❌ `"Success"`（首字母大写）不接受
- ❌ `"SUCCESS "`（带空格）不接受
- ❌ `200`（数字类型）不接受
- ❌ `{"data":{"code":"SUCCESS"}}`（嵌套结构）不接受
- ✅ `{"code":"SUCCESS","extra":"x"}`（额外字段可容忍）
- ✅ `{"code":"SUCCESS","msg":"any message"}`（msg 内容不校验）

**失败响应**：任何其他响应都会触发 [DSPay](#term-dspay) 重试。

**重试策略**：

| 第几次 | 延迟 |
|--------|------|
| 第 1 次 | 立即 |
| 第 2 次 | 1 分钟后 |
| 第 3 次 | 5 分钟后 |
| 终态 | 3 次都失败 → `FAILED`（不再重试） |

### 5.10 事件类型说明

| eventType | 触发条件 | 商户建议处理 |
|-----------|----------|-------------|
| `COMPLETED` | 链上确认到账，订单完成 | 标记已支付 / 发货 |
| `CLOSED` | 订单超时未付款，系统关闭 | 取消订单 / 标记过期 |
| `REFUNDED` | 商户主动退款成功 | 更新退款状态 |
| `COMPLETED` + `reopened=true` | CLOSED 后又确认到账，订单重开 | 特殊场景，按业务需求处理 |

> `reopened=true` 场景：用户在订单超时关闭后才付款，但链上确实到账了。[DSPay](#term-dspay) 会重新打开订单并发 `COMPLETED` 事件，`reopened` 字段标识这是重开路径，商户可据此做特殊处理（如需用户补差价或人工确认）。

### 5.11 ⚠️ 坑点（6 条）

1. **raw body 验签**：用 HTTP body 原始字符串，不能反序列化后重新 `JSON.stringify()`（字段顺序变化 → 签名不一致）。Java 用 `@RequestBody String rawBody`，Node.js 用 `raw-body` 包提取原始字节流。这是回调验签失败**最常见**的原因。

2. **HMAC secret 直接 getBytes，不 Base64 解码**：[apiSecret](#term-apisecret) 是 Base64Url 编码 43 字符，但作为 HMAC key 时直接 `secret.getBytes(UTF_8)`，**不要先 Base64 解码**。Base64Url 只是密钥的存储编码，[HMAC-SHA256](#term-hmac-sha256) 对 key 字节序列没有格式要求。

3. **SUCCESS 严格大小写敏感**：必须 `{"code":"SUCCESS"}` 大写。`"success"` / `"Success"` / `"SUCCESS "`（带空格）/ 数字 200 / 嵌套结构全部不接受 → 触发重试。`code` 字段值用 `equals` 比较，不做 `trim`，不做 `equalsIgnoreCase`。

4. **timestamp ±5 分钟防重放**：回调 payload 的 `timestamp` 需校验在 5 分钟窗口内。这是防重放攻击的核心——攻击者截获旧回调重发，timestamp 会超窗口。商户服务器必须开 [NTP](#term-ntp) 同步。

5. **TIMEOUT 不发回调**：10 分钟超时是中间态，订单仍可继续等付款。[DSPay](#term-dspay) 收银台前端会自动轮询订单状态。**只有 CLOSED / COMPLETED / REFUNDED 发回调**。

6. **幂等维度是 orderNo + eventType**：不是仅 orderNo。同一订单可能依次收到 `COMPLETED → REFUNDED`，两者语义不同不应互相覆盖。用 `(orderNo, eventType)` 作为唯一键，重复的 `(orderNo, eventType)` 组合才直接 ACK。

---

## 第 6 章：对账与运维

本章描述对账与运维要点，包括订单统计接口、**金额对账策略**（`originPayAmount` vs `payAmount`）、回调监控告警与密钥定期轮换流程。

### 6.1 订单列表与统计

订单列表、统计报表均在 **[DSPay](#term-dspay) 后台 UI** 查看，支持按时间筛选、金额汇总、7 日明细等。

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

**监控 SQL**（建议接入告警系统）：

```sql
-- 最近 1 小时内 FAILED 的回调数
SELECT COUNT(*) FROM dspay_notify_log
WHERE status = 'FAILED'
  AND completed_at > NOW() - INTERVAL 1 HOUR;
```

**告警规则**：

| 指标 | 阈值 | 含义 |
|------|------|------|
| 最近 1h FAILED 数 | > 5 | 商户回调 URL 异常或商户服务宕机 |
| 当前 RETRYING 堆积数 | > 50 | 商户服务降级，回调消费速度慢 |
| 单商户连续 FAILED | > 3 | 特定商户配置错误 |

### 6.4 密钥定期轮换

建议定期（如每季度）在 [DSPay](#term-dspay) 后台轮换 [apiSecret](#term-apisecret)。

**轮换步骤**：

1. 在**低峰期**在后台执行 regenerate
2. **立即**更新商户后端的 [apiSecret](#term-apisecret) 配置
3. 观察 5 分钟回调验签是否正常

> regenerate 后飞行中的回调用旧密钥签名，商户端可能短暂验签失败。[DSPay](#term-dspay) 会自动重试（1min / 5min，用新密钥），商户端只需容忍短暂数分钟的验签失败窗口。

### 6.5 ⚠️ 坑点（4 条）

1. **金额对账用 `originPayAmount`**：`payAmount` 含尾数（如 100.001），`originPayAmount` 是商品原价（100）。对账比对 `originPayAmount`，否则会因尾数差异报"金额不匹配"。`payAmount` 仅用于链上交易核对。

2. **regenerate 后飞行中回调验签失败**：regenerate 瞬间已发出的回调用旧密钥签名，商户用新密钥验签会失败。[DSPay](#term-dspay) 自动重试（1min / 5min）用新密钥。商户端容忍短暂数分钟验签失败，不要因此回滚到旧密钥。

3. **重试 3 次后转 FAILED**：不再自动重试。可在 [DSPay](#term-dspay) 后台查看未处理订单，或设置告警（1h FAILED > 5）。商户后台应有"FAILED 回调人工补处理"流程。

4. **统计金额使用 `COALESCE(actual_usd_amount, usd_amount)`**：优先使用完成时锁定的实际到账 USD 价值（含补单时实时汇率），fallback 到订单创建时锁定的 `usd_amount` 快照。**影响**：补单场景实际金额可能因补单时现价波动而与创建时不一致；链上检测完成（CHAIN_DETECTION）场景两者基本相等。

---

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
- [DSPay](#term-dspay) 收银台前端会自动轮询订单状态
- 不要依赖回调感知 TIMEOUT

### 7.2 CLOSED 后链上到账（补单流程）

**现象**：订单进入 `CLOSED` 后，用户才完成链上转账，链上确实到账了。

**[DSPay](#term-dspay) 行为**：
- ❌ **自动检测停止**：[DSPay](#term-dspay) 自动检测任务 只扫 `CREATED` / `TIMEOUT`，CLOSED 后不再自动确认
- ✅ 必须商户**手动补单**

**补单流程**（在 [DSPay](#term-dspay) 后台操作）：

1. 在后台订单详情查看链上实际到账金额
2. 确认到账后执行补单操作（订单重开为 COMPLETED）
3. 商户收到 `COMPLETED` + `reopened=true` 回调

> **补单不校验金额匹配**：[DSPay](#term-dspay) 仅记录 `actualReceivedAmount` + `amountDiff`，由商户判断是否接受。建议补单前先在后台订单详情查看链上实际到账金额，金额差异大时人工审核。

### 7.3 退款流程

在 **[DSPay](#term-dspay) 后台** 操作退款。

**约束**：
- 仅 `COMPLETED` 状态订单可退
- `REFUNDED` 是终态，不可逆
- 退款成功后发 `REFUNDED` 回调

### 7.4 密钥泄漏应急决策树

当 `apiSecret` 疑似泄漏时，根据场景选择：

| 场景 | 操作 | 效果 |
|------|------|------|
| 半夜发现、技术不在岗 | 后台 → 安全设置 → 冻结密钥 | 旧 key 立即失效（回调停发 + 创建订单报 `50503`），紧急止血，**不生成新 key** |
| 确认泄漏 | 后台 → 安全设置 → regenerate 换新 key | 旧 key 立即失效，生成新 key（需同步更新商户端配置） |
| 排查后确认未泄漏 | 后台 → 安全设置 → 恢复密钥 | 旧 key 恢复有效 |

> ⚠️ **关键规则**：
> - `regenerate` **不改冻结状态**：冻结状态下执行 regenerate 会生成新 key 但保持冻结，需在后台手动恢复后才能使用
> - 密钥**确认已泄漏**时强烈建议 regenerate 换新 key，**不要仅恢复旧 key**（攻击者仍持有旧 key）

### 7.5 ⚠️ 坑点（4 条）

1. **CLOSED 后链上自动检测停止**：[DSPay](#term-dspay) 自动检测任务 只扫 `CREATED` / `TIMEOUT`。CLOSED 后即使链上到账也不会自动确认，必须在 [DSPay](#term-dspay) 后台操作补单（`reopened=true`）。商户需有"CLOSED 订单人工补处理"流程，或监控 CLOSED 订单的链上到账情况。

2. **补单不校验金额匹配**：手动补单记录 `actualReceivedAmount` + `amountDiff`，由商户判断。建议补单前先在后台订单详情查看链上实际到账金额，金额差异大时人工审核。[DSPay](#term-dspay) 不会因金额不一致拒绝补单。

3. **密钥泄漏：冻结 vs 换新**：半夜发现、技术不在岗 → 先在后台冻结密钥（止血）；确认泄漏 → 在后台 regenerate 换新 key。不要仅恢复旧 key——攻击者仍持有旧 key。

4. **regenerate 不改冻结状态**：冻结状态下执行 regenerate 生成新 key 但保持冻结，需在后台手动恢复后才能使用。两个操作要分开执行，不能指望 regenerate 自动解冻。

---

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

# 获得 URL 后，在 DSPay 后台配置回调 URL 为 ngrok 地址并启用回调
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

> 金额必须精确到响应的 `payAmount`（含尾数），否则链上检测不匹配。

### 8.5 常见验签失败排查表

| 原因 | 排查方法 |
|------|---------|
| payload 反序列化后重新序列化（字段顺序变） | 用 raw body 原始字符串验签 |
| secret 被 Base64 解码了 | 直接用 secret 字符串，不 Base64 解码 |
| 时间偏差大（防重放拦截） | 检查 `timestamp` 是否在 5 分钟内 |
| 密钥已 regenerate（旧密钥） | 在 [DSPay](#term-dspay) 后台查看最新密钥 |

### 8.6 ⚠️ 坑点（3 条）

1. **测试顺序**：不要一上来就端到端测试。先单独验签名算法（用在线 HMAC 工具对比），确认签名计算正确后再端到端。跳步会导致"验签失败"无法定位是签名算法错还是传输层错。

2. **ngrok URL 变化**：免费版 URL 每次重启变化，需在 [DSPay](#term-dspay) 后台重新配置。测试中途重启 ngrok 后，必须同步更新 [DSPay](#term-dspay) 的 `notifyUrl`，否则回调发到旧 URL 全部失败。

3. **本地 HTTP 客户端用 HTTP_1_1**：调 `http://localhost:xxxx`（非 HTTPS）时 Java HttpClient 必须 `HTTP_1_1`，不能用 `HTTP_2`（明文 h2c 大多数服务不支持 → Connection reset）。HTTPS 才能用 HTTP_2。

---

## 第 9 章：FAQ

本章按认证 / 签名 / 订单 / 回调 / 配置五类组织常见问题，便于快速查找。

### 9.1 认证类

**Q: 后台登录会话过期了怎么办？**
A: 后台会话滑动 7 天续期——每次活跃自动续期 7 天。闲置 7 天后 session 过期，需重新在 [DSPay](#term-dspay) 后台使用钱包登录。商户后端对接 [DSPay](#term-dspay) 的接口（创建订单、回调验签）使用 [apiSecret](#term-apisecret) 签名，不依赖后台会话，不受此限制。

**Q: 观察钱包（read-only）能登录吗？**
A: 不能。[SIWE](#term-siwe) 需要私钥签名，观察钱包无法 `personal_sign`。必须用有私钥的钱包登录。

### 9.2 签名类

**Q: 签名一直验签失败（50613）？**
A: 90% 是 BigDecimal 科学计数法问题。`new BigDecimal("0.0000000001").toString()` 可能输出 `1E-10`，必须用 `toPlainString()` 输出 `0.0000000001`。检查规范化字符串中金额字段是否有 `E` 字符。

**Q: Node.js 金额精度怎么处理？**
A: `productPrice` 和 `payAmount` 用字符串字面量 `'99.99'` 或 Big.js，不能用 JS number。JS number 超过 `Number.MAX_SAFE_INTEGER` 或极小 decimal 会进入科学计数法。

**Q: [apiSecret](#term-apisecret) 需要 Base64 解码后使用吗？**
A: **不需要**。[apiSecret](#term-apisecret) 字符串直接作为 HMAC key 使用。Base64Url 只是密钥的存储编码，[HMAC-SHA256](#term-hmac-sha256) 对 key 字节序列没有格式要求。`secret.getBytes(UTF_8)` 直接传给 `SecretKeySpec`。

**Q: 签名字段顺序错了会怎样？**
A: 签名不一致 → `50613`。必须严格按 `merchantNo → outOrderNo → payAmount → timestamp` 顺序拼接（4 段最小签名集），用 `&` 连接，**顺序敏感**。`outOrderNo` 未传或纯空白时 value 为空字符串（key 保留）。`productPrice` / `productPriceCurrency` / `productId` / `networkId` / `contractAddress` 不参与签名。

### 9.3 订单类

**Q: 响应的 payAmount 为什么不是我传的金额？**
A: 尾数机制。[DSPay](#term-dspay) 为每个订单附加唯一尾数（如 100.001），用于区分同金额并发订单。用户必须按响应的 `payAmount`（含尾数）付款，否则链上检测不匹配。对账用 `originPayAmount`（商品原价）。

**Q: 创建订单报 50609 NO_ENABLED_ADDRESS？**
A: 链支持但商户没为该 [networkId](#term-networkid)（链）配 ENABLED 收款地址。去 [DSPay](#term-dspay) 后台配置收款地址。

**Q: 创建订单报 50707 CHAIN_NOT_SUPPORTED？**
A: [networkId](#term-networkid) 不在 9 链白名单。检查 [networkId](#term-networkid) 拼写（如 `evm--1` 不是 `ethereum-mainnet`），以 `GET /dspay/public/supported-chains` 返回为准。

**Q: 并发创建订单报 50610/50611/50612？**
A: 尾数机制并发问题。`50610 ORDER_CREATE_BUSY`（尾数锁冲突，重试即可）/ `50611 SUFFIX_EXHAUSTED`（尾数槽位耗尽，等并发降）/ `50612 SUFFIX_PRECISION_SATURATED`（精度饱和，`suffixScale > Math.min(tokenDecimals, 18)`）。

### 9.4 回调类

**Q: 回调一直验签失败？**
A: 90% 是 payload 被 JSON 反序列化后重新序列化导致字段顺序变化。必须用 HTTP body 原始字符串验签，不能用反序列化后的对象再 `toJson()`。

**Q: 回调一直重试怎么办？**
A: 检查响应是否符合 `{"code":"SUCCESS"}` 严格格式。`code` 必须是大写字符串 `"SUCCESS"`，数字 200 / 小写 / 嵌套都不接受。

**Q: 重试策略是什么？**
A: 立即 / 1min / 5min 共 3 次，3 次都失败转 `FAILED` 终态不再重试。可在 [DSPay](#term-dspay) 后台查看未处理订单。

**Q: reopened=true 是什么场景？**
A: 用户在订单 CLOSED 后才付款，但链上确实到账了。[DSPay](#term-dspay) 重新打开订单并发 `COMPLETED` + `reopened=true` 回调。商户可据此做特殊处理（如需用户补差价或人工确认）。

**Q: 幂等怎么做？**
A: 基于 `orderNo + eventType`（不是仅 orderNo）。同一订单可能依次收到 `COMPLETED → REFUNDED`，两者语义不同不应互相覆盖。用 DB 唯一键 `(order_no, event_type)` 或 Redis SETNX `notify:{orderNo}:{eventType}`。

**Q: TIMEOUT 会发回调吗？**
A: **不会**。TIMEOUT 是 10 分钟未支付的中间过渡态，订单仍可继续等付款。只有 CLOSED / COMPLETED / REFUNDED 发回调。[DSPay](#term-dspay) 收银台前端会自动轮询订单状态。

### 9.5 配置类

**Q: 密钥泄漏怎么办？**
A: 半夜应急 → 在 [DSPay](#term-dspay) 后台冻结密钥（止血）；确认泄漏 → 在后台 regenerate 换新 key。不要仅恢复旧 key。

**Q: 地址白名单和商户 ENABLED 地址什么区别？**
A: 平台白名单（以 `GET /dspay/public/supported-chains` 返回为准）定义"[DSPay](#term-dspay) 支持哪些链"；商户级收款地址定义"本商户在每条链上用哪个地址收款"（在 [DSPay](#term-dspay) 后台配置）。创建订单时两个条件都要满足。

**Q: 50503 API_SECRET_DISABLED 怎么处理？**
A: 密钥已被冻结。如果是自己冻结的，排查完成后在后台恢复密钥；如果是他人操作，联系商户管理员。

---

## 附录 A：Java 综合接入示例

> 一套可复制使用的代码：工具类（签名 + 验签）+ 创建订单 + 回调通知。
>
> **环境要求**：JDK 11+。
> - **A.1（签名工具类）**：纯 JDK，零第三方依赖，任意 Java 项目可直接复制使用。
> - **A.2/A.3（Spring 集成）**：需要 Spring Boot 2.7+ / 3.x + Lombok。
>
> **Maven 依赖**（仅 A.2/A.3 需要，A.1 无需任何依赖）：
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

#### A.1 签名工具类（一个文件覆盖两个方向）

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * DSPay HMAC-SHA256 签名工具（商户后端可直接复制使用）。
 *
 * <p>两个方向共用同一个 apiSecret：
 * <ul>
 *   <li>{@link #signOrder}      —— 商户 → DSPay：创建订单时对规范化字符串签名</li>
 *   <li>{@link #verifyCallback} —— DSPay → 商户：回调通知时对 raw body 验签</li>
 * </ul>
 */
public class DspaySigner {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * 创建订单签名：规范化字符串 → HMAC-SHA256 → hex 小写。
     * 固定顺序：merchantNo → outOrderNo → payAmount → timestamp（顺序敏感），4 段最小签名集。
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

    /** null 或纯空白 → ""，否则 trim（与服务端 normalizeOptional 对齐）。 */
    public static String normalizeOptional(String s) {
        return (s == null || s.trim().isEmpty()) ? "" : s.trim();
    }

    /** 回调通知验签：对 raw body 计算 HMAC，与 X-DSPay-Signature 常量时间比较（防 timing attack）。 */
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
            throw new IllegalStateException("HMAC-SHA256 签名失败", e);
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
}
```

#### A.2 生成收银台支付链接

> 适用场景：商户后端收到前端下单请求 → 本地签名 → 拼接收银台 URL → 返回给前端（302 跳转或返回 URL 供前端打开）。
> 完整可运行 demo 见 [§4.8](#java-端到端-demo)，这里给出 Spring Service 集成示例。
>
> **环境要求**：JDK 11+，Spring Boot 2.7+ / 3.x，Lombok。
>
> **Maven 依赖**（Spring Boot 项目通常已包含，无需额外添加）：
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
// 签名工具类 DspaySigner 见 A.1，直接复制到项目中使用
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class DspayCashierService {

    // 收银台基础地址
    private static final String CASHIER_BASE_URL = "https://cashier.ds.pro";

    @Value("${dspay.api-secret}")
    private String apiSecret;

    /**
     * 生成收银台支付链接，前端拿到后跳转或 iframe 嵌入。
     * 签名只覆盖 4 字段（防冒充/防篡改金额/防重放），
     * productPrice/productPriceCurrency/productId 可选，仅展示用。
     */
    public String buildCashierUrl(String merchantNo, BigDecimal productPrice,
                                   String currency, BigDecimal payAmount,
                                   String outOrderNo, String productId) {
        long timestamp = System.currentTimeMillis();
        String signature = DspaySigner.signOrder(merchantNo, outOrderNo,
                payAmount, timestamp, apiSecret);

        // 拼接收银台 URL（参数值 URL 编码，顺序不影响签名）
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

> **流程说明**：商户端不直接调用 DSPay 创建订单 API，而是本地签名后生成收银台链接。用户打开收银台页面后自行选择链/代币，收银台前端调用 DSPay API 创建订单。签名保证商户号、金额、时间戳不被前端篡改。

#### A.3 回调通知处理（DSPay → 商户后端）

> **环境要求**：同 A.2（JDK 11+，Spring Boot 2.7+ / 3.x，Lombok）。
> 反序列化用 Spring Boot 内置的 Jackson（无需额外依赖）。

```java
// 签名工具类 DspaySigner 见 A.1，直接复制到项目中使用
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
    /** 防重放窗口：±5 分钟，与服务端一致 */
    private static final long REPLAY_WINDOW_MS = 5 * 60 * 1000;

    @Value("${dspay.api-secret}")
    private String apiSecret;

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> handleNotify(
            @RequestBody String rawBody,
            @RequestHeader("X-DSPay-Signature") String signature) {

        // 1. 验签（用 raw body 原始字符串，工具类见 A.1）
        if (!DspaySigner.verifyCallback(rawBody, signature, apiSecret)) {
            log.warn("验签失败 signature={}", signature);
            return ResponseEntity.status(401).build();
        }

        // 2. 解析 payload（Jackson 已包含在 spring-boot-starter-web 中）
        NotifyPayload payload;
        try {
            payload = JSON.readValue(rawBody, NotifyPayload.class);
        } catch (Exception e) {
            log.error("JSON 解析失败", e);
            return ResponseEntity.badRequest().build();
        }

        // 3. 防重放校验（±5 分钟窗口）
        long now = System.currentTimeMillis();
        if (Math.abs(now - payload.getTimestamp()) > REPLAY_WINDOW_MS) {
            log.warn("timestamp 过期，疑似重放 orderNo={}", payload.getOrderNo());
            return ResponseEntity.status(401).build();
        }

        // 4. 幂等处理 + 业务逻辑
        try {
            handleNotifyBusiness(payload);
        } catch (Exception e) {
            log.error("业务处理异常，让 DSPay 重试 orderNo={}", payload.getOrderNo(), e);
            return ResponseEntity.status(500).build();
        }

        // 5. 返回成功响应（严格模式）
        return ResponseEntity.ok(Map.of("code", "SUCCESS", "msg", "ok"));
    }
}
```

> **关键点**：
> - `@RequestBody String rawBody` 接收原始字符串，**不要**用 `@RequestBody NotifyPayload` 让 Spring 先反序列化（否则后续 `toJson()` 字段顺序变化导致验签失败）
> - 验签通过后再用 Jackson `readValue()` 反序列化为业务对象

---

## 附录 B：Node.js 综合接入示例

> 一套可复制使用的代码：工具模块 + 创建订单 + 回调通知。
>
> **环境要求**：Node.js 18+。
>
> **依赖安装**（B.2/B.3 需要 Express）：
> ```bash
> npm install express raw-body
> # B.1 签名工具模块无需安装，crypto 为 Node.js 内置模块
> ```

#### B.1 签名工具模块（`dspay-signer.js`，一个文件覆盖两个方向）

```javascript
const crypto = require('crypto');

/**
 * DSPay HMAC-SHA256 签名工具（商户后端可直接复制使用）。
 *
 * 两个方向共用同一个 apiSecret：
 *   - signOrder      商户 → DSPay：创建订单时对规范化字符串签名
 *   - verifyCallback DSPay → 商户：回调通知时对 raw body 验签
 */

/**
 * 创建订单签名：规范化字符串 → HMAC-SHA256 → hex 小写。
 * 固定顺序：merchantNo → outOrderNo → payAmount → timestamp（顺序敏感）。
 * 注意：amount 字段必须用字符串（避免 number 精度丢失）。
 */
function signOrder({ merchantNo, outOrderNo, payAmount },
                   timestamp, apiSecret) {
    // opt 与服务端 isBlank 对齐：null 或纯空白 → ""，否则 trim
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
 * 回调通知验签：对 raw body 计算 HMAC，与 X-DSPay-Signature 常量时间比较。
 * 使用 crypto.timingSafeEqual 防 timing attack。
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

#### B.2 生成收银台支付链接

> 完整可运行 demo 见 [§4.9](#node.js-端到端-demo)，这里给出 Express Route 集成示例。

```javascript
const express = require('express');
const { signOrder } = require('./dspay-signer');

const router = express.Router();
// 收银台基础地址
const CASHIER_BASE_URL = 'https://cashier.ds.pro';
const API_SECRET = process.env.DSPAY_API_SECRET;

router.post('/api/cashier-url', (req, res) => {
    const { merchantNo, productPrice, currency,
            payAmount, outOrderNo, productId } = req.body;

    // 生成 timestamp + 签名
    const timestamp = Date.now();
    const signature = signOrder(
        { merchantNo, outOrderNo, payAmount },
        timestamp, API_SECRET);

    // 拼接收银台 URL（签名参数 + 可选展示参数）
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

> **流程说明**：商户端不直接调用 DSPay 创建订单 API，而是本地签名后生成收银台链接。用户打开收银台页面后自行选择链/代币，收银台前端调用 DSPay API 创建订单。签名保证商户号、金额、时间戳不被前端篡改。

#### B.3 回调通知处理（DSPay → 商户后端）

```javascript
const express = require('express');
const rawBody = require('raw-body');
const { verifyCallback } = require('./dspay-signer');

const app = express();
const API_SECRET = process.env.DSPAY_API_SECRET;

// 必须用 raw body 验签，不能让 express.json() 先消费
app.post('/notify', async (req, res) => {
    const raw = (await rawBody(req)).toString('utf8');
    const signature = req.headers['x-dspay-signature'];

    // 1. 验签（工具模块见 B.1）
    if (!verifyCallback(raw, signature, API_SECRET)) {
        return res.status(401).json({ code: 'FAIL', msg: '签名校验失败' });
    }

    // 2. 解析 + 防重放 + 幂等
    const payload = JSON.parse(raw);
    const now = Date.now();
    if (Math.abs(now - payload.timestamp) > 5 * 60_000) {
        return res.status(401).json({ code: 'FAIL', msg: 'timestamp 过期' });
    }

    // 3. 业务处理
    try {
        await handleOrder(payload);
    } catch (e) {
        return res.status(500).json({ code: 'FAIL', msg: '业务异常' });
    }

    // 4. 成功响应（严格模式）
    res.json({ code: 'SUCCESS', msg: 'ok' });
});
```

> **关键点**：用 `raw-body` 提取原始字节流验签，**不要**在 `express.json()` 后再用 `JSON.stringify(req.body)` 验签（字段顺序变化导致签名不一致）。

---

## 附录 C：错误码完整列表

来源：`DspayExceptionConstant.java`，按错误码段分组。

#### 通用错误（400xx）

| code | msg | 说明 |
|------|------|------|
| 40001 | PARAM_ERROR | 参数校验失败 |
| 40101 | UNAUTHORIZED | 未登录 |
| 40301 | FORBIDDEN | 无权限 |
| 40401 | NOT_FOUND | 资源不存在 |
| 40901 | STATE_CONFLICT | 状态冲突 |
| 50000 | INTERNAL_ERROR | 服务内部异常 |

#### 商户相关（505xx）

| code | msg | 说明 |
|------|------|------|
| 50501 | MERCHANT_NOT_FOUND | 商户不存在 |
| 50502 | MERCHANT_DISABLED | 商户已被禁用 |
| 50503 | API_SECRET_DISABLED | 商户 API 密钥已冻结（回调停发 + 创建订单报错。用于密钥泄漏紧急冻结） |

#### 订单相关（506xx）

| code | msg | 说明 |
|------|------|------|
| 50601 | ORDER_NOT_FOUND | 订单不存在 |
| 50603 | ORDER_ALREADY_PAID | 订单已支付 |
| 50604 | ORDER_EXPIRED | 订单已过期 |
| 50605 | ORDER_STATUS_NOT_ALLOWED | 订单状态不允许此操作 |
| 50606 | TX_HASH_INVALID | 交易哈希无效 |
| 50608 | TX_HASH_ALREADY_USED | 交易哈希已被使用（仅 supplement 补单校验；refund 退款不再校验 refundTxHash 防重放） |
| 50609 | NO_ENABLED_ADDRESS | 无可用收款地址（商户未为该 [networkId](#term-networkid)（链）配 ENABLED 地址） |
| 50610 | ORDER_CREATE_BUSY | 订单创建繁忙（尾数锁冲突，重试即可） |
| 50611 | SUFFIX_EXHAUSTED | 尾数槽位已耗尽 |
| 50612 | SUFFIX_PRECISION_SATURATED | 尾数精度饱和（`suffixScale > Math.min(tokenDecimals, 18)`） |
| 50613 | ORDER_SIGNATURE_INVALID | 订单创建签名校验失败 |
| 50614 | ORDER_TIMESTAMP_EXPIRED | 订单创建时间戳超出 ±5 分钟窗口 |

#### 地址相关（507xx）

| code | msg | 说明 |
|------|------|------|
| 50702 | ADDRESS_FORMAT_INVALID | 地址格式无效 |
| 50703 | ADDRESS_NOT_FOUND | 地址不存在 |
| 50704 | ADDRESS_NOT_IN_WALLET | 地址不属于当前钱包 |
| 50705 | ADDRESS_NETWORK_MISMATCH | 地址与网络不匹配 |
| 50706 | CHAIN_ADDRESS_ALREADY_BOUND | 该链地址已被绑定 |
| 50707 | CHAIN_NOT_SUPPORTED | 链不受支持（[networkId](#term-networkid) 不在 9 链白名单或链已禁用） |

#### JWT 认证相关（508xx）

| code | msg | 说明 |
|------|------|------|
| 50801 | JWT_TOKEN_MISSING | 缺少 [JWT](#term-jwt) Token |
| 50802 | JWT_TOKEN_INVALID | [JWT](#term-jwt) Token 无效 |
| 50803 | JWT_TOKEN_EXPIRED | [JWT](#term-jwt) Token 已过期（含 session 过期） |

#### SIWE 签名认证相关（509xx）

| code | msg | 说明 |
|------|------|------|
| 50901 | SIWE_NONCE_NOT_FOUND | [SIWE](#term-siwe) [nonce](#term-nonce) 不存在 |
| 50902 | SIWE_NONCE_EXPIRED | [SIWE](#term-siwe) [nonce](#term-nonce) 已过期（TTL 5 分钟） |
| 50903 | SIWE_SIGNATURE_INVALID | [SIWE](#term-siwe) 签名无效（ecrecover 恢复地址不匹配） |
| 50904 | SIWE_DOMAIN_MISMATCH | [SIWE](#term-siwe) domain 不匹配 |
| 50905 | SIWE_MESSAGE_INVALID | [SIWE](#term-siwe) 消息无效 |
