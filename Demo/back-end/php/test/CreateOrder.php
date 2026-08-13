<?php
require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\Client;

const MERCHANT_NO = 'change-me-to-your-merchantNo';
const API_SECRET  = 'change-me-to-your-apiSecret';

$payment = Client::payment(MERCHANT_NO, API_SECRET);

try {
    $url = $payment->createOrder(array(
        'payAmount'            => '0.01',
        'productPrice'         => '0.01',
        'productPriceCurrency' => 'USD',
        'productId'            => 'NOVA-LIFETIME-001',
    ));

    echo "[CREATE] Success!" . PHP_EOL;
    echo "Redirect URL:" . PHP_EOL . $url . PHP_EOL;
} catch (\DSPay\Api\RequestBuilderException $e) {
    echo "[CREATE] Error: " . $e->getMessage() . PHP_EOL;
}
