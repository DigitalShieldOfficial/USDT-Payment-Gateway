[English](README.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

该 Demo 模拟商户接入流程：前端请求商户后端，商户后端签名调用 DSPay 预下单接口，收到 `checkoutUrl` 后再 302 跳转用户。Demo 不会在浏览器或收银台 URL 中暴露 `apiSecret` 和订单签名参数。

## 结构

```text
Demo/
├── front-end/index.html
└── back-end/
    ├── nodejs/   Node.js 18.20.8，零 npm 依赖
    ├── java/     JDK 8+，零外部依赖
    └── php/      PHP 5.6+，无需 Composer
```

## 已验证运行环境

| Demo | 最低版本 | 已验证版本 | 依赖说明 |
|------|----------|------------|----------|
| Node.js | Node.js `18.20.8` | Node.js `18.20.8` + npm `10.8.2` | 零 npm 依赖；提供 `.nvmrc` |
| Java | JDK 8 | 已在 Corretto `1.8.0_504` 与 Temurin `21.0.11` 验证 | 零 Maven/Gradle 依赖 |
| PHP | PHP 5.6 | PHP CLI `5.6.40`、`8.5.10` | 无需 Composer |
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

1. 前端展示可编辑的 Order ID（`outOrderNo`），刷新按钮每次生成新号；点击 Pay Now 时，使用当前填写的订单号请求本地商户后端 `GET /create`。每笔新订单须使用新号，仅重试同一笔订单时复用原号和相同业务字段。
2. 商户后端使用传入的 `outOrderNo`（未传时生成），构造完整创建请求并计算 HMAC。
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
npm start        # 等价于 node src/server.js
```

三种语言后端都会直接托管商店页：执行 `./start.sh` 后，浏览器打开 `http://localhost:3000`（即 `PUBLIC_BASE_URL`）点击 Pay Now 即可——页面与接口同源，无需额外静态托管。本地也可以直接双击打开 `Demo/front-end/index.html`（页面会自动回退请求 `http://localhost:3000`）。可选环境变量 `FRONT_END_DIR` 可指定其他前端目录（默认 `../../front-end`）。

本地回调需要公网可访问地址。可用 ngrok 等工具代理 3000 端口，然后把 `PUBLIC_BASE_URL` 和商户后台 `notifyUrl` 改为对应公网地址。

## Java 启动

```bash
cd Demo/back-end/java
mkdir -p build && javac -d build src/DspayMockMerchant.java
java \
  -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" \
  -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -DdspayBase="https://REPLACE_WITH_REAL_DSPAY_API_HOST" \
  -DpublicBase="http://localhost:3000" \
  -cp build DspayMockMerchant
```

## PHP 启动

> 把下面三个 `REPLACE_WITH_REAL_*` 占位值（含 API 地址）替换为商户后台的真实参数。不要复制 Markdown 链接语法到 Shell 命令中。

```bash
cd Demo/back-end/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST"
export PUBLIC_BASE_URL="http://localhost:3000"
php -S 0.0.0.0:3000 server.php
# 后台运行可选：./start.sh（缺变量时交互提示，./stop.sh 停止）
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
- 每笔新订单生成新的 `outOrderNo`；仅同一笔订单的网络超时重试复用原 `outOrderNo` 和相同业务字段。HTTP 请求还需配置连接/读取超时和有限重试。
- `returnUrl`和`successRedirectUrl`均为可选字段；`returnUrl`仅用于订单超时，`successRedirectUrl`仅用于订单完成。未配置对应URL时，DSPay停留当前页面。
- `checkoutUrl`在订单创建180天后不再允许查看，不能作为永久订单详情入口。
- 回调先验签，再幂等更新本地订单，事务成功后才返回 `{"code":"SUCCESS"}`。
- 不根据信任前端跳转或 URL 发货，只信任验签回调或服务端查询的 `COMPLETED`。
- 示例 `attach` 只包含非敏感标识；禁止传密码、私钥和证件数据。
