'use strict';

const crypto = require('crypto');

const text = (v) => (v == null ? '' : String(v).trim());
const decimal = (v) => (v == null || v === '' ? '' : String(v));

function canonicalJson(value) {
    if (value == null) return '';
    if (Array.isArray(value)) return `[${value.map(canonicalJsonValue).join(',')}]`;
    return canonicalJsonValue(value);
}

function canonicalJsonValue(value) {
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
    if (!Array.isArray(methods) || methods.length === 0) return '';
    const seen = new Set();
    const values = [];
    for (const method of methods) {
        const networkId = text(method.networkId);
        const rawAddress = text(method.contractAddress);
        const address = rawAddress.startsWith('0x') ? rawAddress.toLowerCase() : rawAddress;
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
    if (typeof value === 'string') return text(value);
    if (typeof value === 'number') return numberToPlainString(value);
    if (typeof value === 'boolean') return String(value);
    return canonicalJson(value);
}

function signCreateOrder(order, apiSecret) {
    const canonical = canonicalFields({
        merchantNo: order.merchantNo == null ? null : text(order.merchantNo),
        outOrderNo: order.outOrderNo == null ? null : text(order.outOrderNo),
        productPrice: order.productPrice == null ? null : decimal(order.productPrice),
        productPriceCurrency: order.productPriceCurrency == null ? null : text(order.productPriceCurrency),
        productId: order.productId == null ? null : text(order.productId),
        attach: order.attach == null ? null : canonicalJson(order.attach),
        payAmount: order.payAmount == null ? null : decimal(order.payAmount),
        allowedPaymentMethods: order.allowedPaymentMethods == null ? null : canonicalMethods(order.allowedPaymentMethods),
        returnUrl: order.returnUrl == null ? null : text(order.returnUrl),
        successRedirectUrl: order.successRedirectUrl == null ? null : text(order.successRedirectUrl),
        timestamp: order.timestamp == null ? null : order.timestamp,
    });
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

function verifyCallback(rawBody, signature, apiSecret) {
    if (!rawBody || !signature || !apiSecret) return false;
    let body;
    try { body = JSON.parse(rawBody); } catch (_) { return false; }
    if (!body || Array.isArray(body) || typeof body !== 'object') return false;
    const expected = Buffer.from(hmac(canonicalFields(body), apiSecret), 'utf8');
    const actual = Buffer.from(String(signature).toLowerCase(), 'utf8');
    return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

module.exports = { canonicalJson, canonicalMethods, signCreateOrder, signQuery, verifyCallback };
