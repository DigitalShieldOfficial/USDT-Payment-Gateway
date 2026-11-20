# Demo security configuration and deployment

## Simple startup

Install the language runtime, run `./start.sh`, and enter the merchant number and API secret, or provide `MERCHANT_NO` and `API_SECRET` beforehand. No code changes are required. All four implementations require explicit credentials without fallback values. Inputs are used as supplied, without trimming or automatic correction. Direct Java startup also supports `-DmerchantNo` and `-DapiSecret`; prefer the environment for secrets to avoid exposing them in command-line arguments.

For PHP, use `./start.sh`, which validates credentials and URLs before opening the listening port. With a direct `php -S ... server.php` invocation, PHP executes the router only when a request arrives: invalid configuration fails the request but does not terminate the development server. PHP's built-in server is for development testing only.

## Optional settings

| Setting | Default behavior | Purpose |
| --- | --- | --- |
| `PUBLIC_BASE_URL` | Omit `returnUrl` and `successRedirectUrl`; payer stays on checkout | Explicit trusted HTTP(S) origin for payment return links |
| `PORT` | `3000` | Alternative listening port |
| `DSPAY_BASE_URL` | `https://pay-api.ds.pro` | Alternative integration environment |
| `FRONT_END_DIR` | Bundled Demo frontend | Serve another demonstration page |

From the relevant backend directory:

```bash
PUBLIC_BASE_URL=https://shop.example.com ./start.sh
```

`PUBLIC_BASE_URL` must be a complete HTTP(S) origin without a trailing slash, path, query, fragment, credentials, or whitespace. An absent value omits return links; an explicitly empty or invalid value is rejected. Return URLs use only this configuration, never `Host`, `X-Forwarded-Host`, or `X-Forwarded-Proto`. Update the setting and restart when your ngrok domain changes.

This setting does not change the listening address or register a webhook. Register `https://shop.example.com/notify` separately in the Merchant Portal for real callback delivery.

## Public deployment boundaries

Create/query endpoints intentionally do not implement user login. Public order creation can be legitimate, but production integrations must enforce their own product and amount rules, order ownership, sensitive query access, and abuse prevention. These demos are not complete merchant applications. Node no longer enables wildcard CORS; open the same-origin page served by the backend. Cross-origin integrations should explicitly configure permitted origins in the merchant's application or proxy.

Binding to all network interfaces supports containers and callback testing. Actual exposure depends on networking. For same-host backend callers, the following illustrative locations can be merged into an existing HTTPS Nginx `server` block:

```nginx
client_max_body_size 64k;

location = /notify {
    proxy_pass http://127.0.0.1:3000;
}

location = /create {
    allow 127.0.0.1;
    allow ::1;
    deny all;
    proxy_pass http://127.0.0.1:3000;
}

location = /query {
    allow 127.0.0.1;
    allow ::1;
    deny all;
    proxy_pass http://127.0.0.1:3000;
}

# Do not expose other demo routes. Add business access controls if serving a storefront.
location / {
    return 404;
}
```

This example restricts create/query to same-host callers; direct public browser requests will be denied. Adjust rules for separate servers, containers, or public checkout flows. Restrict direct access to backend port 3000 using firewall or container port mappings so only trusted callers or the proxy can reach it; otherwise callers could bypass Nginx. These example rules are not installed automatically.

## Callback processing

All four implementations limit callback bodies to 65536 bytes and return HTTP 413 for oversized bodies. Node and Java also enforce this limit during streamed reads. Python requires a valid `Content-Length` and does not support chunked request bodies. PHP limits router reads, but the development server may buffer requests before invoking the router, so enforce a body limit at the public ingress too. Configure proxy read timeouts and rate limits as appropriate for your deployment.

Successful verification establishes signature and timestamp validity. The demo's `SUCCESS` response only acknowledges demonstration verification. Production handlers must validate order existence, merchant, amount, currency, and status, and commit business changes idempotently before acknowledging success. A browser redirect is not proof of payment.
