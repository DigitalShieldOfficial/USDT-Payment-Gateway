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
        $outOrderNo = $this->normalizeRequiredOrderNo($outOrderNo);
        $payAmount = $this->normalizeRequiredPayAmount($payAmount);

        $canonical = implode('&', array(
            'merchantNo=' . $this->merchantNo,
            'outOrderNo=' . $outOrderNo,
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
     * Validate and normalize required merchant external order ID.
     *
     * @param mixed $value
     * @return string
     * @throws RequestBuilderException
     */
    private function normalizeRequiredOrderNo($value)
    {
        if (!is_scalar($value)) {
            throw new RequestBuilderException(
                'outOrderNo is required, must not be blank, and must be <= 64 characters'
            );
        }

        $normalized = trim((string)$value);
        if ($normalized === '' || strlen($normalized) > 64) {
            throw new RequestBuilderException(
                'outOrderNo is required, must not be blank, and must be <= 64 characters'
            );
        }

        return $normalized;
    }

    /**
     * Preserve required payAmount as a plain decimal string. Never convert it
     * through float. Merchants use at most 2 decimal places; DSPay reserves the
     * remaining 4 stablecoin decimal places for its order suffix.
     *
     * @param mixed $value
     * @return string
     * @throws RequestBuilderException
     */
    private function normalizeRequiredPayAmount($value)
    {
        if (!is_string($value)) {
            throw new RequestBuilderException('payAmount must be a plain decimal string');
        }

        $normalized = trim($value);
        if (!preg_match('/^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,2})?$/', $normalized)) {
            throw new RequestBuilderException(
                'payAmount must be a plain decimal string with at most 2 decimal places; scientific notation is not allowed'
            );
        }
        if (!preg_match('/[1-9]/', $normalized)) {
            throw new RequestBuilderException('payAmount must be greater than 0');
        }

        return $normalized;
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
