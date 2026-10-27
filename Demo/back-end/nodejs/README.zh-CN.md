[English](README.md) | [中文](README.zh-CN.md)

# Node.js Mock Merchant

统一使用 Node.js `18.20.8` + npm `10.8.2`，零 npm 依赖。该服务真实调用 `POST /dspay/public/order/create` 和 `/query`，并使用响应中的 `checkoutUrl` 跳转收银台。

版本基线和已验证版本均为 Node.js `18.20.8` + npm `10.8.2`。仓库提供 `.nvmrc`，`package.json` 声明 `node >=18.20.8`，不需要多个Node版本，也没有第三方npm包。

脚本和直接启动均默认使用 `https://pay-api.ds.pro`，不再询问 API 地址；仅测试其他环境时显式设置 `DSPAY_BASE_URL`。API 地址必须是完整的 HTTP(S) 源地址，不允许尾部斜杠、路径、查询参数、用户名密码或空白；显式非法值会报错，不静默修正。

启动只需商户号和 API 密钥，不需要配置访问域名。默认监听 `3000`，可用 `PORT=4000 ./start.sh` 更换端口。支付返回链接根据创建订单请求的域名和协议生成；通过 ngrok/nginx 访问时，请从公网地址打开 Demo 并创建订单。真实回调地址为 `<公网地址>/notify`，仍需在商户后台单独登记。

ngrok 示例：`ngrok http 3000`（端口应与 Demo 一致）。nginx 请转发到 `http://127.0.0.1:3000`，设置 `proxy_set_header Host $http_host;` 和 `proxy_set_header X-Forwarded-Proto $scheme;`，并覆盖客户端传入的 `X-Forwarded-Host`：`proxy_set_header X-Forwarded-Host $http_host;`。Demo 仅信任来自本机回环地址的代理头，代理链应提供单个正确的域名和协议，不能传入逗号列表；公网入口应通过该代理访问。

```bash
nvm install
nvm use
node --version  # v18.20.8
npm --version   # 10.8.2
```

> 命令中的 `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 是占位值，执行前必须替换为 DSPay 商户后台中的真实参数。DSPay API 地址默认使用正式环境地址 `https://pay-api.ds.pro`，无需填写；仅测试其他环境时覆盖。

后台运行可直接执行 `./start.sh`。脚本先询问缺少的 `MERCHANT_NO` 和 `API_SECRET`，密钥输入不回显。`PORT` 可选，默认 `3000`；`FRONT_END_DIR` 可选，可指定其他前端目录（默认 `../../front-end`）。输入值不会保存；下次启动需重新输入或提前设置环境变量。

```bash
cd Demo/back-end/nodejs
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

Node.js 客户端发送 `User-Agent: DSPay-Nodejs-Demo/1.0` 和 `Accept: application/json`。若收到 Cloudflare HTTP 403，日志保留响应正文、`cfRay` 和 `contentType`，页面错误也包含 `CF-Ray`。请将 Ray ID 提供给 API 管理方定位拦截规则；更换 User-Agent 不能保证所有规则都会放行。

## 四种 Demo 的统一行为

`/create` 必须提供 `payAmount`，最少 `0.01`，整数最多 12 位、小数最多 2 位。未提供 `outOrderNo` 时生成新订单号；显式空值和非法值报错。可选 `productPrice` 整数最多 14 位、小数最多 6 位，币种为 USD；`productId` 最多 64 个字符。缺少可选商品参数时不补默认商品。未知、重复参数及非法订单号、前导零、科学计数法和金额空白均返回 HTTP 400。合法字符串保留原值，不静默 trim；可选空值的签名排除仍遵循 SDK 约定。

回调保留 JSON 大整数和小数精度，拒绝重复 JSON 字段。`timestamp` 必须是非负整数毫秒值，允许当前时间前后 5 分钟，包含边界。验签或时间窗不通过返回 HTTP 401。Demo 不额外设置回调正文的固定大小上限。

上游请求显式配置 10 秒超时，不跟随重定向。成功必须满足顶层整数 `code` 为 `0` 且包含 `data`；不能只看 HTTP 200 或 `header.resultCode`。HTTP 错误、业务码非零、响应格式异常或超时统一返回 HTTP 502，并保留诊断日志。网络库、代理默认行为和 TLS 特征仍由各运行时决定。

网络失败时，`DSPAY_ERROR` 日志的 `details` 会保留 Node.js 底层 `cause` 链及错误码，例如 `ECONNRESET`、`ENOTFOUND`、`UND_ERR_CONNECT_TIMEOUT`。错误响应同时显示底层原因；10 秒超时会明确提示。没有收到 HTTP 响应的 `fetch failed` 与 Cloudflare HTTP 403 是不同故障，需根据实际错误码排查。
