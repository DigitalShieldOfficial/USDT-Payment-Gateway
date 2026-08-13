<?php

namespace DSPay\Api;

final class RequestBuilder
{
    /**
     * Default DSPay cashier base URL.
     */
    const CASHIER_BASE = "https://cashier.ds.pro/";

    /**
     * Signature algorithm.
     */
    const SIGN_ALGO = 'sha256';

    /**
     * @var string
     */
    private $merchantNo;

    /**
     * @var string
     */
    private $apiSecret;

    /**
     * @param string $merchantNo
     * @param string $apiSecret
     */
    public function __construct($merchantNo, $apiSecret)
    {
        $this->merchantNo = $merchantNo;
        $this->apiSecret = $apiSecret;
    }

    /**
     * Sign create-order params.
     *
     * Canonical string (field order matters):
     * merchantNo -> outOrderNo -> payAmount -> timestamp
     * Then HMAC-SHA256 -> lowercase hex.
     *
     * @param string $outOrderNo
     * @param string $payAmount
     * @param int|string $timestamp
     * @return string
     * @throws RequestBuilderException
     */
    public function signOrder($outOrderNo, $payAmount, $timestamp)
    {
        $this->assertConfigured();

        $canonical = implode('&', array(
            'merchantNo=' . $this->merchantNo,
            'outOrderNo=' . $this->normalizeOptionalParam($outOrderNo),
            'payAmount=' . $payAmount,
            'timestamp=' . $timestamp,
        ));

        return $this->hmacSha256Hex($canonical, $this->apiSecret);
    }

    /**
     * Verify DSPay callback signature.
     *
     * HMAC-SHA256 over the raw body, constant-time compare against
     * the X-DSPay-Signature header value (compared lowercased).
     *
     * @param string $rawBody Raw request body (must be raw, not re-serialized)
     * @param string $signature X-DSPay-Signature header value
     * @return bool
     */
    public function verifyCallback($rawBody, $signature)
    {
        if ($this->apiSecret === null || $this->apiSecret === ''
            || $rawBody === false || $signature === null) {
            return false;
        }

        $expected = $this->hmacSha256Hex($rawBody, $this->apiSecret);
        if ($expected === false || $expected === '') {
            return false;
        }

        return hash_equals($expected, strtolower($signature));
    }

    /**
     * Build the cashier redirect URL from signed params.
     *
     * @param array $params Ordered params (merchantNo, outOrderNo, payAmount, timestamp, signature, ...)
     * @return string
     */
    public function buildCashierUrl(array $params)
    {
        return self::CASHIER_BASE . '?' . http_build_query($params, '', '&', PHP_QUERY_RFC3986);
    }

    /**
     * @return string
     */
    public function getMerchantNo()
    {
        return $this->merchantNo;
    }


    /**
     * @param string $payload
     * @param string $secret
     * @return string
     */
    private function hmacSha256Hex($payload, $secret)
    {
        return hash_hmac(self::SIGN_ALGO, $payload, $secret);
    }

    /**
     * @param string $s
     * @return string
     */
    /**
     * Normalize optional parameter (empty string for null/whitespace).
     *
     * @param string $s
     * @return string
     */
    private function normalizeOptionalParam($s)
    {
        return ($s === null || trim($s) === '') ? '' : trim($s);
    }

    /**
     * @throws RequestBuilderException
     */
    private function assertConfigured()
    {
        if ($this->merchantNo === null || $this->merchantNo === ''
            || $this->apiSecret === null || $this->apiSecret === '') {
            throw new RequestBuilderException(
                'merchantNo / apiSecret not configured; set them before signing.'
            );
        }
    }
}