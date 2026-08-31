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
| <a id="term-apisecret"></a>**apiSecret** | 商户 API 密钥，用于签名创建/查询请求及验证回调，在 [DSPay 后台](https://mcashier.ds.pro/login/)获取，只能保存在商户服务端 |
| <a id="term-merchantno"></a>**merchantNo** | 商户业务编号（`DSM` 前缀，如 `DSM1`），调用公共创建/查询接口时必传；对外暴露的业务编码，**非 DB 自增主键** |
| **orderNo** | DSPay 创建订单后返回的订单唯一标识，用于查询、回调、对账和收银台页面寻址 |
| **checkoutUrl** | 创建订单接口返回的完整收银台地址，格式为 `{payPageBaseUrl}/checkout/{orderNo}`；商户只负责跳转，不自行拼接业务参数 |
| <a id="term-networkid"></a>**networkId** | 链的唯一标识（如 `evm--1` = Ethereum 主网），完整列表见 [§3.2](#networkid-速查表) |
| <a id="term-contractaddress"></a>**contractAddress** | 代币合约或 mint 地址；可与 `networkId` 组成 `allowedPaymentMethods` 项，限制用户在收银台可选的支付方式 |
| <a id="term-hmac-sha256"></a>**HMAC-SHA256** | 哈希消息认证码算法；DSPay 用 `apiSecret` 作 key 对规范化字符串计算，输出 hex 小写 |
| <a id="term-evm"></a>**EVM** | Ethereum Virtual Machine，以太坊虚拟机；EVM 系链指兼容以太坊智能合约的链（Ethereum / BSC / Polygon / Arbitrum / Base） |
| <a id="term-usdt"></a>**USDT** / <a id="term-usdc"></a>**USDC** | 与美元锚定的稳定币（Tether USD / Centre USD Coin） |
| **尾数（<a id="term-amountsuffix"></a>amountSuffix）** | DSPay 为区分同金额并发订单附加的小额尾数（如 100.001 中的 0.001）。稳定币按 6 位精度处理：商户金额最多使用前 2 位小数，后 4 位由 DSPay 生成尾数，详见 [§4.1](#订单尾数机制详解) |
| **补单（<a id="term-supplement"></a>supplement）** | CLOSED 订单后续链上到账时，由商户在后台确认到账、订单重开为 COMPLETED 的操作 |
| **回调（<a id="term-webhook"></a>webhook）** | DSPay 向商户 `notifyUrl` 发送的 HTTP POST 通知，事件类型：`CLOSED` / `COMPLETED` / `REFUNDED`；`CREATED` / `TIMEOUT` 仅推进订单状态，不发送回调 |
| <a id="term-notifyurl"></a>**notifyUrl** | 商户接收 DSPay 回调通知的公网可达地址；支持 `http://` 和 `https://`，生产环境建议 HTTPS |
| <a id="term-ntp"></a>**NTP** | Network Time Protocol，网络时间协议；用于服务器时钟同步 |

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
| 测试用稳定币 | 钱包至少准备 0.02 [USDT](#term-usdt)，并另备足够的链上 Gas 代币 | — |

> ⚠️ 如果你还没有以上任一项，请先完成对应章节再回来。下面 Step 1 可以零依赖体验。

### Step 1：准备商户配置

在 DSPay 后台获取 `merchantNo` 和 `apiSecret`，并至少配置一个已启用的收款地址。`notifyUrl` 可稍后配置，但生产上线前必须完成。

### Step 2：服务端预创建订单

填入你的 `merchantNo`、`apiSecret` 和 DSPay API 地址，保存为 `create-order.mjs`，使用统一基线 Node.js `18.20.8` 运行。

> 执行前必须将下面的 `DSPAY_BASE_URL`、`MERCHANT_NO` 和 `API_SECRET` 替换为真实参数值。`merchantNo` 和 `apiSecret` 从 DSPay 商户后台获取；示例占位值不能直接用于请求。

```javascript
import crypto from 'node:crypto';

const DSPAY_BASE_URL = 'https://wallet.ds.pro'; // 替换为真实 DSPay API 地址
const MERCHANT_NO = '请替换为真实merchantNo';   // 必须替换：商户后台中的 merchantNo
const API_SECRET = '请替换为真实apiSecret';     // 必须替换：商户后台中的 apiSecret
const outOrderNo = `ORDER-${Date.now()}`;
const payAmount = '0.01';
const timestamp = Date.now();

// 创建签名采用固定字段顺序；可选字段未传时仍保留 key=。
const canonical = [
  `merchantNo=${MERCHANT_NO}`,
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

const signature = crypto.createHmac('sha256', API_SECRET)
  .update(canonical, 'utf8')
  .digest('hex');

const response = await fetch(`${DSPAY_BASE_URL}/dspay/public/order/create`, {
  method: 'POST',
  headers: {'content-type': 'application/json'},
  body: JSON.stringify({
    merchantNo: MERCHANT_NO,
    outOrderNo,
    payAmount,
    timestamp,
    signature,
  }),
});

const result = await response.json();
if (result.code !== 0) {
  throw new Error(result.message || result.header?.message || `DSPay error ${result.code}`);
}

console.log('orderNo:', result.data.orderNo);
console.log('checkoutUrl:', result.data.checkoutUrl);
```

### Step 3：跳转并完成支付

商户将用户跳转到响应中的完整 `checkoutUrl`。用户在 DSPay 收银台选择网络和代币并点击 Pay Now 后，系统才锁定收款地址、最终金额和支付方式。

> 签名时间窗口为5分钟，只限制创建请求的新鲜度；订单从创建成功起按`expireAt`进入10分钟支付倒计时。

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
| 1 | **注册商户** | [SIWE](#term-siwe) 钱包登录 | 商户账号 |
| 2 | **配置收款** | 配置地址 + 回调 URL | ENABLED 地址 |
| 3 | **预创建订单** | 商户服务端签名调用公共创建接口 | `orderNo + checkoutUrl + expireAt` |
| 4 | **用户支付** | 用户在托管收银台选币、Pay Now 后向商户地址转账 | 链上交易 |
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
- **JDK 11+、Node.js 18.20.8 或 PHP 5.6+**：运行对应语言 Demo（其他语言可按规范自行实现）
- **测试用稳定币**：每条测试链的钱包至少准备 0.02 [USDT](#term-usdt)，用于覆盖 0.01 USDT 原始金额及订单识别尾数；链上 Gas 代币需另行准备

#### Demo 运行时版本

以下版本已实际执行对应测试。复制示例时建议优先使用表中的已验证版本；“最低版本”表示源码使用的最低语言/API基线。

| Demo | 最低版本 | 已验证版本 | 外部依赖 |
|------|----------|------------|----------|
| 文档内 Node.js 示例、Node.js Demo | Node.js `18.20.8` | Node.js `18.20.8` + npm `10.8.2` | 无 npm 依赖，仅使用 Node.js 内置模块 |
| Java Demo、Java 验签示例 | JDK 11 | Microsoft OpenJDK `11.0.27`、Eclipse Temurin `21.0.11` | 无 Maven/Gradle 依赖，仅使用 JDK 标准库 |
| PHP Demo | PHP 5.6 | PHP `5.6.40`、`8.5.10` CLI | 无 Composer 依赖，仅使用 PHP 标准扩展 |
| 前端 Demo | 支持 `URL`、`sessionStorage`、`crypto.randomUUID()` 的现代浏览器 | Google Chrome `151.0.7922.175` | 单个 HTML 文件，无前端框架和构建工具 |

> Node.js 只需安装一个版本，统一调试基线为 Node.js `18.20.8` + npm `10.8.2`；Node.js Demo 提供 `.nvmrc`，可在其目录执行 `nvm use`。验证环境包括 macOS `15.1` 和 Docker Linux。Java Demo 可直接使用 JDK 11 的单文件源码启动模式；PHP Demo 的最低版本由 `hash_equals()` 决定，为 PHP 5.6。

### 1.5 核心接入速查表

**商户需要对接的入口**：

| API | 方法 | 用途 |
|---|---|---|
| 查询支付方式白名单 | `GET /dspay/public/supported-chains` | 获取平台支持的网络和代币，无需签名 |
| 预创建订单 | `POST /dspay/public/order/create` | 服务端签名创建订单，取得`orderNo`和完整`checkoutUrl` |
| 主动查询订单 | `POST /dspay/public/order/query` | 未成功收到回调时查询权威订单状态和详情，需HMAC签名 |
| 接收回调 | 商户实现 `notifyUrl` | 接收 [DSPay](#term-dspay) 的支付/关闭/退款通知 |

> 其他操作（注册登录、配置收款地址、配置回调 URL、订单统计报表、退款、补单、密钥管理）均在 **[DSPay 后台](https://mcashier.ds.pro/login/) UI** 完成，无需调 API。
> 加载订单信息、确认支付方式和获取支付结果等收银台交互均由 [DSPay](#term-dspay) 完成，商户无需对接。

### 1.6 公共接口统一响应

```json
{"code":0,"data":{},"header":{"resultCode":0}}
```

- 商户只根据顶层 `code` 判断业务结果：`code = 0` 成功，`code != 0` 失败。
- `header.resultCode` 是与顶层 `code` 同值的兼容字段，无需读取或重复判断。
- 业务数据位于 `data`；失败信息优先读取顶层 `message`，为空时再读取 `header.message`。
- HTTP 200 只代表请求到达服务，不代表业务成功。

---

[↑ 返回目录](#目录)

<a id="第-2-章注册与认证"></a>
## 第 2 章：注册与认证

本章说明商户注册与认证机制，包括后台登录方式、会话有效期与 `apiSecret` 的获取。

<a id="商户注册与登录"></a>
### 2.1 商户注册与登录

商户在 [DSPay 后台](https://mcashier.ds.pro/login/)使用 [EVM](#term-evm) 钱包登录（[SIWE](#term-siwe) 签名认证）。**首次登录自动创建商户账号**，无需单独注册。

登录后可获取：
- **[merchantNo](#term-merchantno)**：商户业务编号（`DSM` 前缀，如 `DSM1`），调用公共创建/查询接口时必传
- **[apiSecret](#term-apisecret)**：API 密钥，用于收银台链接签名和回调验签（后台页面展示，妥善保管）

<a id="会话有效期"></a>
### 2.2 会话有效期

后台登录会话有效期 7 天（滑动过期：每次活跃自动续期 7 天）。闲置 7 天后需重新登录。

> 商户后端调用创建/查询接口及进行回调验签时使用 [apiSecret](#term-apisecret)，不依赖后台登录会话，不受此限制。

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

返回 [DSPay](#term-dspay) 平台支持的全部网络和代币白名单（全量、不按商户过滤）。返回的 `networkId` + `tokens[].address` 可用于创建订单的 `allowedPaymentMethods[].networkId` / `contractAddress`。

**响应示例**：

```json
{
  "code": 0,
  "data": [{
    "networkId": "evm--1",
    "chainName": "Ethereum",
    "chainLogoUrl": "https://assets.ds.pro/server-service-indexer/evm--1/tokens/address--1721282106924.png",
    "tokens": [{
      "symbol": "USDT",
      "address": "0xdac17f958d2ee523a2206206994597c13d831ec7",
      "logoUrl": "https://ds-oss-prod.s3.ap-east-1.amazonaws.com/ds-oss-prod/1752200492611b1fe218f-73d8-40e4-a6ae-5a2fc52740a1.png"
    }]
  }],
  "header": {"resultCode": 0}
}
```

**字段说明**：

| 字段 | 类型 | 允许 null | 说明 |
|------|------|-----------|------|
| `networkId` | string | 否 | 链 [networkId](#term-networkid)（如 `evm--1`），创建订单 `allowedPaymentMethods[].networkId` 使用此值 |
| `chainName` | string | 否 | 链显示名（如 `Ethereum`） |
| `chainLogoUrl` | string | 是 | 链 Logo 的完整 URL；未配置时字段仍返回，值为 `null` |
| `tokens[].symbol` | string | 否 | 代币符号（`USDT` / `USDC`） |
| `tokens[].address` | string | 否 | 代币合约地址 / mint 地址，创建订单 `allowedPaymentMethods[].contractAddress` 使用此值 |
| `tokens[].logoUrl` | string | 是 | 代币 Logo 的完整 URL；未配置时字段仍返回，值为 `null` |

> 该接口只表示平台白名单。收银台最终可选列表为“平台支持列表、商户已启用收款地址、商户传入的 `allowedPaymentMethods`”三者的交集。商户传入限制时保留其数组顺序；未传时按收款地址创建时间排序。

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

商户只需为**要收款的链**配置 `ENABLED` 状态的收款地址；不收款的链无需配置。预创建订单时暂不锁定网络、代币和地址；用户在收银台确认支付方式时，系统才从对应网络的已启用地址中分配并锁定收款地址。

> 收款地址按**链级别**配置（不区分 token）：一个地址接收该链上所有 [DSPay](#term-dspay) 支持的稳定币（如 [USDT](#term-usdt) / [USDC](#term-usdc)）。

在 [DSPay 后台](https://mcashier.ds.pro/login/)为每条要收款的链配置收款地址。每个地址需指定：
- **[networkId](#term-networkid)**（链）
- **收款地址**
- **启用状态**（ENABLED / DISABLED）

> 没有已启用收款地址的网络不会出现在该商户的可选支付方式中；若确认支付时已无可用地址，系统返回 `NO_ENABLED_ADDRESS`。

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

在 [DSPay 后台](https://mcashier.ds.pro/login/)配置回调 URL（商户接收支付通知的 webhook 地址）并启用回调。当前支持 HTTP 和 HTTPS，生产环境建议使用 HTTPS。

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
   用户可选支付方式必须同时满足：[networkId](#term-networkid) 在平台白名单 + 商户在该网络配置了 ENABLED 地址 + 商户创建订单时允许该组合（如传入限制）。

3. **回调开关默认关闭**：必须在 [DSPay 后台](https://mcashier.ds.pro/login/)手动启用回调，否则 [DSPay](#term-dspay) 永远不发回调。新商户登录后最容易遗漏这一步——创建订单成功、用户付款成功、但商户后端永远收不到通知。

4. **首次启用回调自动生成 [apiSecret](#term-apisecret)**：首次在后台启用回调且无旧密钥时自动生成 [apiSecret](#term-apisecret)；关闭再开启时旧密钥保留不重新生成。**商户必须在后台拿到这个 [apiSecret](#term-apisecret) 才能做创建订单签名和回调验签**。

---

[↑ 返回目录](#目录)

<a id="第-4-章创建第一笔订单"></a>
## 第 4 章：创建第一笔订单

本章说明商户服务端预创建订单、请求签名、幂等处理和收银台跳转。商户只需调用公共接口并跳转到返回的 `checkoutUrl`；网络选择、支付方式锁定、二维码展示和状态轮询均由 DSPay 收银台完成。

<a id="订单尾数机制详解"></a>
### 4.1 订单尾数机制详解

DSPay 使用识别尾数区分相同网络、代币、收款地址和原始金额的并发订单。例如商户创建金额为 `100.00` 的订单，用户确认支付方式后，最终应付金额可能为 `100.001`。

- `originPayAmount`：创建订单时提交的原始应付金额。
- `amountSuffix`：用户确认支付方式时分配的识别尾数。
- `payAmount = originPayAmount + amountSuffix`：收银台最终展示和链上检测使用的金额。
- 创建订单响应只返回 `originPayAmount`；最终金额、收款地址和支付方式在用户点击 Pay Now 后才锁定。
- 用户必须按收银台展示的最终金额精确付款。

<a id="收银台接入流程"></a>
### 4.2 标准接入流程

1. 商户后端可调用 `GET /dspay/public/supported-chains` 获取平台支持的网络和代币。
2. 商户后端生成唯一 `outOrderNo`，按固定字段顺序签名。
3. 商户后端调用 `POST /dspay/public/order/create`。
4. DSPay 立即创建状态为 `CREATED` 的订单，返回 `orderNo`、`checkoutUrl` 和支付截止时间。
5. 商户将用户跳转到 `checkoutUrl`。
6. 用户在 DSPay 收银台选择网络和代币并确认支付；DSPay 锁定支付方式、收款地址、识别尾数和最终应付金额。
7. 用户按收银台展示信息发起链上转账。
8. DSPay 检测到账后更新订单并通过 `notifyUrl` 通知商户；商户也可调用公共查询接口兜底确认。

> 签名 5 分钟有效期只限制创建请求的提交时间。订单 10 分钟支付倒计时从订单创建开始，用户尚未确认支付方式也会正常超时。
### 4.3 创建订单接口

```http
POST /dspay/public/order/create
Content-Type: application/json
```

#### 4.3.1 请求

| 字段 | 类型 | 必填 | 签名 | 说明 |
|---|---|---:|---:|---|
| `merchantNo` | string | 是 | 是 | DSPay 商户编号；不能为空，字符串长度最多 32 个字符 |
| `outOrderNo` | string | 是 | 是 | 商户订单号；不能为空，字符串长度最多 64 个字符；同一商户下唯一，也是幂等键 |
| `productPrice` | decimal | 否 | 是 | 商品法币价格；整数部分最多 14 位，小数部分最多 6 位 |
| `productPriceCurrency` | string | 否 | 是 | 商品价格币种；字符串长度最多 16 个字符 |
| `productId` | string | 否 | 是 | 商户产品 ID；字符串长度最多 64 个字符 |
| `attach` | object | 否 | 是 | 附加 JSON 对象；规范化 JSON 的 UTF-8 编码长度最多 4096 字节，嵌套深度最多 3 层 |
| `payAmount` | decimal | 是 | 是 | 原始应付代币金额；最小值 `0.0000000001`，整数部分最多 12 位，小数部分最多 18 位 |
| `allowedPaymentMethods` | array | 否 | 是 | 限制用户可选组合；数组最多 50 项；不传或传空数组表示不额外限制 |
| `returnUrl` | string | 否 | 是 | 可选；订单进入 `TIMEOUT` 时跳转。必须是以 `http://` 或 `https://` 开头的完整 URL，可包含端口、路径和查询参数；整个 URL 最长 8192 个字符。未传时按空字符串参与签名，保留 `returnUrl=` |
| `successRedirectUrl` | string | 否 | 是 | 可选；仅订单进入 `COMPLETED` 时跳转。必须是以 `http://` 或 `https://` 开头的完整 URL，可包含端口、路径和查询参数；整个 URL 最长 8192 个字符。未传时按空字符串参与签名，保留 `successRedirectUrl=`；未配置时停留 DSPay 成功页 |
| `timestamp` | long | 是 | 是 | Unix 毫秒时间戳；与 DSPay 服务端当前时间的差值绝对值不得超过 300000 毫秒（5 分钟） |
| `signature` | string | 是 | 否 | HMAC-SHA256 小写十六进制字符串，固定 64 个字符；字段本身不参与签名 |

`allowedPaymentMethods[]` 每项必须包含非空的 `networkId` 和 `contractAddress`：`networkId` 字符串长度最多 64 个字符，`contractAddress` 字符串长度最多 128 个字符；两者必须组成 `supported-chains` 返回的有效支付方式。最终列表为：

```text
平台支持列表 ∩ 商户已启用收款地址 ∩ allowedPaymentMethods
```

传入时按商户顺序展示；不传时按收款地址创建时间排序。

```json
{
  "merchantNo": "DSM2080260022215368706",
  "outOrderNo": "ORDER-20260821-001",
  "productPrice": "100.00",
  "productPriceCurrency": "USD",
  "productId": "PROD-001",
  "attach": {"customerId":"CUST-1001","source":"web"},
  "payAmount": "100.00",
  "allowedPaymentMethods": [{
    "networkId": "evm--56",
    "contractAddress": "0x55d398326f99059ff775485246999027b3197955"
  }],
  "returnUrl": "https://merchant.example.com/orders/ORDER-20260821-001",
  "successRedirectUrl": "https://merchant.example.com/pay/success",
  "timestamp": 1787292000000,
  "signature": "a1b2c3d4..."
}
```

金额建议使用十进制字符串，禁止科学计数法和二进制浮点运算。

#### 4.3.2 响应

| 字段 | 必返 | 说明 |
|---|---:|---|
| `orderNo` | 是 | DSPay 订单号；用于查询、回调、对账和收银台寻址 |
| `outOrderNo` | 是 | 商户订单号 |
| `checkoutUrl` | 是 | 完整地址：`{payPageBaseUrl}/checkout/{orderNo}` |
| `status` | 是 | 创建成功固定 `CREATED` |
| `originPayAmount` | 是 | 原始金额，不含识别尾数 |
| `createAt` | 是 | 创建时间，Unix 毫秒 |
| `expireAt` | 是 | 支付截止时间，`createAt + 10 分钟` |

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

创建响应不返回网络、代币、最终金额、收款地址或二维码。这些数据在 Pay Now 后才会锁定，二维码由收银台前端生成。

<a id="签名规范化字符串"></a>
### 4.4 签名和幂等

#### 4.4.1 创建签名

固定字段顺序：

```text
merchantNo -> outOrderNo -> productPrice -> productPriceCurrency -> productId
-> attach -> payAmount -> allowedPaymentMethods -> returnUrl
-> successRedirectUrl -> timestamp
```

可选字段未传时仍保留 `key=`。

```text
merchantNo=DSM2080260022215368706&outOrderNo=ORDER-20260821-001&productPrice=100.00&productPriceCurrency=USD&productId=PROD-001&attach={"customerId":"CUST-1001","source":"web"}&payAmount=100.00&allowedPaymentMethods=evm--56|0x55d398326f99059ff775485246999027b3197955&returnUrl=https://merchant.example.com/orders/ORDER-20260821-001&successRedirectUrl=https://merchant.example.com/pay/success&timestamp=1787292000000
```

```text
signature = lowercaseHex(HMAC_SHA256(apiSecret, canonicalString UTF-8))
```

规范化：

- 字符串 trim；空可选字段使用空字符串。
- decimal 使用普通十进制形式。
- `attach` 对象键递归按字典序排列，去除多余空白；数字去除无意义尾零，`-0` 转为 `0`。
- `allowedPaymentMethods` 保留商户顺序并去重，每项拼为 `networkId|contractAddress`，再用逗号连接；`0x` 地址转小写。

`attach` 不可包含密码、私钥、证件或银行卡等敏感信息。

#### 4.4.2 创建幂等

`merchantNo + outOrderNo` 是唯一幂等键：

- 重复请求且业务字段一致：返回首次创建的同一 `orderNo/checkoutUrl/expireAt`，不延长订单时间。
- 幂等键相同但业务字段不同：返回错误码`40901`，错误信息“商户订单号已被使用”（`Merchant order number has already been used`）。
- 商户代码重试时必须复用原 `outOrderNo`。

### 4.5 收银台行为和跳转

只使用创建响应的 `checkoutUrl`；不要自行拼接业务参数。

- Pay Now 前订单已是 `CREATED`，但支付方式尚未锁定。
- Pay Now 首次成功后不能返回重新选币。
- 多窗口访问同一链接时，第一次 Pay Now 胜出；后续请求返回同一支付数据。
- Pay Now 不延长订单 10 分钟支付时间。
- 订单超时/关闭后不能通过旧链接重启。
- 订单创建满180天后，收银台链接不再允许查看；该限制不修改订单状态。

| 状态 | 跳转规则 |
|---|---|
| `COMPLETED` 且配置 `successRedirectUrl` | 跳转 `successRedirectUrl` |
| `COMPLETED` 但未配置 | 停留成功页，不跳转，不使用 `returnUrl` 兜底 |
| 订单 `TIMEOUT`，且配置 `returnUrl` | 跳转 `returnUrl` |
| 订单 `TIMEOUT`，但未配置 `returnUrl` | 停留 DSPay 超时页面，不跳转 |
| 其他情况 | 不使用 `returnUrl` 自动跳转 |

跳转不是支付凭据。商户页面接收用户后，必须由商户服务端调用查询接口确认状态。
<a id="java-端到端-demo"></a>
### 4.6 Java 端到端 Demo

Java 可运行 Demo 统一维护在 [`Demo/back-end/java`](../Demo/back-end/java/README.zh-CN.md)。Demo 会在商户服务端构造完整请求、规范化全部签名字段、调用公共创建接口、检查顶层 `code`，再将浏览器跳转到响应中的 `checkoutUrl`；同时演示 Raw Body 回调验签和公共查询兜底。

- [Java Demo 使用说明](../Demo/back-end/java/README.zh-CN.md)
- [可运行源码 `DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java)
- [启动脚本](../Demo/back-end/java/start.sh) / [停止脚本](../Demo/back-end/java/stop.sh)

<a id="node.js-端到端-demo"></a>
### 4.7 Node.js / PHP Demo

Node.js 和 PHP Demo 遵循同一预下单流程：服务端签名、调用 `POST /dspay/public/order/create`、跳转 `checkoutUrl`、验证回调、主动查询。

- [Node.js Demo 使用说明](../Demo/back-end/nodejs/README.zh-CN.md)
- [Node.js 服务端 `server.js`](../Demo/back-end/nodejs/src/server.js)
- [Node.js 签名与验签 `signer.js`](../Demo/back-end/nodejs/src/signer.js)
- [PHP Demo 使用说明](../Demo/back-end/php/README.zh-CN.md)

### 4.8 创建失败排查表

| 错误码 | 原因 | 排查方向 |
|---|---|---|
| `40001` | 参数校验失败 | 检查字段长度、金额格式、URL 和支付方式数组 |
| `40901` | 商户订单号已被使用 | 检查是否复用 `outOrderNo` 且业务字段发生变化 |
| `50501` | 商户不存在 | 检查 `merchantNo` |
| `50503` | apiSecret 已冻结 | 在 DSPay 后台检查密钥状态 |
| `50609` | 无可用收款地址 | 为对应网络配置已启用收款地址 |
| `50613` | 请求签名无效 | 检查字段顺序、空字段、JSON/数组规范化和密钥 |
| `50614` | 请求时间戳过期 | 检查服务器 NTP；时间差绝对值不得超过 5 分钟 |

### 4.9 ⚠️ 坑点

1. **所有创建业务字段都参与签名**：包括 `productId`、`attach`、支付方式限制和两个跳转 URL；可选字段未传也保留空 key。
2. **金额不要使用浮点数**：Java 使用字符串构造的 `BigDecimal` 和 `toPlainString()`；Node.js/PHP 保留原十进制字符串。
3. **签名时间窗不是订单有效期**：5 分钟只控制请求防重放；订单从创建开始有独立 10 分钟支付期。
4. **创建成功不代表已锁定支付方式**：链上检测和补单只处理已锁定支付方式的订单。
5. **幂等重试不能改变业务字段**：否则返回 `40901`，不会覆盖已有订单。
6. **不要根据页面跳转发货**：只信任验签通过的 `COMPLETED` 回调，或商户服务端查询到的 `COMPLETED`。

---

[↑ 返回目录](#目录)

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
| `CREATED` | 订单已创建；可能尚未确认支付方式，也可能已锁定 | ❌ 不发送 | 支付字段完整时扫描 | 支付字段完整时允许 |
| `TIMEOUT` | 创建后 10min 未完成支付（仍可继续等待到账） | ❌ 不发送 | 支付字段完整时扫描 | 支付字段完整时允许 |
| `CLOSED` | 40min 未支付，系统关闭 | ✅ 发 `CLOSED` | ❌ **停止** | ✅（重开，`reopened=true`） |
| `COMPLETED` | 链上到账 / 补单完成 | ✅ 发 `COMPLETED` | — | — |
| `REFUNDED` | 商户退款成功 | ✅ 发 `REFUNDED` | — | — |

> 当前仅在订单进入 `CLOSED` / `COMPLETED` / `REFUNDED` 时发送回调。用户侧待支付状态和倒计时由 DSPay 收银台展示；商户后端如需主动跟踪状态，使用[主动查询接口](#商户主动查询订单回调兜底)，不要等待 `CREATED` / `TIMEOUT` 回调。

**三个关键行为（容易被忽略）**：

1. **TIMEOUT 不发回调**：10 分钟未付只推进订单状态，订单仍继续等待链上到账并接受自动检测。订单最终走向 CLOSED（40min）或 COMPLETED（链上到账/补单）。商户不要依赖回调感知 TIMEOUT。
2. **未确认支付方式的订单不进入检测或补单**：[DSPay](#term-dspay) 只扫描支付字段完整的 `CREATED` / `TIMEOUT` 订单；缺少支付方式时，补单返回“缺少支付方式，该订单不可补单”。订单进入 `CLOSED` 后自动检测停止，后续到账需商户在后台核实并补单（`reopened=true` 路径）。
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

**字段说明**：

| 字段 | 类型 | 必返 | 允许 null | 说明 |
|------|------|------|-----------|------|
| `orderNo` | string | 是 | 否 | 订单号，如 `DS2024...` |
| `outOrderNo` | string | 是 | 否 | 商户外部订单号（创建订单时必传，原样回传） |
| `attach` | object | 条件 | 否 | 创建订单时传入则原样回传；未传时省略该字段，不返回 `null` |
| `eventType` | string | 是 | 否 | 事件类型：`CLOSED` / `COMPLETED` / `REFUNDED` |
| `status` | string | 是 | 否 | 订单当前状态枚举 |
| `payAmount` | string | 是 | 是 | 最终应付金额（含尾数，Decimal 字符串）；未确认支付方式就关闭时为 `null` |
| `originPayAmount` | string | 是 | 否 | 商户创建订单时提交的原始应付金额（不含尾数） |
| `amountSuffix` | string | 是 | 否 | 订单识别尾数；未产生尾数时为 `"0"` |
| `actualReceivedAmount` | string | 是 | 是 | 实际到账金额；尚未确认到账时为 `null` |
| `actualUsdAmount` | string | 是 | 是 | 实际到账 USD 价值；尚未确认到账时为 `null` |
| `refundAmount` | string | 是 | 是 | 退款代币金额；仅 `REFUNDED` 事件有值，其他事件为 `null` |
| `refundUsdAmount` | string | 是 | 是 | 退款 USD 价值；仅 `REFUNDED` 事件有值，其他事件为 `null` |
| `refundTxHash` | string | 是 | 是 | 退款交易哈希；仅 `REFUNDED` 事件有值，其他事件为 `null` |
| `txHash` | string | 是 | 是 | 链上交易哈希；尚未确认到账时为 `null` |
| `tokenSymbol` | string | 是 | 是 | 已选代币符号，如 `USDT`；未确认支付方式就关闭时为 `null` |
| `contractAddress` | string | 是 | 是 | 已选代币合约地址；未确认支付方式或选择原生币时为 `null` |
| `receivingAddress` | string | 是 | 是 | 用户确认支付方式后锁定的商户收款地址；未确认支付方式时为 `null` |
| `networkId` | string | 是 | 是 | 已选网络 ID，如 `evm--1`；未确认支付方式就关闭时为 `null` |
| `chainName` | string | 是 | 是 | 已选链显示名称；未确认支付方式就关闭时为 `null`。有值时与 [`GET /dspay/public/supported-chains`](#查询支持链代币白名单) 返回值一致 |
| `reopened` | boolean | 是 | 否 | 是否为 `CLOSED` 后由商户手动补单重开的路径 |
| `timestamp` | long | 是 | 否 | [DSPay](#term-dspay) 发送时间戳（Unix 毫秒） |

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
        if (payload == null || secret == null || signature == null
                || !signature.matches("(?i)^[0-9a-f]{64}$")) {
            return false;
        }
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
    if (typeof signature !== 'string' || !/^[0-9a-f]{64}$/i.test(signature)) {
        return false;
    }
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
        // 不使用 Math.abs(now - timestamp)，避免 Long.MIN_VALUE 溢出绕过校验。
        return timestamp >= 0
                && timestamp <= now + TOLERANCE_MS
                && now - timestamp <= TOLERANCE_MS;
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
        // 幂等记录和业务处理必须位于同一个数据库事务中。
        // INSERT IGNORE：重复插入返回 affected rows = 0。
        int affected = jdbc.update(
            "INSERT IGNORE INTO notify_processed (order_no, event_type) VALUES (?, ?)",
            payload.getOrderNo(), payload.getEventType()
        );
        if (affected == 0) {
            // 重复回调，直接 ACK，避免 DSPay 持续重试
            return;
        }
        // 首次处理；异常会回滚业务数据和幂等记录，允许 DSPay 重试。
        fulfillOrder(payload.getOrderNo());
    } catch (Exception e) {
        // 继续抛出，不返回 SUCCESS。
        throw e;
    }
}
```

#### 推荐实现 B：Redis SETNX

```java
public void handleNotify(NotifyPayload payload) throws Exception {
    String key = "notify:" + payload.getOrderNo() + ":" + payload.getEventType();
    // PROCESSING 使用短 TTL，避免进程崩溃后占位长期不释放。
    boolean firstTime = redis.opsForValue()
            .setIfAbsent(key, "PROCESSING", Duration.ofMinutes(5));
    if (!firstTime) {
        // 重复回调，直接 ACK，避免 DSPay 重试
        return;
    }
    try {
        // 首次处理，执行业务逻辑。
        fulfillOrder(payload.getOrderNo());
        // 成功后转为 DONE，保留时间覆盖完整回调重试周期；建议至少 30 天。
        redis.opsForValue().set(key, "DONE", Duration.ofDays(30));
    } catch (Exception e) {
        // 业务失败时释放占位，允许 DSPay 下一次重试重新处理。
        redis.delete(key);
        throw e;
    }
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

> ⚠️ **先前事件停止重试**：若发送过程中订单状态变化（如 `CLOSED` 通知重试时订单已被补单为 `COMPLETED`），[DSPay](#term-dspay) 会停止发送先前事件，避免事件类型与最新订单状态冲突，并另行发送新状态事件（如 `COMPLETED` + `reopened=true`）。

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

```http
POST /dspay/public/order/query
Content-Type: application/json
```

| 字段 | 必填 | 签名 | 说明 |
|---|---:|---:|---|
| `merchantNo` | 是 | 是 | 商户编号；不能为空；最多 32 个字符 |
| `orderNo` | 条件 | 非空时 | 与 `outOrderNo` 至少一个非空；最多 64 个字符 |
| `outOrderNo` | 条件 | 非空时 | 与 `orderNo` 至少一个非空；最多 64 个字符 |
| `timestamp` | 是 | 是 | Unix 毫秒时间戳；与服务端时间差绝对值不得超过 300000 毫秒 |
| `signature` | 是 | 否 | HMAC-SHA256 小写十六进制字符串，固定 64 个字符 |

同时传两个订单号时按 AND 匹配。非空字段按以下顺序签名，空可选字段不进入签名串：

```text
merchantNo -> orderNo（如有） -> outOrderNo（如有） -> timestamp
```

```text
merchantNo=DSM2080260022215368706&orderNo=1949695024925671424&timestamp=1787292500000
```

公共接口使用统一响应包；顶层 `code = 0` 表示成功，订单数组位于 `data`，未查到时 `data=[]`。

| 字段 | 必返 | 说明 |
|---|---:|---|
| `orderNo` | 是 | DSPay 订单号 |
| `outOrderNo` | 是 | 商户订单号 |
| `createAt` | 是 | 创建时间，Unix 毫秒 |
| `status` | 是 | 当前订单状态 |
| `originPayAmount` | 是 | 创建时提交的原始金额 |
| `amountSuffix` | 是 | 识别尾数；未 Pay Now 或无尾数时为 `0` |
| `payAmount` | 否 | 最终应付金额；Pay Now 后返回 |
| `networkId` | 否 | 已锁定网络；Pay Now 后返回 |
| `tokenAddress` | 否 | 已锁定代币合约或 mint 地址 |
| `receivingAddress` | 否 | 本订单锁定的商户收款地址 |
| `payerAddress` | 否 | 链上检测到的付款地址 |
| `txHash` | 否 | 到账交易哈希 |
| `txLink` | 否 | 到账交易浏览器链接 |
| `productPrice` | 否 | 创建时传入的商品法币价格 |
| `productPriceCurrency` | 否 | 商品价格币种 |
| `productId` | 否 | 商户产品 ID |
| `attach` | 否 | 创建时传入的附加 JSON，原样回传 |
| `actualReceivedAmount` | 否 | 实际到账代币金额 |
| `paidSource` | 否 | `CHAIN_DETECTION` 或 `SUPPLEMENT` |
| `paidAt` | 否 | 检测到付款时间，Unix 毫秒 |
| `completedAt` | 否 | 完成时间，Unix 毫秒 |
| `refundTxHash` | 否 | 退款交易哈希 |
| `refundTxLink` | 否 | 退款交易浏览器链接 |
| `refundAmount` | 否 | 退款代币金额 |
| `refundAt` | 否 | 退款完成时间，Unix 毫秒 |
| `refundRemark` | 否 | 退款备注 |

不返回 `usdAmount/actualUsdAmount/refundUsdAmount/amountDiff/statusDesc/tokenSymbol`。
查询建议：

- 正常路径以回调为主；仅在未收到回调、用户从跳转页返回或对账时主动查询。
- 使用退避间隔，不要固定高频轮询；每次查询重新生成 `timestamp` 和 `signature`。
- 查询到目标状态后停止轮询；无论回调还是查询，本地状态更新都必须幂等。


### 5.12 ⚠️ 坑点（6 条）

1. **raw body 验签**：用 HTTP body 原始字符串，不能反序列化后重新 `JSON.stringify()`（字段顺序变化 → 签名不一致）。Java Web框架可直接接收原始请求体；本仓库Node.js Demo使用内置HTTP请求流累积原始字节，不需要安装 `raw-body` 或其他npm包。这是回调验签失败**最常见**的原因。

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

4. **先前事件可能停止重试**：发送过程中订单状态变化（如 `CLOSED` 重试时订单被补单为 `COMPLETED`），DSPay 会取消先前事件发送，另行发送新状态事件。商户只需按 `orderNo + eventType` 幂等处理收到的事件。

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
- 已在超时前确认支付方式的订单仍会继续链上检测，直至 CLOSED
- 始终未确认支付方式的订单不能在超时后重新启动支付
- 配置 `returnUrl` 时，DSPay 收银台在 TIMEOUT 场景跳转该地址；未配置时停留超时页

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
- 测试代币：钱包至少准备 0.02 [USDT](#term-usdt)，用于覆盖 0.01 USDT 测试订单及识别尾数；另备足够的链上 Gas 代币

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
2. **端到端创建订单**：用 [§4.6 / §4.7](#java-端到端-demo) 的 Demo 创建订单，确认返回 `orderNo` + `checkoutUrl`，并能打开收银台
3. **触发回调验签**：用真实代币付款，触发 `COMPLETED` 回调，验签通过
4. **测试幂等性**：手动重发同一回调请求，确认商户后端不会重复发货

### 8.4 测试代币

各链主网小额稳定币测试：
- Ethereum：至少 0.02 [USDT](#term-usdt)（`0xdac17f958d2ee523a2206206994597c13d831ec7`），另备 ETH 支付 Gas
- BSC：至少 0.02 [USDT](#term-usdt)（`0x55d398326f99059fF775485246999027B3197955`），另备 BNB 支付 Gas
- Tron：至少 0.02 [USDT](#term-usdt)（`TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t`），另备 TRX 或足够能量/带宽

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
A: 常见原因是字段顺序、空字段、Decimal、`attach` 或 `allowedPaymentMethods` 规范化不一致。Decimal 必须使用普通十进制形式，例如 Java 使用 `BigDecimal.toPlainString()`；再逐项核对 [§4.4](#签名规范化字符串) 的完整字段顺序。

**Q: Node.js 金额精度怎么处理？**
A: `productPrice` 和 `payAmount` 用字符串字面量 `'99.99'` 或 Big.js，不能用 JS number。JS number 超过 `Number.MAX_SAFE_INTEGER` 或极小 decimal 会进入科学计数法。

**Q: [apiSecret](#term-apisecret) 需要 Base64 解码后使用吗？**
A: **不需要**。[apiSecret](#term-apisecret) 字符串直接作为 HMAC key 使用。Base64Url 只是密钥的存储编码，[HMAC-SHA256](#term-hmac-sha256) 对 key 字节序列没有格式要求。`secret.getBytes(UTF_8)` 直接传给 `SecretKeySpec`。

**Q: 签名字段顺序错了会怎样？**
A: 签名不一致 → [`50613`](#error-50613)。必须严格按 `merchantNo → outOrderNo → productPrice → productPriceCurrency → productId → attach → payAmount → allowedPaymentMethods → returnUrl → successRedirectUrl → timestamp` 拼接。所有业务字段参与签名，可选字段未传时仍保留 `key=`。

### 9.3 订单类

**Q: 收银台显示的 payAmount 为什么不是创建请求中的 originPayAmount？**
A: 尾数机制。创建接口先记录原始金额；用户确认支付方式时，[DSPay](#term-dspay) 分配识别尾数，最终应付金额可能变为 100.001。用户必须按收银台显示金额付款，商户以回调或查询结果对账。

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

**Q: 先前事件重试期间订单状态变化会怎样？**
A: [DSPay](#term-dspay) 会停止发送先前事件，并另行发送新状态事件。例如 `CLOSED` 重试期间订单被补单为 `COMPLETED`，`CLOSED` 停止发送，随后发送 `COMPLETED`。商户按 `orderNo + eventType` 幂等处理即可。

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
A: 平台白名单定义 DSPay 支持哪些支付方式；商户已启用地址定义本商户能在哪些网络收款；创建请求的 `allowedPaymentMethods` 可进一步缩小用户选择范围。收银台展示三者交集。

**Q: [`50503`](#error-50503) API_SECRET_DISABLED 怎么处理？**
A: 密钥已被冻结。如果是自己冻结的，排查完成后在后台恢复密钥；如果是他人操作，联系商户管理员。

---

[↑ 返回目录](#目录)

<a id="附录-a-java-综合接入示例"></a>
## 附录 A：Java 综合接入示例

Java 接入只维护一份权威实现：[`Demo/back-end/java`](../Demo/back-end/java/README.zh-CN.md)。该 Demo 同时覆盖服务端预下单签名、调用公共创建接口、跳转 `checkoutUrl`、回调 Raw Body 验签、严格 ACK 和主动查询。

| 文件 | 用途 |
|------|------|
| [`README.zh-CN.md`](../Demo/back-end/java/README.zh-CN.md) | 环境要求、配置、启动方式、接口与签名规则 |
| [`src/DspayMockMerchant.java`](../Demo/back-end/java/src/DspayMockMerchant.java) | 创建/查询签名、公共 API 调用、收银台跳转、回调验签与严格响应 |
| [`start.sh`](../Demo/back-end/java/start.sh) / [`stop.sh`](../Demo/back-end/java/stop.sh) | 后台进程启停 |

创建流程为：**商户后端签名 → 调用公共创建接口 → 获取 `checkoutUrl` → 跳转用户**。用户在收银台选择链和代币后确认支付方式。

> 附录不再复制源码。只需维护权威 Demo，此处始终引用最新实现。

---

[↑ 返回目录](#目录)

<a id="附录-b-nodejs-综合接入示例"></a>
## 附录 B：Node.js 综合接入示例

Node.js 接入只维护一份权威实现：[`Demo/back-end/nodejs`](../Demo/back-end/nodejs/README.zh-CN.md)。该 Demo 仅使用 Node.js 内置模块，同时覆盖服务端预下单、收银台跳转、主动查询和回调验签。

| 文件 | 用途 |
|------|------|
| [`README.zh-CN.md`](../Demo/back-end/nodejs/README.zh-CN.md) | 环境要求、配置、启动方式、接口与签名规则 |
| [`src/server.js`](../Demo/back-end/nodejs/src/server.js) | HTTP 路由、公共创建/查询 API 调用、302 跳转与 Raw Body 回调处理 |
| [`src/signer.js`](../Demo/back-end/nodejs/src/signer.js) | 创建订单签名与常量时间回调验签 |
| [`package.json`](../Demo/back-end/nodejs/package.json) | 启动命令与运行时信息 |

创建流程为：**商户后端签名 → 调用公共创建接口 → 获取 `checkoutUrl` → 跳转用户**。用户在收银台选择链和代币后确认支付方式。

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
| <a id="error-40901"></a>40901 | STATE_CONFLICT | 商户订单号已被使用；同一商户复用 `outOrderNo` 但请求业务字段不一致 |
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

#### SIWE 签名认证相关（509xx）

| code | msg | 说明 |
|------|------|------|
| <a id="error-50901"></a>50901 | SIWE_NONCE_NOT_FOUND | [SIWE](#term-siwe) nonce 不存在 |
| <a id="error-50902"></a>50902 | SIWE_NONCE_EXPIRED | [SIWE](#term-siwe) nonce 已过期（TTL 5 分钟） |
| <a id="error-50903"></a>50903 | SIWE_SIGNATURE_INVALID | [SIWE](#term-siwe) 签名无效（ecrecover 恢复地址不匹配） |
| <a id="error-50904"></a>50904 | SIWE_DOMAIN_MISMATCH | [SIWE](#term-siwe) domain 不匹配 |
| <a id="error-50905"></a>50905 | SIWE_MESSAGE_INVALID | [SIWE](#term-siwe) 消息无效 |

[↑ 返回目录](#目录)
