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

test('create signature omits null fields and sorts parameter names by ASCII', () => {
    const signed = signCreateOrder({ merchantNo: 'DSM001', outOrderNo: 'M001', payAmount: '1.00', timestamp: 1787700000000 }, secret);
    assert.equal(signed.canonical, 'merchantNo=DSM001&outOrderNo=M001&payAmount=1.00&timestamp=1787700000000');
    assert.equal(signed.signature, digest(signed.canonical));
});

test('create signature omits a blank optional product ID', () => {
    const signed = signCreateOrder({ merchantNo: 'DSM001', outOrderNo: 'M001', payAmount: '1.00', productId: '', timestamp: 1787700000000 }, secret);
    assert.equal(signed.canonical, 'merchantNo=DSM001&outOrderNo=M001&payAmount=1.00&timestamp=1787700000000');
});

test('query omits null fields and keeps an explicitly supplied empty string', () => {
    const signed = signQuery({ merchantNo: 'DSM001', orderNo: '1949695024925671424', timestamp: 1787700000000 }, secret);
    assert.equal(signed.canonical, 'merchantNo=DSM001&orderNo=1949695024925671424&timestamp=1787700000000');
    assert.equal(signed.signature, digest(signed.canonical));
    assert.equal(signQuery({ merchantNo: 'DSM001', orderNo: '', outOrderNo: 'M001', timestamp: 1787700000000 }, secret).canonical,
        'merchantNo=DSM001&orderNo=&outOrderNo=M001&timestamp=1787700000000');
});

test('callback verification uses ASCII-sorted non-null fields', () => {
    const now = 1787700000000;
    const raw = '{"timestamp":1787700000000,"status":"COMPLETED","txHash":null,"notifyNo":"N001","attach":{"z":1,"a":true}}';
    const canonical = 'attach={"a":true,"z":1}&notifyNo=N001&status=COMPLETED&timestamp=1787700000000';
    assert.equal(verifyCallback(raw, digest(canonical), secret, now), true);
    assert.equal(verifyCallback('{"notifyNo":"N001","status":"REFUNDED"}', digest(canonical), secret), false);
});


test('callback window boundaries and lossless JSON numbers', () => {
    const {parseJson, stringifyJson} = require('../src/lossless-json');
    const now = 1787700000000;
    const raw = '{"timestamp":1787700000000,"amount":1.2300,"attach":{"x":90071992547409931234567890,"y":1.234567890123456789}}';
    const canonical = 'amount=1.2300&attach={"x":90071992547409931234567890,"y":1.234567890123456789}&timestamp=1787700000000';
    const signed = digest(canonical);
    assert.equal(verifyCallback(raw, signed, secret, now - 300000), true);
    assert.equal(verifyCallback(raw, signed, secret, now + 300000), true);
    assert.equal(verifyCallback(raw, signed, secret, now + 300001), false);
    assert.equal(verifyCallback(raw, signed, secret, now - 300001), false);
    assert.match(stringifyJson(parseJson(raw)), /90071992547409931234567890/);
    assert.equal(signQuery({merchantNo: 'DSM001', outOrderNo: ' ORDER '}, secret).canonical, 'merchantNo=DSM001&outOrderNo= ORDER ');
    assert.throws(() => parseJson('{"a":1,"a":2}'));
    assert.throws(() => parseJson('{"x":NaN}'));
});


test('JSON logs omit absent fields and remain valid JSON', () => {
    const {stringifyJson} = require('../src/lossless-json');
    const text = stringifyJson({path: '/create', status: 502, location: undefined});
    assert.deepEqual(JSON.parse(text), {path: '/create', status: 502});
    assert.equal(stringifyJson([undefined, 1]), '[null,1]');
});
