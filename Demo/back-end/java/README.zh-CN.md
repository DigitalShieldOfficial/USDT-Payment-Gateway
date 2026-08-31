[English](README.md) | [中文](README.zh-CN.md)

# Java Mock Merchant

JDK 11+，零外部依赖。该服务真实调用 `POST /dspay/public/order/create`，并使用响应中的 `checkoutUrl` 跳转收银台。

版本基线：最低 JDK 11；已验证 Microsoft OpenJDK `11.0.27` 和 Eclipse Temurin `21.0.11`。无需 Maven/Gradle，源码只使用 JDK 标准库，并通过 JDK 11 单文件源码启动。

> 以下命令中的 `REPLACE_WITH_REAL_MERCHANT_NO`、`REPLACE_WITH_REAL_API_SECRET` 和 `REPLACE_WITH_REAL_DSPAY_API_HOST` 是占位值，执行前必须替换为真实参数。`merchantNo` 和 `apiSecret` 从DSPay商户后台获取。

```bash
java -DmerchantNo="REPLACE_WITH_REAL_MERCHANT_NO" -DapiSecret="REPLACE_WITH_REAL_API_SECRET" \
  -DdspayBase="https://REPLACE_WITH_REAL_DSPAY_API_HOST" \
  -DpublicBase="http://localhost:3000" src/DspayMockMerchant.java
```

- `GET /create`：签名预下单并 302 到响应中的 `checkoutUrl`
- `POST /notify`：Raw Body 回调验签
- 超时返回页只做提示；生产系统必须调用 `POST /dspay/public/order/query` 确认状态

完整说明见 [../../README.zh-CN.md](../../README.zh-CN.md)。
