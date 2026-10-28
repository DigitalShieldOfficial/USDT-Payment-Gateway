'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const path = require('node:path');

const server = path.resolve(__dirname, '../src/server.js');

async function startupApi(value) {
    const env = { ...process.env, PORT: '0', PUBLIC_BASE_URL: 'http://localhost:3000' };
    delete env.DSPAY_BASE_URL;
    if (value !== undefined) env.DSPAY_BASE_URL = value;
    const child = spawn(process.execPath, [server], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    let errors = '';
    try {
        return await new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error('Server startup timed out')), 5000);
            child.stdout.on('data', (chunk) => {
                output += chunk;
                const match = output.match(/^DSPay API: (.+)$/m);
                if (match) { clearTimeout(timeout); resolve(match[1]); }
            });
            child.stderr.on('data', (chunk) => { errors += chunk; });
            child.on('error', (error) => { clearTimeout(timeout); reject(error); });
            child.on('exit', () => {
                clearTimeout(timeout);
                reject(new Error('Server exited before startup: ' + errors));
            });
        });
    } finally {
        if (child.exitCode === null && child.signalCode === null) {
            const closed = new Promise((resolve) => child.once('close', resolve));
            child.kill();
            await closed;
        }
    }
}

test('server startup defaults to production without making an API request', async () => {
    assert.equal(await startupApi(), 'https://pay-api.ds.pro');
    assert.equal(await startupApi('http://localhost:12345'), 'http://localhost:12345');
});

test('explicit invalid API origins are rejected, not replaced by the default', () => {
    for (const value of ['', ' https://pay-api.ds.pro', 'https://pay-api.ds.pro/']) {
        const result = spawnSync(process.execPath, [server], {
            env: { ...process.env, DSPAY_BASE_URL: value, PORT: '0' }, encoding: 'utf8', timeout: 5000,
        });
        assert.equal(result.error, undefined);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /DSPAY_BASE_URL/);
    }
});
