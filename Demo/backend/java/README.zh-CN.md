[English](README.md) | [中文](README.zh-CN.md)

# Java Mock Merchant

JDK 8+，零外部依赖。该服务真实调用 `POST /dspay/public/order/create`，并使用响应中的 `checkoutUrl` 跳转收银台。

版本基线：JDK 8+——无需 Maven/Gradle，源码只使用 JDK 标准库。`./start.sh` 会先编译到 `build/` 再以 `java -cp build DspayMockMerchant` 运行。已验证版本见下表。

## 已验证环境（对接生产 DSPay API 的端到端实测）

| Java 版本 | 发行版 | 平台 | 结果 | 推荐 |
|---|---|---|---|---|
| 8（`1.8.0_504`） | Amazon Corretto | macOS arm64 | ✅ 下单成功，302 跳转收银台 | — |
| 11（`11.0.27`） | Microsoft OpenJDK | macOS arm64 | ✅ 下单成功，302 跳转收银台 | — |
| 17（`17.0.21.0.3`） | Eclipse Temurin（Homebrew） | macOS arm64 | ✅ 下单成功，302 跳转收银台 | — |
| 21（`21.0.11`） | Eclipse Temurin | macOS arm64 | ✅ 下单成功，302 跳转收银台 | ⭐ 推荐 — 当前企业默认 |
| 25（`25.0.5.0.3`） | Eclipse Temurin（Homebrew） | macOS arm64 | ✅ 下单成功，302 跳转收银台 | ⭐ 推荐 — 最新 LTS |

每行均经过两轮完整流程验证：`./start.sh` → 商店页 → Pay Now → 服务端创建订单 → `302` 跳转 `https://cashier.ds.pro/checkout/<订单号>?x-digitalshield-temp-token=<令牌>`。已验证各线均持续支持；"—" 表示仍支持但不是新接入的首选。JDK 自带 TLS 栈（JSSE），宿主机 OpenSSL 版本不影响连通性。商户执行 `java -version` 对照即可。

> 命令中的 `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 是占位值，执行前必须替换为 DSPay 商户后台中的真实参数。DSPay API 地址默认使用正式环境地址 `https://pay-api.ds.pro`，无需填写；仅测试其他环境时覆盖。

脚本和直接启动均默认使用 `https://pay-api.ds.pro`，不再询问 API 地址；仅测试其他环境时显式设置 `DSPAY_BASE_URL`。Java 也支持 `-DdspayBase`，优先于环境变量。API 地址必须是完整的 HTTP(S) 源地址，不允许尾部斜杠、路径、查询参数、用户名密码或空白；显式非法值会报错，不静默修正。

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

直接运行 Java 时，部分可选环境变量也支持系统属性（`-D` 参数放在主类名前）：

| 环境变量 | 对应系统属性 | 优先级 / 用法 |
| --- | --- | --- |
| `PORT` | `-Dport=4000` | 系统属性优先于环境变量 |
| `DSPAY_BASE_URL` | `-DdspayBase=https://api.example.com` | 系统属性优先于环境变量 |
| `FRONT_END_DIR` | `-DfrontEndDir=/absolute/path/to/frontend` | 系统属性优先于环境变量 |
| `PUBLIC_BASE_URL` | 无 | 仅支持环境变量 |

必填凭证另支持 `-DmerchantNo` 和 `-DapiSecret`：商户号以系统属性优先；密钥以 `API_SECRET` 环境变量优先（显式空值报错），未设置环境变量才读取 `-DapiSecret`。推荐通过环境变量传递密钥。

```bash
PUBLIC_BASE_URL=https://shop.example.com java -Dport=4000 -cp build DspayMockMerchant
```

直接启动示例假定已经编译，并已通过环境变量提供 `MERCHANT_NO` 和 `API_SECRET`。

ngrok 示例：`ngrok http 3000`（端口应与 Demo 一致）。Nginx 可代理到 `http://127.0.0.1:3000`；支付返回链接必须显式配置 `PUBLIC_BASE_URL`，不使用代理头推导。公网访问限制见安全部署说明。

```bash
mkdir -p build && javac -d build src/DspayMockMerchant.java src/DspayTransport.java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -cp build DspayMockMerchant
```

后台运行可执行 `./start.sh`。脚本先询问 `MERCHANT_NO` 和 `API_SECRET`，密钥输入不回显。提前设置对应环境变量可跳过询问。`PORT` 可选，默认 `3000`；`FRONT_END_DIR` 可选，可指定包含 `index.html` 的其他前端目录（默认 `../../frontend`，脚本中的相对路径以 Java Demo 目录为基准）；直接运行 Java 时也支持此环境变量，`-DfrontEndDir` 优先。显式空值或缺少 `index.html` 会启动失败。输入值不会保存；下次启动需重新输入或通过环境变量提供。非交互运行只需提供上述两项凭证环境变量。

- `GET /create`：签名预下单并 302 到响应中的 `checkoutUrl`
- `GET /query?orderNo=...` 或 `?outOrderNo=...`：签名主动查询
- `POST /notify`：解析回调 JSON，按参数名 ASCII 升序生成规范串后验签
- `GET /` 托管前端商店页（与接口同源；可用 `FRONT_END_DIR` 指定其他目录）
- `returnUrl` 跳回商店首页；`successRedirectUrl` 跳到 `/query` 展示真实订单状态——浏览器跳转不能作为发货依据

完整说明见 [../../README.zh-CN.md](../../README.zh-CN.md)。

## 请求与响应日志

在对应后端目录执行 `tail -f logs/server.log` 查看 `./start.sh` 的实时日志；直接启动时日志输出到终端。

`DEMO_REQUEST` / `DEMO_RESPONSE` 记录创建、查询及回调的入站请求参数和最终响应；`DSPAY_REQUEST` / `DSPAY_RESPONSE` 记录发往 DSPay 的参数、响应正文和 HTTP 状态码。`CALLBACK_REQUEST` 记录回调正文，`CALLBACK_VERIFIED` 表示验签通过；验签失败会记录 HTTP 401 响应，网络异常记录 `DSPAY_ERROR`。创建订单成功后的 HTTP 302 日志包含收银台跳转地址。

请同时检查 HTTP 状态码和 DSPay 业务码；验签通过或跳转成功不代表商户已经入账或发货。API 密钥和 JSON 签名字段会脱敏，其余业务参数保留用于联调。

Java 客户端使用 `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36` 和 `Accept: application/json`。若响应为 Cloudflare 拦截 HTML，请在 `DSPAY_RESPONSE` 日志中查看 HTTP 状态、`CF-Ray` 和响应正文，并提供 Ray ID 给 API 管理方定位规则；增加客户端标识不保证所有防火墙规则都会放行。

## 四种 Demo 的统一行为

`/create` 必须提供 `payAmount`，最少 `0.01`，整数最多 12 位、小数最多 2 位。未提供 `outOrderNo` 时生成新订单号；显式空值和非法值报错。可选 `productPrice` 整数最多 14 位、小数最多 6 位，币种为 USD；`productId` 最多 64 个字符。缺少可选商品参数时不补默认商品。未知、重复参数及非法订单号、前导零、科学计数法和金额空白均返回 HTTP 400。合法字符串保留原值，不静默 trim；可选空值的签名排除仍遵循 SDK 约定。

回调保留 JSON 大整数和小数精度，拒绝重复 JSON 字段。`timestamp` 必须是非负整数毫秒值，允许当前时间前后 5 分钟，包含边界。验签或时间窗不通过返回 HTTP 401。回调正文上限为 64 KiB，超限返回 HTTP 413。

上游请求显式配置 10 秒超时，不跟随重定向。成功必须满足顶层整数 `code` 为 `0` 且包含 `data`；不能只看 HTTP 200 或 `header.resultCode`。HTTP 错误、业务码非零、响应格式异常或超时统一返回 HTTP 502，并保留诊断日志。网络库、代理默认行为和 TLS 特征仍由各运行时决定。

请求通过 `src/DspayTransport.java` 使用 Java 8 公共 `Socket` / `SSLSocket` API，以 HTTP/1.1 发送，与其他 Demo 统一 Chrome 147 User-Agent。无需 JDK 11 API、第三方库或原生二进制，不自动配置代理。HTTPS 校验证书链及主机名，最低 TLS 1.2。10 秒总时限覆盖连接、TLS 握手、发送及读取响应；支持定长、分块和连接关闭定界的响应、gzip/deflate，不跟随重定向。这是精简 POST 实现，不是完整移植 JDK 11 HttpClient。

本地测试：先执行 `mkdir -p /tmp/dspay-java`，再执行 `javac -d /tmp/dspay-java src/DspayMockMerchant.java src/DspayTransport.java test/DspayMockMerchantTest.java test/DspayTransportTest.java`，随后分别运行 `MERCHANT_NO=DSM001 API_SECRET=demo-secret java -cp /tmp/dspay-java DspayMockMerchantTest` 和 `java -cp /tmp/dspay-java DspayTransportTest`。传输测试使用 JDK 自带的 `keytool` 生成临时证书，不关闭 TLS 校验。

## 安全配置与部署

商户号和 apiSecret 必须显式提供，没有兜底值；缺少配置时拒绝运行。`PUBLIC_BASE_URL` 为可选环境变量，未配置时省略支付返回链接，支付后停留收银台；显式空值或非法值会报错。回调请求体上限为 64 KiB。创建、查询接口保持演示用途，生产权限和订单归属需由商户实现。详见[安全部署说明](../../SECURITY-DEPLOYMENT.zh-CN.md)。
