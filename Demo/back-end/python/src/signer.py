"""DSPay canonical signing using only the Python standard library."""

import hashlib
import hmac
import json
import re
import time
from decimal import Decimal


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON contains duplicate field: " + key)
        result[key] = value
    return result


def _invalid_constant(value):
    raise ValueError("JSON must not contain " + value)


def parse_json(raw):
    """Preserve decimal precision and reject ambiguous or non-JSON input."""
    return json.loads(raw, parse_float=Decimal, object_pairs_hook=_unique_object,
                      parse_constant=_invalid_constant)


def canonical_json(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, int):
        if value.bit_length() > 13607:
            raise ValueError("JSON integer exceeds the demo's 4096-digit limit")
        text = str(value)
        if len(text.lstrip("-")) > 4096:
            raise ValueError("JSON integer exceeds the demo's 4096-digit limit")
        return text
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("JSON numbers must be finite")
        if abs(value.as_tuple().exponent) > 4096 or value.adjusted() >= 4096:
            raise ValueError("JSON decimal exceeds the demo's 4096-digit limit")
        if value == 0:
            return "0"
        plain = format(value, "f")
        return plain.rstrip("0").rstrip(".") if "." in plain else plain
    if isinstance(value, list):
        return "[" + ",".join(canonical_json(item) for item in value) + "]"
    if isinstance(value, dict) and all(isinstance(key, str) for key in value):
        keys = sorted(value, key=lambda key: key.encode("utf-16-be"))
        return "{" + ",".join(canonical_json(key) + ":" + canonical_json(value[key])
                              for key in keys) + "}"
    raise ValueError("Use JSON values and Decimal, not binary floating-point numbers")


def canonical_fields(fields):
    parts = []
    for key in sorted(fields):
        value = fields[key]
        if key == "signature" or value is None:
            continue
        # Top-level decimal scale is retained; nested JSON numbers are canonicalized.
        if isinstance(value, str):
            text = value
        elif isinstance(value, Decimal):
            if not value.is_finite():
                raise ValueError("Decimal must be finite")
            if abs(value.as_tuple().exponent) > 4096 or value.adjusted() >= 4096:
                raise ValueError("Decimal exceeds the demo's 4096-digit limit")
            text = format(value, "f")
        else:
            text = canonical_json(value)
        parts.append(key + "=" + text)
    return "&".join(parts)


def canonical_methods(methods):
    if not isinstance(methods, list):
        raise ValueError("allowedPaymentMethods must be an array")
    values = []
    for method in methods:
        if not isinstance(method, dict):
            raise ValueError("allowedPaymentMethods entries must be objects")
        network = method.get("networkId")
        address = method.get("contractAddress")
        if not isinstance(network, str) or not network or network.isspace():
            raise ValueError("allowedPaymentMethods.networkId must be a non-blank string")
        if not isinstance(address, str) or not address or address.isspace():
            raise ValueError("allowedPaymentMethods.contractAddress must be a non-blank string")
        # Address case and de-duplication are explicitly defined by the API contract.
        normalized = address.lower() if address[:2].lower() == "0x" else address
        entry = network + "|" + normalized
        if entry not in values:
            values.append(entry)
    return ",".join(values)


def digest(canonical, secret):
    return hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"),
                    hashlib.sha256).hexdigest()


def sign_create(order, secret):
    fields = dict(order)
    for key in ("productId", "productPriceCurrency"):
        value = fields.get(key)
        if isinstance(value, str) and (value == "" or value.isspace()):
            fields.pop(key)
    for key in ("returnUrl", "successRedirectUrl"):
        if fields.get(key) == "":
            fields.pop(key)
    if fields.get("allowedPaymentMethods") is not None:
        fields["allowedPaymentMethods"] = canonical_methods(fields["allowedPaymentMethods"])
    canonical = canonical_fields(fields)
    return canonical, digest(canonical, secret)


def sign_query(query, secret):
    canonical = canonical_fields(query)
    return canonical, digest(canonical, secret)


def verify_callback(raw, signature, secret, now_ms=None):
    if not isinstance(signature, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", signature):
        return False
    try:
        body = parse_json(raw)
        if not isinstance(body, dict):
            return False
        timestamp = body.get("timestamp")
        now = int(time.time() * 1000) if now_ms is None else now_ms
        if type(timestamp) is not int or timestamp < 0 or abs(now - timestamp) > 300000:
            return False
        expected = digest(canonical_fields(body), secret)
        return hmac.compare_digest(expected, signature.lower())
    except (ValueError, TypeError, UnicodeError, RecursionError):
        return False
