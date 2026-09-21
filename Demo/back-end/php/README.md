[English](README.md) | [中文](README.zh-CN.md)

# DSPay PHP Mock Merchant

PHP 5.6+, no Composer. The backend calls the DSPay create/query APIs and redirects to the `checkoutUrl` returned by the create response.

Runtime baseline: PHP 5.6 minimum; all syntax checks, create-signature tests, and callback-verification tests were run with PHP CLI `5.6.40` and `8.5.10`. Composer is not required; only standard PHP extensions are used. `hash_equals()` requires PHP 5.6+.

> `REPLACE_WITH_REAL_MERCHANT_NO` and `REPLACE_WITH_REAL_API_SECRET` below are placeholders and must be replaced. `DSPAY_BASE_URL` is prefilled with the DSPay production API; change it only when testing another environment.

Run `./start.sh` and enter any missing `DSPAY_BASE_URL`, `PUBLIC_BASE_URL`, `MERCHANT_NO`, and `API_SECRET` values when prompted; secret input is hidden. `PORT` is optional and defaults to `3000`, and `FRONT_END_DIR` optionally overrides the served front-end directory (default `../../front-end`). Entered values are not saved. The server runs in the background (PID in `server.pid`); stop it with `./stop.sh`.

```bash
cd Demo/back-end/php
./start.sh
```

For non-interactive use, set the environment variables first:

```bash
cd Demo/back-end/php
export MERCHANT_NO="REPLACE_WITH_REAL_MERCHANT_NO"
export API_SECRET="REPLACE_WITH_REAL_API_SECRET"
export DSPAY_BASE_URL="https://REPLACE_WITH_REAL_DSPAY_API_HOST"
export PUBLIC_BASE_URL="http://localhost:3000"
php -S 0.0.0.0:3000 server.php
# background alternative: ./start.sh (prompts for missing variables, stop with ./stop.sh)
```

- `GET /create`: call `POST /dspay/public/order/create`, then 302 to returned `checkoutUrl`
- `GET /query?orderNo=...` or `?outOrderNo=...`: signed authoritative query
- `POST /notify`: verify `X-DSPay-Signature` over the shared ASCII-sorted canonical field string
- `GET /` serves the front-end store page (same origin as the API)
- `returnUrl` redirects back to the store page; `successRedirectUrl` lands on `/query` showing the real order status — a redirect is never proof of payment

Run local tests:

```bash
php test/ValidateCreateOrder.php
php test/VerifyCallback.php
php test/ValidateHttpCreate.php  # skipped unless TEST_CREATE_URL is configured
```
