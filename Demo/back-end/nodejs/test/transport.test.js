'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const https = require('node:https');
const http2 = require('node:http2');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawn, execFileSync} = require('node:child_process');
const {once} = require('node:events');
const {gzipSync} = require('node:zlib');
const {postJson} = require('../src/transport');

// Generate short-lived local certificates; never disable certificate validation.
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'dspay-tls-test-'));
const key = path.join(directory, 'key.pem'), cert = path.join(directory, 'cert.pem');
execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', cert,
    '-days', '2', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1'], {stdio: 'ignore'});
test.after(() => fs.rmSync(directory, {recursive: true, force: true}));
const payload = '{"orderNo":90071992547409931234567890,"amount":0.1234567890123456789}';

function secureCall(url, timeout = 1000, trusted = true) {
    const script = `require(${JSON.stringify(path.resolve(__dirname, '../src/transport'))}).postJson(process.argv[1], process.argv[2], Number(process.argv[3])).then(r => console.log(JSON.stringify(r)), e => console.log(JSON.stringify({error:e.message, code:e.code})))`;
    return new Promise((resolve, reject) => {
        const env = {...process.env};
        if (trusted) env.NODE_EXTRA_CA_CERTS = cert; else delete env.NODE_EXTRA_CA_CERTS;
        const child = spawn(process.execPath, ['-e', script, url, payload, String(timeout)], {env});
        let output = '', errors = '';
        child.stdout.on('data', chunk => output += chunk);
        child.stderr.on('data', chunk => errors += chunk);
        child.on('error', reject);
        const timer = setTimeout(() => { child.kill(); reject(new Error('Transport child did not exit: ' + errors)); }, 5000);
        child.once('close', code => {
            clearTimeout(timer);
            try { assert.equal(code, 0, errors); resolve(JSON.parse(output)); } catch (error) { reject(error); }
        });
    });
}

test('HTTP preserves raw JSON and returns redirects without following them', async () => {
    let hits = 0;
    const server = http.createServer((req, res) => {
        hits++;
        let body = '';
        req.on('data', data => body += data);
        req.on('end', () => {
            assert.equal(body, payload);
            res.writeHead(307, {location: '/unexpected'});
            res.end(payload);
        });
    }).listen(0, '127.0.0.1');
    await once(server, 'listening');
    try {
        const result = await postJson(`http://127.0.0.1:${server.address().port}/query`, payload);
        assert.equal(result.status, 307);
        assert.equal(result.body, payload);
        assert.equal(hits, 1);
    } finally { await new Promise(resolve => server.close(resolve)); }
});

test('HTTPS uses verified HTTP/2, preserves responses and closes failed connections', async () => {
    const sessions = new Set();
    const seen = [];
    const server = http2.createSecureServer({key: fs.readFileSync(key), cert: fs.readFileSync(cert)});
    server.on('session', session => { sessions.add(session); session.on('error', () => {}); session.on('close', () => sessions.delete(session)); });
    server.on('stream', (stream, headers) => {
        stream.on('error', () => {});
        let body = '';
        stream.on('data', data => body += data);
        stream.on('end', () => {
            seen.push({headers, body});
            if (headers[':path'] === '/slow') return;
            if (headers[':path'] === '/reset') { stream.close(http2.constants.NGHTTP2_INTERNAL_ERROR); return; }
            if (headers[':path'] === '/truncated') { stream.respond({':status': 200, 'content-length': '100'}); stream.end('x'); return; }
            const status = headers[':path'] === '/redirect' ? 307 : headers[':path'] === '/blocked' ? 403 : 200;
            stream.respond({':status': status, 'content-type': 'application/json', 'content-encoding': 'gzip', 'cf-ray': 'local-test', ...(status === 307 ? {location: '/unexpected'} : {})});
            stream.end(gzipSync(payload));
        });
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const base = `https://127.0.0.1:${server.address().port}`;
    try {
        for (const route of ['/query', '/blocked', '/redirect']) {
            const result = await secureCall(base + route);
            assert.equal(result.status, route === '/query' ? 200 : route === '/blocked' ? 403 : 307, JSON.stringify(result));
            assert.equal(result.body, payload);
            assert.equal(result.headers['cf-ray'], 'local-test');
            assert.equal(seen[seen.length - 1].body, payload);
            assert.match(seen[seen.length - 1].headers['user-agent'], /Mozilla\/5\.0/);
        }
        assert.equal(seen.length, 3);
        assert.equal((await secureCall(base + '/slow', 150)).code, 'ETIMEDOUT');
        assert.ok((await secureCall(base + '/reset')).error);
        assert.ok((await secureCall(base + '/truncated')).error);
        const untrusted = await secureCall(base + '/query', 1000, false);
        assert.match(untrusted.code, /SELF_SIGNED_CERT|UNABLE_TO_VERIFY/);
    } finally {
        for (const session of sessions) session.destroy();
        await new Promise(resolve => server.close(resolve));
    }
});

test('HTTPS does not send the JSON body to an HTTP/1-only upstream', async () => {
    let hits = 0;
    const server = https.createServer({key: fs.readFileSync(key), cert: fs.readFileSync(cert)}, (req, res) => {
        hits++;
        res.end('unexpected');
    }).listen(0, '127.0.0.1');
    await once(server, 'listening');
    try {
        const result = await secureCall(`https://127.0.0.1:${server.address().port}/query`);
        assert.match(result.error, /HTTP\/2|no application protocol/);
        assert.equal(hits, 0);
    } finally { await new Promise(resolve => server.close(resolve)); }
});
