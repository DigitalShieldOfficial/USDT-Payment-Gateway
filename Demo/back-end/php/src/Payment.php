<?php

namespace DSPay\Api;

final class Payment
{
    private $requestBuilder;
    private $baseUrl;
    private $logSecret;

    public function __construct($merchantNo, $apiSecret, $baseUrl = null)
    {
        $this->logSecret = $apiSecret;
        $this->requestBuilder = new RequestBuilder($merchantNo, $apiSecret);
        $configured = $baseUrl !== null ? $baseUrl : getenv('DSPAY_BASE_URL');
        if ($configured === false) $configured = 'https://pay-api.ds.pro';
        $this->baseUrl = self::origin($configured, 'DSPAY_BASE_URL');
    }

    public static function logEvent($event, $fields, $secret = '')
    {
        if (isset($fields["body"]) && is_string($fields["body"])) {
            try { $fields["body"] = LosslessJson::parse($fields["body"]); }
            catch (\Exception $ignored) { /* Keep non-JSON response text for diagnostics. */ }
        }
        $text = LosslessJson::encode($fields);
        $text = preg_replace('/("(?:signature|apiSecret|API_SECRET)"\\s*:\\s*)"(?:\\\\.|[^"\\\\])*"/i', '$1"[REDACTED]"', $text);
        if ($secret !== '') {
            $encoded = json_encode($secret, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
            $text = str_replace(substr($encoded, 1, -1), '[REDACTED]', $text);
        }
        error_log('[' . $event . '] ' . $text);
    }

    public static function origin($configured, $field)
    {
        $url = is_string($configured) ? parse_url($configured) : false;
        if ($url === false || !isset($url['scheme'], $url['host'])
            || !in_array($url['scheme'], array('http', 'https'), true)
            || isset($url['user']) || isset($url['pass']) || isset($url['path'])
            || isset($url['query']) || isset($url['fragment'])
            || (isset($url['port']) && $url['port'] === 0)
            || preg_match('/[\s\x00-\x1f]/', $configured)) {
            throw new RequestBuilderException($field . ' must be an http(s) origin without a trailing slash, path, query, credentials, or whitespace');
        }
        return $configured;
    }

    public function createOrder(array $data)
    {
        $data['merchantNo'] = $this->requestBuilder->getMerchantNo();
        if (!isset($data['timestamp'])) $data['timestamp'] = $this->millis();
        $data['signature'] = $this->requestBuilder->signCreate($data);
        return $this->postJson('/dspay/public/order/create', $data);
    }

    public function queryOrder(array $data)
    {
        $data['merchantNo'] = $this->requestBuilder->getMerchantNo();
        if (!isset($data['timestamp'])) $data['timestamp'] = $this->millis();
        $data['signature'] = $this->requestBuilder->signQuery($data);
        return $this->postJson('/dspay/public/order/query', $data);
    }

    public function verifyCallback($rawBody, $signature)
    {
        return $this->requestBuilder->verifyCallback($rawBody, $signature);
    }

    private function postJson($path, array $body)
    {
        self::logEvent("DSPAY_REQUEST", array("method" => "POST", "url" => $this->baseUrl . $path, "body" => $body), $this->logSecret);
        $json = LosslessJson::encode($body);
        $context = stream_context_create(array('http' => array(
            'method' => 'POST',
            'header' => "Content-Type: application/json\r\nAccept: application/json\r\nUser-Agent: DSPay-PHP-Demo/1.0\r\n",
            'content' => $json,
            'ignore_errors' => true,
            'follow_location' => 0,
            'timeout' => 10,
        )));
        // Capture transport warnings in logs instead of corrupting the JSON response.
        $networkWarnings = array();
        set_error_handler(function ($severity, $message) use (&$networkWarnings) {
            $networkWarnings[] = $message;
            return true;
        }, E_WARNING);
        try {
            $raw = file_get_contents($this->baseUrl . $path, false, $context);
        } finally {
            restore_error_handler();
        }
        if ($raw === false) {
            self::logEvent('DSPAY_ERROR', array('path' => $path, 'errors' => $networkWarnings), $this->logSecret);
            throw new RequestBuilderException('DSPay request failed' . ($networkWarnings ? ': ' . implode('; ', $networkWarnings) : ''), 502, $path);
        }
        // PHP 8.4+ provides a dedicated API. Older versions use a dynamic variable
        // name to avoid PHP 8.5's direct-$http_response_header deprecation while
        // retaining PHP 5.6 compatibility.
        if (function_exists('http_get_last_response_headers')) {
            $responseHeaders = http_get_last_response_headers();
        } else {
            $legacyHeaderVariable = 'http_response_header';
            $responseHeaders = isset($$legacyHeaderVariable) ? $$legacyHeaderVariable : array();
        }
        $status = 0;
        if (isset($responseHeaders[0]) && preg_match('/\s([0-9]{3})\s/', $responseHeaders[0], $m)) {
            $status = intval($m[1]);
        }
        $cfRay = null;
        $contentType = null;
        foreach ($responseHeaders as $header) {
            if (stripos($header, 'CF-Ray:') === 0) $cfRay = trim(substr($header, 7));
            if (stripos($header, 'Content-Type:') === 0) $contentType = trim(substr($header, 13));
        }
        self::logEvent("DSPAY_RESPONSE", array("path" => $path, "status" => $status,
            "cfRay" => $cfRay, "contentType" => $contentType, "body" => $raw), $this->logSecret);
        if ($status < 200 || $status >= 300) {
            throw new RequestBuilderException('DSPay HTTP ' . $status . ($cfRay !== null ? '; CF-Ray: ' . $cfRay : ''), 502, $path);
        }
        try { $decoded = LosslessJson::parse($raw === false ? '' : $raw); }
        catch (\Exception $error) { throw new RequestBuilderException('DSPay request failed or returned invalid JSON', 502, $path); }
        if (!($decoded instanceof \stdClass) || !isset($decoded->code) || !($decoded->code instanceof JsonNumber) || !$decoded->code->integer) {
            throw new RequestBuilderException('DSPay response must contain a top-level integer code', 502, $path);
        }
        if ($decoded->code->plain !== '0') throw new RequestBuilderException('DSPay business error: ' . $decoded->code->plain, 502, $path);
        if (!property_exists($decoded, 'data')) throw new RequestBuilderException('DSPay response is missing data', 502, $path);
        return $decoded->data;
    }

    private function millis()
    {
        return intval(microtime(true) * 1000);
    }
}
