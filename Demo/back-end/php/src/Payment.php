<?php

namespace DSPay\Api;

final class Payment
{
    /**
     * @var RequestBuilder
     */
    private $requestBuilder;

    /**
     * @param string $merchantNo
     * @param string $apiSecret
     */
    public function __construct($merchantNo, $apiSecret)
    {
        $this->requestBuilder = new RequestBuilder($merchantNo, $apiSecret);
    }

    /**
     * Create order: sign params and build the cashier redirect URL.
     *
     * @param array $data
     * - @var string payAmount: Positive plain decimal amount with at most 2 decimal
     *   places (required; never float/scientific notation). Stablecoins use 6
     *   decimals; DSPay reserves the remaining 4 for its order suffix.
     * - @var string outOrderNo: Order ID in your system (required, non-blank, max 64 characters)
     * - @var int|string timestamp: Milliseconds (auto-generated if empty)
     * - @var string productPrice: Product price
     * - @var string productPriceCurrency: Product price currency (e.g. USD)
     * - @var string productId: Product ID
     * @return string Cashier redirect URL
     * @throws RequestBuilderException
     */
    public function createOrder(array $data)
    {
        $payAmount = $this->normalizePayAmount(
            isset($data['payAmount']) ? $data['payAmount'] : null
        );

        if (!isset($data['outOrderNo']) || !is_scalar($data['outOrderNo'])
            || trim((string)$data['outOrderNo']) === ''
            || strlen(trim((string)$data['outOrderNo'])) > 64) {
            throw new RequestBuilderException(
                'outOrderNo is required, must not be blank, and must be <= 64 characters'
            );
        }

        $outOrderNo = trim((string)$data['outOrderNo']);
        $timestamp = (isset($data['timestamp']) && $data['timestamp'] !== '')
            ? $data['timestamp']
            : $this->millis();

        $signature = $this->requestBuilder->signOrder($outOrderNo, $payAmount, $timestamp);

        $params = array(
            'merchantNo' => $this->requestBuilder->getMerchantNo(),
            'outOrderNo' => $outOrderNo,
            'payAmount' => $payAmount,
            'timestamp' => $timestamp,
            'signature' => $signature,
        );

        foreach (array('productPrice', 'productPriceCurrency', 'productId') as $opt) {
            if (isset($data[$opt]) && $data[$opt] !== '') {
                $params[$opt] = $data[$opt];
            }
        }

        return $this->requestBuilder->buildCashierUrl($params);
    }

    /**
     * Verify DSPay callback signature.
     *
     * @param string $rawBody Raw request body (must be raw, not re-serialized)
     * @param string $signature X-DSPay-Signature header value
     * @return bool
     */
    public function verifyCallback($rawBody, $signature)
    {
        return $this->requestBuilder->verifyCallback($rawBody, $signature);
    }

    /**
     * @return int Current time in milliseconds.
     */
    private function millis()
    {
        return intval(microtime(true) * 1000);
    }

    /**
     * Keep payAmount as a string to preserve precision and signed bytes.
     * Merchants use at most 2 decimal places; DSPay reserves the remaining 4.
     *
     * @param mixed $value
     * @return string
     * @throws RequestBuilderException
     */
    private function normalizePayAmount($value)
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
}
