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
 * @param {string} p.outOrderNo  Merchant's external order number (nullable/blank → empty string)
 * @param {string|number} p.payAmount  Payment amount in token (must be plain numeric string to avoid scientific notation)
 * @param {number} p.timestamp   Unix timestamp in milliseconds
 * @param {string} apiSecret     Merchant apiSecret
 * @returns {string} Signature hex string
 */
function signOrder({ merchantNo, outOrderNo, payAmount, timestamp }, apiSecret) {
    const opt = (v) => (v == null || String(v).trim() === '') ? '' : String(v).trim();
    const canonical = [
        `merchantNo=${merchantNo}`,
        `outOrderNo=${opt(outOrderNo)}`,
        `payAmount=${payAmount}`,
        `timestamp=${timestamp}`,
    ].join('&');

    return crypto.createHmac('sha256', apiSecret)
        .update(canonical, 'utf8')
        .digest('hex');
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

module.exports = { signOrder, verifyCallback };
