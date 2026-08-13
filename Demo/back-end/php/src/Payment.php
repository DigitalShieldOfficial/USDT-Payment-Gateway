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
     * - @var string payAmount: Amount to pay (required)
     * - @var string outOrderNo: Order ID in your system (auto-generated if empty)
     * - @var int|string timestamp: Milliseconds (auto-generated if empty)
     * - @var string productPrice: Product price
     * - @var string productPriceCurrency: Product price currency (e.g. USD)
     * - @var string productId: Product ID
     * @return string Cashier redirect URL
     * @throws RequestBuilderException
     */
    public function createOrder(array $data)
    {
        if (!isset($data['payAmount']) || $data['payAmount'] === ''
            || !is_numeric($data['payAmount'])
            || $data['payAmount'] < 0.0000000001) {
            throw new RequestBuilderException('payAmount must be numeric and >= 0.0000000001');
        }

        $payAmount = (float)$data['payAmount'];
        $outOrderNo = (isset($data['outOrderNo']) && $data['outOrderNo'] !== '')
            ? $data['outOrderNo']
            : $this->millis();
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
}
