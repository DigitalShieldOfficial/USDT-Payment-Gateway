[English](README.md) | [中文](README.zh-CN.md)

# Node.js Mock Merchant

该服务真实调用 `POST /dspay/public/order/create` 和 `/query`，并使用响应中的 `checkoutUrl` 跳转收银台。

版本基线：Node.js `>=18.20.8`（`package.json` 声明；`.nvmrc` 固定开发版本），零第三方 npm 包。已验证版本见下表。

## 已验证环境（对接生产 DSPay API 的端到端实测）

| Node.js 版本 | npm | 平台 | 结果 | 推荐 |
|---|---|---|---|---|
| 18.20.8 | 10.8.2 | macOS arm64（nvm） | ✅ 下单成功，302 跳转收银台 | —（已 EOL，建议迁移） |
| 20.20.0 | 10.8.2 | macOS arm64（nvm） | ✅ 下单成功，302 跳转收银台 | —（已 EOL，建议迁移） |
| 22.23.4 | 10.9.9 | macOS arm64（nvm） | ✅ 下单成功，302 跳转收银台 | ⭐ 推荐 — LTS |
| 24.22.2 | 11.19.1 | macOS arm64（nvm） | ✅ 下单成功，302 跳转收银台 | ⭐ 推荐 — LTS |
| 26.13.0 | 11.20.0 | macOS arm64（nvm） | ✅ 下单成功，302 跳转收银台 | —（最新版，发布时间较近） |

每行均经过两轮完整流程验证：`./start.sh` → 商店页 → Pay Now → 服务端创建订单 → `302` 跳转 `https://cashier.ds.pro/checkout/<订单号>?x-digitalshield-temp-token=<令牌>`。已验证各线均持续支持；"—" 表示仍支持但不是新接入的首选。Node 自带 TLS 栈（BoringSSL 系），宿主机 OpenSSL 版本不影响连通性。商户执行 `node -v` 对照即可。

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

ngrok 示例：`ngrok http 3000`（端口应与 Demo 一致）。Nginx 可代理到 `http://127.0.0.1:3000`；支付返回链接必须显式配置 `PUBLIC_BASE_URL`，不使用代理头推导。公网访问限制见安全部署说明。

```bash
nvm install
nvm use
node --version  # v22.23.4 (.nvmrc)
npm --version   # 10.9.9
```

> 命令中的 `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 是占位值，执行前必须替换为 DSPay 商户后台中的真实参数。DSPay API 地址默认使用正式环境地址 `https://pay-api.ds.pro`，无需填写；仅测试其他环境时覆盖。

后台运行可直接执行 `./start.sh`。脚本先询问缺少的 `MERCHANT_NO` 和 `API_SECRET`，密钥输入不回显。`PORT` 可选，默认 `3000`；`FRONT_END_DIR` 可选，可指定其他前端目录（默认 `../../frontend`）。输入值不会保存；下次启动需重新输入或提前设置环境变量。

```bash
cd Demo/backend/nodejs
./start.sh
```

前台交互运行可用 `npm start`（等价于 `node src/server.js`）。

非交互运行时，先设置环境变量：

```bash
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
npm start        # 等价于 node src/server.js
```

后台运行可选 `./start.sh`（缺变量时交互提示，`./stop.sh` 停止）。

- `GET /create`：签名预下单并 302 到响应中的 `checkoutUrl`
- `GET /query?orderNo=...` 或 `?outOrderNo=...`：签名查询
- `POST /notify`：解析回调 JSON，按参数名 ASCII 升序生成规范串后验签
- `GET /` 托管前端商店页（与接口同源；可用 `FRONT_END_DIR` 指定其他目录）
- `returnUrl` 跳回商店首页；`successRedirectUrl` 跳到 `/query` 展示真实订单状态——浏览器跳转不能作为发货依据

完整说明见 [../../README.zh-CN.md](../../README.zh-CN.md)。

## 请求与响应日志

在对应后端目录执行 `tail -f logs/server.log` 查看 `./start.sh` 的实时日志；直接启动时日志输出到终端。

`DEMO_REQUEST` / `DEMO_RESPONSE` 记录创建、查询及回调的入站请求参数和最终响应；`DSPAY_REQUEST` / `DSPAY_RESPONSE` 记录发往 DSPay 的参数、响应正文和 HTTP 状态码。`CALLBACK_REQUEST` 记录回调正文，`CALLBACK_VERIFIED` 表示验签通过；验签失败会记录 HTTP 401 响应，网络异常记录 `DSPAY_ERROR`。创建订单成功后的 HTTP 302 日志包含收银台跳转地址。

请同时检查 HTTP 状态码和 DSPay 业务码；验签通过或跳转成功不代表商户已经入账或发货。API 密钥和 JSON 签名字段会脱敏，其余业务参数保留用于联调。

Node.js 客户端通过内置 `tls`/HTTP/2 能力调用 HTTPS API，采用固定的现代加密套件顺序和普通 Chrome 浏览器 User-Agent，并发送 `Accept: application/json`。实现位于 `src/transport.js`，无需 npm 安装、第三方包或原生二进制；没有配置或自动读取系统代理。HTTPS 上游必须支持 HTTP/2；本地 HTTP 模拟接口仍使用 HTTP/1.1。证书验证保持开启，TLS 最低版本为 1.2。若收到 Cloudflare HTTP 403，日志保留响应正文、`cfRay` 和 `contentType`，页面错误也包含 `CF-Ray`。请将 Ray ID 提供给 API 管理方定位拦截规则；更换 User-Agent 不能保证所有规则都会放行。

## 四种 Demo 的统一行为

`/create` 必须提供 `payAmount`，最少 `0.01`，整数最多 12 位、小数最多 2 位。未提供 `outOrderNo` 时生成新订单号；显式空值和非法值报错。可选 `productPrice` 整数最多 14 位、小数最多 6 位，币种为 USD；`productId` 最多 64 个字符。缺少可选商品参数时不补默认商品。未知、重复参数及非法订单号、前导零、科学计数法和金额空白均返回 HTTP 400。合法字符串保留原值，不静默 trim；可选空值的签名排除仍遵循 SDK 约定。

回调保留 JSON 大整数和小数精度，拒绝重复 JSON 字段。`timestamp` 必须是非负整数毫秒值，允许当前时间前后 5 分钟，包含边界。验签或时间窗不通过返回 HTTP 401。回调正文上限为 64 KiB，超限返回 HTTP 413。

上游请求显式配置 10 秒超时，不跟随重定向。成功必须满足顶层整数 `code` 为 `0` 且包含 `data`；不能只看 HTTP 200 或 `header.resultCode`。HTTP 错误、业务码非零、响应格式异常或超时统一返回 HTTP 502，并保留诊断日志。网络库、代理默认行为和 TLS 特征仍由各运行时决定。

网络失败时，`DSPAY_ERROR` 日志的 `details` 会保留 Node.js 底层 `cause` 链及错误码，例如 `ECONNRESET`、`ENOTFOUND`、`ETIMEDOUT`。错误响应同时显示底层原因；10 秒超时会明确提示。没有收到 HTTP 响应的连接错误 与 Cloudflare HTTP 403 是不同故障，需根据实际错误码排查。

运行回归测试：`node --test`（或 `npm test`）。TLS 测试使用 OpenSSL 生成临时本地证书；OpenSSL 仅为测试工具，启动 Demo 不需要它。

## 安全配置与部署

商户号和 apiSecret 必须显式提供，没有兜底值；缺少配置时拒绝运行。`PUBLIC_BASE_URL` 为可选环境变量，未配置时省略支付返回链接，支付后停留收银台；显式空值或非法值会报错。回调请求体上限为 64 KiB。创建、查询接口保持演示用途，生产权限和订单归属需由商户实现。详见[安全部署说明](../../SECURITY-DEPLOYMENT.zh-CN.md)。
