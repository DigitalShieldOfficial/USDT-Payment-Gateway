'use strict';

// Error properties are not normally included by JSON.stringify.
function describeError(error, seen = new Set()) {
    if (error === null || typeof error !== 'object') return {message: String(error)};
    if (seen.has(error)) return {message: '[circular error reference]'};
    seen.add(error);
    const details = {};
    for (const field of ['name', 'message', 'code', 'errno', 'syscall', 'address', 'port', 'hostname']) {
        if (['string', 'number'].includes(typeof error[field])) details[field] = error[field];
    }
    if (error.cause !== undefined) details.cause = describeError(error.cause, seen);
    if (Array.isArray(error.errors)) details.errors = error.errors.map(item => describeError(item, seen));
    return details;
}

function summarizeError(details) {
    const parts = [];
    function visit(item) {
        const text = [item.code, item.message].filter(value => value !== undefined && value !== '').join(': ');
        if (text && !parts.includes(text)) parts.push(text);
        if (item.cause) visit(item.cause);
        if (item.errors) item.errors.forEach(visit);
    }
    visit(details);
    return parts.join('; ');
}

module.exports = {describeError, summarizeError};
