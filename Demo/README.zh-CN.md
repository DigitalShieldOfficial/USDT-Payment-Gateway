[English](README.md) | [中文](README.zh-CN.md)

# DSPay Mock Merchant

该 Demo 模拟商户接入流程：前端请求商户后端，商户后端签名调用 DSPay 预下单接口，收到 `checkoutUrl` 后再 302 跳转用户。Demo 不会在浏览器或收银台 URL 中暴露 `apiSecret` 和订单签名参数。

> **密钥安全**：`apiSecret` 只能保存在商户服务端。禁止将其嵌入前端代码、浏览器存储或 URL，也不要粘贴到第三方在线工具。本地签名和验签测试使用任意编造的本地测试密钥（如 `demo-secret`，非 DSPay 发放，DSPay 无 sandbox 测试密钥体系）；真实密钥只用于商户服务端调用正式 API 和验证真实回调。

## 结构

```text
Demo/
├── frontend/index.html
└── backend/
    ├── nodejs/   Node.js 18.20.8，零 npm 依赖
    ├── java/     JDK 8+，零外部依赖
    ├── php/      PHP 5.6+，无需 Composer
    └── python/   Python 3.10+，仅标准库
```

## 已验证运行环境

| Demo | 最低版本 | 已验证版本 | 依赖说明 |
|------|----------|------------|----------|
| Node.js | Node.js `18.20.8` | Node.js `18.20.8` + npm `10.8.2` | 零 npm 依赖；提供 `.nvmrc` |
| Java | JDK 8 | 已在 Corretto `1.8.0_504`、Microsoft OpenJDK `11.0.27` 与 Temurin `17.0.21` / `21.0.11` 验证 | 零 Maven/Gradle 依赖 |
| PHP | PHP 5.6 | PHP CLI `5.6.40`、`8.5.10` | 无需 Composer |
| Python | Python 3.10 | Python `3.12.14`、`3.14.3` | 仅标准库，无 pip 依赖 |
| 前端 | 支持 `crypto.randomUUID()` 的现代浏览器 | Chrome `151.0.7922.175` | 单文件 HTML，无构建步骤 |

现有 Demo 的历史验证环境包括 macOS `15.1`、Docker Linux；Python 本次在本机使用表中两个版本执行测试。最低版本和已验证版本含义不同：最低版本是源码兼容基线，已验证版本是仓库测试实际运行过的版本。

运行前可先确认本机版本：

```bash
node --version && npm --version
java -version && javac -version
php --version
python3 --version
```

版本低于表中最低版本时不保证可以运行；Demo 未使用 Express、Spring Boot、Laravel 等框架，因此没有对应框架版本要求。

Node.js相关文档和代码全部使用同一基线，不需要安装或切换多个Node版本：

```bash
cd Demo/backend/nodejs
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
7. DSPay 调用本地 `/notify`；Demo 解析回调 JSON，按参数名 ASCII 升序生成规范串后验签，不直接对原始请求体计算 HMAC。
8. 超时返回页、成功页均不能直接视为支付凭证，商户需调用 `/dspay/public/order/query` 二次确认。

## 本地测试与主网联调边界

- **本地签名测试**：Java 执行其指南中的 `DspayMockMerchantTest`；Node.js 执行 `npm test`；PHP 执行 `php test/ValidateCreateOrder.php`；Python 在 `Demo/backend/python` 执行 `python3 -B -m unittest discover -s test -v`。这些测试使用任意编造的本地测试密钥（见上方密钥安全说明），无需商户账号或真实转账。
- **本地回调模拟**：PHP 的 `php test/VerifyCallback.php` 在进程内构造 JSON 和签名，再验证回调。它不调用真实订单接口，也不测试 HTTP 投递、完整防重放、业务幂等或真实订单状态；不能据此发货。
- **Python HTTP 模拟**：Python 测试套件启动本地模拟 DSPay HTTP 服务，验证创建/查询、回调成功与失败、跳转及后台启停，不调用生产 API，详见 [Python 指南](backend/python/README.zh-CN.md)。
- **主网支付联调**：启动商户 Demo，使用默认正式 API，并配置真实商户参数和公网回调地址，再按收银台最终金额支付。此流程涉及真实代币和网络费用，不能用虚拟回调代替。

本仓库未提供公开测试网、测试币水龙头或免转账生成真实支付结果的接口。`supported-chains` 返回当前 API 支持的网络和代币，不代表测试网环境。

四种 Demo 均默认使用 `https://pay-api.ds.pro`，启动脚本不再询问 API 地址；仅测试其他环境时显式设置 `DSPAY_BASE_URL`。Java 也支持 `-DdspayBase`。

## 简单启动（四种 Demo 通用）

启动只需商户号和 API 密钥，不需要配置访问域名。默认监听 `3000`，可用 `PORT=4000 ./start.sh` 更换端口。支付返回链接根据创建订单请求的域名和协议生成；通过 ngrok/nginx 访问时，请从公网地址打开 Demo 并创建订单。真实回调地址为 `<公网地址>/notify`，仍需在商户后台单独登记。

ngrok 示例：`ngrok http 3000`（端口应与 Demo 一致）。nginx 请转发到 `http://127.0.0.1:3000`，设置 `proxy_set_header Host $http_host;` 和 `proxy_set_header X-Forwarded-Proto $scheme;`，并覆盖客户端传入的 `X-Forwarded-Host`：`proxy_set_header X-Forwarded-Host $http_host;`。Demo 仅信任来自本机回环地址的代理头，代理链应提供单个正确的域名和协议，不能传入逗号列表；公网入口应通过该代理访问。

启动成功后会显示监听端口、本地页面、回调地址格式和公网映射步骤。

支付返回链接根据创建订单请求的访问地址生成。真实回调必须使用公网地址，并在商户后台单独登记；localhost 无法接收 DSPay 的远程回调。

维护者安装四种运行环境后，可在仓库根目录执行跨语言启动回归检查：

```bash
python3 -B -m unittest discover -s Demo/test -v
```

## Node.js 启动

> 以下命令中的 `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 是占位值，执行前必须替换为 DSPay 商户后台中的真实参数。DSPay API 地址默认使用正式环境地址 `https://pay-api.ds.pro`，无需填写；仅测试其他环境时覆盖。

```bash
cd Demo/backend/nodejs
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
npm start        # 等价于 node src/server.js
```

四种语言后端都会直接托管商店页：执行 `./start.sh` 后，浏览器打开启动时显示的本地页面或对外访问地址点击 Pay Now 即可——页面与接口同源，无需额外静态托管。本地也可以直接双击打开 `Demo/frontend/index.html`（页面会自动回退请求 `http://localhost:3000`）。可选环境变量 `FRONT_END_DIR` 可指定其他前端目录（默认 `../../frontend`）。

接收真实回调需要公网可访问地址。可用 ngrok 等工具代理 Demo 的监听端口，将公网 `/notify` 地址单独登记到商户后台。

## Java 启动

```bash
cd Demo/backend/java
mkdir -p build && javac -d build src/DspayMockMerchant.java src/DspayTransport.java
java \
  -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" \
  -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -cp build DspayMockMerchant
```

## PHP 启动

> 把下面的 `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 替换为 DSPay 商户后台中的真实参数。DSPay API 地址默认使用正式环境地址 `https://pay-api.ds.pro`，无需填写；仅测试其他环境时覆盖。不要复制 Markdown 链接语法到 Shell 命令中。

```bash
cd Demo/backend/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
php -S 0.0.0.0:3000 server.php
# 后台运行可选：./start.sh（缺变量时交互提示，./stop.sh 停止）
```

## Python 启动

> 将下面的商户号和 API 密钥替换为商户后台中的真实参数。API 默认使用正式环境地址，无需填写。需要 Python 3.10+，无需 pip 安装。

```bash
cd Demo/backend/python
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
python3 -B src/server.py
# 后台运行可用 ./start.sh（缺变量时交互提示）；执行 ./stop.sh 停止
```

打开 `http://localhost:3000` 使用共享商店页。直接请求创建接口时必须提供 `payAmount`，例如 `/create?payAmount=0.01&outOrderNo=ORDER-1`。配置校验、回调及本地测试详见 [Python 指南](backend/python/README.zh-CN.md)。

## 本地接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/create` | 服务端创建 DSPay 订单并 302 跳转 `checkoutUrl` |
| GET | `/query?orderNo=...` | 四种语言 Demo 主动查询 DSPay 订单 |
| POST | `/notify` | 解析回调 JSON，生成规范串并验签 |
| GET | `/payment/return` | 订单超时后返回商店页；跳转不是支付凭据 |
| GET | `/payment/success` | 成功跳转页；四种语言 Demo 会继续调用查询接口 |

## 生产实现注意

- `apiSecret` 存入 KMS/密钥管理服务，不写死在代码中。
- 每笔新订单生成新的 `outOrderNo`；仅同一笔订单的网络超时重试复用原 `outOrderNo` 和相同业务字段。生产环境按网络条件评估连接/读取超时，并配置有限重试。
- `returnUrl`和`successRedirectUrl`均为可选字段；`returnUrl`仅用于订单超时，`successRedirectUrl`仅用于订单完成。未配置对应URL时，DSPay停留当前页面。
- `checkoutUrl`在订单创建180天后不再允许查看，不能作为永久订单详情入口。
- 回调先验签，再幂等更新本地订单，事务成功后才返回 `{"code":"SUCCESS"}`。
- 不根据信任前端跳转或 URL 发货，只信任验签回调或服务端查询的 `COMPLETED`。
- 示例 `attach` 只包含非敏感标识；禁止传密码、私钥和证件数据。

## 请求与响应日志

在对应后端目录执行 `tail -f logs/server.log` 查看 `./start.sh` 的实时日志；直接启动时日志输出到终端。

`DEMO_REQUEST` / `DEMO_RESPONSE` 记录创建、查询及回调的入站请求参数和最终响应；`DSPAY_REQUEST` / `DSPAY_RESPONSE` 记录发往 DSPay 的参数、响应正文和 HTTP 状态码。`CALLBACK_REQUEST` 记录回调正文，`CALLBACK_VERIFIED` 表示验签通过；验签失败会记录 HTTP 401 响应，网络异常记录 `DSPAY_ERROR`。创建订单成功后的 HTTP 302 日志包含收银台跳转地址。

请同时检查 HTTP 状态码和 DSPay 业务码；验签通过或跳转成功不代表商户已经入账或发货。API 密钥和 JSON 签名字段会脱敏，其余业务参数保留用于联调。

## 四种 Demo 的统一行为

`/create` 必须提供 `payAmount`，最少 `0.01`，整数最多 12 位、小数最多 2 位。未提供 `outOrderNo` 时生成新订单号；显式空值和非法值报错。可选 `productPrice` 整数最多 14 位、小数最多 6 位，币种为 USD；`productId` 最多 64 个字符。缺少可选商品参数时不补默认商品。未知、重复参数及非法订单号、前导零、科学计数法和金额空白均返回 HTTP 400。合法字符串保留原值，不静默 trim；可选空值的签名排除仍遵循 SDK 约定。

回调保留 JSON 大整数和小数精度，拒绝重复 JSON 字段。`timestamp` 必须是非负整数毫秒值，允许当前时间前后 5 分钟，包含边界。验签或时间窗不通过返回 HTTP 401。Demo 不额外设置回调正文的固定大小上限。

上游请求显式配置 10 秒超时，不跟随重定向。成功必须满足顶层整数 `code` 为 `0` 且包含 `data`；不能只看 HTTP 200 或 `header.resultCode`。HTTP 错误、业务码非零、响应格式异常或超时统一返回 HTTP 502，并保留诊断日志。网络库、代理默认行为和 TLS 特征仍由各运行时决定。
