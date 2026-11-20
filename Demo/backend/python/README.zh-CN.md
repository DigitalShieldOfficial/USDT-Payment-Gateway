[English](README.md) | [中文](README.zh-CN.md)

# DSPay Python 模拟商户

Python 3.10+，零第三方依赖。服务端签名调用 `POST /dspay/public/order/create` 和 `/query`，跳转响应中的 `checkoutUrl`，并按统一规范串验证回调。

## 运行环境

使用 `python3`，无需 pip 安装、虚拟环境或框架。已验证版本见下表。

### 已验证环境（对接生产 DSPay API 的端到端实测）

| Python 版本 | 安装方式 | 平台 | 结果 | 推荐 |
|---|---|---|---|---|
| 3.10.16 | pyenv（源码编译） | macOS arm64 | ✅ 下单成功，302 跳转收银台 | —（2026-10 已 EOL） |
| 3.11.13 | pyenv（源码编译） | macOS arm64 | ✅ 下单成功，302 跳转收银台 | — |
| 3.12.3 | pyenv（源码编译） | macOS arm64 | ✅ 下单成功，302 跳转收银台 | ⭐ 推荐 — 生产主力 |
| 3.13.6 | pyenv（源码编译） | macOS arm64 | ✅ 下单成功，302 跳转收银台 | ⭐ 推荐 |
| 3.14.3 | 系统 python3 | macOS arm64 | ✅ 下单成功，302 跳转收银台 | ⭐ 推荐 — 首选，当前推荐版本 |
| 3.15.1 | pyenv（源码编译） | macOS arm64 | ✅ 下单成功，302 跳转收银台 | —（最新版，2026-10 发布） |

每行均经过两轮完整流程验证：`./start.sh` → 商店页 → Pay Now → 服务端创建订单 → `302` 跳转 `https://cashier.ds.pro/checkout/<订单号>?x-digitalshield-temp-token=<令牌>`。已验证各线均持续支持；"—" 表示仍支持但不是新接入的首选。标准库 `ssl` 模块跟随解释器链接的 OpenSSL，实测各代构建（OpenSSL 1.1.1 / 3.x / 4.x 时期）全部通过，OpenSSL 版本对 Python 不是兼容性因素。商户执行 `python3 -V` 对照即可。

启动只需商户号和 API 密钥，不需要配置访问域名。默认监听 `3000`，可用 `PORT=4000 ./start.sh` 更换端口。默认不发送支付返回链接；需要返回商户页面时，启动前设置可选的 `PUBLIC_BASE_URL` 为可信访问源地址，例如 `https://shop.example.com`（无尾部斜杠和路径）。返回链接只使用此配置，不读取 `Host` 或 `X-Forwarded-*`。真实回调地址为 `<公网地址>/notify`，仍需在商户后台单独登记。

## 可选启动参数

基础启动只需商户号 `MERCHANT_NO` 和密钥 `API_SECRET`，缺少时由 `./start.sh` 提示输入。以下参数均为可选环境变量，在启动前设置，无需修改代码。

| 参数 | 默认值 / 未配置行为 | 作用 | 用法（在当前语言目录执行） |
| --- | --- | --- | --- |
| `PUBLIC_BASE_URL` | 未设置；不发送支付返回链接 | 指定可信商户访问源，用于生成 `returnUrl` 和 `successRedirectUrl`。不改变监听地址，也不代替后台登记回调地址。 | `PUBLIC_BASE_URL=https://shop.example.com ./start.sh` |
| `PORT` | `3000` | 指定 Demo 监听端口，范围 `1–65535`。公网映射或反向代理的目标端口需保持一致。 | `PORT=4000 ./start.sh` |
| `DSPAY_BASE_URL` | `https://pay-api.ds.pro` | 指定 DSPay API 服务地址；正常接入无需修改，仅切换联调环境时使用。 | `DSPAY_BASE_URL=https://api.example.com ./start.sh` |
| `FRONT_END_DIR` | 仓库内的 `Demo/frontend` | 指定包含 `index.html` 的前端目录，替换默认演示商店页。建议使用绝对路径。 | `FRONT_END_DIR=/absolute/path/to/frontend ./start.sh` |
| `PYTHON_BIN` | `python3` | 仅供 `start.sh` 选择 Python 3.10+ 解释器；直接运行 Python 时由命令自身决定。 | `PYTHON_BIN=python3.12 ./start.sh` |

URL 参数必须是完整 HTTP(S) 源地址，不允许尾部斜杠、路径、查询参数、片段、用户名密码或空白；国际化域名请使用 punycode。`PUBLIC_BASE_URL` 未设置时省略返回链接，显式设置为空字符串或非法地址则报错。示例域名与目录均需替换为实际值。

可组合设置多个参数，例如：

```bash
PUBLIC_BASE_URL=https://shop.example.com PORT=4000 ./start.sh
```

也可先执行 `export PUBLIC_BASE_URL=https://shop.example.com`，再执行 `./start.sh`。修改配置后需重启服务；已在后台运行时先执行 `./stop.sh`。真实回调仍需在商户后台登记 `<公网地址>/notify`。

ngrok 示例：`ngrok http 3000`（端口应与 Demo 一致）。Nginx 可代理到 `http://127.0.0.1:3000`；支付返回链接必须显式配置 `PUBLIC_BASE_URL`，不使用代理头推导。公网访问限制见安全部署说明。

```bash
python3 --version
```

## 启动

> 将 `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 替换为 DSPay 商户后台中的真实参数。API 默认使用正式环境地址，无需填写。`apiSecret` 只能保存在商户服务端，禁止放入前端代码、URL、浏览器存储或在线工具。

```bash
cd Demo/backend/python
./start.sh
```

脚本和直接运行 Python 均默认使用 `https://pay-api.ds.pro`，不再询问 API 地址；仅测试其他环境时显式设置 `DSPAY_BASE_URL`。脚本先询问缺少的 `MERCHANT_NO` 和 `API_SECRET`，密钥输入不回显，输入值不会保存。非交互运行时先设置环境变量：

```bash
cd Demo/backend/python
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
python3 -B src/server.py
# 后台运行可用 ./start.sh；执行 ./stop.sh 停止
```

`PORT` 默认 `3000`；`PYTHON_BIN` 可指定 `start.sh` 使用的解释器。`FRONT_END_DIR` 可覆盖共享的 `Demo/frontend` 目录；使用 `start.sh` 时，相对路径从 Python Demo 目录解析。DSPay API 基础地址必须是仅含 ASCII 字符的完整 HTTP(S) 源地址（国际化域名需使用 punycode），不允许尾部斜杠、路径、查询参数、片段、用户名密码或空白字符。配置不合法时明确报出字段和要求，不静默修正。`MERCHANT_NO` 必须以 `DSM` 或 `M` 开头，后接 1～19 位数字。

后台进程的 PID 保存在 `server.pid`，日志写入 `logs/server.log`。打开 `http://localhost:3000` 即可使用共享商店页。接收真实回调时，将该端口代理到公网，并在商户后台配置公网 `/notify` 接口。

## 接口

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/` | 托管共享商店页 |
| GET | `/create?payAmount=0.02&outOrderNo=ORDER-1` | 签名创建订单，再跳转响应中的 `checkoutUrl` |
| GET | `/query?orderNo=...` 或 `?outOrderNo=...` | 签名查询权威状态，返回 `data` 数组 |
| POST | `/notify` | 解析 JSON，验证规范串 HMAC 及正负五分钟时间窗，再返回演示 ACK |
| GET | `/payment/return` | 跳回商店页 |
| GET | `/payment/success?outOrderNo=...` | 跳转到服务端签名查询 |

`/create` 必须提供 `payAmount`：最少 `0.01`，整数最多 12 位、小数最多 2 位，不接受科学计数法、前导零或空白。`outOrderNo` 允许 1～64 位字母、数字和横杠；未传时生成新订单号，显式空值或非法值会报错。可选 `productPrice` 整数最多 14 位、小数最多 6 位，币种为 USD；可选 `productId` 最多 64 个字符。不支持的查询参数及重复参数会报错。查询订单号格式遵循 SDK §5.11。

金额保留十进制字符串；回调 JSON 的小数使用 `Decimal`。签名不修剪非空白字符串，按 SDK §4.4 处理可选字段空值及支付方式规范化。签名模块不接受二进制浮点数；超过演示程序 4096 位限制的 JSON 数字及重复对象字段会报错。原始请求对象不会被改写。

上游请求使用 `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36` 客户端标识，超时为 10 秒，不跟随重定向。HTTP 错误会保留网关返回的 `CF-Ray`（如有），便于排查。HTTP 失败、响应格式异常或业务码非零返回 HTTP 502，HTTP 200 本身不代表成功。回调要求 UTF-8 JSON、`Content-Length` 和 `X-DSPay-Signature`，不支持分块请求体；回调请求体上限为 64 KiB，超限返回 HTTP 413；上游响应上限为 1 MiB。验签和时间窗校验通过时返回 HTTP 200 及 `{"code":"SUCCESS","msg":"ok"}`；签名、时间戳或 JSON 异常时返回失败响应。

## 本地测试与生产边界

```bash
cd Demo/backend/python
python3 -B -m unittest discover -s test -v
```

测试使用虚拟密钥及进程内模拟 DSPay HTTP 服务，覆盖共享签名向量、小数精度、字符串原值保留、回调篡改与过期时间戳拒绝、静态页面、创建与查询请求、跳转、上游失败、配置校验及后台启停。无需商户账号或真实转账，不调用生产 API。

运行真实支付 Demo 使用主网，涉及真实代币和网络费用。本地测试不证明链上到账检测、真实 DSPay 回调投递或实际订单完成。该程序是模拟商户，不持久化订单或执行发货，演示 ACK 不代表生产业务事务。正式接入需补持久化 `orderNo + eventType` 幂等处理、ACK 前事务提交、HTTPS、访问控制、监控及密钥管理。浏览器跳转不能作为支付凭据。

完整说明见 [SDK 指南](../../../SDK/SDK.zh-CN.md)和 [Demo 总说明](../../README.zh-CN.md)。

## 请求与响应日志

在对应后端目录执行 `tail -f logs/server.log` 查看 `./start.sh` 的实时日志；直接启动时日志输出到终端。

`DEMO_REQUEST` / `DEMO_RESPONSE` 记录创建、查询及回调的入站请求参数和最终响应；`DSPAY_REQUEST` / `DSPAY_RESPONSE` 记录发往 DSPay 的参数、响应正文和 HTTP 状态码。`CALLBACK_REQUEST` 记录回调正文，`CALLBACK_VERIFIED` 表示验签通过；验签失败会记录 HTTP 401 响应，网络异常记录 `DSPAY_ERROR`。创建订单成功后的 HTTP 302 日志包含收银台跳转地址。

请同时检查 HTTP 状态码和 DSPay 业务码；验签通过或跳转成功不代表商户已经入账或发货。API 密钥和 JSON 签名字段会脱敏，其余业务参数保留用于联调。

## 四种 Demo 的统一行为

`/create` 必须提供 `payAmount`，最少 `0.01`，整数最多 12 位、小数最多 2 位。未提供 `outOrderNo` 时生成新订单号；显式空值和非法值报错。可选 `productPrice` 整数最多 14 位、小数最多 6 位，币种为 USD；`productId` 最多 64 个字符。缺少可选商品参数时不补默认商品。未知、重复参数及非法订单号、前导零、科学计数法和金额空白均返回 HTTP 400。合法字符串保留原值，不静默 trim；可选空值的签名排除仍遵循 SDK 约定。

回调保留 JSON 大整数和小数精度，拒绝重复 JSON 字段。`timestamp` 必须是非负整数毫秒值，允许当前时间前后 5 分钟，包含边界。验签或时间窗不通过返回 HTTP 401。回调正文上限为 64 KiB，超限返回 HTTP 413。

上游请求显式配置 10 秒超时，不跟随重定向。成功必须满足顶层整数 `code` 为 `0` 且包含 `data`；不能只看 HTTP 200 或 `header.resultCode`。HTTP 错误、业务码非零、响应格式异常或超时统一返回 HTTP 502，并保留诊断日志。网络库、代理默认行为和 TLS 特征仍由各运行时决定。

## 安全配置与部署

商户号和 apiSecret 必须显式提供，没有兜底值；缺少配置时拒绝运行。`PUBLIC_BASE_URL` 为可选环境变量，未配置时省略支付返回链接，支付后停留收银台；显式空值或非法值会报错。回调请求体上限为 64 KiB。创建、查询接口保持演示用途，生产权限和订单归属需由商户实现。详见[安全部署说明](../../SECURITY-DEPLOYMENT.zh-CN.md)。
