<?php
require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\RequestBuilder;

$builder = new RequestBuilder('DSM001', 'demo-secret');
$order = array(
    'merchantNo' => 'DSM001',
    'outOrderNo' => 'PHP-DEMO-001',
    'productPrice' => '0.01',
    'productPriceCurrency' => 'USD',
    'productId' => 'NOVA-LIFETIME-001',
    'attach' => array('customerId' => 'CUST-1001', 'demo' => 'php'),
    'payAmount' => '0.01',
    'allowedPaymentMethods' => array(),
    'returnUrl' => 'http://localhost:3000/payment/return',
    'successRedirectUrl' => 'http://localhost:3000/payment/success',
    'timestamp' => '1787700000000',
);

echo $builder->buildCreatePayload($order) . PHP_EOL;
echo $builder->signCreate($order) . PHP_EOL;
