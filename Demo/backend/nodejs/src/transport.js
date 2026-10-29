'use strict';

const http = require('http');
const http2 = require('http2');
const zlib = require('zlib');

// Keep a fixed, modern cipher order without depending on a third-party TLS stack.
const CIPHERS = [
    'TLS_AES_128_GCM_SHA256', 'TLS_AES_256_GCM_SHA384', 'TLS_CHACHA20_POLY1305_SHA256',
    'ECDHE-ECDSA-AES128-GCM-SHA256', 'ECDHE-RSA-AES128-GCM-SHA256',
    'ECDHE-ECDSA-AES256-GCM-SHA384', 'ECDHE-RSA-AES256-GCM-SHA384',
    'ECDHE-ECDSA-CHACHA20-POLY1305', 'ECDHE-RSA-CHACHA20-POLY1305',
].join(':');
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36';

// Return the original response text so callers retain lossless JSON parsing.
// HTTPS requires HTTP/2; plain HTTP remains available for local API test servers.
function postJson(target, body, timeout = 10000) {
    return new Promise((resolve, reject) => {
        let url;
        try {
            url = new URL(target);
            if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('Unsupported API URL');
            if (typeof body !== 'string') throw new TypeError('The request body must be a JSON string');
        } catch (error) { reject(error); return; }
        let session, request, response, settled = false;
        const timer = setTimeout(() => finish(Object.assign(new Error(`DSPay request timed out after ${timeout / 1000} seconds`), {code: 'ETIMEDOUT'})), timeout);
        function finish(error, value) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            response?.destroy();
            request?.destroy();
            session?.destroy();
            if (error) reject(error); else resolve(value);
        }
        function collect(stream, status, headers) {
            response = stream;
            const chunks = [];
            let ended = false;
            stream.on('data', chunk => chunks.push(chunk));
            stream.on('error', finish);
            stream.on('aborted', () => finish(Object.assign(new Error('DSPay response was aborted'), {code: 'ECONNRESET'})));
            stream.on('close', () => {
                if (!ended) finish(Object.assign(new Error('DSPay response closed before completion'), {code: 'ECONNRESET'}));
            });
            stream.on('end', () => {
                ended = true;
                try {
                    let buffer = Buffer.concat(chunks);
                    if (headers['content-length'] !== undefined && BigInt(headers['content-length']) !== BigInt(buffer.length)) {
                        throw new Error('DSPay response Content-Length does not match the received body');
                    }
                    // Do not advertise compression, but handle encoded upstream responses.
                    const encoding = headers['content-encoding'];
                    if (encoding === 'gzip') buffer = zlib.gunzipSync(buffer);
                    else if (encoding === 'deflate') buffer = zlib.inflateSync(buffer);
                    else if (encoding === 'br') buffer = zlib.brotliDecompressSync(buffer);
                    else if (encoding && encoding !== 'identity') throw new Error('Unsupported DSPay content encoding: ' + encoding);
                    finish(null, {status, headers, body: buffer.toString('utf8')});
                } catch (error) { finish(error); }
            });
        }
        const headers = {'user-agent': USER_AGENT, accept: 'application/json', 'content-type': 'application/json'};
        try {
            if (url.protocol === 'http:') {
                request = http.request(url, {method: 'POST', headers: {...headers, 'content-length': Buffer.byteLength(body)}, agent: false}, stream => collect(stream, stream.statusCode, stream.headers));
                request.on('error', finish);
                request.end(body);
            } else {
                session = http2.connect(url.origin, {
                    ciphers: CIPHERS, minVersion: 'TLSv1.2', rejectUnauthorized: true,
                    settings: {enablePush: false, initialWindowSize: 33554432},
                });
                session.on('error', finish);
                session.on('close', () => {
                    if (!settled) finish(Object.assign(new Error('DSPay HTTP/2 connection closed before completion'), {code: 'ECONNRESET'}));
                });
                session.once('connect', () => {
                    if (settled) return;
                    if (session.socket.alpnProtocol !== 'h2') {
                        finish(new Error('DSPay HTTPS endpoint must support HTTP/2'));
                        return;
                    }
                    try {
                        request = session.request({':method': 'POST', ':path': url.pathname + url.search, ...headers});
                        request.on('error', finish);
                        request.once('response', received => collect(request, received[':status'], received));
                        request.once('close', () => {
                            if (!settled) finish(Object.assign(new Error('DSPay HTTP/2 stream closed before completion'), {code: 'ECONNRESET'}));
                        });
                        request.end(body);
                    } catch (error) { finish(error); }
                });
            }
        } catch (error) { finish(error); }
    });
}

module.exports = {postJson};
