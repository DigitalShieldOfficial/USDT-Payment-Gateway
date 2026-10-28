'use strict';

// Retain JSON numeric tokens instead of rounding them through Number.
class JsonNumber {
    constructor(raw) {
        this.raw = raw;
        this.integer = !/[.eE]/.test(raw);
        const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw);
        if (!match) throw new TypeError('Invalid JSON number');
        const exponent = Number(match[4] || 0);
        const digits = match[2] + (match[3] || '');
        const point = match[2].length + exponent;
        if (Math.abs(exponent) > 4096 || digits.length > 4096 || point > 4096) throw new TypeError('JSON number exceeds 4096-digit limit');
        let plain = point <= 0 ? `0.${'0'.repeat(-point)}${digits}`
            : point >= digits.length ? digits + '0'.repeat(point - digits.length)
                : digits.slice(0, point) + '.' + digits.slice(point);
        plain = plain.replace(/^0+(?=\d)/, '');
        this.plain = (match[1] && (!this.integer || /[1-9]/.test(plain)) ? '-' : '') + plain;
    }
    canonical() {
        if (!/[1-9]/.test(this.plain)) return '0';
        return this.plain.includes('.') ? this.plain.replace(/0+$/, '').replace(/\.$/, '') : this.plain;
    }
}

function parseJson(raw) {
    let i = 0;
    function space() { while (/[\x20\t\r\n]/.test(raw[i] || '\0')) i++; }
    function value() {
        space();
        if (raw[i] === '"') {
            const start = i++;
            while (i < raw.length) {
                const c = raw[i++];
                if (c === '"') {
                    const text = JSON.parse(raw.slice(start, i));
                    if (/[\uD800-\uDFFF]/u.test(text)) throw new TypeError('Invalid Unicode surrogate');
                    return text;
                }
                if (c === '\\') i++;
            }
            throw new TypeError('Unterminated JSON string');
        }
        if (raw[i] === '{' || raw[i] === '[') {
            const object = raw[i++] === '{', end = object ? '}' : ']';
            const out = object ? Object.create(null) : [];
            space(); if (raw[i] === end) { i++; return out; }
            while (true) {
                if (object) {
                    space(); if (raw[i] !== '"') throw new TypeError('JSON object key required');
                    const key = value(); space();
                    if (raw[i++] !== ':') throw new TypeError('JSON colon required');
                    if (Object.hasOwn(out, key)) throw new TypeError('Duplicate JSON field: ' + key);
                    out[key] = value();
                } else out.push(value());
                space(); const c = raw[i++];
                if (c === end) return out;
                if (c !== ',') throw new TypeError('JSON separator required');
            }
        }
        for (const [token, result] of [['true', true], ['false', false], ['null', null]]) {
            if (raw.startsWith(token, i)) { i += token.length; return result; }
        }
        const number = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(raw.slice(i));
        if (!number) throw new TypeError('Invalid JSON value');
        i += number[0].length; return new JsonNumber(number[0]);
    }
    const out = value(); space();
    if (i !== raw.length) throw new TypeError('Trailing JSON data');
    return out;
}

function stringifyJson(value) {
    if (value instanceof JsonNumber) return value.canonical();
    if (value === null) return 'null';
    if (Array.isArray(value)) return '[' + value.map(item => stringifyJson(item) ?? 'null').join(',') + ']';
    if (typeof value === 'object') return '{' + Object.keys(value).filter(k => value[k] !== undefined).sort().map(k => JSON.stringify(k) + ':' + stringifyJson(value[k])).join(',') + '}';
    if (typeof value === 'number' && !Number.isFinite(value)) throw new TypeError('JSON number must be finite');
    return JSON.stringify(value);
}
module.exports = { JsonNumber, parseJson, stringifyJson };
