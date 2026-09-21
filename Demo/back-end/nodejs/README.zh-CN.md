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

后台运行可直接执行 `./start.sh`。脚本会询问缺少的 `DSPAY_BASE_URL`、`PUBLIC_BASE_URL`、`MERCHANT_NO` 和 `API_SECRET`，密钥输入不回显。`PORT` 可选，默认 `3000`；`FRONT_END_DIR` 可选，可指定其他前端目录（默认 `../../front-end`）。输入值不会保存；下次启动需重新输入或提前设置环境变量。

```bash
cd Demo/back-end/nodejs
./start.sh
```

前台交互运行可用 `npm start`（等价于 `node src/server.js`）。

非交互运行时，先设置环境变量：

```bash
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST"
export PUBLIC_BASE_URL="http://localhost:3000"
npm start        # 等价于 node src/server.js
```

后台运行可选 `./start.sh`（缺变量时交互提示，`./stop.sh` 停止）。

- `GET /create`：签名预下单并 302 到响应中的 `checkoutUrl`
- `GET /query?orderNo=...` 或 `?outOrderNo=...`：签名查询
- `POST /notify`：Raw Body 回调验签
- `GET /` 托管前端商店页（与接口同源；可用 `FRONT_END_DIR` 指定其他目录）
- `returnUrl` 跳回商店首页；`successRedirectUrl` 跳到 `/query` 展示真实订单状态——浏览器跳转不能作为发货依据

完整说明见 [../../README.zh-CN.md](../../README.zh-CN.md)。
