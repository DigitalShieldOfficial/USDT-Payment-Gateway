[English](README.md) | [中文](README.zh-CN.md)

# DSPay 模拟商户后端（Java）

DSPay 接入测试用模拟商户后端：`/create` 在本地签名、拼接收银台 URL 并跳转用户；`/notify` 接收 DSPay 回调并验签。

## 环境要求

- JDK 11+
- 零外部依赖，仅使用 JDK 内置 `com.sun.net.httpserver` 和 `javax.crypto`

## 快速启动

### 方式一：后台运行（推荐）

```bash
cd back-end/java
./start.sh   # 后台启动
./stop.sh    # 停止服务
```

### 方式二：单文件前台运行（Java 11+）

```bash
cd back-end/java
java -Dfile.encoding=UTF-8 src/DspayMockMerchant.java
```

指定端口：

```bash
java -Dfile.encoding=UTF-8 -Dport=4000 src/DspayMockMerchant.java
```

## 配置

所有配置均可通过 `-D` 系统属性传入：

| 属性 | 默认值 | 说明 |
|---|---|---|
| `port` | `3000` | 服务端口 |
| `cashierBase` | `https://cashier.ds.pro/` | 收银台基础 URL |
| `merchantNo` | `change-me-to-your-merchantNo` | 商户编号 |
| `apiSecret` | `change-me-to-your-apiSecret` | API 密钥，用于签名和验签 |

示例：

```bash
java -Dfile.encoding=UTF-8 -Dport=4000 -DmerchantNo=M2079022817467412481 -DapiSecret=my-secret \
     src/DspayMockMerchant.java
```

## 接口

### `GET /create` — 生成签名收银台链接并跳转

商户后端在本地签名并将用户跳转到 Hosted Cashier，用户在收银台选择链和代币后完成订单创建。

商户凭证 `merchantNo + apiSecret` 固定保存在服务端，**不接受**查询参数覆盖，防止攻击者切换商户身份。查询参数只能覆盖业务字段。

**查询参数（均可选，未传时使用默认值）：**

| 参数 | 默认值 | 说明 |
|---|---|---|
| `payAmount` | `0.01` | 支付金额；必须为大于 0、最多 2 位小数的普通十进制字符串，参考 [SDK 精度规则](../../../SDK/SDK.zh-CN.md#订单尾数机制详解) |
| `productPrice` | `0.01` | 商品价格 |
| `productPriceCurrency` | `USD` | 价格币种 |
| `productId` | `NOVA-LIFETIME-001` | 商品 ID |

> **`payAmount` 精度限制：** 稳定币统一按 6 位精度处理。商户最多提交 2 位小数，后 4 位由 DSPay 生成订单尾数。`100` / `100.1` / `100.12` 合法，`100.123` 不合法。必须使用普通十进制字符串，不能使用 `double` / `float` 或科学计数法。超过 2 位小数时返回 [`50612`](../../../SDK/SDK.zh-CN.md#error-50612)。

**处理流程：**

1. 生成 `outOrderNo = String(System.currentTimeMillis())` 和 `timestamp`
2. 按固定顺序签名 4 个字段：`merchantNo → outOrderNo → payAmount → timestamp`
3. 返回 HTTP 302，跳转到 `{cashierBase}?merchantNo=...&outOrderNo=...&payAmount=...&timestamp=...&signature=...&productPrice=...&productPriceCurrency=...&productId=...`

**示例：**

```text
http://localhost:3000/create
http://localhost:3000/create?payAmount=0.05&productId=NOVA-001
```

### `POST /notify` — 接收 DSPay 回调并验签

**请求头：** `X-DSPay-Signature: <hmac-sha256-hex>`

**处理流程：**

1. 读取原始请求体，不做 JSON 解析后重新序列化
2. 使用服务端保存的 `apiSecret` 对原始请求体计算 HMAC-SHA256
3. 使用 `MessageDigest.isEqual` 与 `X-DSPay-Signature` 做常量时间比较
4. 验签成功：返回 `200 {"code":"SUCCESS","msg":"ok"}`；`SUCCESS` 必须严格大写，否则 DSPay 会重试
5. 验签失败：返回 `401 {"code":"FAIL","msg":"signature invalid"}`

**本地测试：**

```bash
BODY='{"orderNo":"DS001","status":"COMPLETED","payAmount":"0.01"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "change-me-to-your-apiSecret" -hex | awk '{print $NF}')
curl -X POST http://localhost:3000/notify \
  -H "Content-Type: application/json" \
  -H "X-DSPay-Signature: $SIG" \
  -d "$BODY"
```

## 签名规则

**创建收银台链接（商户 → DSPay）：**

```text
canonical = merchantNo={m}&outOrderNo={o}&payAmount={p}&timestamp={t}
signature = HMAC-SHA256(canonical, apiSecret) → hex 小写
```

- 字段顺序敏感，顺序错误会返回 [`50613`](../../../SDK/SDK.zh-CN.md#error-50613)
- `outOrderNo` 必填且不能为空，必须同时参与签名并出现在收银台 URL 中；字段名区分大小写，必须写作 `outOrderNo`，不是 `outOrderNO`
- `payAmount` 必须大于 0、最多 2 位小数，并保持普通十进制字符串；不能先转为 `double` / `float`，也不能使用科学计数法，违反精度约定时返回 [`50612`](../../../SDK/SDK.zh-CN.md#error-50612)

**回调验签（DSPay → 商户）：**

```text
expected = HMAC-SHA256(rawBody, apiSecret)
compare expected == X-DSPay-Signature（常量时间比较）
```

- 必须直接使用原始请求体。不要先 `JSON.parse` 再 `JSON.stringify`，字段顺序或空白变化都会改变字节序列并导致验签失败

## 日志

日志同时输出到控制台和 `logs/server.log`。
