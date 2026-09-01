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

function signCreateOrder(order, apiSecret) {
    const canonical = [
        `merchantNo=${text(order.merchantNo)}`,
        `outOrderNo=${text(order.outOrderNo)}`,
        `productPrice=${decimal(order.productPrice)}`,
        `productPriceCurrency=${text(order.productPriceCurrency)}`,
        `productId=${text(order.productId)}`,
        `attach=${canonicalJson(order.attach)}`,
        `payAmount=${decimal(order.payAmount)}`,
        `allowedPaymentMethods=${canonicalMethods(order.allowedPaymentMethods)}`,
        `returnUrl=${text(order.returnUrl)}`,
        `successRedirectUrl=${text(order.successRedirectUrl)}`,
        `timestamp=${order.timestamp == null ? '' : order.timestamp}`,
    ].join('&');
    return { canonical, signature: hmac(canonical, apiSecret) };
}

function signQuery(query, apiSecret) {
    const fields = [`merchantNo=${text(query.merchantNo)}`];
    if (text(query.orderNo)) fields.push(`orderNo=${text(query.orderNo)}`);
    if (text(query.outOrderNo)) fields.push(`outOrderNo=${text(query.outOrderNo)}`);
    fields.push(`timestamp=${query.timestamp}`);
    const canonical = fields.join('&');
    return { canonical, signature: hmac(canonical, apiSecret) };
}

function verifyCallback(rawBody, signature, apiSecret) {
    if (!rawBody || !signature || !apiSecret) return false;
    const expected = Buffer.from(hmac(rawBody, apiSecret), 'utf8');
    const actual = Buffer.from(String(signature).toLowerCase(), 'utf8');
    return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

module.exports = { canonicalJson, canonicalMethods, signCreateOrder, signQuery, verifyCallback };
