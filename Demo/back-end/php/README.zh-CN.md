[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP Mock Merchant

可直接运行的 Digital Shield pay模拟商户后端。SDK 源码已包含在 `src/` 中，不需要安装或使用 Composer。

## 环境要求

- PHP 5.6+
- PHP `hash` 和 `json` 扩展（通常默认启用）

## 快速启动

```bash
cd Demo/back-end/php

MERCHANT_NO="你的merchantNo" \
API_SECRET="你的apiSecret" \
./start.sh
```

服务默认监听 `http://localhost:3000`。然后直接用浏览器打开 `Demo/front-end/index.html`，点击 **Pay Now** 即可发起支付。

也可以不使用脚本，直接启动：

```bash
MERCHANT_NO="你的merchantNo" API_SECRET="你的apiSecret" \
php -S localhost:3000 server.php
```

使用其他端口：

```bash
PORT=4000 MERCHANT_NO="你的merchantNo" API_SECRET="你的apiSecret" ./start.sh
```

> `merchantNo` 和 `apiSecret` 是敏感凭证。Demo 仅从服务端环境变量读取，不接受请求参数传入凭证。

## 接口

### `GET /create`

创建签名订单并通过 HTTP 302 跳转到 DSPay 收银台。HTTP 路由会在商户后端生成非空 `outOrderNo`，再传给 `Payment::createOrder()`。查询参数 `payAmount`、`productPrice`、`productPriceCurrency`、`productId` 可选，未传时使用 Demo 默认值。

```bash
curl -i 'http://localhost:3000/create?payAmount=0.01&productId=DEMO-001'
```

**处理流程：**

1. 商户后端生成唯一、非空且不超过 64 字符的 `outOrderNo`
2. 按 `merchantNo → outOrderNo → payAmount → timestamp` 固定顺序计算 HMAC-SHA256
3. 将 `outOrderNo` 同时写入签名规范化字符串和收银台 URL
4. 返回 HTTP 302 跳转收银台

> `outOrderNo` 是必填字段，字段名区分大小写，必须写作 `outOrderNo`，不是 `outOrderNO`。直接调用 `Payment::createOrder()` 时必须显式传入；缺失、纯空白或超过 64 字符会抛出 `RequestBuilderException`。

> **`payAmount` 精度限制：** 稳定币统一按 6 位精度处理。商户最多提交 2 位小数，后 4 位由 DSPay 生成订单尾数。`100` / `100.1` / `100.12` 合法，`100.123` 不合法。必须以大于 0 的普通十进制字符串传入，禁止使用 PHP `float` 或科学计数法。超过 2 位小数时返回 [`50612`](../../../SDK/SDK.zh-CN.md#error-50612)。

### `POST /notify`

接收 DSPay 回调，使用原始请求体和 `X-DSPay-Signature` 请求头完成 HMAC-SHA256 验签。成功时返回：

```json
{"code":"SUCCESS","msg":"ok"}
```

## 目录结构

```text
php/
├── server.php              # HTTP 路由：/create、/notify
├── start.sh                # 一键启动脚本
├── src/
│   ├── bootstrap.php       # 本地源码加载，无需 Composer
│   ├── Client.php
│   ├── Payment.php
│   ├── RequestBuilder.php
│   └── RequestBuilderException.php
└── test/                   # 可单独运行的签名示例
```

本地运行示例：

```bash
php test/CreateOrder.php
php test/VerifyCallback.php
php test/ValidateCreateOrder.php
```
