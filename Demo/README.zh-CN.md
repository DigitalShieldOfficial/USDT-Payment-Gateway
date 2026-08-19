## 语言
[English](README.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

模拟商户系统（Mock Merchant），用于快速体验 DSPay 支付流程 —— 商户前端发起支付 → 商户后端本地签名并拼接收银台链接 → 跳转 DSPay 收银台 → 支付回调通知。

## 项目结构

```
dspay-mock-merchant/
├── back-end/                  # 模拟商户后端
│   ├── java/                  # Java 11+ 实现（零外部依赖）
│   │   └── src/DspayMockMerchant.java
│   ├── php/                   # PHP 5.6+ 实现（无需 Composer）
│   │   ├── server.php
│   │   └── start.sh
│   └── nodejs/                # Node.js 18+ 实现（零 npm 依赖）
│       └── src/
│           ├── server.js
│           └── signer.js
└── front-end/                 # 模拟商户前端（纯静态 HTML）
    └── index.html
```

## 整体联动流程

```
                                DSPay
┌─────────────────────┐     ┌──────────────┐
│ DSPay 后台           │     │  DSPay 收银台  │
│                     │     │              │
│ ① 注册商户          │     │ ④ 用户完成    │
│ ② 获取 merchantNo   │     │   支付        │
│    生成 apiSecret    │     │              │
└────────┬────────────┘     └──────▲───────┘
         │                         │
    提供 merchantNo         ⑤ 异步通知
     + apiSecret             POST /notify
         │                         │
         ▼                         │
┌──────────────────────────────────────────────┐
│              模拟商户系统                     │
│                                              │
│  ┌─────────────┐         ┌──────────────┐   │
│  │  后端        │◄────────│   前端        │   │
│  │  GET /create │  ③ 点击 │  index.html  │   │
│  │  POST /notify│  Pay Now│              │   │
│  │              │────────►│              │   │
│  │              │ 302跳转  │              │   │
│  └──────────────┘  收银台  └──────────────┘   │
│   Java、Node.js 或 PHP                         │
└──────────────────────────────────────────────┘
```

### 流程说明

| 步骤 | 描述 |
|------|------|
| ① | 前往 [DSPay 后台](https://mcashier.ds.pro/login/)注册成为商户 |
| ② | 在商户管理台 [账户页面](https://mcashier.ds.pro/account) 获取 `merchantNo`，在 [设置页面](https://mcashier.ds.pro/settings) 获取「支付 Key」即 `apiSecret` |
| ③ | 将 `merchantNo` 和 `apiSecret` 替换后端代码中的占位符并启动后端；点击「Pay Now」后，后端本地签名并返回 302 收银台跳转，不调用创建订单 API |
| ④ | HTTP 302 跳转到 DSPay 收银台，用户完成支付 |
| ⑤ | DSPay 异步回调 `POST /notify`，后端验签后记录支付结果 |

## 快速开始

### 前置条件：注册商户并获取凭证

1. 打开 [DSPay 后台](https://mcashier.ds.pro/login/)注册成为商户
2. 注册后进入 [账户页面](https://mcashier.ds.pro/account) 复制你的 **商户编号（merchantNo）**
3. 进入 [设置页面](https://mcashier.ds.pro/settings)，在「支付 Key」栏目中获取 **API 密钥（apiSecret）**

> :warning: `merchantNo` 和 `apiSecret` 是敏感凭证，请妥善保管。

### 选择语言版本启动后端

后端提供 Java、Node.js 和 PHP 三种实现，功能完全等价，选择你熟悉的一种即可。

#### 选项 A：Node.js（推荐，启动最快）

```bash
# 1. 进入 Node.js 后端目录
cd back-end/nodejs

# 2. 设置你的商户凭证（替换占位符）
export MERCHANT_NO="你的merchantNo"
export API_SECRET="你的apiSecret"

# 3. 启动后端（监听 localhost:3000）
node src/server.js
```

> 或直接编辑 `src/server.js` 第 62-63 行，将 `change-me-to-your-merchantNo` 和 `change-me-to-your-apiSecret` 替换为真实值后启动。

#### 选项 B：Java

```bash
# 1. 进入 Java 后端目录
cd back-end/java

# 2. 设置商户凭证并启动（JDK 11+）
java -DmerchantNo="你的merchantNo" -DapiSecret="你的apiSecret" DspayMockMerchant.java
```

> 或直接编辑 `src/DspayMockMerchant.java` 第 47-50 行，将占位符替换为真实值后启动。

#### 选项 C：PHP（无需 Composer）

PHP Demo 已包含所需源码，不需要安装或使用 Composer。

```bash
# 1. 进入 PHP 后端目录
cd back-end/php

# 2. 设置商户凭证并启动（PHP 5.6+，监听 localhost:3000）
MERCHANT_NO="你的merchantNo" API_SECRET="你的apiSecret" ./start.sh
```

也可以直接运行：`php -S localhost:3000 server.php`。详细说明见 [PHP Demo README](back-end/php/README.zh-CN.md)。

### 启动前端

用浏览器直接打开 `front-end/index.html`，你将看到 Nova Store 模拟商品页面。

### 发起支付

在前端页面点击 **「Pay Now」** 按钮：

1. 前端请求 `GET http://localhost:3000/create`（携带商品参数）
2. 后端生成签名订单，返回 HTTP 302 跳转到 DSPay 收银台
3. 在收银台完成支付
4. DSPay 异步回调 `POST http://localhost:3000/notify`，后端验签并记录日志

## API 说明

### GET /create

创建订单并跳转收银台。

| 参数 | 必填 | 说明 |
|------|------|------|
| `payAmount` | 否 | 支付金额，默认 `0.01`；必须为大于 0、最多 2 位小数的普通十进制字符串 |
| `productPrice` | 否 | 商品价格，默认 `0.01` |
| `productPriceCurrency` | 否 | 币种，默认 `USD` |
| `productId` | 否 | 商品 ID，默认 `NOVA-LIFETIME-001` |

> **`payAmount` 精度限制：** 稳定币统一按 6 位精度处理。商户最多提交 2 位小数，后 4 位由 DSPay 生成订单尾数。`100`、`100.1`、`100.12` 合法，`100.123` 不合法。必须使用普通十进制字符串，不能传数字或科学计数法。超过 2 位小数时返回 [`50612`](../SDK/SDK.zh-CN.md#error-50612)。详见 [订单尾数机制](../SDK/SDK.zh-CN.md#订单尾数机制详解)。

响应：HTTP 302 重定向到 DSPay 收银台（附带签名参数）。

### POST /notify

接收 DSPay 支付回调。后端使用 `apiSecret` 对回调 body 做 HMAC-SHA256 验签，验签通过后记录支付结果日志。

## 后续扩展




`back-end/` 目录计划支持更多语言版本（Python、Go 等），欢迎贡献。
