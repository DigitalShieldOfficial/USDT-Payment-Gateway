'use strict';

const crypto = require('crypto');
const { JsonNumber, parseJson } = require('./lossless-json');

const blank = (value) => /^[\u0009-\u000d\u001c-\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/u.test(value);
const text = (v) => (v == null ? '' : String(v));

function canonicalJson(value) {
    if (value == null) return '';
    if (Array.isArray(value)) return `[${value.map(canonicalJsonValue).join(',')}]`;
    return canonicalJsonValue(value);
}

function canonicalJsonValue(value) {
    if (value instanceof JsonNumber) return value.canonical();
    if (value == null) return 'null';
    if (Array.isArray(value)) return `[${value.map(canonicalJsonValue).join(',')}]`;
    if (typeof value === 'object') {
        return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonicalJsonValue(value[k])}`).join(',')}}`;
    }
    if (typeof value === 'number') {
        if (!Number.isFinite(value)) throw new TypeError('attach contains a non-finite number');
        return numberToPlainString(value);
    }
    return JSON.stringify(value);
}

function numberToPlainString(value) {
    if (Object.is(value, -0) || value === 0) return '0';
    const source = String(value);
    if (!/[eE]/.test(source)) return source;
    const sign = source.startsWith('-') ? '-' : '';
    const unsigned = sign ? source.slice(1) : source;
    const [coefficient, exponentText] = unsigned.toLowerCase().split('e');
    const exponent = Number(exponentText);
    const point = coefficient.indexOf('.');
    const digits = coefficient.replace('.', '');
    const integerLength = point < 0 ? coefficient.length : point;
    const decimalPosition = integerLength + exponent;
    if (decimalPosition <= 0) return `${sign}0.${'0'.repeat(-decimalPosition)}${digits}`;
    if (decimalPosition >= digits.length) return `${sign}${digits}${'0'.repeat(decimalPosition - digits.length)}`;
    return `${sign}${digits.slice(0, decimalPosition)}.${digits.slice(decimalPosition)}`;
}

function canonicalMethods(methods) {
    if (!Array.isArray(methods)) throw new TypeError('allowedPaymentMethods must be an array');
    if (methods.length === 0) return '';
    const seen = new Set();
    const values = [];
    for (const method of methods) {
        if (!method || typeof method !== "object" || Array.isArray(method)) throw new TypeError("Payment method must be an object");
        if (typeof method.networkId !== "string" || !method.networkId || blank(method.networkId) || typeof method.contractAddress !== "string" || !method.contractAddress || blank(method.contractAddress)) throw new TypeError("Payment method fields must be non-blank strings");
        const networkId = text(method.networkId);
        const rawAddress = text(method.contractAddress);
        const address = rawAddress.slice(0, 2).toLowerCase() === '0x' ? rawAddress.toLowerCase() : rawAddress;
        const value = `${networkId}|${address}`;
        if (!seen.has(value)) { seen.add(value); values.push(value); }
    }
    return values.join(',');
}

function hmac(payload, apiSecret) {
    return crypto.createHmac('sha256', apiSecret).update(payload, 'utf8').digest('hex');
}

function canonicalFields(fields) {
    return Object.keys(fields)
        .filter((key) => fields[key] !== null && fields[key] !== undefined)
        .filter((key) => key !== 'signature')
        .sort()
        .map((key) => `${key}=${canonicalFieldValue(fields[key])}`)
        .join('&');
}

function canonicalFieldValue(value) {
    if (value instanceof JsonNumber) return value.plain;
    if (typeof value === 'string') return text(value);
    if (typeof value === 'number') return numberToPlainString(value);
    if (typeof value === 'boolean') return String(value);
    return canonicalJson(value);
}

function signCreateOrder(order, apiSecret) {
    const fields = { ...order };
    for (const key of ['productId', 'productPriceCurrency']) {
        if (typeof fields[key] === 'string' && blank(fields[key])) delete fields[key];
    }
    for (const key of ['returnUrl', 'successRedirectUrl']) if (fields[key] === '') delete fields[key];
    if (fields.allowedPaymentMethods != null) fields.allowedPaymentMethods = canonicalMethods(fields.allowedPaymentMethods);
    const canonical = canonicalFields(fields);
    return { canonical, signature: hmac(canonical, apiSecret) };
}

function signQuery(query, apiSecret) {
    const fields = {
        merchantNo: query.merchantNo == null ? null : text(query.merchantNo),
        timestamp: query.timestamp == null ? null : query.timestamp,
    };
    if (query.orderNo !== null && query.orderNo !== undefined) fields.orderNo = text(query.orderNo);
    if (query.outOrderNo !== null && query.outOrderNo !== undefined) fields.outOrderNo = text(query.outOrderNo);
    const canonical = canonicalFields(fields);
    return { canonical, signature: hmac(canonical, apiSecret) };
}

function verifyCallback(rawBody, signature, apiSecret, now = Date.now()) {
    if (!rawBody || typeof signature !== "string" || !/^[a-fA-F0-9]{64}$/.test(signature) || !apiSecret) return false;
    let body;
    try { body = parseJson(rawBody); } catch (_) { return false; }
    if (!body || Array.isArray(body) || typeof body !== 'object') return false;
    if (!(body.timestamp instanceof JsonNumber) || !body.timestamp.integer) return false;
    const stamp = BigInt(body.timestamp.plain), clock = BigInt(now);
    if (stamp < 0n || stamp < clock - 300000n || stamp > clock + 300000n) return false;
    let canonical;
    try { canonical = canonicalFields(body); } catch (_) { return false; }
    const expected = Buffer.from(hmac(canonical, apiSecret), 'utf8');
    const actual = Buffer.from(String(signature).toLowerCase(), 'utf8');
    return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

module.exports = { canonicalJson, canonicalMethods, signCreateOrder, signQuery, verifyCallback };
