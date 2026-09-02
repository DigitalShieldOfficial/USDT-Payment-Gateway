<?php

namespace DSPay\Api;

final class RequestBuilder
{
    const SIGN_ALGO = 'sha256';

    private $merchantNo;
    private $apiSecret;

    public function __construct($merchantNo, $apiSecret)
    {
        $this->merchantNo = trim((string)$merchantNo);
        $this->apiSecret = (string)$apiSecret;
    }

    public function getMerchantNo()
    {
        return $this->merchantNo;
    }

    public function buildCreatePayload(array $data)
    {
        $this->assertConfigured();
        $fields = array(
            'merchantNo' => $this->text($this->value($data, 'merchantNo', $this->merchantNo)),
            'outOrderNo' => $this->requiredOrderNo($this->value($data, 'outOrderNo', '')),
            'payAmount' => $this->requiredPayAmount($this->value($data, 'payAmount', '')),
        );
        if (array_key_exists('productPrice', $data) && $data['productPrice'] !== null) $fields['productPrice'] = $this->decimal($data['productPrice']);
        if (array_key_exists('productPriceCurrency', $data) && $data['productPriceCurrency'] !== null) $fields['productPriceCurrency'] = $this->text($data['productPriceCurrency']);
        if (array_key_exists('productId', $data) && $data['productId'] !== null) $fields['productId'] = $this->text($data['productId']);
        if (array_key_exists('attach', $data) && $data['attach'] !== null) $fields['attach'] = $this->canonicalJson($data['attach']);
        if (array_key_exists('allowedPaymentMethods', $data) && $data['allowedPaymentMethods'] !== null) $fields['allowedPaymentMethods'] = $this->canonicalMethods($data['allowedPaymentMethods']);
        if (array_key_exists('returnUrl', $data) && $data['returnUrl'] !== null) $fields['returnUrl'] = $this->text($data['returnUrl']);
        if (array_key_exists('successRedirectUrl', $data) && $data['successRedirectUrl'] !== null) $fields['successRedirectUrl'] = $this->text($data['successRedirectUrl']);
        if (array_key_exists('timestamp', $data) && $data['timestamp'] !== null) $fields['timestamp'] = $this->text($data['timestamp']);
        return $this->joinSortedFields($fields);
    }

    public function signCreate(array $data)
    {
        return $this->hmac($this->buildCreatePayload($data));
    }

    public function buildQueryPayload(array $data)
    {
        $this->assertConfigured();
        $fields = array('merchantNo' => $this->text($this->value($data, 'merchantNo', $this->merchantNo)));
        if (array_key_exists('orderNo', $data) && $data['orderNo'] !== null) $fields['orderNo'] = $this->text($data['orderNo']);
        if (array_key_exists('outOrderNo', $data) && $data['outOrderNo'] !== null) $fields['outOrderNo'] = $this->text($data['outOrderNo']);
        if (array_key_exists('timestamp', $data) && $data['timestamp'] !== null) $fields['timestamp'] = $this->text($data['timestamp']);
        return $this->joinSortedFields($fields);
    }

    public function signQuery(array $data)
    {
        return $this->hmac($this->buildQueryPayload($data));
    }

    public function verifyCallback($rawBody, $signature)
    {
        if ($rawBody === false || $rawBody === '' || $signature === null || $signature === '') return false;
        $body = json_decode($rawBody, true);
        if (!is_array($body) || json_last_error() !== JSON_ERROR_NONE) return false;
        return hash_equals($this->hmac($this->buildCallbackPayload($body)), strtolower((string)$signature));
    }

    public function buildCallbackPayload(array $data)
    {
        $fields = array();
        foreach ($data as $key => $value) {
            if ($key === 'signature' || $value === null) continue;
            if (is_string($value)) $fields[$key] = $this->text($value);
            elseif (is_bool($value)) $fields[$key] = $value ? 'true' : 'false';
            elseif (is_int($value) || is_float($value)) $fields[$key] = $this->plainNumber($value);
            else $fields[$key] = $this->canonicalJson($value);
        }
        return $this->joinSortedFields($fields);
    }

    public function canonicalMethods($methods)
    {
        if (!is_array($methods) || count($methods) === 0) return '';
        $seen = array();
        $values = array();
        foreach ($methods as $method) {
            if (!is_array($method)) throw new RequestBuilderException('allowedPaymentMethods item must be an object');
            $networkId = $this->text($this->value($method, 'networkId', ''));
            $address = $this->text($this->value($method, 'contractAddress', ''));
            if (strpos($address, '0x') === 0) $address = strtolower($address);
            $entry = $networkId . '|' . $address;
            if (!isset($seen[$entry])) {
                $seen[$entry] = true;
                $values[] = $entry;
            }
        }
        return implode(',', $values);
    }

    public function canonicalJson($value)
    {
        if ($value === null) return 'null';
        if (is_bool($value)) return $value ? 'true' : 'false';
        if (is_int($value)) return (string)$value;
        if (is_float($value)) return $this->plainNumber($value);
        if (is_string($value)) return json_encode($value, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        if (is_object($value)) $value = get_object_vars($value);
        if (!is_array($value)) throw new RequestBuilderException('attach contains an unsupported value');

        $isList = array_keys($value) === range(0, count($value) - 1);
        if ($isList) {
            $items = array();
            foreach ($value as $item) $items[] = $this->canonicalJson($item);
            return '[' . implode(',', $items) . ']';
        }
        ksort($value, SORT_STRING);
        $items = array();
        foreach ($value as $key => $item) {
            $items[] = json_encode((string)$key, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)
                . ':' . $this->canonicalJson($item);
        }
        return '{' . implode(',', $items) . '}';
    }

    private function value(array $data, $key, $default)
    {
        return array_key_exists($key, $data) && $data[$key] !== null ? $data[$key] : $default;
    }

    private function joinSortedFields(array $fields)
    {
        ksort($fields, SORT_STRING);
        $items = array();
        foreach ($fields as $key => $value) $items[] = $key . '=' . $value;
        return implode('&', $items);
    }

    private function text($value)
    {
        return trim((string)$value);
    }

    private function decimal($value)
    {
        if ($value === '') return '';
        if (!is_string($value) || !preg_match('/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/', trim($value))) {
            throw new RequestBuilderException('decimal fields must be positive plain decimal strings');
        }
        return trim($value);
    }

    private function requiredOrderNo($value)
    {
        $normalized = $this->text($value);
        if ($normalized === '' || strlen($normalized) > 64) {
            throw new RequestBuilderException('outOrderNo is required and must be <= 64 characters');
        }
        return $normalized;
    }

    private function requiredPayAmount($value)
    {
        $normalized = $this->decimal($value);
        if ($normalized === '' || !preg_match('/[1-9]/', $normalized)) {
            throw new RequestBuilderException('payAmount must be greater than 0');
        }
        return $normalized;
    }

    private function plainNumber($value)
    {
        if (!is_finite($value)) throw new RequestBuilderException('attach contains a non-finite number');
        if ($value == 0) return '0';
        $encoded = strtolower(json_encode($value));
        if (strpos($encoded, 'e') === false) return $encoded;
        list($coefficient, $exponentText) = explode('e', ltrim($encoded, '-'));
        $sign = $encoded[0] === '-' ? '-' : '';
        $exponent = intval($exponentText);
        $point = strpos($coefficient, '.');
        $integerLength = $point === false ? strlen($coefficient) : $point;
        $digits = str_replace('.', '', $coefficient);
        $position = $integerLength + $exponent;
        if ($position <= 0) return $sign . '0.' . str_repeat('0', -$position) . $digits;
        if ($position >= strlen($digits)) return $sign . $digits . str_repeat('0', $position - strlen($digits));
        return $sign . substr($digits, 0, $position) . '.' . substr($digits, $position);
    }

    private function hmac($payload)
    {
        $this->assertConfigured();
        return hash_hmac(self::SIGN_ALGO, $payload, $this->apiSecret);
    }

    private function assertConfigured()
    {
        if ($this->merchantNo === '' || $this->apiSecret === '') {
            throw new RequestBuilderException('merchantNo / apiSecret not configured');
        }
    }
}
