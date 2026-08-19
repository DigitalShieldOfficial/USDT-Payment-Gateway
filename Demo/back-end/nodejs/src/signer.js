'use strict';

const crypto = require('crypto');

/**
 * DSPay signature utilities
 *
 * Both directions share the same apiSecret:
 *   - signOrder      merchant → DSPay: 4-field canonical signature for order creation
 *   - verifyCallback DSPay → merchant: HMAC-SHA256 over raw callback body
 */

/**
 * Create order signature: canonical string → HMAC-SHA256 → lowercase hex.
 *
 * Signed fields and order (order-sensitive):
 *   merchantNo → outOrderNo → payAmount → timestamp
 *
 * HMAC-SHA256 is byte-sequence-sensitive; wrong field order results in
 * signature mismatch and verification failure (error code 50613).
 *
 * @param {object} p
 * @param {string} p.merchantNo  Merchant ID
 * @param {string} p.outOrderNo  Required merchant external order number (non-blank, max 64 characters)
 * @param {string} p.payAmount  Positive plain decimal string with at most 2 decimal
 *   places. Stablecoins use 6 decimals; DSPay reserves the remaining 4 for its suffix.
 * @param {number} p.timestamp   Unix timestamp in milliseconds
 * @param {string} apiSecret     Merchant apiSecret
 * @returns {string} Signature hex string
 */
function signOrder({ merchantNo, outOrderNo, payAmount, timestamp }, apiSecret) {
    const normalizedOutOrderNo = normalizeOutOrderNo(outOrderNo);
    const normalizedPayAmount = normalizePayAmount(payAmount);
    const canonical = [
        `merchantNo=${merchantNo}`,
        `outOrderNo=${normalizedOutOrderNo}`,
        `payAmount=${normalizedPayAmount}`,
        `timestamp=${timestamp}`,
    ].join('&');

    return crypto.createHmac('sha256', apiSecret)
        .update(canonical, 'utf8')
        .digest('hex');
}

function normalizeOutOrderNo(value) {
    const normalized = value == null ? '' : String(value).trim();
    if (!normalized || normalized.length > 64) {
        throw new TypeError('outOrderNo is required, must not be blank, and must be <= 64 characters');
    }
    return normalized;
}

/**
 * Keep payAmount as a string. Converting through Number can lose precision or
 * emit scientific notation. Merchants use at most 2 decimal places; DSPay reserves
 * the remaining 4 stablecoin decimal places for its order suffix.
 */
function normalizePayAmount(value) {
    if (typeof value !== 'string') {
        throw new TypeError('payAmount must be a plain decimal string');
    }
    const normalized = value.trim();
    if (!/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(normalized)) {
        throw new TypeError('payAmount must be a plain decimal string with at most 2 decimal places; scientific notation is not allowed');
    }
    if (!/[1-9]/.test(normalized)) {
        throw new RangeError('payAmount must be greater than 0');
    }
    return normalized;
}

/**
 * Callback verification: HMAC-SHA256 over raw body, constant-time compare
 * against X-DSPay-Signature header.
 *
 * IMPORTANT: Must use the raw body string as-is. Do NOT JSON.parse then
 * JSON.stringify — field order or whitespace changes would alter the byte
 * sequence and break verification.
 *
 * @param {string} rawBody    Raw request body string
 * @param {string} signature  Value of X-DSPay-Signature header (case-insensitive)
 * @param {string} apiSecret  Merchant apiSecret
 * @returns {boolean}
 */
function verifyCallback(rawBody, signature, apiSecret) {
    if (!apiSecret || !rawBody || !signature) return false;

    const expected = crypto.createHmac('sha256', apiSecret)
        .update(rawBody, 'utf8')
        .digest('hex');

    const expectedBuf = Buffer.from(expected, 'utf8');
    const sigBuf = Buffer.from(signature.toLowerCase(), 'utf8');
    if (expectedBuf.length !== sigBuf.length) return false;
    return crypto.timingSafeEqual(expectedBuf, sigBuf);
}

module.exports = { signOrder, verifyCallback, normalizeOutOrderNo, normalizePayAmount };
