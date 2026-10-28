[English](README.md) | [中文](README.zh-CN.md)

# Java Mock Merchant

JDK 8+，零外部依赖。该服务真实调用 `POST /dspay/public/order/create`，并使用响应中的 `checkoutUrl` 跳转收银台。

版本基线：最低 JDK 8；已在 Corretto `1.8.0_504`、Microsoft OpenJDK `11.0.27` 和 Temurin `17.0.21` / `21.0.11` 上完成传输模块验证，完整启动及业务回归覆盖 JDK 8 和 21。无需 Maven/Gradle，源码只使用 JDK 标准库。`./start.sh` 会先编译到 `build/` 再以 `java -cp build DspayMockMerchant` 运行。

> 命令中的 `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 是占位值，执行前必须替换为 DSPay 商户后台中的真实参数。DSPay API 地址默认使用正式环境地址 `https://pay-api.ds.pro`，无需填写；仅测试其他环境时覆盖。

脚本和直接启动均默认使用 `https://pay-api.ds.pro`，不再询问 API 地址；仅测试其他环境时显式设置 `DSPAY_BASE_URL`。Java 也支持 `-DdspayBase`，优先于环境变量。API 地址必须是完整的 HTTP(S) 源地址，不允许尾部斜杠、路径、查询参数、用户名密码或空白；显式非法值会报错，不静默修正。

启动只需商户号和 API 密钥，不需要配置访问域名。默认监听 `3000`，可用 `PORT=4000 ./start.sh` 更换端口。支付返回链接根据创建订单请求的域名和协议生成；通过 ngrok/nginx 访问时，请从公网地址打开 Demo 并创建订单。真实回调地址为 `<公网地址>/notify`，仍需在商户后台单独登记。

ngrok 示例：`ngrok http 3000`（端口应与 Demo 一致）。nginx 请转发到 `http://127.0.0.1:3000`，设置 `proxy_set_header Host $http_host;` 和 `proxy_set_header X-Forwarded-Proto $scheme;`，并覆盖客户端传入的 `X-Forwarded-Host`：`proxy_set_header X-Forwarded-Host $http_host;`。Demo 仅信任来自本机回环地址的代理头，代理链应提供单个正确的域名和协议，不能传入逗号列表；公网入口应通过该代理访问。

```bash
mkdir -p build && javac -d build src/DspayMockMerchant.java src/DspayTransport.java
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -cp build DspayMockMerchant
```

后台运行可执行 `./start.sh`。脚本先询问 `MERCHANT_NO` 和 `API_SECRET`，密钥输入不回显。提前设置对应环境变量可跳过询问。`PORT` 可选，默认 `3000`；`FRONT_END_DIR` 可选，可指定包含 `index.html` 的其他前端目录（默认 `../../front-end`，脚本中的相对路径以 Java Demo 目录为基准）；直接运行 Java 时也支持此环境变量，`-DfrontEndDir` 优先。显式空值或缺少 `index.html` 会启动失败。输入值不会保存；下次启动需重新输入或通过环境变量提供。非交互运行只需提供上述两项凭证环境变量。

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

回调保留 JSON 大整数和小数精度，拒绝重复 JSON 字段。`timestamp` 必须是非负整数毫秒值，允许当前时间前后 5 分钟，包含边界。验签或时间窗不通过返回 HTTP 401。Demo 不额外设置回调正文的固定大小上限。

上游请求显式配置 10 秒超时，不跟随重定向。成功必须满足顶层整数 `code` 为 `0` 且包含 `data`；不能只看 HTTP 200 或 `header.resultCode`。HTTP 错误、业务码非零、响应格式异常或超时统一返回 HTTP 502，并保留诊断日志。网络库、代理默认行为和 TLS 特征仍由各运行时决定。

请求通过 `src/DspayTransport.java` 使用 Java 8 公共 `Socket` / `SSLSocket` API，以 HTTP/1.1 发送，与其他 Demo 统一 Chrome 147 User-Agent。无需 JDK 11 API、第三方库或原生二进制，不自动配置代理。HTTPS 校验证书链及主机名，最低 TLS 1.2。10 秒总时限覆盖连接、TLS 握手、发送及读取响应；支持定长、分块和连接关闭定界的响应、gzip/deflate，不跟随重定向。这是精简 POST 实现，不是完整移植 JDK 11 HttpClient。

本地测试：先执行 `mkdir -p /tmp/dspay-java`，再执行 `javac -d /tmp/dspay-java src/DspayMockMerchant.java src/DspayTransport.java test/DspayMockMerchantTest.java test/DspayTransportTest.java`，随后分别运行 `java -cp /tmp/dspay-java DspayMockMerchantTest` 和 `java -cp /tmp/dspay-java DspayTransportTest`。传输测试使用 JDK 自带的 `keytool` 生成临时证书，不关闭 TLS 校验。
