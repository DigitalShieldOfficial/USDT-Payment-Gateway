[English](README.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

该 Demo 模拟商户接入流程：前端请求商户后端，商户后端签名调用 DSPay 预下单接口，收到 `checkoutUrl` 后再 302 跳转用户。Demo 不会在浏览器或收银台 URL 中暴露 `apiSecret` 和订单签名参数。

## 结构

```text
Demo/
├── front-end/index.html
└── back-end/
    ├── nodejs/   Node.js 18.20.8，零 npm 依赖
    ├── java/     JDK 11+，零外部依赖
    └── php/      PHP 5.6+，无需 Composer
```

## 已验证运行环境

| Demo | 最低版本 | 已验证版本 | 依赖说明 |
|------|----------|------------|----------|
| Node.js | Node.js `18.20.8` | Node.js `18.20.8` + npm `10.8.2` | 零 npm 依赖；提供 `.nvmrc` |
| Java | JDK 11 | Microsoft OpenJDK `11.0.27`；Temurin `21.0.11` | 零 Maven/Gradle 依赖 |
| PHP | PHP 5.6 | PHP CLI `5.6.40` | 无需 Composer |
| 前端 | 支持 `crypto.randomUUID()` 的现代浏览器 | Chrome `151.0.7922.175` | 单文件 HTML，无构建步骤 |

验证操作系统：macOS `15.1`、Docker Linux。最低版本和已验证版本含义不同：最低版本是源码兼容基线，已验证版本是仓库测试实际运行过的版本。

运行前可先确认本机版本：

```bash
node --version && npm --version
java -version && javac -version
php --version
```

版本低于表中最低版本时不保证可以运行；Demo 未使用 Express、Spring Boot、Laravel 等框架，因此没有对应框架版本要求。

Node.js相关文档和代码全部使用同一基线，不需要安装或切换多个Node版本：

```bash
cd Demo/back-end/nodejs
nvm install
nvm use
npm test
```

## 流程

1. 前端点击 Pay Now，请求本地商户后端 `GET /create`。
   前端在当前会话内复用同一个 `outOrderNo`，用于演示创建接口幂等重试。
2. 商户后端生成唯一 `outOrderNo`，构造完整创建请求并计算 HMAC。
3. 商户后端调用 `POST /dspay/public/order/create`。
4. DSPay 返回 `orderNo` 和 `checkoutUrl`。
5. 商户后端 302 跳转到 `checkoutUrl`。
6. 用户在 DSPay 收银台选币并确认 Pay Now，随后链上付款。
7. DSPay 调用本地 `/notify`；Demo 使用 Raw Body 验签。
8. 超时返回页、成功页均不能直接视为支付凭证，商户需调用 `/dspay/public/order/query` 二次确认。

## Node.js 启动

> 以下命令中的 `REPLACE_WITH_REAL_MERCHANT_NO`、`REPLACE_WITH_REAL_API_SECRET` 和 `REPLACE_WITH_REAL_DSPAY_API_HOST` 都是占位值，执行前必须替换为真实参数。`merchantNo` 和 `apiSecret` 从DSPay商户后台获取。

```bash
cd Demo/back-end/nodejs
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST"
export PUBLIC_BASE_URL="http://localhost:3000"
node src/server.js
```

打开 `Demo/front-end/index.html`，点击 Pay Now。

本地回调需要公网可访问地址。可用 ngrok 等工具代理 3000 端口，然后把 `PUBLIC_BASE_URL` 和商户后台 `notifyUrl` 改为对应公网地址。

## Java 启动

```bash
cd Demo/back-end/java
java \
  -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" \
  -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -DdspayBase="https://REPLACE_WITH_REAL_DSPAY_API_HOST" \
  -DpublicBase="http://localhost:3000" \
  src/DspayMockMerchant.java
```

## PHP 启动

```bash
cd Demo/back-end/php
MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO" API_SECRET="REPLACE_WITH_REAL_API_SECRET" \
DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST" \
PUBLIC_BASE_URL="http://localhost:3000" ./start.sh
```

## 本地接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/create` | 服务端创建 DSPay 订单并 302 跳转 `checkoutUrl` |
| GET | `/query?orderNo=...` | Node.js/PHP Demo 主动查询 DSPay 订单 |
| POST | `/notify` | 接收回调并使用 Raw Body 验签 |
| GET | `/payment/return` | 订单超时返回页；Node.js/PHP Demo 会继续调用查询接口 |
| GET | `/payment/success` | 成功跳转页；Node.js/PHP Demo 会继续调用查询接口 |

## 生产实现注意

- `apiSecret` 存入 KMS/密钥管理服务，不写死在代码中。
- HTTP 请求配置连接/读取超时和有限重试；重试复用同一 `outOrderNo`。
- `returnUrl`和`successRedirectUrl`均为可选字段；`returnUrl`仅用于订单超时，`successRedirectUrl`仅用于订单完成。未配置对应URL时，DSPay停留当前页面。
- `checkoutUrl`在订单创建180天后不再允许查看，不能作为永久订单详情入口。
- 回调先验签，再幂等更新本地订单，事务成功后才返回 `{"code":"SUCCESS"}`。
- 不根据信任前端跳转或 URL 发货，只信任验签回调或服务端查询的 `COMPLETED`。
- 示例 `attach` 只包含非敏感标识；禁止传密码、私钥和证件数据。
