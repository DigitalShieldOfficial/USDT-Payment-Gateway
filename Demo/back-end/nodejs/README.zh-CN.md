[English](README.md) | [中文](README.zh-CN.md)

# Node.js Mock Merchant

统一使用 Node.js `18.20.8` + npm `10.8.2`，零 npm 依赖。该服务真实调用 `POST /dspay/public/order/create` 和 `/query`，并使用响应中的 `checkoutUrl` 跳转收银台。

版本基线和已验证版本均为 Node.js `18.20.8` + npm `10.8.2`。仓库提供 `.nvmrc`，`package.json` 声明 `node >=18.20.8`，不需要多个Node版本，也没有第三方npm包。

```bash
nvm install
nvm use
node --version  # v18.20.8
npm --version   # 10.8.2
```

> 启动命令中的 `REPLACE_WITH_REAL_MERCHANT_NO`、`REPLACE_WITH_REAL_API_SECRET` 和 `REPLACE_WITH_REAL_DSPAY_API_HOST` 是占位值，必须替换为真实参数。`merchantNo` 和 `apiSecret` 从DSPay商户后台获取。

```bash
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST"
export PUBLIC_BASE_URL="http://localhost:3000"
npm start
```

- `GET /create`：签名预下单并 302 到响应中的 `checkoutUrl`
- `GET /query?orderNo=...` 或 `?outOrderNo=...`：签名查询
- `POST /notify`：Raw Body 回调验签

完整说明见 [../../README.zh-CN.md](../../README.zh-CN.md)。
