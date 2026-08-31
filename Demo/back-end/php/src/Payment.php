<?php

namespace DSPay\Api;

final class Payment
{
    private $requestBuilder;
    private $baseUrl;

    public function __construct($merchantNo, $apiSecret, $baseUrl = null)
    {
        $this->requestBuilder = new RequestBuilder($merchantNo, $apiSecret);
        $configured = $baseUrl !== null ? $baseUrl : getenv('DSPAY_BASE_URL');
        $this->baseUrl = rtrim((string)$configured, '/');
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
        if ($this->baseUrl === '') throw new RequestBuilderException('DSPAY_BASE_URL is required');
        $json = json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $context = stream_context_create(array('http' => array(
            'method' => 'POST',
            'header' => "Content-Type: application/json\r\nAccept: application/json\r\n",
            'content' => $json,
            'ignore_errors' => true,
            'timeout' => 10,
        )));
        $raw = file_get_contents($this->baseUrl . $path, false, $context);
        $status = 0;
        if (isset($http_response_header[0]) && preg_match('/\s([0-9]{3})\s/', $http_response_header[0], $m)) {
            $status = intval($m[1]);
        }
        $decoded = json_decode($raw === false ? '' : $raw, true);
        if ($status < 200 || $status >= 300 || !is_array($decoded)) {
            throw new RequestBuilderException('DSPay request failed: HTTP ' . $status, $status, $path,
                is_array($decoded) ? $decoded : array('raw' => $raw));
        }
        $resultCode = isset($decoded['code']) ? intval($decoded['code'])
            : (isset($decoded['header']['resultCode']) ? intval($decoded['header']['resultCode']) : 0);
        if ($resultCode !== 0) {
            $message = isset($decoded['message']) ? $decoded['message']
                : (isset($decoded['header']['message']) ? $decoded['header']['message'] : 'DSPay business error');
            throw new RequestBuilderException($message, $resultCode, $path, $decoded);
        }
        // wallet 统一响应格式为 { code, data, header }；兼容无包装的历史响应。
        return array_key_exists('data', $decoded) ? $decoded['data'] : $decoded;
    }

    private function millis()
    {
        return intval(microtime(true) * 1000);
    }
}
