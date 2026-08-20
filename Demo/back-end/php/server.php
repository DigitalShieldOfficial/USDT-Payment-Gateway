<?php

require __DIR__ . '/src/bootstrap.php';

use DSPay\Api\Client;
use DSPay\Api\RequestBuilderException;

$merchantNo = getenv('MERCHANT_NO') ?: 'change-me-to-your-merchantNo';
$apiSecret = getenv('API_SECRET') ?: 'change-me-to-your-apiSecret';
$payment = Client::payment($merchantNo, $apiSecret);

function jsonResponse($status, array $body)
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body);
}

function logMessage($message)
{
    error_log('[DSPay PHP Demo] ' . $message);
}

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET' && $path === '/') {
    jsonResponse(200, array(
        'name' => 'DSPay PHP Mock Merchant',
        'createOrder' => 'GET /create',
        'callback' => 'POST /notify',
    ));
    return;
}

if ($method === 'GET' && $path === '/create') {
    try {
        $timestamp = intval(microtime(true) * 1000);
        $url = $payment->createOrder(array(
            'outOrderNo' => 'PHP-' . $timestamp,
            'timestamp' => $timestamp,
            // Stablecoins use 6 decimals. Merchants submit at most 2 decimal
            // places; DSPay reserves the remaining 4 for its suffix.
            'payAmount' => isset($_GET['payAmount']) ? $_GET['payAmount'] : '0.01',
            'productPrice' => isset($_GET['productPrice']) ? $_GET['productPrice'] : '0.01',
            'productPriceCurrency' => isset($_GET['productPriceCurrency']) ? $_GET['productPriceCurrency'] : 'USD',
            'productId' => isset($_GET['productId']) ? $_GET['productId'] : 'NOVA-LIFETIME-001',
        ));

        logMessage('create order, redirect=' . $url);
        header('Location: ' . $url, true, 302);
    } catch (RequestBuilderException $exception) {
        logMessage('create order failed: ' . $exception->getMessage());
        jsonResponse(400, array('code' => 'FAIL', 'msg' => $exception->getMessage()));
    }
    return;
}

if ($method === 'POST' && $path === '/notify') {
    $rawBody = file_get_contents('php://input');
    $signature = isset($_SERVER['HTTP_X_DSPAY_SIGNATURE'])
        ? $_SERVER['HTTP_X_DSPAY_SIGNATURE']
        : '';

    if (!$payment->verifyCallback($rawBody, $signature)) {
        logMessage('callback signature invalid');
        jsonResponse(401, array('code' => 'FAIL', 'msg' => 'signature invalid'));
        return;
    }

    logMessage('callback verified, body=' . $rawBody);
    jsonResponse(200, array('code' => 'SUCCESS', 'msg' => 'ok'));
    return;
}

jsonResponse(404, array(
    'code' => 'NOT_FOUND',
    'msg' => 'unknown path: ' . $method . ' ' . $path,
));
