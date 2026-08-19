'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL, URLSearchParams } = require('url');
const { signOrder, verifyCallback, normalizePayAmount } = require('./signer');

// ==================== Configuration ====================

const PORT = Number(process.env.PORT) || 3000;
const CASHIER_BASE = process.env.CASHIER_BASE || 'https://cashier.ds.pro/';

// Log directory and file
const LOG_DIR = path.join(__dirname, '..', 'logs');
const LOG_FILE = path.join(LOG_DIR, 'server.log');

// ==================== Logger ====================

// Ensure logs directory exists
if (!fs.existsSync(LOG_DIR)) {
    fs.mkdirSync(LOG_DIR, { recursive: true });
}

const logStream = fs.createWriteStream(LOG_FILE, { flags: 'a' });

/**
 * Write to both console and log file with timestamp.
 * @param {'INFO'|'ERROR'} level
 * @param {...any} args
 */
function log(level, ...args) {
    const ts = new Date().toISOString();
    const prefix = `[${ts}] [${level}]`;
    const msg = [prefix, ...args].join(' ');

    if (level === 'ERROR') {
        console.error(msg);
    } else {
        console.log(msg);
    }
    logStream.write(msg + '\n');
}

const info = (...args) => log('INFO', ...args);
const error = (...args) => log('ERROR', ...args);

// ---------------------------------------------------------------------------
// [Security] Hardcoded merchant credentials (with env var fallback)
//
// merchantNo and apiSecret are hardcoded in the server code. All external
// requests use these fixed credentials for signing. Query parameters for
// other merchant IDs or secrets are deliberately ignored.
//
// This prevents attackers from switching merchant identity via query params
// to bypass signature verification.
//
// To change credentials, edit the values below or set env vars and restart.
// In production, credentials should come from config center / DB / KMS,
// never from code files or environment variables.
// ---------------------------------------------------------------------------
const MERCHANT_NO = process.env.MERCHANT_NO || 'change-me-to-your-merchantNo';
const API_SECRET = process.env.API_SECRET || 'change-me-to-your-apiSecret';

// Default business params (overridable via query string, fallback to these)
const FIXED_PARAMS = {
    // Stablecoins use 6 decimals. Merchants may submit at most 2 decimal places;
    // DSPay reserves the remaining 4 for order suffixes (error 50612 otherwise).
    payAmount: '0.01',
    productPrice: '0.01',
    productPriceCurrency: 'USD',
    productId: 'NOVA-LIFETIME-001',
};

// ==================== Server ====================

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const path = url.pathname;

    if (path === '/create' && req.method === 'GET') {
        return handleCreate(url, res);
    }
    if (path === '/notify' && req.method === 'POST') {
        return handleNotify(req, res);
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ code: 'NOT_FOUND', msg: `unknown path: ${req.method} ${path}` }));
});

// ==================== /create: Create order + redirect to cashier ====================

/**
 * Full-param order creation endpoint.
 *
 * Optional params (fallback to FIXED_PARAMS defaults):
 *   payAmount, productPrice, productPriceCurrency, productId
 *
 * Examples:
 *   /create                           → all defaults
 *   /create?payAmount=100             → custom amount
 *   /create?payAmount=50&productId=88 → custom fields
 */
function handleCreate(url, res) {
    const p = url.searchParams;

    // merchantNo and apiSecret are hardcoded server-side — query params ignored
    // to prevent identity spoofing via arbitrary merchant credentials
    const merchantNo = MERCHANT_NO;
    const apiSecret = API_SECRET;

    // Read optional params (query overrides FIXED_PARAMS defaults)
    let payAmount;
    try {
        payAmount = normalizePayAmount(p.get('payAmount') || FIXED_PARAMS.payAmount);
    } catch (validationError) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify({ code: 'FAIL', msg: validationError.message }));
    }
    const productPrice = p.get('productPrice') || FIXED_PARAMS.productPrice;
    const productPriceCurrency = p.get('productPriceCurrency') || FIXED_PARAMS.productPriceCurrency;
    const productId = p.get('productId') || FIXED_PARAMS.productId;
    const outOrderNo = String(Date.now());
    const timestamp = Date.now();

    // Sign order (4-field canonical signature)
    const signature = signOrder(
        { merchantNo, outOrderNo, payAmount, timestamp },
        apiSecret
    );

    // Build cashier redirect URL
    const params = new URLSearchParams();
    params.set('merchantNo', merchantNo);
    params.set('outOrderNo', outOrderNo);
    params.set('payAmount', payAmount);
    params.set('timestamp', String(timestamp));
    params.set('signature', signature);
    params.set('productPrice', productPrice);
    params.set('productPriceCurrency', productPriceCurrency);
    params.set('productId', productId);
    const redirectUrl = CASHIER_BASE + '?' + params.toString();

    info(`[CREATE] merchantNo=${merchantNo} outOrderNo=${outOrderNo} payAmount=${payAmount} timestamp=${timestamp}`);
    info(`[CREATE] signature=${signature}`);
    info(`[CREATE] redirect → ${redirectUrl}`);

    res.writeHead(302, {
        Location: redirectUrl,
        'Content-Type': 'text/plain; charset=utf-8',
    });
    res.end(`Redirecting to ${redirectUrl}`);
}

// ==================== /notify: Receive DSPay callback + verify signature ====================

function handleNotify(req, res) {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
        const rawBody = Buffer.concat(chunks).toString('utf8');
        const signatureHeader = req.headers['x-dspay-signature'] || '';

        // apiSecret: always use the hardcoded server-side value — never trust query params
        const apiSecret = API_SECRET;

        info(`[NOTIFY] received, length=${rawBody.length}`);
        info(`[NOTIFY] X-DSPay-Signature=${signatureHeader}`);
        info(`[NOTIFY] body=${rawBody}`);

        if (!apiSecret) {
            error('[NOTIFY] API_SECRET not configured; set env var or edit src/server.js');
            res.writeHead(500, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({
                code: 'FAIL',
                msg: 'API_SECRET not configured; set env var or edit src/server.js',
            }));
        }

        const ok = verifyCallback(rawBody, signatureHeader, apiSecret);
        info(`[NOTIFY] verify=${ok}`);

        if (!ok) {
            res.writeHead(401, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({ code: 'FAIL', msg: 'signature invalid' }));
        }

        // Signature valid: parse business fields (log only; production should persist + idempotent)
        try {
            const payload = JSON.parse(rawBody);
            info(`[NOTIFY] ✓ orderNo=${payload.orderNo} status=${payload.status} amount=${payload.payAmount}`);
        } catch (e) {
            info('[NOTIFY] body is not JSON, skipping parse');
        }

        // DSPay requires strictly uppercase "SUCCESS", otherwise it will retry
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ code: 'SUCCESS', msg: 'ok' }));
    });
}

// ==================== Startup ====================

server.listen(PORT, () => {
    info(`DSPay mock merchant running at http://localhost:${PORT}`);
    info(`  merchantNo: ${MERCHANT_NO}`);
    info(`  GET  /create?payAmount=0.01&productPrice=0.01&productPriceCurrency=USD&productId=123`);
    info(`  POST /notify  → Receive DSPay callback + verify signature (apiSecret hardcoded)`);
    info(`  cashier base: ${CASHIER_BASE}`);
});
