[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP Mock Merchant

PHP 5.6+, no Composer. The backend calls the DSPay create/query APIs and redirects to the `checkoutUrl` returned by the create response.

Runtime baseline: PHP 5.6 minimum; all syntax checks, create-signature tests, and callback-verification tests were run with PHP CLI `5.6.40` and `8.5.10`. Composer is not required; only standard PHP extensions are used. `hash_equals()` requires PHP 5.6+.

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders and must be replaced. `DSPAY_BASE_URL` is prefilled with the DSPay production API; change it only when testing another environment.

```bash
cd Demo/back-end/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://wallet.ds.pro"
export PUBLIC_BASE_URL="http://localhost:3000"
./start.sh
```

- `GET /create`: call `POST /dspay/public/order/create`, then 302 to returned `checkoutUrl`
- `GET /query?orderNo=...` or `?outOrderNo=...`: signed authoritative query
- `POST /notify`: verify `X-DSPay-Signature` over the shared ASCII-sorted canonical field string
- timeout and success pages query DSPay; redirects are never proof of payment

Run local tests:

```bash
php test/ValidateCreateOrder.php
php test/VerifyCallback.php
php test/ValidateHttpCreate.php  # skipped unless TEST_CREATE_URL is configured
```
