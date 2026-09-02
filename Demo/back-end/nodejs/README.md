[English](README.md) | [中文](README.zh-CN.md)

# DSPay Node.js Mock Merchant

The single runtime baseline is Node.js `18.20.8` + npm `10.8.2`, with no npm dependencies. The service signs and calls `POST /dspay/public/order/create` and `/query`; it never builds a signed cashier URL in the browser.

The minimum and tested baseline are both Node.js `18.20.8` + npm `10.8.2`. The repository includes `.nvmrc`, and `package.json` declares `node >=18.20.8`. Multiple Node.js installations are not required, and no third-party npm packages are used.

```bash
nvm install
nvm use
node --version  # v18.20.8
npm --version   # 10.8.2
```

> `REPLACE_WITH_REAL_MERCHANT_NO`, `REPLACE_WITH_REAL_API_SECRET`, and `REPLACE_WITH_REAL_DSPAY_API_HOST` below are placeholders. Replace them with real values from the DSPay Merchant Portal before running.

```bash
cd Demo/back-end/nodejs
MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO" API_SECRET="REPLACE_WITH_REAL_API_SECRET" \
DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST" PUBLIC_BASE_URL="http://localhost:3000" npm start
```

- `GET /create`: server-to-server create, then 302 to returned `checkoutUrl`
- `GET /query?orderNo=...` or `?outOrderNo=...`: signed authoritative query
- `POST /notify`: verify `X-DSPay-Signature` over the shared ASCII-sorted canonical field string
- `/payment/return` handles timeout and `/payment/success` handles completion; both query DSPay before fulfillment

Run tests with `npm test`.
