'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { signOrder, normalizePayAmount } = require('../src/signer');

test('payAmount remains an unchanged plain decimal string', () => {
    assert.equal(normalizePayAmount('100.12'), '100.12');
    assert.equal(normalizePayAmount('0.01'), '0.01');
});

test('payAmount rejects numbers, scientific notation, zero, and more than 2 decimal places', () => {
    assert.throws(() => normalizePayAmount(100), /plain decimal string/);
    assert.throws(() => normalizePayAmount('1e2'), /scientific notation/);
    assert.throws(() => normalizePayAmount('100.123'), /at most 2 decimal places/);
    assert.throws(() => normalizePayAmount('0'), /greater than 0/);
});

test('signature uses exact payAmount string and requires outOrderNo', () => {
    const signature = signOrder({
        merchantNo: 'DSM1',
        outOrderNo: 'ORDER-001',
        payAmount: '100.12',
        timestamp: 1717689600000,
    }, 'secret');

    assert.equal(signature, '8864aa09c5fb8011ee615fe3e627d7e24214220fe18deec2f8c767411d284ae4');
    assert.throws(() => signOrder({
        merchantNo: 'DSM1',
        outOrderNo: '',
        payAmount: '0.01',
        timestamp: 1717689600000,
    }, 'secret'), /outOrderNo is required/);
});
