import hashlib
import hmac
import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from signer import (canonical_fields, canonical_json, canonical_methods, parse_json,
                    sign_create, sign_query, verify_callback)

SECRET = "demo-secret"
NOW = 1787700000000


class SignerTest(unittest.TestCase):
    def test_shared_create_vector(self):
        canonical, signature = sign_create({"merchantNo": "DSM001", "outOrderNo": "M001",
                                             "payAmount": "1.00", "timestamp": NOW}, SECRET)
        self.assertEqual(canonical, "merchantNo=DSM001&outOrderNo=M001&payAmount=1.00&timestamp=1787700000000")
        self.assertEqual(signature, "e522c9b170a8e9389f86170ed3b5f05c8a52e14374ec6166799f97b5f409d68b")

    def test_decimal_json_precision_and_unicode(self):
        value = parse_json('{"z":-0.0,"a":{"中文":"值","x":null},"large":12345678901234567890.123456,"small":1e-7}')
        self.assertEqual(canonical_json(value), '{"a":{"x":null,"中文":"值"},"large":12345678901234567890.123456,"small":0.0000001,"z":0}')
        self.assertEqual(canonical_fields({"amount": Decimal("1.00")}), "amount=1.00")
        with self.assertRaises(ValueError):
            canonical_json({"amount": 0.1})
        with self.assertRaises(ValueError):
            canonical_json(10 ** 4096)

    def test_no_silent_string_repair_or_input_mutation(self):
        order = {"merchantNo": "DSM001", "outOrderNo": "M001", "payAmount": "1.00",
                 "productId": "  item  ", "productPriceCurrency": " ", "returnUrl": "",
                 "allowedPaymentMethods": [], "attach": {"note": "  untouched  "},
                 "timestamp": NOW}
        before = dict(order)
        canonical, _ = sign_create(order, SECRET)
        self.assertIn('attach={"note":"  untouched  "}', canonical)
        self.assertIn("productId=  item  ", canonical)
        self.assertIn("allowedPaymentMethods=&", canonical)
        self.assertNotIn("productPriceCurrency=", canonical)
        self.assertNotIn("returnUrl=", canonical)
        self.assertEqual(order, before)

    def test_payment_methods_contract(self):
        methods = [{"networkId": "evm--1", "contractAddress": "0xABC"},
                   {"networkId": "tron", "contractAddress": "TXYZ"},
                   {"networkId": "evm--1", "contractAddress": "0xabc"}]
        self.assertEqual(canonical_methods(methods), "evm--1|0xabc,tron|TXYZ")
        self.assertEqual(methods[0]["contractAddress"], "0xABC")

    def test_query_empty_null_and_signature_exclusion(self):
        canonical, _ = sign_query({"merchantNo": "DSM001", "orderNo": "", "outOrderNo": "M001",
                                   "timestamp": NOW, "attach": None, "signature": "ignored"}, SECRET)
        self.assertEqual(canonical, "merchantNo=DSM001&orderNo=&outOrderNo=M001&timestamp=1787700000000")

    def test_callback_canonical_hmac_not_raw_body(self):
        raw = '{"timestamp":1787700000000,"status":"COMPLETED","txHash":null,"attach":{"z":1.0,"a":true}}'
        canonical = 'attach={"a":true,"z":1}&status=COMPLETED&timestamp=1787700000000'
        signature = hmac.new(SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
        self.assertTrue(verify_callback(raw, signature.upper(), SECRET, NOW))
        self.assertFalse(verify_callback(raw.replace("COMPLETED", "REFUNDED"), signature, SECRET, NOW))
        raw_signature = hmac.new(SECRET.encode(), raw.encode(), hashlib.sha256).hexdigest()
        self.assertFalse(verify_callback(raw, raw_signature, SECRET, NOW))
        self.assertFalse(verify_callback(raw, signature, "wrong-secret", NOW))

    def test_timestamp_window_boundaries(self):
        for delta, accepted in [(0, True), (-300000, True), (300000, True),
                                (-300001, False), (300001, False)]:
            body = {"timestamp": NOW + delta, "status": "COMPLETED"}
            raw = canonical_json(body)
            signature = sign_query(body, SECRET)[1]
            self.assertEqual(verify_callback(raw, signature, SECRET, NOW), accepted)
        for timestamp in [True, "1787700000000", None, -1]:
            body = {"timestamp": timestamp}
            self.assertFalse(verify_callback(canonical_json(body), sign_query(body, SECRET)[1], SECRET, NOW))

    def test_invalid_json_and_signature(self):
        for raw in ['[]', '{"timestamp":1,"timestamp":2}', '{"x":NaN}', '{bad json']:
            self.assertFalse(verify_callback(raw, "a" * 64, SECRET, NOW))
        self.assertFalse(verify_callback('{}', "invalid", SECRET, NOW))
        with self.assertRaises(ValueError):
            parse_json('{"a":Infinity}')
        self.assertFalse(verify_callback('{"timestamp":1787700000000,"x":1e999999999}', "a" * 64, SECRET, NOW))


if __name__ == "__main__":
    unittest.main()
