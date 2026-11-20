[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP 模拟商户

PHP 5.6+，无需 Composer。服务端调用 DSPay 创建和查询接口，并使用创建响应中的 `checkoutUrl` 跳转收银台。

版本基线：最低 PHP 5.6；已在 PHP CLI `5.6.40` 和 `8.5.10` 执行全部语法检查、创建签名测试和回调验签测试。无需 Composer，只使用 PHP 标准扩展；`hash_equals()` 要求 PHP 5.6+。

> `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 是占位值，执行前必须替换。API 地址默认使用 DSPay 正式环境；仅测试其他环境时覆盖。

执行 `./start.sh` 后先输入缺少的 `MERCHANT_NO` 和 `API_SECRET`，再选择 Demo 访问地址；密钥输入不回显。`PORT` 可选，默认 `3000`；`FRONT_END_DIR` 可选，可指定其他前端目录（默认 `../../frontend`）。输入值不会保存。服务在后台运行（PID 记录在 `server.pid`），执行 `./stop.sh` 停止。

脚本和直接启动均默认使用 `https://pay-api.ds.pro`，不再询问 API 地址；仅测试其他环境时显式设置 `DSPAY_BASE_URL`。API 地址必须是完整的 HTTP(S) 源地址，不允许尾部斜杠、路径、查询参数、用户名密码或空白；显式非法值会报错，不静默修正。

启动只需商户号和 API 密钥，不需要配置访问域名。默认监听 `3000`，可用 `PORT=4000 ./start.sh` 更换端口。默认不发送支付返回链接；需要返回商户页面时，启动前设置可选的 `PUBLIC_BASE_URL` 为可信访问源地址，例如 `https://shop.example.com`（无尾部斜杠和路径）。返回链接只使用此配置，不读取 `Host` 或 `X-Forwarded-*`。真实回调地址为 `<公网地址>/notify`，仍需在商户后台单独登记。

## 可选启动参数

基础启动只需商户号 `MERCHANT_NO` 和密钥 `API_SECRET`，缺少时由 `./start.sh` 提示输入。以下参数均为可选环境变量，在启动前设置，无需修改代码。

| 参数 | 默认值 / 未配置行为 | 作用 | 用法（在当前语言目录执行） |
| --- | --- | --- | --- |
| `PUBLIC_BASE_URL` | 未设置；不发送支付返回链接 | 指定可信商户访问源，用于生成 `returnUrl` 和 `successRedirectUrl`。不改变监听地址，也不代替后台登记回调地址。 | `PUBLIC_BASE_URL=https://shop.example.com ./start.sh` |
| `PORT` | `3000` | 指定 Demo 监听端口，范围 `1–65535`。公网映射或反向代理的目标端口需保持一致。 | `PORT=4000 ./start.sh` |
| `DSPAY_BASE_URL` | `https://pay-api.ds.pro` | 指定 DSPay API 服务地址；正常接入无需修改，仅切换联调环境时使用。 | `DSPAY_BASE_URL=https://api.example.com ./start.sh` |
| `FRONT_END_DIR` | 仓库内的 `Demo/frontend` | 指定包含 `index.html` 的前端目录，替换默认演示商店页。建议使用绝对路径。 | `FRONT_END_DIR=/absolute/path/to/frontend ./start.sh` |

URL 参数必须是完整 HTTP(S) 源地址，不允许尾部斜杠、路径、查询参数、片段、用户名密码或空白；国际化域名请使用 punycode。`PUBLIC_BASE_URL` 未设置时省略返回链接，显式设置为空字符串或非法地址则报错。示例域名与目录均需替换为实际值。

可组合设置多个参数，例如：

```bash
PUBLIC_BASE_URL=https://shop.example.com PORT=4000 ./start.sh
```

也可先执行 `export PUBLIC_BASE_URL=https://shop.example.com`，再执行 `./start.sh`。修改配置后需重启服务；已在后台运行时先执行 `./stop.sh`。真实回调仍需在商户后台登记 `<公网地址>/notify`。

`PORT` 由 `start.sh` 使用；直接运行 PHP 内置服务器时，请通过 `php -S 0.0.0.0:4000 server.php` 指定端口。推荐使用 `start.sh`，以便在监听端口前完成配置校验。

ngrok 示例：`ngrok http 3000`（端口应与 Demo 一致）。Nginx 可代理到 `http://127.0.0.1:3000`；支付返回链接必须显式配置 `PUBLIC_BASE_URL`，不使用代理头推导。公网访问限制见安全部署说明。

```bash
cd Demo/backend/php
./start.sh
```

非交互运行时，先设置环境变量：

```bash
cd Demo/backend/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
php -S 0.0.0.0:3000 server.php
# 后台运行可选：./start.sh（缺变量时交互提示，./stop.sh 停止）
```

- `GET /create`：签名调用 `POST /dspay/public/order/create`，随后 302 到响应中的 `checkoutUrl`
- `GET /query?orderNo=...` 或 `?outOrderNo=...`：签名主动查询
- `POST /notify`：解析回调 JSON，按参数名 ASCII 升序生成规范串，并与 `X-DSPay-Signature` 验签
- `GET /` 托管前端商店页（与接口同源）
- `returnUrl` 跳回商店首页；`successRedirectUrl` 跳到 `/query` 展示真实订单状态——浏览器跳转不能作为发货依据

本地测试：

```bash
php test/ValidateApiBase.php
php test/ValidateNetworkError.php
php test/ValidateCreateOrder.php
php test/VerifyCallback.php
php test/ValidateHttpCreate.php  # 未配置 TEST_CREATE_URL 时跳过线上联调
```

## 请求与响应日志

在对应后端目录执行 `tail -f logs/server.log` 查看 `./start.sh` 的实时日志；直接启动时日志输出到终端。

`DEMO_REQUEST` / `DEMO_RESPONSE` 记录创建、查询及回调的入站请求参数和最终响应；`DSPAY_REQUEST` / `DSPAY_RESPONSE` 记录发往 DSPay 的参数、响应正文和 HTTP 状态码。`CALLBACK_REQUEST` 记录回调正文，`CALLBACK_VERIFIED` 表示验签通过；验签失败会记录 HTTP 401 响应，网络异常记录 `DSPAY_ERROR`。创建订单成功后的 HTTP 302 日志包含收银台跳转地址。

请同时检查 HTTP 状态码和 DSPay 业务码；验签通过或跳转成功不代表商户已经入账或发货。API 密钥和 JSON 签名字段会脱敏，其余业务参数保留用于联调。

PHP 客户端使用 `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36` 和 `Accept: application/json`。若收到 Cloudflare HTTP 403，日志记录响应正文、`cfRay` 和 `contentType`，错误信息也包含 `CF-Ray`。需由 API 管理方根据 Ray ID 查询命中规则；客户端标识不保证解除拦截。

## 四种 Demo 的统一行为

`/create` 必须提供 `payAmount`，最少 `0.01`，整数最多 12 位、小数最多 2 位。未提供 `outOrderNo` 时生成新订单号；显式空值和非法值报错。可选 `productPrice` 整数最多 14 位、小数最多 6 位，币种为 USD；`productId` 最多 64 个字符。缺少可选商品参数时不补默认商品。未知、重复参数及非法订单号、前导零、科学计数法和金额空白均返回 HTTP 400。合法字符串保留原值，不静默 trim；可选空值的签名排除仍遵循 SDK 约定。

回调保留 JSON 大整数和小数精度，拒绝重复 JSON 字段。`timestamp` 必须是非负整数毫秒值，允许当前时间前后 5 分钟，包含边界。验签或时间窗不通过返回 HTTP 401。回调正文上限为 64 KiB，超限返回 HTTP 413。

上游请求显式配置 10 秒超时，不跟随重定向。成功必须满足顶层整数 `code` 为 `0` 且包含 `data`；不能只看 HTTP 200 或 `header.resultCode`。HTTP 错误、业务码非零、响应格式异常或超时统一返回 HTTP 502，并保留诊断日志。网络库、代理默认行为和 TLS 特征仍由各运行时决定。

## 安全配置与部署

商户号和 apiSecret 必须显式提供，没有兜底值；缺少配置时拒绝运行。`PUBLIC_BASE_URL` 为可选环境变量，未配置时省略支付返回链接，支付后停留收银台；显式空值或非法值会报错。回调请求体上限为 64 KiB。创建、查询接口保持演示用途，生产权限和订单归属需由商户实现。详见[安全部署说明](../../SECURITY-DEPLOYMENT.zh-CN.md)。
