[English](README.md) | [中文](README.zh-CN.md)

# DSPay 模拟商户后端（Node.js）

DSPay 接入测试用模拟商户后端：访问 `/create` 后，服务端在本地签名、拼接收银台 URL 并跳转用户；`/notify` 接收 DSPay 回调并验签。

## 环境要求

- Node.js 18+
- 零第三方依赖，仅使用内置 `http`、`crypto`、`url`、`fs`、`path` 模块

## 启动前配置

编辑 `src/server.js`，将占位凭证替换为真实 DSPay 商户信息：

```js
const MERCHANT_NO = process.env.MERCHANT_NO || 'change-me-to-your-merchantNo';
const API_SECRET = process.env.API_SECRET || 'change-me-to-your-apiSecret';
```

也可以通过环境变量传入，无需修改源码：

```bash
export MERCHANT_NO=M123456789
export API_SECRET=sk_yourApiSecret
```

**安全说明：** `merchantNo` 和 `apiSecret` 保存在服务端。代码会忽略查询参数中的其他商户编号或密钥，避免攻击者通过参数切换商户身份、绕过签名校验。

## 启动方式

### 前台运行（开发环境）

```bash
npm start
# 或：node src/server.js
```

### 后台运行（长期运行）

```bash
./start.sh    # 使用 nohup 后台启动
./stop.sh     # 停止服务
```

服务默认监听 `3000` 端口。指定其他端口：

```bash
PORT=4000 npm start
# 或：PORT=4000 ./start.sh
```

### 查看日志

所有请求日志写入 `logs/server.log`：

```bash
tail -f logs/server.log
```

## API 接口

### `GET /create` — 生成签名收银台链接并跳转

服务端在本地生成签名收银台 URL，并返回 HTTP 302 跳转；用户在 Hosted Cashier 选择链和代币后完成订单创建。

**查询参数：**

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `payAmount` | 否 | `0.01` | 支付金额；必须为大于 0、最多 2 位小数的普通十进制字符串，参考 [SDK 精度规则](../../../SDK/SDK.zh-CN.md#订单尾数机制详解) |
| `productPrice` | 否 | `0.01` | 商品价格 |
| `productPriceCurrency` | 否 | `USD` | 价格币种 |
| `productId` | 否 | `NOVA-LIFETIME-001` | 商品 ID |

> **`payAmount` 精度限制：** 稳定币统一按 6 位精度处理。商户最多提交 2 位小数，后 4 位由 DSPay 生成订单尾数。`100` / `100.1` / `100.12` 合法，`100.123` 不合法。必须使用普通十进制字符串，不能转为 JavaScript `number` 或使用科学计数法。超过 2 位小数时返回 [`50612`](../../../SDK/SDK.zh-CN.md#error-50612)。

**处理流程：**

1. 自动生成 `timestamp = Date.now()` 和基于时间戳的 `outOrderNo`
2. 使用服务端保存的 `apiSecret`，按 `merchantNo → outOrderNo → payAmount → timestamp` 顺序对 4 个字段计算 HMAC-SHA256
3. 返回 HTTP 302，跳转到 `https://cashier.ds.pro/?merchantNo=...&outOrderNo=...&payAmount=...&timestamp=...&signature=...&productPrice=...&productPriceCurrency=...&productId=...`

**示例：**

```bash
# 使用全部默认值
curl -L 'http://localhost:3000/create'

# 自定义金额
curl -L 'http://localhost:3000/create?payAmount=100'

# 自定义金额和商品
curl -L 'http://localhost:3000/create?payAmount=50&productId=MY-PRODUCT-001&productPrice=50'
```

### `POST /notify` — 接收 DSPay 回调并验签

接收 DSPay 支付状态回调，并验证 `X-DSPay-Signature` 请求头。

**请求头：**

| 请求头 | 说明 |
|--------|------|
| `X-DSPay-Signature` | 原始请求体的 HMAC-SHA256 hex 签名 |

**处理流程：**

1. 读取原始请求体，不做 JSON 重新序列化
2. 使用服务端保存的 `apiSecret` 对原始请求体计算 HMAC-SHA256
3. 与 `X-DSPay-Signature` 做常量时间比较
4. 匹配时返回 `{"code":"SUCCESS","msg":"ok"}`；`SUCCESS` 必须严格大写，否则 DSPay 会重试
5. 不匹配时返回 `401 {"code":"FAIL","msg":"signature invalid"}`
6. 未配置 `API_SECRET` 时返回 `500`

**本地测试：**

```bash
# 替换为真实 apiSecret
API_SECRET="sk_yourApiSecret"

BODY='{"orderNo":"DS001","status":"COMPLETED","payAmount":"0.01"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$API_SECRET" -hex | awk '{print $2}')
curl -X POST http://localhost:3000/notify \
  -H "Content-Type: application/json" \
  -H "X-DSPay-Signature: $SIG" \
  -d "$BODY"
```

## 签名规则

### 创建收银台链接

签名字段及顺序（**顺序敏感**）：

```text
merchantNo → outOrderNo → payAmount → timestamp
```

规范化字符串：

```text
merchantNo={merchantNo}&outOrderNo={outOrderNo}&payAmount={payAmount}&timestamp={timestamp}
```

- `outOrderNo` 必填且不能为空，必须同时参与签名并出现在收银台 URL 中；字段名区分大小写，必须写作 `outOrderNo`，不是 `outOrderNO`
- `payAmount` 必须大于 0、最多 2 位小数，并保持普通十进制字符串；不能转为 JavaScript `number`，也不能使用科学计数法，违反精度约定时返回 [`50612`](../../../SDK/SDK.zh-CN.md#error-50612)
- HMAC-SHA256 输出为 hex 小写

### 回调验签

签名基于**原始请求体**计算。不要先 `JSON.parse` 再重新序列化；字段顺序或空白变化都会改变字节序列并导致验签失败。

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | `3000` | 服务监听端口 |
| `CASHIER_BASE` | `https://cashier.ds.pro/` | 收银台基础 URL |
| `MERCHANT_NO` | `change-me-to-your-merchantNo` | 商户编号（代码内置回退值） |
| `API_SECRET` | `change-me-to-your-apiSecret` | 商户 apiSecret（代码内置回退值） |

## 项目结构

```text
dspay-mock-merchant/
├── src/
│   ├── server.js    # HTTP 服务、路由和日志
│   └── signer.js    # HMAC-SHA256 signOrder + verifyCallback
├── logs/
│   └── server.log   # 请求日志（自动创建）
├── start.sh         # 后台启动
├── stop.sh          # 后台停止
├── package.json
├── README.md
└── README.zh-CN.md
```
