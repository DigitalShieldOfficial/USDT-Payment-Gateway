'use strict';

const http = require('http');
const { URL } = require('url');
const { signCreateOrder, signQuery, verifyCallback } = require('./signer');

const PORT = Number(process.env.PORT || 3000);
const DSPAY_BASE_URL = (process.env.DSPAY_BASE_URL || '').replace(/\/$/, '');
const MERCHANT_NO = process.env.MERCHANT_NO || 'change-me';
const API_SECRET = process.env.API_SECRET || 'change-me';
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || `http://localhost:${PORT}`).replace(/\/$/, '');

if (!DSPAY_BASE_URL) throw new Error('DSPAY_BASE_URL is required');

function json(res, status, body) {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify(body));
}

async function post(path, body) {
    const response = await fetch(`${DSPAY_BASE_URL}${path}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    const raw = await response.text();
    let result;
    try { result = JSON.parse(raw); } catch { result = { raw }; }
    if (!response.ok) throw Object.assign(new Error(`DSPay HTTP ${response.status}`), { status: response.status, result });
    const resultCode = result?.code ?? result?.header?.resultCode ?? 0;
    if (resultCode !== 0) {
        throw Object.assign(new Error(result?.message || result?.header?.message || `DSPay code ${resultCode}`), {
            status: 502, result,
        });
    }
    // wallet 统一响应格式为 { code, data, header }；兼容无包装的历史响应。
    return Object.prototype.hasOwnProperty.call(result, 'data') ? result.data : result;
}

async function createOrder(url, res) {
    const outOrderNo = url.searchParams.get('outOrderNo') || `DEMO-${Date.now()}`;
    const order = {
        merchantNo: MERCHANT_NO,
        outOrderNo,
        productPrice: url.searchParams.get('productPrice') || '0.02',
        productPriceCurrency: 'USD',
        productId: url.searchParams.get('productId') || 'NOVA-LIFETIME-001',
        attach: { demo: 'nodejs', customerId: 'CUST-1001' },
        payAmount: url.searchParams.get('payAmount') || '0.02',
        allowedPaymentMethods: [],
        returnUrl: `${PUBLIC_BASE_URL}/payment/return?outOrderNo=${encodeURIComponent(outOrderNo)}`,
        successRedirectUrl: `${PUBLIC_BASE_URL}/payment/success?outOrderNo=${encodeURIComponent(outOrderNo)}`,
        timestamp: Date.now(),
    };
    const signed = signCreateOrder(order, API_SECRET);
    console.log('[CREATE canonical]', signed.canonical);
    const result = await post('/dspay/public/order/create', { ...order, signature: signed.signature });
    if (!result.checkoutUrl) return json(res, 502, { code: 'INVALID_DSPAY_RESPONSE', result });
    res.writeHead(302, { Location: result.checkoutUrl });
    res.end();
}

async function queryOrder(url, res) {
    const query = { merchantNo: MERCHANT_NO, timestamp: Date.now() };
    if (url.searchParams.get('orderNo')) query.orderNo = url.searchParams.get('orderNo');
    if (url.searchParams.get('outOrderNo')) query.outOrderNo = url.searchParams.get('outOrderNo');
    if (!query.orderNo && !query.outOrderNo) return json(res, 400, { code: 'ORDER_NO_REQUIRED' });
    const signed = signQuery(query, API_SECRET);
    const result = await post('/dspay/public/order/query', { ...query, signature: signed.signature });
    json(res, 200, result);
}

function notify(req, res) {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
        const rawBody = Buffer.concat(chunks).toString('utf8');
        if (!verifyCallback(rawBody, req.headers['x-dspay-signature'], API_SECRET)) {
            return json(res, 401, { code: 'FAIL', msg: 'signature invalid' });
        }
        const payload = JSON.parse(rawBody);
        console.log('[NOTIFY verified]', payload.orderNo, payload.eventType, payload.receivingAddress);
        // Production: idempotently commit local business state before responding SUCCESS.
        json(res, 200, { code: 'SUCCESS', msg: 'ok' });
    });
}

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, PUBLIC_BASE_URL);
    try {
        if (req.method === 'GET' && url.pathname === '/create') return await createOrder(url, res);
        if (req.method === 'GET' && url.pathname === '/query') return await queryOrder(url, res);
        if (req.method === 'POST' && url.pathname === '/notify') return notify(req, res);
        if (req.method === 'GET' && ['/payment/return', '/payment/success'].includes(url.pathname)) {
            const target = `/query?outOrderNo=${encodeURIComponent(url.searchParams.get('outOrderNo') || '')}`;
            res.writeHead(302, { Location: target }); return res.end();
        }
        json(res, 404, { code: 'NOT_FOUND' });
    } catch (error) {
        console.error(error);
        json(res, error.status || 500, { code: 'DEMO_ERROR', message: error.message, dspay: error.result });
    }
});

server.listen(PORT, () => {
    console.log(`Mock merchant: ${PUBLIC_BASE_URL}`);
    console.log(`DSPay API: ${DSPAY_BASE_URL}`);
    console.log('GET /create -> server-side create order -> 302 checkoutUrl');
});
