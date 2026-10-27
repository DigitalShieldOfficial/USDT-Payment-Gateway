<?php

namespace DSPay\Api;

final class RequestBuilder
{
    const SIGN_ALGO = 'sha256';

    private $merchantNo;
    private $apiSecret;

    public function __construct($merchantNo, $apiSecret)
    {
        $this->merchantNo = (string)$merchantNo;
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
        if (array_key_exists('productPriceCurrency', $data) && $data['productPriceCurrency'] !== null && !preg_match('/^[\x{0009}-\x{000d}\x{001c}-\x{0020}\x{0085}\x{00a0}\x{1680}\x{2000}-\x{200a}\x{2028}\x{2029}\x{202f}\x{205f}\x{3000}]*$/u', $data['productPriceCurrency'])) $fields['productPriceCurrency'] = $this->text($data['productPriceCurrency']);
        if (array_key_exists('productId', $data) && $data['productId'] !== null && !preg_match('/^[\x{0009}-\x{000d}\x{001c}-\x{0020}\x{0085}\x{00a0}\x{1680}\x{2000}-\x{200a}\x{2028}\x{2029}\x{202f}\x{205f}\x{3000}]*$/u', $data['productId'])) $fields['productId'] = $this->text($data['productId']);
        if (array_key_exists('attach', $data) && $data['attach'] !== null) $fields['attach'] = $this->canonicalJson($data['attach']);
        if (array_key_exists('allowedPaymentMethods', $data) && $data['allowedPaymentMethods'] !== null) $fields['allowedPaymentMethods'] = $this->canonicalMethods($data['allowedPaymentMethods']);
        if (array_key_exists('returnUrl', $data) && $data['returnUrl'] !== null && $data['returnUrl'] !== '') $fields['returnUrl'] = $this->text($data['returnUrl']);
        if (array_key_exists('successRedirectUrl', $data) && $data['successRedirectUrl'] !== null && $data['successRedirectUrl'] !== '') $fields['successRedirectUrl'] = $this->text($data['successRedirectUrl']);
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

    public function verifyCallback($rawBody, $signature, $now = null)
    {
        if (!is_string($signature) || !preg_match('/^[0-9a-fA-F]{64}$/D', $signature)) return false;
        try {
            $body = LosslessJson::parse($rawBody);
            if (!($body instanceof \stdClass) || !isset($body->timestamp) || !($body->timestamp instanceof JsonNumber) || !$body->timestamp->integer) return false;
            $stamp = $body->timestamp->plain;
            // Only current millisecond timestamps can pass this bounded window.
            if ($stamp[0] === '-' || strlen($stamp) > 16) return false;
            if ($now === null) $now = floor(microtime(true) * 1000);
            if (abs((float)$stamp - $now) > 300000) return false;
            return hash_equals($this->hmac($this->buildCallbackPayload(get_object_vars($body))), strtolower($signature));
        } catch (\Exception $error) { return false; }
    }

    public function buildCallbackPayload(array $data)
    {
        $fields = array();
        foreach ($data as $key => $value) {
            if ($key === 'signature' || $value === null) continue;
            $fields[$key] = is_string($value) ? $value : ($value instanceof JsonNumber ? $value->plain : LosslessJson::encode($value));
        }
        return $this->joinSortedFields($fields);
    }

    public function canonicalMethods($methods)
    {
        if (!is_array($methods)) throw new RequestBuilderException('allowedPaymentMethods must be an array');
        if (count($methods) === 0) return '';
        $seen = array();
        $values = array();
        foreach ($methods as $method) {
            if (!is_array($method)) throw new RequestBuilderException('allowedPaymentMethods item must be an object');
            $networkId = $this->text($this->value($method, 'networkId', ''));
            $address = $this->text($this->value($method, 'contractAddress', ''));
            if ($networkId === '' || preg_match('/^[\x{0009}-\x{000d}\x{001c}-\x{0020}\x{0085}\x{00a0}\x{1680}\x{2000}-\x{200a}\x{2028}\x{2029}\x{202f}\x{205f}\x{3000}]*$/u', $networkId) || $address === '' || preg_match('/^[\x{0009}-\x{000d}\x{001c}-\x{0020}\x{0085}\x{00a0}\x{1680}\x{2000}-\x{200a}\x{2028}\x{2029}\x{202f}\x{205f}\x{3000}]*$/u', $address)) throw new RequestBuilderException('Payment method fields must be non-blank strings');
            if (strtolower(substr($address, 0, 2)) === '0x') $address = strtolower($address);
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
        return LosslessJson::encode($value);
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
        return (string)$value;
    }

    private function decimal($value)
    {
        if ($value === '') return '';
        if (!is_string($value) || !preg_match('/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/', $value)) {
            throw new RequestBuilderException('decimal fields must be positive plain decimal strings');
        }
        return $value;
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
