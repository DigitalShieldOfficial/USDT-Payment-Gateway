'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');
const { describeError, summarizeError } = require('./request-error');
const { postJson } = require('./transport');
const { JsonNumber, parseJson, stringifyJson } = require('./lossless-json');
const { signCreateOrder, signQuery, verifyCallback } = require('./signer');

const PORT = Number(process.env.PORT || 3000);
const DSPAY_BASE_URL = apiBase(process.env.DSPAY_BASE_URL ?? 'https://pay-api.ds.pro');
const MERCHANT_NO = process.env.MERCHANT_NO || 'change-me';
const API_SECRET = process.env.API_SECRET || 'change-me';
// Serve Demo/front-end by default; override the directory with the FRONT_END_DIR environment variable.
const FRONT_END_DIR = process.env.FRONT_END_DIR || path.resolve(__dirname, '../../../front-end');
function logEvent(event, fields) {
    fields = { ...fields };
    if (typeof fields.body === "string") { try { fields.body = parseJson(fields.body); } catch (_) {} }
    function redact(value) {
        if (value instanceof JsonNumber || value === null || typeof value !== 'object') return value;
        if (Array.isArray(value)) return value.map(redact);
        return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, /^(signature|apiSecret|API_SECRET)$/i.test(k) ? '[REDACTED]' : redact(v)]));
    }
    let text = stringifyJson(redact(fields));
    if (API_SECRET) text = text.split(JSON.stringify(API_SECRET).slice(1, -1)).join('[REDACTED]');
    console.log(`[${event}] ${text}`);
}

function requestOrigin(req) {
    const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
    const host = local && req.headers['x-forwarded-host'] !== undefined ? req.headers['x-forwarded-host'] : req.headers.host;
    const proto = local && req.headers['x-forwarded-proto'] !== undefined ? req.headers['x-forwarded-proto'] : 'http';
    if (!['http', 'https'].includes(proto)) throw Object.assign(new Error('X-Forwarded-Proto must be http or https'), { status: 400 });
    return apiBase(`${proto}://${host}`, 'Request origin');
}

function apiBase(value, field = 'DSPAY_BASE_URL') {
    try {
        const url = new URL(value);
        if (['http:', 'https:'].includes(url.protocol) && url.hostname && url.port !== '0'
            && !url.username && !url.password && !/[\s\x00-\x1f]/.test(value)
            && /^https?:\/\/[^/?#]+$/.test(value)) return value;
    } catch (_) {
        // Report the field requirement without rewriting the supplied value.
    }
    throw new Error(`${field} must be an http(s) origin without a trailing slash, path, query, credentials, or whitespace`);
}

function json(res, status, body) {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(stringifyJson(body));
}

function parameterError(message) { return Object.assign(new Error(message), {status: 400, demoCode: 'PARAM_ERROR'}); }
function parameters(url, allowed) {
    if ([...url.searchParams].length > 20) throw parameterError('At most 20 query fields are allowed');
    const result = Object.create(null);
    for (const [key, value] of url.searchParams) {
        if (!allowed.includes(key)) throw parameterError('Unsupported query field: ' + key);
        if (Object.hasOwn(result, key)) throw parameterError(key + ' must occur exactly once');
        if (value.includes('\ufffd')) throw parameterError(key + ' must be valid UTF-8');
        result[key] = value;
    }
    return result;
}
function identifier(value, field) {
    if (!(field === 'outOrderNo' ? /^[A-Za-z0-9-]{1,64}$/ : /^(?:DS)?[1-9][0-9]{14,18}$/).test(value)) throw parameterError(field + ' has an invalid format; whitespace is not allowed');
    return value;
}
function amount(value, field, integers, decimals, minimum = false) {
    const pattern = new RegExp('^(?:0|[1-9][0-9]{0,' + (integers - 1) + '})(?:\\.[0-9]{1,' + decimals + '})?$');
    if (!pattern.test(value) || (minimum && Number(value) < 0.01)) throw parameterError(field + ' must be a plain nonnegative decimal with at most ' + integers + ' integer and ' + decimals + ' fractional digits, no leading zeros or whitespace' + (minimum ? '; minimum 0.01' : ''));
    return value;
}
async function post(path, body) {
    logEvent('DSPAY_REQUEST', {method: 'POST', url: DSPAY_BASE_URL + path, body});
    try {
        const response = await postJson(DSPAY_BASE_URL + path, stringifyJson(body));
        const raw = response.body, cfRay = response.headers['cf-ray'];
        logEvent('DSPAY_RESPONSE', {path, status: response.status, cfRay, contentType: response.headers['content-type'], body: raw});
        if (response.status < 200 || response.status >= 300) throw new Error('DSPay HTTP ' + response.status + (cfRay ? '; CF-Ray: ' + cfRay : ''));
        const result = parseJson(raw);
        if (!result || Array.isArray(result) || !(result.code instanceof JsonNumber) || !result.code.integer) throw new Error('DSPay response must contain a top-level integer code');
        if (result.code.plain !== '0') throw new Error('DSPay business error: ' + result.code.plain);
        if (!Object.hasOwn(result, 'data')) throw new Error('DSPay response is missing data');
        return result.data;
    } catch (error) {
        const details = describeError(error);
        logEvent('DSPAY_ERROR', {path, error: error.message, details});
        const message = error.code === 'ETIMEDOUT' ? 'DSPay request timed out after 10 seconds' : summarizeError(details);
        throw Object.assign(new Error(message, {cause: error}), {status: 502, demoCode: 'DSPAY_ERROR'});
    }
}

async function createOrder(url, res, base) {
    const args = parameters(url, ['outOrderNo', 'payAmount', 'productPrice', 'productPriceCurrency', 'productId']);
    if (args.productPriceCurrency !== undefined && args.productPriceCurrency !== 'USD') throw parameterError('productPriceCurrency must be USD for this storefront');
    if (!Object.hasOwn(args, 'payAmount')) throw parameterError('payAmount is required; minimum 0.01');
    const outOrderNo = Object.hasOwn(args, 'outOrderNo') ? identifier(args.outOrderNo, 'outOrderNo') : 'DEMO-' + crypto.randomBytes(16).toString('hex');
    const order = {merchantNo: MERCHANT_NO, outOrderNo, payAmount: amount(args.payAmount, 'payAmount', 12, 2, true),
        attach: {demo: 'nodejs', customerId: 'CUST-1001'}, allowedPaymentMethods: [], timestamp: Date.now(),
        returnUrl: `${base}/payment/return?outOrderNo=${encodeURIComponent(outOrderNo)}`,
        successRedirectUrl: `${base}/payment/success?outOrderNo=${encodeURIComponent(outOrderNo)}`};
    if (Object.hasOwn(args, 'productPrice')) { order.productPrice = amount(args.productPrice, 'productPrice', 14, 6); order.productPriceCurrency = 'USD'; }
    if (Object.hasOwn(args, 'productPriceCurrency')) order.productPriceCurrency = args.productPriceCurrency;
    if (Object.hasOwn(args, 'productId')) {
        if ([...args.productId].length > 64) throw parameterError('productId must be at most 64 characters');
        order.productId = args.productId;
    }
    const signed = signCreateOrder(order, API_SECRET);

    const result = await post('/dspay/public/order/create', { ...order, signature: signed.signature });
    try {
        if (!result || typeof result.checkoutUrl !== 'string' || /[\s\x00-\x1f]/.test(result.checkoutUrl)) throw new Error();
        const checkout = new URL(result.checkoutUrl);
        if (!['http:', 'https:'].includes(checkout.protocol) || !checkout.hostname) throw new Error();
    } catch (_) { throw Object.assign(new Error('DSPay create response must contain a complete checkoutUrl'), {status: 502, demoCode: 'DSPAY_ERROR'}); }
    res.writeHead(302, { Location: result.checkoutUrl });
    res.end();
}

async function queryOrder(url, res) {
    const args = parameters(url, ['orderNo', 'outOrderNo']);
    if (!args.orderNo && !args.outOrderNo) throw parameterError('orderNo or outOrderNo is required');
    for (const [key, value] of Object.entries(args)) if (value !== '') identifier(value, key);
    const query = {...args, merchantNo: MERCHANT_NO, timestamp: Date.now()};
    const signed = signQuery(query, API_SECRET);
    const result = await post('/dspay/public/order/query', { ...query, signature: signed.signature });
    json(res, 200, result);
}

function notify(req, res) {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
        let rawBody;
        try { rawBody = new TextDecoder('utf-8', {fatal: true}).decode(Buffer.concat(chunks)); }
        catch (_) { return json(res, 400, {code: 'FAIL', msg: 'Callback must be UTF-8 JSON'}); }
        logEvent("CALLBACK_REQUEST", { body: rawBody, signature: req.headers["x-dspay-signature"] ? "[REDACTED]" : "missing" });
        if (!verifyCallback(rawBody, req.headers['x-dspay-signature'], API_SECRET)) {
            return json(res, 401, { code: 'FAIL', msg: 'Invalid signature, JSON, or timestamp (requires ±5 minutes)'  });
        }
        const payload = parseJson(rawBody);
        logEvent("CALLBACK_VERIFIED", { orderNo: payload.orderNo, result: "signature valid" });
        // Production: idempotently commit local business state before responding SUCCESS.
        json(res, 200, { code: 'SUCCESS', msg: 'ok' });
    });
}

const server = http.createServer(async (req, res) => {
    req.setTimeout(10000);
    try {
        const url = new URL(req.url, "http://localhost");
        if (['/create', '/query', '/notify'].includes(url.pathname)) {
            logEvent('DEMO_REQUEST', { method: req.method, target: req.url });
            const end = res.end;
            res.end = function (body, ...args) {
                logEvent('DEMO_RESPONSE', { path: url.pathname, status: res.statusCode, location: res.getHeader('Location'), body: body === undefined ? '' : String(body) });
                return end.call(this, body, ...args);
            };
        }
        if (req.method === 'GET' && url.pathname === '/create') return await createOrder(url, res, requestOrigin(req));
        if (req.method === 'GET' && url.pathname === '/query') return await queryOrder(url, res);
        if (req.method === 'POST' && url.pathname === '/notify') return notify(req, res);
        // returnUrl (cancel/return) -> store front page; successRedirectUrl -> order query
        // (a redirect is not proof of payment — success lands on /query showing the real server-side status)
        if (req.method === 'GET' && url.pathname === '/payment/return') {
            res.writeHead(302, { Location: '/' }); return res.end();
        }
        if (req.method === 'GET' && url.pathname === '/payment/success') {
            const args = parameters(url, ['outOrderNo']);
            if (!Object.hasOwn(args, 'outOrderNo')) throw parameterError('outOrderNo is required');
            const target = `/query?outOrderNo=${encodeURIComponent(identifier(args.outOrderNo, 'outOrderNo'))}`;
            res.writeHead(302, { Location: target }); return res.end();
        }
        // GET / serves the front-end storefront from the same origin as the API.
        if (req.method === 'GET') {
            const rel = (url.pathname === '/' || url.pathname === '/index.html') ? 'index.html' : decodeURIComponent(url.pathname).slice(1);
            const file = path.resolve(FRONT_END_DIR, rel);
            if (file.startsWith(FRONT_END_DIR) && fs.existsSync(file) && fs.statSync(file).isFile()) {
                res.writeHead(200, { 'Content-Type': file.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream' });
                return fs.createReadStream(file).pipe(res);
            }
        }
        json(res, 404, { code: 'NOT_FOUND' });
    } catch (error) {
        logEvent("DEMO_ERROR", { error: error.message });
        json(res, error.status || 500, { code: error.demoCode || (error.status === 400 ? 'PARAM_ERROR' : 'DEMO_ERROR'), message: error.message });
    }
});

server.listen(PORT, () => {
    console.log(`Mock merchant: http://localhost:${server.address().port}`);
    console.log(`DSPay API: ${DSPAY_BASE_URL}`);
    console.log('GET /create -> server-side create order -> 302 checkoutUrl');
    console.log(`Demo page: http://localhost:${server.address().port}/ (served from ${FRONT_END_DIR})`);
});
