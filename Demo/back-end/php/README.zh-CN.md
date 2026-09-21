[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP 模拟商户

PHP 5.6+，无需 Composer。服务端调用 DSPay 创建和查询接口，并使用创建响应中的 `checkoutUrl` 跳转收银台。

版本基线：最低 PHP 5.6；已在 PHP CLI `5.6.40` 和 `8.5.10` 执行全部语法检查、创建签名测试和回调验签测试。无需 Composer，只使用 PHP 标准扩展；`hash_equals()` 要求 PHP 5.6+。

> `REPLACE_WITH_REAL_MERCHANT_NO` 和 `REPLACE_WITH_REAL_API_SECRET` 是占位值，执行前必须替换。`DSPAY_BASE_URL` 已填写DSPay正式API地址；测试其他环境时再修改。

执行 `./start.sh` 后按提示输入缺少的 `DSPAY_BASE_URL`、`PUBLIC_BASE_URL`、`MERCHANT_NO` 和 `API_SECRET`；密钥输入不回显。`PORT` 可选，默认 `3000`；`FRONT_END_DIR` 可选，可指定其他前端目录（默认 `../../front-end`）。输入值不会保存。服务在后台运行（PID 记录在 `server.pid`），执行 `./stop.sh` 停止。

```bash
cd Demo/back-end/php
./start.sh
```

非交互运行时，先设置环境变量：

```bash
cd Demo/back-end/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST"
export PUBLIC_BASE_URL="http://localhost:3000"
php -S 0.0.0.0:3000 server.php
# 后台运行可选：./start.sh（缺变量时交互提示，./stop.sh 停止）
```

- `GET /create`：签名调用 `POST /dspay/public/order/create`，随后 302 到响应中的 `checkoutUrl`
- `GET /query?orderNo=...` 或 `?outOrderNo=...`：签名主动查询
- `POST /notify`：使用 Raw Body 和 `X-DSPay-Signature` 验签
- `GET /` 托管前端商店页（与接口同源）
- `returnUrl` 跳回商店首页；`successRedirectUrl` 跳到 `/query` 展示真实订单状态——浏览器跳转不能作为发货依据

本地测试：

```bash
php test/ValidateCreateOrder.php
php test/VerifyCallback.php
php test/ValidateHttpCreate.php  # 未配置 TEST_CREATE_URL 时跳过线上联调
```
