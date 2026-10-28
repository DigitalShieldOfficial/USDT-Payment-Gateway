'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {describeError, summarizeError} = require('../src/request-error');

test('preserves fetch causes and aggregate connection failures', () => {
    const reset = Object.assign(new Error('socket closed'), {code: 'ECONNRESET', syscall: 'read'});
    const refused = Object.assign(new Error('connection refused'), {code: 'ECONNREFUSED', address: '127.0.0.1', port: 443});
    const error = new TypeError('fetch failed', {cause: new AggregateError([reset, refused], 'connections failed')});
    const details = describeError(error);
    assert.equal(details.cause.errors[0].code, 'ECONNRESET');
    assert.equal(details.cause.errors[1].port, 443);
    assert.match(summarizeError(details), /fetch failed.*ECONNRESET: socket closed.*ECONNREFUSED: connection refused/);
    assert.equal(JSON.parse(JSON.stringify(details)).cause.errors.length, 2);
});

test('handles circular causes without losing the original error', () => {
    const error = new Error('network failure'); error.cause = error;
    assert.equal(describeError(error).message, 'network failure');
    assert.doesNotThrow(() => JSON.stringify(describeError(error)));
});
