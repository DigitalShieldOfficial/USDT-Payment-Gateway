<?php

namespace DSPay\Api;

final class Client
{
    /**
     * @param string $merchantNo
     * @param string $apiSecret
     * @return Payment
     */
    public static function payment($merchantNo, $apiSecret)
    {
        return new Payment($merchantNo, $apiSecret);
    }
}