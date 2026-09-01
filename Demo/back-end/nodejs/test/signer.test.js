'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const { canonicalJson, canonicalMethods, signCreateOrder, signQuery, verifyCallback } = require('../src/signer');

const secret = 'demo-secret';
const digest = (value) => crypto.createHmac('sha256', secret).update(value, 'utf8').digest('hex');

test('canonicalizes attach JSON numbers and object keys', () => {
    assert.equal(canonicalJson({ z: 1, a: { y: true, x: null } }), '{"a":{"x":null,"y":true},"z":1}');
    assert.equal(canonicalJson({ small: 1e-7, large: 1e21, zero: -0 }), '{"large":1000000000000000000000,"small":0.0000001,"zero":0}');
});

test('preserves allowed payment method order and removes duplicates', () => {
    assert.equal(canonicalMethods([
        { networkId: 'evm--1', contractAddress: '0xABC' },
        { networkId: 'tron', contractAddress: 'TXYZ' },
        { networkId: 'evm--1', contractAddress: '0xabc' },
    ]), 'evm--1|0xabc,tron|TXYZ');
});

test('create signature always includes all fixed fields', () => {
    const signed = signCreateOrder({ merchantNo: 'DSM001', outOrderNo: 'M001', payAmount: '1.00', timestamp: 1787700000000 }, secret);
    assert.equal(signed.canonical, 'merchantNo=DSM001&outOrderNo=M001&productPrice=&productPriceCurrency=&productId=&attach=&payAmount=1.00&allowedPaymentMethods=&returnUrl=&successRedirectUrl=&timestamp=1787700000000');
    assert.equal(signed.signature, digest(signed.canonical));
});

test('query signs only non-empty order identifiers', () => {
    const signed = signQuery({ merchantNo: 'DSM001', orderNo: '1949695024925671424', timestamp: 1787700000000 }, secret);
    assert.equal(signed.canonical, 'merchantNo=DSM001&orderNo=1949695024925671424&timestamp=1787700000000');
    assert.equal(signed.signature, digest(signed.canonical));
});

test('callback verification uses the exact raw body', () => {
    const raw = '{"notifyNo":"N001","status":"COMPLETED"}';
    assert.equal(verifyCallback(raw, digest(raw), secret), true);
    assert.equal(verifyCallback(raw + ' ', digest(raw), secret), false);
});
